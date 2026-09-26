package com.winlator.renderer.material;

public class WindowMaterial extends ShaderMaterial {
    public final Uniforms uniforms = new Uniforms();

    public static class Uniforms {
        public final Uniform xform = new Uniform("xform");
        public final Uniform viewSize = new Uniform("viewSize");
        public final Uniform texture = new Uniform("texture");
        public final Uniform noAlpha = new Uniform("noAlpha");
        public final Uniform flipY = new Uniform("flipY");
        public final Uniform softStretch = new Uniform("softStretch");
        public final Uniform texelSize = new Uniform("texelSize");
    }

    @Override
    protected String getVertexShader() {
        return String.join("\n",
            "uniform float xform[6];",
            "uniform vec2 viewSize;",
            "uniform bool flipY;",

            "in vec2 position;",
            "out vec2 vUV;",

            "void main() {",
                "vUV = vec2(position.x, flipY ? (1.0 - position.y) : position.y);",
                "vec2 transformedPos = applyXForm(position, xform);",
                "gl_Position = vec4(2.0 * transformedPos.x / viewSize.x - 1.0, 1.0 - 2.0 * transformedPos.y / viewSize.y, 0.0, 1.0);",
            "}"
        );
    }

    @Override
    protected String getFragmentShader() {
        return String.join("\n",
            "precision mediump float;",

            "uniform sampler2D windowTexture;",
            "uniform float noAlpha;",
            "uniform bool softStretch;",
            "uniform vec2 texelSize;",
            "in vec2 vUV;",

            "layout(location = 0) out vec4 outFragColor;",

            "vec4 cubic(vec4 p0, vec4 p1, vec4 p2, vec4 p3, float t) {",
                "vec4 a = -0.5 * p0 + 1.5 * p1 - 1.5 * p2 + 0.5 * p3;",
                "vec4 b = p0 - 2.5 * p1 + 2.0 * p2 - 0.5 * p3;",
                "vec4 c = -0.5 * p0 + 0.5 * p2;",
                "vec4 d = p1;",
                "return a * t * t * t + b * t * t + c * t + d;",
            "}",

            "vec4 sampleWindowTexture(sampler2D tex, vec2 uv) {",
                "if (!softStretch) return texture(tex, uv);",

                "vec2 r = uv / texelSize - vec2(0.5);",
                "vec2 f = fract(r);",
                "vec2 base = (r - f + 0.5) * texelSize;",

                "vec4 row0 = cubic(",
                    "texture(tex, base + vec2(-texelSize.x, -texelSize.y)),",
                    "texture(tex, base + vec2(0.0, -texelSize.y)),",
                    "texture(tex, base + vec2(texelSize.x, -texelSize.y)),",
                    "texture(tex, base + vec2(2.0 * texelSize.x, -texelSize.y)),",
                    "f.x);",

                "vec4 row1 = cubic(",
                    "texture(tex, base + vec2(-texelSize.x, 0.0)),",
                    "texture(tex, base + vec2(0.0, 0.0)),",
                    "texture(tex, base + vec2(texelSize.x, 0.0)),",
                    "texture(tex, base + vec2(2.0 * texelSize.x, 0.0)),",
                    "f.x);",

                "vec4 row2 = cubic(",
                    "texture(tex, base + vec2(-texelSize.x, texelSize.y)),",
                    "texture(tex, base + vec2(0.0, texelSize.y)),",
                    "texture(tex, base + vec2(texelSize.x, texelSize.y)),",
                    "texture(tex, base + vec2(2.0 * texelSize.x, texelSize.y)),",
                    "f.x);",

                "vec4 row3 = cubic(",
                    "texture(tex, base + vec2(-texelSize.x, 2.0 * texelSize.y)),",
                    "texture(tex, base + vec2(0.0, 2.0 * texelSize.y)),",
                    "texture(tex, base + vec2(texelSize.x, 2.0 * texelSize.y)),",
                    "texture(tex, base + vec2(2.0 * texelSize.x, 2.0 * texelSize.y)),",
                    "f.x);",

                "return cubic(row0, row1, row2, row3, f.y);",
            "}",

            "void main() {",
                "vec4 texelColor = sampleWindowTexture(windowTexture, vUV);",
                "outFragColor = vec4(texelColor.rgb, max(texelColor.a, noAlpha));",
            "}"
        );
    }
}
