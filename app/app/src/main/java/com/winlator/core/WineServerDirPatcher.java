package com.winlator.core;

import android.content.Context;
import android.system.Os;

import com.winlator.container.Container;
import com.winlator.xenvironment.RootFS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes stock Linux Wine builds (e.g. Kron4ek) bootable on Android.
 *
 * <p>Upstream wineserver computes its server dir as {@code /tmp/.wine-<uid>}
 * unless compiled with {@code __ANDROID__} (Winlator@Frost's own builds instead
 * hardcode a rootfs path). Desktop builds like Kron4ek therefore die
 * instantly on Android ({@code wineserver: mkdir /tmp/.wine-UID: Permission
 * denied}) and the container drops back to the main menu. The path is baked
 * into two binaries that must agree with each other: {@code bin/wineserver}
 * (creates the dir, literal {@code /tmp/.wine-%u}) and the client's
 * {@code lib/wine/<arch>-unix/ntdll.so} (locates it, literal
 * {@code /tmp/.wine-%u/server-%llx-%llx}).
 *
 * <p>This patcher retargets both literals (each referenced by RIP-relative
 * LEAs) to {@code <rootfs>/tmp/...} computed at runtime for this device.
 * The longer replacement is appended at EOF, the last PT_LOAD is extended
 * to map it, and each LEA's disp32 is rewritten — no code bytes change
 * size. Patched (Android-aware) builds contain neither literal and are
 * never touched. Stock files are backed up once as {@code *.winlator-stock};
 * every step is verified before anything is written, the written files are
 * re-verified afterwards, and files are replaced atomically, so an
 * unsupported build is simply left alone. After patching, both binaries
 * must pass a standalone {@code --version} smoke test under box64;
 * otherwise the stock files are restored and the build is marked revoked
 * ({@code .winlator-serverdir-revoked}) so it is never patched again.
 */
public abstract class WineServerDirPatcher {
    private static final String TAG = "WineServerDir";
    private static final int MAX_BINARY_SIZE = 64 * 1024 * 1024;
    private static final int EM_X86_64 = 62;
    private static final int PT_LOAD = 1;
    private static final int PF_R = 4;
    private static final String BACKUP_SUFFIX = ".winlator-stock";
    private static final String TMP_SUFFIX = ".winlator-tmp";
    private static final String REVOKE_MARKER = ".winlator-serverdir-revoked";

    private static class LoadSegment {
        long fileOffset;
        long vaddr;
        long fileSize;
        long memSize;
        int flags;
    }

    private static class LeaRef {
        int insnOffset;
        long targetOffset;
    }

    /**
     * Patches a custom Wine install in place so its wineserver and clients
     * use a writable server dir. No-op for the main Wine version and for
     * builds that do not carry the upstream {@code /tmp} literals.
     *
     * @return true if any file was changed.
     */
    public static boolean ensurePatched(Context context, Container container) {
        if (context == null || container == null || WineInfo.isMainWineVersion(container.getWineVersion())) return false;
        try {
            String winePath = WineInfo.fromIdentifier(context, container.getWineVersion()).path;
            if (winePath == null) return false;
            File wineRoot = new File(winePath);
            File revokeMarker = new File(wineRoot, REVOKE_MARKER);
            if (revokeMarker.isFile()) {
                android.util.Log.i(TAG, "Patch revoked for " + container.getWineVersion() + ", skipping");
                return false;
            }
            String serverBase = RootFS.find(context).getRootDir().getPath() + "/tmp";
            boolean changed = false;
            List<File> attempted = new ArrayList<>();

            File serverBin = new File(wineRoot, "bin/wineserver");
            byte[] serverOriginal = ascii("/tmp/.wine-%u\0");
            byte[] serverReplacement = ascii(serverBase + "/.wine-%u\0");
            if (serverBin.isFile()) {
                attempted.add(serverBin);
                if (patchOne(serverBin, serverOriginal, serverReplacement)) changed = true;
            }

            final String[] clientPaths = {
                "lib/wine/x86_64-unix/ntdll.so",
                "lib64/wine/x86_64-unix/ntdll.so",
                "lib/wine/i386-unix/ntdll.so"
            };
            byte[] clientOriginal = ascii("/tmp/.wine-%u/server-%llx-%llx\0");
            byte[] clientReplacement = ascii(serverBase + "/.wine-%u/server-%llx-%llx\0");
            for (int i = 0; i < clientPaths.length; i++) {
                File client = new File(wineRoot, clientPaths[i]);
                if (i > 0 && !client.isFile()) continue;
                if (client.isFile()) attempted.add(client);
                if (patchOne(client, clientOriginal, clientReplacement)) changed = true;
            }
            android.util.Log.i(TAG, "ensurePatched " + container.getWineVersion() + " changed=" + changed);
            if (changed && !smokeTest(context, container, wineRoot)) {
                android.util.Log.e(TAG, "Smoke test failed for " + container.getWineVersion()
                    + ", restoring stock files and revoking future patches");
                for (File file : attempted) restoreBackup(file);
                try {
                    FileUtils.writeString(revokeMarker, "1\n");
                }
                catch (Exception ignored) {}
                return true;
            }
            return changed;
        }
        catch (Exception e) {
            return false;
        }
    }

    private static void restoreBackup(File target) {
        try {
            File backup = new File(target.getPath() + BACKUP_SUFFIX);
            if (!backup.isFile()) return;
            File tmp = new File(target.getPath() + ".winlator-restore-tmp");
            if (!FileUtils.copy(backup, tmp)) return;
            int mode = 0644;
            try {
                mode = Os.stat(backup.getAbsolutePath()).st_mode & 0777;
            }
            catch (Exception ignored) {}
            FileUtils.chmod(tmp, mode);
            if (tmp.renameTo(target)) {
                android.util.Log.i(TAG, "Restored stock " + target.getName());
            }
            else {
                FileUtils.delete(tmp);
            }
        }
        catch (Exception ignored) {}
    }

    /**
     * Runs the patched binaries standalone (--version exits before any
     * prefix work). x86_64 ELFs cannot execute directly on ARM Android, so
     * both go through box64 exactly like a real container boot.
     */
    private static boolean smokeTest(Context context, Container container, File wineRoot) {
        try {
            // Use the CONTAINER's effective Box64 version, not the global pref.
            // The old code always extracted the global version here, clobbering
            // a per-container choice (e.g. container=0.3.8, global=0.4.4) and
            // producing the "mixed/inconsistent version" reports.
            String effectiveBox64 = container != null && container.getBox64Version() != null
                ? container.getBox64Version() : null;
            if (effectiveBox64 == null || effectiveBox64.isEmpty()) {
                try {
                    effectiveBox64 = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .getString("box64_version", DefaultVersion.BOX64);
                }
                catch (Exception ignored) { effectiveBox64 = DefaultVersion.BOX64; }
            }
            // On the first boot after a fresh install box64 has not been
            // extracted yet (that happens in the launcher afterwards), so make
            // sure it is in place instead of skipping the test when it matters
            // most. This only touches files, never the UI.
            // Probe the version the session will actually run (including the
            // Wine 11 guard), otherwise the test can pass on a binary that is
            // never executed while the real one crashes.
            int wineMajor = -1;
            try {
                WineInfo wineInfo = container != null
                    ? WineInfo.fromIdentifier(context, container.getWineVersion()) : null;
                wineMajor = WineInstaller.wineMajorVersion(wineInfo != null ? wineInfo.version : null);
            }
            catch (Exception ignored) {}
            effectiveBox64 = com.winlator.box64.Box64Utils.resolveWineCompatibleBox64Version(effectiveBox64, wineMajor);
            com.winlator.xenvironment.components.GuestProgramLauncherComponent.ensureBox64Extracted(context, effectiveBox64);
            RootFS rootFS = RootFS.find(context);
            File rootDir = rootFS.getRootDir();
            File box64Bin = new File(rootDir, "usr/local/bin/box64");
            if (!box64Bin.isFile()) {
                android.util.Log.i(TAG, "box64 not installed yet, skipping smoke test");
                return true;
            }
            EnvVars envVars = new EnvVars();
            envVars.put("PATH", new File(wineRoot, "bin").getPath() + ":/usr/bin:/bin");
            envVars.put("TMPDIR", rootDir.getPath() + "/tmp");
            envVars.put("HOME", rootDir.getPath() + RootFS.HOME_PATH);
            envVars.put("USER", RootFS.USER);
            // Point at a throwaway prefix so the probes can never touch a
            // real container even if a future wine version does more work
            // for --version than just printing.
            envVars.put("WINEPREFIX", rootDir.getPath() + "/tmp/wine-smoke-test");
            envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
            envVars.put("BOX64_LD_LIBRARY_PATH", rootDir.getPath() + "/lib/x86_64-linux-gnu");
            envVars.put("BOX64_NOBANNER", "1");
            String box64 = box64Bin.getPath();
            File serverBin = new File(wineRoot, "bin/wineserver");
            if (!runAndWait(rootDir, envVars, box64 + " " + serverBin.getPath() + " --version", 30)) {
                android.util.Log.e(TAG, "wineserver smoke test failed");
                return false;
            }
            // Probe the loader box64 can actually execute: classic non-WoW64
            // builds ship a 32-bit bin/wine that box64 cannot run, so test
            // bin/wine64 there instead of failing a healthy install.
            File wineBin = new File(wineRoot, "bin/wine");
            File wineBin64 = new File(wineRoot, "bin/wine64");
            File probeBin = wineBin;
            try {
                String loader = WineInstaller.getWineLoaderExecutable(wineRoot, wineRoot.getName());
                if (loader.equals("wine64") && wineBin64.isFile()) probeBin = wineBin64;
            }
            catch (Exception ignored) {}
            if (probeBin.isFile() && !runAndWait(rootDir, envVars, box64 + " " + probeBin.getPath() + " --version", 60)) {
                android.util.Log.e(TAG, "wine smoke test failed");
                return false;
            }
            android.util.Log.i(TAG, "Smoke test passed");
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    private static boolean runAndWait(File workDir, EnvVars envVars, String command, int timeoutSec) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final int[] code = {Integer.MIN_VALUE};
        int pid = -1;
        try {
            pid = ProcessHelper.exec(command, envVars, workDir, (status) -> {
                code[0] = status;
                latch.countDown();
            });
        }
        catch (Exception e) {
            return false;
        }
        try {
            if (!latch.await(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                if (pid != -1) {
                    try {
                        android.os.Process.killProcess(pid);
                    }
                    catch (Exception ignored) {}
                }
                return false;
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return code[0] == 0;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] readAll(File file) throws Exception {
        long length = file.length();
        if (length <= 0 || length > MAX_BINARY_SIZE) throw new Exception("bad size");
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) length)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            return out.toByteArray();
        }
    }

    private static List<Integer> indexOfAll(byte[] data, byte[] needle) {
        List<Integer> hits = new ArrayList<>();
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            hits.add(i);
        }
        return hits;
    }

    private static class ElfMap {
        final List<LoadSegment> loads = new ArrayList<>();
        long phoff;
        int phentsize;
        int phnum;
    }

    private static ElfMap parseElf(byte[] data) throws Exception {
        if (data.length < 64 || data[0] != 0x7F || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            throw new Exception("not ELF");
        }
        if (data[4] != 2 || data[5] != 1) throw new Exception("not 64-bit LE");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getShort(18) != EM_X86_64) throw new Exception("not x86_64");
        ElfMap map = new ElfMap();
        map.phoff = buf.getLong(0x20);
        map.phentsize = buf.getShort(0x36) & 0xFFFF;
        map.phnum = buf.getShort(0x38) & 0xFFFF;
        if (map.phoff <= 0 || map.phoff >= data.length || map.phentsize < 56 || map.phnum <= 0 || map.phnum > 64) {
            throw new Exception("bad PHDR table");
        }
        for (int i = 0; i < map.phnum; i++) {
            int o = (int) (map.phoff + (long) i * map.phentsize);
            if (o + 56 > data.length) throw new Exception("PHDR overflow");
            if (buf.getInt(o) != PT_LOAD) continue;
            LoadSegment seg = new LoadSegment();
            seg.fileOffset = buf.getLong(o + 8);
            seg.vaddr = buf.getLong(o + 16);
            seg.fileSize = buf.getLong(o + 32);
            seg.memSize = buf.getLong(o + 40);
            seg.flags = buf.getInt(o + 4);
            if (seg.fileOffset < 0 || seg.fileSize < 0 || seg.memSize < 0
                    || seg.fileOffset + seg.fileSize < 0 || seg.fileOffset + seg.fileSize > data.length) {
                throw new Exception("bad LOAD range");
            }
            map.loads.add(seg);
        }
        if (map.loads.isEmpty()) throw new Exception("no LOAD segments");
        return map;
    }

    private static Long offsetToVaddr(ElfMap map, long offset) {
        for (LoadSegment seg : map.loads) {
            if (offset >= seg.fileOffset && offset < seg.fileOffset + seg.fileSize) {
                return seg.vaddr + (offset - seg.fileOffset);
            }
        }
        return null;
    }

    private static Long vaddrToOffset(ElfMap map, long vaddr) {
        for (LoadSegment seg : map.loads) {
            if (vaddr >= seg.vaddr && vaddr < seg.vaddr + seg.fileSize) {
                return seg.fileOffset + (vaddr - seg.vaddr);
            }
        }
        return null;
    }

    /** Finds RIP-relative LEA instructions (REX + 0x8D + modrm(r/m=101)) resolving to targetOffset. */
    private static List<LeaRef> findLeaRefs(byte[] data, ElfMap map, long targetOffset) {
        List<LeaRef> refs = new ArrayList<>();
        for (int i = 0; i + 7 <= data.length; i++) {
            int rex = data[i] & 0xFF;
            if (rex < 0x40 || rex > 0x4F || (data[i + 1] & 0xFF) != 0x8D) continue;
            if (((data[i + 2] & 0xFF) & 0xC7) != 0x05) continue;
            int disp = ByteBuffer.wrap(data, i + 3, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            Long insnVaddr = offsetToVaddr(map, i);
            if (insnVaddr == null) continue;
            Long resolved = vaddrToOffset(map, insnVaddr + 7L + disp);
            if (resolved != null && resolved == targetOffset) {
                LeaRef ref = new LeaRef();
                ref.insnOffset = i;
                ref.targetOffset = targetOffset;
                refs.add(ref);
            }
        }
        return refs;
    }

    private static boolean patchOne(File target, byte[] original, byte[] replacement) {
        if (target == null || !target.isFile() || replacement.length == 0) return false;
        try {
            byte[] data = readAll(target);
            if (indexOfAll(data, original).isEmpty()) return false; // not a patchable (Android-unaware) build
            ElfMap map = parseElf(data);

            List<Integer> replacementHits = indexOfAll(data, replacement);
            // Collect refs pointing at the original literal or at a previous replacement copy.
            List<LeaRef> refs = new ArrayList<>();
            for (int off : indexOfAll(data, original)) refs.addAll(findLeaRefs(data, map, off));
            for (int off : replacementHits) refs.addAll(findLeaRefs(data, map, off));
            if (refs.isEmpty()) return false;

            // Already converged: every known ref aims at a copy of the current replacement.
            int currentRefs = 0;
            for (LeaRef ref : refs) {
                for (int off : replacementHits) {
                    if (ref.targetOffset == off) {
                        currentRefs++;
                        break;
                    }
                }
            }
            if (currentRefs == refs.size()) return false;

            // Reuse an existing copy when present, else append at EOF.
            int stringOffset;
            if (!replacementHits.isEmpty()) {
                stringOffset = replacementHits.get(0);
            }
            else {
                LoadSegment last = null;
                for (LoadSegment seg : map.loads) {
                    if (last == null || seg.fileOffset + seg.fileSize > last.fileOffset + last.fileSize) last = seg;
                }
                if (last == null || (last.flags & PF_R) == 0) return false;
                long end = last.fileOffset + last.fileSize;
                // The appended string sits at EOF, which may lie beyond this
                // segment behind section headers: extend the segment over the
                // whole [end, EOF+len) span so the new bytes are mapped.
                long newEnd = (long) data.length + replacement.length;
                if (newEnd <= end || newEnd - end > 1024 * 1024) return false; // sanity
                for (LoadSegment seg : map.loads) {
                    if (seg == last) continue;
                    long s = seg.fileOffset, e = s + seg.fileSize;
                    if (end < e && newEnd > s) return false; // overlap guard
                }
                byte[] grown = new byte[data.length + replacement.length];
                System.arraycopy(data, 0, grown, 0, data.length);
                System.arraycopy(replacement, 0, grown, data.length, replacement.length);
                data = grown;
                stringOffset = (int) (data.length - replacement.length);
                // Extend the last LOAD over the appended bytes.
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                // Locate this segment's PHDR entry again by file offset match.
                boolean extended = false;
                for (int i = 0; i < map.phnum; i++) {
                    long off = map.phoff + (long) i * map.phentsize;
                    if (off > Integer.MAX_VALUE - 56 || off + 56 > data.length) break;
                    int o = (int) off;
                    if (buf.getInt(o) != PT_LOAD) continue;
                    if (buf.getLong(o + 8) == last.fileOffset && buf.getLong(o + 32) == last.fileSize) {
                        buf.putLong(o + 32, newEnd - last.fileOffset);
                        long growth = newEnd - last.fileOffset - last.fileSize;
                        long newMem = last.memSize + growth;
                        if (newMem < last.memSize) return false; // overflow guard
                        buf.putLong(o + 40, newMem);
                        extended = true;
                        break;
                    }
                }
                if (!extended) return false;
                // Refresh maps with the grown segment for VA math below.
                map = parseElf(data);
            }

            Long stringVaddr = offsetToVaddr(map, stringOffset);
            if (stringVaddr == null) return false;
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            for (LeaRef ref : refs) {
                Long insnVaddr = offsetToVaddr(map, ref.insnOffset);
                if (insnVaddr == null) return false;
                long disp = stringVaddr - (insnVaddr + 7L);
                if (disp > Integer.MAX_VALUE || disp < Integer.MIN_VALUE) return false;
                buf.putInt(ref.insnOffset + 3, (int) disp);
            }

            int mode = 0644;
            try {
                mode = Os.stat(target.getAbsolutePath()).st_mode & 0777;
            }
            catch (Exception ignored) {}
            File backup = new File(target.getPath() + BACKUP_SUFFIX);
            if (!backup.isFile()) {
                try (OutputStream out = new FileOutputStream(backup)) {
                    out.write(readAll(target));
                }
                FileUtils.chmod(backup, mode);
            }
            File tmp = new File(target.getPath() + TMP_SUFFIX);
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            FileUtils.chmod(tmp, mode);
            if (!tmp.renameTo(target)) {
                FileUtils.delete(tmp);
                return false;
            }
            if (!verifyPatched(target, replacement)) {
                android.util.Log.e(TAG, "Post-write verification failed for " + target.getName()
                    + ", restoring backup");
                restoreBackup(target);
                return false;
            }
            android.util.Log.i(TAG, "Patched " + target.getName() + " (" + data.length
                + " bytes, " + refs.size() + " ref(s) retargeted)");
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    /** Re-reads a patched file and confirms the replacement is live: present and referenced. */
    private static boolean verifyPatched(File target, byte[] replacement) {
        try {
            byte[] data = readAll(target);
            ElfMap map = parseElf(data);
            for (int off : indexOfAll(data, replacement)) {
                if (!findLeaRefs(data, map, off).isEmpty()) return true;
            }
            return false;
        }
        catch (Exception e) {
            return false;
        }
    }
}
