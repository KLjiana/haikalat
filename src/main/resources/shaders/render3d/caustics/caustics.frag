#version 460 core

// Bounded water-caustics contribution (S3 #36).
//
// ARTISTIC APPROXIMATION, NOT A PHYSICAL CAUSTICS SOLVER. The pattern is a
// deterministic three-wave interference field (or an optional alpha-mask
// texture) projected from a finite horizontal water rectangle along the main
// directional light onto a receiver surface. There is no photon mapping, no
// refraction/reflection solve, no underwater volume and no dispersion. The
// projection is a screen-space lookup driven entirely by the shared scene
// depth, so it can only reject surfaces represented by that depth buffer.
//
// Coordinates: world space is right-handed, one unit is one metre, Y is up.
// uLightDirection is the direction the main light travels (normalized, Y<0).
// The water plane is horizontal at uWaterLevel.
//
// Rejection chain (all from the reconstructed depth):
//   1. background depth / far plane                         -> no contribution
//   2. surface below water by at least uDepthBias             -> else reject
//   3. light-path distance <= uMaximumProjectionDistance      -> else reject
//   4. projected water entry inside the finite water rectangle -> else reject
//   5. receiver height inside [min,max] with edge fades       -> else reject
//   6. |normal.y| >= uMinimumReceiverUpDot (floor, not wall) -> else reject
// The pass writes additive light only (alpha 0) and never writes depth.
//
// The pattern is a pure function of world position and uTimeSeconds. No frame
// counter, random number, derivative noise or temporal accumulation is used, so
// a fixed time produces the same pattern.

#ifndef CAUSTICS_MSAA
uniform sampler2D uSceneDepth;
#else
uniform sampler2DMS uSceneDepth;
#endif

uniform sampler2D uPatternTexture;
uniform sampler2D uShadowMap;
uniform int uShadowCascadeCount;
uniform mat4 uShadowMatrices[4];
uniform float uShadowSplits[4];
uniform vec4 uShadowTiles[4];
uniform float uShadowBias;
uniform float uShadowNormalBias;
uniform float uShadowBlendRange;
uniform int uShadowRadius;

uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform vec3 uLightDirection;
uniform float uTimeSeconds;
uniform float uIntensity;
uniform vec3 uColor;
uniform float uWorldScale;
uniform float uAnimationSpeed;
uniform float uWaterLevel;
uniform float uWaterMinX;
uniform float uWaterMinZ;
uniform float uWaterMaxX;
uniform float uWaterMaxZ;
uniform float uReceiverMinimumHeight;
uniform float uReceiverMaximumHeight;
uniform float uDepthBias;
uniform float uMaximumProjectionDistance;
uniform float uDistanceFalloff;
uniform float uEdgeFadeDistance;
uniform float uMinimumReceiverUpDot;
uniform int uHasPatternTexture;

layout(location = 0) out vec4 FragColor;

const float TWO_PI = 6.283185307179586;
const float RANGE_FADE_START = 0.8;
const float WAVE_X1 = 0.83;
const float WAVE_Z1 = 0.37;
const float WAVE_X2 = -0.44;
const float WAVE_Z2 = 0.79;
const float WAVE2_SCALE = 1.21;
const float WAVE2_PHASE = 1.7;
const float WAVE_X3 = 0.17;
const float WAVE_Z3 = -0.91;
const float WAVE3_SCALE = 0.87;
const float WAVE3_PHASE = 3.1;
const float RIDGE_SHARPNESS = 1.8;
const float RIDGE_POWER = 3.0;

float sampleSceneDepth(ivec2 pixel) {
    return texelFetch(uSceneDepth, pixel, 0).r;
}

// Deterministic interference ridges in pattern-tile space.
float causticPattern(vec2 q, float phase) {
    float wave1 = sin(TWO_PI * (WAVE_X1 * q.x + WAVE_Z1 * q.y) + phase);
    float wave2 = sin(TWO_PI * (WAVE_X2 * q.x + WAVE_Z2 * q.y) * WAVE2_SCALE
            - phase * 0.73 + WAVE2_PHASE);
    float wave3 = sin(TWO_PI * (WAVE_X3 * q.x + WAVE_Z3 * q.y) * WAVE3_SCALE
            + phase * 1.31 + WAVE3_PHASE);
    float sum = (wave1 + wave2 + wave3) * (1.0 / 3.0);
    float ridge = clamp(1.0 - abs(sum) * RIDGE_SHARPNESS, 0.0, 1.0);
    return pow(ridge, RIDGE_POWER);
}

float bandFade(float value, float minimum, float maximum, float requestedFade) {
    float width = min(max(requestedFade, 0.0), 0.5 * (maximum - minimum));
    if (width <= 0.0) {
        return value >= minimum && value <= maximum ? 1.0 : 0.0;
    }
    return smoothstep(minimum, minimum + width, value)
            * (1.0 - smoothstep(maximum - width, maximum, value));
}

float rectangleFade(float distanceToEdge, float requestedFade, float minimumExtent) {
    if (!(distanceToEdge > 0.0)) return 0.0;
    float width = min(max(requestedFade, 0.0), 0.5 * minimumExtent);
    return width <= 0.0 ? 1.0 : smoothstep(0.0, width, distanceToEdge);
}

float cascadeVisibility(int cascade, vec3 world, vec3 normal, vec3 dx, vec3 dy) {
    mat4 lightSpace = uShadowMatrices[cascade];
    vec3 p = (lightSpace * vec4(world + normal * uShadowNormalBias, 1.0)).xyz * 0.5 + 0.5;
    if (any(lessThanEqual(p, vec3(0.0))) || any(greaterThanEqual(p, vec3(1.0)))) return 1.0;
    vec4 tile = uShadowTiles[cascade];
    vec2 tileSize = tile.zw - tile.xy;
    vec2 uv = tile.xy + p.xy * tileSize;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    vec2 lower = tile.xy + 0.5 * texel;
    vec2 upper = tile.zw - 0.5 * texel;
    // Orthographic shadow coordinates; compensate each tap against the receiver plane.
    dx = (lightSpace * vec4(dx, 0.0)).xyz * vec3(tileSize, 1.0) * 0.5;
    dy = (lightSpace * vec4(dy, 0.0)).xyz * vec3(tileSize, 1.0) * 0.5;
    float determinant = dx.x * dy.y - dx.y * dy.x;
    vec2 gradient = abs(determinant) < 1.0e-10 ? vec2(0.0)
            : vec2(dx.z * dy.y - dy.z * dx.y, dy.z * dx.x - dx.z * dy.x) / determinant;
    float bias = max(uShadowBias * (1.0 - dot(normal, -uLightDirection)), uShadowBias * 0.25)
            + min(dot(abs(gradient), texel) * 0.5, 0.01);
    float lit = 0.0;
    int radius = clamp(uShadowRadius, 0, 2);
    for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
        vec2 tap = clamp(uv + vec2(x, y) * texel, lower, upper);
        float receiver = p.z + dot(gradient, tap - uv) - bias;
        lit += receiver <= textureLod(uShadowMap, tap, 0.0).r ? 1.0 : 0.0;
    }
    float width = float(radius * 2 + 1);
    return lit / (width * width);
}

float directionalVisibility(float viewDepth, vec3 world, vec3 normal, vec3 dx, vec3 dy) {
    if (uShadowCascadeCount == 0) return 1.0;
    int cascade = 0;
    while (cascade < uShadowCascadeCount - 1 && viewDepth > uShadowSplits[cascade]) cascade++;
    float current = cascadeVisibility(cascade, world, normal, dx, dy);
    if (cascade == uShadowCascadeCount - 1 || uShadowBlendRange <= 0.0) return current;
    float start = cascade == 0 ? 0.0 : uShadowSplits[cascade - 1];
    float end = uShadowSplits[cascade];
    float weight = smoothstep(end - max((end - start) * uShadowBlendRange, 1.0e-4), end, viewDepth);
    if (weight <= 0.0) return current;
    return mix(current, cascadeVisibility(cascade + 1, world, normal, dx, dy), weight);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = sampleSceneDepth(pixel);
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    view /= view.w;
    vec3 world = (uInverseView * vec4(view.xyz, 1.0)).xyz;

    // Screen-space geometric normal from the reconstructed position. The sign
    // is arbitrary; caustic receivers are selected by the absolute up axis so
    // both winding conventions (and mirrored projections) work.
    vec3 dx = dFdx(world);
    vec3 dy = dFdy(world);
    if (!(depth < 1.0)) {
        FragColor = vec4(0.0);
        return;
    }
    vec3 normal = cross(dx, dy);
    float normalLengthSquared = dot(normal, normal);
    if (!(normalLengthSquared > 1.0e-12)) {
        FragColor = vec4(0.0);
        return;
    }
    float upDot = abs(normal.y) * inversesqrt(normalLengthSquared);
    if (!(upDot >= uMinimumReceiverUpDot)) {
        FragColor = vec4(0.0);
        return;
    }

    float thickness = uWaterLevel - world.y;
    if (!(thickness > uDepthBias)) {
        FragColor = vec4(0.0);
        return;
    }
    float travel = thickness / max(-uLightDirection.y, 1.0e-4);
    if (!(travel <= uMaximumProjectionDistance)) {
        FragColor = vec4(0.0);
        return;
    }

    // Trace the receiver back along the light to its water entry point.
    vec3 water = world - uLightDirection * travel;

    float edgeDistance = min(
            min(water.x - uWaterMinX, uWaterMaxX - water.x),
            min(water.z - uWaterMinZ, uWaterMaxZ - water.z));
    float regionFade = rectangleFade(edgeDistance, uEdgeFadeDistance,
            min(uWaterMaxX - uWaterMinX, uWaterMaxZ - uWaterMinZ));

    float heightFade = bandFade(world.y, uReceiverMinimumHeight,
            uReceiverMaximumHeight, uEdgeFadeDistance);

    float rangeFade = 1.0 - smoothstep(RANGE_FADE_START * uMaximumProjectionDistance,
            uMaximumProjectionDistance, travel);
    float attenuation = exp(-uDistanceFalloff * travel)
            * rangeFade * regionFade * heightFade;

    float phase = TWO_PI * uAnimationSpeed * uTimeSeconds;
    vec2 patternCoordinates = water.xz / max(uWorldScale, 1.0e-4);
    float pattern = causticPattern(patternCoordinates, phase);
    if (uHasPatternTexture != 0) {
        // Optional art mask: alpha channel, linear data, tiled in pattern space.
        pattern *= texture(uPatternTexture, patternCoordinates).a;
    }

    float amount = uIntensity * pattern * attenuation;
    if (!(amount > 0.0)) {
        FragColor = vec4(0.0);
        return;
    }
    vec3 upNormal = normalize(normal) * (normal.y < 0.0 ? -1.0 : 1.0);
    float visibility = directionalVisibility(-view.z, world, upNormal, dx, dy);
    FragColor = vec4(uColor * (amount * visibility), 0.0);
}
