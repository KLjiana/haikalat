#version 460 core

in vec2 vTexCoord;
in vec4 vVertexColor;
uniform int uHasVertexColor;
uniform sampler2D uBaseColorMap;
uniform vec4 uBaseColorFactor;
uniform float uAlphaCutoff;

void main() {
    float alpha = texture(uBaseColorMap, vTexCoord).a * uBaseColorFactor.a;
    if (uHasVertexColor != 0) alpha *= vVertexColor.a;
    if (alpha < uAlphaCutoff) discard;
}
