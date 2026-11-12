#version 460 core

// Screen-space contact shadows (#9).
//
// Bounded short march against the single-sample camera scene depth. This is a
// screen-space approximation: it can only see occluders represented by the
// visible depth buffer, so geometry hidden behind the visible surface casts no
// contact shadow. A tap that leaves the raster is skipped rather than clamped
// to the border; a receiver whose entire light ray leaves the screen therefore
// keeps the fully lit source colour (the documented off-screen fallback).
//
// The fragment reconstructs its view-space position from depth, derives a
// geometric normal with screen-space derivatives, rejects receivers facing away
// from the light, then marches toward the light in view space. A tap occludes
// when the sampled scene surface is in front of the ray point by more than a
// small bias and no farther than the configured thickness. The result is
// premultiplied darkening for blendFunc(GL_ZERO, GL_ONE_MINUS_SRC_COLOR):
// dst' = dst * (1 - rgb). Alpha stays 0 so the copied source alpha survives.
//
// Coordinates: view space is right-handed with the camera looking down -Z.
// uLightDirection is the view-space direction the light travels (normalized),
// so the march direction toward the light is -uLightDirection. uProjection is
// the jittered projection that produced the current colour/depth pair.

uniform sampler2D uSceneDepth;
uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform mat4 uProjection;
uniform vec3 uLightDirection;
uniform float uDistance;
uniform float uThickness;
uniform float uStrength;
uniform int uSteps;

layout(location = 0) out vec4 FragColor;

const int CONTACT_SHADOW_MAX_STEPS = 32;
const float DEPTH_EPSILON = 1.0e-6;

// Homogeneous view position of a depth-buffer sample.
vec4 viewPositionAt(vec2 uv, float depth) {
    vec4 clip = uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (!(clip.w > DEPTH_EPSILON)) return vec4(0.0);
    return vec4(clip.xyz / clip.w, 1.0);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(uSceneDepth, pixel, 0).r;
    if (!(depth < 1.0) || !(uStrength > 0.0)) {
        FragColor = vec4(0.0);
        return;
    }

    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec4 view = viewPositionAt(uv, depth);
    if (!(view.w > 0.0) || !(view.z < 0.0)) {
        FragColor = vec4(0.0);
        return;
    }
    vec3 viewPosition = view.xyz;

    // Geometric receiver normal from the reconstructed position. The sign of
    // the cross product is implementation-defined, so orient it toward the eye
    // and use it only to reject back-facing receivers (and their self-shadow
    // taps) instead of trusting a particular winding.
    vec3 normal = cross(dFdx(viewPosition), dFdy(viewPosition));
    float normalLengthSquared = dot(normal, normal);
    if (!(normalLengthSquared > 1.0e-12)) {
        FragColor = vec4(0.0);
        return;
    }
    normal *= inversesqrt(normalLengthSquared);
    if (dot(normal, -viewPosition) < 0.0) normal = -normal;

    vec3 toLight = -uLightDirection;
    if (!(dot(normal, toLight) > 0.0)) {
        FragColor = vec4(0.0);
        return;
    }

    int steps = clamp(uSteps, 1, CONTACT_SHADOW_MAX_STEPS);
    float distance = max(uDistance, 0.0);
    float thickness = clamp(uThickness, 0.0, distance);
    if (!(distance > 0.0) || !(thickness > 0.0)) {
        FragColor = vec4(0.0);
        return;
    }

    // Small bias relative to the contact band, plus a normal offset so the
    // receiver never touches its own depth slice at the first tap.
    float bias = max(thickness * 0.02, 1.0e-4);
    vec3 origin = viewPosition + normal * min(thickness * 0.05, distance * 0.01);

    float occlusion = 0.0;
    for (int step = 1; step <= CONTACT_SHADOW_MAX_STEPS; step++) {
        if (step > steps) break;
        float along = float(step) / float(steps);
        vec3 ray = origin + toLight * (distance * along);

        vec4 clip = uProjection * vec4(ray, 1.0);
        if (!(clip.w > DEPTH_EPSILON)) continue;
        vec2 sampleUv = clip.xy / clip.w * 0.5 + 0.5;
        // Off-screen taps are skipped, never clamped to the border: the
        // fallback for a ray that leaves the raster is "no occlusion".
        if (any(lessThan(sampleUv, vec2(0.0)))
                || any(greaterThan(sampleUv, vec2(1.0)))) {
            continue;
        }

        ivec2 samplePixel = clamp(ivec2(sampleUv * uViewportSize), ivec2(0),
                ivec2(uViewportSize) - ivec2(1));
        float sampleDepth = texelFetch(uSceneDepth, samplePixel, 0).r;
        if (!(sampleDepth < 1.0)) continue;

        vec4 sampleView = viewPositionAt(sampleUv, sampleDepth);
        if (!(sampleView.w > 0.0)) continue;

        // Positive separation means the sampled visible surface is closer to
        // the camera than the ray point, i.e. the ray has passed behind it.
        float separation = -ray.z - (-sampleView.z);
        if (separation > bias && separation <= thickness) {
            // Early-out weighted by the travelled fraction keeps the contact
            // band strongest at the receiver and bounded to one march.
            occlusion = 1.0 - 0.65 * along;
            break;
        }
    }

    FragColor = vec4(vec3(clamp(uStrength, 0.0, 1.0) * occlusion), 0.0);
}
