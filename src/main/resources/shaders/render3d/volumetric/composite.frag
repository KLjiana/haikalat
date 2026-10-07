uniform mat4 uFogRasterInverseProjection;
uniform mat4 uFogStableProjection;
#ifdef VOLUME_MSAA
layout(binding=0) uniform sampler2DMS uSceneColor;
layout(binding=1) uniform sampler2DMS uSceneDepth;
#else
layout(binding=0) uniform sampler2D uSceneColor;
layout(binding=1) uniform sampler2D uSceneDepth;
#endif
layout(location=0) out vec4 FragColor;
#ifndef VOLUME_MSAA
layout(location=1) out float FragReactive;
#endif
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy);
#ifdef VOLUME_MSAA
    vec2 pixel=vec2(p)+gl_SamplePosition;
    vec4 color=texelFetch(uSceneColor,p,gl_SampleID);
    float rawDepth=texelFetch(uSceneDepth,p,gl_SampleID).r;
#else
    vec2 pixel=gl_FragCoord.xy;
    vec4 color=texelFetch(uSceneColor,p,0);
    float rawDepth=texelFetch(uSceneDepth,p,0).r;
#endif
    vec4 view=uFogRasterInverseProjection*vec4(2.0*pixel/vExtentDepth.xy-1.0,2.0*rawDepth-1.0,1.0);
    view/=view.w;
    float depth=rawDepth >= 1.0 ? vExtentDepth.w : clamp(-view.z,0.0,vExtentDepth.w);
    vec4 stable=uFogStableProjection*view;
    vec2 stablePixel=(stable.xy/stable.w*0.5+0.5)*vExtentDepth.xy;
#ifdef VOLUME_MSAA
    vec4 fog=volumeQuery(stablePixel,depth);
#else
    vec4 fog=volumeQueryWithReactive(stablePixel,depth,FragReactive);
#endif
    fog=volumeRefineNativeRay(stablePixel,depth,fog);
    FragColor=vec4(fog.rgb+fog.a*color.rgb,color.a);
}
