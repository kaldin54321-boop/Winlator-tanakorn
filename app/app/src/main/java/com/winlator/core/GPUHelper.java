package com.winlator.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.EGL14;

import androidx.collection.ArrayMap;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

import dalvik.annotation.optimization.CriticalNative;

public abstract class GPUHelper {
    public enum VkPresentMode {
        IMMEDIATE, MAILBOX, FIFO, FIFO_RELAXED;

        public String value() {
            return name().toLowerCase(Locale.ENGLISH);
        }
    }
    public static int VK_API_VERSION_1_3 = GPUHelper.vkMakeVersion(1, 3, 0);

    public static final String[] ESSENTIAL_VULKAN_EXTENSIONS = {
        "VK_KHR_swapchain",
        "VK_KHR_get_physical_device_properties2",
        "VK_KHR_maintenance1",
        "VK_KHR_maintenance2",
        "VK_KHR_maintenance3",
        "VK_KHR_maintenance4",
        "VK_KHR_multiview",
        "VK_KHR_device_group",
        "VK_KHR_shader_draw_parameters",
        "VK_KHR_draw_indirect_count",
        "VK_KHR_8bit_storage",
        "VK_KHR_16bit_storage",
        "VK_KHR_shader_float16_int8",
        "VK_KHR_shader_float_controls",
        "VK_KHR_bind_memory2",
        "VK_KHR_sampler_mirror_clamp_to_edge",
        "VK_KHR_shader_clock",
        "VK_KHR_depth_stencil_resolve",
        "VK_KHR_driver_properties",
        "VK_KHR_image_format_list",
        "VK_KHR_imageless_framebuffer",
        "VK_KHR_create_renderpass2",
        "VK_KHR_separate_depth_stencil_layouts",
        "VK_KHR_uniform_buffer_standard_layout",
        "VK_KHR_buffer_device_address",
        "VK_KHR_pipeline_executable_properties",
        "VK_KHR_shader_non_semantic_info",
        "VK_KHR_present_id",
        "VK_KHR_present_wait",
        "VK_KHR_synchronization2",
        "VK_KHR_zero_initialize_workgroup_memory",
        "VK_KHR_workgroup_memory_explicit_layout",
        "VK_KHR_copy_commands2",
        "VK_KHR_format_feature_flags2",
        "VK_KHR_dynamic_rendering",
        "VK_KHR_global_priority",
        "VK_KHR_load_store_op_none",
        "VK_KHR_shader_integer_dot_product",
        "VK_KHR_shader_terminate_invocation",
        "VK_EXT_transform_feedback",
        "VK_EXT_depth_clip_enable",
        "VK_EXT_custom_border_color",
        "VK_EXT_line_rasterization",
        "VK_EXT_provoking_vertex",
        "VK_EXT_sample_locations",
        "VK_EXT_scalar_block_layout",
        "VK_EXT_index_type_uint8",
        "VK_EXT_conditional_rendering",
        "VK_EXT_vertex_attribute_divisor",
        "VK_EXT_post_depth_coverage",
        "VK_EXT_subgroup_size_control",
        "VK_EXT_texel_buffer_alignment",
        "VK_EXT_robustness2",
        "VK_EXT_filter_cubic",
        "VK_EXT_private_data",
        "VK_EXT_pipeline_creation_cache_control",
        "VK_EXT_border_color_swizzle",
        "VK_EXT_image_robustness",
        "VK_EXT_4444_formats",
        "VK_EXT_ycbcr_2plane_444_formats",
        "VK_EXT_extended_dynamic_state",
        "VK_EXT_extended_dynamic_state2",
        "VK_EXT_extended_dynamic_state3",
        "VK_EXT_color_write_enable",
        "VK_EXT_graphics_pipeline_library",
        "VK_EXT_non_seamless_cube_map",
        "VK_EXT_descriptor_buffer",
        "VK_EXT_attachment_feedback_loop_layout",
        "VK_EXT_shader_module_identifier",
        "VK_EXT_mesh_shader",
        "VK_EXT_primitives_generated_query",
        "VK_VALVE_mutable_descriptor_type"
    };

    private static final String[] VULKAN_VENDOR_PREFIXES = {
        "VK_KHR_", "VK_EXT_", "VK_VALVE_", "VK_QCOM_", "VK_ARM_",
        "VK_AMD_", "VK_NV_", "VK_GOOGLE_", "VK_ANDROID_", "VK_MESA_",
        "VK_LUNARG_", "VK_IMG_", "VK_INTEL_", "VK_MSFT_", "VK_HUAWEI_"
    };

    static {
        System.loadLibrary("winlator");
    }

    private static ArrayMap<String, String> loadGPUInformation(Context context) {
        final Thread thread = Thread.currentThread();
        final ArrayMap<String, String> gpuInfo = new ArrayMap<>();
        gpuInfo.put("renderer", "");
        gpuInfo.put("vendor", "");
        gpuInfo.put("version", "");

        (new Thread(() -> {
            int[] attribList = new int[] {
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 0,
                EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCounts = new int[1];

            EGL10 egl = (EGL10)EGLContext.getEGL();
            EGLDisplay eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

            int[] version = new int[2];
            egl.eglInitialize(eglDisplay, version);
            egl.eglChooseConfig(eglDisplay, attribList, configs, 1, configCounts);

            attribList = new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};
            EGLContext eglContext = egl.eglCreateContext(eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, attribList);

            egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, eglContext);

            GL10 gl = (GL10)eglContext.getGL();
            String gpuRenderer = Objects.toString(gl.glGetString(GL10.GL_RENDERER), "");
            String gpuVendor = Objects.toString(gl.glGetString(GL10.GL_VENDOR), "");
            String gpuVersion = Objects.toString(gl.glGetString(GL10.GL_VERSION), "");

            gpuInfo.put("renderer", gpuRenderer);
            gpuInfo.put("vendor", gpuVendor);
            gpuInfo.put("version", gpuVersion);

            final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            preferences.edit()
                .putString("gpu_renderer", gpuRenderer)
                .putString("gpu_vendor", gpuVendor)
                .putString("gpu_version", gpuVersion)
                .apply();

            synchronized (thread) {
                thread.notify();
            }
        })).start();

        synchronized (thread) {
            try {
                thread.wait();
            }
            catch (InterruptedException e) {}
        }
        return gpuInfo;
    }

    public static String glGetRenderer(Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String value = preferences.getString("gpu_renderer", "");
        if (!value.isEmpty()) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("renderer");
    }

    public static String glGetVendor(Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String value = preferences.getString("gpu_vendor", "");
        if (!value.isEmpty()) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("vendor");
    }

    public static String glGetVersion(Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String value = preferences.getString("gpu_version", "");
        if (!value.isEmpty()) return value;

        ArrayMap<String, String> gpuInfo = loadGPUInformation(context);
        return gpuInfo.get("version");
    }

    public static short getAdrenoModelId(Context context) {
        Matcher matcher = Pattern.compile("adreno[^678]*([678][0-9]{2})", Pattern.CASE_INSENSITIVE).matcher(glGetRenderer(context));
        return (short)(matcher.find() ? Integer.parseInt(matcher.group(1)) : 0);
    }

    public static int vkMakeVersion(String value) {
        final Pattern pattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
        Matcher matcher = pattern.matcher(value);
        if (matcher.find()) {
            try {
                int major = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : 0;
                int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
                int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
                if (matcher.group(1) == null && patch == 0) patch = minor;
                return vkMakeVersion(major, minor, patch);
            }
            catch (NumberFormatException e) {
                return 0;
            }
        }
        else return 0;
    }

    public static int vkMakeVersion(int major, int minor, int patch) {
        return  ((major) << 22) | ((minor) << 12) | (patch);
    }

    public static int vkVersionMajor(int version) {
        return (version) >> 22;
    }

    public static int vkVersionMinor(int version) {
        return ((version) >> 12) & 0x3FF;
    }

    public static int vkVersionPatch(int version) {
        return (version) & 0xFFF;
    }

    public static native String[] vkGetDeviceExtensions();

    public static boolean isVulkanExtensionName(String str) {
        if (str == null || str.length() < 8 || str.length() > 128) return false;

        boolean hasVendorPrefix = false;
        for (String prefix : VULKAN_VENDOR_PREFIXES) {
            if (str.startsWith(prefix)) {
                hasVendorPrefix = true;
                break;
            }
        }
        if (!hasVendorPrefix) return false;

        if (str.contains("_STRUCTURE_TYPE") || str.contains("_CREATE_INFO") ||
            str.contains("_SPEC_VERSION") || str.contains("_EXTENSION_NAME") ||
            str.contains("_BEGIN_RANGE") || str.contains("_END_RANGE") ||
            str.contains("_RANGE_SIZE") || str.contains("_MAX_ENUM")) {
            return false;
        }

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    public static String[] getDriverExtensionsFromFile(File soFile) {
        if (soFile == null || !soFile.isFile()) return new String[0];
        TreeSet<String> extensions = new TreeSet<>();

        try (FileInputStream fis = new FileInputStream(soFile);
             FileChannel channel = fis.getChannel()) {
            long fileSize = channel.size();
            long maxRead = Math.min(fileSize, 64 * 1024 * 1024);
            ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, maxRead);

            byte[] bytes = new byte[(int) maxRead];
            buffer.get(bytes);

            for (int i = 0; i < bytes.length - 7; i++) {
                if (bytes[i] == 'V' && bytes[i + 1] == 'K' && bytes[i + 2] == '_') {
                    int start = i;
                    int end = start;
                    while (end < bytes.length) {
                        byte b = bytes[end];
                        if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '_') {
                            end++;
                        }
                        else {
                            break;
                        }
                    }
                    if (end - start >= 8) {
                        String str = new String(bytes, start, end - start, StandardCharsets.US_ASCII);
                        if (isVulkanExtensionName(str)) {
                            extensions.add(str);
                            if (extensions.size() >= 250) break;
                        }
                    }
                    i = end;
                }
            }
        }
        catch (Exception e) {}

        return extensions.toArray(new String[0]);
    }

    public static String[] vkGetDeviceExtensions(Context context, String adrenotoolsDriver) {
        if (context == null || adrenotoolsDriver == null || adrenotoolsDriver.isEmpty() || GeneralComponents.isBuiltinComponent(GeneralComponents.Type.ADRENOTOOLS_DRIVER, adrenotoolsDriver)) {
            String[] sysExts = vkGetDeviceExtensions();
            if (sysExts != null && sysExts.length > 0) return sysExts;

            String[] candidatePaths = {
                "/vendor/lib64/hw/vulkan.adreno.so",
                "/vendor/lib64/egl/libGLESv2_adreno.so",
                "/vendor/lib64/vulkan.adreno.so",
                "/system/lib64/libvulkan.so"
            };

            for (String path : candidatePaths) {
                File file = new File(path);
                if (file.isFile()) {
                    String[] scanned = getDriverExtensionsFromFile(file);
                    if (scanned != null && scanned.length > 0) return scanned;
                }
            }

            return ESSENTIAL_VULKAN_EXTENSIONS;
        }

        String libvulkanPath = GeneralComponents.getDefinitivePath(GeneralComponents.Type.ADRENOTOOLS_DRIVER, context, adrenotoolsDriver);
        if (libvulkanPath != null) {
            File soFile = new File(libvulkanPath);
            if (soFile.isFile()) {
                String[] customExts = getDriverExtensionsFromFile(soFile);
                if (customExts != null && customExts.length > 0) return customExts;
            }
        }

        return vkGetDeviceExtensions();
    }

    @CriticalNative
    public static native int vkGetApiVersion();

    public static native void setGlobalEGLContext();

    public static native long createOffscreenEGLContext(boolean sharedContext);

    public static native void destroyOffscreenEGLContext(long contextPtr);
}
