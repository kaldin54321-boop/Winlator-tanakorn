package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;

import java.util.ArrayList;

public class VirGLConfigDialog extends ContentDialog {
    public static final String DEFAULT_GL_VERSION = "3.1";

    public VirGLConfigDialog(final View anchor) {
        super(anchor.getContext(), R.layout.virgl_config_dialog);
        Context context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("VirGL "+context.getString(R.string.configuration));

        final Spinner sDriverVersion = findViewById(R.id.SVersion);
        final Spinner sGLVersion = findViewById(R.id.SGLVersion);
        final CheckBox cbDisableVertexArrayBGRA = findViewById(R.id.CBDisableVertexArrayBGRA);
        final CheckBox cbDisableKHRDebug = findViewById(R.id.CBDisableKHRDebug);
        final CheckBox cbDisableTextureSRGBDecode = findViewById(R.id.CBDisableTextureSRGBDecode);

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sGLVersion, config.get("glVersion", DEFAULT_GL_VERSION));
        cbDisableVertexArrayBGRA.setChecked(config.getBoolean("disableVertexArrayBGRA", true));
        cbDisableKHRDebug.setChecked(config.getBoolean("disableKHRdebug", false));
        cbDisableTextureSRGBDecode.setChecked(config.getBoolean("disableTextureSRGBdecode", true));

        String driverVersion = config.get("version");
        GeneralComponents.initViews(GeneralComponents.Type.VIRGL, findViewById(R.id.VirGLToolbox), sDriverVersion, driverVersion, DefaultVersion.VIRGL);

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", sDriverVersion.getSelectedItem().toString());
            newConfig.put("glVersion", sGLVersion.getSelectedItem().toString());
            newConfig.put("disableVertexArrayBGRA", cbDisableVertexArrayBGRA.isChecked() ? "1" : "0");
            newConfig.put("disableKHRdebug", cbDisableKHRDebug.isChecked() ? "1" : "0");
            newConfig.put("disableTextureSRGBdecode", cbDisableTextureSRGBDecode.isChecked() ? "1" : "0");
            anchor.setTag(newConfig.toString());
        });
    }

    public static void setEnvVars(KeyValueSet config, EnvVars envVars) {
        ArrayList<String> disabledExtensions = new ArrayList<>();
        if (config.getBoolean("disableKHRdebug", false)) disabledExtensions.add("GL_KHR_debug");
        if (config.getBoolean("disableVertexArrayBGRA", true)) disabledExtensions.add("GL_EXT_vertex_array_bgra");
        if (config.getBoolean("disableTextureSRGBdecode", true)) disabledExtensions.add("GL_EXT_texture_sRGB_decode");

        String mesaExtensionOverride = "";
        for (String disabledExtension : disabledExtensions) {
            mesaExtensionOverride += (!mesaExtensionOverride.isEmpty() ? " " : "")+"-"+disabledExtension;
        }

        if (!mesaExtensionOverride.isEmpty()) envVars.put("MESA_EXTENSION_OVERRIDE", mesaExtensionOverride);
        envVars.put("MESA_GL_VERSION_OVERRIDE", config.get("glVersion", DEFAULT_GL_VERSION));
    }
}