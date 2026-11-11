#version 330 core
/*
 * SMAA 1x luma edge detection (first pass).
 *
 * GLSL 330 port of SMAALumaEdgeDetectionPS from SMAA.hlsl, commit
 * 71c806a838bdd7d517df19192a20f0c61b3ca29d.  Copyright (C) 2013 Jorge Jimenez,
 * Jose I. Echevarria, Belen Masia, Fernando Navarro, Diego Gutierrez.
 * MIT License; full text and provenance in docs/licenses/smaa.md.
 *
 * Input is the display-referred, gamma-encoded RGBA8 image produced after
 * tone mapping and the screen effects.  Unchanged pixels discard; the target
 * is cleared to zero by the graph pass, like the reference edgesTex.
 */
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uColor;
uniform vec2 uInvResolution;

const float SMAA_THRESHOLD = 0.1;
const float SMAA_LOCAL_CONTRAST_ADAPTATION_FACTOR = 2.0;

float luma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

vec3 sceneTexel(vec2 uv) {
    return textureLod(uColor, uv, 0.0).rgb;
}

void main() {
    vec2 leftUv = vUv + vec2(-uInvResolution.x, 0.0);
    vec2 topUv = vUv + vec2(0.0, -uInvResolution.y);
    vec2 rightUv = vUv + vec2(uInvResolution.x, 0.0);
    vec2 bottomUv = vUv + vec2(0.0, uInvResolution.y);
    vec2 leftLeftUv = vUv + vec2(-2.0 * uInvResolution.x, 0.0);
    vec2 topTopUv = vUv + vec2(0.0, -2.0 * uInvResolution.y);

    float centerLuma = luma(sceneTexel(vUv));
    float leftLuma = luma(sceneTexel(leftUv));
    float topLuma = luma(sceneTexel(topUv));

    vec2 delta = abs(vec2(centerLuma) - vec2(leftLuma, topLuma));
    vec2 edges = step(vec2(SMAA_THRESHOLD), delta);
    if (edges.x + edges.y == 0.0) {
        discard;
    }

    float rightLuma = luma(sceneTexel(rightUv));
    float bottomLuma = luma(sceneTexel(bottomUv));
    vec2 neighbourDelta = abs(vec2(centerLuma) - vec2(rightLuma, bottomLuma));

    vec2 maxDelta = max(delta, neighbourDelta);
    float leftLeftLuma = luma(sceneTexel(leftLeftUv));
    float topTopLuma = luma(sceneTexel(topTopUv));
    vec2 extendedDelta = abs(vec2(leftLuma, topLuma) - vec2(leftLeftLuma, topTopLuma));
    maxDelta = max(maxDelta, extendedDelta);
    float finalDelta = max(maxDelta.x, maxDelta.y);

    edges *= step(finalDelta, SMAA_LOCAL_CONTRAST_ADAPTATION_FACTOR * delta);
    FragColor = vec4(edges, 0.0, 1.0);
}
