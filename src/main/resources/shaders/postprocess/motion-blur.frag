#version 460 core

in vec2 vUv;
layout (location = 0) out vec4 fragColor;

uniform sampler2D uColor;
uniform sampler2D uVelocity;
uniform sampler2D uValidity;
uniform vec2 uJitter;
uniform vec2 uViewportSize;
uniform float uShutterFraction;
uniform float uMaxRadiusPixels;

const int TAP_COUNT = 12;
const float MINIMUM_MOTION_PIXELS = 0.5;
const float MAXIMUM_ABSOLUTE_VELOCITY = 1.0e4;

bool finite2(vec2 value) {
    return all(equal(value, value)) && all(lessThan(abs(value), vec2(1.0e12)));
}

void main() {
    vec2 viewport = max(uViewportSize, vec2(1.0));
    vec2 rasterUv = clamp(vUv, 0.5 / viewport, 1.0 - 0.5 / viewport);
    vec4 center = texture(uColor, rasterUv);

    // The shared velocity is produced with the stable projection.  Evaluate it
    // at the stable position by removing this frame's raster jitter instead of
    // adding jitter to the reconstruction.
    vec2 stableUv = clamp(rasterUv - uJitter, 0.5 / viewport, 1.0 - 0.5 / viewport);
    vec2 velocity = texture(uVelocity, stableUv).rg;
    float validity = texture(uValidity, stableUv).r;

    // Camera cuts, first frames and invalid deformation write validity 0 and
    // zero velocity.  Without valid velocity the pass is an exact identity:
    // there is no history to smear from another shot.
    if (validity < 0.5 || !finite2(velocity)
            || any(greaterThan(abs(velocity), vec2(MAXIMUM_ABSOLUTE_VELOCITY)))) {
        fragColor = center;
        return;
    }

    vec2 motionPixels = velocity * viewport * uShutterFraction;
    float lengthPixels = length(motionPixels);
    if (!(lengthPixels >= MINIMUM_MOTION_PIXELS)) {
        fragColor = center;
        return;
    }
    // The maximum radius bounds both the gather footprint and its cost.
    if (lengthPixels > uMaxRadiusPixels) {
        motionPixels *= uMaxRadiusPixels / lengthPixels;
    }
    vec2 motionUv = motionPixels / viewport;

    // Bounded backward gather from the current toward the previous position.
    // Every tap stays inside the clamped segment, so the footprint cannot
    // exceed uMaxRadiusPixels.
    vec3 sum = center.rgb;
    float weight = 1.0;
    for (int i = 0; i < TAP_COUNT; ++i) {
        float fraction = (float(i) + 0.5) / float(TAP_COUNT);
        vec2 sampleUv = clamp(stableUv + motionUv * fraction,
                0.5 / viewport, 1.0 - 0.5 / viewport);
        sum += texture(uColor, sampleUv).rgb;
        weight += 1.0;
    }
    fragColor = vec4(sum / weight, center.a);
}
