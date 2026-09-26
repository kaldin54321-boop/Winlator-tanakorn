package com.winlator.box64.rc;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

public class RCManager {
    public static final String PREF_BOX64_RCFILE_ID = "box64_rcfile_id";
    public static final int DEFAULT_RCFILE_ID = 1;
    private static final String ASSET_DEFAULT_BOX64RC = "box64/default.box64rc";

    private LinkedList<RCFile> rcfiles;
    private final Context context;
    private int maxRCFileId;
    private boolean rcfilesLoaded = false;

    public RCManager(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    public RCFile duplicateRCFile(RCFile source) {
        String newName = source.getName();
        for (RCFile rcFile : getRCFiles()) {
            if (rcFile.getName().equals(newName)) {
                for (int i = 1; ; i++) {
                    newName = source.getName() + " (" + i + ")";
                    boolean found = false;
                    for (RCFile rcfile : rcfiles) {
                        if (rcfile.getName().equals(newName)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) break;
                }
                break;
            }
        }

        int newId = ++maxRCFileId;
        File newFile = RCFile.getRCFile(context, newId);

        try {
            JSONObject data = source.toJson();
            data.put("id", newId);
            data.put("name", newName);
            FileUtils.writeString(newFile, data.toString());
        } catch (JSONException e) {}

        RCFile rcfile = loadRCFile(context, newFile);
        if (rcfile != null) rcfiles.add(rcfile);
        return rcfile;
    }

    public static File getRCFilesDir(Context context) {
        File rcfilesDir = new File(context.getFilesDir(), "rcfiles");
        if (!rcfilesDir.isDirectory()) rcfilesDir.mkdirs();
        return rcfilesDir;
    }

    public List<RCFile> getRCFiles() {
        if (!rcfilesLoaded) loadRCFiles();
        return rcfiles;
    }

    private void copyAssetRCFilesIfNeeded() {
        File rcfilesDir = RCManager.getRCFilesDir(context);
        if (FileUtils.isEmpty(rcfilesDir)) seedDefaultRCFile();
    }

    public void seedDefaultRCFile() {
        try {
            String assetText = null;
            try {
                assetText = FileUtils.readString(context, ASSET_DEFAULT_BOX64RC);
            } catch (Exception e) {
                assetText = null;
            }
            RCFile defaultFile = new RCFile(context, DEFAULT_RCFILE_ID);
            defaultFile.setName("Default");
            RCGroup defaultGroup = parseBox64rcToGroup(assetText);
            if (defaultGroup != null) defaultFile.getGroups().add(defaultGroup);
            defaultFile.save();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static RCGroup parseBox64rcToGroup(String box64rcText) {
        LinkedList<RCItem> items = new LinkedList<>();
        if (box64rcText != null) {
            String currentSection = null;
            TreeMap<String, String> currentVars = null;
            for (String rawLine : box64rcText.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (currentSection != null && currentVars != null) {
                        items.add(new RCItem(currentSection, "", currentVars));
                    }
                    currentSection = line.substring(1, line.length() - 1).trim();
                    currentVars = new TreeMap<>();
                } else if (currentSection != null && currentVars != null) {
                    int sep = line.indexOf('=');
                    if (sep > 0) {
                        String key = line.substring(0, sep).trim();
                        String value = line.substring(sep + 1).trim();
                        if (!key.isEmpty() && !key.contains(" ")) currentVars.put(key, value);
                    }
                }
            }
            if (currentSection != null && currentVars != null) {
                items.add(new RCItem(currentSection, "", currentVars));
            }
        }
        return new RCGroup("Default", "", true, items);
    }

    public RCFile createRCFile(String name) {
        RCFile rcfile = new RCFile(context, ++maxRCFileId);
        rcfile.setName(name);
        rcfile.save();
        rcfiles.add(rcfile);
        return rcfile;
    }

    public void loadRCFiles() {
        File rcfilesDir = RCManager.getRCFilesDir(context);
        copyAssetRCFilesIfNeeded();

        LinkedList<RCFile> rcfiles = new LinkedList<>();
        File[] files = rcfilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getPath().endsWith(".rcp")) {
                    try {
                        RCFile rcfile = loadRCFile(context, file);
                        if (rcfile == null) continue;
                        maxRCFileId = Math.max(maxRCFileId, rcfile.id);
                        rcfiles.add(rcfile);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        Collections.sort(rcfiles);
        this.rcfiles = rcfiles;
        rcfilesLoaded = true;
    }

    public static RCFile loadRCFile(Context context, File file) {
        if (file.exists() && file.isFile()) return loadRCFile(context, FileUtils.readString(file));
        return null;
    }

    public static RCFile loadRCFile(Context context, String json) {
        try {
            return loadRCFile(context, new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    public static RCFile loadRCFile(Context context, JSONObject obj) {
        try {
            JSONObject rcfileJSONObject = obj;
            int rcfileId = rcfileJSONObject.getInt("id");
            String rcfileName = rcfileJSONObject.getString("name");
            LinkedList<RCGroup> groups = new LinkedList<>();
            JSONArray groupsJSONArray = rcfileJSONObject.getJSONArray("groups");

            for (int i = 0; i < groupsJSONArray.length(); i++) {
                JSONObject groupJSONObject = groupsJSONArray.getJSONObject(i);
                String groupName = groupJSONObject.getString("name");
                String groupDesc = groupJSONObject.optString("desc", "");
                boolean groupEnabled = groupJSONObject.getBoolean("enabled");
                LinkedList<RCItem> items = new LinkedList<>();
                JSONArray itemsJSONArray = groupJSONObject.getJSONArray("items");

                for (int j = 0; j < itemsJSONArray.length(); j++) {
                    JSONObject itemJSONObject = itemsJSONArray.getJSONObject(j);
                    String processName = itemJSONObject.getString("processName");
                    String itemDesc = itemJSONObject.optString("desc", "");
                    TreeMap<String, String> map = new TreeMap<>();

                    JSONObject varsJSONObject = itemJSONObject.getJSONObject("vars");
                    Iterator<String> keys = varsJSONObject.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = varsJSONObject.getString(key);
                        map.put(key, value);
                    }

                    RCItem item = new RCItem(processName, itemDesc, map);
                    items.add(item);
                }
                RCGroup group = new RCGroup(groupName, groupDesc, groupEnabled, items);
                groups.add(group);
            }

            RCFile rcfile = new RCFile(context, rcfileId);
            rcfile.setName(rcfileName);
            for (RCGroup group : groups)
                rcfile.getGroups().add(group);

            return rcfile;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    public RCFile getRcfile(int id) {
        for (RCFile rcfile : getRCFiles()) if (rcfile.id == id) return rcfile;
        return null;
    }

    public static int parseRCFileId(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static int getSelectedRCFileId(Context context) {        try {
            return PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_BOX64_RCFILE_ID, DEFAULT_RCFILE_ID);
        } catch (Exception e) {
            return DEFAULT_RCFILE_ID;
        }
    }

    public static void setSelectedRCFileId(Context context, int id) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(PREF_BOX64_RCFILE_ID, id).apply();
        } catch (Exception e) {}
    }

    public static boolean writeEffectiveBox64rc(Context context, File dest) {
        try {
            int id = getSelectedRCFileId(context);
            if (id == 0) return false;
            RCManager manager = new RCManager(context);
            RCFile rcfile = manager.getRcfile(id);
            if (rcfile == null) return false;
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            return FileUtils.writeString(dest, rcfile.generateBox64rc());
        } catch (Exception e) {
            return false;
        }
    }

    public File exportRCFile(RCFile rcfile) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File destination = new File(downloadsDir, "Winlator/rcfiles/" + rcfile.getName() + ".rcp");
        FileUtils.copy(RCFile.getRCFile(context, rcfile.id), destination);
        try {
            MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        } catch (Exception e) {}
        return destination.isFile() ? destination : null;
    }

    public void saveAllRCFiles() {
        if (rcfiles != null) {
            for (RCFile rcfile : rcfiles)
                rcfile.save();
        }
    }

    public void removeRCFile(RCFile rcfile) {
        File file = RCFile.getRCFile(context, rcfile.id);
        if (file.isFile() && file.delete()) rcfiles.remove(rcfile);
    }

    public static void loadRCFileSpinner(RCManager rcManager, int rcfileId, Spinner spinner, Callback<Integer> callback) {
        Context context = spinner.getContext();
        rcManager.loadRCFiles();
        List<RCFile> rcFiles = rcManager.getRCFiles();

        List<String> filesName = new ArrayList<>();
        filesName.add("-- " + context.getString(R.string.disabled) + " --");
        for (RCFile rcfile : rcFiles)
            filesName.add(rcfile.getName());

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, filesName));
        RCFile currentRCFile = rcManager.getRcfile(rcfileId);

        spinner.setSelection(currentRCFile == null ? 0 : rcFiles.indexOf(currentRCFile) + 1);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                callback.call(position == 0 ? 0 : rcFiles.get(position - 1).id);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }
}
