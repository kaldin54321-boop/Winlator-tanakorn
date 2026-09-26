package com.winlator.contentdialog;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.container.DXWrappers;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.core.VegasReleaseDownloader;
import com.winlator.widget.GPUCardAdapter;
import com.winlator.widget.MultiSelectionComboBox;
import com.winlator.xenvironment.RootFS;

import java.io.File;

public class VegasConfigDialog extends ContentDialog {
    public VegasConfigDialog(String graphicsDriver, final View anchor) {
        super(anchor.getContext(), R.layout.vegas_config_dialog);
        setIcon(R.drawable.icon_display_settings);
        Context context = anchor.getContext();
        setTitle("Vegas "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sVegasConfigFile = findViewById(R.id.SVegasConfigFile);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sCustomDevice = findViewById(R.id.SCustomDevice);
        final Spinner sDDrawWrapper = findViewById(R.id.SDDrawWrapper);
        final View btDownloadConfig = findViewById(R.id.BTDownloadConfig);
        final MultiSelectionComboBox mscbDxvkHud = findViewById(R.id.MSCBDxvkHud);

        mscbDxvkHud.setPopupWindowWidth(300);
        mscbDxvkHud.setDisplayText(context.getString(R.string.multiselection_combobox_display_text));
        mscbDxvkHud.setItems(DXVKConfigDialog.DXVK_HUD_OPTIONS);

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate", "0"));
        AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, config.get("maxDeviceMemory", "0"));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDrawWrapper, config.get("ddrawWrapper", DXWrappers.WINED3D));
        AppUtils.setSpinnerSelectionFromValue(sVegasConfigFile, config.get("configFile", "/storage/emulated/0/dxvk.conf"));

        String dxvkHudVal = config.get("dxvkHud", "");
        if (!dxvkHudVal.isEmpty()) mscbDxvkHud.setSelectedItems(dxvkHudVal.contains(":") ? dxvkHudVal.split(":") : dxvkHudVal.split(","));

        String version = config.get("version");
        String defaultVersion = DefaultVersion.VEGAS();
        GeneralComponents.initViews(GeneralComponents.Type.VEGAS, findViewById(R.id.VegasToolbox), sVersion, version, defaultVersion);

        if (btDownloadConfig != null) {
            btDownloadConfig.setOnClickListener((v) -> {
                Activity activity = AppUtils.getActivity(context);
                if (activity != null) {
                    VegasReleaseDownloader.showVegasConfigDownloadDialog(activity, sVegasConfigFile);
                }
            });
        }

        GPUCardAdapter adapter = new GPUCardAdapter(context, android.R.layout.simple_spinner_dropdown_item, R.string.none);
        sCustomDevice.setAdapter(adapter);

        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            try {
                int deviceId = Integer.parseInt(customDevice.split(":")[0], 16);
                sCustomDevice.setSelection(adapter.getPositionByDeviceId(deviceId));
            }
            catch (NumberFormatException e) {}
        }

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", sVersion.getSelectedItem().toString());
            newConfig.put("configFile", sVegasConfigFile.getSelectedItem().toString());
            newConfig.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));

            String ddrawWrapper = StringUtils.parseIdentifier(sDDrawWrapper.getSelectedItem());
            if (!ddrawWrapper.equals(DXWrappers.WINED3D)) newConfig.put("ddrawWrapper", ddrawWrapper);

            GPUCardAdapter.GPUCard gpuCard = (GPUCardAdapter.GPUCard)sCustomDevice.getSelectedItem();
            if (gpuCard.deviceId > 0) {
                newConfig.put("customDevice", String.format("%04x", gpuCard.deviceId)+":"+String.format("%04x", gpuCard.vendorId)+":"+gpuCard.name);
            }

            String[] selectedDxvkHud = mscbDxvkHud.getSelectedItems();
            if (selectedDxvkHud.length > 0) newConfig.put("dxvkHud", String.join(":", selectedDxvkHud));

            anchor.setTag(newConfig.toString());
        });
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        if (config.get("version").contains("async")) envVars.put("DXVK_ASYNC", "1");
        envVars.put("DXVK_STATE_CACHE_PATH", RootFS.getDosUserCachePath());
        envVars.put("DXVK_LOG_LEVEL", "none");

        String dxvkHud = config.get("dxvkHud", "").replace(":", ",");
        if (!dxvkHud.isEmpty()) envVars.put("DXVK_HUD", dxvkHud);

        String configFile = config.get("configFile", "/storage/emulated/0/dxvk.conf");
        if (!configFile.isEmpty()) {
            String winPath = configFile.startsWith("/") ? "Z:" + configFile.replace("/", "\\") : configFile;
            envVars.put("DXVK_CONFIG_FILE", winPath);
        }

        File rootDir = RootFS.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, RootFS.USER_CONFIG_PATH+"/dxvk.conf");

        FileUtils.delete(dxvkConfigFile);
        String content = getDXVKConfigContent(config);
        if (FileUtils.writeString(dxvkConfigFile, content) && configFile.isEmpty()) {
            envVars.put("DXVK_CONFIG_FILE", RootFS.getDosUserConfigPath()+"\\dxvk.conf");
        }
    }

    private static String getDXVKConfigContent(KeyValueSet config) {
        String content = "";

        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+"\n";
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+"\n";
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            content += "dxgi.maxFrameRate = "+framerate+"\n";
            content += "d3d9.maxFrameRate = "+framerate+"\n";
        }

        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            String[] parts = customDevice.split(":");
            content += "dxgi.customDeviceId = "+parts[0]+"\n";
            content += "dxgi.customVendorId = "+parts[1]+"\n";

            content += "d3d9.customDeviceId = "+parts[0]+"\n";
            content += "d3d9.customVendorId = "+parts[1]+"\n";

            content += "dxgi.customDeviceDesc = \""+parts[2]+"\"\n";
            content += "d3d9.customDeviceDesc = \""+parts[2]+"\"\n";
        }

        content += "d3d11.constantBufferRangeCheck = \"True\"\n\n";

        content += "[GTA5.exe]\n";
        content += "dxgi.maxDeviceMemory = 512\n";
        content += "dxgi.maxSharedMemory = 512\n";
        return content;
    }
}