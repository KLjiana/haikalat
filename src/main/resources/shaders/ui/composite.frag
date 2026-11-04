#version 460 core
uniform sampler2D uSource;
uniform float uOpacity;
in vec2 vUv;
layout(location=0) out vec4 outColor;
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
void main() { outColor=sampleLinear(vUv)*uOpacity; }
