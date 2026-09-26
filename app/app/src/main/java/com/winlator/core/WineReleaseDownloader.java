package com.winlator.core;

import android.app.Activity;

import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloads custom Wine builds from GitHub releases.
 * Uses the same two-step logic as {@link VegasReleaseDownloader}:
 * pick a release, then pick a file, then download it.
 * Two sources are supported: Winlator@Frost .whp packages and Kron4ek
 * tar.xz builds (64-bit/WoW64 only; 32-bit-only builds cannot run here
 * since the app executes everything under box64 with no box86).
 */
public class WineReleaseDownloader {
    public static final String RELEASES_API_URL = "https://api.github.com/repos/kaldin54321-boop/Wine-for-winlator-official/releases";
    public static final String KRON4EK_RELEASES_API_URL = "https://api.github.com/repos/Kron4ek/Wine-Builds/releases";
    public static final int SOURCE_OFFICIAL = 0;
    public static final int SOURCE_KRON4EK = 1;

    public static class WineAsset {
        public final String name;
        public final String downloadUrl;

        public WineAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }

    public static class WineRelease {
        public final String tagName;
        public final String name;
        public final List<WineAsset> assets;

        public WineRelease(String tagName, String name, List<WineAsset> assets) {
            this.tagName = tagName;
            this.name = name;
            this.assets = assets;
        }

        public String getDisplayName() {
            return name != null && !name.isEmpty() ? name : tagName;
        }
    }

    public interface ReleasesCallback {
        void onReleasesLoaded(List<WineRelease> releases);
    }

    public interface WinePackageCallback {
        void onWinePackageReady(File whpFile, String versionHint);
    }

    public interface ChecksumCallback {
        void onChecksumChecked(boolean verified);
    }

    private static String sha256Hex(File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) md.update(buf, 0, read);
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifies a downloaded Kron4ek archive against the release's
     * sha256sums.txt. Anything unverifiable (no sums file, unparsable, no
     * entry) is allowed through so downloads never hard-block; only a
     * definite mismatch rejects the file.
     */
    public static void verifyKron4ekChecksum(Activity activity, WineRelease release, WineAsset asset,
                                             File file, ChecksumCallback callback) {
        String sumsUrl = null;
        if (release != null && release.assets != null) {
            for (WineAsset candidate : release.assets) {
                if (candidate.name.equalsIgnoreCase("sha256sums.txt") && !candidate.downloadUrl.isEmpty()) {
                    sumsUrl = candidate.downloadUrl;
                    break;
                }
            }
        }
        // The sums download reports on a background thread while the caller
        // expects UI thread (it touches dialogs/toasts), so hop threads here.
        final ChecksumCallback uiCallback = (verified) ->
            activity.runOnUiThread(() -> callback.onChecksumChecked(verified));
        if (sumsUrl == null || asset == null) {
            uiCallback.onChecksumChecked(true);
            return;
        }
        final String fileName = asset.name;
        HttpUtils.download(sumsUrl, (content) -> {
            try {
                if (content == null || content.isEmpty()) {
                    uiCallback.onChecksumChecked(true);
                    return;
                }
                String expected = null;
                for (String line : content.split("\r?\n")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2 && parts[parts.length - 1].replaceFirst("^\\*", "").equals(fileName)) {
                        expected = parts[0].toLowerCase(java.util.Locale.ENGLISH);
                        break;
                    }
                }
                if (expected == null) {
                    uiCallback.onChecksumChecked(true);
                    return;
                }
                String actual = sha256Hex(file);
                uiCallback.onChecksumChecked(actual != null && actual.equals(expected));
            }
            catch (Exception e) {
                uiCallback.onChecksumChecked(true);
            }
        });
    }

    /**
     * Major Wine version parsed from a Kron4ek asset file name such as
     * {@code wine-11.16-amd64-wow64.tar.xz} or
     * {@code wine-11.16-staging-tkg-amd64.tar.xz}, or -1 when unknown.
     * Unknown names are treated as installable (fail-open) by the caller.
     */
    static int kron4ekMajorVersion(String assetName) {
        if (assetName == null) return -1;
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("(?i)wine-(\\d+)\\.").matcher(assetName);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            }
            catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    public static void fetchReleases(Activity activity, ReleasesCallback callback) {
        fetchReleases(activity, SOURCE_OFFICIAL, callback);
    }

    public static void fetchReleases(Activity activity, int source, ReleasesCallback callback) {
        String apiUrl = source == SOURCE_KRON4EK ? KRON4EK_RELEASES_API_URL : RELEASES_API_URL;
        PreloaderDialog preloaderDialog = new PreloaderDialog(activity);
        preloaderDialog.show(R.string.loading);
        HttpUtils.download(apiUrl, (content) -> activity.runOnUiThread(() -> {
            preloaderDialog.close();
            if (content != null && !content.isEmpty()) {
                try {
                    JSONArray jsonArray = new JSONArray(content);
                    List<WineRelease> releases = new ArrayList<>();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject releaseObj = jsonArray.getJSONObject(i);
                        String tagName = releaseObj.optString("tag_name", "");
                        String name = releaseObj.optString("name", tagName);
                        JSONArray assetsArray = releaseObj.optJSONArray("assets");
                        List<WineAsset> assets = new ArrayList<>();
                        if (assetsArray != null) {
                            for (int j = 0; j < assetsArray.length(); j++) {
                                JSONObject assetObj = assetsArray.getJSONObject(j);
                                String assetName = assetObj.optString("name", "");
                                String downloadUrl = assetObj.optString("browser_download_url", "");
                                if (!assetName.isEmpty() && !downloadUrl.isEmpty()) {
                                    assets.add(new WineAsset(assetName, downloadUrl));
                                }
                            }
                        }
                        releases.add(new WineRelease(tagName, name, assets));
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

    public static void showWineDownloadDialog(Activity activity, WinePackageCallback callback) {
        showWineDownloadDialog(activity, SOURCE_OFFICIAL, callback);
    }

    public static void showWineDownloadDialog(Activity activity, int source, WinePackageCallback callback) {
        fetchReleases(activity, source, (releases) -> {
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
                WineRelease selectedRelease = releases.get(releasePositions.get(0));

                List<WineAsset> filteredAssets = new ArrayList<>();
                int skippedTooNew = 0;
                for (WineAsset asset : selectedRelease.assets) {
                    String lowerName = asset.name.toLowerCase(Locale.ENGLISH);
                    if (source == SOURCE_KRON4EK) {
                        // Kron4ek publishes vanilla/staging/tkg in amd64
                        // (WoW64 or classic 64-bit) plus 32-bit-only x86
                        // builds, which cannot run here (box64 only, no
                        // box86). The "amd64" requirement filters those out
                        // along with checksums/readmes.
                        if (lowerName.endsWith(".tar.xz") && lowerName.contains("amd64")) {
                            // Wine 11.x loaders crash the bundled box64 0.4.4
                            // deterministically (SIGSEGV in box64's own setbuf
                            // wrapper during loader startup, before Wine prints
                            // anything), so they are not offered: only a
                            // silent return-to-menu would follow. Builds whose
                            // version cannot be parsed are kept (fail-open).
                            int major = kron4ekMajorVersion(asset.name);
                            if (major >= 11) {
                                skippedTooNew++;
                                continue;
                            }
                            filteredAssets.add(asset);
                        }
                    }
                    else if (lowerName.endsWith(".whp")) {
                        filteredAssets.add(asset);
                    }
                }

                if (filteredAssets.isEmpty()) {
                    if (skippedTooNew > 0) {
                        AppUtils.showToast(activity, R.string.kron4ek_wine11_unsupported);
                    }
                    else {
                        AppUtils.showToast(activity, R.string.there_are_no_items_to_download);
                    }
                    return;
                }

                String[] fileNames = new String[filteredAssets.size()];
                for (int i = 0; i < filteredAssets.size(); i++) {
                    fileNames[i] = filteredAssets.get(i).name;
                }

                ContentDialog.showSelectionList(activity, R.string.select_file, fileNames, false, (filePositions) -> {
                    if (filePositions.isEmpty()) return;
                    WineAsset selectedAsset = filteredAssets.get(filePositions.get(0));

                    File destination = new File(activity.getCacheDir(), selectedAsset.name);
                    if (destination.isFile()) destination.delete();

                    HttpUtils.download(activity, selectedAsset.downloadUrl, destination, (success) -> {
                        if (!success) {
                            AppUtils.showToast(activity, R.string.a_network_error_occurred);
                            return;
                        }
                        if (source == SOURCE_KRON4EK) {
                            // 100MB+ downloads over mobile connections can
                            // truncate; a truncated wine tree boots into
                            // instant silent crashes. Kron4ek publishes
                            // sha256sums.txt per release, so verify first.
                            verifyKron4ekChecksum(activity, selectedRelease, selectedAsset, destination, (verified) -> {
                                if (verified) {
                                    callback.onWinePackageReady(destination, selectedAsset.name);
                                }
                                else {
                                    FileUtils.delete(destination);
                                    AppUtils.showToast(activity, R.string.custom_wine_checksum_mismatch);
                                }
                            });
                            return;
                        }
                        String versionHint = !selectedAsset.name.isEmpty() ? selectedAsset.name
                                : (!selectedRelease.tagName.isEmpty() ? selectedRelease.tagName : selectedRelease.name);
                        callback.onWinePackageReady(destination, versionHint);
                    });
                });
            });
        });
    }
}
