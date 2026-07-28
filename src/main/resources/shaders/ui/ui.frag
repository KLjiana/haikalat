#version 460 core

layout(location = 0) out vec4 outColor;

in vec2 vUv;
in vec4 vColor;

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

void main() {
    if (uMode == 0) {
        outColor = vColor;
    } else if (uMode == 1) {
        vec4 sampled = texture(uTexture, vUv);
        outColor = vec4(sampled.rgb * sampled.a, sampled.a) * vColor;
    } else if (uMode == 2) {
        float coverage = texture(uTexture, vUv).r;
        outColor = vColor * coverage;
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
