#version 330 core

in vec2 vUv;
out vec4 outColor;

uniform sampler2D uTexture2D;
uniform samplerCube uTextureCube;
uniform int uSourceKind;
uniform int uMode;
uniform int uChannel;
uniform float uExposureEv;
uniform vec2 uRange;
uniform int uFalseColor;
uniform int uDepthInterpretation;
uniform vec2 uNearFar;
uniform int uInvertDepth;
uniform int uCubeFace;
uniform int uMipLevel;
uniform int uCheckerboard;

vec3 cubeDirection(vec2 uv) {
    vec2 p = uv * 2.0 - 1.0;
    if (uCubeFace == 0) return normalize(vec3( 1.0, -p.y, -p.x));
    if (uCubeFace == 1) return normalize(vec3(-1.0, -p.y,  p.x));
    if (uCubeFace == 2) return normalize(vec3( p.x,  1.0,  p.y));
    if (uCubeFace == 3) return normalize(vec3( p.x, -1.0, -p.y));
    if (uCubeFace == 4) return normalize(vec3( p.x, -p.y,  1.0));
    return normalize(vec3(-p.x, -p.y, -1.0));
}

vec4 sourceValue() {
    if (uSourceKind == 1) {
        return textureLod(uTextureCube, cubeDirection(vUv), float(uMipLevel));
    }
    return texture(uTexture2D, vUv);
}

vec4 selectChannel(vec4 value) {
    if (uChannel == 0) return value;
    if (uChannel == 1) return vec4(value.rgb, 1.0);
    if (uChannel == 2) return vec4(value.rrr, 1.0);
    if (uChannel == 3) return vec4(value.ggg, 1.0);
    if (uChannel == 4) return vec4(value.bbb, 1.0);
    if (uChannel == 5) return vec4(value.aaa, 1.0);
    return vec4(value.rg, 0.0, 1.0);
}

vec3 falseColor(float value) {
    float x = clamp(value, 0.0, 1.0);
    return clamp(vec3(1.5 - abs(4.0 * x - 3.0),
                      1.5 - abs(4.0 * x - 2.0),
                      1.5 - abs(4.0 * x - 1.0)), 0.0, 1.0);
}

vec3 acesFitted(vec3 value) {
    return clamp((value * (2.51 * value + 0.03)) /
                 (value * (2.43 * value + 0.59) + 0.14), 0.0, 1.0);
}

float interpretedDepth(float depth) {
    if (uDepthInterpretation == 1) {
        float z = depth * 2.0 - 1.0;
        return (2.0 * uNearFar.x * uNearFar.y) /
               max(uNearFar.y + uNearFar.x - z * (uNearFar.y - uNearFar.x), 1e-7);
    }
    if (uDepthInterpretation == 2) {
        return mix(uNearFar.x, uNearFar.y, depth);
    }
    return depth;
}

void main() {
    vec4 raw = sourceValue();
    if (any(isnan(raw))) {
        outColor = vec4(1.0, 0.0, 1.0, 1.0);
        return;
    }
    if (any(isinf(raw))) {
        bool positive = any(greaterThan(raw, vec4(0.0)));
        outColor = positive ? vec4(1.0, 1.0, 0.0, 1.0)
                            : vec4(0.0, 1.0, 1.0, 1.0);
        return;
    }

    vec4 selected = selectChannel(raw);
    vec3 result;
    if (uMode == 2) {
        float depth = interpretedDepth(raw.r);
        float normalized = (depth - uRange.x) / (uRange.y - uRange.x);
        if (uInvertDepth != 0) normalized = 1.0 - normalized;
        result = uFalseColor != 0 ? falseColor(normalized) : vec3(clamp(normalized, 0.0, 1.0));
    } else if (uMode == 1) {
        vec3 exposed = selected.rgb * exp2(uExposureEv);
        float normalized = (dot(exposed, vec3(0.2126, 0.7152, 0.0722)) - uRange.x)
                           / (uRange.y - uRange.x);
        result = uFalseColor != 0 ? falseColor(normalized) : acesFitted(max(exposed, vec3(0.0)));
    } else {
        vec3 normalized = (selected.rgb - vec3(uRange.x)) / (uRange.y - uRange.x);
        result = uFalseColor != 0
                ? falseColor(dot(normalized, vec3(0.2126, 0.7152, 0.0722)))
                : clamp(normalized, 0.0, 1.0);
    }

    if (uCheckerboard != 0 && uChannel == 0 && selected.a < 1.0 && uMode != 2) {
        float cell = mod(floor(gl_FragCoord.x / 8.0) + floor(gl_FragCoord.y / 8.0), 2.0);
        vec3 background = mix(vec3(0.25), vec3(0.5), cell);
        result = mix(background, result, clamp(selected.a, 0.0, 1.0));
    }
    outColor = vec4(result, 1.0);
}
