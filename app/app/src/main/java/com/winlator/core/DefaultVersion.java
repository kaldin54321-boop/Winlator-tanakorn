package com.winlator.core;

import com.winlator.container.GraphicsDrivers;

import java.util.Locale;

public abstract class DefaultVersion {
    public static final String BOX64 = "0.4.4";
    public static final String TURNIP = "26.2.0";
    public static final String VORTEK = "2.1";
    public static final String ZINK = "22.2.5";
    // Pinned to the last WORKING asset until the 25.0.7 CI artifact lands:
    // run build-virgl-winlator.yml (default Mesa 25.0.7, includes the W8
    // null-dt present + W12 NULL-ctx guard that the blackscreened 25.0.0
    // asset predates), drop the produced virgl-25.0.7.tzst next to the
    // 23.1.9 file, then flip this to "25.0.7". Must always name a file
    // present under app/src/main/assets/graphics_driver/.
    public static final String VIRGL = "25.0.7";
    public static final String GLADIO = "1.1";
    // Pinned to 25.0.0 (Mesa 25 + LLVM 15, contemporaneous pairing): the
    // in-house Mesa 26.0.0 + LLVM 22 build rendered clear-color/black only
    // (no geometry, zero errors) on both the GL and Vulkan slots, while its
    // server protocol and env were verified byte-identical to the working
    // reference -- i.e. the 26.0.0 binaries' shared llvmpipe rasterizer is
    // suspect (possibly misbuilt under QEMU user-mode CI). 25.0.0 is the
    // proven correctly-paired fallback. Revisit only with a natively-built
    // and on-device-verified Mesa 26+ asset; keep build-llvmpipe-winlator.yml
    // defaults in sync with this pin.
    public static final String LLVMPIPE = "25.0.0";
    public static final String D7VK = "1.11";
    public static final String DGVOODOO = "1.0";
    public static final String D8VK = "1.0";
    public static final String VKD3D = "2.14.1";
    public static final String WINED3D = WineInfo.MAIN_WINE_VERSION;
    public static final String CNC_DDRAW = "6.6";
    public static final String SOUNDFONT = "SONiVOX-EAS-GM-Wavetable";
    public static final String MINOR_DXVK = "1.10.3";
    public static final String MAJOR_DXVK = "2.4.1";
    public static final String VEGAS = "2.7.3";

    public static String VEGAS() {
        return VEGAS;
    }

    public static String DXVK() {
        return DXVK(null);
    }

    public static String DXVK(String vulkanDriver) {
        int vkApiVersion = 0;
        if (vulkanDriver != null && vulkanDriver.equals(GraphicsDrivers.VORTEK)) vkApiVersion = GPUHelper.vkGetApiVersion();
        // lavapipe (llvmpipe Vulkan) exposes Vulkan 1.4, same as Turnip -> always MAJOR_DXVK.
        // Vortek keeps the GPU-dependent check since its vk version comes from the host GPU.
        return vulkanDriver == null || vulkanDriver.equals(GraphicsDrivers.TURNIP) || vulkanDriver.equals(GraphicsDrivers.LLVMPIPE) || vkApiVersion >= GPUHelper.vkMakeVersion(1, 3, 0) ? MAJOR_DXVK : MINOR_DXVK;
    }

    public static String valueOf(String name) {
        switch (name.toUpperCase(Locale.ENGLISH)) {
            case "BOX64": return BOX64;
            case "TURNIP": return TURNIP;
            case "VORTEK": return VORTEK;
            case "ZINK": return ZINK;
            case "VIRGL": return VIRGL;
            case "GLADIO": return GLADIO;
            case "LLVMPIPE": return LLVMPIPE;
            case "D7VK": return D7VK;
            case "DGVOODOO": return DGVOODOO;
            case "D8VK": return D8VK;
            case "VKD3D": return VKD3D;
            case "WINED3D": return WINED3D;
            case "CNC_DDRAW": return CNC_DDRAW;
            case "SOUNDFONT": return SOUNDFONT;
            case "VEGAS": return VEGAS;
            default: return "0.0";
        }
    }
}