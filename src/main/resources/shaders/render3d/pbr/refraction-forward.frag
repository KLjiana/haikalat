#version 460 core
// HAIKALAT_SINGLE_LAYER_REFRACTION
//
// Single-layer glass/shield transmission.  This shader samples a snapshot of
// the resolved scene colour taken before the glass pass; it must never sample
// the colour target it writes.  The authored IOR bends the view ray through the
// material normal, the world-space thickness projects that ray to a bounded
// screen offset, and roughness spreads the lookup over at most four fixed
// taps.  Off-screen or over-limit lookups fall back to the untouched pixel
// colour instead of clamping or smearing the border.

#define REFRACTION_MAX_ROUGH_TAPS 4
#define REFRACTION_MAX_SCREEN_OFFSET_UV 0.25
#define REFRACTION_MAX_ROUGHNESS_SPREAD_UV 0.02
#define REFRACTION_ATTENUATION_PER_WORLD_UNIT 1.5

const float REFRACTION_PI = 3.14159265358979323846;
const float REFRACTION_TAU = 6.28318530717958647692;

layout(std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};

in vec2 vTexCoord;
in vec3 vWorldPosition;
in vec3 vNormal;
in vec3 vTangent;
in float vTangentHandedness;
in vec4 vVertexColor;
layout(location = 0) out vec4 FragColor;

uniform sampler2D uBaseColorMap;
uniform sampler2D uNormalMap;
uniform sampler2D uRefractionSource;
uniform vec4 uBaseColorFactor;
uniform float uNormalScale;
uniform int uEnableNormalMap;
uniform int uHasVertexColor;
uniform int uDoubleSided;
uniform vec3 uCameraPosition;
uniform vec2 uRefractionExtent;
uniform float uRefractionIor;
uniform float uRefractionThickness;
uniform float uRefractionRoughness;
uniform float uRefractionTransmission;
uniform float uRefractionReflection;

float refractionHash(vec2 value) {
    vec3 p = fract(vec3(value.xyx) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

/** Returns (-1, -1) when the point is behind the camera. */
vec2 projectToUv(vec3 worldPosition) {
    vec4 clip = uProjection * (uView * vec4(worldPosition, 1.0));
    if (clip.w <= 1.0e-6) return vec2(-1.0);
    return clip.xy / clip.w * 0.5 + 0.5;
}

bool insideUnitSquare(vec2 value) {
    return all(greaterThanEqual(value, vec2(0.0)))
            && all(lessThanEqual(value, vec2(1.0)));
}

/** Out-of-range lookups copy the untouched pixel instead of clamping the edge. */
vec3 sampleRefracted(vec2 uv, vec2 centreUv) {
    return texture(uRefractionSource, insideUnitSquare(uv) ? uv : centreUv).rgb;
}

void main() {
    vec4 vertexColor = uHasVertexColor != 0 ? vVertexColor : vec4(1.0);
    vec3 baseColor = (texture(uBaseColorMap, vTexCoord) * uBaseColorFactor * vertexColor).rgb;
    vec3 n = normalize(vNormal);
    if (uDoubleSided != 0 && !gl_FrontFacing) n = -n;
    if (uEnableNormalMap != 0) {
        vec3 sampled = texture(uNormalMap, vTexCoord).xyz * 2.0 - 1.0;
        sampled.xy *= uNormalScale;
        vec3 t = normalize(vTangent);
        vec3 b = normalize(cross(n, t)) * vTangentHandedness;
        n = normalize(mat3(t, b, n) * sampled);
    }
    vec3 v = normalize(uCameraPosition - vWorldPosition);
    // Keep the bending direction stable for authored back faces or inverted
    // vertex normals; a surface always refracts rays travelling into it.
    if (dot(n, v) < 0.0) n = -n;
    float nDotV = clamp(dot(n, v), 0.0, 1.0);

    vec2 centreUv = gl_FragCoord.xy / uRefractionExtent;
    vec2 offset = vec2(0.0);
    vec3 incident = -v;
    vec3 refracted = refract(incident, n, 1.0 / max(uRefractionIor, 1.0));
    if (dot(refracted, refracted) <= 1.0e-12) {
        // Total internal reflection has no transmitted screen position; the
        // pixel keeps its untouched background and only Fresnel reflection may
        // still contribute.
        refracted = incident;
    }
    vec3 refractedWorld = vWorldPosition + refracted * uRefractionThickness;
    vec2 refractedUv = projectToUv(refractedWorld);
    if (insideUnitSquare(refractedUv)) {
        offset = refractedUv - centreUv;
        if (length(offset) > REFRACTION_MAX_SCREEN_OFFSET_UV) offset = vec2(0.0);
    }

    vec3 refractedColor = vec3(0.0);
    if (uRefractionRoughness <= 1.0e-4) {
        refractedColor = sampleRefracted(centreUv + offset, centreUv);
    } else {
        // Fixed four-tap spread.  The per-pixel rotation is deterministic so a
        // static scene reproduces the same rough transmission frame to frame.
        float rotation = refractionHash(gl_FragCoord.xy) * REFRACTION_TAU;
        float radius = uRefractionRoughness * REFRACTION_MAX_ROUGHNESS_SPREAD_UV;
        for (int tap = 0; tap < REFRACTION_MAX_ROUGH_TAPS; tap++) {
            float angle = rotation + float(tap) * REFRACTION_TAU
                    / float(REFRACTION_MAX_ROUGH_TAPS);
            refractedColor += sampleRefracted(
                    centreUv + offset + vec2(cos(angle), sin(angle)) * radius, centreUv);
        }
        refractedColor /= float(REFRACTION_MAX_ROUGH_TAPS);
    }
    vec3 tint = exp(-(vec3(1.0) - clamp(baseColor, 0.0, 1.0))
            * (uRefractionThickness * REFRACTION_ATTENUATION_PER_WORLD_UNIT));
    vec3 background = texture(uRefractionSource, centreUv).rgb;
    vec3 transmitted = mix(background, refractedColor * tint,
            clamp(uRefractionTransmission, 0.0, 1.0));

    float f0scalar = (uRefractionIor - 1.0) / (uRefractionIor + 1.0);
    float f0 = f0scalar * f0scalar;
    float fresnel = f0 + (1.0 - f0) * pow(1.0 - nDotV, 5.0);
    vec3 reflection = baseColor * (fresnel * clamp(uRefractionReflection, 0.0, 1.0));

    // Replacement coverage: the refracted background already contains the
    // scene behind the glass, so alpha stays one and the pass writes the
    // complete single-layer result without double-blending the background.
    FragColor = vec4(max(transmitted + reflection, vec3(0.0)), 1.0);
}
