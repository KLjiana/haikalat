layout(binding=14) uniform sampler3D uVolumePrefix;
layout(binding=15) uniform sampler3D uVolumeSource;
uniform int uVolumeSurfaceGuideEnabled;

vec4 volumeQueryColumn(ivec2 column,float depth) {
    float d=clamp(depth,0.0,vExtentDepth.w);
    if (d == vExtentDepth.w) return texelFetch(uVolumePrefix,ivec3(column,vGrid.z),0);
    int z=volumeInterval(d);
    vec4 prefix=texelFetch(uVolumePrefix,ivec3(column,z),0);
    vec4 q=texelFetch(uVolumeSource,ivec3(column,z),0);
    return volumeIntegrateSegment(prefix,q,volumeSegment(column,z,d));
}
// Last clipped tile has a different center; interpolate in render pixels, never padded pixels.
float volumeColumnCoordinate(float pixel,int count,int extent) {
    if (count == 1) return 0.0;
    int lo=clamp(int(floor(pixel/float(vGrid.w)-0.5)),0,count-2);
    float a=0.5*float(lo*vGrid.w+min((lo+1)*vGrid.w,extent));
    float b=0.5*float((lo+1)*vGrid.w+min((lo+2)*vGrid.w,extent));
    return float(lo)+clamp((pixel-a)/(b-a),0.0,1.0);
}
vec4 volumeReconstructionWeights(ivec2 a,ivec2 b,vec2 f,float depth) {
    vec4 spatial=vec4((1-f.x)*(1-f.y),f.x*(1-f.y),(1-f.x)*f.y,f.x*f.y);
    if(uVolumeSurfaceGuideEnabled==0||depth>=vExtentDepth.w)return spatial;
    vec4 guides=vec4(texelFetch(uVolumePrefix,ivec3(a,vGrid.z+1),0).r,
        texelFetch(uVolumePrefix,ivec3(b.x,a.y,vGrid.z+1),0).r,
        texelFetch(uVolumePrefix,ivec3(a.x,b.y,vGrid.z+1),0).r,
        texelFetch(uVolumePrefix,ivec3(b,vGrid.z+1),0).r);
    // Continuous in physical depth, including log-slice boundaries.
    float interval=vCounts.z!=0 ? depth*log(vExtentDepth.w/vExtentDepth.z)/float(vGrid.z-1)
        : vExtentDepth.w/float(vGrid.z);
    float tolerance=max(0.005,0.25*interval);
    vec4 distance=mix(vec4(2*vExtentDepth.w),abs(guides-depth),lessThan(guides,vec4(vExtentDepth.w)));
    // Only guide a query that lies on a nearby opaque layer. Air queries between
    // surfaces retain the full volume, including everything behind opaque depth.
    float nearest=min(min(distance.x,distance.y),min(distance.z,distance.w));
    if(nearest>=2*tolerance)return spatial;
    vec4 delta=distance/tolerance;
    vec4 weighted=spatial*exp2(-delta*delta)*(1-smoothstep(vec4(1),vec4(2),delta));
    float total=dot(weighted,vec4(1));
    if(total<=1e-8)return spatial;
    // Preserve exact zero support inside the fully guided region. A rounded
    // mix endpoint must not reintroduce a background column into reactive MAX.
    if(nearest<=tolerance && vExtentDepth.w-depth>=2*tolerance)return weighted/total;
    float strength=(1-smoothstep(tolerance,2*tolerance,nearest))*smoothstep(0,2*tolerance,vExtentDepth.w-depth);
    return mix(spatial,weighted/total,strength);
}
vec4 volumeQuery(vec2 pixel,float depth) {
    vec2 c=vec2(volumeColumnCoordinate(pixel.x,vGrid.x,int(vExtentDepth.x)),
                volumeColumnCoordinate(pixel.y,vGrid.y,int(vExtentDepth.y)));
    ivec2 a=ivec2(floor(c)),b=min(a+1,vGrid.xy-1); vec2 f=fract(c);
    vec4 w=volumeReconstructionWeights(a,b,f,depth);
    vec4 result=vec4(0);
    if(w.x>0.0)result+=w.x*volumeQueryColumn(a,depth);
    if(w.y>0.0)result+=w.y*volumeQueryColumn(ivec2(b.x,a.y),depth);
    if(w.z>0.0)result+=w.z*volumeQueryColumn(ivec2(a.x,b.y),depth);
    if(w.w>0.0)result+=w.w*volumeQueryColumn(b,depth);
    return result;
}
