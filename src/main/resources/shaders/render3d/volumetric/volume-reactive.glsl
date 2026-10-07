layout(binding=16) uniform sampler3D uVolumeReject;
layout(binding=17) uniform sampler3D uVolumePreviousSource;
layout(binding=18) uniform sampler2D uVolumeOpaqueDepth;
layout(binding=19) uniform sampler3D uVolumeReactivePrefix;
layout(binding=20) uniform sampler3D uVolumeLightOffReactive;
uniform int uVolumeReactiveOnly;

float volumeReactiveColumn(ivec2 column,float depth) {
    float d=clamp(depth,0.0,vExtentDepth.w);
    if(d==vExtentDepth.w)return texelFetch(uVolumeReactivePrefix,ivec3(column,vGrid.z),0).r;
    int z=volumeInterval(d);
    float prefix=texelFetch(uVolumeReactivePrefix,ivec3(column,z),0).r;
    if(prefix>=1.0)return 1.0;
    float ds=volumeSegment(column,z,d);
    if(ds>0.0 && texelFetch(uVolumePrefix,ivec3(column,z),0).a>0.0
            && texelFetch(uVolumeLightOffReactive,ivec3(column,z),0).r>0.5)return 1.0;
    vec4 current=texelFetch(uVolumeSource,ivec3(column,z),0);
    vec4 old=vCounts.w!=0 ? texelFetch(uVolumePreviousSource,ivec3(column,z),0) : current;
    vec3 changedSource=vCounts.w!=0 ? abs(current.rgb-old.rgb) : current.rgb;
    float signal=texelFetch(uVolumePrefix,ivec3(column,z),0).a
        *(volumeIntegralWeight(current.a,ds)*length(changedSource)+abs(current.a-old.a)*ds);
    return clamp(prefix+texelFetch(uVolumeReject,ivec3(column,z),0).r*signal/vHistoryConfidence.w,0.0,1.0);
}
float volumeReactive(vec2 pixel,float depth) {
    vec2 c=vec2(volumeColumnCoordinate(pixel.x,vGrid.x,int(vExtentDepth.x)),volumeColumnCoordinate(pixel.y,vGrid.y,int(vExtentDepth.y)));
    ivec2 a=ivec2(floor(c)),b=min(a+1,vGrid.xy-1);vec4 w=volumeReconstructionWeights(a,b,fract(c),depth);
    return max(max(w.x>0 ? volumeReactiveColumn(a,depth) : 0,w.y>0 ? volumeReactiveColumn(ivec2(b.x,a.y),depth) : 0),
               max(w.z>0 ? volumeReactiveColumn(ivec2(a.x,b.y),depth) : 0,w.w>0 ? volumeReactiveColumn(b,depth) : 0));
}

// Opaque color and reactive use the same physical interval, source and transmission.
// Fetch/derive those once when the single-sample compositor writes both MRT outputs.
vec4 volumeQueryReactiveColumn(ivec2 column,float depth,out float reactive) {
    float d=clamp(depth,0.0,vExtentDepth.w);
    if(d==vExtentDepth.w) {
        reactive=texelFetch(uVolumeReactivePrefix,ivec3(column,vGrid.z),0).r;
        return texelFetch(uVolumePrefix,ivec3(column,vGrid.z),0);
    }
    int z=volumeInterval(d);
    vec4 prefix=texelFetch(uVolumePrefix,ivec3(column,z),0);
    vec4 current=texelFetch(uVolumeSource,ivec3(column,z),0);
    float ds=volumeSegment(column,z,d),weight=volumeIntegralWeight(current.a,ds);
    reactive=texelFetch(uVolumeReactivePrefix,ivec3(column,z),0).r;
    if(ds>0.0 && prefix.a>0.0 && texelFetch(uVolumeLightOffReactive,ivec3(column,z),0).r>0.5)reactive=1.0;
    if(reactive<1.0) {
        vec4 old=vCounts.w!=0 ? texelFetch(uVolumePreviousSource,ivec3(column,z),0) : current;
        vec3 changedSource=vCounts.w!=0 ? abs(current.rgb-old.rgb) : current.rgb;
        float signal=prefix.a*(weight*length(changedSource)+abs(current.a-old.a)*ds);
        reactive=clamp(reactive+texelFetch(uVolumeReject,ivec3(column,z),0).r*signal/vHistoryConfidence.w,0.0,1.0);
    }
    return vec4(prefix.rgb+prefix.a*current.rgb*weight,prefix.a*exp(-current.a*ds));
}
vec4 volumeQueryWithReactive(vec2 pixel,float depth,out float reactive) {
    vec2 c=vec2(volumeColumnCoordinate(pixel.x,vGrid.x,int(vExtentDepth.x)),volumeColumnCoordinate(pixel.y,vGrid.y,int(vExtentDepth.y)));
    ivec2 a=ivec2(floor(c)),b=min(a+1,vGrid.xy-1);vec2 f=fract(c);
    vec4 w=volumeReconstructionWeights(a,b,f,depth);
    float r0=0,r1=0,r2=0,r3=0;
    vec4 c0=vec4(0),c1=vec4(0),c2=vec4(0),c3=vec4(0);
    if(w.x>0.0)c0=volumeQueryReactiveColumn(a,depth,r0);
    if(w.y>0.0)c1=volumeQueryReactiveColumn(ivec2(b.x,a.y),depth,r1);
    if(w.z>0.0)c2=volumeQueryReactiveColumn(ivec2(a.x,b.y),depth,r2);
    if(w.w>0.0)c3=volumeQueryReactiveColumn(b,depth,r3);
    reactive=max(max(w.x>0 ? r0 : 0,w.y>0 ? r1 : 0),max(w.z>0 ? r2 : 0,w.w>0 ? r3 : 0));
    return w.x*c0+w.y*c1+w.z*c2+w.w*c3;
}
