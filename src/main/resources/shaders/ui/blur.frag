#version 460 core
uniform sampler2D uSource;
uniform vec4 uUvRect;
uniform vec2 uStep;
uniform int uHalfWidth;
uniform float uWeights[257];
in vec2 vUv;
layout(location=0) out vec4 outColor;
// Explicit interpolation keeps resampling in shader float precision, including odd extents.
vec4 sampleLinear(vec2 uv) {
    ivec2 size=textureSize(uSource,0);
    vec2 texel=uv*vec2(size)-.5;
    ivec2 base=ivec2(floor(texel));vec2 fraction=fract(texel);
    ivec2 hi=size-ivec2(1);
    vec4 a=texelFetch(uSource,clamp(base,ivec2(0),hi),0);
    vec4 b=texelFetch(uSource,clamp(base+ivec2(1,0),ivec2(0),hi),0);
    vec4 c=texelFetch(uSource,clamp(base+ivec2(0,1),ivec2(0),hi),0);
    vec4 d=texelFetch(uSource,clamp(base+ivec2(1,1),ivec2(0),hi),0);
    return mix(mix(a,b,fraction.x),mix(c,d,fraction.x),fraction.y);
}
void main() {
    vec2 uv=uUvRect.xy+vUv*uUvRect.zw;
    vec4 value=sampleLinear(uv)*uWeights[0];
    for(int i=1;i<=uHalfWidth;i++)
        value+=(sampleLinear(uv+uStep*float(i))+sampleLinear(uv-uStep*float(i)))*uWeights[i];
    outColor=value;
}
