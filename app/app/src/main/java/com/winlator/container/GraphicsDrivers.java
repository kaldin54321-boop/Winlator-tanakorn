package com.winlator.container;

import android.content.Context;

import com.winlator.core.GPUHelper;
import com.winlator.core.KeyValueSet;

public abstract class GraphicsDrivers {
    public static final String TURNIP = "turnip";
    public static final String VORTEK = "vortek";
    public static final String ZINK = "zink";
    public static final String VIRGL = "virgl";
    public static final String GLADIO = "gladio";
    public static final String LLVMPIPE = "llvmpipe";
    public static final String DEFAULT_VULKAN_DRIVER = VORTEK;
    public static final String DEFAULT_OPENGL_DRIVER = GLADIO;

    public static String getName(String identifier) {
        switch (identifier) {
            case TURNIP: return "Turnip";
            case VORTEK: return "Vortek";
            case ZINK: return "Zink";
            case VIRGL: return "VirGL";
            case GLADIO: return "Gladio";
            case LLVMPIPE: return "LLVMPipe";
            default: return "None";
        }
    }

    public static boolean isVulkanDriver(String identifier) {
        return identifier != null && (identifier.equals(TURNIP) || identifier.equals(VORTEK) || identifier.equals(LLVMPIPE));
    }

    public static boolean isOpenGLDriver(String identifier) {
        return identifier != null && (identifier.equals(ZINK) || identifier.equals(VIRGL) || identifier.equals(GLADIO) || identifier.equals(LLVMPIPE));
    }

    public static String[] getItems(String apiName) {
        if (apiName.equalsIgnoreCase("VULKAN")) {
            return new String[]{getName(TURNIP), getName(VORTEK), getName(LLVMPIPE)};
        }
        else if (apiName.equalsIgnoreCase("OPENGL")) {
            return new String[]{getName(ZINK), getName(VIRGL), getName(GLADIO), getName(LLVMPIPE)};
        }
        else return new String[0];
    }

    public static String[] parseIdentifiers(String graphicsDriver) {
        if (graphicsDriver == null || graphicsDriver.isEmpty()) return new String[]{DEFAULT_VULKAN_DRIVER, DEFAULT_OPENGL_DRIVER};
        if (graphicsDriver.contains(",")) {
            return graphicsDriver.split(",");
        }
        else {
            if (isVulkanDriver(graphicsDriver)) {
                return new String[]{graphicsDriver, DEFAULT_OPENGL_DRIVER};
            }
            else if (isOpenGLDriver(graphicsDriver)) {
                return new String[]{DEFAULT_VULKAN_DRIVER, graphicsDriver};
            }
            else return new String[]{DEFAULT_VULKAN_DRIVER, DEFAULT_OPENGL_DRIVER};
        }
    }

    public static KeyValueSet[] parseConfigs(String graphicsDriver, String graphicsDriverConfig) {
        if (graphicsDriverConfig == null || graphicsDriverConfig.isEmpty()) return new KeyValueSet[]{new KeyValueSet(), new KeyValueSet()};
        int separator = graphicsDriverConfig.indexOf("|");
        if (separator != -1) {
            String first = graphicsDriverConfig.substring(0, separator);
            String second = graphicsDriverConfig.substring(separator + 1);
            return new KeyValueSet[]{new KeyValueSet(first), new KeyValueSet(second)};
        }
        else {
            String[] identifiers = parseIdentifiers(graphicsDriver);
            if (identifiers.length > 1) {
                if (graphicsDriverConfig.contains("glVersion") || graphicsDriverConfig.contains("disableVertexArrayBGRA")
                        || graphicsDriverConfig.contains("disableKHRdebug") || graphicsDriverConfig.contains("disableTextureSRGBdecode")) {
                    return new KeyValueSet[]{new KeyValueSet(), new KeyValueSet(graphicsDriverConfig)};
                }
                else if (graphicsDriverConfig.contains("tuDebug") || graphicsDriverConfig.contains("maxDeviceMemory")) {
                    return new KeyValueSet[]{new KeyValueSet(graphicsDriverConfig), new KeyValueSet()};
                }
            }

            if (isVulkanDriver(identifiers[0])) {
                return new KeyValueSet[]{new KeyValueSet(graphicsDriverConfig), new KeyValueSet()};
            }
            else if (isOpenGLDriver(identifiers[0])) {
                return new KeyValueSet[]{new KeyValueSet(), new KeyValueSet(graphicsDriverConfig)};
            }
            else return new KeyValueSet[]{new KeyValueSet(), new KeyValueSet()};
        }
    }

    public static String getDefaultDriver(Context context) {
        return GPUHelper.getAdrenoModelId(context) > 0 ? GraphicsDrivers.TURNIP+","+GraphicsDrivers.DEFAULT_OPENGL_DRIVER : GraphicsDrivers.VORTEK+","+GraphicsDrivers.DEFAULT_OPENGL_DRIVER;
    }
}
