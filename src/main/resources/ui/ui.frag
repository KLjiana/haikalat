#version 460 core

layout(location = 0) out vec4 outColor;

in vec2 vUv;
in vec4 vColor;

uniform sampler2D uTexture;
uniform int uMode;

void main() {
    if (uMode == 0) {
        outColor = vColor;
    } else if (uMode == 1) {
        vec4 sampled = texture(uTexture, vUv);
        outColor = vec4(sampled.rgb * sampled.a, sampled.a) * vColor;
    } else if (uMode == 2) {
        float coverage = texture(uTexture, vUv).r;
        outColor = vColor * coverage;
    } else {
        vec2 edgeDistance = min(vUv, vec2(1.0) - vUv);
        vec2 pixelWidth = fwidth(vUv) * 1.5;
        if (edgeDistance.x > pixelWidth.x && edgeDistance.y > pixelWidth.y) discard;
        outColor = vColor;
    }
}
