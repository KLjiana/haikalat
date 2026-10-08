// Borrow the existing successful-frame dirty snapshot. A native-ray medium can
// be missed by every coarse sample, so an R8 coarse-source mask alone is insufficient.
layout(std430,binding=14) readonly buffer VolumeReactiveDomains { vec4 volumeReactiveDomains[]; };

bool volumeReactiveSphere(vec3 origin,vec3 direction,vec3 center,float radius,inout vec2 interval) {
    vec3 offset=center-origin;float along=dot(offset,direction);
    float squared=radius*radius-(dot(offset,offset)-along*along);
    if(squared<=0.0)return false;
    float reach=sqrt(squared);
    interval=vec2(max(interval.x,along-reach),min(interval.y,along+reach));
    return interval.y>interval.x;
}
bool volumeReactiveBox(vec3 origin,vec3 direction,vec3 center,vec3 extent,inout vec2 interval) {
    for(int axis=0;axis<3;axis++) {
        if(abs(direction[axis])<0.0000001) {
            if(abs(origin[axis]-center[axis])>=extent[axis])return false;
        } else {
            float a=(center[axis]-extent[axis]-origin[axis])/direction[axis];
            float b=(center[axis]+extent[axis]-origin[axis])/direction[axis];
            interval=vec2(max(interval.x,min(a,b)),min(interval.y,max(a,b)));
            if(interval.y<=interval.x)return false;
        }
    }
    return interval.y>interval.x;
}
vec3 volumeReactiveFootprint(vec2 pixel,vec3 center) {
    float d=clamp(-(vView*vec4(center,1)).z,0.0,vExtentDepth.w);
    vec3 centerRay=volumeProjectionPoint(pixel),rightRay=volumeProjectionPoint(pixel+vec2(1,0)),upRay=volumeProjectionPoint(pixel+vec2(0,1));
    if(vCounts.z!=0) {
        centerRay*=d/-centerRay.z;rightRay*=d/-rightRay.z;upRay*=d/-upRay.z;
    }
    return abs((vInverseView*vec4(rightRay-centerRay,0)).xyz)
        +abs((vInverseView*vec4(upRay-centerRay,0)).xyz);
}
bool volumeReactiveScattering(vec2 pixel,vec3 origin,vec3 direction,float cosine,vec2 interval) {
    if(vAlbedoExtinction.w>0.0&&any(greaterThan(vAlbedoExtinction.rgb,vec3(0))))
        return volumeQuery(pixel,interval.x*cosine).a>0.0;
    for(int i=0;i<vCounts.x;i++) {
        FogVolume medium=fogVolumes[i];
        if(medium.extentExtinction.w<=0.0||!any(greaterThan(medium.albedoFalloff.rgb,vec3(0))))continue;
        // Final AA can retain an adjacent, previously jittered native ray. Cover
        // one pixel of reconstruction support while retaining the visible depth clip.
        vec3 footprint=volumeReactiveFootprint(pixel,medium.centerShape.xyz);
        vec2 visible=interval;
        bool intersects=medium.centerShape.w<0.5
            ? volumeReactiveSphere(origin,direction,medium.centerShape.xyz,medium.extentExtinction.x+length(footprint),visible)
            : volumeReactiveBox(origin,direction,medium.centerShape.xyz,medium.extentExtinction.xyz+footprint,visible);
        if(intersects&&volumeQuery(pixel,visible.x*cosine).a>0.0)return true;
    }
    return false;
}
float volumeNativeReactive(vec2 pixel,float depth,float coarse) {
    if(coarse>=1.0||depth<=0.0||(vNativeReactive.x==0&&vNativeReactive.y==0&&vDirtyHistory.x==0&&(vDirtyHistory.y&128)==0))return coarse;
    vec3 ray=volumeProjectionPoint(pixel);
    float cosine=vCounts.z!=0 ? -ray.z/length(ray) : 1.0;
    vec3 origin=(vInverseView*vec4(vCounts.z!=0 ? vec3(0) : vec3(ray.xy,0),1)).xyz;
    vec3 direction=normalize((vInverseView*vec4(vCounts.z!=0 ? ray : vec3(0,0,-1),0)).xyz);
    vec2 visible=vec2(0,clamp(depth,0.0,vExtentDepth.w)/cosine);
    for(int i=0;i<vNativeReactive.x;i++) {
        vec4 domain=vNativeMediumDomains[2*i],extent=vNativeMediumDomains[2*i+1];
        vec3 footprint=volumeReactiveFootprint(pixel,domain.xyz);vec2 interval=visible;
        bool intersects=domain.w>=0.0
            ? volumeReactiveSphere(origin,direction,domain.xyz,domain.w+length(footprint),interval)
            : volumeReactiveBox(origin,direction,domain.xyz,extent.xyz+footprint,interval);
        if(intersects&&volumeQuery(pixel,interval.x*cosine).a>0.0)return 1.0;
    }
    if((vNativeReactive.y!=0||(vDirtyHistory.y&128)!=0)&&volumeReactiveScattering(pixel,origin,direction,cosine,visible))return 1.0;
    for(int i=0;i<vDirtyHistory.x;i++) {
        vec4 domain=volumeReactiveDomains[2*i],extent=volumeReactiveDomains[2*i+1];
        if(extent.w<=0.5)continue;
        vec2 interval=visible;
        vec3 footprint=volumeReactiveFootprint(pixel,domain.xyz);
        bool intersects=domain.w>=0.0 ? volumeReactiveSphere(origin,direction,domain.xyz,extent.x+length(footprint),interval)
            : volumeReactiveBox(origin,direction,domain.xyz,extent.xyz,interval);
        if(intersects&&volumeReactiveScattering(pixel,origin,direction,cosine,interval))return 1.0;
    }
    return coarse;
}
