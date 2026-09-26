package com.winlator.core;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.container.Drive;
import com.winlator.win32.MSLogFont;
import com.winlator.win32.WinVersions;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

public abstract class WineUtils {
    public static void createDosdevicesSymlinks(Container container, boolean addDriveCDRom) {
        File rootDir = container.getRootDir();
        String dosdevicesPath = (new File(rootDir, ".wine/dosdevices")).getPath();
        File[] files = (new File(dosdevicesPath)).listFiles();
        if (files != null) for (File file : files) if (file.getName().matches("[a-z]:")) file.delete();

        FileUtils.symlink("../drive_c", dosdevicesPath+"/c:");
        FileUtils.symlink("../../../../", dosdevicesPath+"/z:");

        if (addDriveCDRom) {
            File driveX = new File(rootDir, ".wine/drive_x");
            if (!driveX.isDirectory()) {
                driveX.mkdir();
                FileUtils.chmod(driveX, 0771);
            }

            String serial = String.format(Locale.ENGLISH, "%-8x", (int)'X').replace(' ', '0');
            FileUtils.writeString(new File(driveX, ".windows-serial"), serial+"\n");
            FileUtils.symlink("../drive_x", dosdevicesPath+"/x:");
        }

        for (Drive drive : container.drivesIterator()) {
            File linkTarget = new File(drive.path);
            String path = linkTarget.getAbsolutePath();
            if (!linkTarget.isDirectory() && path.startsWith(AppUtils.INTERNAL_STORAGE)) {
                linkTarget.mkdirs();
                FileUtils.chmod(linkTarget, 0771);
            }
            FileUtils.symlink(path, dosdevicesPath+"/"+drive.letter.toLowerCase(Locale.ENGLISH)+":");
        }
    }

    public static void setSystemFont(WineRegistryEditor userRegistry, String faceName) {
        byte[] fontNormalData = (new MSLogFont()).setFaceName(faceName).toByteArray();
        byte[] fontBoldData = (new MSLogFont()).setFaceName(faceName).setWeight(700).toByteArray();
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "CaptionFont", fontBoldData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "IconFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "MenuFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "MessageFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "SmCaptionFont", fontNormalData);
        userRegistry.setHexValues("Control Panel\\Desktop\\WindowMetrics", "StatusFont", fontNormalData);
    }

    public static void applySystemTweaks(Context context, WineInfo wineInfo) {
        File rootDir = RootFS.find(context).getRootDir();

        File userCacheDir = new File(rootDir, RootFS.USER_CACHE_PATH);
        if (!userCacheDir.isDirectory()) userCacheDir.mkdirs();
        File userConfigDir = new File(rootDir, RootFS.USER_CONFIG_PATH);
        if (!userConfigDir.isDirectory()) userConfigDir.mkdirs();

        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");
        File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setStringValue("Software\\Wine\\Drives", "x:", "cdrom");
            registryEditor.setStringValue("Software\\Classes\\.reg", null, "REGfile");
            registryEditor.setStringValue("Software\\Classes\\.reg", "Content Type", "application/reg");
            registryEditor.setStringValue("Software\\Classes\\REGfile\\Shell\\Open\\command", null, "C:\\windows\\regedit.exe /C \"%1\"");

            registryEditor.setStringValue("Software\\Classes\\dllfile\\DefaultIcon", null, "shell32.dll,-154");
            registryEditor.setStringValue("Software\\Classes\\lnkfile\\DefaultIcon", null, "shell32.dll,-30");
            registryEditor.setStringValue("Software\\Classes\\inifile\\DefaultIcon", null, "shell32.dll,-151");

            File corefontsAddedFile = new File(userConfigDir, "corefonts.added");
            if (!corefontsAddedFile.isFile()) {
                setupSystemFonts(registryEditor);
                FileUtils.writeString(corefontsAddedFile, String.valueOf(System.currentTimeMillis()));
            }
        }

        final String[] direct3dLibs = {"d3d8", "d3d9", "d3d10", "d3d10_1", "d3d10core", "d3d11", "d3d12", "d3d12core", "ddraw", "dxgi", "wined3d"};
        final String dllOverridesKey = "Software\\Wine\\DllOverrides";

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            for (String name : direct3dLibs) registryEditor.setStringValue(dllOverridesKey, name, "native,builtin");

            registryEditor.removeKey("Software\\Winlator\\WFM\\ContextMenu\\7-Zip");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Open Archive", "Z:\\opt\\apps\\7-Zip\\7zFM.exe \"%FILE%\"");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Extract Here", "Z:\\opt\\apps\\7-Zip\\7zG.exe x \"%FILE%\" -r -o\"%DIR%\" -y");
            registryEditor.setStringValue("Software\\Winlator\\WFM\\ContextMenu\\7-Zip", "Extract to Folder", "Z:\\opt\\apps\\7-Zip\\7zG.exe x \"%FILE%\" -r -o\"%DIR%\\%BASENAME%\" -y");
            registryEditor.setStringValue("Software\\Wine\\AddonsURL", null, "https://raw.githubusercontent.com/brunodev85/winlator/main/wine_addons/");
            registryEditor.setStringValue("Software\\Wine\\Drivers", "Graphics", "x11");
        }
    }

    /**
     * Checks whether a Wine build can drive the virtual gamepad, i.e. its
     * winebus contains Winlator@Frost's {@code winlator_bus} backend
     * ({@code dlls/winebus.sys/bus_winlator.c}, UDP 127.0.0.1:7949/7947).
     * Stock upstream/TKG/Proton builds lack this backend, so no HID gamepad
     * device is ever enumerated there (joy.cpl stays empty) regardless of
     * prefix DLLs or registry keys.
     */
    public static boolean hasWinlatorGamepadBus(Context context, String wineVersion) {
        try {
            File winebusSo = findWinebusSo(context, wineVersion);
            return winebusSo != null && fileContainsMarker(winebusSo);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static boolean fileContainsMarker(File file) {
        return fileContainsAscii(file, "winlator_bus_init") || fileContainsAscii(file, "could not init Winlator bus");
    }

    private static boolean fileContainsAscii(File file, String markerText) {
        try {
            if (file == null || !file.isFile()) return false;
            byte[] marker = markerText.getBytes("ASCII");
            if (marker.length == 0) return false;
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[65536];
                int read;
                // Small state machine so a marker split across reads is still found.
                int match = 0;
                while ((read = in.read(buf)) != -1) {
                    for (int i = 0; i < read; i++) {
                        byte b = buf[i];
                        match = (b == marker[match]) ? match + 1 : ((b == marker[0]) ? 1 : 0);
                        if (match == marker.length) return true;
                    }
                }
            }
            return false;
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * Detects the 9.x-era Winlator@Frost XInput: the PE DLL talks UDP straight to
     * the app (ws2_32 import, single controller) instead of enumerating
     * HID/XI_ gamepad devices via setupapi like current builds do. Such a
     * DLL can never see backend-created devices, so XInput stays dead while
     * DInput (HID-enumerated) works.
     */
    private static boolean isLegacyUdpXinput(File xinputDll) {
        return fileContainsAscii(xinputDll, "ws2_32.dll") && !fileContainsAscii(xinputDll, "setupapi.dll");
    }

    private static File findWinebusSo(Context context, String wineVersion) {
        File rootDir = RootFS.find(context).getRootDir();
        String winePath;
        if (WineInfo.isMainWineVersion(wineVersion)) {
            winePath = new File(rootDir, "opt/wine").getPath();
        }
        else {
            winePath = WineInfo.fromIdentifier(context, wineVersion).path;
            if (winePath == null) return null;
        }
        // Standard Wine layout has lib/ next to bin/; some builds use lib64/.
        final String[] candidates = {
            "lib/wine/x86_64-unix/winebus.so",
            "lib64/wine/x86_64-unix/winebus.so",
            "lib/wine/i386-unix/winebus.so"
        };
        for (String candidate : candidates) {
            File file = new File(winePath, candidate);
            if (file.isFile() && file.length() > 0) return file;
        }
        return null;
    }

    /**
     * Gives a custom Wine build without Winlator@Frost's gamepad backend a working
     * one. The virtual gamepad is enumerated by the {@code winlator_bus}
     * backend (unix {@code winebus.so} + matching PE drivers); stock
     * upstream/TKG/Proton builds lack it, so joy.cpl stays empty there no
     * matter what the prefix contains. This transplants the matched driver
     * stack from the main build (same source the working default Wine uses):
     * the unix backend into the install plus the PE trio
     * (winebus/winehid/winexinput.sys) into both the install (so wineboot
     * regenerates working prefixes) and the live prefix (what loads at
     * boot). Additionally, pre-10 builds whose XInput PE DLLs still speak
     * UDP directly (ws2_32 import, no HID enumeration — DInput works there
     * but XInput never sees backend devices) get the HID-based XInput set.
     * Patched builds are never touched. Stock files are backed up as
     * {@code *.winlator-stock} on first replacement, and every step is
     * content-compared so it is idempotent across launches.
     *
     * @return true if any file was changed.
     */
    /** Major version number parsed from a wine identifier, or -1 when unknown. */
    static int customWineMajorVersion(Context context, String wineVersion) {
        try {
            String version = WineInfo.fromIdentifier(context, wineVersion).version;
            if (version == null) return -1;
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < version.length() && Character.isDigit(version.charAt(i)); i++) {
                digits.append(version.charAt(i));
            }
            return digits.length() > 0 ? Integer.parseInt(digits.toString()) : -1;
        }
        catch (Exception e) {
            return -1;
        }
    }

    public static boolean ensureCustomWineGamepadStack(Context context, Container container) {
        if (context == null || container == null || WineInfo.isMainWineVersion(container.getWineVersion())) return false;
        try {
            // The transplanted 10.10 driver stack is ABI-compatible with the
            // 9.x-10.x winebus (verified in the field); 11.x reworked the bus
            // device descriptors, so transplanting there segfaults instead of
            // helping. Unknown versions are skipped rather than risked.
            int major = customWineMajorVersion(context, container.getWineVersion());
            if (major < 9 || major > 10) {
                android.util.Log.i("WineGamepad", "Skipping driver transplant for "
                    + container.getWineVersion() + ": unsupported major " + major);
                return false;
            }
            File rootDir = RootFS.find(context).getRootDir();
            File srcLibWine = new File(rootDir, "opt/wine/lib/wine");
            if (!srcLibWine.isDirectory()) return false;

            String winePath = WineInfo.fromIdentifier(context, container.getWineVersion()).path;
            if (winePath == null) return false;
            File wineRoot = new File(winePath);
            boolean changed = false;

            // 1. Backend stack, only for builds that lack it. Never touch a
            // build that already enumerates the virtual gamepad itself.
            File srcUnixSo = new File(srcLibWine, "x86_64-unix/winebus.so");
            if (!hasWinlatorGamepadBus(context, container.getWineVersion()) && fileContainsMarker(srcUnixSo)) {
                // 1a. Unix backend lives in the install, never in the prefix.
                File standardTarget = new File(wineRoot, "lib/wine/x86_64-unix/winebus.so");
                File lib64Target = new File(wineRoot, "lib64/wine/x86_64-unix/winebus.so");
                if (transplantFile(srcUnixSo, standardTarget)) changed = true;
                if (lib64Target.isFile() && transplantFile(srcUnixSo, lib64Target)) changed = true;

                // 1b. Matching PE drivers in the install (source for wineboot) ...
                final String[] archDirs = {"x86_64-windows", "i386-windows"};
                final String[] drivers = {"winebus.sys", "winehid.sys", "winexinput.sys"};
                for (String arch : archDirs) {
                    for (String driver : drivers) {
                        File src = new File(srcLibWine, arch + "/" + driver);
                        if (!src.isFile()) continue;
                        if (transplantFile(src, new File(wineRoot, "lib/wine/" + arch + "/" + driver))) changed = true;
                    }
                }

                // 1c. ... and in the live prefix (what actually loads at boot).
                File driversDir = new File(container.getRootDir(), ".wine/drive_c/windows/system32/drivers");
                for (String driver : drivers) {
                    File src = new File(srcLibWine, "x86_64-windows/" + driver);
                    if (!src.isFile()) continue;
                    if (transplantFile(src, new File(driversDir, driver))) changed = true;
                }
            }

            // 2. HID-based XInput PE files, only when the build ships the
            // legacy UDP-based ones (9.x era): those ignore HID/XI_ devices
            // entirely, so XInput stays dead while DInput works. New-arch
            // builds keep their own files.
            File installXinput = new File(wineRoot, "lib/wine/x86_64-windows/xinput1_3.dll");
            if (!installXinput.isFile()) installXinput = new File(wineRoot, "lib/wine/i386-windows/xinput1_3.dll");
            if (isLegacyUdpXinput(installXinput)) {
                final String[] xinputDlls = {"xinput1_1.dll", "xinput1_2.dll", "xinput1_3.dll",
                    "xinput1_4.dll", "xinput9_1_0.dll", "xinputuap.dll"};
                File prefixSystem32 = new File(container.getRootDir(), ".wine/drive_c/windows/system32");
                File prefixSyswow64 = new File(container.getRootDir(), ".wine/drive_c/windows/syswow64");
                for (String dll : xinputDlls) {
                    File src64 = new File(srcLibWine, "x86_64-windows/" + dll);
                    if (src64.isFile()) {
                        if (transplantFile(src64, new File(wineRoot, "lib/wine/x86_64-windows/" + dll))) changed = true;
                        if (transplantFile(src64, new File(prefixSystem32, dll))) changed = true;
                    }
                    File src32 = new File(srcLibWine, "i386-windows/" + dll);
                    if (src32.isFile()) {
                        if (transplantFile(src32, new File(wineRoot, "lib/wine/i386-windows/" + dll))) changed = true;
                        if (transplantFile(src32, new File(prefixSyswow64, dll))) changed = true;
                    }
                }
            }
            if (changed) {
                android.util.Log.i("WineGamepad", "Transplanted gamepad stack into " + container.getWineVersion());
            }
            return changed;
        }
        catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha256(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[32768];
            int read;
            while ((read = in.read(buf)) != -1) md.update(buf, 0, read);
        }
        return md.digest();
    }

    private static boolean transplantFile(File src, File dst) {
        if (src == null || dst == null || !src.isFile()) return false;
        try {
            if (dst.isFile()) {
                try {
                    if (java.util.Arrays.equals(sha256(src), sha256(dst))) return false;
                }
                catch (Exception e) {
                    return false;
                }
                File backup = new File(dst.getPath() + ".winlator-stock");
                if (!backup.isFile()) FileUtils.copy(dst, backup);
            }
            else {
                File parent = dst.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
            }
            return FileUtils.copy(src, dst);
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * Repairs service states that hide every gamepad from joy.cpl: the
     * winebus/winehid drivers plus PlugPlay must be enabled for HID device
     * enumeration. Restores their wine.inf defaults when a previous
     * ESSENTIAL/AGGRESSIVE startup pass disabled them (Start=4).
     *
     * @return true if any value was changed.
     */
    public static boolean healGamepadServices(Container container) {
        if (container == null) return false;
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        if (!systemRegFile.isFile()) return false;
        final String[][] services = {{"winebus", "3"}, {"winehid", "3"}, {"PlugPlay", "2"}};
        boolean changed = false;
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setCreateKeyIfNotExist(false);
            String controlSetPath = registryEditor.getSymlinkValue("System\\CurrentControlSet", "SymbolicLinkValue");
            if (controlSetPath == null) controlSetPath = "System\\CurrentControlSet";
            for (String[] service : services) {
                Integer start = registryEditor.getDwordValue(controlSetPath + "\\Services\\" + service[0], "Start", null);
                if (start != null && start == 4) {
                    registryEditor.setDwordValue(controlSetPath + "\\Services\\" + service[0], "Start", Integer.parseInt(service[1]));
                    changed = true;
                }
            }
        }
        catch (Exception e) {
            return changed;
        }
        return changed;
    }

    /**
     * Removes stale {@code "override"} values under
     * {@code Software\Wine\DirectInput\Joysticks}. Upstream Wine hides any
     * gamepad listed there with that value from DirectInput/XInput
     * enumeration, so a pattern shipping such entries makes the virtual
     * gamepad invisible even though the winebus device exists.
     *
     * @return true if any value was removed.
     */
    public static boolean clearHiddenJoystickOverrides(Container container) {
        if (container == null) return false;
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        if (!userRegFile.isFile()) return false;
        try {
            String content = FileUtils.readString(userRegFile);
            if (content == null || content.isEmpty()) return false;
            final String keyHeader = "[Software\\\\Wine\\\\DirectInput\\\\Joysticks]";
            String[] lines = content.split("\n", -1);
            StringBuilder out = new StringBuilder(content.length());
            boolean inSection = false;
            boolean changed = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("[")) {
                    // Key headers carry a trailing timestamp ("...] 1758569987").
                    inSection = line.equals(keyHeader) || line.startsWith(keyHeader + " ");
                    out.append(line);
                }
                else if (inSection && line.matches("^\"[^\"]+\"=\"override\"$")) {
                    changed = true;
                    continue;
                }
                else {
                    out.append(line);
                }
                if (i < lines.length - 1) out.append("\n");
            }
            if (changed) FileUtils.writeString(userRegFile, out.toString());
            return changed;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static void changeBrowsersRegistryKey(Container container, boolean useAndroidBrowser) {
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (useAndroidBrowser) {
                registryEditor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\winhandler.exe /url");
            }
            else registryEditor.setStringValue("Software\\Wine\\WineBrowser", "Browsers", "C:\\windows\\system32\\iexplore.exe");
        }
    }

    public static void overrideWinComponentDlls(Context context, Container container, String wincomponents) {
        final String dllOverridesKey = "Software\\Wine\\DllOverrides";
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"));

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                for (int i = 0; i < dlnames.length(); i++) {
                    String dlname = dlnames.getString(i);
                    if (useNative) {
                        registryEditor.setStringValue(dllOverridesKey, dlname, "native,builtin");
                    }
                    else registryEditor.removeValue(dllOverridesKey, dlname);
                }
            }
        }
        catch (JSONException e) {}
    }

    public static void setWinComponentRegistryKeys(File systemRegFile, String identifier, boolean useNative) {
        if (identifier.equals("directsound")) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                final String key64 = "Software\\Classes\\CLSID\\{083863F1-70DE-11D0-BD40-00A0C911CE86}\\Instance\\{E30629D1-27E5-11CE-875D-00608CB78066}";
                final String key32 = "Software\\Classes\\Wow6432Node\\CLSID\\{083863F1-70DE-11D0-BD40-00A0C911CE86}\\Instance\\{E30629D1-27E5-11CE-875D-00608CB78066}";

                if (useNative) {
                    registryEditor.setStringValue(key32, "CLSID", "{E30629D1-27E5-11CE-875D-00608CB78066}");
                    registryEditor.setHexValue(key32, "FilterData", "02000000000080000100000000000000307069330200000000000000010000000000000000000000307479330000000038000000480000006175647300001000800000aa00389b710100000000001000800000aa00389b71");
                    registryEditor.setStringValue(key32, "FriendlyName", "Wave Audio Renderer");

                    registryEditor.setStringValue(key64, "CLSID", "{E30629D1-27E5-11CE-875D-00608CB78066}");
                    registryEditor.setHexValue(key64, "FilterData", "02000000000080000100000000000000307069330200000000000000010000000000000000000000307479330000000038000000480000006175647300001000800000aa00389b710100000000001000800000aa00389b71");
                    registryEditor.setStringValue(key64, "FriendlyName", "Wave Audio Renderer");
                }
                else {
                    registryEditor.removeKey(key32);
                    registryEditor.removeKey(key64);
                }
            }
        }
        else if (identifier.equals("wmdecoder")) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                if (useNative) {
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{2EEB4ADF-4578-4D10-BCA7-BB955F56320A}\\InprocServer32", null, "C:\\windows\\syswow64\\wmadmod.dll");
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{82D353DF-90BD-4382-8BC2-3F6192B76E34}\\InprocServer32", null, "C:\\windows\\syswow64\\wmvdecod.dll");
                }
                else {
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{2EEB4ADF-4578-4D10-BCA7-BB955F56320A}\\InprocServer32", null, "C:\\windows\\syswow64\\winegstreamer.dll");
                    registryEditor.setStringValue("Software\\Classes\\Wow6432Node\\CLSID\\{82D353DF-90BD-4382-8BC2-3F6192B76E34}\\InprocServer32", null, "C:\\windows\\syswow64\\winegstreamer.dll");
                }
            }
        }
    }

    public static void updateWineprefix(Context context, final Callback<Integer> terminationCallback) {
        RootFS rootFS = RootFS.find(context);
        final File rootDir = rootFS.getRootDir();
        File tmpDir = rootFS.getTmpDir();
        if (!tmpDir.isDirectory()) tmpDir.mkdir();

        FileUtils.writeString(new File(rootDir, RootFS.WINEPREFIX+"/.update-timestamp"), "0\n");

        EnvVars envVars = new EnvVars();
        envVars.put("WINEPREFIX", rootDir+RootFS.WINEPREFIX);
        envVars.put("WINEDLLOVERRIDES", "mscoree,mshtml=d");

        XEnvironment environment = new XEnvironment(context, rootFS);
        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();
        guestProgramLauncherComponent.setEnvVars(envVars);
        // Match the loader to the active Wine build (classic non-WoW64 builds
        // need wine64 since their bin/wine is a 32-bit loader).
        String wineLoader = "wine";
        try {
            File wineRoot = new File(rootDir, rootFS.getWinePath());
            String identifier = wineRoot.getName();
            if (WineInfo.isMainWineVersion(identifier)) {
                // Main build lives at opt/wine, whose dir name is not a wine
                // identifier; keep the default loader.
            }
            else if (wineRoot.isDirectory()) {
                wineLoader = WineInstaller.getWineLoaderExecutable(wineRoot, identifier);
            }
        }
        catch (Exception ignored) {}
        guestProgramLauncherComponent.setGuestExecutable(wineLoader+" wineboot -u");
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            FileUtils.writeString(new File(rootDir, RootFS.WINEPREFIX+"/.update-timestamp"), "disable\n");
            if (terminationCallback != null) terminationCallback.call(status);
        });
        environment.addComponent(guestProgramLauncherComponent);
        environment.startEnvironmentComponents();
    }

    public static boolean isWineprefixWasUpdated(Container container) {
        File file = new File(container.getRootDir(), "/.wine/.update-timestamp");
        String content = FileUtils.readString(file);
        
        if (!content.startsWith("disable")) {
            content = content.replaceAll("[\r\n]+", "");
            try {
                int updateTimestamp = Integer.parseInt(content);
                if (updateTimestamp != 0) return FileUtils.writeString(file, "disable\n");
            }
            catch (NumberFormatException e) {}
        }
        return false;
    }

    public static void changeServicesStatus(Container container, byte startupSelection) {
        final byte SERVICE_DISABLED = 4;
        final String[] services = {"BITS:3", "Eventlog:2", "HTTP:3", "LanmanServer:3", "NDIS:2", "PlugPlay:2", "RpcSs:3", "scardsvr:3", "Schedule:3", "Spooler:3", "StiSvc:3", "TermService:3", "Winmgmt:3", "wuauserv:3", "winebth:3"};
        final String[] extraServices = {"nsiproxy:2", "MSIServer:3", "FontCache:3"};
        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            registryEditor.setCreateKeyIfNotExist(false);

            String controlSetPath = registryEditor.getSymlinkValue("System\\CurrentControlSet", "SymbolicLinkValue");
            if (controlSetPath == null) controlSetPath = "System\\CurrentControlSet";

            for (String service : services) {
                String name = service.substring(0, service.indexOf(":"));
                // The winebus/winehid/PlugPlay stack enumerates HID gamepads,
                // including the virtual gamepad. Disabling it empties joy.cpl,
                // so it is preserved in every startup mode.
                if (name.equals("winebus") || name.equals("winehid") || name.equals("PlugPlay")) {
                    if (startupSelection == Container.STARTUP_SELECTION_NORMAL)
                        registryEditor.setDwordValue(controlSetPath+"\\Services\\"+name, "Start", Character.getNumericValue(service.charAt(service.length()-1)));
                    continue;
                }
                int value = startupSelection != Container.STARTUP_SELECTION_NORMAL ? SERVICE_DISABLED : Character.getNumericValue(service.charAt(service.length()-1));
                registryEditor.setDwordValue(controlSetPath+"\\Services\\"+name, "Start", value);
            }

            for (String service : extraServices) {
                String name = service.substring(0, service.indexOf(":"));
                int value = startupSelection == Container.STARTUP_SELECTION_AGGRESSIVE ? SERVICE_DISABLED : Character.getNumericValue(service.charAt(service.length()-1));
                registryEditor.setDwordValue(controlSetPath+"\\Services\\"+name, "Start", value);
            }
        }
    }

    public static String unixToDOSPath(String unixPath, Container container) {
        if (unixPath == null || unixPath.isEmpty()) return "";

        if (unixPath.length() >= 2 && unixPath.charAt(1) == ':') {
            return unixPath.replace("/", "\\");
        }

        String dosPath = "";
        String driveLetter = "";

        if (container != null) {
            for (Drive drive : container.drivesIterator()) {
                if (unixPath.startsWith(drive.path)) {
                    driveLetter = drive.letter + ":";
                    dosPath = unixPath.substring(drive.path.length()).replace("/", "\\");
                    break;
                }
            }
        }

        if (dosPath.isEmpty()) {
            int index = unixPath.indexOf("/.wine/dosdevices/");
            if (index != -1) {
                String sub = unixPath.substring(index + 18);
                if (sub.length() >= 2 && sub.charAt(1) == ':') {
                    driveLetter = sub.substring(0, 2).toUpperCase(Locale.ENGLISH);
                    dosPath = sub.substring(2).replace("/", "\\");
                }
            }
        }

        if (dosPath.isEmpty()) {
            int index = unixPath.indexOf("/.wine/drive_c");
            if (index != -1) {
                driveLetter = "C:";
                dosPath = unixPath.substring(index + 14).replace("/", "\\");
            }
        }

        if (driveLetter.isEmpty()) return unixPath.replace("/", "\\");

        if (!dosPath.startsWith("\\")) dosPath += "\\";
        dosPath = driveLetter + StringUtils.removeEndSlash(dosPath);
        if (dosPath.equals(driveLetter)) dosPath += "\\";
        return dosPath;
    }

    public static String dosToUnixPath(String dosPath, Container container) {
        int index = dosPath.indexOf(":");
        if (index == -1) return "";

        String unixPath = "";
        String driveLetter = dosPath.substring(0, index).toUpperCase(Locale.ENGLISH);
        String relativePath = StringUtils.removeStartSlash(dosPath.substring(index+1).replace("\\", "/"));

        if (driveLetter.equals("C")) {
            unixPath = container.getRootDir()+"/.wine/drive_c/"+relativePath;
        }
        else if (driveLetter.equals("Z")) {
            File rootDir = new File(container.getRootDir(), "../../");
            try {
                unixPath = rootDir.getCanonicalPath()+"/"+relativePath;
            }
            catch (IOException e) {}
        }
        else {
            for (Drive drive : container.drivesIterator()) {
                if (drive.letter.equals(driveLetter)) {
                    unixPath = drive.path+"/"+relativePath;
                    break;
                }
            }
        }

        return unixPath;
    }

    public static void setWinVersion(Container container, int winVersionIdx) {
        WinVersions.WinVersion winVersion = WinVersions.getWinVersions()[winVersionIdx];
        String currentBuild = String.valueOf(winVersion.buildNumber);
        String currentVersion = winVersion.currentVersion != null ? winVersion.currentVersion : winVersion.majorVersion+"."+winVersion.minorVersion;

        File systemRegFile = new File(container.getRootDir(), ".wine/system.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
            String key64 = "Software\\Microsoft\\Windows NT\\CurrentVersion";
            String key32 = "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion";

            registryEditor.setStringValue(key32, "CurrentVersion", currentVersion);
            registryEditor.setDwordValue(key32, "CurrentMajorVersionNumber", winVersion.majorVersion);
            registryEditor.setDwordValue(key32, "CurrentMinorVersionNumber", winVersion.minorVersion);
            registryEditor.setStringValue(key32, "CSDVersion", winVersion.csdVersion);
            registryEditor.setStringValue(key32, "CurrentBuild", currentBuild);
            registryEditor.setStringValue(key32, "CurrentBuildNumber", currentBuild);
            registryEditor.setStringValue(key32, "ProductName", "Microsoft "+winVersion.description);

            registryEditor.setStringValue(key64, "CurrentVersion", currentVersion);
            registryEditor.setDwordValue(key64, "CurrentMajorVersionNumber", winVersion.majorVersion);
            registryEditor.setDwordValue(key64, "CurrentMinorVersionNumber", winVersion.minorVersion);
            registryEditor.setStringValue(key64, "CSDVersion", winVersion.csdVersion);
            registryEditor.setStringValue(key64, "CurrentBuild", currentBuild);
            registryEditor.setStringValue(key64, "CurrentBuildNumber", currentBuild);
            registryEditor.setStringValue(key64, "ProductName", "Microsoft "+winVersion.description);
        }
    }

    private static void setupSystemFonts(WineRegistryEditor registryEditor) {
        final String[][] corefonts = {
            {"Andale Mono (TrueType)", "andalemo.ttf"},
            {"Arial (TrueType)", "arial.ttf"},
            {"Arial Black (TrueType)", "ariblk.ttf"},
            {"Arial Bold (TrueType)", "arialbd.ttf"},
            {"Arial Bold Italic (TrueType)", "arialbi.ttf"},
            {"Arial Italic (TrueType)", "ariali.ttf"},
            {"Comic Sans MS (TrueType)", "comic.ttf"},
            {"Comic Sans MS Bold (TrueType)", "comicbd.ttf"},
            {"Courier New (TrueType)", "cour.ttf"},
            {"Courier New Bold (TrueType)", "courbd.ttf"},
            {"Courier New Bold Italic (TrueType)", "courbi.ttf"},
            {"Courier New Italic (TrueType)", "couri.ttf"},
            {"Georgia (TrueType)", "georgia.ttf"},
            {"Georgia Bold (TrueType)", "georgiab.ttf"},
            {"Georgia Bold Italic (TrueType)", "georgiaz.ttf"},
            {"Georgia Italic (TrueType)", "georgiai.ttf"},
            {"Impact (TrueType)", "impact.ttf"},
            {"Times New Roman (TrueType)", "times.ttf"},
            {"Times New Roman Bold (TrueType)", "timesbd.ttf"},
            {"Times New Roman Bold Italic (TrueType)", "timesbi.ttf"},
            {"Times New Roman Italic (TrueType)", "timesi.ttf"},
            {"Trebuchet MS (TrueType)", "trebuc.ttf"},
            {"Trebuchet MS Bold (TrueType)", "trebucbd.ttf"},
            {"Trebuchet MS Bold Italic (TrueType)", "trebucbi.ttf"},
            {"Trebuchet MS Italic (TrueType)", "trebucit.ttf"},
            {"Verdana (TrueType)", "verdana.ttf"},
            {"Verdana Bold (TrueType)", "verdanab.ttf"},
            {"Verdana Bold Italic (TrueType)", "verdanaz.ttf"},
            {"Verdana Italic (TrueType)", "verdanai.ttf"},
            {"Webdings (TrueType)", "webdings.ttf"}
        };

        registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", corefonts);
        registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", corefonts);

        final String[][] wineFonts = {
            {"Marlett (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\marlett.ttf"},
            {"Symbol (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\symbol.ttf"},
            {"Tahoma (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahoma.ttf"},
            {"Tahoma Bold (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\tahomabd.ttf"},
            {"Wingdings (TrueType)", "Z:\\opt\\wine\\share\\wine\\fonts\\wingding.ttf"}
        };

        registryEditor.setStringValues("Software\\Microsoft\\Windows\\CurrentVersion\\Fonts", wineFonts);
        registryEditor.setStringValues("Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts", wineFonts);
    }
}
