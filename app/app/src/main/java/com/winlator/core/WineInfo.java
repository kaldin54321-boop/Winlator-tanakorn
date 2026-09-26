package com.winlator.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WineInfo implements Parcelable {
    public static final String MAIN_WINE_VERSION = "10.10";
    public static final WineInfo MAIN_WINE_INFO = new WineInfo(MAIN_WINE_VERSION);
    // Numeric versions (legacy, e.g. wine-10.10, wine-9.0-2) plus an optional arch suffix.
    private static final Pattern pattern = Pattern.compile("^wine\\-([0-9\\.]+)\\-?([0-9\\.]+)?\\-?(x86|x86_64)?$");
    // Custom builds (e.g. wine-10.7-tkg-wlt10-test0.1, wine-10.0.1-proton, Wine-10.15).
    // Group 1 = numeric version, group 2 = free-form subversion (letters, digits, dots, dashes).
    private static final Pattern customPattern = Pattern.compile("^wine\\-([0-9]+(?:\\.[0-9]+)*)\\-?([A-Za-z0-9\\.\\-_]+?)?(?:\\-?(x86|x86_64))?$", Pattern.CASE_INSENSITIVE);
    public final String version;
    public final String subversion;
    public final String path;

    public WineInfo(String version) {
        this.version = version;
        this.subversion = null;
        this.path = null;
    }

    public WineInfo(String version, String subversion, String path) {
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.path = path;
    }

    private WineInfo(Parcel in) {
        version = in.readString();
        subversion = in.readString();
        path = in.readString();
    }

    public String identifier() {
        return "wine-"+fullVersion()+(this == MAIN_WINE_INFO ? "-custom" : "");
    }

    public String fullVersion() {
        return version+(subversion != null ? "-"+subversion : "");
    }

    @NonNull
    @Override
    public String toString() {
        return "Wine "+fullVersion()+(this == MAIN_WINE_INFO ? " (Custom)" : "");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(version);
        dest.writeString(subversion);
        dest.writeString(path);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, String identifier) {
        if (identifier.equals(MAIN_WINE_INFO.identifier())) return MAIN_WINE_INFO;
        Matcher matcher = pattern.matcher(identifier);
        if (matcher.find()) {
            File installedWineDir = RootFS.find(context).getInstalledWineDir();
            String path = (new File(installedWineDir, identifier)).getPath();
            return new WineInfo(matcher.group(1), matcher.group(2), path);
        }
        Matcher customMatcher = customPattern.matcher(identifier);
        if (customMatcher.find()) {
            File installedWineDir = RootFS.find(context).getInstalledWineDir();
            String path = (new File(installedWineDir, identifier)).getPath();
            WineInfo parsed = new WineInfo(customMatcher.group(1), customMatcher.group(2), path);
            // Guard against parses that would not round-trip (e.g. a trailing
            // arch-like suffix being swallowed); fall through to the generic
            // split below, which always reconstructs the directory name.
            if (parsed.identifier().equals(identifier)) return parsed;
        }
        // Generic fallback for any other "wine-*" directory so custom builds with
        // free-form names still resolve to their installed path instead of MAIN.
        if (identifier.startsWith("wine-") && identifier.length() > 5) {
            String fullVersion = identifier.substring(5);
            String version = fullVersion;
            String subversion = null;
            int dash = fullVersion.indexOf('-');
            if (dash > 0) {
                version = fullVersion.substring(0, dash);
                subversion = fullVersion.substring(dash + 1);
            }
            File installedWineDir = RootFS.find(context).getInstalledWineDir();
            String path = (new File(installedWineDir, identifier)).getPath();
            return new WineInfo(version, subversion, path);
        }
        return MAIN_WINE_INFO;
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null ||wineVersion.equals(MAIN_WINE_INFO.identifier());
    }
}
