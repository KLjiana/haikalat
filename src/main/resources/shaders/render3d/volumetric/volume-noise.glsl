struct FogVolume { vec4 centerShape; vec4 extentExtinction; vec4 albedoFalloff; vec4 emissionNoiseScale; vec4 noiseAmount; vec4 reserved; };
layout(std430,binding=6) readonly buffer FogVolumes { FogVolume fogVolumes[]; };
uint volumeHash(uvec3 cell) {
    uint h=cell.x*1597334677u ^ cell.y*3812015801u ^ cell.z*2798796415u ^ floatBitsToUint(vWindSeed.w);
    h=(h^(h>>16))*2246822519u; h=(h^(h>>13))*3266489917u; return h^(h>>16);
}
float valueNoise(vec3 p) {
    ivec3 cell=ivec3(floor(p)); vec3 f=fract(p); f=f*f*(3.0-2.0*f);
    float v[8];
    for (int k=0;k<8;k++) v[k]=float(volumeHash(uvec3(cell+ivec3(k&1,(k>>1)&1,(k>>2)&1)))&0x00ffffffu)/16777215.0;
    return mix(mix(mix(v[0],v[1],f.x),mix(v[2],v[3],f.x),f.y),
               mix(mix(v[4],v[5],f.x),mix(v[6],v[7],f.x),f.y),f.z);
}
float volumeWindConfidence(vec3 world,float sigma,vec3 source) {
    if (length(vWindSeed.xyz)==0.0) return 1.0;
    float changedCoefficients=0.0;
    for (int i=0;i<vCounts.x;i++) {
        FogVolume v=fogVolumes[i];
        if (v.noiseAmount.x<=0.0 || v.emissionNoiseScale.w<=0.0) continue;
        vec3 delta=abs(world-v.centerShape.xyz);
        float r=v.centerShape.w<0.5 ? length(delta)/v.extentExtinction.x
            : max(max(delta.x/v.extentExtinction.x,delta.y/v.extentExtinction.y),delta.z/v.extentExtinction.z);
        if (r>=1.0) continue;
        float edge=v.albedoFalloff.w>0.0 ? 1.0-smoothstep(1.0-v.albedoFalloff.w,1.0,r) : 1.0;
        float now=valueNoise((world-vWindSeed.xyz*vSettings.z)*v.emissionNoiseScale.w);
        float old=valueNoise((world-vWindSeed.xyz*vPreviousDepthTime.z)*v.emissionNoiseScale.w);
        changedCoefficients+=edge*abs(now-old)*v.noiseAmount.x*(v.extentExtinction.w+length(v.emissionNoiseScale.rgb));
    }
    return clamp(1.0-changedCoefficients/(vHistoryConfidence.x*max(sigma+length(source),vHistoryConfidence.w)),0.0,1.0);
}
