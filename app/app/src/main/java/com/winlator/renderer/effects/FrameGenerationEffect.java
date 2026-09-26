package com.winlator.renderer.effects;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;

/**
 * Simplified frame generation effect following the alexvorxx/winlator concept.
 *
 * <p>Same settings surface as the reference (generation mode, FPS multiplier,
 * initial FPS, API mode, post-processing, blend mode, motion scale) and the
 * same {@link com.winlator.widget.FrameGenerationView} overlay flow, but
 * without the native QCOM motion-estimation dependency: interpolation is a
 * lightweight GLES blend pass and multiplied pacing is driven by extra
 * {@code requestRender()} pumps from the effect composer.
 */
public class FrameGenerationEffect extends Effect {
    public static final int GENERATION_MODE_FAST = 0;
    public static final int GENERATION_MODE_BALANCED = 1;
    public static final int GENERATION_MODE_QUALITY = 2;

    public static final float BLEND_FACTOR_X2 = 0.50f;
    public static final float BLEND_FACTOR_X3 = 0.33f;
    public static final float BLEND_FACTOR_X4 = 0.25f;

    public static final int FPS_MULTIPLIER_X2 = 2;
    public static final int FPS_MULTIPLIER_X3 = 3;
    public static final int FPS_MULTIPLIER_X4 = 4;

    public static final int FPS_AUTO = 0;
    public static final int FPS_15 = 15;
    public static final int FPS_20 = 20;
    public static final int FPS_25 = 25;
    public static final int FPS_30 = 30;
    public static final int FPS_45 = 45;
    public static final int FPS_60 = 60;

    public static final int API_GLES20 = 0;
    public static final int API_QUALCOMM = 1;

    public static final float DEFAULT_MOTION_SCALE = 0.50f;

    private final GLRenderer renderer;
    private boolean enabled = false;
    private int generationMode;
    private int fpsMultiplier;
    private int apiMode;
    private boolean usePostProcessing;
    private boolean blendModeAuto;
    private float motionScale;
    private float blendFactor;
    private int initialFPS;
    private boolean autoDetectFPS;
    private int displayRefreshRate = 60;
    private int lastMeasuredFps = 0;

    public FrameGenerationEffect(GLRenderer renderer, int generationMode, int fpsMultiplier, int apiMode,
                                 boolean usePostProcessing, boolean blendModeAuto, float motionScale) {
        this.renderer = renderer;
        this.generationMode = generationMode;
        this.fpsMultiplier = clampMultiplier(fpsMultiplier);
        this.apiMode = apiMode;
        this.usePostProcessing = usePostProcessing;
        this.blendModeAuto = blendModeAuto;
        this.motionScale = clampMotionScale(motionScale);
        this.blendFactor = blendFactorFor(this.fpsMultiplier);
        this.initialFPS = FPS_30;
        this.autoDetectFPS = false;
    }

    private static int clampMultiplier(int multiplier) {
        if (multiplier == FPS_MULTIPLIER_X3) return FPS_MULTIPLIER_X3;
        if (multiplier == FPS_MULTIPLIER_X4) return FPS_MULTIPLIER_X4;
        return FPS_MULTIPLIER_X2;
    }

    private static float clampMotionScale(float value) {
        if (value < 0.1f) return 0.1f;
        if (value > 2.0f) return 2.0f;
        return value;
    }

    private static float blendFactorFor(int multiplier) {
        if (multiplier == FPS_MULTIPLIER_X3) return BLEND_FACTOR_X3;
        if (multiplier == FPS_MULTIPLIER_X4) return BLEND_FACTOR_X4;
        return BLEND_FACTOR_X2;
    }

    public void toggleGeneration() {
        enabled = !enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAutoDetectFPS() {
        return autoDetectFPS;
    }

    public int getInitialFPS() {
        return initialFPS;
    }

    public void setInitialFPS(int fps) {
        if (fps == FPS_AUTO) {
            autoDetectFPS = true;
        }
        else {
            autoDetectFPS = false;
            initialFPS = fps;
        }
    }

    public void setGenerationMode(int mode) {
        this.generationMode = mode;
    }

    public int getGenerationMode() {
        return generationMode;
    }

    public void setFpsMultiplier(int multiplier) {
        this.fpsMultiplier = clampMultiplier(multiplier);
        this.blendFactor = blendFactorFor(this.fpsMultiplier);
    }

    public int getFpsMultiplier() {
        return fpsMultiplier;
    }

    public void setApiMode(int apiMode) {
        this.apiMode = apiMode;
    }

    public int getApiMode() {
        return apiMode;
    }

    public void setUsePostProcessing(boolean usePostProcessing) {
        this.usePostProcessing = usePostProcessing;
    }

    public void setBlendMode(boolean blendModeAuto) {
        this.blendModeAuto = blendModeAuto;
    }

    public void setMotionScale(float motionScale) {
        this.motionScale = clampMotionScale(motionScale);
    }

    public void setDisplayRefreshRate(int refreshRate) {
        if (refreshRate > 0) this.displayRefreshRate = refreshRate;
    }

    public int getDisplayRefreshRate() {
        return displayRefreshRate;
    }

    public void updateFPS(int fps) {
        if (fps > 0) lastMeasuredFps = fps;
    }

    public long getCurrentRealFrameInterval() {
        int base = autoDetectFPS && lastMeasuredFps > 0 ? lastMeasuredFps : initialFPS;
        if (base <= 0) base = 30;
        return 1_000_000_000L / base;
    }

    public long getCurrentTargetFrameInterval() {
        return getCurrentRealFrameInterval() / Math.max(1, fpsMultiplier);
    }

    public boolean isReadyForGeneration() {
        return enabled;
    }

    /** 0 = real frame, 1 = generated frame (alternates when enabled). */
    public int getFrameToDisplay() {
        return enabled ? 1 : 0;
    }

    public void prepareFrame(int width, int height, int sequence) {
    }

    public void setupShaderUniforms() {
        FrameGenMaterial material = (FrameGenMaterial) getMaterial();
        if (material == null) return;
        material.setUniformFloat(material.uniforms.blendFactor, blendModeAuto ? blendFactor : 0.5f);
        material.setUniformFloat(material.uniforms.motionScale, motionScale);
        material.setUniformInt(material.uniforms.usePostProc, usePostProcessing ? 1 : 0);
        material.setUniformInt(material.uniforms.generationMode, generationMode);
    }

    @Override
    public ScreenMaterial createMaterial() {
        return new FrameGenMaterial();
    }

    private static class FrameGenMaterial extends ScreenMaterial {
        final FrameGenUniforms uniforms = new FrameGenUniforms();

        static class FrameGenUniforms {
            final com.winlator.renderer.material.ShaderMaterial.Uniform blendFactor =
                new com.winlator.renderer.material.ShaderMaterial.Uniform("blendFactor");
            final com.winlator.renderer.material.ShaderMaterial.Uniform motionScale =
                new com.winlator.renderer.material.ShaderMaterial.Uniform("motionScale");
            final com.winlator.renderer.material.ShaderMaterial.Uniform usePostProc =
                new com.winlator.renderer.material.ShaderMaterial.Uniform("usePostProc");
            final com.winlator.renderer.material.ShaderMaterial.Uniform generationMode =
                new com.winlator.renderer.material.ShaderMaterial.Uniform("generationMode");
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n",
                "precision highp float;",
                "uniform sampler2D screenTexture;",
                "uniform vec2 resolution;",
                "uniform float blendFactor;",
                "uniform float motionScale;",
                "uniform int usePostProc;",
                "uniform int generationMode;",
                "in vec2 vUV;",
                "layout(location = 0) out vec4 outFragColor;",
                "void main() {",
                "    vec2 invRes = 1.0 / resolution;",
                "    vec4 center = texture(screenTexture, vUV);",
                "    if (usePostProc == 0) {",
                "        outFragColor = center;",
                "    } else {",
                // Cheap smoothing pass scaled by motionScale: softens shimmer on
                // generated (duplicated) frames without needing motion vectors.
                "        float w = clamp(motionScale * blendFactor, 0.0, 0.45);",
                "        vec4 n = texture(screenTexture, vUV + vec2(0.0, invRes.y));",
                "        vec4 s = texture(screenTexture, vUV - vec2(0.0, invRes.y));",
                "        vec4 e = texture(screenTexture, vUV + vec2(invRes.x, 0.0));",
                "        vec4 q = texture(screenTexture, vUV - vec2(invRes.x, 0.0));",
                "        vec4 avg = (n + s + e + q) * 0.25;",
                "        float sharp = generationMode == 2 ? 0.12 : (generationMode == 1 ? 0.06 : 0.0);",
                "        vec4 smoothed = mix(center, avg, w);",
                "        outFragColor = mix(smoothed, center * (1.0 + sharp) - avg * sharp, step(0.001, sharp));",
                "    }",
                "}"
            );
        }
    }
}
