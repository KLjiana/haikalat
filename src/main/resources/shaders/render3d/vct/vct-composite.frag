#version 460 core

// Per-pixel diffuse + specular indirect light from the voxel cone trace.
//
// The caller adds this output to an HDR colour target with additive blending.
// Scene depth is sampled at pixel centres, the world position is reconstructed
// through the camera inverse matrices and the geometric normal comes from the
// world-position screen derivatives. No normal or roughness G-buffer is assumed;
// the surface roughness and albedo are explicit uniforms of this first bounded
// integration. Pixels at the far plane contribute nothing.
//
// Cone math is intentionally identical to vct-trace.comp (the shader contract
// test compares the documented constants): lod = log2(2r / voxelSize),
// transmittance *= 1 - coverage, 1% cut.

in vec2 vUv;

layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uSceneDepth;
layout(binding = 1) uniform sampler3D uVoxels;

uniform vec3 uCameraPosition;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform vec3 uBoundsMinimum;
uniform float uHalfExtent;
uniform int uResolution;
uniform int uMaxMip;
uniform int uDiffuseCones;
uniform float uDiffuseAperture;
uniform float uSpecularApertureScale;
uniform float uMaxDistance;
uniform int uMaxSteps;
uniform float uOpacityThreshold;
uniform float uNormalBiasVoxels;
uniform float uSurfaceRoughness;
uniform vec3 uSurfaceAlbedo;
uniform float uDiffuseIntensity;
uniform float uSpecularIntensity;

const float PI = 3.14159265359;

vec3 traceCone(vec3 origin, vec3 direction, float aperture, out float transmittance) {
    float voxelSize = (2.0 * uHalfExtent) / float(uResolution);
    float travel = voxelSize * (0.75 + uNormalBiasVoxels);
    transmittance = 1.0;
    vec3 radiance = vec3(0.0);
    for (int step = 0; step < uMaxSteps; ++step) {
        if (travel >= uMaxDistance || transmittance <= 0.01) break;
        float radius = travel * tan(aperture);
        float lod = clamp(log2(max(2.0 * radius / voxelSize, 1.0)), 0.0, float(uMaxMip));
        vec3 position = origin + direction * travel;
        vec3 uvw = (position - uBoundsMinimum) / (2.0 * uHalfExtent);
        if (any(lessThan(uvw, vec3(0.0))) || any(greaterThan(uvw, vec3(1.0)))) break;
        vec4 value = textureLod(uVoxels, uvw, lod);
        float coverage = clamp((value.a - uOpacityThreshold)
                / max(1.0 - uOpacityThreshold, 1e-3), 0.0, 1.0);
        if (coverage > 0.0) {
            radiance += transmittance * coverage * value.rgb;
            transmittance *= 1.0 - coverage;
        }
        travel += max(voxelSize, radius * 0.75);
    }
    return radiance;
}

void main() {
    float depth = texture(uSceneDepth, vUv).r;
    if (depth >= 0.999999) {
        fragColor = vec4(0.0);
        return;
    }
    vec4 ndc = vec4(vUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 viewPosition = uInverseProjection * ndc;
    viewPosition /= viewPosition.w;
    vec3 world = (uInverseView * vec4(viewPosition.xyz, 1.0)).xyz;

    vec3 normal = normalize(cross(dFdx(world), dFdy(world)));
    if (!all(lessThan(abs(normal), vec3(1.0e6)))) {
        fragColor = vec4(0.0);
        return;
    }
    if (dot(normal, normalize(uCameraPosition - world)) < 0.0) normal = -normal;
    vec3 view = normalize(uCameraPosition - world);

    vec3 tangent = normalize(abs(normal.y) < 0.99
            ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
    vec3 bitangent = cross(normal, tangent);
    vec3 origin = world + normal * (uNormalBiasVoxels
            * (2.0 * uHalfExtent) / float(uResolution));

    vec3 diffuse = vec3(0.0);
    for (int cone = 0; cone < uDiffuseCones; ++cone) {
        float fraction = (float(cone) + 0.5) / float(uDiffuseCones);
        float cosTheta = sqrt(fraction);
        float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
        float phi = 2.0 * PI * fraction;
        vec3 direction = normalize(tangent * cos(phi) * sinTheta
                + bitangent * sin(phi) * sinTheta + normal * cosTheta);
        float transmittance;
        diffuse += traceCone(origin, direction, uDiffuseAperture, transmittance);
    }
    diffuse /= float(uDiffuseCones);

    vec3 reflection = reflect(-view, normal);
    float aperture = clamp(uSurfaceRoughness * uSurfaceRoughness * uSpecularApertureScale,
            0.02, 1.2);
    float transmittance;
    vec3 specular = traceCone(origin, reflection, aperture, transmittance);

    vec3 indirect = diffuse * uSurfaceAlbedo * uDiffuseIntensity
            + specular * uSpecularIntensity;
    if (any(isnan(indirect)) || any(isinf(indirect))) indirect = vec3(0.0);
    fragColor = vec4(indirect, 0.0);
}
