#version 460 core

/*
 * Alpha-tested depth-only fragment stage for the hair-strand shadow caster
 * (#46). It uses the same root-to-tip alpha profile as the forward stage and
 * discards sub-cutoff fragments, so the cast shadow follows the authored strand
 * coverage rather than a full rectangular ribbon.
 */

in float vStrandParameter;

uniform float uAlpha;
uniform float uAlphaCutoff;

void main() {
    float alpha = clamp(uAlpha * mix(0.55, 1.0, smoothstep(0.0, 0.3, vStrandParameter)),
            0.0, 1.0);
    if (alpha < uAlphaCutoff) {
        discard;
    }
}
