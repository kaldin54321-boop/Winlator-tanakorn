package com.winlator.box64;

import android.content.Context;

import com.winlator.core.ArrayUtils;
import com.winlator.core.StreamUtils;
import com.winlator.xenvironment.RootFS;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public abstract class Box64Utils {
    /**
     * Single source of truth for Box64 version identity.
     * Extracts the first {@code X.Y[.Z]} number from any label the UI,
     * prefs, containers or shortcuts may carry ("0.3.8", "Box64 0.3.8",
     * "box64-0.4.0", "v0.4.4", "BOX64_V0.4.0", ...). Returns "" if none
     * found so callers can fall back to the default version.
     * Using a regex (instead of naive replace("box64-","").replace("v",""))
     * guarantees every selection maps 1:1 to its own asset and can never
     * rotate (0.3.8->0.4.0 etc.) or mis-handle "Box64 0.3.8" (space vs '-').
     */
    public static String normalizeBox64Version(String raw) {
        if (raw == null) return "";
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+(?:\\.\\d+)?)")
                .matcher(raw);
            if (m.find()) return m.group(1).trim();
        }
        catch (Exception ignored) {}
        return "";
    }

    /**
     * Wine-version guard for the Box64 binary a session will run.
     * Wine 11.x loaders crash the bundled box64 0.4.4 deterministically
     * (SIGSEGV in box64's own setbuf wrapper during loader startup, before
     * Wine prints anything), so such a container can only bounce straight
     * back to the main menu. When the resolved version is 0.4.4 and the
     * container's Wine major version is 11+, transparently use the bundled
     * 0.4.0 instead (requires the box64-0.4.0.tzst asset, per the 1:1
     * version-to-asset rule in GeneralComponents) so the container boots.
     * Every other combination is returned untouched.
     *
     * @param requestedVersion the Box64 version the session resolved
     *     (shortcut override -&gt; container -&gt; global), any label form.
     * @param wineMajorVersion the container Wine's major version
     *     (see WineInstaller.wineMajorVersion), or -1 when unknown.
     * @return the normalized version to actually install and run.
     */
    public static String resolveWineCompatibleBox64Version(String requestedVersion, int wineMajorVersion) {
        String requested = normalizeBox64Version(requestedVersion);
        if (requested.isEmpty()) requested = com.winlator.core.DefaultVersion.BOX64;
        if (wineMajorVersion >= 11 && requested.equals("0.4.4")) {
            android.util.Log.w("WinlatorBox64", "Box64 0.4.4 crashes Wine " + wineMajorVersion
                + " loaders at startup; falling back to 0.4.0 for this session");
            return "0.4.0";
        }
        return requested;
    }

    public static String extractBinVersion(Context context) {
        File binFile = new File(RootFS.find(context).getRootDir(), "/usr/local/bin/box64");
        try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(binFile), StreamUtils.BUFFER_SIZE)) {
            int bytesRead;
            byte[] buffer = new byte[4096];
            final byte[] str = {'B','o','x','6','4',' ','a','r','m','6','4',' ','v'};
            // Keep the trailing bytes between reads so a "Box64 arm64 vX.Y.Z"
            // marker split across two 4K chunks is still found. The old code
            // searched each chunk independently and could miss/truncate it.
            byte[] carry = new byte[0];
            while ((bytesRead = inStream.read(buffer)) != -1) {
                byte[] window;
                if (carry.length > 0) {
                    window = new byte[carry.length + bytesRead];
                    System.arraycopy(carry, 0, window, 0, carry.length);
                    System.arraycopy(buffer, 0, window, carry.length, bytesRead);
                }
                else {
                    window = new byte[bytesRead];
                    System.arraycopy(buffer, 0, window, 0, bytesRead);
                }
                int windowLen = window.length;
                int index = ArrayUtils.indexOf(window, 0, windowLen, str);
                if (index != ArrayUtils.INDEX_NOT_FOUND) {
                    int start = index + str.length;
                    // Version may run past this window; scan forward across
                    // subsequent chunks until the terminating space (or a
                    // non-version byte) instead of truncating at the edge.
                    StringBuilder ver = new StringBuilder();
                    int pos = start;
                    boolean terminated = false;
                    while (true) {
                        while (pos < windowLen) {
                            byte b = window[pos++];
                            if (b == (byte)' ') { terminated = true; break; }
                            if ((b >= '0' && b <= '9') || b == '.' || b == '-' || b == '+'
                                || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')) {
                                ver.append((char) b);
                            }
                            else { terminated = true; break; }
                            if (ver.length() > 32) { terminated = true; break; }
                        }
                        if (terminated || ver.length() == 0) break;
                        // Ran off the end without a terminator: pull more.
                        int more = inStream.read(buffer);
                        if (more == -1) break;
                        window = new byte[more];
                        System.arraycopy(buffer, 0, window, 0, more);
                        windowLen = more;
                        pos = 0;
                    }
                    String raw = ver.toString();
                    String norm = normalizeBox64Version(raw);
                    return !norm.isEmpty() ? norm : raw;
                }
                // Preserve overlap for the next iteration.
                int keep = Math.min(windowLen, str.length + 32);
                carry = new byte[keep];
                System.arraycopy(window, windowLen - keep, carry, 0, keep);
            }
        }
        catch (IOException e) {}
        return "";
    }
}
