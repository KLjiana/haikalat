uniform int uVolumeAdditive;
uniform mat4 uVolumeRasterInverseProjection;

// Input is straight alpha for ALPHA and already coverage-weighted emission for ADDITIVE.
vec4 volumeFogSurface(vec4 surface, vec3 world, bool additive) {
    vec4 clip=vViewProjection*vec4(world,1.0);
    vec2 pixel=(clip.xy/clip.w*0.5+0.5)*vExtentDepth.xy;
    float depth=max(0.0,-(vView*vec4(world,1.0)).z);
    if (uVolumeReactiveOnly!=0) {
        if (gl_FragCoord.z>texelFetch(uVolumeOpaqueDepth,ivec2(gl_FragCoord.xy),0).r+0.00001) discard;
        return vec4(vec3(surface.a*volumeNativeReactive(pixel,depth,volumeReactive(pixel,depth))),1.0);
    }
    vec4 fog=volumeRefineNativeRay(pixel,depth,volumeQuery(pixel,depth));
    return vec4(additive ? fog.a*surface.rgb : surface.a*(fog.a*surface.rgb+fog.rgb),surface.a);
}

vec4 volumeFogRasterSurface(vec4 surface, bool additive) {
    vec4 view=uVolumeRasterInverseProjection*vec4(2.0*gl_FragCoord.xy/vExtentDepth.xy-1.0,
                                                2.0*gl_FragCoord.z-1.0,1.0);
    vec3 world=(vInverseView*vec4(view.xyz/view.w,1.0)).xyz;
    return volumeFogSurface(surface,world,additive);
}
