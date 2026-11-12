#version 460 core

in vec2 vTexCoord;
in vec4 vVertexColor;
in vec3 vWorldNormal;
in vec3 vWorldPosition;
in vec4 vCurrentClip;
in vec4 vPreviousClip;
in float vPreviousViewDepth;
in float vPreviousValid;

layout(location = SCENE_SLOT_NORMAL) out vec4 outNormal;
layout(location = SCENE_SLOT_VELOCITY) out vec2 outVelocity;
layout(location = SCENE_SLOT_PREVIOUS_SURFACE_DEPTH) out float outPreviousDepth;
layout(location = SCENE_SLOT_VALIDITY) out float outValidity;
#ifdef SCENE_SLOT_ROUGHNESS
layout(location = SCENE_SLOT_ROUGHNESS) out float outRoughness;
uniform float uRoughness;
#endif
#ifdef SCENE_SLOT_ALBEDO
layout(location = SCENE_SLOT_ALBEDO) out vec4 outAlbedo;
uniform vec4 uAlbedoFactor;
#endif
#ifdef SCENE_SLOT_SUBSURFACE
layout(location = SCENE_SLOT_SUBSURFACE) out vec4 outSubsurface;
uniform int uSubsurfaceEnabled;
uniform float uSubsurfaceIntensity;
uniform int uSubsurfaceProfile;
uniform float uSubsurfaceProfileScale;
uniform int uSubsurfaceThicknessSource;
uniform float uSubsurfaceThickness;
uniform float uSubsurfaceDiffusionRadius;
uniform int uSubsurfaceScreenSpace;
uniform float uSubsurfaceMaxDistance;
uniform sampler2D uOcclusionMap;
#endif

// Fixed red-dominant penetration of the skin profile. The values match
// SubsurfaceScatteringSettings.SKIN_* and the PBR forward shader.
const vec3 SUBSURFACE_SKIN_PENETRATION = vec3(1.0, 0.55, 0.30);

uniform int uMasked;
uniform int uHasVertexColor;
uniform int uDerivativeNormal;
uniform float uAlphaCutoff;
uniform vec4 uBaseColorFactor;
uniform vec3 uCameraPosition;
uniform sampler2D uBaseColorMap;

vec2 octEncode(vec3 normal) {
    vec3 n = normalize(normal);
    vec2 oct = n.xy / (abs(n.x) + abs(n.y) + abs(n.z) + 1.0e-8);
    oct = n.z >= 0.0 ? oct : (1.0 - abs(oct.yx)) * sign(oct + vec2(1.0e-8));
    return oct;
}

vec3 surfaceNormal() {
    if (uDerivativeNormal == 0) {
        return normalize(vWorldNormal);
    }
    vec3 n = normalize(cross(dFdx(vWorldPosition), dFdy(vWorldPosition)));
    if (dot(n, vWorldPosition - uCameraPosition) > 0.0) {
        n = -n;
    }
    return n;
}

void main() {
    if (uMasked != 0) {
        vec4 base = texture(uBaseColorMap, vTexCoord) * uBaseColorFactor;
        if (uHasVertexColor != 0) base *= vVertexColor;
        if (base.a < uAlphaCutoff) {
            discard;
        }
    }
    vec2 currentUv = (vCurrentClip.xy / vCurrentClip.w) * 0.5 + 0.5;
    vec2 previousUv = (vPreviousClip.xy / vPreviousClip.w) * 0.5 + 0.5;
    vec2 velocity = previousUv - currentUv;
    bool finiteVelocity = all(lessThan(abs(velocity), vec2(4.0)));
    bool valid = vPreviousValid > 0.5 && finiteVelocity;

    outNormal = vec4(octEncode(surfaceNormal()), 0.0, 1.0);
    outVelocity = valid ? velocity : vec2(0.0);
    outPreviousDepth = valid ? vPreviousViewDepth : 0.0;
    outValidity = valid ? 1.0 : 0.0;
#ifdef SCENE_SLOT_ROUGHNESS
    outRoughness = clamp(uRoughness, 0.0, 1.0);
#endif
#ifdef SCENE_SLOT_ALBEDO
    // Linear base-colour factor only; a per-texel base-colour map is not
    // sampled by the surface pass in this version.
    outAlbedo = vec4(clamp(uAlbedoFactor.rgb, 0.0, 1.0), 1.0);
#endif
#ifdef SCENE_SLOT_SUBSURFACE
    outSubsurface = vec4(0.0);
    if (uSubsurfaceEnabled != 0) {
        // Same metre-space thickness formula as pbr-forward.frag: a constant
        // value, or a proxy derived from the existing occlusion/base-alpha
        // inputs. Every term is bounded by the settings contract. The
        // comparison-based checks make a NaN/Inf raw override fail closed.
        float thickness = uSubsurfaceThickness >= 0.0 && uSubsurfaceThickness <= 2.0
                ? uSubsurfaceThickness : 0.0;
        if (uSubsurfaceThicknessSource == 1) {
            float occlusion = clamp(texture(uOcclusionMap, vTexCoord).r, 0.0, 1.0);
            thickness = clamp((1.0 - occlusion) * thickness, 0.0, 2.0);
        } else if (uSubsurfaceThicknessSource == 2) {
            vec4 base = texture(uBaseColorMap, vTexCoord) * uBaseColorFactor;
            if (uHasVertexColor != 0) base *= vVertexColor;
            thickness = clamp((1.0 - clamp(base.a, 0.0, 1.0)) * thickness, 0.0, 2.0);
        }
        float profileScale = uSubsurfaceProfileScale >= 0.001
                && uSubsurfaceProfileScale <= 10.0 ? uSubsurfaceProfileScale : 0.001;
        vec3 penetration = uSubsurfaceProfile == 1
                ? SUBSURFACE_SKIN_PENETRATION : vec3(1.0);
        vec3 attenuation = exp(-thickness / max(profileScale * penetration,
                vec3(0.001)));
        // Thick regions absorb more, so their screen-space scatter halo is
        // narrower; the effective radius is the mean channel attenuation times
        // the authored radius and stays in [0, 2] metres.
        float authoredRadius = uSubsurfaceDiffusionRadius >= 0.0
                && uSubsurfaceDiffusionRadius <= 2.0 ? uSubsurfaceDiffusionRadius : 0.0;
        float effectiveRadius = clamp(authoredRadius
                * dot(attenuation, vec3(1.0 / 3.0)), 0.0, 2.0);
        float distanceFade = 1.0;
        if (uSubsurfaceMaxDistance > 0.0 && uSubsurfaceMaxDistance <= 100.0) {
            float bound = max(uSubsurfaceMaxDistance, 0.001);
            distanceFade = 1.0 - smoothstep(bound * 0.5, bound,
                    length(uCameraPosition - vWorldPosition));
        }
        float intensity = uSubsurfaceIntensity >= 0.0 && uSubsurfaceIntensity <= 1.0
                ? uSubsurfaceIntensity : 0.0;
        float coverage = uSubsurfaceScreenSpace != 0
                ? intensity * clamp(distanceFade, 0.0, 1.0) : 0.0;
        outSubsurface = vec4(coverage, effectiveRadius, thickness,
                uSubsurfaceScreenSpace != 0 ? 1.0 : 0.0);
    }
#endif
}
