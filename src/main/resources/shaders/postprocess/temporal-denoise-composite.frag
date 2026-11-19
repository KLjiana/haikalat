#version 460 core

// Reflection-signal composite (S7 / #34).
//
// The temporal denoiser resolves the bounded SSR reflection contribution in
// RGB and carries its usable hit confidence in alpha. The spatial SSR pass
// composites with `base + contribution`; this pass applies exactly the same
// equation after denoising instead of denoising the already composited scene
// colour.
//
// Both inputs are point-sampled, so a pixel whose reflection contribution is
// exactly zero reproduces the base colour byte-for-byte (the base-colour
// regions the denoiser must never touch). A non-finite contribution fails
// closed to zero and therefore also leaves the base untouched. The output
// alpha returns to the opaque pre-signal contract (`1.0`), matching the
// legacy SSR output that downstream effects already expect.
//
// No history, no readback, one full-screen draw.

in vec2 vUv;
layout(location = 0) out vec4 FragColor;

uniform sampler2D uBaseColor;
uniform sampler2D uContribution;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 base = texelFetch(uBaseColor, pixel, 0).rgb;
    vec3 contribution = texelFetch(uContribution, pixel, 0).rgb;
    if (any(isnan(contribution)) || any(isinf(contribution))) {
        contribution = vec3(0.0);
    }
    FragColor = vec4(base + contribution, 1.0);
}
