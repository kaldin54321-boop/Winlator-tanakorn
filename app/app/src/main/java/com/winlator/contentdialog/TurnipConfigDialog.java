package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;

import androidx.collection.ArraySet;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.GPUHelper;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.widget.MultiSelectionComboBox;

public class TurnipConfigDialog extends ContentDialog {
    public static final String[] TU_DEBUG_OPTIONS = {"startup", "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "noubwc", "nomultipos", "nolrz", "nolrzfc", "perf", "perfc", "flushall", "syncdraw", "push_consts_per_stage", "rast_order", "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "noconform", "rd"};
    private static final GPUHelper.VkPresentMode DEFAULT_PRESENT_MODE = GPUHelper.VkPresentMode.MAILBOX;

    public TurnipConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.turnip_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_display_settings);
        setTitle("Turnip "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final CheckBox cbDirectRendering = findViewById(R.id.CBDirectRendering);
        final Spinner sPresentMode = findViewById(R.id.SPresentMode);
        final CheckBox cbTurnipGlitchFix = findViewById(R.id.CBTurnipGlitchFix);
        final MultiSelectionComboBox mscbTuDebug = findViewById(R.id.MSCBTuDebug);

        mscbTuDebug.setPopupWindowWidth(300);
        mscbTuDebug.setDisplayText(context.getString(R.string.multiselection_combobox_display_text));
        mscbTuDebug.setItems(TU_DEBUG_OPTIONS);

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        // Backward compat: pre-v11.2 configs used useHWBuf instead of directRendering.
        boolean directRendering = config.contains("directRendering")
            ? config.getBoolean("directRendering", true)
            : config.getBoolean("useHWBuf", true);
        cbDirectRendering.setChecked(directRendering);
        cbTurnipGlitchFix.setChecked(config.getBoolean("turnipGlitchFix"));
        AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, config.get("maxDeviceMemory", "0"));
        sPresentMode.setSelection(config.getInt("presentMode", DEFAULT_PRESENT_MODE.ordinal()), false);

        // Show exactly what the user stored. GPU-required flags (noconform,
        // sysmem/gmem) are enforced at launch time in setEnvVars(), not baked
        // into the dialog state, so unchecking an option actually sticks.
        mscbTuDebug.setSelectedItems(parseTuDebugSelection(config.get("tuDebug", "")));

        String version = config.get("version");
        GeneralComponents.initViews(GeneralComponents.Type.TURNIP, findViewById(R.id.TurnipToolbox), sVersion, version, DefaultVersion.TURNIP);

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", StringUtils.parseNumber(sVersion.getSelectedItem()));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));
            newConfig.put("directRendering", cbDirectRendering.isChecked() ? "1" : "0");
            newConfig.put("presentMode", sPresentMode.getSelectedItemPosition());
            newConfig.put("turnipGlitchFix", cbTurnipGlitchFix.isChecked() ? "1" : "0");

            // Store the raw user selection verbatim so OK always applies.
            // GPU-required flags are (re-)applied in setEnvVars() instead.
            String[] selectedTuDebug = mscbTuDebug.getSelectedItems();
            newConfig.put("tuDebug", String.join(":", selectedTuDebug));

            anchor.setTag(newConfig.toString());
        });
    }

    /** Split a stored tuDebug value without adding/stripping any flags. */
    public static String[] parseTuDebugSelection(String tuDebugVal) {
        if (tuDebugVal == null || tuDebugVal.isEmpty()) return new String[0];
        String[] split = tuDebugVal.contains(":") ? tuDebugVal.split(":") : tuDebugVal.split(",");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String item : split) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.toArray(new String[0]);
    }

    public static String processTuDebug(Context context, String tuDebugVal) {
        short modelId = GPUHelper.getAdrenoModelId(context);
        boolean isGMEMDevice = (modelId == 710 || modelId == 720 || modelId == 732);

        ArraySet<String> items = new ArraySet<>();
        if (tuDebugVal != null && !tuDebugVal.isEmpty()) {
            String[] split = tuDebugVal.contains(":") ? tuDebugVal.split(":") : tuDebugVal.split(",");
            for (String item : split) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) items.add(trimmed);
            }
        }

        items.add("noconform");

        if (isGMEMDevice) {
            items.remove("sysmem");
            items.add("gmem");
        }
        else {
            items.remove("gmem");
            items.add("sysmem");
        }

        return String.join(":", items);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String maxDeviceMemory = config.get("maxDeviceMemory", "0");
        if (!maxDeviceMemory.equals("0")) envVars.put("TU_OVERRIDE_HEAP_SIZE", maxDeviceMemory);
        boolean directRendering = config.contains("directRendering")
            ? config.getBoolean("directRendering", true)
            : config.getBoolean("useHWBuf", true);
        if (directRendering) envVars.put("MESA_VK_WSI_NATIVE_MEM_IMPORTED", "1");

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
        String tuDebug = processTuDebug(context, rawTuDebug).replace(":", ",");
        if (!tuDebug.contains("deck_emu")) tuDebug = (!tuDebug.isEmpty() ? tuDebug + "," : "") + "deck_emu";
        envVars.put("TU_DEBUG", tuDebug);
    }
}
