#version 460 core

layout (location = 0) in vec2 vUv;
layout (location = 0) out vec4 fragColor;

uniform sampler2D uSceneColor;
uniform sampler2D uSceneDepth;
uniform sampler2D uShadowMap;
uniform mat4 uInverseViewProjection;
uniform mat4 uView;
uniform vec3 uCameraPosition;
uniform vec3 uSunDirection;
uniform vec3 uSunColor;
uniform float uSunIntensity;
uniform vec3 uEnvironmentColor;
uniform float uEnvironmentIntensity;
uniform vec3 uScatteringColor;
uniform int uSteps;
uniform float uMaximumDistance;
uniform float uDensity;
uniform float uAnisotropy;
uniform float uHistoryWeight;
uniform float uFramePhase;
uniform float uGlobalDistanceDensity;
uniform float uGlobalHeightDensity;
uniform float uGlobalHeightFalloff;
uniform float uGlobalBaseHeight;
uniform float uGlobalMaximumOpacity;
uniform vec3 uGlobalFogColor;
uniform int uCascadeCount;
uniform float uCascadeAtlasSize;
uniform mat4 uCascadeMatrices[4];
uniform float uCascadeSplits[4];
uniform int uLocalFogCount;
uniform int uNoiseSeed;
uniform float uWindTime;
uniform vec3 uFogCenters[8];
uniform vec3 uFogExtents[8];
uniform vec3 uFogColors[8];
uniform float uFogDensities[8];
uniform float uFogNoiseScales[8];
uniform float uFogNoiseAmounts[8];
uniform int uFogShapes[8];

float phaseHenyeyGreenstein(float cosineTheta, float g) {
    float g2 = g * g;
    float denominator = pow(max(1.0e-4, 1.0 + g2 - 2.0 * g * cosineTheta), 1.5);
    return (1.0 - g2) / (12.56637061436 * denominator);
}

vec3 reconstructWorld(vec2 uv, float depth) {
    vec4 clip = uInverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return clip.xyz / max(clip.w, 1.0e-6);
}

float hash13(vec3 p) {
    p += float(uNoiseSeed) * 0.001;
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

float localDensity(vec3 position, out vec3 localColor) {
    float density = 0.0;
    localColor = vec3(0.0);
    for (int i = 0; i < 8; ++i) {
        if (i >= uLocalFogCount) break;
        vec3 d = position - uFogCenters[i];
        bool inside = uFogShapes[i] == 0
                ? dot(d, d) <= uFogExtents[i].x * uFogExtents[i].x
                : all(lessThanEqual(abs(d), uFogExtents[i]));
        if (!inside) continue;
        float noise = 1.0;
        if (uFogNoiseScales[i] > 0.0 && uFogNoiseAmounts[i] > 0.0) {
            noise = mix(1.0, hash13(position * uFogNoiseScales[i]
                    + vec3(uWindTime, 0.0, uWindTime)), uFogNoiseAmounts[i]);
        }
        float contribution = uFogDensities[i] * noise;
        density += contribution;
        localColor += uFogColors[i] * contribution;
    }
    localColor /= max(density, 1.0e-5);
    return density;
}

float sunVisibility(vec3 position, float viewDepth) {
    if (uCascadeCount <= 0) return 1.0;
    int cascade = uCascadeCount - 1;
    for (int i = 0; i < 4; ++i) {
        if (i >= uCascadeCount) break;
        if (viewDepth <= uCascadeSplits[i]) { cascade = i; break; }
    }
    vec4 lightClip = uCascadeMatrices[cascade] * vec4(position, 1.0);
    if (abs(lightClip.w) < 1.0e-5) return 1.0;
    vec3 projected = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    // Cascade projections have finite coverage.  Do not switch from a
    // filtered shadow sample to fully lit at the exact border: that creates a
    // visible seam while the camera crosses a cascade/tile edge.  Fade the
    // shadow contribution over a small normalized border band instead.
    float borderDistance = min(min(projected.x, 1.0 - projected.x),
            min(projected.y, 1.0 - projected.y));
    float depthDistance = min(projected.z, 1.0 - projected.z);
    float coverage = smoothstep(0.0, 0.08, min(borderDistance, depthDistance));
    if (coverage <= 0.0) return 1.0;
    vec3 sampleProjected = clamp(projected, vec3(0.0), vec3(1.0));
    int columns = uCascadeCount > 1 ? 2 : 1;
    int rows = uCascadeCount > 2 ? 2 : 1;
    vec2 scale = vec2(1.0 / float(columns), 1.0 / float(rows));
    vec2 offset = vec2(float(cascade % columns), float(cascade / columns)) * scale;
    vec2 uv = sampleProjected.xy * scale + offset;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    float visibility = 0.0;
    for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) {
        vec2 sampleUv = clamp(uv + vec2(x, y) * texel,
                offset + texel * 0.5, offset + scale - texel * 0.5);
        visibility += sampleProjected.z - 0.0015 > texture(uShadowMap, sampleUv).r ? 0.0 : 1.0;
    }
    return mix(1.0, visibility / 9.0, coverage);
}

void main() {
    float depth = texture(uSceneDepth, vUv).r;
    vec3 farPoint = reconstructWorld(vUv, 1.0);
    vec3 ray = normalize(farPoint - uCameraPosition);
    float endDistance = uMaximumDistance;
    if (depth < 0.99999) {
        endDistance = min(endDistance, length(reconstructWorld(vUv, depth) - uCameraPosition));
    }
    if (endDistance <= 1.0e-4 || uDensity <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float phase = phaseHenyeyGreenstein(clamp(dot(uSunDirection, -ray), -1.0, 1.0), uAnisotropy);
    float stepLength = endDistance / float(max(uSteps, 1));
    float transmittance = 1.0;
    vec3 scattering = vec3(0.0);
    // A fixed spatial sequence avoids flicker when history is disabled.  The
    // phase uniform is reserved for the independent temporal owner.
    float jitter = fract(hash13(vec3(gl_FragCoord.xy, uFramePhase)));
    for (int i = 0; i < 128; ++i) {
        if (i >= uSteps) break;
        float distanceAlongRay = (float(i) + 0.5 + (jitter - 0.5) * 0.25) * stepLength;
        vec3 samplePosition = uCameraPosition + ray * distanceAlongRay;
        vec3 localColor;
        float localMedium = localDensity(samplePosition, localColor);
        float heightTerm = uGlobalHeightDensity * exp(-max(samplePosition.y - uGlobalBaseHeight, 0.0)
                * uGlobalHeightFalloff);
        float globalMedium = (uGlobalDistanceDensity + heightTerm)
                * min(1.0, uGlobalMaximumOpacity);
        float medium = uDensity + localMedium + globalMedium;
        vec3 viewPosition = (uView * vec4(samplePosition, 1.0)).xyz;
        float viewDepth = max(-viewPosition.z, 0.0);
        float visibility = sunVisibility(samplePosition, viewDepth);
        vec3 incident = uSunColor * uSunIntensity * visibility
                + uEnvironmentColor * uEnvironmentIntensity * 0.08;
        vec3 mediumColor = localMedium > 1.0e-5 ? localColor : uGlobalFogColor;
        incident *= mix(vec3(1.0), mediumColor, 0.35);
        float extinction = exp(-medium * stepLength);
        scattering += transmittance * (1.0 - extinction) * incident
                * phase * uScatteringColor;
        transmittance *= extinction;
    }
    fragColor = vec4(scattering, transmittance);
}
