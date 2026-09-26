package com.winlator.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for where store games live and how they map to Wine paths.
 *
 * <p>Wine in this project maps {@code Z:} to the RootFS root
 * ({@code <filesDir>/rootfs}, see {@code WineUtils.createDosdevicesSymlinks} which links
 * {@code dosdevices/z:} to {@code containerRoot/.wine/dosdevices/../../../../} =
 * {@code rootfs/}, and {@code WineUtils.dosToUnixPath} which resolves {@code Z:} to
 * {@code containerRoot/../../}). The {@code filesDir/imagefs} tree is a legacy location
 * ({@code RootFS.find()} even tries to rename it away) that Wine cannot see — installing
 * games there and writing {@code Z:\imagefs\...} shortcuts produces Wine's
 * "Path not found" dialog at launch.
 *
 * <p>Canonical layout (all Wine-visible as {@code Z:\<dir>}):
 * <pre>
 *   &lt;filesDir&gt;/rootfs/steam_games/
 *   &lt;filesDir&gt;/rootfs/gog_games/
 *   &lt;filesDir&gt;/rootfs/epic_games/
 *   &lt;filesDir&gt;/rootfs/Amazon/
 * </pre>
 *
 * <p>Installs from older builds may still sit under {@code filesDir/imagefs/...} with
 * shortcuts/DB rows/prefs pointing at {@code Z:\imagefs\...}. {@link #migrateLegacyStores}
 * moves that data into place and rewrites stored paths; {@link #repairStoreShortcuts}
 * rewrites the stale {@code Exec=} lines. Both are idempotent — run them whenever the
 * stores hub is opened.
 */
public final class StorePaths {

    private static final String TAG = "StorePaths";

    public static final String STEAM_DIR  = "steam_games";
    public static final String GOG_DIR    = "gog_games";
    public static final String EPIC_DIR   = "epic_games";
    public static final String AMAZON_DIR = "Amazon";

    private static final String[] ALL_DIRS = {STEAM_DIR, GOG_DIR, EPIC_DIR, AMAZON_DIR};
    private static final String[] PREFS_FILES = {"bh_gog_prefs", "bh_epic_prefs", "bh_amazon_prefs"};

    private StorePaths() {}

    /** Canonical install root for a store dir, e.g. {@code <rootfs>/steam_games}. */
    public static File storeDir(Context ctx, String dirName) {
        return new File(RootFS.find(ctx).getRootDir(), dirName);
    }

    /** Legacy (pre-migration) install root, e.g. {@code <filesDir>/imagefs/steam_games}. */
    public static File legacyStoreDir(Context ctx, String dirName) {
        return new File(new File(ctx.getFilesDir(), "imagefs"), dirName);
    }

    /**
     * Converts an absolute Android path to the Wine path used in shortcuts.
     * Anything under the RootFS root becomes {@code Z:\...}; anything else is
     * returned unchanged.
     */
    public static String toWinePath(Context ctx, String absPath) {
        if (absPath == null || absPath.isEmpty()) return "";
        try {
            String root = RootFS.find(ctx).getRootDir().getAbsolutePath();
            if (absPath.startsWith(root)) {
                String rel = absPath.substring(root.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                return "Z:\\" + rel.replace("/", "\\");
            }
        } catch (Exception e) {
            Log.w(TAG, "toWinePath failed for " + absPath, e);
        }
        return absPath;
    }

    /**
     * Moves legacy {@code filesDir/imagefs/<store>} trees into {@code rootfs/<store>}
     * and rewrites every stored absolute path (prefs + Steam DB) to match.
     * Safe to call repeatedly; does nothing when there is nothing to migrate.
     *
     * @return number of store dirs moved
     */
    public static int migrateLegacyStores(Context ctx) {
        int moved = 0;
        try {
            File imageFs = new File(ctx.getFilesDir(), "imagefs");
            for (String dir : ALL_DIRS) {
                File legacy = new File(imageFs, dir);
                if (!legacy.isDirectory()) continue;
                File target = storeDir(ctx, dir);
                String oldPrefix = legacy.getAbsolutePath();
                String newPrefix = target.getAbsolutePath();
                if (moveDir(legacy, target)) {
                    Log.i(TAG, "Migrated " + oldPrefix + " -> " + newPrefix);
                    moved++;
                } else {
                    Log.w(TAG, "Could not fully move " + oldPrefix);
                }
                rewriteStoredPaths(ctx, oldPrefix, newPrefix);
            }
            // Remove the legacy parent if we emptied it, so RootFS.find() never
            // attempts its doomed imagefs->rootfs rename on our leftovers.
            try {
                String[] rest = imageFs.list();
                if (rest != null && rest.length == 0) {
                    //noinspection ResultOfMethodCallIgnored
                    imageFs.delete();
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "migrateLegacyStores failed", e);
        }
        return moved;
    }

    /**
     * Rewrites stale {@code Z:\imagefs\...} shortcut targets (written by older builds)
     * to {@code Z:\...} in every container's Desktop directory.
     *
     * @return number of .desktop files rewritten
     */
    public static int repairStoreShortcuts(Context ctx) {
        int fixed = 0;
        try {
            ContainerManager manager = new ContainerManager(ctx);
            ArrayList<Container> containers = manager.getContainers();
            if (containers == null) return 0;
            for (Container container : containers) {
                File desktopDir;
                try {
                    desktopDir = new File(container.getUserDir(), "Desktop");
                } catch (Exception e) {
                    continue;
                }
                if (!desktopDir.isDirectory()) continue;
                File[] files = desktopDir.listFiles((d, name) ->
                        name.toLowerCase(Locale.ENGLISH).endsWith(".desktop"));
                if (files == null) continue;
                for (File f : files) {
                    try {
                        String content = FileUtils.readString(f);
                        if (content == null || content.isEmpty()) continue;
                        String repaired = repairExecLine(content);
                        if (!repaired.equals(content)) {
                            FileUtils.writeString(f, repaired);
                            fixed++;
                            Log.i(TAG, "Repaired shortcut: " + f.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not repair " + f.getAbsolutePath(), e);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "repairStoreShortcuts failed", e);
        }
        return fixed;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static boolean moveDir(File src, File dst) {
        try {
            if (!dst.exists()) {
                // Same filesystem: fast atomic rename.
                return src.renameTo(dst);
            }
            // Target exists (partial earlier run): move children that are missing.
            File[] children = src.listFiles();
            if (children == null) return false;
            boolean allOk = true;
            for (File child : children) {
                File dest = new File(dst, child.getName());
                if (dest.exists()) {
                    Log.w(TAG, "Skipping " + child.getAbsolutePath() + " (already exists at target)");
                    allOk = false;
                    continue;
                }
                if (!child.renameTo(dest)) {
                    Log.w(TAG, "Could not move " + child.getAbsolutePath());
                    allOk = false;
                }
            }
            String[] rest = src.list();
            if (rest != null && rest.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
            return allOk;
        } catch (Exception e) {
            Log.w(TAG, "moveDir " + src + " -> " + dst + " failed", e);
            return false;
        }
    }

    private static void rewriteStoredPaths(Context ctx, String oldPrefix, String newPrefix) {
        // SharedPreferences (GOG/Epic/Amazon exe + dir entries, and any cached JSON).
        for (String prefsName : PREFS_FILES) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                Map<String, ?> all = sp.getAll();
                SharedPreferences.Editor editor = sp.edit();
                boolean changed = false;
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        String str = (String) value;
                        if (str.contains(oldPrefix)) {
                            editor.putString(entry.getKey(), str.replace(oldPrefix, newPrefix));
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    editor.apply();
                    Log.i(TAG, "Rewrote stored paths in " + prefsName);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not rewrite prefs " + prefsName, e);
            }
        }
        // Steam SQLite DB (steam_games.install_dir, steam_downloads.install_dir).
        try {
            SteamDatabase.getInstance(ctx).rewriteInstallPaths(oldPrefix, newPrefix);
        } catch (Exception e) {
            Log.w(TAG, "Could not rewrite Steam DB paths", e);
        }
        // Steam detail screens cache GameRows in memory; force a reload next read.
        try {
            SteamRepository.getInstance().invalidateGameCache();
        } catch (Exception ignored) {
            // Repository not initialised yet — nothing cached.
        }
    }

    private static String repairExecLine(String content) {
        String repaired = content;
        // File bytes use doubled backslashes (native escaped Exec= format).
        repaired = repaired.replace("Z:\\\\imagefs\\\\", "Z:\\\\");
        repaired = repaired.replace("Z:\\\\IMAGEFS\\\\", "Z:\\\\");
        // Tolerate single-backslash variants too.
        repaired = repaired.replace("Z:\\imagefs\\", "Z:\\");
        repaired = repaired.replace("Z:\\IMAGEFS\\", "Z:\\");
        return repaired;
    }
}
