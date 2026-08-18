#version 460 core
in vec2 vTexCoord;
uniform sampler2D uBaseColorMap;
uniform vec4 uBaseColorFactor;
uniform float uAlphaCutoff;
void main() {
    if (texture(uBaseColorMap, vTexCoord).a * uBaseColorFactor.a < uAlphaCutoff) discard;
}
