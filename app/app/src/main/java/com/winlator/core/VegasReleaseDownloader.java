package com.winlator.core;

import android.app.Activity;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class VegasReleaseDownloader {
    public static final String RELEASES_API_URL = "https://api.github.com/repos/isygold/vegas-releases/releases";

    public static class VegasAsset {
        public final String name;
        public final String downloadUrl;

        public VegasAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }

    public static class VegasRelease {
        public final String tagName;
        public final String name;
        public final List<VegasAsset> assets;

        public VegasRelease(String tagName, String name, List<VegasAsset> assets) {
            this.tagName = tagName;
            this.name = name;
            this.assets = assets;
        }

        public String getDisplayName() {
            return name != null && !name.isEmpty() ? name : tagName;
        }
    }

    public interface ReleasesCallback {
        void onReleasesLoaded(List<VegasRelease> releases);
    }

    public static void fetchReleases(Activity activity, ReleasesCallback callback) {
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        HttpUtils.download(RELEASES_API_URL, (content) -> activity.runOnUiThread(() -> {
            preloaderDialog.close();
            if (content != null && !content.isEmpty()) {
                try {
                    JSONArray jsonArray = new JSONArray(content);
                    List<VegasRelease> releases = new ArrayList<>();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject releaseObj = jsonArray.getJSONObject(i);
                        String tagName = releaseObj.optString("tag_name", "");
                        String name = releaseObj.optString("name", tagName);
                        JSONArray assetsArray = releaseObj.optJSONArray("assets");
                        List<VegasAsset> assets = new ArrayList<>();
                        if (assetsArray != null) {
                            for (int j = 0; j < assetsArray.length(); j++) {
                                JSONObject assetObj = assetsArray.getJSONObject(j);
                                String assetName = assetObj.optString("name", "");
                                String downloadUrl = assetObj.optString("browser_download_url", "");
                                assets.add(new VegasAsset(assetName, downloadUrl));
                            }
                        }
                        releases.add(new VegasRelease(tagName, name, assets));
                    }
                    callback.onReleasesLoaded(releases);
                }
                catch (JSONException e) {
                    AppUtils.showToast(activity, R.string.a_network_error_occurred);
                }
            }
            else {
                AppUtils.showToast(activity, R.string.a_network_error_occurred);
            }
        }));
    }

    public static void showVegasVersionDownloadDialog(Activity activity, Spinner spinner, String defaultItem) {
        fetchReleases(activity, (releases) -> {
            if (releases.isEmpty()) {
                AppUtils.showToast(activity, R.string.there_are_no_items_to_download);
                return;
            }

            String[] releaseNames = new String[releases.size()];
            for (int i = 0; i < releases.size(); i++) {
                releaseNames[i] = releases.get(i).getDisplayName();
            }

            ContentDialog.showSelectionList(activity, R.string.select_release, releaseNames, false, (releasePositions) -> {
                if (releasePositions.isEmpty()) return;
                VegasRelease selectedRelease = releases.get(releasePositions.get(0));

                List<VegasAsset> filteredAssets = new ArrayList<>();
                for (VegasAsset asset : selectedRelease.assets) {
                    String lower = asset.name.toLowerCase();
                    if (lower.contains("vegas") && lower.endsWith(".wcp")) {
                        filteredAssets.add(asset);
                    }
                }

                if (filteredAssets.isEmpty()) {
                    AppUtils.showToast(activity, R.string.there_are_no_items_to_download);
                    return;
                }

                String[] fileNames = new String[filteredAssets.size()];
                for (int i = 0; i < filteredAssets.size(); i++) {
                    fileNames[i] = filteredAssets.get(i).name;
                }

                ContentDialog.showSelectionList(activity, R.string.select_file, fileNames, false, (filePositions) -> {
                    if (filePositions.isEmpty()) return;
                    VegasAsset selectedAsset = filteredAssets.get(filePositions.get(0));

                    File destination = new File(GeneralComponents.getComponentDir(GeneralComponents.Type.VEGAS, activity), selectedAsset.name);
                    if (destination.isFile()) destination.delete();

                    HttpUtils.download(activity, selectedAsset.downloadUrl, destination, (success) -> {
                        if (success) {
                            String identifier = selectedAsset.name.replace("vegas-", "").replace("vegas_", "").replace(".wcp", "");
                            GeneralComponents.loadSpinner(GeneralComponents.Type.VEGAS, spinner, identifier, defaultItem);
                        }
                        else {
                            AppUtils.showToast(activity, R.string.a_network_error_occurred);
                        }
                    });
                });
            });
        });
    }

    public static void showVegasConfigDownloadDialog(Activity activity, Spinner vegasConfigFileSpinner) {
        fetchReleases(activity, (releases) -> {
            if (releases.isEmpty()) {
                AppUtils.showToast(activity, R.string.there_are_no_items_to_download);
                return;
            }

            String[] releaseNames = new String[releases.size()];
            for (int i = 0; i < releases.size(); i++) {
                releaseNames[i] = releases.get(i).getDisplayName();
            }

            ContentDialog.showSelectionList(activity, R.string.select_release, releaseNames, false, (releasePositions) -> {
                if (releasePositions.isEmpty()) return;
                VegasRelease selectedRelease = releases.get(releasePositions.get(0));

                List<VegasAsset> filteredAssets = new ArrayList<>();
                for (VegasAsset asset : selectedRelease.assets) {
                    if (asset.name.toLowerCase().endsWith(".conf")) {
                        filteredAssets.add(asset);
                    }
                }

                if (filteredAssets.isEmpty()) {
                    AppUtils.showToast(activity, R.string.there_are_no_items_to_download);
                    return;
                }

                String[] fileNames = new String[filteredAssets.size()];
                for (int i = 0; i < filteredAssets.size(); i++) {
                    fileNames[i] = filteredAssets.get(i).name;
                }

                ContentDialog.showSelectionList(activity, R.string.select_file, fileNames, false, (filePositions) -> {
                    if (filePositions.isEmpty()) return;
                    VegasAsset selectedAsset = filteredAssets.get(filePositions.get(0));

                    String[] locationOptions = new String[]{
                        "/storage/emulated/0/dxvk.conf",
                        "/storage/emulated/0/starengine.ini",
                        "/storage/emulated/0/Download/dxvk.conf",
                        "/storage/emulated/0/Winlator/dxvk.conf"
                    };

                    ContentDialog.showSelectionList(activity, R.string.select_location, locationOptions, false, (locPositions) -> {
                        if (locPositions.isEmpty()) return;
                        String selectedLocation = locationOptions[locPositions.get(0)];
                        File targetFile = new File(selectedLocation);
                        if (targetFile.getParentFile() != null) targetFile.getParentFile().mkdirs();
                        if (targetFile.isFile()) targetFile.delete();

                        HttpUtils.download(activity, selectedAsset.downloadUrl, targetFile, (success) -> {
                            if (success) {
                                AppUtils.setSpinnerSelectionFromValue(vegasConfigFileSpinner, selectedLocation);
                                AppUtils.showToast(activity, R.string.download_completed);
                            }
                            else {
                                AppUtils.showToast(activity, R.string.a_network_error_occurred);
                            }
                        });
                    });
                });
            });
        });
    }
}
