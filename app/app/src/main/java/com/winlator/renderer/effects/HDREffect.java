package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

public class HDREffect extends Effect {
    private float intensity = 1.0f;

    @Override
    public ScreenMaterial createMaterial() {
        final ShaderMaterial.Uniform intensityUniform = new ShaderMaterial.Uniform("intensity");

        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return String.join("\n",
                    "precision highp float;",

                    "uniform sampler2D screenTexture;",
                    "uniform float intensity;",
                    "in vec2 vUV;",

                    "layout(location = 0) out vec4 outFragColor;",

                    "void main() {",
                        "vec4 texelColor = texture(screenTexture, vUV);",
                        "vec3 color = texelColor.rgb;",

                        "float luma = dot(color, vec3(0.299, 0.587, 0.114));",
                        "float highlight = smoothstep(0.4, 1.0, luma);",
                        "float boost = 1.0 + intensity * highlight * 0.5;",

                        "color = clamp(color * boost + vec3(highlight * intensity * 0.2), 0.0, 1.0);",

                        "outFragColor = vec4(color, texelColor.a);",
                    "}"
                );
            }

            @Override
            public void use() {
                super.use();
                setUniformFloat(intensityUniform, intensity);
            }
        };
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
    }
}