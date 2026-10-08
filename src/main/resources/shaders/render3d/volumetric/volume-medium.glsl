// A conservative endpoint box contains every point of a straight ray interval.
// Padding only admits extra candidates; it never changes the density formula.
uint volumeMediumSegmentMask(vec3 start,vec3 end) {
    if(vCounts.x==0)return 0u;
    if(any(isnan(start))||any(isinf(start))||any(isnan(end))||any(isinf(end)))return 0xffffffffu;
    vec3 padding=vec3(0.0001)+max(abs(start),abs(end))*0.000004;
    vec3 low=min(start,end)-padding,high=max(start,end)+padding;
    uint mask=0u;
    for(int i=0;i<vCounts.x;i++) {
        FogVolume v=fogVolumes[i];
        vec3 extent=v.centerShape.w<0.5 ? vec3(v.extentExtinction.x) : v.extentExtinction.xyz;
        if(any(lessThan(high,v.centerShape.xyz-extent))||any(greaterThan(low,v.centerShape.xyz+extent)))continue;
        mask|=1u<<uint(i);
    }
    return mask;
}
void volumeMediumAtMasked(vec3 world,uint candidates,out vec4 medium,out vec3 emission) {
    float height=exp(clamp(-(world.y-vEmissionHeight.w)*vSettings.x,-16.0,16.0));
    float sigma=vAlbedoExtinction.w*height;vec3 scattering=vAlbedoExtinction.rgb*sigma;emission=vEmissionHeight.rgb*height;
    uint remaining=candidates&((1u<<uint(vCounts.x))-1u);
    for(;remaining!=0u;remaining&=remaining-1u) {
        int i=findLSB(remaining);
        FogVolume v=fogVolumes[i];vec3 delta=abs(world-v.centerShape.xyz);
        float radius=v.centerShape.w<0.5 ? length(delta)/v.extentExtinction.x
            : max(max(delta.x/v.extentExtinction.x,delta.y/v.extentExtinction.y),delta.z/v.extentExtinction.z);
        if(radius>=1.0)continue;
        float weight=v.albedoFalloff.w>0 ? 1.0-smoothstep(1.0-v.albedoFalloff.w,1.0,radius) : 1.0;
        if(v.emissionNoiseScale.w>0 && v.noiseAmount.x>0)
            weight*=mix(1.0,valueNoise((world-vWindSeed.xyz*vSettings.z)*v.emissionNoiseScale.w),v.noiseAmount.x);
        float localSigma=v.extentExtinction.w*weight;
        sigma+=localSigma;scattering+=v.albedoFalloff.rgb*localSigma;emission+=v.emissionNoiseScale.rgb*weight;
    }
    if(isnan(sigma)||isinf(sigma)||any(isnan(scattering))||any(isinf(scattering))||any(isnan(emission))||any(isinf(emission))) {
        if(vDirtyHistory.w!=0)atomicAdd(diagnostics[0],1u);sigma=0.0;scattering=vec3(0.0);emission=vec3(0.0);
    }
    if(vDirtyHistory.w!=0 && (sigma>64.0 || any(greaterThan(emission,vec3(16.0)))))atomicAdd(diagnostics[1],1u);
    medium=vec4(min(scattering,vec3(64.0)),min(sigma,64.0));emission=min(emission,vec3(16.0));
}
void volumeMediumAt(vec3 world,out vec4 medium,out vec3 emission) {
    volumeMediumAtMasked(world,0xffffffffu,medium,emission);
}
