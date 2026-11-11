#version 330 core
/*
 * SMAA 1x neighborhood blending (third pass).
 *
 * GLSL 330 port of SMAANeighborhoodBlendingPS from SMAA.hlsl, commit
 * 71c806a838bdd7d517df19192a20f0c61b3ca29d.  Copyright (C) 2013 Jorge Jimenez,
 * Jose I. Echevarria, Belen Masia, Fernando Navarro, Diego Gutierrez.
 * MIT License; full text and provenance in docs/licenses/smaa.md.
 *
 * The input and output are display-referred gamma-encoded RGBA8.  This pass
 * writes the final backbuffer image; host UI compositing happens after it.
 */
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uColor;
uniform sampler2D uBlend;
uniform vec2 uInvResolution;

vec4 sampleBlend(vec2 texcoord) {
    return textureLod(uBlend, texcoord, 0.0);
}

vec4 sampleColor(vec2 texcoord) {
    return textureLod(uColor, texcoord, 0.0);
}

void selectVector(bool condition, inout vec4 variable, vec4 value) {
    if (condition) {
        variable = value;
    }
}

void selectVector(bool condition, inout vec2 variable, vec2 value) {
    if (condition) {
        variable = value;
    }
}

void main() {
    vec2 right = vUv + vec2(uInvResolution.x, 0.0);
    vec2 top = vUv + vec2(0.0, uInvResolution.y);

    vec4 a;
    a.x = sampleBlend(right).a;
    a.y = sampleBlend(top).g;
    a.wz = sampleBlend(vUv).xz;

    if (dot(a, vec4(1.0)) < 1e-5) {
        FragColor = vec4(sampleColor(vUv).rgb, 1.0);
        return;
    }

    bool horizontal = max(a.x, a.z) > max(a.y, a.w);

    vec4 blendingOffset = vec4(0.0, a.y, 0.0, a.w);
    vec2 blendingWeight = a.yw;
    selectVector(horizontal, blendingOffset, vec4(a.x, 0.0, a.z, 0.0));
    selectVector(horizontal, blendingWeight, a.xz);
    blendingWeight /= dot(blendingWeight, vec2(1.0));

    vec4 blendingCoord = blendingOffset
            * vec4(uInvResolution.xy, -uInvResolution.xy) + vUv.xyxy;

    vec3 color = blendingWeight.x * sampleColor(blendingCoord.xy).rgb;
    color += blendingWeight.y * sampleColor(blendingCoord.zw).rgb;

    FragColor = vec4(color, 1.0);
}
