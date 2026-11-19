#version 460 core

// Real production consumer of the DDGI probe query: a bounded screen-space
// indirect diffuse pass. It reconstructs the visible world position from the
// shared scene depth, decodes the shared octahedral world normal and adds the
// probe irradiance for that normal. The probe grid border fade and the
// per-probe visibility gate come from ddgi-common.glsl, so receivers outside
// the probe bounds or hidden behind a wall recorded by the probe depth atlas
// receive exactly zero indirect contribution.
//
// The pass owns its full-size RGBA16F target, copies the caller's scene colour
// first and then adds its contribution with GL_ONE/GL_ONE. It never samples a
// target it is writing and never writes the shared depth.
//
// Limitations: single-sample scene depth and normals only (multisampled depth
// must be resolved first), one scene-wide albedo proxy instead of per-pixel
// material albedo, no specular or AO de-duplication, and the visible-surface
// restriction of any screen-space consumer.

uniform sampler2D uSceneDepth;
uniform sampler2D uSceneNormal;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform vec2 uViewportSize;
uniform float uIntensity;
uniform float uSurfaceAlbedo;

out vec4 fragColor;

// Decodes the shared SceneBufferChannel.NORMAL octahedral world normal.
vec3 ddgiDecodeNormal(vec2 octahedral) {
    // The shared scene surface stores signed octahedral coordinates.
    vec3 normal = vec3(octahedral, 1.0 - abs(octahedral.x) - abs(octahedral.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx))
                * vec2(normal.x >= 0.0 ? 1.0 : -1.0, normal.y >= 0.0 ? 1.0 : -1.0);
    }
    float lengthSquared = dot(normal, normal);
    return lengthSquared > 1.0e-12 ? normal / sqrt(lengthSquared) : vec3(0.0);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    float depth = texture(uSceneDepth, uv).r;
    if (!(depth < 0.999999)) {
        fragColor = vec4(0.0);
        return;
    }
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 viewPosition = uInverseProjection * clip;
    viewPosition /= viewPosition.w;
    vec4 worldPosition = uInverseView * viewPosition;
    worldPosition /= worldPosition.w;

    vec3 normal = ddgiDecodeNormal(texture(uSceneNormal, uv).rg);
    if (!(dot(normal, normal) > 0.5)) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 radiance = ddgiSampleIrradiance(worldPosition.xyz, normal, true);
    vec3 contribution = radiance * (uSurfaceAlbedo * uIntensity);
    if (any(isnan(contribution)) || any(isinf(contribution))) {
        contribution = vec3(0.0);
    }
    fragColor = vec4(contribution, 0.0);
}
