#version 460 core

layout(location = 0) out vec4 outColor;

in vec2 vUv;
in vec4 vColor;
in vec2 vLocal;

uniform sampler2D uTexture;
uniform int uMode;
uniform int uSdfShape;
uniform vec2 uSdfSize;
uniform vec4 uSdfRadii;
uniform vec4 uSdfParams;
uniform float uSdfGradientAngle;
uniform vec4 uSdfFillColor;
uniform vec4 uSdfBorderColor;
uniform vec4 uSdfGradientStart;
uniform vec4 uSdfGradientEnd;

// Text effect uniforms
uniform int uTextEffectType;        // 0=none, 1=outline, 2=shadow, 3=glow, 4=inner_glow, 5=gradient
uniform vec4 uTextEffectColor1;     // Primary effect color
uniform vec4 uTextEffectColor2;     // Secondary color (for gradient)
uniform float uTextEffectThickness; // Outline thickness or glow radius
uniform vec2 uTextEffectOffset;     // Shadow offset
uniform float uTextEffectBlur;      // Shadow blur radius
uniform float uTextEffectAngle;     // Gradient angle in radians

float roundedBoxDistance(vec2 p, vec2 halfSize, vec4 radii) {
    float radius = p.x < 0.0
            ? (p.y < 0.0 ? radii.x : radii.w)
            : (p.y < 0.0 ? radii.y : radii.z);
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

float sdfDistance(vec2 local) {
    vec2 p = (local - vec2(0.5)) * uSdfSize;
    vec2 halfSize = uSdfSize * 0.5;
    if (uSdfShape == 2 || uSdfShape == 3) {
        vec2 normalized = p / max(halfSize, vec2(0.001));
        return (length(normalized) - 1.0) * min(halfSize.x, halfSize.y);
    }
    if (uSdfShape == 4) {
        float radius = max(0.0, uSdfParams.x + uSdfParams.w * 0.5);
        return abs(length(p) - radius) - uSdfParams.w * 0.5;
    }
    if (uSdfShape == 5) {
        float radius = max(0.0, min(halfSize.x, halfSize.y) - uSdfParams.w * 0.5);
        float angle = atan(-p.y, p.x);
        float start = uSdfParams.y;
        float end = uSdfParams.z;
        float span = max(0.0001, end - start);
        float wrapped = angle;
        while (wrapped < start) wrapped += 6.28318530718;
        while (wrapped > start + 6.28318530718) wrapped -= 6.28318530718;
        float angular = min(abs(wrapped - start), abs(wrapped - (start + span)))
                * radius;
        float radial = abs(length(p) - radius) - uSdfParams.w * 0.5;
        return max(radial, angular - uSdfParams.w * 0.5);
    }
    return roundedBoxDistance(p, halfSize,
            uSdfShape == 1 ? vec4(min(halfSize.x, halfSize.y)) : uSdfRadii);
}

// ============ Text Effect Functions ============

const vec2 SAMPLE_DIRECTIONS[8] = vec2[](
    vec2(1.0, 0.0), vec2(0.70710678, 0.70710678),
    vec2(0.0, 1.0), vec2(-0.70710678, 0.70710678),
    vec2(-1.0, 0.0), vec2(-0.70710678, -0.70710678),
    vec2(0.0, -1.0), vec2(0.70710678, -0.70710678)
);

vec2 coveragePixelSize(sampler2D atlas, vec2 uv) {
    vec2 texel = 1.0 / vec2(textureSize(atlas, 0));
    return max(fwidth(uv), texel);
}

float sampleMaximum(sampler2D atlas, vec2 uv, float radius) {
    float coverage = texture(atlas, uv).r;
    vec2 offset = coveragePixelSize(atlas, uv) * max(radius, 0.0);
    for (int index = 0; index < 8; index++) {
        coverage = max(coverage,
                texture(atlas, uv + SAMPLE_DIRECTIONS[index] * offset).r);
    }
    return coverage;
}

float sampleMinimum(sampler2D atlas, vec2 uv, float radius) {
    float coverage = texture(atlas, uv).r;
    vec2 offset = coveragePixelSize(atlas, uv) * max(radius, 0.0);
    for (int index = 0; index < 8; index++) {
        coverage = min(coverage,
                texture(atlas, uv + SAMPLE_DIRECTIONS[index] * offset).r);
    }
    return coverage;
}

float sampleBlurred(sampler2D atlas, vec2 uv, float radius) {
    if (radius <= 0.01) return texture(atlas, uv).r;
    vec2 offset = coveragePixelSize(atlas, uv) * radius;
    float sum = texture(atlas, uv).r * 4.0;
    for (int index = 0; index < 8; index++) {
        sum += texture(atlas, uv + SAMPLE_DIRECTIONS[index] * offset * 0.5).r;
    }
    for (int index = 0; index < 8; index += 2) {
        sum += texture(atlas, uv + SAMPLE_DIRECTIONS[index] * offset).r;
    }
    return sum / 16.0;
}

vec4 premultipliedOver(vec4 foreground, vec4 background) {
    return foreground + background * (1.0 - foreground.a);
}

vec4 applyGradient(vec2 local, vec4 color1, vec4 color2, float angle) {
    vec2 dir = vec2(cos(angle), sin(angle));
    float extent = max(abs(dir.x) + abs(dir.y), 0.001);
    float t = clamp(dot(local - vec2(0.5), dir) / extent + 0.5, 0.0, 1.0);
    return mix(color1, color2, t);
}

vec4 renderTextWithEffect(sampler2D atlas, vec2 uv, vec2 local, vec4 baseColor) {
    float baseCoverage = texture(atlas, uv).r;
    vec4 text = baseColor * baseCoverage;

    if (uTextEffectType == 0) {
        return text;
    }

    if (uTextEffectType == 1) {
        float expandedCoverage = sampleMaximum(atlas, uv, uTextEffectThickness);
        float outlineCoverage = clamp(expandedCoverage - baseCoverage, 0.0, 1.0);
        vec4 outline = uTextEffectColor1 * outlineCoverage;
        return premultipliedOver(text, outline);
    }

    if (uTextEffectType == 2) {
        vec2 shadowUv = uv - uTextEffectOffset * coveragePixelSize(atlas, uv);
        float shadowCoverage = sampleBlurred(atlas, shadowUv, uTextEffectBlur);
        vec4 shadow = uTextEffectColor1 * shadowCoverage;
        return premultipliedOver(text, shadow);
    }

    if (uTextEffectType == 3) {
        float blurredCoverage = sampleBlurred(atlas, uv, uTextEffectThickness);
        float glowCoverage = clamp(blurredCoverage * 1.6, 0.0, 1.0)
                * (1.0 - baseCoverage);
        vec4 glow = uTextEffectColor1 * glowCoverage;
        return premultipliedOver(text, glow);
    }

    if (uTextEffectType == 4) {
        // Use the local coverage range so both anti-aliased and binary glyphs
        // retain an inner edge after switching font faces.
        float nearbyCoverage = sampleMaximum(atlas, uv, 0.90);
        float interiorCoverage = sampleMinimum(atlas, uv, 0.90);
        float edgeRange = clamp(nearbyCoverage - interiorCoverage, 0.0, 1.0);
        float edgeFactor = smoothstep(0.05, 0.65, edgeRange)
                * smoothstep(0.08, 0.92, baseCoverage) * 0.22;
        float effectAlpha = uTextEffectColor1.a;
        vec3 baseRgb = baseColor.a > 0.001
                ? baseColor.rgb / baseColor.a : vec3(0.0);
        vec3 effectRgb = effectAlpha > 0.001
                ? uTextEffectColor1.rgb / effectAlpha : baseRgb;
        vec3 glowRgb = mix(baseRgb, effectRgb, edgeFactor * effectAlpha);
        return vec4(glowRgb * baseColor.a, baseColor.a) * baseCoverage;
    }

    if (uTextEffectType == 5) {
        vec4 gradient = applyGradient(local, uTextEffectColor1,
            uTextEffectColor2, uTextEffectAngle);
        return gradient * baseCoverage;
    }

    return text;
}

void main() {
    if (uMode == 0) {
        outColor = vColor;
    } else if (uMode == 1) {
        vec4 sampled = texture(uTexture, vUv);
        outColor = vec4(sampled.rgb * sampled.a, sampled.a) * vColor;
    } else if (uMode == 2) {
        outColor = renderTextWithEffect(uTexture, vUv, vLocal, vColor);
    } else if (uMode == 4) {
        float sdfDistanceValue = sdfDistance(vUv);
        float aa = max(fwidth(sdfDistanceValue), 0.85);
        vec2 sampleX = dFdx(vUv) * 0.25;
        vec2 sampleY = dFdy(vUv) * 0.25;
        float d0 = sdfDistance(vUv - sampleX - sampleY);
        float d1 = sdfDistance(vUv + sampleX - sampleY);
        float d2 = sdfDistance(vUv - sampleX + sampleY);
        float d3 = sdfDistance(vUv + sampleX + sampleY);
        float outerCoverage = (
                1.0 - smoothstep(-aa, aa, d0)
                + 1.0 - smoothstep(-aa, aa, d1)
                + 1.0 - smoothstep(-aa, aa, d2)
                + 1.0 - smoothstep(-aa, aa, d3)) * 0.25;
        float borderWidth = (uSdfShape == 4 || uSdfShape == 5)
                ? 0.0 : max(0.0, uSdfParams.w);
        float innerCoverage = (
                1.0 - smoothstep(-aa, aa, d0 + borderWidth)
                + 1.0 - smoothstep(-aa, aa, d1 + borderWidth)
                + 1.0 - smoothstep(-aa, aa, d2 + borderWidth)
                + 1.0 - smoothstep(-aa, aa, d3 + borderWidth)) * 0.25;
        float borderCoverage = clamp(outerCoverage - innerCoverage, 0.0, 1.0);
        vec2 direction = vec2(cos(uSdfGradientAngle), sin(uSdfGradientAngle));
        float gradientT = clamp(dot(vUv - vec2(0.5), direction) + 0.5, 0.0, 1.0);
        vec4 fill = mix(uSdfGradientStart, uSdfGradientEnd, gradientT);
        fill = mix(fill, uSdfFillColor,
                step(0.999, distance(uSdfGradientStart, uSdfGradientEnd)));
        outColor = fill * (outerCoverage - borderCoverage)
                + uSdfBorderColor * borderCoverage;
    } else {
        vec2 edgeDistance = min(vUv, vec2(1.0) - vUv);
        vec2 pixelWidth = fwidth(vUv) * 1.5;
        if (edgeDistance.x > pixelWidth.x && edgeDistance.y > pixelWidth.y) discard;
        outColor = vColor;
    }
}
