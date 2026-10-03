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

        KeyValueSet config = new KeyValueSet(anchor.getTag());

        String adrenotoolsDriver = config.get("adrenotoolsDriver");
        // Always offer the full option list so every TU_DEBUG flag stays
        // selectable; GPU-required flags (noconform, sysmem/gmem) are enforced
        // at launch time in setEnvVars(), not by hiding options here.
        mscbTuDebug.setItems(TurnipConfigDialog.TU_DEBUG_OPTIONS);

        String exposedDeviceExtensionsVal = config.get("exposedDeviceExtensions", "all");
        if (exposedDeviceExtensionsVal.contains("|")) exposedDeviceExtensionsVal = "all";
        final String savedExposedExtensionsVal = exposedDeviceExtensionsVal;

        // Show exactly what the user stored so unchecking an option sticks.
        mscbTuDebug.setSelectedItems(TurnipConfigDialog.parseTuDebugSelection(config.get("tuDebug", "")));

        cbTurnipGlitchFix.setChecked(config.getBoolean("turnipGlitchFix"));

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
                // TU_DEBUG list is static (full options); the user's selection
                // is left untouched on driver switches and GPU-required flags
                // are enforced in setEnvVars().
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

            // Store the raw user selection verbatim so OK always applies.
            // GPU-required flags are (re-)applied in setEnvVars() instead.
            String[] selectedTuDebug = mscbTuDebug.getSelectedItems();
            newConfig.put("tuDebug", String.join(":", selectedTuDebug));

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

        // PanVK-on-kbase mods (e.g. FristOneRR Panvk-Mali-G57, Mesa 26.3-devel).
        // Keys are optional; when absent the container-level Environment
        // Variables (user-added PANVK_*) are left untouched. Values follow the
        // upstream table: PANVK_HEAP_MB (256-16384), PANVK_ATOM_STRIDE (56/64),
        // PANVK_TILER_HEAP_MB (16-2048), PANVK_POLY_HEAP_MB (4-512),
        // PANVK_TRACE (0/1). Only whitelisted values are forwarded so a stale
        // config can never inject an arbitrary PANVK_* switch (upstream warns
        // internal PANVK_* switches break rendering).
        String panvkHeapMb = config.get("panvkHeapMb", "");
        if (!panvkHeapMb.isEmpty()) {
            try {
                int heapMb = Integer.parseInt(panvkHeapMb);
                if (heapMb >= 256 && heapMb <= 16384) envVars.put("PANVK_HEAP_MB", String.valueOf(heapMb));
            } catch (NumberFormatException ignored) {}
        }
        String panvkAtomStride = config.get("panvkAtomStride", "");
        if (panvkAtomStride.equals("56") || panvkAtomStride.equals("64")) {
            envVars.put("PANVK_ATOM_STRIDE", panvkAtomStride);
        }
        String panvkTilerHeapMb = config.get("panvkTilerHeapMb", "");
        if (!panvkTilerHeapMb.isEmpty()) {
            try {
                int tilerMb = Integer.parseInt(panvkTilerHeapMb);
                if (tilerMb >= 16 && tilerMb <= 2048) envVars.put("PANVK_TILER_HEAP_MB", String.valueOf(tilerMb));
            } catch (NumberFormatException ignored) {}
        }
        String panvkPolyHeapMb = config.get("panvkPolyHeapMb", "");
        if (!panvkPolyHeapMb.isEmpty()) {
            try {
                int polyMb = Integer.parseInt(panvkPolyHeapMb);
                if (polyMb >= 4 && polyMb <= 512) envVars.put("PANVK_POLY_HEAP_MB", String.valueOf(polyMb));
            } catch (NumberFormatException ignored) {}
        }
        if (config.getBoolean("panvkTrace", false)) envVars.put("PANVK_TRACE", "1");

        if (config.getBoolean("turnipGlitchFix")) {
            String fdDevFeatures = envVars.get("FD_DEV_FEATURES");
            if (!fdDevFeatures.contains("enable_tp_ubwc_flag_hint=1")) {
                envVars.put("FD_DEV_FEATURES", (!fdDevFeatures.isEmpty() ? fdDevFeatures + "," : "") + "enable_tp_ubwc_flag_hint=1");
            }
        }

        String rawTuDebug = config.get("tuDebug", "");
        String tuDebug = processVortekTuDebug(context, rawTuDebug, config.get("adrenotoolsDriver", "System")).replace(":", ",");
        envVars.put("TU_DEBUG", tuDebug);
    }

    /**
     * Vortek-specific TU_DEBUG rules (Turnip's {@code processTuDebug} is left
     * untouched so the Turnip dialog keeps its existing behavior).
     * <ul>
     *   <li>Non-Adreno GPUs (Mali, PowerVR, Xclipse, …): both {@code sysmem}
     *       and {@code gmem} are stripped — they are Turnip/Adreno-only flags.</li>
     *   <li>Adreno 6xx/7xx/8xx + System/Qualcomm driver: {@code sysmem} is
     *       enforced, {@code gmem} is stripped.</li>
     *   <li>Adreno 710/720/732 + imported Turnip (Adrenotools) driver:
     *       {@code gmem} is enforced, {@code sysmem} is stripped.</li>
     *   <li>Other Adreno + Turnip driver, or Adreno + any other driver:
     *       falls back to {@code sysmem} (safe default, mirrors Turnip).</li>
     * </ul>
     */
    public static String processVortekTuDebug(Context context, String tuDebugVal, String adrenotoolsDriver) {
        androidx.collection.ArraySet<String> items = new androidx.collection.ArraySet<>();
        if (tuDebugVal != null && !tuDebugVal.isEmpty()) {
            String[] split = tuDebugVal.contains(":") ? tuDebugVal.split(":") : tuDebugVal.split(",");
            for (String item : split) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) items.add(trimmed);
            }
        }

        items.add("noconform");

        if (!isVortekAdreno(context)) {
            items.remove("sysmem");
            items.remove("gmem");
        }
        else if (isVortekTurnipDriver(adrenotoolsDriver) && isVortekGmemDevice(context)) {
            items.remove("sysmem");
            items.add("gmem");
        }
        else {
            // Covers: Adreno + System/Qualcomm -> sysmem; Adreno non-GMEM +
            // Turnip -> sysmem; Adreno + unknown driver -> sysmem (safe).
            items.remove("gmem");
            items.add("sysmem");
        }

        return String.join(":", items);
    }

    private static boolean isVortekAdreno(Context context) {
        short modelId = GPUHelper.getAdrenoModelId(context);
        return modelId >= 600 && modelId <= 899;
    }

    private static boolean isVortekGmemDevice(Context context) {
        short modelId = GPUHelper.getAdrenoModelId(context);
        return modelId == 710 || modelId == 720 || modelId == 732;
    }

    private static boolean isVortekTurnipDriver(String adrenotoolsDriver) {
        if (adrenotoolsDriver == null) return false;
        return adrenotoolsDriver.toLowerCase(Locale.ROOT).contains("turnip");
    }

    public static boolean isRequireRestart(String oldGraphicsDriverConfig, String newGraphicsDriverConfig) {
        if (!oldGraphicsDriverConfig.equals(newGraphicsDriverConfig)) {
            KeyValueSet oldConfig = new KeyValueSet(oldGraphicsDriverConfig);
            KeyValueSet newConfig = new KeyValueSet(newGraphicsDriverConfig);
            String oldAdrenotoolsDriver = oldConfig.get("adrenotoolsDriver");
            String newAdrenotoolsDriver = newConfig.get("adrenotoolsDriver");
            if (!oldAdrenotoolsDriver.isEmpty() && !newAdrenotoolsDriver.isEmpty() && !newAdrenotoolsDriver.equals(oldAdrenotoolsDriver)) return true;
            // PanVK-on-kbase env keys are applied at container start; a change
            // needs the same restart as a driver switch.
            for (String key : new String[]{"panvkHeapMb", "panvkAtomStride", "panvkTilerHeapMb", "panvkPolyHeapMb", "panvkTrace"}) {
                if (!oldConfig.get(key, "").equals(newConfig.get(key, ""))) return true;
            }
            return false;
        }
        else return false;
    }
}