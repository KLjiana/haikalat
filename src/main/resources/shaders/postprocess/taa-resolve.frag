#version 460 core

out vec4 outColor;
out float outLinearDepth;
in vec2 vUv;

uniform sampler2D uCurrent;
uniform sampler2D uHistoryColor;
uniform sampler2D uHistoryDepth;
uniform sampler2D uSceneDepth;
uniform sampler2D uVelocity;
uniform sampler2D uPreviousDepth;
uniform sampler2D uValidity;
uniform sampler2D uReactive;
uniform vec2 uExtent;
uniform vec2 uJitter;
uniform vec2 uPreviousJitter;
uniform mat4 uInverseProjection;
uniform mat4 uPreviousStableViewProjection;
uniform mat4 uInverseView;
uniform float uHistoryWeight;
uniform float uDepthAbsoluteTolerance;
uniform float uDepthRelativeTolerance;
uniform float uReactiveStrength;
uniform int uHistoryValid;
uniform int uVarianceClipping;
uniform int uNeighborhoodRadius;

float linearize(vec2 uv) {
    float deviceDepth = texture(uSceneDepth, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, deviceDepth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    return -view.z / view.w;
}

float toleranceFor(float expectedDepth) {
    return max(uDepthAbsoluteTolerance, uDepthRelativeTolerance * abs(expectedDepth));
}

bool finite2(vec2 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec2(1.0e12)));
}

vec2 skyHistoryUv(vec2 rasterUv) {
    vec4 clip = vec4(rasterUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 viewDir = normalize((uInverseProjection * clip).xyz);
    vec3 worldDir = normalize(mat3(uInverseView) * viewDir);
    vec4 previousClip = uPreviousStableViewProjection * vec4(worldDir, 0.0);
    if (!(previousClip.w > 1.0e-6)) return vec2(-1.0);
    return previousClip.xy / previousClip.w * 0.5 + 0.5;
}

void main() {
    vec2 rasterUv = vUv;
    vec4 current = texture(uCurrent, rasterUv);
    float deviceDepth = texture(uSceneDepth, rasterUv).r;

    // Sky/background: no surface correspondence.  Reproject by camera rotation
    // only and accept history only from another background sample (the history
    // depth stores a negative marker for background).
    if (deviceDepth >= 0.999999) {
        if (uHistoryValid == 0) {
            outColor = current;
            outLinearDepth = -1.0;
            return;
        }
        vec2 previousUv = skyHistoryUv(rasterUv);
        // inverseJitteredProjection already removed the current raster jitter.
        vec2 historyUv = previousUv + uPreviousJitter;
        if (previousUv.x < 0.0 || !finite2(historyUv)
                || any(lessThan(historyUv, vec2(0.0)))
                || any(greaterThan(historyUv, vec2(1.0)))) {
            outColor = current;
            outLinearDepth = -1.0;
            return;
        }
        // Validate every bilinear tap. A nearest depth check alone can accept
        // background while filtered color still includes a foreground edge.
        vec2 skyBase = historyUv * uExtent - 0.5;
        ivec2 skyOrigin = ivec2(floor(skyBase));
        vec2 skyFraction = fract(skyBase);
        vec3 skyAccum = vec3(0.0);
        float skyCoverage = 0.0;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                ivec2 pixel = skyOrigin + ivec2(x, y);
                if (any(lessThan(pixel, ivec2(0)))
                        || any(greaterThanEqual(pixel, ivec2(uExtent)))) continue;
                if (!(texelFetch(uHistoryDepth, pixel, 0).r < 0.0)) continue;
                float tapWeight = (x == 0 ? 1.0 - skyFraction.x : skyFraction.x)
                    * (y == 0 ? 1.0 - skyFraction.y : skyFraction.y);
                skyAccum += texelFetch(uHistoryColor, pixel, 0).rgb * tapWeight;
                skyCoverage += tapWeight;
            }
        }
        vec3 skyHistory = skyCoverage > 0.0 ? skyAccum / skyCoverage : current.rgb;
        // Background also needs neighborhood rejection: depth alone cannot
        // reject a stale color already accumulated into a background texel.
        vec3 skyLo = current.rgb;
        vec3 skyHi = current.rgb;
        ivec2 currentPixel = ivec2(rasterUv * uExtent);
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                ivec2 pixel = clamp(currentPixel + ivec2(x, y), ivec2(0), ivec2(uExtent) - 1);
                if (texelFetch(uSceneDepth, pixel, 0).r < 0.999999) continue;
                vec3 neighbor = texelFetch(uCurrent, pixel, 0).rgb;
                skyLo = min(skyLo, neighbor);
                skyHi = max(skyHi, neighbor);
            }
        }
        skyHistory = clamp(skyHistory, skyLo, skyHi);
        float skyReactive = texture(uReactive, rasterUv).r;
        float skyWeight = uHistoryWeight * (1.0 - skyReactive * uReactiveStrength);
        outColor = vec4(mix(current.rgb, skyHistory, clamp(skyWeight, 0.0, 1.0)), current.a);
        outLinearDepth = -1.0;
        return;
    }

    float currentDepth = linearize(rasterUv);

    float validity = texture(uValidity, rasterUv).r;
    if (uHistoryValid == 0 || validity < 0.5) {
        outColor = current;
        outLinearDepth = currentDepth;
        return;
    }

    vec2 velocity = texture(uVelocity, rasterUv).rg;
    vec2 historyUv = rasterUv + velocity + uPreviousJitter - uJitter;
    if (!finite2(historyUv)
            || any(lessThan(historyUv, vec2(0.0)))
            || any(greaterThan(historyUv, vec2(1.0)))) {
        outColor = current;
        outLinearDepth = currentDepth;
        return;
    }

    float expectedDepth = texture(uPreviousDepth, rasterUv).r;
    float tolerance = toleranceFor(expectedDepth);
    vec2 texel = 1.0 / uExtent;
    vec2 base = historyUv * uExtent - 0.5;
    vec2 baseFloor = floor(base);
    ivec2 baseInt = ivec2(baseFloor);
    vec2 fraction = base - baseFloor;

    vec3 historyAccum = vec3(0.0);
    float historyWeight = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            ivec2 pixel = baseInt + ivec2(x, y);
            if (pixel.x < 0 || pixel.y < 0
                    || pixel.x >= int(uExtent.x) || pixel.y >= int(uExtent.y)) {
                continue;
            }
            vec2 sampleUv = (vec2(pixel) + 0.5) * texel;
            float historyDepth = texture(uHistoryDepth, sampleUv).r;
            if (!(historyDepth > 0.0) || abs(historyDepth - expectedDepth) > tolerance) {
                continue;
            }
            float weight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                * (y == 0 ? 1.0 - fraction.y : fraction.y);
            historyAccum += texture(uHistoryColor, sampleUv).rgb * weight;
            historyWeight += weight;
        }
    }
    if (historyWeight <= 0.0) {
        outColor = current;
        outLinearDepth = currentDepth;
        return;
    }
    vec3 history = historyAccum / historyWeight;

    vec3 lo = current.rgb;
    vec3 hi = current.rgb;
    vec3 sum = current.rgb;
    vec3 sumSquares = current.rgb * current.rgb;
    float count = 1.0;
    float neighborhoodDepthTolerance = max(tolerance, toleranceFor(currentDepth) * 8.0);
    int radius = uNeighborhoodRadius;
    for (int y = -radius; y <= radius; y++) {
        for (int x = -radius; x <= radius; x++) {
            if (x == 0 && y == 0) {
                continue;
            }
            vec2 neighborUv = rasterUv + vec2(x, y) * texel;
            if (any(lessThan(neighborUv, vec2(0.0)))
                    || any(greaterThan(neighborUv, vec2(1.0)))) {
                continue;
            }
            float neighborDepth = linearize(neighborUv);
            if (abs(neighborDepth - currentDepth) > neighborhoodDepthTolerance) {
                continue;
            }
            vec3 neighbor = texture(uCurrent, neighborUv).rgb;
            lo = min(lo, neighbor);
            hi = max(hi, neighbor);
            sum += neighbor;
            sumSquares += neighbor * neighbor;
            count += 1.0;
        }
    }
    if (uVarianceClipping != 0 && count > 1.0) {
        vec3 mean = sum / count;
        vec3 variance = max(sumSquares / count - mean * mean, vec3(0.0));
        vec3 sigma = sqrt(variance) * 1.5;
        lo = max(lo, mean - sigma);
        hi = min(hi, mean + sigma);
    }
    history = clamp(history, min(lo, hi), max(lo, hi));

    float reactive = texture(uReactive, rasterUv).r;
    float weight = uHistoryWeight * (1.0 - reactive * uReactiveStrength);
    vec3 resolved = mix(current.rgb, history, clamp(weight, 0.0, 1.0));
    outColor = vec4(resolved, current.a);
    outLinearDepth = currentDepth;
}
