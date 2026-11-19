#version 460 core
// Depth-aware composite of the reconstructed cloud layer over the existing HDR
// scene colour (which already contains the #29 atmosphere/sky and the
// volumetric-fog composite). The cloud result is premultiplied single scatter
// S with transmittance T, so the composite is exactly S + T * scene.
//
// The pass copies the scene colour into its own target before this draw and
// never writes the caller's attachment; texelFetch keeps the mapping exact and
// the source is not sampled while it is bound as a target.
uniform sampler2D uCloud;
uniform sampler2D uColorSource;

layout (location = 0) out vec4 outColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 cloud = texelFetch(uCloud, pixel, 0);
    vec4 source = texelFetch(uColorSource, pixel, 0);
    outColor = vec4(max(cloud.rgb, vec3(0.0)) + clamp(cloud.a, 0.0, 1.0) * source.rgb,
            source.a);
}
