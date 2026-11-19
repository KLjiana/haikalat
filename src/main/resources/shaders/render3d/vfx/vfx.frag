#version 460 core
// HAIKALAT_VOLUME_FOG_CONTRACT

uniform vec4 uColor;
uniform sampler2D uTexture;
uniform sampler2D uNoise;
uniform int uMaskMode;
uniform int uAdditive;
uniform float uEmissiveIntensity;
uniform float uAlphaCutoff;
uniform sampler2D uSceneDepth;
uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform float uSoftParticleDistance;
uniform int uDepthEnabled;
uniform float uFlipbookBlend;
uniform int uFlipbook;
uniform vec4 uUvRegion;
uniform vec4 uNextUvRegion;

uniform float uTime;
uniform int uMesh;
uniform int uUvFlowEnabled;
uniform vec4 uUvFlow;
uniform float uUvNoiseSeed;

uniform int uDissolveEnabled;
uniform float uDissolveProgress;
uniform vec4 uDissolveParams;
uniform vec3 uDissolveEdgeColor;

uniform int uRimEnabled;
uniform vec3 uRimColor;
uniform float uRimIntensity;
uniform float uRimExponent;
uniform vec3 uCameraPosition;

in vec2 vLocalUv;
in vec3 vWorldPosition;
in vec3 vWorldNormal;
flat in int vHasNormals;

layout (location = 0) out vec4 fragColor;

float viewDistance(vec2 uv, float depth) {
    vec4 view = uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return abs(view.z / view.w);
}

vec2 noiseCoordinate(vec2 localUv, float seed, float scale) {
    // Mesh VFX sample the linear noise in world space; billboards use their
    // local UV so a moving camera cannot make the same particle re-dissolve.
    // This choice must depend on the primitive type, never on whether rim is
    // enabled: a zero-strength rim term may not move the dissolve threshold.
    vec2 base = uMesh != 0
            ? vec2(vWorldPosition.x + vWorldPosition.y, vWorldPosition.z)
            : localUv;
    return base * scale + vec2(seed * 0.137, seed * 0.311);
}

void main() {
    vec2 local = vLocalUv;
    if (uUvFlowEnabled != 0) {
        local += uUvFlow.xy * uTime;
        float noise = textureLod(uNoise, noiseCoordinate(local, uUvNoiseSeed, uUvFlow.z), 0.0).r;
        local += (noise * 2.0 - 1.0) * uUvFlow.w;
    }
    // Keep the sample footprint inside the current atlas cell. The half-texel
    // inset is applied in cell-local space so a padded or unpadded atlas cannot
    // leak the neighbouring frame through bilinear filtering or mip selection.
    vec2 cellSize = max(uUvRegion.zw - uUvRegion.xy, vec2(1.0e-6));
    vec2 texel = 1.0 / vec2(max(textureSize(uTexture, 0), ivec2(1)));
    vec2 inset = clamp(0.5 * texel / cellSize, vec2(0.0), vec2(0.5));
    local = clamp(local, inset, vec2(1.0) - inset);

    vec2 currentUv = mix(uUvRegion.xy, uUvRegion.zw, local);
    vec2 nextUv = mix(uNextUvRegion.xy, uNextUvRegion.zw, local);
    vec4 sampled;
    if (uFlipbook != 0) {
        // An unpadded frame atlas must not average neighbouring cells through
        // mip selection; use the base level and the explicit half-texel inset.
        sampled = mix(textureLod(uTexture, currentUv, 0.0),
                textureLod(uTexture, nextUv, 0.0), uFlipbookBlend);
    } else {
        sampled = mix(texture(uTexture, currentUv),
                texture(uTexture, nextUv), uFlipbookBlend);
    }
    float mask = 1.0;
    vec3 sampledColor = vec3(1.0);
    if (uMaskMode == 1 || uMaskMode == 2 || uMaskMode == 3) {
        mask = sampled.r;
    } else if (uMaskMode == 4) {
        mask = sampled.a;
        sampledColor = sampled.rgb;
    }
    float alpha = uColor.a * mask;

    float edgeGlow = 0.0;
    if (uDissolveEnabled != 0) {
        if (uDissolveProgress >= 1.0) discard;
        if (uDissolveProgress > 0.0) {
            float noise = textureLod(uNoise,
                    noiseCoordinate(local, uDissolveParams.w, uDissolveParams.z), 0.0).r;
            if (noise < uDissolveProgress) discard;
            if (uDissolveParams.x > 0.0) {
                edgeGlow = 1.0 - clamp((noise - uDissolveProgress) / uDissolveParams.x, 0.0, 1.0);
            }
        }
    }

    if (uDepthEnabled != 0) {
        vec2 screenUv = gl_FragCoord.xy / uViewportSize;
        float sceneDistance = viewDistance(screenUv, texture(uSceneDepth, screenUv).r);
        float fragmentDistance = viewDistance(screenUv, gl_FragCoord.z);
        float separation = sceneDistance - fragmentDistance;
        if (separation <= 0.0) discard;
        if (uSoftParticleDistance > 0.0) {
            alpha *= clamp(separation / uSoftParticleDistance, 0.0, 1.0);
        }
    }
    if (alpha < uAlphaCutoff) discard;

    vec3 glow = vec3(0.0);
    if (uRimEnabled != 0 && vHasNormals != 0) {
        vec3 normal = normalize(vWorldNormal);
        vec3 toCamera = normalize(uCameraPosition - vWorldPosition);
        // Double-sided meshes flip the back-face normal toward the viewer before
        // the artistic rim weight is evaluated.
        if (dot(normal, toCamera) < 0.0) normal = -normal;
        float weight = pow(1.0 - clamp(dot(normal, toCamera), 0.0, 1.0), uRimExponent);
        glow += uRimColor * (uRimIntensity * weight);
    }
    glow += uDissolveEdgeColor * (uDissolveParams.y * edgeGlow);
    glow *= mask;

    vec3 rgb = sampledColor * uColor.rgb * uEmissiveIntensity + glow;
    if (uAdditive != 0) rgb *= alpha;
    fragColor = vec4(rgb, alpha);
#ifdef HAIKALAT_VOLUME_FOG
    fragColor = volumeFogRasterSurface(fragColor,uAdditive != 0);
#endif
}
