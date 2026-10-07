// std140 layout is fixed by VolumetricPassBuilder and its GL coordinate tests.
layout(std140,binding=7) uniform VolumeParameters {
    mat4 vInverseProjection;
    mat4 vInverseView;
    mat4 vView;
    mat4 vViewProjection;
    mat4 vPreviousViewProjection;
    mat4 vPreviousView;
    mat4 vPreviousInverseView;
    mat4 vPreviousProjection;
    ivec4 vGrid;                 // xyz dimensions, tile pixels
    vec4 vExtentDepth;           // width, height, near, finite fog far
    vec4 vAlbedoExtinction;
    vec4 vEmissionHeight;
    vec4 vSettings;              // height falloff, g, successful time, history weight
    vec4 vWindSeed;              // wind xyz, seed bits
    ivec4 vCounts;               // local count, emission allocated, perspective, history valid
    vec4 vPreviousDepthTime;
    vec4 vJitterHistory;         // current/previous depth phase, extinction absolute/relative tolerance
    ivec4 vDirtyHistory;         // domain count, full rejection reason, accumulation enabled, diagnostic counters
    vec4 vHistoryConfidence;    // source consistency tolerance, view cosine minimum, shadow refit, reserved
    vec4 vSpatialJitter;        // current XY phase, previous successful XY phase
    ivec4 vNativeReactive;       // native medium count, full native light response, remaining fields reserved
    vec4 vNativeMediumDomains[32]; // up to 8 previous + 8 current exact medium bounds
};

float volumeBoundary(int k) {
    if (k == 0) return 0.0;
    if (k == vGrid.z) return vExtentDepth.w;
    return vCounts.z != 0
        ? vExtentDepth.z * pow(vExtentDepth.w/vExtentDepth.z,float(k-1)/float(vGrid.z-1))
        : vExtentDepth.w * float(k)/float(vGrid.z);
}
float volumeCenter(int z) { return 0.5*(volumeBoundary(z)+volumeBoundary(z+1)); }
float volumeSampleDepth(int z) { return mix(volumeBoundary(z),volumeBoundary(z+1),vJitterHistory.x); }
vec2 volumePixel(ivec2 c) {
    vec2 a=vec2(c*vGrid.w);
    return 0.5*(a+min(a+vec2(vGrid.w),vExtentDepth.xy));
}
vec3 volumeProjectionPoint(vec2 pixel) {
    vec4 p=vInverseProjection*vec4(2.0*pixel/vExtentDepth.xy-1.0,-1.0,1.0);
    return p.xyz/p.w;
}
vec3 volumeViewPosition(ivec3 p) {
    vec2 pixel=volumePixel(p.xy);
    // Sampling moves inside the cell; its stable boundaries and integration ray stay fixed.
    // XY coverage prevents a narrow light/shadow feature from missing the same column forever.
    vec2 size=min(vec2(vGrid.w),vExtentDepth.xy-vec2(p.xy*vGrid.w));
    pixel+=(vSpatialJitter.xy-0.5)*size;
    vec3 r=volumeProjectionPoint(pixel);
    float d=volumeSampleDepth(p.z);
    return vCounts.z != 0 ? r*(d/-r.z) : vec3(r.xy,-d);
}
vec3 volumeWorldPosition(ivec3 p) { return (vInverseView*vec4(volumeViewPosition(p),1.0)).xyz; }
float volumeRayCosine(ivec2 c) {
    return vCounts.z != 0 ? abs(normalize(volumeProjectionPoint(volumePixel(c))).z) : 1.0;
}
float volumeSegment(ivec2 c,int z,float endpoint) {
    return max(0.0,min(endpoint,volumeBoundary(z+1))-volumeBoundary(z))/volumeRayCosine(c);
}
int volumeInterval(float d) {
    if(d<=0.0)return 0;
    if(d>=vExtentDepth.w)return vGrid.z-1;
    if(vCounts.z!=0 && d<vExtentDepth.z)return 0;
    float coordinate=vCounts.z!=0
        ? 1.0+log2(d/vExtentDepth.z)/log2(vExtentDepth.w/vExtentDepth.z)*float(vGrid.z-1)
        : d/vExtentDepth.w*float(vGrid.z);
    int z=clamp(int(floor(coordinate)),0,vGrid.z-1);
    // Retain the physical boundary convention despite inverse/log rounding.
    while(z>0 && d<volumeBoundary(z))--z;
    while(z+1<vGrid.z && d>=volumeBoundary(z+1))++z;
    return z;
}
// Stable near-vacuum limit; do not divide by tiny sigma or subtract almost equal floats.
float volumeIntegralWeight(float sigma,float ds) {
    float tau=sigma*ds;
    if (tau < 0.001) return ds*(1.0-tau*0.5+tau*tau/6.0);
    return (1.0-exp(-tau))/sigma;
}
vec4 volumeIntegrateSegment(vec4 prefix,vec4 q,float ds) {
    vec3 s=prefix.rgb+prefix.a*q.rgb*volumeIntegralWeight(q.a,ds);
    return vec4(s,prefix.a*exp(-q.a*ds));
}
float volumePhase(float g,float mu) {
    float denominator=max(0.01,1.0+g*g-2.0*g*mu);
    return (1.0-g*g)/(12.566370614359172*denominator*sqrt(denominator));
}
