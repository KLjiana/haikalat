#define CLOUD_SHADOW_OCTAVES 3
#define CLOUD_PROFILE_EDGE 0.25

// --- shared #10 cloud-shadow mask (identical source text, see the shader test) ---
float cloudShadowHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float cloudShadowNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 local = fract(p);
    vec2 weight = local * local * (3.0 - 2.0 * local);
    float a = cloudShadowHash(cell);
    float b = cloudShadowHash(cell + vec2(1.0, 0.0));
    float c = cloudShadowHash(cell + vec2(0.0, 1.0));
    float d = cloudShadowHash(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, weight.x), mix(c, d, weight.x), weight.y);
}

float cloudShadowDensity(vec2 p) {
    float density = 0.0;
    float amplitude = 0.5;
    for (int octave = 0; octave < CLOUD_SHADOW_OCTAVES; octave++) {
        density += amplitude * cloudShadowNoise(p);
        p = p * 2.03 + vec2(17.1, 9.2);
        amplitude *= 0.5;
    }
    return density / 0.875;
}
// --- end shared #10 cloud-shadow mask ---

// World-xz coverage of the shared cloud field. The wind offset and threshold /
// softness mapping are the same expressions as cloudShadowVisibility() in
// pbr-forward.frag; only the projection differs (this layer uses the cloud's
// own world xz instead of a light-projected receiver plane).
float cloudLayerMask(vec2 worldXZ) {
    vec2 projected = worldXZ + vec2(0.8, 0.6) * (uCloudShadowTime * max(uCloudShadowSpeed, 0.0));
    float scale = max(uCloudShadowScale, 1.0e-4);
    float noise = cloudShadowDensity(projected * scale);
    float coverage = clamp(uCloudShadowCoverage, 0.0, 1.0);
    float threshold = mix(0.78, 0.22, coverage);
    float edge = mix(0.004, 0.28, clamp(uCloudShadowSoftness, 0.0, 1.0));
    return smoothstep(threshold - edge, threshold + edge, noise);
}

float cloudLayerProfile(float y) {
    float span = max(uLayerTop - uLayerBase, 1.0e-3);
    float t = (y - uLayerBase) / span;
    return smoothstep(0.0, CLOUD_PROFILE_EDGE, t)
            * (1.0 - smoothstep(1.0 - CLOUD_PROFILE_EDGE, 1.0, t));
}

vec4 cloudShapeHash(vec4 cell) {
    vec4 p = fract(cell * 0.1031);
    return fract(p * (19.31 + p) * (7.17 + p));
}

// Trilinear value noise, with the four corners of each z slice packed together.
// The smooth interpolation leaves no cell boundaries in the density field.
float cloudShapeNoise(vec3 p) {
    vec4 cell = vec4(dot(floor(p), vec3(1.0, 59.0, 113.0))) + vec4(0.0, 1.0, 59.0, 60.0);
    vec3 w = fract(p);
    w *= w * (3.0 - 2.0 * w);
    vec4 corners = mix(cloudShapeHash(cell), cloudShapeHash(cell + 113.0), w.z);
    vec2 rows = mix(corners.xz, corners.yw, w.x);
    return mix(rows.x, rows.y, w.y);
}

// Weather controls the threshold, not the density of each vertical column.
// Two low-frequency 3D bands form the body before optional edge erosion.
float cloudBody(vec2 advected, float height, float coverage) {
    vec3 p = vec3(advected * (0.95 * max(uCloudShadowScale, 1.0e-4)), height * 2.35);
    float broad = cloudShapeNoise(p + vec3(11.4, 37.5, 19.3));
    float lobes = cloudShapeNoise(p.yxz * vec3(-2.07, 2.07, 1.85) + vec3(21.3, 9.2, 7.1));
    float body = mix(broad, lobes, 0.30);
    float cutoff = mix(0.69, 0.24, coverage) + 0.09 * height * height;
    float envelope = smoothstep(0.0, 0.25, coverage) * mix(0.62, 1.0, coverage);
    return smoothstep(cutoff, cutoff + 0.24, body) * envelope;
}

float cloudDensityAt(vec3 worldPosition, float sampleSpacing, vec3 marchDirection) {
    float mask = cloudLayerMask(worldPosition.xz);
    if (!(mask > 0.0)) {
        return 0.0;
    }
    float profile = cloudLayerProfile(worldPosition.y);
    if (!(profile > 0.0)) {
        return 0.0;
    }
    vec2 advected = worldPosition.xz
            + vec2(0.8, 0.6) * (uCloudShadowTime * max(uCloudShadowSpeed, 0.0));
    if (uCloudBodyStrength > 0.0) {
        float height = clamp((worldPosition.y - uLayerBase) / (uLayerTop - uLayerBase), 0.0, 1.0);
        mask = mix(mask, cloudBody(advected, height, mask), uCloudBodyStrength);
    }
    if (uShapeDetail > 0.0 && mask > 0.0) {
        float horizontalFrequency = 2.8 * max(uCloudShadowScale, 1.0e-4);
        float verticalFrequency = 2.6 / (uLayerTop - uLayerBase);
        vec3 coord = vec3(advected * horizontalFrequency,
                (worldPosition.y - uLayerBase) * verticalFrequency + 17.0);
        float shape = cloudShapeNoise(coord);
        // Suppress sub-step structure before it can turn into streaks on long rays.
        float cellSpan = sampleSpacing * length(marchDirection.xzy
                * vec3(horizontalFrequency, horizontalFrequency, verticalFrequency));
        float detailWeight = 0.66 * (1.0 - smoothstep(0.20, 0.75, cellSpan * 3.4));
        if (detailWeight > 0.0) {
            float detail = cloudShapeNoise(coord * 3.4 + vec3(23.7, 41.3, 12.1));
            shape = clamp(shape + (detail - 0.5) * detailWeight, 0.0, 1.0);
        }
        // Carve thin parts first, leaving the densest parts of the shared mask intact.
        float erosion = uShapeDetail * mix(0.04, 0.78, shape);
        mask = max(mask - erosion, 0.0) / (1.0 - erosion);
    }
    return uDensity * (uAbsorption + uScattering) * mask * profile;
}
