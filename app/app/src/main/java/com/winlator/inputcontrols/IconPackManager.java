package com.winlator.inputcontrols;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import com.winlator.core.FileUtils;
import com.winlator.core.StreamUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class IconPackManager {
    public static final String IPK_EXTENSION = "ipk";

    public static File getIconPacksDir(Context context) {
        File dir = new File(context.getFilesDir(), "iconpacks");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static File getPackDir(Context context, String packId) {
        return new File(getIconPacksDir(context), sanitizePackId(packId));
    }

    public static File getIconsDir(Context context, String packId) {
        File dir = new File(getPackDir(context, packId), "icons");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static File getMetaFile(Context context, String packId) {
        return new File(getPackDir(context, packId), "meta.json");
    }

    public static File getIconFile(Context context, String packId, String iconName) {
        return new File(getIconsDir(context, packId), sanitizeIconName(iconName) + ".png");
    }

    public static ArrayList<IconPack> getPacks(Context context) {
        ArrayList<IconPack> packs = new ArrayList<>();
        File packsDir = getIconPacksDir(context);
        File[] dirs = packsDir.listFiles();
        if (dirs == null) return packs;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String packId = dir.getName();
            String name = packId;
            File metaFile = new File(dir, "meta.json");
            if (metaFile.isFile()) {
                try {
                    JSONObject meta = new JSONObject(FileUtils.readString(metaFile));
                    if (meta.has("name")) name = meta.getString("name");
                    if (meta.has("id") && !meta.getString("id").isEmpty()) packId = meta.getString("id");
                }
                catch (Exception ignored) {}
            }
            IconPack pack = new IconPack(packId, name);
            ArrayList<String> iconNames = new ArrayList<>();
            File iconsDir = new File(dir, "icons");
            File[] iconFiles = iconsDir.listFiles();
            if (iconFiles != null) {
                for (File iconFile : iconFiles) {
                    String filename = iconFile.getName().toLowerCase(Locale.ENGLISH);
                    if (iconFile.isFile() && (filename.endsWith(".png") || filename.endsWith(".jpg")
                        || filename.endsWith(".jpeg") || filename.endsWith(".webp"))) {
                        iconNames.add(FileUtils.getBasename(iconFile.getName()));
                    }
                }
            }
            pack.setIconNames(iconNames);
            packs.add(pack);
        }
        Collections.sort(packs);
        return packs;
    }

    public static IconPack getPack(Context context, String packId) {
        if (packId == null || packId.isEmpty()) return null;
        for (IconPack pack : getPacks(context)) {
            if (pack.getId().equals(packId)) return pack;
        }
        return null;
    }

    public static IconPack createPack(Context context, String name) {
        String baseName = name != null ? name.trim() : "";
        if (baseName.isEmpty()) baseName = "Icon Pack";
        String packId = "pack-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        packId = sanitizePackId(packId);
        // Guarantee uniqueness
        File packDir = new File(getIconPacksDir(context), packId);
        if (!packDir.mkdirs() && !packDir.isDirectory()) return null;
        new File(packDir, "icons").mkdirs();
        IconPack pack = new IconPack(packId, baseName);
        saveMeta(context, pack);
        return pack;
    }

    public static boolean renamePack(Context context, IconPack pack, String newName) {
        if (pack == null || newName == null || newName.trim().isEmpty()) return false;
        pack.setName(newName.trim());
        return saveMeta(context, pack);
    }

    public static boolean deletePack(Context context, IconPack pack) {
        if (pack == null) return false;
        return FileUtils.delete(getPackDir(context, pack.getId()));
    }

    public static boolean deleteIcon(Context context, String packId, String iconName) {
        File iconFile = getIconFile(context, packId, iconName);
        return iconFile.isFile() && iconFile.delete();
    }

    private static boolean saveMeta(Context context, IconPack pack) {
        try {
            JSONObject meta = new JSONObject();
            meta.put("id", pack.getId());
            meta.put("name", pack.getName());
            meta.put("version", 1);
            return FileUtils.writeString(getMetaFile(context, pack.getId()), meta.toString());
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * Adds an image from an input stream to a pack, normalizing it to PNG.
     * @return the stored icon name, or null on failure.
     */
    public static String addIconFromStream(Context context, String packId, String displayName, InputStream inStream) {
        if (inStream == null) return null;
        try {
            byte[] data = StreamUtils.copyToByteArray(inStream);
            if (data == null || data.length == 0) return null;
            return addIconFromBytes(context, packId, displayName, data);
        }
        catch (Exception e) {
            return null;
        }
    }

    public static String addIconFromBytes(Context context, String packId, String displayName, byte[] data) {
        if (data == null || data.length == 0) return null;
        String base = sanitizeIconName(displayName);
        if (base.isEmpty()) base = "icon-" + System.currentTimeMillis();
        File iconsDir = getIconsDir(context, packId);
        String candidate = base;
        int suffix = 1;
        while (new File(iconsDir, candidate + ".png").exists()) {
            candidate = base + "-" + (++suffix);
        }
        // Try to normalize to PNG so every stored icon is decodable as PNG.
        byte[] pngData = transcodeToPng(data);
        if (pngData == null) pngData = data;
        File outFile = new File(iconsDir, candidate + ".png");
        if (!FileUtils.write(outFile, pngData)) return null;
        // Verify it decodes
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(outFile.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            outFile.delete();
            return null;
        }
        return candidate;
    }

    private static byte[] transcodeToPng(byte[] data) {
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap == null) return null;
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            return os.toByteArray();
        }
        catch (Exception e) {
            return null;
        }
    }

    public static Bitmap loadIconBitmap(Context context, String packId, String iconName) {
        if (packId == null || packId.isEmpty() || iconName == null || iconName.isEmpty()) return null;
        try {
            File iconFile = getIconFile(context, packId, iconName);
            if (!iconFile.isFile()) {
                // Fallback: search case-insensitively / with other extensions (InputBridge compat)
                File iconsDir = new File(getPackDir(context, packId), "icons");
                File[] files = iconsDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && FileUtils.getBasename(f.getName()).equalsIgnoreCase(iconName)) {
                            iconFile = f;
                            break;
                        }
                    }
                }
            }
            if (!iconFile.isFile()) return null;
            return BitmapFactory.decodeFile(iconFile.getAbsolutePath());
        }
        catch (Exception e) {
            return null;
        }
    }

    /**
     * Exports a pack as a .ipk file (ZIP archive) into Downloads/Winlator/iconpacks.
     * Format: meta.json at root + icons/*.png. This is also readable by InputBridge-style
     * importers that accept a zip of images, and our importer accepts InputBridge ipks
     * (zip of images with or without meta).
     */
    public static File exportPack(Context context, IconPack pack) {
        if (pack == null) return null;
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String safeName = sanitizeFileName(pack.getName());
        if (safeName.isEmpty()) safeName = pack.getId();
        File destination = new File(downloadsDir, "Winlator/iconpacks/" + safeName + ".ipk");
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(destination);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            // meta.json
            JSONObject meta = new JSONObject();
            meta.put("id", pack.getId());
            meta.put("name", pack.getName());
            meta.put("version", 1);
            writeZipEntry(zos, "meta.json", meta.toString().getBytes("UTF-8"));

            File iconsDir = new File(getPackDir(context, pack.getId()), "icons");
            File[] files = iconsDir.listFiles();
            if (files != null) {
                byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                for (File iconFile : files) {
                    if (!iconFile.isFile()) continue;
                    String entryName = "icons/" + iconFile.getName();
                    try (FileInputStream fis = new FileInputStream(iconFile)) {
                        zos.putNextEntry(new ZipEntry(entryName));
                        int count;
                        while ((count = fis.read(buffer)) > 0) zos.write(buffer, 0, count);
                        zos.closeEntry();
                    }
                }
            }
            zos.finish();
        }
        catch (Exception e) {
            return null;
        }
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    /**
     * Imports a .ipk (or .zip) file from a content Uri. Accepts:
     * - Our format: meta.json + icons/*.png
     * - InputBridge-style packs: a zip containing images at any level, with or without meta.
     * - A single image file (treated as a one-icon pack).
     * @return the imported IconPack, or null on failure.
     */
    public static IconPack importPack(Context context, Uri uri, String fallbackName) {
        if (uri == null) return null;
        String uriName = null;
        try {
            uriName = FileUtils.getNameFromUri(context, uri);
        }
        catch (Exception ignored) {}
        if (fallbackName == null || fallbackName.isEmpty()) {
            fallbackName = uriName != null ? FileUtils.getBasename(uriName) : "Imported Pack";
            if (fallbackName.isEmpty()) fallbackName = "Imported Pack";
        }
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) return null;
            byte[] allBytes = StreamUtils.copyToByteArray(raw);
            if (allBytes == null || allBytes.length == 0) return null;
            if (isZipArchive(allBytes)) {
                return importPackFromZipBytes(context, allBytes, fallbackName);
            }
            else {
                // Single image -> create a pack containing it
                IconPack pack = createPack(context, fallbackName);
                if (pack == null) return null;
                String iconName = FileUtils.getBasename(fallbackName);
                if (iconName.isEmpty()) iconName = "icon";
                addIconFromBytes(context, pack.getId(), iconName, allBytes);
                return getPack(context, pack.getId());
            }
        }
        catch (Exception e) {
            return null;
        }
    }

    private static boolean isZipArchive(byte[] data) {
        return data.length > 4 && data[0] == 'P' && data[1] == 'K';
    }

    private static IconPack importPackFromZipBytes(Context context, byte[] zipBytes, String fallbackName) {
        String packName = fallbackName;
        // First pass: try to read meta (ours or InputBridge-like json at root)
        ArrayList<PendingIcon> pendingIcons = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String entryName = entry.getName();
                String lower = entryName.toLowerCase(Locale.ENGLISH);
                if (lower.endsWith("__macosx/") || lower.contains("__macosx")) {
                    zis.closeEntry();
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int count;
                while ((count = zis.read(buffer)) > 0) baos.write(buffer, 0, count);
                zis.closeEntry();
                byte[] entryBytes = baos.toByteArray();
                if (entryBytes.length == 0) continue;
                String baseName = FileUtils.getName(entryName);
                if (baseName.equalsIgnoreCase("meta.json") || baseName.equalsIgnoreCase("pack.json")
                    || baseName.equalsIgnoreCase("iconpack.json") || baseName.equalsIgnoreCase("manifest.json")) {
                    try {
                        JSONObject meta = new JSONObject(new String(entryBytes, "UTF-8"));
                        if (meta.has("name") && !meta.getString("name").trim().isEmpty()) {
                            packName = meta.getString("name").trim();
                        }
                        else if (meta.has("title") && !meta.getString("title").trim().isEmpty()) {
                            packName = meta.getString("title").trim();
                        }
                    }
                    catch (Exception ignored) {}
                }
                else if (isImageEntry(lower)) {
                    pendingIcons.add(new PendingIcon(FileUtils.getBasename(baseName), entryBytes));
                }
            }
        }
        catch (Exception e) {
            return null;
        }
        if (pendingIcons.isEmpty()) return null;
        IconPack pack = createPack(context, packName);
        if (pack == null) return null;
        for (PendingIcon pending : pendingIcons) {
            addIconFromBytes(context, pack.getId(), pending.name, pending.data);
        }
        return getPack(context, pack.getId());
    }

    private static boolean isImageEntry(String lowerName) {
        return lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
            || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp");
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    public static String sanitizePackId(String packId) {
        if (packId == null) return "";
        return packId.replaceAll("[^A-Za-z0-9-_]", "_");
    }

    public static String sanitizeIconName(String name) {
        if (name == null) return "";
        // Strip extension if present
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ENGLISH);
            if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("webp")) {
                name = name.substring(0, dot);
            }
        }
        // Keep path basename only
        name = FileUtils.getName(name.replace('\\', '/'));
        name = name.trim().replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9-_]", "_");
        if (name.length() > 48) name = name.substring(0, 48);
        return name;
    }

    public static String sanitizeFileName(String name) {
        if (name == null) return "";
        name = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.length() > 64) name = name.substring(0, 64);
        return name;
    }

    private static class PendingIcon {
        final String name;
        final byte[] data;
        PendingIcon(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }
}
