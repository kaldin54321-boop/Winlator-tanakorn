package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

/**
 * Screen-space Toon (cel) shader.
 *
 * <p>How it works (post-process characteristics): unlike true 3D cel-shading,
 * which quantizes lighting per-fragment using scene light data, a screen
 * effect only sees the finished framebuffer. So the Toon look is reproduced
 * with the two classic 2D cartoon-filter steps:
 *
 * <ol>
 *   <li><b>Posterization (color banding):</b> each RGB channel is quantized
 *       into {@code levels} discrete steps
 *       ({@code floor(c * levels + 0.5) / levels}), collapsing smooth
 *       gradients into flat cartoon bands.</li>
 *   <li><b>Sobel edge outline:</b> luminance is sampled in a 3x3 neighbourhood
 *       (texel size from the {@code resolution} uniform the composer already
 *       provides), horizontal/vertical Sobel gradients give an edge magnitude,
 *       and pixels on an edge are darkened toward ink black proportionally to
 *       the intensity.</li>
 * </ol>
 *
 * <p>{@code intensity} (0..1) cross-fades between the original frame and the
 * full toon look so the slider acts as effect strength.
 */
public class ToonEffect extends Effect {
    /** Default posterization bands, a typical cartoon-filter value. */
    public static final float DEFAULT_LEVELS = 5.0f;
    private float intensity = 1.0f;
    private float levels = DEFAULT_LEVELS;

    public ToonEffect() {}

    public ToonEffect(float intensity) {
        setIntensity(intensity);
    }

    @Override
    public ScreenMaterial createMaterial() {
        final ShaderMaterial.Uniform intensityUniform = new ShaderMaterial.Uniform("intensity");
        final ShaderMaterial.Uniform levelsUniform = new ShaderMaterial.Uniform("levels");

        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return String.join("\n",
                    "precision highp float;",

                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "uniform float intensity;",
                    "uniform float levels;",
                    "in vec2 vUV;",

                    "layout(location = 0) out vec4 outFragColor;",

                    "float toonLuma(vec3 c) {",
                        "return dot(c, vec3(0.299, 0.587, 0.114));",
                    "}",

                    "void main() {",
                        "vec4 texelColor = texture(screenTexture, vUV);",
                        "vec3 color = texelColor.rgb;",

                        // 1. Posterize: quantize each channel into discrete bands.
                        "vec3 posterized = floor(color * levels + 0.5) / levels;",

                        // 2. Sobel edge detection on luma for the ink outline.
                        "vec2 texel = 1.0 / resolution;",
                        "float tl = toonLuma(texture(screenTexture, vUV + texel * vec2(-1.0, -1.0)).rgb);",
                        "float t  = toonLuma(texture(screenTexture, vUV + texel * vec2( 0.0, -1.0)).rgb);",
                        "float tr = toonLuma(texture(screenTexture, vUV + texel * vec2( 1.0, -1.0)).rgb);",
                        "float l  = toonLuma(texture(screenTexture, vUV + texel * vec2(-1.0,  0.0)).rgb);",
                        "float r  = toonLuma(texture(screenTexture, vUV + texel * vec2( 1.0,  0.0)).rgb);",
                        "float bl = toonLuma(texture(screenTexture, vUV + texel * vec2(-1.0,  1.0)).rgb);",
                        "float b  = toonLuma(texture(screenTexture, vUV + texel * vec2( 0.0,  1.0)).rgb);",
                        "float br = toonLuma(texture(screenTexture, vUV + texel * vec2( 1.0,  1.0)).rgb);",

                        "float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;",
                        "float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;",
                        "float edge = clamp(sqrt(gx * gx + gy * gy), 0.0, 1.0);",
                        "float ink = 1.0 - smoothstep(0.15, 0.6, edge);",

                        "vec3 toon = posterized * ink;",

                        // Cross-fade original -> toon by intensity (effect strength).
                        "vec3 result = mix(color, toon, clamp(intensity, 0.0, 1.0));",

                        "outFragColor = vec4(result, texelColor.a);",
                    "}"
                );
            }

            @Override
            public void use() {
                super.use();
                setUniformFloat(intensityUniform, intensity);
                setUniformFloat(levelsUniform, Math.max(2.0f, levels));
            }
        };
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = Math.max(0.0f, Math.min(1.0f, intensity));
    }

    public float getLevels() {
        return levels;
    }

    public void setLevels(float levels) {
        this.levels = Math.max(2.0f, Math.min(12.0f, levels));
    }
}
