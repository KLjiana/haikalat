#version 460 core

layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec2 aUv;

uniform mat4 uMvp;
uniform mat4 uProjectionView;
uniform vec2 uRibbonWidths;
uniform vec4 uUvRegion;
uniform vec4 uNextUvRegion;
uniform int uRibbon;
uniform vec2 uRibbonUvRange;
uniform vec3 uRibbonStart;
uniform vec3 uRibbonEnd;
uniform vec3 uRibbonStartOffset;
uniform vec3 uRibbonEndOffset;

out vec2 vUv;
out vec2 vNextUv;

void main() {
    vec2 localUv = aUv;
    if (uRibbon != 0) {
        float side = aUv.y * 2.0 - 1.0;
        vec3 center = mix(uRibbonStart, uRibbonEnd, aUv.x);
        vec3 offset = mix(uRibbonStartOffset, uRibbonEndOffset, aUv.x);
        gl_Position = uProjectionView * vec4(center + offset * side, 1.0);
        localUv = vec2(aUv.y, mix(uRibbonUvRange.x, uRibbonUvRange.y, aUv.x));
    } else {
        vec3 position = aPosition;
        position.y *= mix(uRibbonWidths.x, uRibbonWidths.y, aUv.x);
        gl_Position = uMvp * vec4(position, 1.0);
    }
    vUv = mix(uUvRegion.xy, uUvRegion.zw, localUv);
    vNextUv = mix(uNextUvRegion.xy, uNextUvRegion.zw, localUv);
}
