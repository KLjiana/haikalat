// Conservative ball enclosing the collinear native nodes. Nonfinite/large-angle
// inputs retain the original path; padding admits extra work near boundaries.
bool volumeNativeSpotNodesUnlit(LightRecord light,vec3 first,vec3 last) {
    float outer=light.directionOuter.w;
    if(!(outer>0.0 && outer<1.5707963))return false;
    vec3 axis=normalize(light.directionOuter.xyz);
    vec3 center=(first+last)*0.5-light.positionRange.xyz;
    float padding=0.0001+max(max(length(first),length(last)),length(light.positionRange.xyz))*0.000008;
    float radius=length(last-first)*0.5+padding;
    float nearest=max(0.0,length(center)-radius);
    float upper=dot(center,axis)+radius;
    float lower=cos(outer)*nearest-padding;
    if(isnan(upper)||isinf(upper)||isnan(lower)||isinf(lower))return false;
    return upper<lower;
}
// Native-ray quadrature replaces selected near-emitter intervals. The stored
// filtered contribution is removed once; unrefined intervals keep their history.
uniform int uFineOmittedLight=-1;
layout(binding=21) uniform sampler3D uVolumeCurrentSource;
uniform int uVolumeCurrentSourceEnabled=0;

vec3 volumeFineFiniteSource(vec3 q) {
    if(any(isnan(q))||any(isinf(q))) {
        if(vDirtyHistory.w!=0)atomicAdd(diagnostics[0],1u);
        return vec3(0);
    }
    if(any(greaterThan(q,vec3(4096)))) {
        if(vDirtyHistory.w!=0)atomicAdd(diagnostics[2],1u);
        q=min(q,vec3(4096));
    }
    return q;
}
vec4 volumeFineSource(vec3 world) {
    vec4 medium;vec3 emission;volumeMediumAt(world,medium,emission);
    return vec4(volumeFineFiniteSource(emission+medium.rgb*volumeLightingOmitting(world,uFineOmittedLight)),medium.a);
}
vec3 volumeFineColumnSource(ivec2 column,int z,vec3 world) {
    if(uVolumeCurrentSourceEnabled!=0 && uFineOmittedLight<0)
        return texelFetch(uVolumeCurrentSource,ivec3(column,z),0).rgb;
    return volumeFineSource(world).rgb;
}
vec3 volumeFineWorld(vec3 projectionPoint,float depth) {
    vec3 p=vCounts.z!=0 ? projectionPoint*(depth/-projectionPoint.z) : vec3(projectionPoint.xy,-depth);
    return (vInverseView*vec4(p,1)).xyz;
}
vec3 volumeFineColumnContribution(ivec2 column,int z,float end) {
    vec4 start=texelFetch(uVolumePrefix,ivec3(column,z),0);
    if(end==volumeBoundary(z+1))return texelFetch(uVolumePrefix,ivec3(column,z+1),0).rgb-start.rgb;
    vec4 q=texelFetch(uVolumeSource,ivec3(column,z),0);
    return start.a*q.rgb*volumeIntegralWeight(q.a,volumeSegment(column,z,end));
}
vec3 volumeFineLampWithScattering(uint index,vec3 world,vec3 scattering) {
    vec3 p=(vView*vec4(world,1)).xyz;
    vec3 V=vCounts.z!=0 ? normalize((vInverseView*vec4(-p,0)).xyz) : normalize((vInverseView*vec4(0,0,1,0)).xyz);
    return volumeFineFiniteSource(scattering*volumeLightRadiance(index,world,V,-p.z));
}
vec3 volumeFineLamp(uint index,vec3 world,uint mediumCandidates) {
    vec3 p=(vView*vec4(world,1)).xyz;
    vec3 V=vCounts.z!=0 ? normalize((vInverseView*vec4(-p,0)).xyz) : normalize((vInverseView*vec4(0,0,1,0)).xyz);
    vec3 radiance=volumeLightRadiance(index,world,V,-p.z);
    // Diagnostic frames still evaluate the medium to retain all original counters.
    if(vDirtyHistory.w==0 && all(equal(radiance,vec3(0))))return vec3(0);
    vec4 medium;vec3 emission;volumeMediumAtMasked(world,mediumCandidates,medium,emission);
    return volumeFineFiniteSource(medium.rgb*radiance);
}
uvec3 volumeFineIntervals(ivec2 column) {
    uvec3 mask;
    for(int i=0;i<3;i++) {
        uvec4 bytes=uvec4(texelFetch(uVolumePrefix,ivec3(column,vGrid.z+2+i),0));
        mask[i]=bytes.x|(bytes.y<<8)|(bytes.z<<16)|(bytes.w<<24);
    }
    return mask;
}
vec4 volumeRefineNativeRay(vec2 pixel,float depth,vec4 coarse) {
    if(uLightingEnabled==0||uLightHeader.y==0u||uVolumeSurfaceGuideEnabled==0)return coarse;
    float end=clamp(depth,0.0,vExtentDepth.w);
    vec2 coordinate=vec2(volumeColumnCoordinate(pixel.x,vGrid.x,int(vExtentDepth.x)),
        volumeColumnCoordinate(pixel.y,vGrid.y,int(vExtentDepth.y)));
    ivec2 ca=ivec2(floor(coordinate)),cb=min(ca+1,vGrid.xy-1);
    uvec3 intervals=uvec3(0);int first=vGrid.z,last=-1;
    // The lower column's selector includes one full tile of ray support, which
    // contains this native ray and the four reconstruction columns.
    intervals=volumeFineIntervals(ca);
    for(int i=0;i<3;i++)if(intervals[i]!=0u) {
        first=min(first,32*i+findLSB(intervals[i]));last=max(last,32*i+findMSB(intervals[i]));
    }
    last=min(last,volumeInterval(end));
    if(last<first)return coarse;
    vec3 rayPoint=volumeProjectionPoint(pixel);
    vec3 origin=volumeFineWorld(rayPoint,0.0),direction=normalize(volumeFineWorld(rayPoint,1.0)-origin);
    float cosine=vCounts.z!=0 ? -rayPoint.z/length(rayPoint) : 1.0;
    ivec2 columns[4]=ivec2[](ca,ivec2(cb.x,ca.y),ivec2(ca.x,cb.y),cb);
    vec4 w=volumeReconstructionWeights(ca,cb,fract(coordinate),end);
    uint selectedSlots=0u;bool overflow=false;
    for(int j=0;j<4;j++) {
        vec4 indices=texelFetch(uVolumePrefix,ivec3(ca,vGrid.z+5+j),0);
        for(int k=0;k<4;k++) {
            if(indices[k]<0.0)overflow=true;
            else if(indices[k]>0.0)selectedSlots|=1u<<uint(4*j+k);
        }
    }
    for(int z=first;z<=last;z++) {
        if((intervals[z/32]&(1u<<uint(z%32)))==0u)continue;
        float a=volumeBoundary(z),b=min(end,volumeBoundary(z+1));if(b<=a)continue;
        float tStart=0.0,sigma=0.0;vec4 columnWeights=vec4(0);
        vec3 columnWorld[4],columnScattering[4];
        for(int j=0;j<4;j++)if(w[j]>0.0) {
            float transmission=texelFetch(uVolumePrefix,ivec3(columns[j],z),0).a;
            float extinction=texelFetch(uVolumeSource,ivec3(columns[j],z),0).a;
            tStart+=w[j]*transmission;
            sigma+=w[j]*extinction;
            columnWeights[j]=w[j]*transmission*volumeIntegralWeight(extinction,volumeSegment(columns[j],z,b));
            columnWorld[j]=volumeWorldPosition(ivec3(columns[j],z));
        }
        vec3 correction=vec3(0);bool sourceReplaced=false;uint nativeMediumCandidates=0u;
        vec3 midpoint=volumeFineWorld(rayPoint,(a+b)*0.5);
        float smoothWeight=tStart*volumeIntegralWeight(sigma,(b-a)/cosine);
        // Importance quadrature for a near inverse-square lamp, in atan(distance/
        // impact). Its Jacobian cancels the radiance peak without resampling every
        // other light in the same cluster.
        const float nodes[8]=float[](0.019855072,0.101666761,0.237233795,0.408282679,0.591717321,0.762766205,0.898333239,0.980144928);
        const float weights[8]=float[](0.050614268,0.111190517,0.156853323,0.181341892,0.181341892,0.156853323,0.111190517,0.050614268);
        uint pendingSlots=selectedSlots;
        uint candidates=overflow ? uLightHeader.y : uint(bitCount(selectedSlots));
        for(uint candidate=0u;candidate<candidates;candidate++) {
            uint i;
            if(overflow)i=uLightHeader.x+candidate;
            else {
                // Walk occupied slots in their original order, including sparse
                // layouts, without a dynamically indexed private lamp array.
                int slot=findLSB(pendingSlots);pendingSlots&=pendingSlots-1u;
                vec4 indices=texelFetch(uVolumePrefix,ivec3(ca,vGrid.z+5+slot/4),0);
                i=uint(int(indices[slot%4])-1);
            }
            if(int(i)==uFineOmittedLight||uVolumeHints[i].intensity<=0.0||uLights[i].colorIntensity.a<=0.0)continue;
            vec3 delta=uLights[i].positionRange.xyz-origin;float along=dot(delta,direction),d=along*cosine;
            float impact=max(0.0001,dot(delta,delta)-along*along);
            float distance=impact+pow(max(max(a-d,d-b),0.0)/cosine,2);
            float radius=volumeNativeRefinementRadius(uLights[i]);
            if(d+radius<a||d-radius>b)continue;
            if(distance>radius*radius) {
                bool columnCore=false;
                for(int j=0;j<4;j++)if(columnWeights[j]>0.0) {
                    vec3 difference=uLights[i].positionRange.xyz-columnWorld[j];
                    columnCore=columnCore||dot(difference,difference)<=radius*radius;
                }
                if(!columnCore)continue;
            }
            if(!sourceReplaced) {
                vec3 nativeA=origin+direction*(a/cosine),nativeB=origin+direction*(b/cosine);
                nativeMediumCandidates=volumeMediumSegmentMask(min(min(nativeA,nativeB),midpoint),max(max(nativeA,nativeB),midpoint));
                // Prefix contains filtered aggregate q, not the instantaneous
                // lamp q below. Replace the actual stored interval once before
                // exchanging current coarse lamp samples for native quadrature.
                // Other lights and emission remain coarse in this local interval;
                // outside selected support the filtered field is left intact.
                for(int j=0;j<4;j++)if(columnWeights[j]>0.0) {
                    // Medium at a reconstruction point is independent of the
                    // selected lamp; keep its FP32 value for this interval.
                    vec4 columnMedium;vec3 columnEmission;
                    volumeMediumAt(columnWorld[j],columnMedium,columnEmission);
                    columnScattering[j]=columnMedium.rgb;
                    correction+=volumeFineColumnSource(columns[j],z,columnWorld[j])*columnWeights[j]
                        -w[j]*volumeFineColumnContribution(columns[j],z,b);
                }
                sourceReplaced=true;
            }
            for(int j=0;j<4;j++)if(columnWeights[j]>0.0)
                correction-=volumeFineLampWithScattering(i,columnWorld[j],columnScattering[j])*columnWeights[j];
            // A lamp can be near a reconstruction column while this native ray
            // misses its core. Remove that column's peak and restore its smooth
            // contribution on this ray instead of retaining transverse aliasing.
            // A spot cone or its caster shadow is not smooth when this ray misses
            // the emitter core. Keep angular quadrature instead of restoring the
            // whole interval from one potentially unoccluded midpoint.
            if(distance>radius*radius && uLights[i].metadata.x!=2) {
                correction+=volumeFineLamp(i,midpoint,nativeMediumCandidates)*smoothWeight;
                continue;
            }
            float scale=sqrt(impact),start=atan((a/cosine-along)/scale),stop=atan((b/cosine-along)/scale);
            int slot=uLights[i].metadata.y;
            bool allocatedSpotShadow=uLights[i].metadata.x==2 && (uVolumeHints[i].flags&1u)!=0u
                && uHasSpotShadow!=0 && slot>=0 && slot<4
                && uSpotShadowMeta[slot].y!=0 && uSpotShadowMeta[slot].x==int(i);
            // PCF visibility is piecewise rather than smooth. Four angular panels
            // resolve thin caster edges without multiplying work for other lamps.
            int panels=allocatedSpotShadow ? 4 : 1;
            for(int panel=0;panel<panels;panel++) {
                float panelStart=mix(start,stop,float(panel)/float(panels));
                float panelStop=mix(start,stop,float(panel+1)/float(panels));
                if(vDirtyHistory.w==0 && uVolumeDiagnosticsEnabled==0 && uLights[i].metadata.x==2) {
                    vec3 first=origin+direction*(along+scale*tan(mix(panelStart,panelStop,nodes[0])));
                    vec3 last=origin+direction*(along+scale*tan(mix(panelStart,panelStop,nodes[7])));
                    if(volumeNativeSpotNodesUnlit(uLights[i],first,last))continue;
                }
                for(int j=0;j<8;j++) {
                    float angle=mix(panelStart,panelStop,nodes[j]),t=along+scale*tan(angle),cosAngle=cos(angle);
                    correction+=volumeFineLamp(i,origin+direction*t,nativeMediumCandidates)*tStart*exp(-sigma*(t-a/cosine))
                        *((panelStop-panelStart)*weights[j]*scale/(cosAngle*cosAngle));
                }
            }
        }
        coarse.rgb+=correction;
    }
    if(any(isnan(coarse))||any(isinf(coarse))) {
        if(vDirtyHistory.w!=0)atomicAdd(diagnostics[0],1u);
        return vec4(0);
    }
    if(any(greaterThan(coarse.rgb,vec3(32768)))) {
        if(vDirtyHistory.w!=0)atomicAdd(diagnostics[3],1u);
        coarse.rgb=min(coarse.rgb,vec3(32768));
    }
    coarse.rgb=max(coarse.rgb,vec3(0));return coarse;
}
