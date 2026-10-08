struct LightRecord { vec4 positionRange; vec4 directionOuter; vec4 colorIntensity; vec4 extra; ivec4 metadata; };
layout(std430,binding=7) buffer VolumeDiagnostics { uint diagnostics[]; };
layout(std430,binding=0) readonly buffer LightTableBlock { uvec4 uLightHeader; LightRecord uLights[]; };
layout(std430,binding=2) readonly buffer ClusterHeadersBlock { uvec4 uClusterHeaders[]; };
layout(std430,binding=3) readonly buffer ClusterIndicesBlock { uint uClusterIndices[]; };
struct VolumeHint { float intensity; uint flags; uint reserved0; uint reserved1; };
layout(std430,binding=4) readonly buffer LightVolumeHintsBlock { VolumeHint uVolumeHints[]; };
layout(std140,binding=6) uniform ClusterParametersBlock {
    mat4 uStableView; mat4 uStableViewProjection; mat4 uInverseProjection;
    ivec4 uGridParams; vec4 uDepthParams; ivec4 uLightCounts; vec4 uEdgeExpand;
};
layout(std140,binding=5) uniform ShadowSamplingBlock {
    ivec4 uPointShadowMeta[2]; mat4 uPointSlotMatrices[12]; vec4 uPointFaceRects[12];
    ivec4 uSpotShadowMeta[4]; mat4 uSpotSlotMatrices[4]; vec4 uSpotTileRects[4]; ivec4 uShadowQualityMeta;
};
layout(binding=7) uniform sampler2D uShadowMap;
layout(binding=11) uniform sampler2D uPointShadowMap;
layout(binding=12) uniform sampler2D uSpotShadowMap;
uniform int uLightingEnabled;
uniform int uVolumeDiagnosticsEnabled;
uniform int uHasDirectionalShadow;
uniform int uHasPointShadow;
uniform int uHasSpotShadow;
uniform int uDirectionalShadowFrameLightIndex;
uniform int uDirectionalCascadeCount;
uniform mat4 uDirectionalCascadeMatrices[4];
uniform float uDirectionalCascadeSplits[4];
uniform float uDirectionalCascadeBlendRange;
uniform vec4 uVolumeCascadeRects[4];
uniform float uVolumeDirectionalBias;
uniform float uVolumePointBias;
uniform float uVolumeSpotBias;

float volumeAtlasVisibility(sampler2D atlas,mat4 matrix,vec4 rect,vec3 world,float bias) {
    vec4 clip=matrix*vec4(world,1.0);
    if (clip.w<=0.0) return 1.0;
    vec3 projected=clip.xyz/clip.w*0.5+0.5;
    if (projected.z<=0.0 || projected.z>=1.0 || any(lessThanEqual(projected.xy,vec2(0.0)))
            || any(greaterThanEqual(projected.xy,vec2(1.0)))) return 1.0;
    vec2 texel=1.0/vec2(textureSize(atlas,0)); int radius=clamp(uShadowQualityMeta.x,0,2);
    vec2 guard=texel*(float(radius)+0.5); vec2 a=rect.xy+guard,b=rect.zw-guard;
    vec2 uv=mix(rect.xy,rect.zw,projected.xy); float lit=0.0;
    for (int x=-2;x<=2;x++) for (int y=-2;y<=2;y++) {
        if (abs(x)>radius||abs(y)>radius) continue;
        float stored=texture(atlas,clamp(uv+vec2(x,y)*texel,a,b)).r;
        lit+=projected.z-bias<=stored ? 1.0 : 0.0;
    }
    float count=float(2*radius+1); return lit/(count*count);
}
float volumeDirectionalVisibility(vec3 world,float depth) {
    int count=clamp(uDirectionalCascadeCount,1,4),last=count-1;
    if (depth>=uDirectionalCascadeSplits[last]) return 1.0;
    int cascade=last;
    for (int i=0;i<count;i++) if (depth<=uDirectionalCascadeSplits[i]) {cascade=i;break;}
    float lit=volumeAtlasVisibility(uShadowMap,uDirectionalCascadeMatrices[cascade],uVolumeCascadeRects[cascade],world,uVolumeDirectionalBias);
    float near=cascade==0 ? 0.0 : uDirectionalCascadeSplits[cascade-1];
    float width=max((uDirectionalCascadeSplits[cascade]-near)*uDirectionalCascadeBlendRange,0.0001);
    float fade=smoothstep(uDirectionalCascadeSplits[cascade]-width,uDirectionalCascadeSplits[cascade],depth);
    if (fade>0.0) {
        float next=cascade==last ? 1.0 : volumeAtlasVisibility(uShadowMap,uDirectionalCascadeMatrices[cascade+1],
                uVolumeCascadeRects[cascade+1],world,uVolumeDirectionalBias);
        lit=mix(lit,next,fade);
    }
    return lit;
}
int volumePointFace(vec3 d) {
    vec3 a=abs(d);
    if (a.x>=a.y&&a.x>=a.z) return d.x>=0.0 ? 0 : 1;
    if (a.y>=a.z) return d.y>=0.0 ? 2 : 3;
    return d.z>=0.0 ? 4 : 5;
}
float volumeLightVisibility(uint index,LightRecord light,VolumeHint hints,vec3 world,float depth) {
    if ((hints.flags&1u)==0u) return 1.0;
    int slot=light.metadata.y,type=light.metadata.x;
    if (type==0) return uHasDirectionalShadow!=0 && int(index)==uDirectionalShadowFrameLightIndex
            ? volumeDirectionalVisibility(world,depth) : 1.0;
    if (slot<0) { if (uVolumeDiagnosticsEnabled!=0 && (light.metadata.z&1)!=0) atomicAdd(diagnostics[9],1u); return 1.0; }
    if (type==1 && uHasPointShadow!=0 && slot<2 && uPointShadowMeta[slot].y!=0 && uPointShadowMeta[slot].x==int(index)) {
        int face=slot*6+volumePointFace(world-light.positionRange.xyz);
        return volumeAtlasVisibility(uPointShadowMap,uPointSlotMatrices[face],uPointFaceRects[face],world,uVolumePointBias);
    }
    if (type==2 && uHasSpotShadow!=0 && slot<4 && uSpotShadowMeta[slot].y!=0 && uSpotShadowMeta[slot].x==int(index)) {
        return volumeAtlasVisibility(uSpotShadowMap,uSpotSlotMatrices[slot],uSpotTileRects[slot],world,uVolumeSpotBias);
    }
    return 1.0;
}
vec3 volumeLightRadiance(uint index,vec3 world,vec3 V,float depth) {
    VolumeHint hints=uVolumeHints[index];
    if (hints.intensity<=0.0) return vec3(0.0);
    if (uVolumeDiagnosticsEnabled!=0) atomicAdd(diagnostics[8],1u);
    LightRecord light=uLights[index]; vec3 L; float attenuation=1.0,cone=1.0;
    if (light.metadata.x==0) L=normalize(-light.directionOuter.xyz);
    else {
        vec3 delta=light.positionRange.xyz-world; float distance=length(delta);
        if (distance>=light.positionRange.w) return vec3(0.0);
        L=delta/max(distance,0.00001);
        float window=clamp(1.0-distance/max(light.positionRange.w,0.0001),0.0,1.0);
        attenuation=window*window/max(distance*distance,0.0001);
        if (light.metadata.x==2) {
            float angle=acos(clamp(dot(-L,normalize(light.directionOuter.xyz)),-1.0,1.0));
            cone=1.0-smoothstep(light.extra.x,light.directionOuter.w,angle);
            if (cone<=0.0) return vec3(0.0);
        }
    }
    float phase=volumePhase(vSettings.y,clamp(dot(-L,V),-1.0,1.0));
    float visibility=volumeLightVisibility(index,light,hints,world,depth);
    return light.colorIntensity.rgb*(light.colorIntensity.a*attenuation*cone*phase*visibility*hints.intensity);
}
// Cone and caster edges remain sharp beyond a point emitter's singular core.
// Both the column selector and native quadrature use this world-space support.
float volumeNativeRefinementRadius(LightRecord light) {
    return min(light.metadata.x==2 ? 2.0 : 1.0,light.positionRange.w);
}
int volumeLightingCluster(vec3 world) {
    vec4 clip=uStableViewProjection*vec4(world,1.0);
    float depth=-(uStableView*vec4(world,1.0)).z;
    if (clip.w<=0.0 || depth<uDepthParams.x || depth>uDepthParams.y) return -1;
    vec2 uv=clip.xy/clip.w*0.5+0.5;
    if (any(lessThan(uv,vec2(0.0)))||any(greaterThan(uv,vec2(1.0)))) return -1;
    int z=uDepthParams.z>0.5 ? int(floor(log(depth/uDepthParams.x)*float(uGridParams.z)/log(uDepthParams.y/uDepthParams.x)))
        : int(floor((depth-uDepthParams.x)/(uDepthParams.y-uDepthParams.x)*float(uGridParams.z)));
    ivec2 xy=clamp(ivec2(floor(uv*vec2(uGridParams.xy))),ivec2(0),uGridParams.xy-1);
    return xy.x+uGridParams.x*(xy.y+uGridParams.y*clamp(z,0,uGridParams.z-1));
}
vec3 volumeLightingOmitting(vec3 world,int omitted) {
    vec3 viewPosition=(vView*vec4(world,1.0)).xyz;
    float depth=-viewPosition.z;
    vec3 V=vCounts.z!=0 ? normalize((vInverseView*vec4(-viewPosition,0.0)).xyz)
                        : normalize((vInverseView*vec4(0.0,0.0,1.0,0.0)).xyz);
    vec3 radiance=vec3(0.0);
    for(uint i=0u;i<uLightHeader.x;i++)if(int(i)!=omitted)radiance+=volumeLightRadiance(i,world,V,depth);
    int cluster=volumeLightingCluster(world); uvec4 header=uvec4(0u);
    #ifndef VOLUME_FULL_SCAN
    if (cluster>=0) header=uClusterHeaders[cluster];
    bool fullScan=cluster<0 || header.z!=0u;
    #else
    bool fullScan=true;
    #endif
    uint candidates=fullScan ? uLightHeader.y : header.y;
    if (uVolumeDiagnosticsEnabled!=0) {
        atomicAdd(diagnostics[4],1u); atomicAdd(diagnostics[7],candidates); atomicMax(diagnostics[10],candidates);
        if(cluster<0) atomicAdd(diagnostics[6],1u); else if(header.z!=0u) atomicAdd(diagnostics[5],1u);
        // Exact histogram through 990 candidates; bucket 991 explicitly represents >=991.
        atomicAdd(diagnostics[16u+min(candidates,991u)],1u);
    }
    for(uint j=0u;j<candidates;j++) {
        uint index=fullScan ? uLightHeader.x+j : uClusterIndices[header.x+j];
        if(int(index)!=omitted)radiance+=volumeLightRadiance(index,world,V,depth);
    }
    return radiance;
}
vec3 volumeLighting(vec3 world) {return volumeLightingOmitting(world,-1);}
