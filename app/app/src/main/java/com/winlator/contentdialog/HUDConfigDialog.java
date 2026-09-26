package com.winlator.contentdialog;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.CheckBox;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.container.HUDConfig;
import com.winlator.core.Callback;
import com.winlator.widget.SeekBar;

public class HUDConfigDialog extends ContentDialog {
    private final HUDConfig config;
    private final SeekBar sbScale;
    private final SeekBar sbTransparency;
    private final TextView tvScaleValue;
    private final TextView tvTransparencyValue;
    private final CheckBox cbShowFPS;
    private final CheckBox cbShowCPU;
    private final CheckBox cbShowGPU;
    private final CheckBox cbShowRAM;
    private final CheckBox cbShowBattery;
    private final CheckBox cbShowPower;
    private final CheckBox cbShowRenderer;
    private final CheckBox cbShowTemp;
    private final TextView tvLivePreview;

    public HUDConfigDialog(Context context, HUDConfig currentConfig, Callback<HUDConfig> onConfirm) {
        super(context, R.layout.hud_config_dialog);
        this.config = currentConfig != null ? new HUDConfig(currentConfig) : new HUDConfig();

        setTitle(R.string.fps_counter_settings);

        sbScale = findViewById(R.id.SBScale);
        sbTransparency = findViewById(R.id.SBTransparency);
        tvScaleValue = findViewById(R.id.TVScaleValue);
        tvTransparencyValue = findViewById(R.id.TVTransparencyValue);

        cbShowFPS = findViewById(R.id.CBShowFPS);
        cbShowCPU = findViewById(R.id.CBShowCPU);
        cbShowGPU = findViewById(R.id.CBShowGPU);
        cbShowRAM = findViewById(R.id.CBShowRAM);
        cbShowBattery = findViewById(R.id.CBShowBattery);
        cbShowPower = findViewById(R.id.CBShowPower);
        cbShowRenderer = findViewById(R.id.CBShowRenderer);
        cbShowTemp = findViewById(R.id.CBShowTemp);

        tvLivePreview = findViewById(R.id.TVLivePreview);

        sbScale.setValue(config.getScale());
        sbTransparency.setValue(config.getTransparency());

        cbShowFPS.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_FPS));
        cbShowCPU.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_CPU));
        cbShowGPU.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_GPU));
        cbShowRAM.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_RAM));
        cbShowBattery.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_BATTERY));
        cbShowPower.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_POWER));
        cbShowRenderer.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_RENDERER));
        cbShowTemp.setChecked(config.isElementEnabled(HUDConfig.ELEMENT_TEMP));

        SeekBar.OnValueChangeListener seekListener = (seekBar, value) -> updateLivePreview();
        sbScale.setOnValueChangeListener(seekListener);
        sbTransparency.setOnValueChangeListener(seekListener);

        cbShowFPS.setOnCheckedChangeListener((v, c) -> updateLivePreview());
        cbShowCPU.setOnCheckedChangeListener((v, c) -> updateLivePreview());
        cbShowGPU.setOnCheckedChangeListener((v, c) -> updateLivePreview());
        cbShowRAM.setOnCheckedChangeListener((v, c) -> updateLivePreview());
        cbShowBattery.setOnCheckedChangeListener((v, c) -> updateLivePreview());
        cbShowPower.setOnCheckedChangeListener((v, c) -> updateLivePreview());
        cbShowRenderer.setOnCheckedChangeListener((v, c) -> updateLivePreview());
        cbShowTemp.setOnCheckedChangeListener((v, c) -> updateLivePreview());

        setOnConfirmCallback(() -> {
            applyToConfig(config);
            if (onConfirm != null) onConfirm.call(config);
        });

        updateLivePreview();
    }

    private void applyToConfig(HUDConfig target) {
        target.setScale((int) sbScale.getValue());
        target.setTransparency((int) sbTransparency.getValue());
        target.setElementEnabled(HUDConfig.ELEMENT_FPS, cbShowFPS.isChecked());
        target.setElementEnabled(HUDConfig.ELEMENT_CPU, cbShowCPU.isChecked());
        target.setElementEnabled(HUDConfig.ELEMENT_GPU, cbShowGPU.isChecked());
        target.setElementEnabled(HUDConfig.ELEMENT_RAM, cbShowRAM.isChecked());
        target.setElementEnabled(HUDConfig.ELEMENT_BATTERY, cbShowBattery.isChecked());
        target.setElementEnabled(HUDConfig.ELEMENT_POWER, cbShowPower.isChecked());
        target.setElementEnabled(HUDConfig.ELEMENT_RENDERER, cbShowRenderer.isChecked());
        target.setElementEnabled(HUDConfig.ELEMENT_TEMP, cbShowTemp.isChecked());
    }

    private void updateLivePreview() {
        HUDConfig tempConfig = new HUDConfig();
        applyToConfig(tempConfig);

        tvScaleValue.setText(tempConfig.getScale() + "%");
        tvTransparencyValue.setText(String.valueOf(tempConfig.getTransparency()));

        SpannableStringBuilder builder = new SpannableStringBuilder();

        appendElement(builder, tempConfig, HUDConfig.ELEMENT_RENDERER, "RDR", "Turnip (DXVK)", 0xFFFFD700);
        appendElement(builder, tempConfig, HUDConfig.ELEMENT_GPU, "GPU", "0%", 0xFFE040FB);
        appendElement(builder, tempConfig, HUDConfig.ELEMENT_CPU, "CPU", "2.59 GHz | v0.4.4 | 41°C", 0xFF00E5FF);
        appendElement(builder, tempConfig, HUDConfig.ELEMENT_RAM, "RAM", "0%", 0xFF00E676);
        appendElement(builder, tempConfig, HUDConfig.ELEMENT_POWER, "PWR", "0.0W", 0xFFFF9100);
        appendElement(builder, tempConfig, HUDConfig.ELEMENT_TEMP, "TMP", "0°C", 0xFFFF3D00);
        appendElement(builder, tempConfig, HUDConfig.ELEMENT_BATTERY, "BTR", "0%", 0xFFFF4081);
        appendElement(builder, tempConfig, HUDConfig.ELEMENT_FPS, "FPS", "0", 0xFF76FF03);

        tvLivePreview.setText(builder);

        int alpha = (int) ((100 - tempConfig.getTransparency()) / 100.0f * 220);
        alpha = Math.max(0, Math.min(255, alpha));
        tvLivePreview.setBackgroundColor(Color.argb(alpha, 0, 0, 0));

        float scale = tempConfig.getScale() / 100.0f;
        tvLivePreview.setScaleX(scale);
        tvLivePreview.setScaleY(scale);
        tvLivePreview.setPivotX(0);
        tvLivePreview.setPivotY(0);
    }

    private void appendElement(SpannableStringBuilder builder, HUDConfig config, int bit, String label, String value, int labelColor) {
        if (!config.isElementEnabled(bit)) return;

        if (builder.length() > 0) {
            builder.append("\n");
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
