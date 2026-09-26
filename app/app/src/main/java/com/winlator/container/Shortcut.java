package com.winlator.container;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.winlator.core.FileUtils;
import com.winlator.core.ImageUtils;
import com.winlator.core.StringUtils;
import com.winlator.core.WineUtils;
import com.winlator.win32.MSLink;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

public class Shortcut {
    public final Container container;
    public final String name;
    public final String path;
    public final Bitmap icon;
    public final File file;
    public final File iconFile;
    public final String wmClass;
    private final JSONObject extraData = new JSONObject();

    public Shortcut(Container container, File file) {
        this.container = container;
        this.file = file;

        if (file.isDirectory()) {
            this.name = file.getName();
            this.path = null;
            this.icon = null;
            this.iconFile = null;
            this.wmClass = "";
        }
        else {
            String execArgs = "";
            Bitmap icon = null;
            File iconFile = null;
            String wmClass = "";
            String section = "";

            final short[] iconSizes = {64, 48, 32, 24, 16, 128, 256};
            int index;
            for (String line : FileUtils.readLines(file, true)) {
                if (line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    section = line.substring(1, line.indexOf("]"));
                }
                else {
                    index = line.indexOf("=");
                    if (index == -1) continue;
                    String key = line.substring(0, index);
                    String value = line.substring(index+1);

                    if (section.equals("Desktop Entry")) {
                        if (key.equals("Exec")) execArgs = value;
                        if (key.equals("Icon")) {
                            for (short iconSize : iconSizes) {
                                iconFile = new File(container.getIconsDir(iconSize), value+".png");
                                if (iconFile.isFile()){
                                    icon = BitmapFactory.decodeFile(iconFile.getPath());
                                    break;
                                }
                            }
                        }
                        if (key.equals("StartupWMClass")) wmClass = value;
                    }
                    else if (section.equals("Extra Data")) {
                        try {
                            extraData.put(key, value);
                        }
                        catch (JSONException e) {}
                    }
                }
            }

            this.name = FileUtils.getBasename(file.getPath());
            this.icon = icon;
            this.iconFile = iconFile;
            this.wmClass = wmClass;

            String path = "";
            if (!execArgs.isEmpty()) {
                int unixIndex = execArgs.toLowerCase(Locale.ENGLISH).indexOf("/unix");
                if (unixIndex != -1) {
                    String unixPath = execArgs.substring(unixIndex + 5).trim();
                    if (unixPath.startsWith("\"") && unixPath.endsWith("\"") && unixPath.length() >= 2) {
                        unixPath = unixPath.substring(1, unixPath.length() - 1).trim();
                    }
                    unixPath = StringUtils.unescapeDOSPath(unixPath);
                    path = WineUtils.unixToDOSPath(unixPath, container);
                }
                else {
                    int wineIndex = execArgs.lastIndexOf("wine ");
                    path = wineIndex != -1 ? execArgs.substring(wineIndex + 5) : execArgs;
                    path = StringUtils.unescapeDOSPath(path);

                    int startIdx = path.indexOf("start.exe ");
                    if (startIdx != -1) path = path.substring(startIdx + 10).trim();
                }
            }

            if (path.endsWith(".lnk")) {
                try {
                    File linkFile = new File(WineUtils.dosToUnixPath(path, container));
                    if (linkFile.exists()) {
                        MSLink.LinkInfo linkInfo = MSLink.extractLinkInfo(linkFile);
                        if (linkInfo != null && linkInfo.targetPath != null && !linkInfo.targetPath.isEmpty()) {
                            path = linkInfo.targetPath;
                            if (getExtra("execArgs").isEmpty() && linkInfo.arguments != null && !linkInfo.arguments.isEmpty()) {
                                putExtra("execArgs", linkInfo.arguments);
                            }
                        }
                    }
                }
                catch (Exception e) {}
            }

            this.path = path;
            Container.checkObsoleteOrMissingProperties(extraData);
        }
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, String value) {
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    public void saveData() {
        String content = "[Desktop Entry]\n";
        for (String line : FileUtils.readLines(file)) {
            if (line.contains("[Extra Data]")) break;
            if (!line.contains("[Desktop Entry]") && !line.isEmpty()) content += line+"\n";
        }

        if (extraData.length() > 0) {
            content += "\n[Extra Data]\n";
            Iterator<String> keys = extraData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    content += key + "=" + extraData.getString(key) + "\n";
                }
                catch (JSONException e) {}
            }
        }

        FileUtils.writeString(file, content);
    }

    public void setIcon(Bitmap bitmap) {
        String iconName = FileUtils.getBasename(file.getPath());
        File iconDir = container.getIconsDir(48);
        if (!iconDir.exists()) iconDir.mkdirs();
        File targetIconFile = new File(iconDir, iconName + ".png");
        ImageUtils.save(bitmap, targetIconFile, Bitmap.CompressFormat.PNG, 100);

        ArrayList<String> lines = FileUtils.readLines(file);
        boolean iconFound = false;
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("Icon=")) {
                content.append("Icon=").append(iconName).append("\n");
                iconFound = true;
            }
            else {
                content.append(line).append("\n");
            }
        }
        if (!iconFound) {
            int index = content.indexOf("[Desktop Entry]");
            if (index != -1) {
                int insertPos = content.indexOf("\n", index);
                if (insertPos != -1) {
                    content.insert(insertPos + 1, "Icon=" + iconName + "\n");
                }
                else {
                    content.append("Icon=").append(iconName).append("\n");
                }
            }
            else {
                content.append("Icon=").append(iconName).append("\n");
            }
        }
        FileUtils.writeString(file, content.toString());
    }

    public void genUUID() {
        if (getExtra("uuid").isEmpty()) {
            putExtra("uuid", UUID.randomUUID().toString());
            saveData();
        }
    }

    public int getContainerId() {
        return container.id;
    }

    public boolean isLinkPath() {
        return path.endsWith(".lnk") || path.contains("://");
    }

    public File getLinkFile() {
        if (isLinkPath()) {
            return new File(WineUtils.dosToUnixPath(path, container));
        }
        else {
            String name = file.getName().replace(".desktop", ".lnk");
            return new File(file.getParentFile(), name);
        }
    }

    public void remove() {
        if (file.isDirectory()) {
            FileUtils.delete(file);
        }
        else {
            File linkFile = getLinkFile();
            if (file.delete()) {
                if (iconFile != null) iconFile.delete();
                if (linkFile.isFile()) linkFile.delete();
            }
        }
    }
}
