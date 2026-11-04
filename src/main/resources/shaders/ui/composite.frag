#version 460 core
uniform sampler2D uSource;
uniform float uOpacity;
in vec2 vUv;
layout(location=0) out vec4 outColor;
void main() { outColor=texture(uSource,vUv)*uOpacity; }
