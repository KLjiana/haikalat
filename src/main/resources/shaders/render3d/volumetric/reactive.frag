uniform mat4 uFogRasterInverseProjection;
uniform mat4 uFogStableProjection;
layout(location=0) out float FragReactive;
void main() {
    float raw=texelFetch(uVolumeOpaqueDepth,ivec2(gl_FragCoord.xy),0).r;
    vec4 view=uFogRasterInverseProjection*vec4(2.0*gl_FragCoord.xy/vExtentDepth.xy-1.0,2.0*raw-1.0,1.0);
    view/=view.w;
    vec4 stable=uFogStableProjection*view;
    float depth=raw>=1.0 ? vExtentDepth.w : clamp(-view.z,0.0,vExtentDepth.w);
    vec2 pixel=(stable.xy/stable.w*0.5+0.5)*vExtentDepth.xy;
    FragReactive=volumeNativeReactive(pixel,depth,volumeReactive(pixel,depth));
}
