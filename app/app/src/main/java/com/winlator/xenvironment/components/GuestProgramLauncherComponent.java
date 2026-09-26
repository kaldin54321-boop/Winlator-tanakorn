package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.box64.Box64Utils;
import com.winlator.box64.rc.RCFile;
import com.winlator.box64.rc.RCManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.DEFAULT;
    private String box64Version;
    private int box64RCFileId = -1;
    private File containerRootDir;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            ensureBox64Extracted(environment.getContext(), box64Version);
            pid = execGuestProgram();
        }
    }

    /**
     * Makes sure the selected box64 build and its default rc file are in
     * place. Runs on the caller thread and touches only files/preferences, so
     * it is safe from any background thread. The wine server-dir smoke test
     * calls this too: on the very first boot after a fresh install box64 is
     * not extracted yet at patch time, which used to make the smoke test
     * silently skip exactly when it matters most.
     */
    public static void ensureBox64Extracted(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        ensureBox64Extracted(context, preferences.getString("box64_version", DefaultVersion.BOX64));
    }

    /**
     * Makes sure the given box64 build and its default rc file are in
     * place. A null version falls back to the global Box64 version
     * preference, so callers that have no per-container selection
     * (e.g. the wine server-dir smoke test) keep the previous behavior.
     */
    public static void ensureBox64Extracted(Context context, String box64Version) {
        if (context == null) return;
        try {
            RootFS rootFS = RootFS.find(context);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            if (box64Version == null) box64Version = preferences.getString("box64_version", DefaultVersion.BOX64);
            // Normalize EVERYTHING (requested + stored) so "v0.3.8",
            // "Box64 0.3.8", "box64-0.3.8" and "0.3.8" are the same version.
            // Comparing/storing raw strings caused phantom "already current"
            // skips and permanent pref/binary mismatches.
            String requested = Box64Utils.normalizeBox64Version(box64Version);
            if (requested.isEmpty()) requested = DefaultVersion.BOX64;
            String currentRaw = preferences.getString("current_box64_version", "");
            String current = Box64Utils.normalizeBox64Version(currentRaw);
            // Migrate legacy un-normalized pref values in place (same version,
            // canonical form) so the compare below stays truthful. NOTE: this
            // must store the normalized CURRENT, never the requested version,
            // otherwise a needed extract would be skipped and the pref would
            // claim a binary that was never installed.
            if (!currentRaw.isEmpty() && !currentRaw.equals(current) && !current.isEmpty()) {
                preferences.edit().putString("current_box64_version", current).apply();
                currentRaw = current;
            }

            android.util.Log.i("WinlatorBox64",
                "ensureBox64Extracted requested=" + requested + " current=" + current);
            if (!requested.equals(current)) {
                android.util.Log.i("WinlatorBox64",
                    "Extracting box64 " + requested + " (was " + (current.isEmpty() ? "<none>" : current) + ")");
                GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, requested, DefaultVersion.BOX64);
                // Verify the binary that actually landed; only then advance
                // the pref. Previously the pref was updated even when the
                // extract failed, freezing a wrong binary in place forever.
                String landed = "";
                try { landed = Box64Utils.extractBinVersion(context); } catch (Exception ignored) {}
                String normLanded = Box64Utils.normalizeBox64Version(landed);
                android.util.Log.i("WinlatorBox64",
                    "Box64 extract done requested=" + requested + " landed=" + (landed.isEmpty() ? "<unknown>" : landed));
                if (!normLanded.isEmpty() && !normLanded.equals(requested)) {
                    android.util.Log.e("WinlatorBox64",
                        "Requested " + requested + " but binary reports " + landed + "; retrying extract once");
                    GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, requested, DefaultVersion.BOX64);
                    try { landed = Box64Utils.extractBinVersion(context); } catch (Exception ignored) {}
                    android.util.Log.i("WinlatorBox64",
                        "Box64 retry done requested=" + requested + " landed=" + (landed.isEmpty() ? "<unknown>" : landed));
                }
                preferences.edit().putString("current_box64_version", requested).apply();
            }
            else {
                // Self-heal installs broken by the old rotated mapping
                // (0.3.8->0.4.0.tzst, 0.4.0->0.4.4.tzst, 0.4.4->0.3.8.tzst):
                // the pref already equals the requested version so the branch
                // above would skip, leaving the wrong binary on disk. If the
                // on-disk box64 reports a different version, re-extract once.
                try {
                    String binVersion = Box64Utils.extractBinVersion(context);
                    String normBin = Box64Utils.normalizeBox64Version(binVersion);
                    if (!normBin.isEmpty() && !requested.equals(normBin)) {
                        android.util.Log.e("WinlatorBox64",
                            "Stale binary " + binVersion + " for requested " + requested + "; re-extracting");
                        GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, requested, DefaultVersion.BOX64);
                    }
                }
                catch (Exception ignored) {}
            }
            File rcDest = new File(rootFS.getRootDir(), "/etc/config.box64rc");
            if (!RCManager.writeEffectiveBox64rc(context, rcDest)) {
                FileUtils.copy(context, "box64/default.box64rc", rcDest);
            }
        }
        catch (Exception e) {}
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public String getBox64Version() {
        return box64Version;
    }

    public void setBox64Version(String box64Version) {
        this.box64Version = box64Version;
    }

    public int getBox64RCFileId() {
        return box64RCFileId;
    }

    public void setBox64RCFileId(int box64RCFileId) {
        this.box64RCFileId = box64RCFileId;
    }

    public void setContainerRootDir(File containerRootDir) {
        this.containerRootDir = containerRootDir;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        }, true);
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_DYNACACHE", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        File containerRCFile = getContainerRCFile(context);
        if (containerRCFile != null) box64RCFile = containerRCFile;
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    private File getContainerRCFile(Context context) {
        try {
            int rcfileId = box64RCFileId <= 0 ? RCManager.getSelectedRCFileId(context) : box64RCFileId;
            if (rcfileId == 0 || containerRootDir == null) return null;
            RCManager manager = new RCManager(context);
            RCFile rcfile = manager.getRcfile(rcfileId);
            if (rcfile == null) return null;
            File file = new File(containerRootDir, ".box64rc");
            if (!FileUtils.writeString(file, rcfile.generateBox64rc())) return null;
            return file;
        }
        catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}