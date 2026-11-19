#version 460 core

/*
 * Velocity attachment for the deterministic hair-strand set (#46 v1).
 *
 * The shared scene surface writes the visible opaque surface's motion vector;
 * the forward hair ribbons are a transparent overlay that never enters that
 * pass. This stage writes `previousUv - currentUv` for the strand centreline so
 * a temporal resolve can reproject the previous lock image instead of treating
 * it as a static receiver.
 *
 * The encoding matches scene-surface.frag: UVs come from the stable
 * (unjittered) view-projections, `w > 0` gates a usable projection and the
 * |velocity| < 4 bound rejects a clipped/degenerate reprojection. A CUTOUT
 * strand applies the same alpha cutoff as the forward stage, so pixels the
 * forward pass discarded keep the receiver's motion vector. There is no
 * blending and no depth write; the pass only replaces the velocity attachment.
 */

layout(location = 0) out vec2 outVelocity;

in vec4 vCurrentClip;
in vec4 vPreviousClip;
in float vStrandParameter;

uniform int uTransparencyMode;
uniform float uAlpha;
uniform float uAlphaCutoff;

float strandAlpha() {
    // The root is thinner/softer than the tip; the profile is deterministic and
    // shared with the forward and shadow-caster fragment stages.
    float profile = mix(0.55, 1.0, smoothstep(0.0, 0.3, vStrandParameter));
    return clamp(uAlpha * profile, 0.0, 1.0);
}

void main() {
    if (uTransparencyMode == 0) {
        float alpha = strandAlpha();
        if (alpha <= 0.0 || alpha < uAlphaCutoff) {
            discard;
        }
    }
    if (!(vCurrentClip.w > 0.0) || !(vPreviousClip.w > 0.0)) {
        outVelocity = vec2(0.0);
        return;
    }
    vec2 currentUv = (vCurrentClip.xy / vCurrentClip.w) * 0.5 + 0.5;
    vec2 previousUv = (vPreviousClip.xy / vPreviousClip.w) * 0.5 + 0.5;
    vec2 velocity = previousUv - currentUv;
    bool finiteVelocity = all(lessThan(abs(velocity), vec2(4.0)));
    outVelocity = finiteVelocity ? velocity : vec2(0.0);
}
