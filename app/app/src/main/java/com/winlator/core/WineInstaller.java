package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class WineInstaller {
    public static void generateWineprefix(WineInfo wineInfo, XEnvironment environment) {
        Activity activity = (Activity)environment.getContext();
        RootFS rootFS = environment.getRootFS();
        final File rootDir = rootFS.getRootDir();
        final File installedWineDir = rootFS.getInstalledWineDir();
        rootFS.setWinePath(wineInfo.path);

        final File containerPatternDir = new File(installedWineDir, "/preinstall/container-pattern");
        if (containerPatternDir.isDirectory()) FileUtils.delete(containerPatternDir);
        containerPatternDir.mkdirs();

        File linkFile = new File(rootDir, RootFS.HOME_PATH);
        FileUtils.symlink(containerPatternDir.getPath(), linkFile.getPath());

        GuestProgramLauncherComponent guestProgramLauncherComponent = environment.getComponent(GuestProgramLauncherComponent.class);
        guestProgramLauncherComponent.setBox64Preset(Box64Preset.STABILITY);
        String wineLoader = (wineInfo != null && wineInfo.path != null)
            ? getWineLoaderExecutable(new File(wineInfo.path), wineInfo.identifier()) : "wine";
        guestProgramLauncherComponent.setGuestExecutable(wineLoader+" explorer /desktop=shell,"+ Container.DEFAULT_SCREEN_SIZE+" C:\\windows\\system32\\winecfg.exe");

        final PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        guestProgramLauncherComponent.setTerminationCallback((status) -> Executors.newSingleThreadExecutor().execute(() -> {
            if (status > 0) {
                AppUtils.showToast(activity, R.string.unable_to_install_wine);
                FileUtils.delete(new File(installedWineDir, "/preinstall"));
                AppUtils.restartApplication(activity);
                return;
            }

            preloaderDialog.showOnUiThread(R.string.finishing_installation);
            FileUtils.writeString(new File(rootDir, RootFS.WINEPREFIX+"/.update-timestamp"), "disable\n");

            File userDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/users/xuser");
            File[] userFiles = userDir.listFiles();
            if (userFiles != null) {
                for (File userFile : userFiles) {
                    if (FileUtils.isSymlink(userFile)) {
                        String path = userFile.getPath();
                        userFile.delete();
                        (new File(path)).mkdirs();
                    }
                }
            }

            File containerPatternFile = new File(installedWineDir, "/preinstall/container-pattern-"+wineInfo.fullVersion()+".tzst");
            TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(rootDir, RootFS.WINEPREFIX), containerPatternFile, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);

            if (!containerPatternFile.renameTo(new File(installedWineDir, containerPatternFile.getName())) ||
                    !(new File(wineInfo.path)).renameTo(new File(installedWineDir, wineInfo.identifier()))) {
                containerPatternFile.delete();
            }

            FileUtils.delete(new File(installedWineDir, "/preinstall"));

            preloaderDialog.closeOnUiThread();
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.selectedMenuItemId = R.id.menu_item_settings;
            AppUtils.restartApplication(activity, options);
        }));
    }

    public static void extractWineFileForInstallAsync(Context context, Uri uri, Callback<File> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File destination = new File(RootFS.find(context).getInstalledWineDir(), "/preinstall/wine");
            FileUtils.delete(destination);
            destination.mkdirs();
            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, destination);
            if (!success) FileUtils.delete(destination);
            if (callback != null) callback.call(success ? destination : null);
        });
    }

    public static void findWineVersionAsync(Context context, File wineDir, Callback<WineInfo> callback) {
        if (wineDir == null || !wineDir.isDirectory()) {
            callback.call(null);
            return;
        }
        File[] files = wineDir.listFiles();
        if (files == null || files.length == 0) {
            callback.call(null);
            return;
        }

        if (files.length == 1) {
            if (!files[0].isDirectory()) {
                callback.call(null);
                return;
            }
            wineDir = files[0];
            files = wineDir.listFiles();
            if (files == null || files.length == 0) {
                callback.call(null);
                return;
            }
        }

        File binDir = null;
        for (File file : files) {
            if (file.isDirectory() && file.getName().equals("bin")) {
                binDir = file;
                break;
            }
        }

        if (binDir == null) {
            callback.call(null);
            return;
        }

        File wineBin = new File(binDir, "wine");
        File wineBin64 = new File(binDir, "wine64");

        if (!wineBin.isFile()) {
            callback.call(null);
            return;
        }

        final boolean is64Bit = (wineBin64.isFile() && ElfHelper.is64Bit(wineBin64)) || ElfHelper.is64Bit(wineBin);
        if (!is64Bit) {
            callback.call(null);
            return;
        }

        RootFS rootFS = RootFS.find(context);
        File rootDir = rootFS.getRootDir();
        String wineBinPath = wineBin64.isFile() ? wineBin64.getPath() : wineBin.getPath();
        final String winePath = wineDir.getPath();

        final AtomicReference<WineInfo> wineInfoRef = new AtomicReference<>();
        Callback<String> debugCallback = (line) -> {
            Pattern pattern = Pattern.compile("^wine\\-([0-9\\.]+)\\-?([0-9\\.]+)?", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String version = matcher.group(1);
                String subversion = matcher.groupCount() >= 2 ? matcher.group(2) : null;
                wineInfoRef.set(new WineInfo(version, subversion, winePath));
            }
        };

        ProcessHelper.addDebugCallback(debugCallback);

        File linkFile = new File(rootDir, RootFS.HOME_PATH);
        linkFile.delete();
        FileUtils.symlink(wineDir, linkFile);

        XEnvironment environment = new XEnvironment(context, rootFS);
        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        guestProgramLauncherComponent.setGuestExecutable(wineBinPath+" --version");
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            callback.call(wineInfoRef.get());
            ProcessHelper.removeDebugCallback(debugCallback);
        });
        environment.addComponent(guestProgramLauncherComponent);
        environment.startEnvironmentComponents();
    }

    public static ArrayList<WineInfo> getInstalledWineInfos(Context context) {
        ArrayList<WineInfo> wineInfos = new ArrayList<>();
        wineInfos.add(WineInfo.MAIN_WINE_INFO);
        File installedWineDir = RootFS.find(context).getInstalledWineDir();

        File[] files = installedWineDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith("wine")) wineInfos.add(WineInfo.fromIdentifier(context, name));
            }
        }

        return wineInfos;
    }

    // -------------------------------------------------------------------------
    // Custom Wine (.whp) installation.
    //
    // Unlike the legacy flow above (which boots winecfg inside the guest to
    // install mono/gecko and generate the prefix), custom .whp builds are
    // installed directly:
    //   1. the .whp archive is extracted into a staging dir. Real-world .whp
    //      files (e.g. Wine-for-winlator-official releases) contain the wine
    //      build in a wrapper dir (e.g. "wine-9.2-1-/") PLUS a prebuilt
    //      "container-pattern-<ver>.tzst" next to it, so the wine root is
    //      located by searching for "bin/wine" instead of assuming a fixed
    //      single-folder layout,
    //   2. the wine root is moved to Z:\opt\installed-wine
    //      (<rootfs>/opt/installed-wine/wine-<version>),
    //   3. rootfs_patches.tzst is applied over the rootfs for prefix support,
    //   4. the bundled container-pattern-*.tzst is reused when the package
    //      ships one (it was built for that exact wine version); otherwise
    //      one is generated from the bundled container_pattern.tzst asset so
    //      containers can use the new build.
    // No guest wine boot is performed, so a missing/broken mono/gecko in the
    // custom build cannot abort the installation.
    // -------------------------------------------------------------------------
    private static final String TAG = "WineInstaller";
    /** Name of the marker the bundled container pattern is stashed under. */
    private static final String BUNDLED_PATTERN_MARKER = ".bundled-container-pattern.tzst";
    /** Max directory depth searched when locating "bin/wine" in a package. */
    private static final int WINE_ROOT_SEARCH_DEPTH = 4;

    /** Staging dir used while a custom Wine build is being prepared. */
    private static File getCustomWineStagingDir(Context context) {
        return new File(RootFS.find(context).getInstalledWineDir(), "/preinstall/wine-custom");
    }

    private static void logError(String message) {
        try {
            android.util.Log.e(TAG, message);
        }
        catch (Exception e) {}
    }

    /** One-line summary of a staging dir's top-level members for diagnostics. */
    private static String describeTopLevel(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return "<unreadable>";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(files.length, 20); i++) {
                if (i > 0) sb.append(", ");
                sb.append(files[i].isDirectory() ? files[i].getName() + "/" : files[i].getName());
            }
            if (files.length > 20) sb.append(", ...");
            sb.append("] (").append(files.length).append(" entries)");
            return sb.toString();
        }
        catch (Exception e) {
            return "<error>";
        }
    }

    /**
     * Parses a version hint (usually the .whp file name or the release tag)
     * into a {version, subversion} pair for {@link WineInfo}.
     * Examples: "wine-10.15.whp" -> {"10.15", null},
     * "Wine-10.7-tkg-wlt10-test0.1.whp" -> {"10.7", "tkg-wlt10-test0.1"}.
     */
    public static String[] parseCustomWineVersionHint(String hint) {
        String name = hint != null ? hint.trim() : "";
        if (name.isEmpty()) return new String[]{"custom", null};
        // Strip any path and the archive extension.
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash != -1) name = name.substring(slash + 1);
        name = name.replaceAll("(?i)\\.(whp|tzst|txz|xz|zst|zip|tar)(\\.[^.]+)?$", "");

        Matcher matcher = Pattern.compile("^wine[\\-_]?([0-9]+(?:\\.[0-9]+)*)(?:[\\-_]?(.+))?$", Pattern.CASE_INSENSITIVE).matcher(name);
        if (matcher.find()) {
            String version = matcher.group(1);
            String subversion = matcher.groupCount() >= 2 ? matcher.group(2) : null;
            if (subversion != null) {
                subversion = subversion.trim().toLowerCase(java.util.Locale.ENGLISH)
                        .replaceAll("\\s+", "-").replaceAll("[^a-z0-9.\\-_]", "");
                if (subversion.isEmpty() || subversion.equals("whp")) subversion = null;
            }
            return new String[]{version, subversion};
        }

        String sanitized = name.toLowerCase(java.util.Locale.ENGLISH)
                .replaceAll("\\s+", "-").replaceAll("[^a-z0-9.\\-_]", "");
        if (sanitized.isEmpty()) sanitized = "custom";
        if (sanitized.startsWith("wine-")) sanitized = sanitized.substring(5);
        if (sanitized.isEmpty()) sanitized = "custom";
        // Reuse the generic WineInfo fallback: everything goes into version.
        return new String[]{sanitized, null};
    }

    /**
     * Whether a staged wine root can run on this app at all. Everything here
     * executes under box64 (x86_64) with no box86, so the build must provide
     * 64-bit binaries — either WoW64-style (x86_64 unix libs, like Winlator@Frost
     * and Kron4ek-wow64 builds) or a plain 64-bit loader. Pure 32-bit
     * (x86-only) builds can never start.
     */
    public static boolean is64BitCapableWineRoot(File wineRoot) {
        if (wineRoot == null || !wineRoot.isDirectory()) return false;
        if (new File(wineRoot, "lib/wine/x86_64-unix").isDirectory()) return true;
        if (new File(wineRoot, "lib64/wine/x86_64-unix").isDirectory()) return true;
        try {
            File wineBin = new File(wineRoot, "bin/wine");
            if (!wineBin.isFile()) wineBin = new File(wineRoot, "bin/wine64");
            if (wineBin.isFile() && ElfHelper.is64Bit(wineBin)) return true;
        }
        catch (Exception e) {}
        return false;
    }

    /**
     * Whether an installed wine root looks bootable: the loader, wineserver
     * and the 64-bit ntdll backend must exist and be non-empty, and the unix
     * lib dirs must not be full of 0-byte files (the signature of a tar
     * extraction that dropped hardlink entries). Used to reject a corrupted
     * Kron4ek install at install time and to detect one at container start.
     */
    public static boolean isInstalledWineRootValid(File wineRoot) {
        if (wineRoot == null || !wineRoot.isDirectory()) return false;
        File loader = new File(wineRoot, "bin/wine");
        if (!loader.isFile() || loader.length() == 0) {
            if (!(loader = new File(wineRoot, "bin/wine64")).isFile() || loader.length() == 0) return false;
        }
        File wineserver = new File(wineRoot, "bin/wineserver");
        if (!wineserver.isFile() || wineserver.length() == 0) return false;
        boolean hasNtdll = false;
        final String[] ntdllCandidates = {
            "lib/wine/x86_64-unix/ntdll.so",
            "lib64/wine/x86_64-unix/ntdll.so"
        };
        for (String candidate : ntdllCandidates) {
            File ntdll = new File(wineRoot, candidate);
            if (ntdll.isFile() && ntdll.length() > 0) {
                hasNtdll = true;
                break;
            }
        }
        if (!hasNtdll) return false;
        // Scan the unix lib dirs for 0-byte files. A couple of strays are
        // tolerable, but dozens mean the extractor lost hardlink contents.
        int empty = 0;
        int total = 0;
        final File[] libDirs = {
            new File(wineRoot, "lib/wine/x86_64-unix"),
            new File(wineRoot, "lib64/wine/x86_64-unix")
        };
        for (File dir : libDirs) {
            File[] files = dir.isDirectory() ? dir.listFiles() : null;
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile()) continue;
                total++;
                if (file.length() == 0 && ++empty > 5) return false;
            }
        }
        return total > 0;
    }

    /**
     * Whether the build runs 32-bit Windows apps on its own (WoW64): either
     * named so (Kron4ek) or shipping 32-bit PE files without 32-bit unix
     * libs (Winlator@Frost style). Otherwise only 64-bit apps will run.
     */
    public static boolean hasBuiltInWoW64(File wineRoot, String identifier) {
        if (identifier != null && identifier.toLowerCase(java.util.Locale.ENGLISH).contains("wow64")) return true;
        if (wineRoot == null || !wineRoot.isDirectory()) return false;
        boolean hasI386Windows = new File(wineRoot, "lib/wine/i386-windows").isDirectory();
        boolean hasI386Unix = new File(wineRoot, "lib/wine/i386-unix").isDirectory()
            || new File(wineRoot, "lib64/wine/i386-unix").isDirectory();
        return hasI386Windows && !hasI386Unix;
    }

    /**
     * Major version parsed from a {@link WineInfo} version string such as
     * {@code "11.16"}, or -1 when unknown.
     */
    public static int wineMajorVersion(String version) {
        if (version == null) return -1;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < version.length() && Character.isDigit(version.charAt(i)); i++) {
            digits.append(version.charAt(i));
        }
        if (digits.length() == 0) return -1;
        try {
            return Integer.parseInt(digits.toString());
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Name of the Wine loader binary to execute under box64 for a given build.
     * Everything here runs under box64 with no box86, so a classic (non-WoW64)
     * 64-bit build such as Kron4ek's plain {@code amd64} tarball must be
     * started via {@code wine64}: its {@code bin/wine} is a 32-bit loader that
     * box64 cannot execute and dies instantly with SIGSEGV (status 139).
     * WoW64-style builds (Winlator@Frost, Kron4ek {@code -wow64}) run 32-bit apps
     * through their own thunk, so plain {@code wine} (which is {@code wine64}
     * there) is correct.
     */
    public static String getWineLoaderExecutable(File wineRoot, String identifier) {
        if (wineRoot != null && !hasBuiltInWoW64(wineRoot, identifier)
                && new File(wineRoot, "bin/wine64").isFile()) {
            return "wine64";
        }
        return "wine";
    }

    /** Builds the install identifier, avoiding a clash with the main Wine build. */
    private static String buildCustomWineIdentifier(String version, String subversion) {
        WineInfo info = new WineInfo(version, subversion, null);
        String identifier = info.identifier();
        if (identifier.equals(WineInfo.MAIN_WINE_INFO.identifier())) {
            identifier = identifier + "-imported";
        }
        return identifier;
    }

    /**
     * Locates the wine root inside an extracted package by searching for a
     * "bin/wine" file, breadth-first up to {@link #WINE_ROOT_SEARCH_DEPTH}.
     * Handles flat layouts, single wrapper dirs and deeper nestings such as
     * "opt/wine", even when the package root holds extra files (e.g. a
     * bundled container-pattern-*.tzst next to the wine dir).
     */
    private static File findWineRoot(File stagingDir) {
        if (stagingDir == null || !stagingDir.isDirectory()) return null;
        if ((new File(stagingDir, "bin/wine")).isFile()) return stagingDir;

        java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        File[] top = stagingDir.listFiles();
        if (top == null) return null;
        for (File file : top) {
            if (file.isDirectory() && !FileUtils.isSymlink(file)) {
                queue.add(file);
                depths.add(1);
            }
        }

        while (!queue.isEmpty()) {
            File dir = queue.poll();
            Integer depth = depths.poll();
            if (dir == null || depth == null) continue;
            if ((new File(dir, "bin/wine")).isFile()) return dir;
            if (depth >= WINE_ROOT_SEARCH_DEPTH) continue;
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory() && !FileUtils.isSymlink(child)) {
                    queue.add(child);
                    depths.add(depth + 1);
                }
            }
        }
        return null;
    }

    /**
     * Derives {version, subversion} from an extracted wine dir name such as
     * "wine-9.2-1-" or "Wine-10.7-tkg-wlt10-test0.1". The recombined
     * identifier always round-trips back to the sanitized dir name.
     */
    private static String[] wineDirNameToVersionParts(String dirName) {
        String id = dirName != null ? dirName.trim().toLowerCase(java.util.Locale.ENGLISH) : "";
        id = id.replaceAll("\\s+", "-").replaceAll("[^a-z0-9.\\-_]", "").replaceAll("[-_]+$", "");
        if (!id.startsWith("wine-")) id = "wine-" + id;
        if (id.length() <= 5) id = "wine-custom";
        String full = id.substring(5);
        int dash = full.indexOf('-');
        if (dash <= 0) return new String[]{full, null};
        return new String[]{full.substring(0, dash), full.substring(dash + 1)};
    }

    /** Finds a bundled "container-pattern-*.tzst" shipped inside the package. */
    private static File findBundledContainerPattern(File stagingDir) {
        if (stagingDir == null || !stagingDir.isDirectory()) return null;
        java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        queue.add(stagingDir);
        depths.add(0);
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            Integer depth = depths.poll();
            if (dir == null || depth == null) continue;
            // Never descend into the wine build itself; the pattern (if any)
            // sits next to it at the package top level.
            if (depth > 0 && (new File(dir, "bin/wine")).isFile()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isFile()) {
                    String name = file.getName().toLowerCase(java.util.Locale.ENGLISH);
                    if (name.startsWith("container-pattern-") && name.endsWith(".tzst")) return file;
                }
                else if (file.isDirectory() && !FileUtils.isSymlink(file) && depth < 2) {
                    queue.add(file);
                    depths.add(depth + 1);
                }
            }
        }
        return null;
    }

    private static void logInfo(String message) {
        try {
            android.util.Log.i(TAG, message);
        }
        catch (Exception e) {}
    }

    private static boolean extractWHPArchive(File whpFile, File destination) {
        if (whpFile == null || !whpFile.isFile() || destination == null) return false;
        if (!destination.isDirectory()) destination.mkdirs();
        // .whp builds in the wild are tar payloads (zstd or xz) or plain zips.
        // Clear the destination between attempts so a failed probe cannot leave
        // partial files behind for the next format attempt.
        if (TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, whpFile, destination)
                && !FileUtils.isEmpty(destination)) return true;
        FileUtils.delete(destination);
        destination.mkdirs();
        if (TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, whpFile, destination)
                && !FileUtils.isEmpty(destination)) return true;
        FileUtils.delete(destination);
        destination.mkdirs();
        return ZipUtils.extract(whpFile, destination) && !FileUtils.isEmpty(destination);
    }

    public static void extractCustomWinePackageAsync(Context context, Uri uri, String versionHint, Callback<WineInfo> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File stagingDir = getCustomWineStagingDir(context);
            FileUtils.delete(stagingDir);
            stagingDir.mkdirs();
            // Copy the content URI to a temp .whp first so big archives don't
            // depend on the content resolver staying open during extraction.
            File tmpWhp = new File(context.getCacheDir(), "wine-import-" + System.currentTimeMillis() + ".whp");
            boolean copied = false;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri);
                 java.io.OutputStream out = new java.io.FileOutputStream(tmpWhp)) {
                if (in != null) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    copied = true;
                }
            }
            catch (Exception e) {
                copied = false;
            }
            if (!copied) {
                logError("Custom wine install failed: unable to copy picked file to cache");
                FileUtils.delete(stagingDir);
                FileUtils.delete(tmpWhp);
                if (callback != null) callback.call(null);
                return;
            }
            String hint = versionHint != null ? versionHint : FileUtils.getNameFromUri(context, uri);
            installCustomWHPFileAsync(context, tmpWhp, hint, true, callback);
        });
    }

    public static void installCustomWHPFileAsync(Context context, File whpFile, String versionHint, Callback<WineInfo> callback) {
        installCustomWHPFileAsync(context, whpFile, versionHint, false, callback);
    }

    private static void installCustomWHPFileAsync(Context context, File whpFile, String versionHint, boolean deleteWhpAfterwards, Callback<WineInfo> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String whpName = whpFile != null ? whpFile.getName() : "<null>";
            long whpSize = whpFile != null && whpFile.isFile() ? whpFile.length() : -1;
            File stagingDir = getCustomWineStagingDir(context);
            FileUtils.delete(stagingDir);
            stagingDir.mkdirs();
            boolean success = extractWHPArchive(whpFile, stagingDir);
            if (deleteWhpAfterwards) FileUtils.delete(whpFile);
            if (!success) {
                logError("Custom wine install failed: unable to extract package " + whpName + " (" + whpSize + " bytes)");
                FileUtils.delete(stagingDir);
                if (callback != null) callback.call(null);
                return;
            }
            File wineRoot = findWineRoot(stagingDir);
            if (wineRoot == null) {
                logError("Custom wine install failed: no bin/wine found in package " + whpName
                        + ". Top level: " + describeTopLevel(stagingDir));
                FileUtils.delete(stagingDir);
                if (callback != null) callback.call(null);
                return;
            }
            // Stash a bundled container pattern (if the package ships one) as a
            // marker inside the wine root so it survives the move to its final
            // location in installStagedCustomWine().
            File bundledPattern = findBundledContainerPattern(stagingDir);
            if (bundledPattern != null && !bundledPattern.getParentFile().equals(wineRoot)) {
                File marker = new File(wineRoot, BUNDLED_PATTERN_MARKER);
                FileUtils.delete(marker);
                if (!bundledPattern.renameTo(marker)) {
                    if (FileUtils.copy(bundledPattern, marker)) FileUtils.delete(bundledPattern);
                }
            }
            else if (bundledPattern != null) {
                bundledPattern.renameTo(new File(wineRoot, BUNDLED_PATTERN_MARKER));
            }
            String[] versionParts;
            if (wineRoot.equals(stagingDir)) {
                // Flat layout: no wrapper dir name to derive the version from.
                versionParts = parseCustomWineVersionHint(versionHint != null ? versionHint : whpName);
            }
            else {
                versionParts = wineDirNameToVersionParts(wineRoot.getName());
                // Wrapper dirs without a version (e.g. Kron4ek's plain "wine/")
                // yield junk: fall back to the file/release name hint instead.
                if (versionParts[0] == null || versionParts[0].isEmpty() || !Character.isDigit(versionParts[0].charAt(0))) {
                    versionParts = parseCustomWineVersionHint(versionHint != null ? versionHint : whpName);
                }
            }
            WineInfo stagedInfo = new WineInfo(versionParts[0], versionParts[1], wineRoot.getPath());
            if (callback != null) callback.call(stagedInfo);
        });
    }

    /**
     * Finalizes a staged custom Wine build without booting wine (no mono/gecko
     * install): moves it to /opt/installed-wine, applies rootfs_patches.tzst
     * and generates container-pattern-&lt;version&gt;.tzst from the bundled
     * container_pattern.tzst asset.
     */
    public static void installStagedCustomWineAsync(Context context, WineInfo stagedInfo, Callback<WineInfo> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            WineInfo result = installStagedCustomWine(context, stagedInfo);
            if (callback != null) callback.call(result);
        });
    }

    private static WineInfo installStagedCustomWine(Context context, WineInfo stagedInfo) {
        if (stagedInfo == null || stagedInfo.path == null) {
            logError("Custom wine install failed: staged info is null");
            return null;
        }
        File wineRoot = new File(stagedInfo.path);
        if (!(new File(wineRoot, "bin/wine")).isFile()) {
            logError("Custom wine install failed: staged wine root lost bin/wine: " + stagedInfo.path);
            return null;
        }

        String version = stagedInfo.version;
        String subversion = stagedInfo.subversion;
        String identifier = buildCustomWineIdentifier(version, subversion);
        if (!identifier.equals(new WineInfo(version, subversion, null).identifier())) {
            // Main-wine identifier clash: the "-imported" suffix was appended.
            // Fold it into the subversion so the returned WineInfo round-trips
            // to the real on-disk identifier.
            subversion = (subversion != null ? subversion + "-imported" : "imported");
            identifier = new WineInfo(version, subversion, null).identifier();
        }
        // Derive the pattern suffix from the identifier so the wine dir and its
        // container-pattern file always stay in sync.
        String fullVersion = identifier.startsWith("wine-") ? identifier.substring(5) : new WineInfo(version, subversion, null).fullVersion();

        RootFS rootFS = RootFS.find(context);
        File rootDir = rootFS.getRootDir();
        File installedWineDir = rootFS.getInstalledWineDir();
        if (!installedWineDir.isDirectory()) installedWineDir.mkdirs();

        File targetDir = new File(installedWineDir, identifier);
        if (targetDir.exists()) {
            logError("Custom wine install failed: " + identifier + " is already installed");
            return null;
        }

        // 1. Move the extracted build to Z:\opt\installed-wine\<identifier>.
        boolean moved = wineRoot.renameTo(targetDir);
        if (!moved) {
            targetDir.mkdirs();
            if (!FileUtils.copy(wineRoot, targetDir)) {
                logError("Custom wine install failed: unable to move staged build to " + targetDir.getPath());
                FileUtils.delete(targetDir);
                return null;
            }
        }
        FileUtils.delete(getCustomWineStagingDir(context));
        if (!(new File(targetDir, "bin/wine")).isFile()) {
            logError("Custom wine install failed: installed build lost bin/wine: " + targetDir.getPath());
            FileUtils.delete(targetDir);
            return null;
        }
        // Kron4ek tarballs store duplicated files as hardlinks; an extractor
        // without hardlink support leaves 0-byte DLLs behind and Wine dies
        // instantly (SIGSEGV, status 139) with no guest output. Reject such a
        // broken tree here instead of letting it reach container start.
        if (!isInstalledWineRootValid(targetDir)) {
            logError("Custom wine install failed: extracted tree failed validation (missing/empty system files): "
                    + targetDir.getPath());
            FileUtils.delete(targetDir);
            return null;
        }

        // 2. Patch the rootfs for prefix support (no wine boot, no mono/gecko).
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "rootfs_patches.tzst", rootDir);

        // 3. Provide the container pattern for this build: reuse the
        //    container-pattern-*.tzst bundled in the package when present (it
        //    was built for that exact wine version); otherwise generate one
        //    from the bundled container_pattern.tzst asset so the app system
        //    can create containers with the custom Wine version.
        File containerPatternFile = new File(installedWineDir, "container-pattern-" + fullVersion + ".tzst");
        if (!containerPatternFile.isFile()) {
            File marker = new File(targetDir, BUNDLED_PATTERN_MARKER);
            if (marker.isFile()) {
                if (!marker.renameTo(containerPatternFile)) {
                    if (FileUtils.copy(marker, containerPatternFile)) FileUtils.delete(marker);
                }
                // Never leave the 10s-of-MB archive inside the wine install.
                FileUtils.delete(marker);
            }
        }
        else {
            // A pattern with this name already exists: drop the duplicate.
            FileUtils.delete(new File(targetDir, BUNDLED_PATTERN_MARKER));
        }
        if (!containerPatternFile.isFile()) {
            File tmpStaging = new File(context.getCacheDir(), "container-pattern-" + fullVersion + ".tzst");
            FileUtils.delete(tmpStaging);
            FileUtils.copy(context, "container_pattern.tzst", tmpStaging);
            if (tmpStaging.isFile() && !tmpStaging.renameTo(containerPatternFile)) {
                if (FileUtils.copy(tmpStaging, containerPatternFile)) FileUtils.delete(tmpStaging);
            }
            FileUtils.delete(tmpStaging);
        }
        if (!containerPatternFile.isFile()) {
            logError("Custom wine install failed: unable to provide container-pattern-" + fullVersion + ".tzst");
            FileUtils.delete(targetDir);
            return null;
        }

        WineInfo installed = new WineInfo(version, subversion, targetDir.getPath());
        logInfo("Custom wine installed: " + installed.identifier() + " -> " + targetDir.getPath()
                + " + " + containerPatternFile.getName());
        return installed;
    }

    /** Removes staging leftovers (e.g. when the user cancels the install dialog). */
    public static void discardStagedCustomWine(Context context) {
        Executors.newSingleThreadExecutor().execute(() -> {
            FileUtils.delete(getCustomWineStagingDir(context));
        });
    }
}
