package com.winlator.store;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.core.ImageUtils;
import com.winlator.core.StringUtils;
import com.winlator.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Launch bridge for store integrations (GOG / Epic / Amazon).
 *
 * Presents a container picker dialog after a game is downloaded, writes a
 * .desktop shortcut into the chosen Wine container's desktop directory, and
 * downloads + saves the game's cover art as both the shortcut icon (shown in
 * the Shortcuts grid) and as customCoverArt in [Extra Data].
 *
 * Cover art resolution strategy:
 *   1. Use the store-provided URL (passed in by the caller — Epic gives tall
 *      portrait DieselGameBoxTall ~1200x1600; GOG uses SteamGridDB 600x900
 *      first then falls back to its icon CDN).
 *   2. If the store URL is absent or fails to download, fall back to
 *      SteamGridDB autocomplete → grids API (600x900 portrait cover).
 *
 * Shortcut format (native Winlator .desktop, parsed by
 * {@link com.winlator.container.Shortcut}):
 *   [Desktop Entry]
 *   Name=<game name>
 *   Exec=wine "Z:\<store>_games\<game>\<game>.exe"
 *   Icon=<safeName>
 *   Type=Application
 *   StartupWMClass=explorer
 */
public final class StarLaunchBridge {

    private static final String TAG = "BH_BRIDGE";
    private static final String SGDB_KEY = "cf89227f12c773bb1117b6b109ae1659";

    private StarLaunchBridge() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Show a container picker, write a shortcut, then download cover art.
     *
     * @param activity     calling Activity
     * @param gameName     display name (used as shortcut filename and title)
     * @param exePath      absolute Android path to the .exe (under the RootFS
     *                     root, e.g. {@code <rootfs>/steam_games/...})
     * @param coverArtUrl  URL of the game's cover art image, or null to fall
     *                     back to SteamGridDB
     */
    public static void addToLauncher(Activity activity,
                                     String gameName,
                                     String exePath,
                                     String coverArtUrl) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                ContainerManager manager = new ContainerManager(activity);
                ArrayList<Container> containers = manager.getContainers();

                if (containers == null || containers.isEmpty()) {
                    h.post(() -> Toast.makeText(activity,
                            activity.getString(R.string.store_no_container),
                            Toast.LENGTH_LONG).show());
                    return;
                }

                String[] names = new String[containers.size()];
                for (int i = 0; i < containers.size(); i++) {
                    String n = containers.get(i).getName();
                    names[i] = (n != null && !n.isEmpty()) ? n : activity.getString(R.string.store_container_n, i + 1);
                }

                h.post(() -> new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.store_add_to, gameName))
                        .setItems(names, (dialog, which) ->
                                writeShortcut(activity, containers.get(which),
                                        gameName, exePath, coverArtUrl, h))
                        .setNegativeButton(activity.getString(R.string.store_cancel), null)
                        .show());

            } catch (Exception e) {
                Log.e(TAG, "addToLauncher failed", e);
                h.post(() -> Toast.makeText(activity,
                        activity.getString(R.string.store_containers_error, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }, "store-launcher-picker").start();
    }

    /**
     * Convenience overload — falls back to SteamGridDB for cover art.
     */
    public static void addToLauncher(Activity activity, String gameName, String exePath) {
        addToLauncher(activity, gameName, exePath, null);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private static void writeShortcut(Activity activity,
                                      Container container,
                                      String gameName,
                                      String exePath,
                                      String coverArtUrl,
                                      Handler h) {
        new Thread(() -> {
            try {
                File desktopDir = new File(container.getUserDir(), "Desktop");
                if (!desktopDir.exists() && !desktopDir.mkdirs()) {
                    h.post(() -> Toast.makeText(activity,
                            activity.getString(R.string.store_no_desktop),
                            Toast.LENGTH_LONG).show());
                    return;
                }

                // Sanitise game name → safe filename (dedup like ShortcutsFragment import)
                String safeName = gameName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (safeName.isEmpty()) safeName = "game";

                File shortcutFile = new File(desktopDir, safeName + ".desktop");
                int counter = 2;
                while (shortcutFile.exists()) {
                    shortcutFile = new File(desktopDir, safeName + " (" + (counter++) + ").desktop");
                }
                final String iconName = shortcutFile.getName().replaceFirst("\\.desktop$", "");

                // Wine maps Z: to the RootFS root, so the shortcut target is the
                // Z:-relative form of the absolute exe path (see StorePaths).
                // Native Winlator shortcut format: quoted + single-escaped DOS path so
                // Shortcut's one-pass unescapeDOSPath() yields a valid Z:\... path.
                String dosPath = StorePaths.toWinePath(activity, exePath);
                String content = "[Desktop Entry]\n"
                        + "Name=" + gameName + "\n"
                        + "Exec=wine \"" + StringUtils.escapeDOSPath(dosPath) + "\"\n"
                        + "Icon=" + iconName + "\n"
                        + "Type=Application\n"
                        + "StartupWMClass=explorer\n";

                try (FileWriter fw = new FileWriter(shortcutFile)) {
                    fw.write(content);
                }

                Log.d(TAG, "Wrote shortcut: " + shortcutFile.getPath());

                // Resolve cover art URL: fix protocol-relative, then try store URL,
                // fall back to SteamGridDB if needed.
                String artUrl = normalizeUrl(coverArtUrl);
                if (artUrl == null || artUrl.isEmpty()) {
                    Log.d(TAG, "No store cover art URL — trying SteamGridDB for: " + gameName);
                    artUrl = sgdbFetchCover(gameName);
                }

                if (artUrl != null && !artUrl.isEmpty()) {
                    saveCoverArt(activity, container, shortcutFile, safeName, artUrl);
                } else {
                    Log.d(TAG, "No cover art found for: " + gameName);
                }

                h.post(() -> Toast.makeText(activity,
                        activity.getString(R.string.store_added_shortcuts, gameName),
                        Toast.LENGTH_LONG).show());

            } catch (Exception e) {
                Log.e(TAG, "writeShortcut failed for " + gameName, e);
                h.post(() -> Toast.makeText(activity,
                        activity.getString(R.string.store_shortcut_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
            }
        }, "store-write-shortcut").start();
    }

    /**
     * Downloads cover art from {@code url} and saves it as the shortcut icon
     * (so ShortcutsFragment displays it via the Icon= field).
     *
     * If the store URL fails, a SteamGridDB lookup is attempted automatically
     * before giving up.
     */
    private static void saveCoverArt(Context ctx, Container container,
                                     File shortcutFile, String safeName,
                                     String url) {
        Bitmap bmp = downloadBitmap(url);

        // If store URL failed, try SteamGridDB as last resort
        if (bmp == null) {
            Log.w(TAG, "Store cover art download failed for " + safeName + ", trying SteamGridDB");
            String sgdbUrl = sgdbFetchCover(safeName);
            if (sgdbUrl != null && !sgdbUrl.isEmpty()) {
                bmp = downloadBitmap(sgdbUrl);
            }
        }

        if (bmp == null) {
            Log.w(TAG, "All cover art sources failed for " + safeName);
            return;
        }

        try {
            // Save to icons dir — this is what ShortcutsFragment reads via Icon= field.
            File iconsDir = container.getIconsDir(64);
            if (!iconsDir.exists()) iconsDir.mkdirs();
            File iconFile = new File(iconsDir, safeName + ".png");
            ImageUtils.save(bmp, iconFile, android.graphics.Bitmap.CompressFormat.PNG, 100);
            Log.d(TAG, "Saved icon to: " + iconFile.getPath());

            // Also register via Shortcut.setIcon() so the icon is stored in the
            // standard location and the Icon= entry stays in sync.
            Shortcut shortcut = new Shortcut(container, shortcutFile);
            shortcut.setIcon(bmp);
            Log.d(TAG, "Cover art saved for " + safeName);
        } catch (Exception e) {
            Log.w(TAG, "Cover art save failed for " + safeName + ": " + e.getMessage());
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    /** Prepends https: to protocol-relative URLs (//cdn.example.com/…). */
    private static String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.startsWith("//") ? "https:" + url : url;
    }

    /** Downloads a URL and decodes it as a Bitmap. Returns null on any failure. */
    private static Bitmap downloadBitmap(String url) {
        try {
            Log.d(TAG, "Downloading cover art: " + url);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Cover art HTTP " + code + " for " + url);
                conn.disconnect();
                return null;
            }
            Bitmap bmp;
            try (InputStream is = conn.getInputStream()) {
                bmp = BitmapFactory.decodeStream(is);
            }
            conn.disconnect();
            if (bmp == null) Log.w(TAG, "Cover art decode returned null for " + url);
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "downloadBitmap failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Searches SteamGridDB for a 600x900 portrait cover matching {@code title}.
     * Returns the image URL or an empty string on failure.
     */
    private static String sgdbFetchCover(String title) {
        try {
            String encoded = java.net.URLEncoder.encode(title, "UTF-8");
            String searchJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/search/autocomplete/" + encoded);
            if (searchJson == null) return "";
            JSONArray results = new JSONObject(searchJson).optJSONArray("data");
            if (results == null || results.length() == 0) return "";
            int gameId = results.getJSONObject(0).getInt("id");

            String gridsJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/grids/game/" + gameId
                            + "?dimensions=600x900&mimes=image/jpeg,image/png&limit=1");
            if (gridsJson == null) return "";
            JSONArray grids = new JSONObject(gridsJson).optJSONArray("data");
            if (grids == null || grids.length() == 0) return "";
            String imgUrl = grids.getJSONObject(0).optString("url", "");
            Log.d(TAG, "SteamGridDB cover for \"" + title + "\": " + imgUrl);
            return imgUrl;
        } catch (Exception e) {
            Log.w(TAG, "sgdbFetchCover failed for \"" + title + "\": " + e.getMessage());
            return "";
        }
    }

    private static String httpGet(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Authorization", "Bearer " + SGDB_KEY);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
