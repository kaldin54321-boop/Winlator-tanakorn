package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.EnvVars;
import com.winlator.core.GPUHelper;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.widget.MultiSelectionComboBox;
import com.winlator.xenvironment.components.VortekRendererComponent;

import java.util.Locale;

public class VortekConfigDialog extends ContentDialog {
    public static final String DEFAULT_VK_MAX_VERSION = GPUHelper.vkVersionMajor(VortekRendererComponent.VK_MAX_VERSION)+"."+GPUHelper.vkVersionMinor(VortekRendererComponent.VK_MAX_VERSION);
    private static final GPUHelper.VkPresentMode DEFAULT_PRESENT_MODE = GPUHelper.VkPresentMode.MAILBOX;

    public VortekConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.vortek_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_display_settings);
        setTitle("Vortek "+context.getString(R.string.configuration));

        final Spinner sAdrenotoolsDriver = findViewById(R.id.SAdrenotoolsDriver);
        final Spinner sVkMaxVersion = findViewById(R.id.SVkMaxVersion);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sPresentMode = findViewById(R.id.SPresentMode);
        final Spinner sImageCacheSize = findViewById(R.id.SImageCacheSize);
        final Spinner sResourceMemoryType = findViewById(R.id.SResourceMemoryType);
        final CheckBox cbTurnipGlitchFix = findViewById(R.id.CBTurnipGlitchFix);
        final MultiSelectionComboBox mscbExposedExtensions = findViewById(R.id.MSCBExposedExtensions);
        final MultiSelectionComboBox mscbTuDebug = findViewById(R.id.MSCBTuDebug);

        mscbExposedExtensions.setPopupWindowWidth(360);
        mscbExposedExtensions.setDisplayText(context.getString(R.string.multiselection_combobox_display_text));

        mscbTuDebug.setPopupWindowWidth(300);
        mscbTuDebug.setDisplayText(context.getString(R.string.multiselection_combobox_display_text));
        mscbTuDebug.setItems(TurnipConfigDialog.TU_DEBUG_OPTIONS);

        KeyValueSet config = new KeyValueSet(anchor.getTag());

        String exposedDeviceExtensionsVal = config.get("exposedDeviceExtensions", "all");
        if (exposedDeviceExtensionsVal.contains("|")) exposedDeviceExtensionsVal = "all";
        final String savedExposedExtensionsVal = exposedDeviceExtensionsVal;

        String tuDebugVal = TurnipConfigDialog.processTuDebug(context, config.get("tuDebug", ""));
        mscbTuDebug.setSelectedItems(tuDebugVal.split(":"));

        cbTurnipGlitchFix.setChecked(config.getBoolean("turnipGlitchFix"));

        String adrenotoolsDriver = config.get("adrenotoolsDriver");
        GeneralComponents.initViews(GeneralComponents.Type.ADRENOTOOLS_DRIVER, findViewById(R.id.AdrenotoolsDriverToolbox), sAdrenotoolsDriver, adrenotoolsDriver, "System");

        Runnable updateGlitchFixVisibility = () -> {
            Object selected = sAdrenotoolsDriver.getSelectedItem();
            String driverName = selected != null ? selected.toString() : "";
            String driverNameLower = driverName.toLowerCase(Locale.ROOT);

            if (driverName.equalsIgnoreCase("System") || driverNameLower.contains("qualcomm")) {
                cbTurnipGlitchFix.setVisibility(View.GONE);
            }
            else if (driverNameLower.contains("turnip")) {
                cbTurnipGlitchFix.setVisibility(View.VISIBLE);
            }
            else {
                cbTurnipGlitchFix.setVisibility(View.GONE);
            }
        };

        Callback<String> updateExposedExtensions = (driverName) -> {
            String[] availableExtensions = GPUHelper.vkGetDeviceExtensions(context, driverName);
            mscbExposedExtensions.setItems(availableExtensions);

            if (savedExposedExtensionsVal.equals("all")) {
                mscbExposedExtensions.setSelectedItems(availableExtensions);
            }
            else if (!savedExposedExtensionsVal.isEmpty()) {
                String[] selectedItems = savedExposedExtensionsVal.split(":");
                mscbExposedExtensions.setSelectedItems(selectedItems);
            }
        };

        sAdrenotoolsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object selected = sAdrenotoolsDriver.getSelectedItem();
                String driverName = selected != null ? selected.toString() : "";
                updateGlitchFixVisibility.run();
                updateExposedExtensions.call(driverName);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateGlitchFixVisibility.run();

        Object initialSelected = sAdrenotoolsDriver.getSelectedItem();
        String initialDriverName = initialSelected != null ? initialSelected.toString() : (adrenotoolsDriver.isEmpty() ? "System" : adrenotoolsDriver);
        updateExposedExtensions.call(initialDriverName);

        AppUtils.setSpinnerSelectionFromValue(sVkMaxVersion, config.get("vkMaxVersion", DEFAULT_VK_MAX_VERSION));
        AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, config.get("maxDeviceMemory", "0"));
        sPresentMode.setSelection(config.getInt("presentMode", DEFAULT_PRESENT_MODE.ordinal()), false);
        AppUtils.setSpinnerSelectionFromNumber(sImageCacheSize, config.get("imageCacheSize", String.valueOf(VortekRendererComponent.IMAGE_CACHE_SIZE)));
        sResourceMemoryType.setSelection(config.getInt("resourceMemoryType"));

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("adrenotoolsDriver", sAdrenotoolsDriver.getSelectedItem());
            newConfig.put("vkMaxVersion", StringUtils.parseNumber(sVkMaxVersion.getSelectedItem(), "0"));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));
            newConfig.put("presentMode", sPresentMode.getSelectedItemPosition());
            newConfig.put("imageCacheSize", StringUtils.parseNumber(sImageCacheSize.getSelectedItem()));
            newConfig.put("resourceMemoryType", sResourceMemoryType.getSelectedItemPosition());

            String[] selectedItems = mscbExposedExtensions.getSelectedItems();
            String[] currentAvailable = mscbExposedExtensions.getItems();
            if (selectedItems.length > 0) {
                if (currentAvailable != null && selectedItems.length == currentAvailable.length) {
                    newConfig.put("exposedDeviceExtensions", "all");
                }
                else newConfig.put("exposedDeviceExtensions", String.join(":", selectedItems));
            }

            String[] selectedTuDebug = mscbTuDebug.getSelectedItems();
            String tuDebugStr = TurnipConfigDialog.processTuDebug(context, String.join(":", selectedTuDebug));
            newConfig.put("tuDebug", tuDebugStr);

            if (cbTurnipGlitchFix.getVisibility() == View.VISIBLE) {
                newConfig.put("turnipGlitchFix", cbTurnipGlitchFix.isChecked() ? "1" : "0");
            }

            anchor.setTag(newConfig.toString());
        });
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        int presentModeIdx = config.getInt("presentMode", DEFAULT_PRESENT_MODE.ordinal());
        GPUHelper.VkPresentMode[] modes = GPUHelper.VkPresentMode.values();
        if (presentModeIdx < 0 || presentModeIdx >= modes.length) presentModeIdx = DEFAULT_PRESENT_MODE.ordinal();
        String presentMode = modes[presentModeIdx].value();
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);

        if (config.getBoolean("turnipGlitchFix")) {
            String fdDevFeatures = envVars.get("FD_DEV_FEATURES");
            if (!fdDevFeatures.contains("enable_tp_ubwc_flag_hint=1")) {
                envVars.put("FD_DEV_FEATURES", (!fdDevFeatures.isEmpty() ? fdDevFeatures + "," : "") + "enable_tp_ubwc_flag_hint=1");
            }
        }

        String rawTuDebug = config.get("tuDebug", "");
        String tuDebug = TurnipConfigDialog.processTuDebug(context, rawTuDebug).replace(":", ",");
        envVars.put("TU_DEBUG", tuDebug);
    }

    public static boolean isRequireRestart(String oldGraphicsDriverConfig, String newGraphicsDriverConfig) {
        if (!oldGraphicsDriverConfig.equals(newGraphicsDriverConfig)) {
            String oldAdrenotoolsDriver = (new KeyValueSet(oldGraphicsDriverConfig)).get("adrenotoolsDriver");
            String newAdrenotoolsDriver = (new KeyValueSet(newGraphicsDriverConfig)).get("adrenotoolsDriver");
            return !oldAdrenotoolsDriver.isEmpty() && !newAdrenotoolsDriver.isEmpty() && !newAdrenotoolsDriver.equals(oldAdrenotoolsDriver);
        }
        else return false;
    }
}