package com.winlator.container;

import com.winlator.box64.Box64Preset;
import com.winlator.core.AppUtils;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.KeyValueSet;
import com.winlator.core.WineInfo;
import com.winlator.core.WineThemeManager;
import com.winlator.widget.FrameRating;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;

public class Container {
    public static final String DEFAULT_ENV_VARS = "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 VKD3D_SHADER_MODEL=6_0";
    public static final String DEFAULT_LC_ALL = "en_US.UTF-8";
    public static final String DEFAULT_TIMEZONE = "UTC";
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_AUDIO_DRIVER = AudioDrivers.ALSA;
    public static final String DEFAULT_DXWRAPPER = DXWrappers.DXVK;
    public static final String DEFAULT_WINCOMPONENTS = "direct3d=1,directsound=1,directmusic=1,directshow=0,directplay=0,xaudio=1,vcrun2005=0,vcrun2010=1,wmdecoder=1";
    public static final String FALLBACK_WINCOMPONENTS = "direct3d=0,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,vcrun2005=0,vcrun2010=0,wmdecoder=0";
    public static final String DEFAULT_DRIVES = "D:"+AppUtils.DIRECTORY_DOWNLOADS +"E:"+AppUtils.INTERNAL_STORAGE;
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    public static final byte STARTUP_SELECTION_ESSENTIAL = 1;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;
    public static final byte MAX_DRIVE_LETTERS = 8;
    public final int id;
    private String name;
    private String screenSize = DEFAULT_SCREEN_SIZE;
    private String envVars = DEFAULT_ENV_VARS;
    private String graphicsDriver = GraphicsDrivers.DEFAULT_VULKAN_DRIVER+","+ GraphicsDrivers.DEFAULT_OPENGL_DRIVER;
    private String dxwrapper = DEFAULT_DXWRAPPER;
    private String dxwrapperConfig = "";
    private String graphicsDriverConfig = "";
    private String audioDriverConfig = "";
    private String wincomponents = DEFAULT_WINCOMPONENTS;
    private String audioDriver = DEFAULT_AUDIO_DRIVER;
    private String drives = DEFAULT_DRIVES;
    private String wineVersion = WineInfo.MAIN_WINE_INFO.identifier();
    private byte hudMode = (byte)FrameRating.Mode.DISABLED.ordinal();
    private boolean showHUD = false;
    private boolean showQuickHUD = false;
    private HUDConfig hudConfig = new HUDConfig();
    private boolean fullscreenStretched = false;
    private byte startupSelection = STARTUP_SELECTION_ESSENTIAL;
    private String cpuList;
    private String cpuListWoW64;
    private String desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
    private String box64Preset = Box64Preset.DEFAULT;
    private String box64Version;
    private String lcAll = DEFAULT_LC_ALL;
    private String timezone = DEFAULT_TIMEZONE;
    private int rcfileId = 0;
    private File rootDir;
    private JSONObject extraData;

    public Container(int id) {
        this.id = id;
        this.name = "Container-"+id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public String getGraphicsDriverConfig() {
        return graphicsDriverConfig;
    }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) {
        this.graphicsDriverConfig = graphicsDriverConfig != null ? graphicsDriverConfig : "";
    }

    public String getDXWrapperConfig() {
        return dxwrapperConfig;
    }

    public void setDXWrapperConfig(String dxwrapperConfig) {
        this.dxwrapperConfig = dxwrapperConfig != null ? dxwrapperConfig : "";
    }

    public String getAudioDriverConfig() {
        return audioDriverConfig;
    }

    public void setAudioDriverConfig(String audioDriverConfig) {
        this.audioDriverConfig = audioDriverConfig != null ? audioDriverConfig : "";
    }

    public String getAudioDriver() {
        return audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public String getDrives() {
        return drives;
    }

    public void setDrives(String drives) {
        this.drives = drives;
    }

    public byte getHUDMode() {
        return hudMode;
    }

    public void setHUDMode(byte hudMode) {
        this.hudMode = hudMode;
        this.showHUD = hudMode != FrameRating.Mode.DISABLED.ordinal();
    }

    public boolean isShowHUD() {
        return showHUD;
    }

    public void setShowHUD(boolean showHUD) {
        this.showHUD = showHUD;
        this.hudMode = (byte)(showHUD ? FrameRating.Mode.FULL.ordinal() : FrameRating.Mode.DISABLED.ordinal());
    }

    public boolean isShowQuickHUD() {
        return showQuickHUD;
    }

    public void setShowQuickHUD(boolean showQuickHUD) {
        this.showQuickHUD = showQuickHUD;
    }

    public HUDConfig getHUDConfig() {
        return hudConfig;
    }

    public void setHUDConfig(HUDConfig hudConfig) {
        this.hudConfig = hudConfig != null ? hudConfig : new HUDConfig();
    }

    public boolean isFullscreenStretched() {
        return fullscreenStretched;
    }

    public void setFullscreenStretched(boolean fullscreenStretched) {
        this.fullscreenStretched = fullscreenStretched;
    }

    public byte getStartupSelection() {
        return startupSelection;
    }

    public void setStartupSelection(byte startupSelection) {
        this.startupSelection = startupSelection;
    }

    public String getCPUList() {
        return getCPUList(false);
    }

    public String getCPUList(boolean allowFallback) {
        return cpuList != null ? cpuList : (allowFallback ? getFallbackCPUList() : null);
    }

    public void setCPUList(String cpuList) {
        this.cpuList = cpuList != null && !cpuList.isEmpty() ? cpuList : null;
    }

    public String getCPUListWoW64() {
        return getCPUListWoW64(false);
    }

    public String getCPUListWoW64(boolean allowFallback) {
        return cpuListWoW64 != null ? cpuListWoW64 : (allowFallback ? getFallbackCPUList() : null);
    }

    public void setCPUListWoW64(String cpuListWoW64) {
        this.cpuListWoW64 = cpuListWoW64 != null && !cpuListWoW64.isEmpty() ? cpuListWoW64 : null;
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

    public String getLCAll() {
        return lcAll != null && !lcAll.isEmpty() ? lcAll : DEFAULT_LC_ALL;
    }

    public void setLCAll(String lcAll) {
        this.lcAll = lcAll != null && !lcAll.isEmpty() ? lcAll : DEFAULT_LC_ALL;
    }

    public String getTimezone() {
        return timezone != null && !timezone.isEmpty() ? timezone : DEFAULT_TIMEZONE;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone != null && !timezone.isEmpty() ? timezone : DEFAULT_TIMEZONE;
    }

    public int getRCFileId() {
        return rcfileId;
    }

    public void setRcfileId(int id) {
        rcfileId = id;
    }

    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData != null && extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, Object value) {
        if (extraData == null) extraData = new JSONObject();
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {}
    }

    public String getWineVersion() {
        return wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    public File getConfigFile() {
        return new File(rootDir, ".container");
    }

    public File getUserDir() {
        return new File(rootDir, ".wine/drive_c/users/"+ RootFS.USER+"/");
    }

    public File getStartMenuDir() {
        return new File(rootDir, ".wine/drive_c/ProgramData/Microsoft/Windows/Start Menu/");
    }

    public File getIconsDir(int size) {
        return new File(rootDir, ".local/share/icons/hicolor/"+size+"x"+size+"/apps/");
    }

    public String getDesktopTheme() {
        return desktopTheme;
    }

    public void setDesktopTheme(String desktopTheme) {
        this.desktopTheme = desktopTheme;
    }

    public Iterable<Drive> drivesIterator() {
        return drivesIterator(drives);
    }

    public static Iterable<Drive> drivesIterator(final String drives) {
        final int[] index = {drives.indexOf(":")};
        return () -> new Iterator<Drive>() {
            @Override
            public boolean hasNext() {
                return index[0] != -1;
            }

            @Override
            public Drive next() {
                String letter = String.valueOf(drives.charAt(index[0]-1));
                int nextIndex = drives.indexOf(":", index[0]+1);
                String path = drives.substring(index[0]+1, nextIndex != -1 ? nextIndex-1 : drives.length());
                index[0] = nextIndex;
                return new Drive(letter, path);
            }
        };
    }

    public void saveData() {
        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("screenSize", screenSize);
            data.put("envVars", envVars);
            data.put("cpuList", cpuList);
            data.put("cpuListWoW64", cpuListWoW64);
            data.put("graphicsDriver", graphicsDriver);
            data.put("dxwrapper", dxwrapper);
            if (!dxwrapperConfig.isEmpty()) data.put("dxwrapperConfig", dxwrapperConfig);
            if (!graphicsDriverConfig.isEmpty()) data.put("graphicsDriverConfig", graphicsDriverConfig);
            if (!audioDriverConfig.isEmpty()) data.put("audioDriverConfig", audioDriverConfig);
            data.put("audioDriver", audioDriver);
            data.put("wincomponents", wincomponents);
            data.put("drives", drives);
            data.put("hudMode", hudMode);
            data.put("showHUD", showHUD);
            data.put("showQuickHUD", showQuickHUD);
            if (hudConfig != null) data.put("hudConfig", hudConfig.toJSONString());
            data.put("fullscreenStretched", fullscreenStretched);
            data.put("startupSelection", startupSelection);
            data.put("box64Preset", box64Preset);
            data.put("box64Version", box64Version);
            data.put("lcAll", getLCAll());
            data.put("timezone", getTimezone());
            data.put("rcfileId", rcfileId);
            data.put("desktopTheme", desktopTheme);
            data.put("extraData", extraData);

            if (!WineInfo.isMainWineVersion(wineVersion)) data.put("wineVersion", wineVersion);
            FileUtils.writeString(getConfigFile(), data.toString());
        }
        catch (JSONException e) {}
    }

    public void loadData(JSONObject data) throws JSONException {
        wineVersion = WineInfo.MAIN_WINE_INFO.identifier();
        dxwrapperConfig = "";
        graphicsDriverConfig = "";
        audioDriverConfig = "";

        checkObsoleteOrMissingProperties(data);

        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "name" :
                    setName(data.getString(key));
                    break;
                case "screenSize" :
                    setScreenSize(data.getString(key));
                    break;
                case "envVars" :
                    setEnvVars(data.getString(key));
                    break;
                case "cpuList" :
                    setCPUList(data.getString(key));
                    break;
                case "cpuListWoW64" :
                    setCPUListWoW64(data.getString(key));
                    break;
                case "graphicsDriver" :
                    setGraphicsDriver(data.getString(key));
                    break;
                case "wincomponents" :
                    setWinComponents(data.getString(key));
                    break;
                case "dxwrapper" :
                    setDXWrapper(data.getString(key));
                    break;
                case "dxwrapperConfig" :
                    setDXWrapperConfig(data.getString(key));
                    break;
                case "graphicsDriverConfig" :
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                case "audioDriverConfig" :
                    setAudioDriverConfig(data.getString(key));
                    break;
                case "drives" :
                    setDrives(data.getString(key));
                    break;
                case "showFPS" :
                    setShowHUD(data.getBoolean(key));
                    break;
                case "showHUD" :
                    setShowHUD(data.getBoolean(key));
                    break;
                case "showQuickHUD" :
                    setShowQuickHUD(data.getBoolean(key));
                    break;
                case "hudConfig" :
                    setHUDConfig(HUDConfig.fromJSONString(data.getString(key)));
                    break;
                case "hudMode" :
                    setHUDMode((byte)data.getInt(key));
                    if (!data.has("showHUD")) setShowHUD(hudMode != FrameRating.Mode.DISABLED.ordinal());
                    break;
                case "fullscreenStretched" :
                    setFullscreenStretched(data.getBoolean(key));
                    break;
                case "startupSelection" :
                    setStartupSelection((byte)data.getInt(key));
                    break;
                case "extraData" : {
                    JSONObject extraData = data.getJSONObject(key);
                    checkObsoleteOrMissingProperties(extraData);
                    setExtraData(extraData);
                    break;
                }
                case "wineVersion" :
                    setWineVersion(data.getString(key));
                    break;
                case "box64Preset" :
                    setBox64Preset(data.getString(key));
                    break;
                case "box64Version" :
                    setBox64Version(data.getString(key));
                    break;
                case "lcAll" :
                    setLCAll(data.getString(key));
                    break;
                case "timezone" :
                case "tz" :
                    setTimezone(data.getString(key));
                    break;
                case "rcfileId" :
                    setRcfileId(data.getInt(key));
                    break;
                case "audioDriver" :
                    setAudioDriver(data.getString(key));
                    break;
                case "desktopTheme" :
                    setDesktopTheme(data.getString(key));
                    break;
            }
        }
    }

    public static void checkObsoleteOrMissingProperties(JSONObject data) {
        try {
            if (data.has("extraData")) {
                JSONObject extraData = data.getJSONObject("extraData");
                int appVersion = Integer.parseInt(extraData.optString("appVersion", "0"));

                if (appVersion < 16 && data.has("envVars")) {
                    EnvVars defaultEnvVars = new EnvVars(DEFAULT_ENV_VARS);
                    EnvVars envVars = new EnvVars(data.getString("envVars"));
                    for (String name : defaultEnvVars) if (!envVars.has(name)) envVars.put(name, defaultEnvVars.get(name));
                    data.put("envVars", envVars.toString());
                }
            }

            KeyValueSet wincomponents1 = new KeyValueSet(DEFAULT_WINCOMPONENTS);
            KeyValueSet wincomponents2 = new KeyValueSet(data.getString("wincomponents"));
            String result = "";

            for (String[] wincomponent1 : wincomponents1) {
                String value = wincomponent1[1];

                for (String[] wincomponent2 : wincomponents2) {
                    if (wincomponent1[0].equals(wincomponent2[0])) {
                        value = wincomponent2[1];
                        break;
                    }
                }

                result += (!result.isEmpty() ? "," : "")+wincomponent1[0]+"="+value;
            }

            data.put("wincomponents", result);
        }
        catch (JSONException e) {}
    }

    public static String getFallbackCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }
}
