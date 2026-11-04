#version 460 core
uniform sampler2D uSource;
uniform vec4 uUvRect;
uniform vec2 uStep;
uniform int uHalfWidth;
uniform float uWeights[33];
in vec2 vUv;
layout(location=0) out vec4 outColor;
void main() {
    vec2 uv=uUvRect.xy+vUv*uUvRect.zw;
    vec4 value=texture(uSource,uv)*uWeights[0];
    for(int i=1;i<=uHalfWidth;i++)
        value+=(texture(uSource,uv+uStep*float(i))+texture(uSource,uv-uStep*float(i)))*uWeights[i];
    outColor=value;
}
