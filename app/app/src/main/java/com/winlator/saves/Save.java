package com.winlator.saves;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

public class Save {
    public int id;
    public Container container;
    private String title;
    public String path;
    public final File file;
    private final JSONObject extraData = new JSONObject();

    public Save(ContainerManager containerManager, Container container, File file) {
        this.container = container;
        this.file = file;

        String title = "";
        String path = "";

        String content = FileUtils.readString(file);
        try {
            JSONObject saveData = new JSONObject(content);
            title = saveData.getString("Title");
            path = saveData.getString("Path");

            if (container == null && saveData.has("ContainerID")) {
                int containerId = saveData.getInt("ContainerID");
                this.container = containerManager.getContainerById(containerId);
            }

            if (saveData.has("ID")) {
                try {
                    this.id = saveData.getInt("ID");
                } catch (JSONException ignored) {}
            }

            Iterator<String> keys = saveData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.equals("Title") && !key.equals("Path")) {
                    extraData.put(key, saveData.getString(key));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        this.title = title;
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public void update(String newTitle, String newPath, Container newContainer) {
        this.title = newTitle;
        this.path = newPath;
        this.container = newContainer;
        Log.d("Save", "Updated Save: Title=" + newTitle + ", Path=" + newPath);
    }

    public void saveData() {
        try {
            JSONObject saveData = new JSONObject();
            saveData.put("ID", id);
            saveData.put("Title", title);
            saveData.put("Path", path);

            if (container != null) {
                saveData.put("ContainerID", container.id);
            }

            Log.d("Save", "Saving Data: " + saveData.toString());

            Iterator<String> keys = extraData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.equals("ID") && !key.equals("Title") && !key.equals("Path") && !key.equals("ContainerID")) {
                    saveData.put(key, extraData.getString(key));
                }
            }

            FileUtils.writeString(file, saveData.toString());
            Log.d("Save", "Data written to file: " + file.getAbsolutePath());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
