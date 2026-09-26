package com.winlator.contentdialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.core.AppUtils;
import com.winlator.core.KeyValueSet;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.CRTEffect;
import com.winlator.renderer.effects.ColorEffect;
import com.winlator.renderer.effects.FXAAEffect;
import com.winlator.renderer.effects.FrameGenerationEffect;
import com.winlator.renderer.effects.HDREffect;
import com.winlator.renderer.effects.ToonEffect;
import com.winlator.widget.FrameGenerationView;
import com.winlator.widget.SeekBar;

import java.util.ArrayList;
import java.util.LinkedHashSet;

public class ScreenEffectDialog extends ContentDialog {
    private final XServerDisplayActivity activity;
    private final SharedPreferences preferences;
    private final Spinner sProfile;
    private final Spinner sFpsLimit;
    private final SeekBar sbBrightness;
    private final SeekBar sbContrast;
    private final SeekBar sbGamma;
    private final CheckBox cbEnableFXAA;
    private final CheckBox cbEnableCRTShader;
    private final CheckBox cbEnableHDR;
    private final SeekBar sbHDRIntensity;
    private final TextView tvHDRIntensity;
    private final CheckBox cbEnableToonShader;
    private final SeekBar sbToonIntensity;
    private final TextView tvToonIntensity;
    private final CheckBox cbEnableFramePacing;

    public ScreenEffectDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.screen_effect_dialog);
        this.activity = activity;
        setTitle(R.string.screen_effect);
        setIcon(R.drawable.icon_screen_effect);

        preferences = PreferenceManager.getDefaultSharedPreferences(activity);

        GLRenderer renderer = activity.getXServerView().getRenderer();
        ColorEffect currentColorEffect = renderer.effectComposer.getEffect(ColorEffect.class);
        final ColorEffect colorEffect = currentColorEffect != null ? currentColorEffect : new ColorEffect();
        final FXAAEffect fxaaEffect = renderer.effectComposer.getEffect(FXAAEffect.class);
        final CRTEffect crtEffect = renderer.effectComposer.getEffect(CRTEffect.class);
        final HDREffect hdrEffect = renderer.effectComposer.getEffect(HDREffect.class);
        final ToonEffect toonEffect = renderer.effectComposer.getEffect(ToonEffect.class);

        sProfile = findViewById(R.id.SProfile);
        sFpsLimit = findViewById(R.id.SFpsLimit);
        sbBrightness = findViewById(R.id.SBBrightness);
        sbContrast = findViewById(R.id.SBContrast);
        sbGamma = findViewById(R.id.SBGamma);
        cbEnableFXAA = findViewById(R.id.CBEnableFXAA);
        cbEnableCRTShader = findViewById(R.id.CBEnableCRTShader);
        cbEnableHDR = findViewById(R.id.CBEnableHDR);
        sbHDRIntensity = findViewById(R.id.SBHDRIntensity);
        tvHDRIntensity = findViewById(R.id.TVHDRIntensity);
        cbEnableToonShader = findViewById(R.id.CBEnableToonShader);
        sbToonIntensity = findViewById(R.id.SBToonIntensity);
        tvToonIntensity = findViewById(R.id.TVToonIntensity);
        cbEnableFramePacing = findViewById(R.id.CBEnableFramePacing);

        initFpsLimitSpinner();

        sbBrightness.setValue(colorEffect.getBrightness() * 100);
        sbContrast.setValue(colorEffect.getContrast() * 100);
        sbGamma.setValue(colorEffect.getGamma());
        cbEnableFXAA.setChecked(fxaaEffect != null);
        cbEnableCRTShader.setChecked(crtEffect != null);
        cbEnableHDR.setChecked(hdrEffect != null);
        sbHDRIntensity.setValue((int)(hdrEffect != null ? hdrEffect.getIntensity() * 100 : 100));
        updateHDRControls();

        cbEnableHDR.setOnCheckedChangeListener((v, checked) -> updateHDRControls());

        cbEnableToonShader.setChecked(toonEffect != null);
        sbToonIntensity.setValue((int)(toonEffect != null ? toonEffect.getIntensity() * 100 : 100));
        updateToonControls();

        cbEnableToonShader.setOnCheckedChangeListener((v, checked) -> updateToonControls());

        cbEnableFramePacing.setChecked(activity.isFramePacingEnabled());
        cbEnableFramePacing.setOnCheckedChangeListener((v, checked) -> {
            // Apply instantly, same as the FPS limiter spinner.
            activity.setFramePacingEnabled(checked);
        });

        sProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedProfile = position > 0 ? sProfile.getItemAtPosition(position).toString() : null;
                if (selectedProfile != null) loadProfile(selectedProfile);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadProfileSpinner(sProfile, activity.getScreenEffectProfile());
        findViewById(R.id.BTAddProfile).setOnClickListener((v) -> ContentDialog.prompt(activity, R.string.profile_name, null, (name) -> {
            addProfile(name, sProfile);
        }));

        findViewById(R.id.BTRemoveProfile).setOnClickListener((v) -> {
            String selectedProfile = sProfile.getSelectedItemPosition() > 0 ? sProfile.getSelectedItem().toString() : null;
            if (selectedProfile != null) {
                ContentDialog.confirm(activity, R.string.do_you_want_to_remove_this_profile, () -> {
                    removeProfile(selectedProfile, sProfile);
                });
            }
            else AppUtils.showToast(activity, R.string.no_profile_selected);
        });

        findViewById(R.id.BTHUDConfig).setOnClickListener((v) -> {
            dismiss();
            activity.showHUDConfigDialog();
        });

        findViewById(R.id.BTFrameGeneration).setOnClickListener((v) -> addFrameGenerationView());

        setOnConfirmCallback(() -> {
            float brightness = sbBrightness.getValue();
            float contrast = sbContrast.getValue();
            float gamma = sbGamma.getValue();

            if (brightness != 0 || contrast != 0 || gamma != 1.0f) {
                colorEffect.setBrightness(brightness / 100.0f);
                colorEffect.setContrast(contrast / 100.0f);
                colorEffect.setGamma(gamma);

                renderer.effectComposer.addEffect(colorEffect);
            }
            else renderer.effectComposer.removeEffect(colorEffect);

            if (cbEnableFXAA.isChecked()) {
                if (fxaaEffect == null) renderer.effectComposer.addEffect(new FXAAEffect());
            }
            else if (fxaaEffect != null) renderer.effectComposer.removeEffect(fxaaEffect);

            if (cbEnableCRTShader.isChecked()) {
                if (crtEffect == null) renderer.effectComposer.addEffect(new CRTEffect());
            }
            else if (crtEffect != null) renderer.effectComposer.removeEffect(crtEffect);

            if (cbEnableHDR.isChecked()) {
                HDREffect hdrToApply = hdrEffect;
                if (hdrToApply == null) {
                    hdrToApply = new HDREffect();
                    renderer.effectComposer.addEffect(hdrToApply);
                }
                hdrToApply.setIntensity(sbHDRIntensity.getValue() / 100.0f);
            }
            else if (hdrEffect != null) renderer.effectComposer.removeEffect(hdrEffect);

            if (cbEnableToonShader.isChecked()) {
                ToonEffect toonToApply = toonEffect;
                if (toonToApply == null) {
                    toonToApply = new ToonEffect();
                    renderer.effectComposer.addEffect(toonToApply);
                }
                toonToApply.setIntensity(sbToonIntensity.getValue() / 100.0f);
            }
            else if (toonEffect != null) renderer.effectComposer.removeEffect(toonEffect);

            activity.setGameFpsLimit(getSelectedFpsLimit());
            activity.setFramePacingEnabled(cbEnableFramePacing.isChecked());
            saveProfile(sProfile);
        });

        Button resetButton = findViewById(R.id.BTReset);
        resetButton.setVisibility(View.VISIBLE);
        resetButton.setOnClickListener((v) -> resetSettings());
    }

    private void initFpsLimitSpinner() {
        java.util.List<String> items = java.util.Arrays.asList(
            activity.getString(R.string.unlimited), "30", "60", "120");
        sFpsLimit.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, items));
        sFpsLimit.setSelection(fpsToPosition(activity.getGameFpsLimit()), false);
        sFpsLimit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Apply instantly, same as Steamlator's LimitFpsDialog.
                activity.setGameFpsLimit(getSelectedFpsLimit());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private int getSelectedFpsLimit() {
        int position = sFpsLimit != null ? sFpsLimit.getSelectedItemPosition() : 0;
        return position == 1 ? 30 : position == 2 ? 60 : position == 3 ? 120 : 0;
    }

    private static int fpsToPosition(int fps) {
        if (fps == 30) return 1;
        if (fps == 60) return 2;
        if (fps == 120) return 3;
        return 0;
    }

    private void resetSettings() {
        sbBrightness.setValue(0);
        sbContrast.setValue(0);
        sbGamma.setValue(1.0f);

        cbEnableFXAA.setChecked(false);
        cbEnableCRTShader.setChecked(false);
        cbEnableHDR.setChecked(false);
        sbHDRIntensity.setValue(100);
        updateHDRControls();
        cbEnableToonShader.setChecked(false);
        sbToonIntensity.setValue(100);
        updateToonControls();
        cbEnableFramePacing.setChecked(true);
        activity.setFramePacingEnabled(true);
        if (sFpsLimit != null) sFpsLimit.setSelection(0);
        activity.setGameFpsLimit(0);
    }

    private void updateHDRControls() {
        int visibility = cbEnableHDR.isChecked() ? View.VISIBLE : View.GONE;
        sbHDRIntensity.setVisibility(visibility);
        tvHDRIntensity.setVisibility(visibility);
    }

    private void updateToonControls() {
        int visibility = cbEnableToonShader.isChecked() ? View.VISIBLE : View.GONE;
        sbToonIntensity.setVisibility(visibility);
        tvToonIntensity.setVisibility(visibility);
    }

    private void saveProfile(Spinner sProfile) {
        String selectedProfile = sProfile.getSelectedItemPosition() > 0 ? sProfile.getSelectedItem().toString() : null;
        if (selectedProfile != null) {
            LinkedHashSet<String> oldProfiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
            LinkedHashSet<String> newProfiles = new LinkedHashSet<>();

            KeyValueSet settings = new KeyValueSet();
            settings.put("brightness", sbBrightness.getValue());
            settings.put("contrast", sbContrast.getValue());
            settings.put("gamma", sbGamma.getValue());
            settings.put("fxaa", cbEnableFXAA.isChecked());
            settings.put("crt_shader", cbEnableCRTShader.isChecked());
            settings.put("hdr", cbEnableHDR.isChecked());
            settings.put("hdr_intensity", sbHDRIntensity.getValue());
            settings.put("toon_shader", cbEnableToonShader.isChecked());
            settings.put("toon_intensity", sbToonIntensity.getValue());
            settings.put("frame_pacing", cbEnableFramePacing.isChecked());
            settings.put("fps_limit", getSelectedFpsLimit());

            for (String profile : oldProfiles) {
                String name = profile.split(":")[0];
                newProfiles.add(name.equals(selectedProfile) ? selectedProfile+":"+settings : profile);
            }

            preferences.edit().putStringSet("screen_effect_profiles", newProfiles).apply();
        }

        activity.setScreenEffectProfile(selectedProfile);
    }

    private void loadProfile(String name) {
        LinkedHashSet<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        for (String profile : profiles) {
            String[] parts = profile.split(":");
            if (parts[0].equals(name)) {
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    KeyValueSet settings = new KeyValueSet(parts[1]);

                    sbBrightness.setValue(settings.getFloat("brightness", 0.0f));
                    sbContrast.setValue(settings.getFloat("contrast", 1.0f));
                    sbGamma.setValue(settings.getFloat("gamma", 1.0f));
                    cbEnableFXAA.setChecked(settings.getBoolean("fxaa", false));
                    cbEnableCRTShader.setChecked(settings.getBoolean("crt_shader", false));
                    cbEnableHDR.setChecked(settings.getBoolean("hdr", false));
                    sbHDRIntensity.setValue(settings.getInt("hdr_intensity", 100));
                    updateHDRControls();
                    cbEnableToonShader.setChecked(settings.getBoolean("toon_shader", false));
                    sbToonIntensity.setValue(settings.getInt("toon_intensity", 100));
                    updateToonControls();
                    boolean framePacing = settings.getBoolean("frame_pacing", true);
                    cbEnableFramePacing.setChecked(framePacing);
                    activity.setFramePacingEnabled(framePacing);
                    int fpsLimit = settings.getInt("fps_limit", 0);
                    if (sFpsLimit != null) sFpsLimit.setSelection(fpsToPosition(fpsLimit));
                    activity.setGameFpsLimit(fpsLimit);
                }
                break;
            }
        }
    }

    private void addProfile(String newName, Spinner sProfile) {
        LinkedHashSet<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        for (String profile : profiles) {
            String name = profile.split(":")[0];
            if (name.equals(newName)) return;
        }
        profiles.add(newName.replace(":", "")+":");
        preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
        loadProfileSpinner(sProfile, newName);
    }

    private void removeProfile(String targetName, Spinner sProfile) {
        LinkedHashSet<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        for (String profile : profiles) {
            String name = profile.split(":")[0];
            if (name.equals(targetName)) {
                profiles.remove(profile);
                break;
            }
        }
        preferences.edit().putStringSet("screen_effect_profiles", profiles).apply();
        loadProfileSpinner(sProfile, null);
        resetSettings();
    }

    private void loadProfileSpinner(Spinner sProfile, String selectedName) {
        LinkedHashSet<String> profiles = new LinkedHashSet<>(preferences.getStringSet("screen_effect_profiles", new LinkedHashSet<>()));
        ArrayList<String> items = new ArrayList<>();

        items.add("-- "+activity.getString(R.string.select_profile)+" --");
        int selectedPosition = 0;
        int position = 1;
        for (String profile : profiles) {
            String name = profile.split(":")[0];
            items.add(name);
            if (name.equals(selectedName)) selectedPosition = position;
            position++;
        }

        sProfile.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, items));
        sProfile.setSelection(selectedPosition);
    }

    private void addFrameGenerationView() {
        if (activity.frameGenerationView == null) {
            GLRenderer currentRenderer = activity.getXServerView().getRenderer();
            final FrameLayout container = activity.findViewById(R.id.FLXServerDisplay);
            activity.frameGenerationView = new FrameGenerationView(activity, currentRenderer);
            activity.frameGenerationView.setFrameGenerationCallback((value) -> {
                FrameGenerationEffect effect = currentRenderer.effectComposer.getEffect(FrameGenerationEffect.class);
                applyFrameGenerationEffect(currentRenderer, effect, value);
            });
            activity.frameGenerationView.setHideButtonCallback(() -> {
                activity.frameGenerationView.setVisibility(View.GONE);
            });
            container.addView(activity.frameGenerationView);
        }
        else {
            activity.frameGenerationView.setVisibility(View.VISIBLE);
        }
        dismiss();
    }

    public void applyFrameGenerationEffect(GLRenderer renderer, FrameGenerationEffect effect, Boolean enabled) {
        if (renderer == null || renderer.effectComposer == null) return;
        if (enabled) {
            if (effect == null) {
                SharedPreferences prefs = getContext().getSharedPreferences("frame_generation", Context.MODE_PRIVATE);
                int generationMode = prefs.getInt("mode_spinner_position", FrameGenerationEffect.GENERATION_MODE_BALANCED);
                int fpsMultiplier = prefs.getInt("fps_multiplier", FrameGenerationEffect.FPS_MULTIPLIER_X2);
                int apiMode = prefs.getInt("api_mode", FrameGenerationEffect.API_QUALCOMM);
                boolean usePostProcessing = prefs.getBoolean("use_post_processing", false);
                boolean blendModeAuto = prefs.getBoolean("blend_mode_auto", true);
                float motionScale = prefs.getFloat("motion_scale", FrameGenerationEffect.DEFAULT_MOTION_SCALE);
                effect = new FrameGenerationEffect(renderer, generationMode, fpsMultiplier, apiMode,
                    usePostProcessing, blendModeAuto, motionScale);
                renderer.effectComposer.addEffect(effect);
                effect.toggleGeneration();
                effect.setDisplayRefreshRate(getRefreshRate());
            }
        }
        else if (effect != null) {
            effect.toggleGeneration();
            renderer.effectComposer.removeEffect(effect);
        }
    }

    public int getRefreshRate() {
        int refreshRate = 60;
        try {
            WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                Display display = windowManager.getDefaultDisplay();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    refreshRate = (int) display.getRefreshRate();
                }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    refreshRate = (int) display.getMode().getRefreshRate();
                }
                else {
                    DisplayMetrics metrics = new DisplayMetrics();
                    display.getMetrics(metrics);
                    refreshRate = 60;
                }
            }
        }
        catch (Exception e) {
            refreshRate = 60;
        }
        int[] standardRates = {30, 45, 48, 50, 60, 72, 75, 90, 96, 100, 120, 144, 165, 240, 360};
        int closest = 60;
        int minDiff = Integer.MAX_VALUE;
        for (int standard : standardRates) {
            int diff = Math.abs(refreshRate - standard);
            if (diff < minDiff) {
                minDiff = diff;
                closest = standard;
            }
        }
        if (refreshRate < 30) return 60;
        return closest;
    }
}
