package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;
import android.widget.Spinner;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.xenvironment.RootFS;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public abstract class GeneralComponents {
    public enum InstallMode {DOWNLOAD, FILE, BOTH}
    private static final String INSTALLABLE_COMPONENTS_URL_BRUNO = "https://raw.githubusercontent.com/brunodev85/winlator/main/installable_components/%s";
    private static final String INSTALLABLE_COMPONENTS_URL_WINHUB = "https://raw.githubusercontent.com/winhub-emu/winhub/main/installable_components/%s";
    private static final String INSTALLABLE_COMPONENTS_URL_AFEI = "https://raw.githubusercontent.com/afeimod/winlator-mod/main/10.0/%s";
    public static final String COMPONENT_SOURCE_BRUNO = "bruno";
    public static final String COMPONENT_SOURCE_WINHUB = "winhub";
    public static final String COMPONENT_SOURCE_AFEI = "afei";

    public static String getInstallableComponentsBaseUrl(Context context) {
        String source = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getString("component_source", COMPONENT_SOURCE_BRUNO);
        if (COMPONENT_SOURCE_WINHUB.equals(source)) return INSTALLABLE_COMPONENTS_URL_WINHUB;
        if (COMPONENT_SOURCE_AFEI.equals(source)) return INSTALLABLE_COMPONENTS_URL_AFEI;
        return INSTALLABLE_COMPONENTS_URL_BRUNO;
    }

    public enum Type {
        BOX64, TURNIP, VIRGL, DXVK, VKD3D, WINED3D, SOUNDFONT, ADRENOTOOLS_DRIVER, VEGAS;

        private String lowerName() {
            return name().toLowerCase(Locale.ENGLISH);
        }

        private String title() {
            switch (this) {
                case BOX64:
                    return "Box64";
                case TURNIP:
                    return "Turnip";
                case VIRGL:
                    return "VirGL";
                case DXVK:
                    return "DXVK";
                case VKD3D:
                    return "VKD3D";
                case WINED3D:
                    return "WineD3D";
                case SOUNDFONT:
                    return "SoundFont";
                case ADRENOTOOLS_DRIVER:
                    return "Adrenotools Driver";
                case VEGAS:
                    return "Vegas";
            }

            return "";
        }

        private String assetFolder() {
            switch (this) {
                case BOX64:
                    return "box64";
                case TURNIP:
                case VIRGL:
                    return "graphics_driver";
                case WINED3D:
                case DXVK:
                case VKD3D:
                case VEGAS:
                    return "dxwrapper";
                case SOUNDFONT:
                    return "soundfont";
            }

            return "";
        }

        private File getSource(Context context, String identifier) {
            File componentDir = getComponentDir(this, context);
            switch (this) {
                case SOUNDFONT:
                    return new File(componentDir, identifier+".sf2");
                case ADRENOTOOLS_DRIVER:
                    return new File(componentDir, identifier);
                default:
                    File wcpFile = new File(componentDir, lowerName()+"-"+identifier+".wcp");
                    if (wcpFile.exists()) return wcpFile;
                    return new File(componentDir, lowerName()+"-"+identifier+".tzst");
            }
        }

        public File getDestination(Context context) {
            File rootDir = RootFS.find(context).getRootDir();
            switch (this) {
                case DXVK:
                case VKD3D:
                case WINED3D:
                case VEGAS:
                    return new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
                case SOUNDFONT:
                    File destination = new File(context.getCacheDir(), "soundfont");
                    if (!destination.isDirectory()) destination.mkdirs();
                    return destination;
                default:
                    return rootDir;
            }
        }

        private InstallMode getInstallMode() {
            InstallMode installMode;
            if (this == Type.SOUNDFONT || this == ADRENOTOOLS_DRIVER) {
                installMode = InstallMode.FILE;
            }
            else if (this == Type.WINED3D || this == Type.DXVK || this == Type.VKD3D || this == Type.VEGAS) {
                installMode = InstallMode.BOTH;
            }
            else installMode = InstallMode.DOWNLOAD;
            return installMode;
        }

        private boolean isVersioned() {
            return this == BOX64 || this == TURNIP || this == VIRGL || this == DXVK || this == VKD3D || this == WINED3D || this == VEGAS;
        }
    }

    public static ArrayList<String> getBuiltinComponentNames(Type type) {
        String[] items = new String[0];

        switch (type) {
            case BOX64:
                items = new String[]{"0.3.8", "0.4.0", DefaultVersion.BOX64};
                break;
            case TURNIP:
                items = new String[]{"26.1.0", DefaultVersion.TURNIP};
                break;
            case VIRGL:
                // Both VirGL assets ship in the apk: legacy 23.1.9 + fixed 25.0.7.
                items = DefaultVersion.VIRGL.equals("23.1.9")
                    ? new String[]{"23.1.9"}
                    : new String[]{"23.1.9", DefaultVersion.VIRGL};
                break;
            case DXVK:
                items = new String[]{DefaultVersion.MINOR_DXVK, DefaultVersion.MAJOR_DXVK};
                break;
            case VKD3D:
                items = new String[]{DefaultVersion.VKD3D};
                break;
            case WINED3D:
                items = new String[]{DefaultVersion.WINED3D};
                break;
            case SOUNDFONT:
                items = new String[]{DefaultVersion.SOUNDFONT};
                break;
            case ADRENOTOOLS_DRIVER:
                items = new String[]{"System"};
                break;
            case VEGAS:
                items = new String[]{DefaultVersion.VEGAS};
                break;
        }

        return new ArrayList<>(Arrays.asList(items));
    }

    public static File getComponentDir(Type type, Context context) {
        File file = new File(context.getFilesDir(), "/installed_components/"+type.lowerName());
        if (!file.isDirectory()) file.mkdirs();
        return file;
    }

    public static ArrayList<String> getInstalledComponentNames(Type type, Context context) {
        File componentDir = getComponentDir(type, context);
        ArrayList<String> result = new ArrayList<>();

        String[] names;
        if (componentDir.isDirectory() && (names = componentDir.list()) != null) {
            for (String name : names) result.add(parseDisplayText(type, name));
        }
        return result;
    }

    public static boolean isBuiltinComponent(Type type, String identifier) {
        for (String builtinComponentName : getBuiltinComponentNames(type)) {
            if (builtinComponentName.equalsIgnoreCase(identifier)) return true;
        }
        return false;
    }

    public static String getDefinitivePath(Type type, Context context, String identifier) {
        if (identifier == null || identifier.isEmpty()) return null;
        if (type == Type.SOUNDFONT && isBuiltinComponent(type, identifier)) {
            File destination = type.getDestination(context);
            FileUtils.clear(destination);

            String filename = identifier+".sf2";
            destination = new File(destination, filename);
            FileUtils.copy(context, type.assetFolder()+"/"+filename, destination);
            return destination.getPath();
        }
        else if (type == Type.ADRENOTOOLS_DRIVER) {
            if (isBuiltinComponent(type, identifier)) return null;
            File source = type.getSource(context, identifier);
            if (!source.isDirectory()) return null;

            File[] jsonFiles = source.listFiles((f, name) -> name.toLowerCase(Locale.ENGLISH).endsWith(".json"));
            if (jsonFiles != null && jsonFiles.length > 0) {
                File manifestFile = null;
                for (File f : jsonFiles) {
                    String nameLower = f.getName().toLowerCase(Locale.ENGLISH);
                    if (nameLower.equals("meta.json") || nameLower.equals("manifest.json") || nameLower.equals("driver.json")) {
                        manifestFile = f;
                        break;
                    }
                }
                if (manifestFile == null) manifestFile = jsonFiles[0];

                try {
                    JSONObject manifestJSONObject = new JSONObject(FileUtils.readString(manifestFile));
                    String libraryName = manifestJSONObject.optString("libraryName", "");
                    if (!libraryName.isEmpty()) {
                        File libraryFile = new File(source, libraryName);
                        if (libraryFile.isFile()) return libraryFile.getPath();
                    }
                }
                catch (Exception e) {}
            }

            File[] soFiles = source.listFiles((f, name) -> name.toLowerCase(Locale.ENGLISH).endsWith(".so"));
            if (soFiles != null && soFiles.length > 0) {
                File chosenSo = soFiles[0];
                for (File so : soFiles) {
                    String soname = so.getName().toLowerCase(Locale.ENGLISH);
                    if (soname.contains("freedreno") || soname.contains("adreno")) {
                        chosenSo = so;
                        break;
                    }
                }
                return chosenSo.getPath();
            }

            File[] subDirs = source.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File subDir : subDirs) {
                    File[] subSoFiles = subDir.listFiles((f, name) -> name.toLowerCase(Locale.ENGLISH).endsWith(".so"));
                    if (subSoFiles != null && subSoFiles.length > 0) {
                        return subSoFiles[0].getPath();
                    }
                }
            }
            return null;
        }

        return type.getSource(context, identifier).getPath();
    }

    public static boolean extractWCPFile(File sourceFile, File destinationDir) {
        if (sourceFile == null || !sourceFile.exists()) return false;
        File tempDir = new File(destinationDir.getParentFile(), "wcp_temp_" + System.currentTimeMillis());
        if (!tempDir.isDirectory()) tempDir.mkdirs();

        boolean extracted = ZipUtils.extract(sourceFile, tempDir);
        if (!extracted) {
            extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, sourceFile, tempDir);
        }
        if (!extracted) {
            extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, sourceFile, tempDir);
        }

        if (!extracted) {
            FileUtils.delete(tempDir);
            return false;
        }

        File manifestFile = new File(tempDir, "content.json");
        if (!manifestFile.exists()) manifestFile = new File(tempDir, "manifest.json");

        if (manifestFile.exists()) {
            try {
                JSONObject json = new JSONObject(FileUtils.readString(manifestFile));
                JSONArray files = json.optJSONArray("files");
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject fileObj = files.getJSONObject(i);
                        String sourceRel = fileObj.optString("source");
                        String targetRel = fileObj.optString("target");
                        if (!sourceRel.isEmpty() && !targetRel.isEmpty()) {
                            File src = new File(tempDir, sourceRel);
                            File dst = new File(destinationDir, targetRel);
                            if (src.exists()) {
                                if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                                FileUtils.copy(src, dst);
                            }
                        }
                    }
                }
            }
            catch (Exception e) {}
        }
        else {
            File system32 = new File(tempDir, "system32");
            File syswow64 = new File(tempDir, "syswow64");
            File x64 = new File(tempDir, "x64");
            File x86 = new File(tempDir, "x86");
            File x32 = new File(tempDir, "x32");

            if (system32.exists()) FileUtils.copy(system32, new File(destinationDir, "system32"));
            if (syswow64.exists()) FileUtils.copy(syswow64, new File(destinationDir, "syswow64"));
            if (x64.exists()) FileUtils.copy(x64, new File(destinationDir, "system32"));
            if (x86.exists()) FileUtils.copy(x86, new File(destinationDir, "syswow64"));
            if (x32.exists()) FileUtils.copy(x32, new File(destinationDir, "syswow64"));

            File[] rootFiles = tempDir.listFiles();
            if (rootFiles != null) {
                for (File file : rootFiles) {
                    if (file.isFile() && file.getName().toLowerCase(Locale.ENGLISH).endsWith(".dll")) {
                        File dst32 = new File(destinationDir, "system32/" + file.getName());
                        File dst64 = new File(destinationDir, "syswow64/" + file.getName());
                        if (dst32.getParentFile() != null) dst32.getParentFile().mkdirs();
                        if (dst64.getParentFile() != null) dst64.getParentFile().mkdirs();
                        FileUtils.copy(file, dst32);
                        FileUtils.copy(file, dst64);
                    }
                }
            }
        }

        FileUtils.delete(tempDir);
        return true;
    }

    public static void extractFile(Type type, Context context, String identifier, String defaultVersion) {
        extractFile(type, context, identifier, defaultVersion, null);
    }

    public static void extractFile(Type type, Context context, String identifier, String defaultVersion, TarCompressorUtils.OnExtractFileListener onExtractFileListener) {
        File destination = type.getDestination(context);

        if (identifier == null || identifier.isEmpty()) identifier = defaultVersion;

        if (type == Type.BOX64) {
            // THE 1:1 RULE: the selected version ALWAYS maps to its own asset
            //   0.3.8 -> box64/box64-0.3.8.tzst (contains 0.3.8)
            //   0.4.0 -> box64/box64-0.4.0.tzst (contains 0.4.0)
            //   0.4.4 -> box64/box64-0.4.4.tzst (contains 0.4.4)
            // No manual if/else filename table (a rotated table caused
            // 0.3.8->0.4.0, 0.4.0->0.4.4, 0.4.4->0.3.8). The version number is
            // extracted via regex so every label form ("0.3.8", "Box64 0.3.8",
            // "box64-0.4.0", "v0.4.4") resolves to the same asset.
            String norm = com.winlator.box64.Box64Utils.normalizeBox64Version(identifier);
            if (norm.isEmpty()) norm = com.winlator.box64.Box64Utils.normalizeBox64Version(defaultVersion);
            if (norm.isEmpty()) norm = defaultVersion;
            identifier = norm;
        }

        if (isBuiltinComponent(type, identifier)) {
            String sourcePath = type.assetFolder()+"/"+type.lowerName()+"-"+identifier+".tzst";
            if (type == Type.BOX64) {
                // One log line per install proves the 1:1 rule in any logcat:
                // requested 0.3.8 must always show box64-0.3.8.tzst, etc.
                android.util.Log.i("WinlatorBox64",
                    "Installing box64 " + identifier + " from asset " + sourcePath);
            }
            boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, sourcePath, destination, onExtractFileListener);
            if (type == Type.BOX64) {
                android.util.Log.i("WinlatorBox64",
                    "Box64 asset extract " + sourcePath + " ok=" + ok);
            }
            if (!ok && type == Type.BOX64) {
                // Never leave a stale/mismatched binary behind: fall back to
                // the default asset instead of keeping the previous version
                // while the pref claims the new one (the "xserver still says
                // 0.4.x" confusion).
                android.util.Log.e("WinlatorBox64", "Missing asset " + sourcePath + ", falling back to " + defaultVersion);
                String fallback = type.assetFolder()+"/"+type.lowerName()+"-"+defaultVersion+".tzst";
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, fallback, destination, onExtractFileListener);
            }
        }
        else {
            File componentDir = getComponentDir(type, context);
            File sourceWCP = new File(componentDir, type.lowerName()+"-"+identifier+".wcp");
            File sourceTZST = new File(componentDir, type.lowerName()+"-"+identifier+".tzst");
            boolean success = false;

            if (sourceWCP.exists()) {
                success = extractWCPFile(sourceWCP, destination);
            }
            else if (sourceTZST.exists()) {
                success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, sourceTZST, destination, onExtractFileListener);
            }

            if (!success) {
                String sourcePath = type.assetFolder()+"/"+type.lowerName()+"-"+defaultVersion+".tzst";
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, sourcePath, destination, onExtractFileListener);
            }
        }
    }

    private static String parseDisplayText(Type type, String filename) {
        return filename.replace(type.lowerName()+"-", "").replace(".tzst", "").replace(".wcp", "").replace(".sf2", "");
    }

    private static void downloadComponentFile(final Type type, final String filename, final Spinner spinner, final String defaultItem) {
        final Activity activity = AppUtils.getActivity(spinner.getContext());
        if (activity == null) return;
        File destination = new File(getComponentDir(type, activity), filename);
        if (destination.isFile()) destination.delete();
        HttpUtils.download(activity, String.format(getInstallableComponentsBaseUrl(activity), type.lowerName()+"/"+filename), destination, (success) -> {
            if (success) {
                loadSpinner(type, spinner, parseDisplayText(type, filename), defaultItem);
            }
            else AppUtils.showToast(activity, R.string.a_network_error_occurred);
        });
    }

    private static void installFromPackagedFile(Context context, TarCompressorUtils.Type compressedType, final Type type, File originFile, String identifier, JSONArray filesJSONArray) throws JSONException {
        File componentDir = getComponentDir(type, context);
        File tempDir = new File(componentDir, type.lowerName()+"-"+identifier);
        if (tempDir.isDirectory()) FileUtils.delete(tempDir);
        tempDir.mkdirs();

        for (int i = 0; i < filesJSONArray.length(); i++) {
            JSONObject fileJSONObject = filesJSONArray.getJSONObject(i);
            String target = fileJSONObject.getString("target");
            File file = null;

            if (target.contains("system32")) {
                file = new File(tempDir, "system32/"+FileUtils.getName(target));
            }
            else if (target.contains("syswow64")) {
                file = new File(tempDir, "syswow64/"+FileUtils.getName(target));
            }

            if (file != null) {
                File parent = file.getParentFile();
                parent.mkdirs();
                final String source = fileJSONObject.getString("source");
                TarCompressorUtils.extract(compressedType, originFile, tempDir, (destination, size) -> destination.getPath().endsWith(source) ? destination : null);
            }
        }

        String filename = type.lowerName()+"-"+identifier+".tzst";
        File destination = new File(componentDir, filename);
        TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(tempDir, "/."), destination, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);
        FileUtils.delete(tempDir);
    }

    private static void flattenDirectory(File dir, File targetDir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                flattenDirectory(file, targetDir);
                file.delete();
            } else {
                File dest = new File(targetDir, file.getName());
                if (!file.getAbsolutePath().equals(dest.getAbsolutePath())) {
                    if (dest.exists()) dest.delete();
                    if (!file.renameTo(dest)) {
                        FileUtils.copy(file, dest);
                        file.delete();
                    }
                }
            }
        }
    }

    private static void installAdrenotoolsDriver(MainActivity activity, File sourceFile, String originalFileName, Spinner spinner, String defaultItem) {
        File componentDir = getComponentDir(Type.ADRENOTOOLS_DRIVER, activity);
        File tempDir = new File(activity.getCacheDir(), "adrenotools_temp_" + System.currentTimeMillis());
        if (tempDir.isDirectory()) FileUtils.delete(tempDir);
        tempDir.mkdirs();

        if (!ZipUtils.extract(sourceFile, tempDir)) {
            FileUtils.delete(tempDir);
            AppUtils.showToast(activity, R.string.a_network_error_occurred);
            return;
        }

        File[] topFiles = tempDir.listFiles();
        if (topFiles != null) {
            for (File file : topFiles) {
                if (file.isDirectory()) {
                    flattenDirectory(file, tempDir);
                    file.delete();
                }
            }
        }

        String driverName = "";
        String libraryName = "";
        JSONObject manifestObj = null;

        File[] jsonFiles = tempDir.listFiles((dir, name) -> name.toLowerCase(Locale.ENGLISH).endsWith(".json"));
        if (jsonFiles != null && jsonFiles.length > 0) {
            File bestJson = null;
            for (File f : jsonFiles) {
                String fname = f.getName().toLowerCase(Locale.ENGLISH);
                if (fname.equals("meta.json") || fname.equals("manifest.json") || fname.equals("driver.json")) {
                    bestJson = f;
                    break;
                }
            }
            if (bestJson == null) bestJson = jsonFiles[0];

            try {
                manifestObj = new JSONObject(FileUtils.readString(bestJson));
                driverName = manifestObj.optString("name", "");
                if (driverName.isEmpty()) driverName = manifestObj.optString("title", "");
                if (driverName.isEmpty()) {
                    String ver = manifestObj.optString("driverVersion", manifestObj.optString("version", ""));
                    String author = manifestObj.optString("author", "");
                    if (!ver.isEmpty()) {
                        driverName = (!author.isEmpty() ? author + " " : "") + "Turnip " + ver;
                    }
                }
                libraryName = manifestObj.optString("libraryName", "");
            }
            catch (Exception e) {}
        }

        if (driverName.isEmpty()) {
            driverName = originalFileName != null ? originalFileName.replaceAll("(?i)\\.zip$", "") : "Driver_" + System.currentTimeMillis();
        }

        driverName = driverName.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
        if (driverName.isEmpty()) driverName = "Driver_" + System.currentTimeMillis();

        if (libraryName.isEmpty() || !new File(tempDir, libraryName).isFile()) {
            File[] soFiles = tempDir.listFiles((dir, name) -> name.toLowerCase(Locale.ENGLISH).endsWith(".so"));
            if (soFiles != null && soFiles.length > 0) {
                File chosenSo = soFiles[0];
                for (File so : soFiles) {
                    String soname = so.getName().toLowerCase(Locale.ENGLISH);
                    if (soname.contains("freedreno") || soname.contains("adreno")) {
                        chosenSo = so;
                        break;
                    }
                }
                libraryName = chosenSo.getName();
            }
        }

        if (libraryName.isEmpty()) {
            FileUtils.delete(tempDir);
            AppUtils.showToast(activity, R.string.a_network_error_occurred);
            return;
        }

        try {
            if (manifestObj == null) manifestObj = new JSONObject();
            manifestObj.put("name", driverName);
            manifestObj.put("libraryName", libraryName);
            FileUtils.writeString(new File(tempDir, "meta.json"), manifestObj.toString());
        }
        catch (Exception e) {}

        File destination = new File(componentDir, driverName);
        if (destination.isDirectory()) FileUtils.delete(destination);

        if (!tempDir.renameTo(destination)) {
            destination.mkdirs();
            FileUtils.copy(tempDir, destination);
            FileUtils.delete(tempDir);
        }

        loadSpinner(Type.ADRENOTOOLS_DRIVER, spinner, driverName, defaultItem);
    }

    private static String getFileNameFromUri(Context context, Uri uri) {
        String result = null;
        if (uri != null && uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) result = cursor.getString(nameIndex);
                }
            }
            catch (Exception e) {}
        }
        if (result == null && uri != null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result != null ? result : "imported_file";
    }

    private static void openFileForInstall(final Context context, final Type type, final Spinner spinner, final String defaultItem) {
        final MainActivity activity = (MainActivity)AppUtils.getActivity(context);
        if (activity == null) return;

        activity.setOpenFileCallback((uri) -> {
            if (uri == null) return;

            String fileName = getFileNameFromUri(activity, uri);
            File tempSourceFile = new File(activity.getCacheDir(), "import_temp_" + System.currentTimeMillis() + "_" + fileName);

            try (InputStream inStream = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream outStream = new FileOutputStream(tempSourceFile)) {
                if (inStream == null) return;
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inStream.read(buffer)) != -1) {
                    outStream.write(buffer, 0, bytesRead);
                }
            }
            catch (Exception e) {
                AppUtils.showToast(activity, R.string.a_network_error_occurred);
                return;
            }

            try {
                switch (type) {
                    case SOUNDFONT: {
                        File destination = new File(getComponentDir(type, activity), fileName);
                        if (destination.isFile()) FileUtils.delete(destination);
                        if (FileUtils.copy(tempSourceFile, destination)) loadSpinner(type, spinner, parseDisplayText(type, fileName), defaultItem);
                        break;
                    }
                    case ADRENOTOOLS_DRIVER: {
                        installAdrenotoolsDriver(activity, tempSourceFile, fileName, spinner, defaultItem);
                        break;
                    }
                    default: {
                        if (fileName.toLowerCase(Locale.ENGLISH).endsWith(".wcp")) {
                            String identifier = fileName.replace("vegas-", "").replace("vegas_", "").replace(".wcp", "");
                            File destination = new File(getComponentDir(type, activity), type.lowerName()+"-"+identifier+".wcp");
                            if (destination.isFile()) FileUtils.delete(destination);
                            if (FileUtils.copy(tempSourceFile, destination)) loadSpinner(type, spinner, identifier, defaultItem);
                            break;
                        }

                        TarCompressorUtils.Type compressedType = TarCompressorUtils.Type.ZSTD;
                        byte[] manifestData = TarCompressorUtils.read(compressedType, tempSourceFile, "*.json");
                        if (manifestData == null) manifestData = TarCompressorUtils.read(compressedType = TarCompressorUtils.Type.XZ, tempSourceFile, "*.json");
                        if (manifestData != null) {
                            JSONObject manifestJSONObject = new JSONObject(new String(manifestData));
                            String contentType = manifestJSONObject.optString("type", "").toUpperCase(Locale.ENGLISH);
                            String identifier = StringUtils.parseIdentifier(manifestJSONObject.optString("versionName", ""));
                            JSONArray filesJSONArray = manifestJSONObject.optJSONArray("files");

                            if (contentType.equals(type.name()) && !identifier.isEmpty() && filesJSONArray != null) {
                                installFromPackagedFile(activity, compressedType, type, tempSourceFile, identifier, filesJSONArray);
                                loadSpinner(type, spinner, identifier, defaultItem);
                            }
                        }
                        break;
                    }
                }
            }
            catch (JSONException e) {}
            finally {
                FileUtils.delete(tempSourceFile);
            }
        });

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    private static void showDownloadableListDialog(Type type, final Spinner spinner, final String defaultItem) {
        final Activity activity = AppUtils.getActivity(spinner.getContext());
        if (activity == null) return;
        if (type == Type.VEGAS) {
            VegasReleaseDownloader.showVegasVersionDownloadDialog(activity, spinner, defaultItem);
            return;
        }

        final PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        HttpUtils.download(String.format(getInstallableComponentsBaseUrl(activity), type.lowerName()+"/index.txt"), (content) -> activity.runOnUiThread(() -> {
            preloaderDialog.close();
            if (content != null) {
                if (content.isEmpty()) {
                    AppUtils.showToast(activity, R.string.there_are_no_items_to_download);
                    return;
                }
                final String[] filenames = content.split("\n");
                final String[] items = filenames.clone();
                for (int i = 0; i < items.length; i++) {
                    items[i] = type.title()+" "+parseDisplayText(type, items[i]);
                }

                ContentDialog.showSelectionList(activity, R.string.install_component, items, false, (positions) -> {
                    if (!positions.isEmpty()) downloadComponentFile(type, filenames[positions.get(0)], spinner, defaultItem);
                });
            }
            else AppUtils.showToast(activity, R.string.a_network_error_occurred);
        }));
    }

    public static void initViews(final Type type, View toolbox, final Spinner spinner, final String selectedItem, final String defaultItem) {
        final Context context = spinner.getContext();
        toolbox.findViewWithTag("install").setOnClickListener((v) -> {
            InstallMode installMode = type.getInstallMode();
            switch (installMode) {
                case DOWNLOAD:
                    showDownloadableListDialog(type, spinner, defaultItem);
                    break;
                case FILE:
                    openFileForInstall(context, type, spinner, defaultItem);
                    break;
                case BOTH:
                    PopupMenu popupMenu = new PopupMenu(context, v);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popupMenu.setForceShowIcon(true);
                    popupMenu.inflate(R.menu.open_file_popup_menu);
                    popupMenu.setOnMenuItemClickListener((menuItem) -> {
                        int itemId = menuItem.getItemId();
                        if (itemId == R.id.menu_item_open_file) {
                            openFileForInstall(context, type, spinner, defaultItem);
                        }
                        else if (itemId == R.id.menu_item_download_file) {
                            showDownloadableListDialog(type, spinner, defaultItem);
                        }
                        return true;
                    });
                    popupMenu.show();
                    break;
            }
        });

        toolbox.findViewWithTag("remove").setOnClickListener((v) -> {
            String identifier = spinner.getSelectedItem().toString();

            if (isBuiltinComponent(type, identifier)) {
                AppUtils.showToast(context, R.string.you_cannot_remove_this_component_version);
                return;
            }

            File source = type.getSource(context, identifier);
            if (source.exists()) {
                ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_component_version, () -> {
                    FileUtils.delete(source);
                    loadSpinner(type, spinner, selectedItem, defaultItem);
                });
            }
        });

        loadSpinner(type, spinner, selectedItem, defaultItem);
    }

    public static void loadSpinner(Type type, Spinner spinner, String selectedItem, String defaultItem) {
        if (!AppUtils.isUiThread()) {
            Activity activity = AppUtils.getActivity(spinner.getContext());
            if (activity != null) {
                activity.runOnUiThread(() -> loadSpinner(type, spinner, selectedItem, defaultItem));
                return;
            }
        }

        ArrayList<String> items = getBuiltinComponentNames(type);
        items.addAll(getInstalledComponentNames(type, spinner.getContext()));

        if (type.isVersioned()) {
            items.sort((o1, o2) -> Integer.compare(GPUHelper.vkMakeVersion(o1), GPUHelper.vkMakeVersion(o2)));
        }
        Log.d("WinlatorBox64", "loadSpinner type=" + type + ", items=" + items + ", selectedItem=" + selectedItem);

        spinner.setAdapter(new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_dropdown_item, items));

        if (selectedItem == null || selectedItem.isEmpty() || !AppUtils.setSpinnerSelectionFromValue(spinner, selectedItem)) {
            AppUtils.setSpinnerSelectionFromValue(spinner, defaultItem);
        }
    }
}