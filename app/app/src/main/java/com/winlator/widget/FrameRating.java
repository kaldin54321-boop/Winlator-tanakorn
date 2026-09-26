package com.winlator.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Utils;
import com.winlator.container.HUDConfig;
import com.winlator.core.BatteryUtils;
import com.winlator.core.CPUStatus;
import com.winlator.core.DefaultVersion;

import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    public enum Mode {DISABLED, SIMPLE, FULL}
    private Mode mode = Mode.FULL;
    private HUDConfig config = new HUDConfig();

    private long lastTime = 0;
    private short frameCount = 0;
    private float lastFPS = 0;

    private ActivityManager activityManager;
    private ActivityManager.MemoryInfo memoryInfo;
    private String box64Version = null;
    private String gpuInfoText = "Turnip (DXVK)";

    private final TextView tvHUD;

    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    private boolean isDragging;

    public FrameRating(Context context) {
        this(context, null);
    }

    public FrameRating(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameRating(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        memoryInfo = new ActivityManager.MemoryInfo();

        tvHUD = new TextView(context);
        tvHUD.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvHUD.setPadding(12, 10, 12, 10);
        tvHUD.setTextSize(12);
        tvHUD.setIncludeFontPadding(false);

        addView(tvHUD, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        setupTouchListener();
        updateHUDView();
    }

    private void setupTouchListener() {
        setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = getX();
                    initialY = getY();
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.hypot(dx, dy) > 8) {
                        isDragging = true;
                        ViewGroup parent = (ViewGroup) getParent();
                        if (parent != null) {
                            int parentWidth = parent.getWidth();
                            int parentHeight = parent.getHeight();
                            int viewWidth = getWidth();
                            int viewHeight = getHeight();

                            float maxX = Math.max(0, parentWidth - viewWidth);
                            float maxY = Math.max(0, parentHeight - viewHeight);

                            setX(Math.max(0, Math.min(maxX, initialX + dx)));
                            setY(Math.max(0, Math.min(maxY, initialY + dy)));
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        config.setHorizontal(!config.isHorizontal());
                        updateHUDView();
                        performClick();
                    }
                    return true;
            }
            return false;
        });
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    public HUDConfig getConfig() {
        return config;
    }

    public void setConfig(HUDConfig config) {
        if (config != null) {
            this.config = config;
            updateHUDView();
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.DISABLED) {
            setVisibility(GONE);
        } else {
            setVisibility(VISIBLE);
            if (mode == Mode.SIMPLE) {
                config.setElements(HUDConfig.ELEMENT_FPS);
            }
            updateHUDView();
        }
    }

    public void setGPUInfo(String gpuInfo) {
        if (gpuInfo != null && !gpuInfo.isEmpty()) {
            this.gpuInfoText = formatRendererName(gpuInfo);
            post(this::updateHUDView);
        }
    }

    /**
     * Pins the HUD to the effective Box64 version resolved by
     * XServerDisplayActivity (shortcut override -> container -> global).
     * Without this the HUD could only guess: it read the on-disk binary
     * once (often before extraction finished) and fell back to the GLOBAL
     * pref, so a container set to 0.3.8 displayed v0.4.4. The value is
     * still re-validated against what is actually installed on every
     * refresh (see below), so a failed extract can never freeze a lie.
     */
    public void setBox64Version(String version) {
        String norm = Box64Utils.normalizeBox64Version(version);
        if (norm.isEmpty()) return;
        String ver = (norm.startsWith("v") || norm.startsWith("V")) ? norm : "v" + norm;
        if (!ver.equals(this.box64Version)) {
            this.box64Version = ver;
            post(this::updateHUDView);
        }
    }

    /**
     * Cheap drift check run on every HUD refresh: if the actually-installed
     * version (current_box64_version pref, written only after a verified
     * extract) disagrees with what is displayed, drop the cache so the
     * next block re-resolves from the on-disk binary. A pref read per
     * ~500ms tick is free; the heavy binary scan happens only on change.
     */
    private void refreshBox64VersionIfStale() {
        if (box64Version == null) return;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            String installed = Box64Utils.normalizeBox64Version(prefs.getString("current_box64_version", ""));
            if (installed.isEmpty()) return;
            String shown = Box64Utils.normalizeBox64Version(box64Version);
            if (!installed.equals(shown)) box64Version = null;
        }
        catch (Exception ignored) {}
    }

    private String formatRendererName(String raw) {
        if (raw == null || raw.isEmpty()) return "Turnip (DXVK)";
        String cleaned = raw.replaceAll("(?i)(Adreno|Mali|PowerVR|Apple|Snapdragon|Xclipse|GeForce|Radeon|Intel|NVIDIA|TM|\\(TM\\)|\\(R\\)|\\d{3,4})", "")
                            .replaceAll("\\s+", " ").trim();
        if (!cleaned.isEmpty()) {
            if (cleaned.equalsIgnoreCase("Turnip") && !cleaned.contains("DXVK")) {
                return "Turnip (DXVK)";
            }
            return cleaned;
        }
        return "Turnip (DXVK)";
    }

    public void reset() {
        frameCount = 0;
        lastTime = SystemClock.elapsedRealtime();
        lastFPS = 0;
    }

    public void update() {
        long time = SystemClock.elapsedRealtime();
        if (time >= lastTime + 500) {
            lastFPS = ((float) (frameCount * 1000) / (time - lastTime));
            post(this);
            lastTime = time;
            frameCount = 0;
        }
        frameCount++;
    }

    @Override
    public void run() {
        if (getVisibility() == GONE && mode != Mode.DISABLED) {
            setVisibility(VISIBLE);
        }
        updateHUDView();
    }

    private void updateHUDView() {
        if (mode == Mode.DISABLED) {
            setVisibility(GONE);
            return;
        }

        SpannableStringBuilder builder = new SpannableStringBuilder();

        String fpsVal = String.format(Locale.ENGLISH, "%d", (int) lastFPS);

        int ramPct = 0;
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
            long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
            ramPct = (int) (((double) usedMem / memoryInfo.totalMem) * 100);
        }
        String ramVal = ramPct + "%";

        // Re-validate the cached label against what is actually installed;
        // may clear the cache (never blocks: pref read only, no I/O here).
        refreshBox64VersionIfStale();

        if (box64Version == null) {
            // Prefer the ON-DISK binary (what `box64 --version` reports), so
            // the HUD can never disagree with the xserver terminal.
            String ver = Box64Utils.extractBinVersion(getContext());
            ver = Box64Utils.normalizeBox64Version(ver);
            if (ver == null || ver.trim().isEmpty()) {
                // Next best: what was actually installed (written only after
                // a verified extract), NOT the global default. The old code
                // fell back straight to the global `box64_version` pref, so a
                // container on 0.3.8 displayed v0.4.4 whenever the binary
                // read raced extraction.
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                ver = Box64Utils.normalizeBox64Version(preferences.getString("current_box64_version", ""));
            }
            if (ver == null || ver.trim().isEmpty()) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                ver = Box64Utils.normalizeBox64Version(preferences.getString("box64_version", DefaultVersion.BOX64));
            }
            if (ver == null || ver.trim().isEmpty()) {
                ver = DefaultVersion.BOX64;
            }
            if (!ver.startsWith("v") && !ver.startsWith("V")) {
                ver = "v" + ver;
            }
            box64Version = ver;
        }

        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        int maxClockSpeed = 0;
        for (short clockSpeed : clockSpeeds) {
            maxClockSpeed = Math.max(maxClockSpeed, clockSpeed);
        }
        int cpuTemp = CPUStatus.getTemperature();
        String cpuVal = CPUStatus.formatClockSpeed(maxClockSpeed) + " | " + box64Version + " | " + cpuTemp + "°C";

        String pwrVal = "0.0W";
        String btrVal = "100%";
        try {
            Intent intent = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (scale > 0) btrVal = (int) ((level / (float) scale) * 100) + "%";

                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                     status == BatteryManager.BATTERY_STATUS_FULL ||
                                     plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                                     plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                                     plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;

                if (isCharging) {
                    pwrVal = "CHARGING";
                } else {
                    int rawVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                    float voltageVolts = rawVoltage > 0 ? rawVoltage / 1000.0f : 3.8f;
                    int currentMicroamperes = 0;
                    BatteryManager batteryManager = (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
                    if (batteryManager != null) {
                        currentMicroamperes = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                        currentMicroamperes = currentMicroamperes != 0 && currentMicroamperes != Integer.MIN_VALUE ? Math.abs(currentMicroamperes) : 0;
                        if (currentMicroamperes <= 1000 && currentMicroamperes > 0) currentMicroamperes *= 1000;
                    }
                    float powerWatts = BatteryUtils.computePower(currentMicroamperes, voltageVolts);
                    pwrVal = String.format(Locale.ENGLISH, "%.1fW", powerWatts);
                }
            }
        } catch (Exception ignored) {}

        String gpuUsageVal = "0%";
        String rdrVal = gpuInfoText;

        boolean isHorizontal = config.isHorizontal();

        appendItem(builder, HUDConfig.ELEMENT_RENDERER, "RDR", rdrVal, 0xFFFFD700, isHorizontal);
        appendItem(builder, HUDConfig.ELEMENT_GPU, "GPU", gpuUsageVal, 0xFFE040FB, isHorizontal);
        appendItem(builder, HUDConfig.ELEMENT_CPU, "CPU", cpuVal, 0xFF00E5FF, isHorizontal);
        appendItem(builder, HUDConfig.ELEMENT_RAM, "RAM", ramVal, 0xFF00E676, isHorizontal);
        appendItem(builder, HUDConfig.ELEMENT_POWER, "PWR", pwrVal, 0xFFFF9100, isHorizontal);
        appendItem(builder, HUDConfig.ELEMENT_TEMP, "TMP", cpuTemp + "°C", 0xFFFF3D00, isHorizontal);
        appendItem(builder, HUDConfig.ELEMENT_BATTERY, "BTR", btrVal, 0xFFFF4081, isHorizontal);
        appendItem(builder, HUDConfig.ELEMENT_FPS, "FPS", fpsVal, 0xFF76FF03, isHorizontal);

        tvHUD.setText(builder);

        int alpha = (int) ((100 - config.getTransparency()) / 100.0f * 220);
        alpha = Math.max(0, Math.min(255, alpha));
        tvHUD.setBackgroundColor(Color.argb(alpha, 0, 0, 0));

        float scale = config.getScale() / 100.0f;
        tvHUD.setScaleX(scale);
        tvHUD.setScaleY(scale);
        tvHUD.setPivotX(0);
        tvHUD.setPivotY(0);
    }

    private void appendItem(SpannableStringBuilder builder, int elementBit, String label, String value, int labelColor, boolean isHorizontal) {
        if (!config.isElementEnabled(elementBit)) return;

        if (builder.length() > 0) {
            if (isHorizontal) {
                int sepStart = builder.length();
                builder.append(" | ");
                builder.setSpan(new ForegroundColorSpan(0xFFCCCCCC), sepStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                builder.append("\n");
            }
        }

        int start = builder.length();
        builder.append(label);
        builder.setSpan(new ForegroundColorSpan(labelColor), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        builder.append(" ");

        start = builder.length();
        builder.append(value);
        builder.setSpan(new ForegroundColorSpan(Color.WHITE), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
