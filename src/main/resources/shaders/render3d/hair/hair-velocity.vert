#version 460 core

/*
 * Velocity vertex stage for the deterministic hair-strand set (#46 v1).
 *
 * The generated geometry stores the strand centreline, its tangent, the
 * normalized parameter along the strand, the ribbon corner sign and a
 * per-strand random value. This stage duplicates the screen-space expansion of
 * hair-forward.vert exactly: the centre and a short world-space probe along the
 * camera-facing side vector are projected with the current stable (unjittered)
 * view-projection, the pixel difference becomes pixels-per-metre, and the
 * clip-space vertex is offset by `corner * widthPixels / 2`. That duplication is
 * required: a centreline-only pass rasterizes zero-area ribbons and writes
 * nothing.
 *
 * The motion vectors are the *unexpanded* centreline projections. The previous
 * and current clip positions therefore describe the same local strand point, so
 * a static lock and camera cancel exactly. The screen-space ribbon offset is
 * deliberately not part of either projection: it is a pixel-width convention,
 * not world motion, and the previous frame's ribbon edge is not reproducible
 * from the current probe anyway.
 */

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aTangent;
layout(location = 2) in float aStrandParameter;
layout(location = 3) in float aCorner;
layout(location = 4) in float aRandom;

uniform mat4 uCurrentStableViewProjection;
uniform mat4 uPreviousStableViewProjection;
uniform mat4 uModel;
uniform mat4 uPreviousModel;
uniform vec3 uCameraPosition;
uniform vec2 uViewportSize;
uniform float uWidthPixels;
uniform float uMinimumWidthPixels;
uniform float uMaximumWidthPixels;
uniform float uWidthScale;
uniform float uRootRadiusMetres;

out vec4 vCurrentClip;
out vec4 vPreviousClip;
out float vStrandParameter;

void main() {
    vec3 world = (uModel * vec4(aPosition, 1.0)).xyz;
    vec3 previousWorld = (uPreviousModel * vec4(aPosition, 1.0)).xyz;
    vec3 tangent = mat3(uModel) * aTangent;
    float tangentLengthSquared = dot(tangent, tangent);
    tangent = tangentLengthSquared > 1.0e-12 ? tangent * inversesqrt(tangentLengthSquared)
            : vec3(0.0, 1.0, 0.0);

    vec3 toCamera = uCameraPosition - world;
    float viewLengthSquared = dot(toCamera, toCamera);
    vec3 viewDirection = viewLengthSquared > 1.0e-12
            ? toCamera * inversesqrt(viewLengthSquared) : vec3(0.0, 0.0, 1.0);

    // A strand tangent parallel to the view direction has no stable screen
    // normal; fall back to a fixed perpendicular instead of producing NaN.
    vec3 side = cross(tangent, viewDirection);
    float sideLengthSquared = dot(side, side);
    side = sideLengthSquared > 1.0e-10
            ? side * inversesqrt(sideLengthSquared) : vec3(1.0, 0.0, 0.0);

    vec4 clipCenter = uCurrentStableViewProjection * vec4(world, 1.0);
    vec4 previousClip = uPreviousStableViewProjection * vec4(previousWorld, 1.0);
    if (!(clipCenter.w > 1.0e-6)) {
        // Collapse vertices behind the eye; the rasterizer discards them.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vCurrentClip = clipCenter;
        vPreviousClip = previousClip;
        vStrandParameter = aStrandParameter;
        return;
    }

    float probeLength = max(uRootRadiusMetres, 1.0e-4);
    vec4 clipProbe = uCurrentStableViewProjection * vec4(world + side * probeLength, 1.0);
    vec2 ndcCenter = clipCenter.xy / clipCenter.w;
    vec2 ndcProbe = clipProbe.xy / max(clipProbe.w, 1.0e-6);
    vec2 pixelDelta = (ndcProbe - ndcCenter) * 0.5 * uViewportSize;
    float pixelsPerMetre = length(pixelDelta) / probeLength;
    float widthPixels = clamp(uWidthPixels * uWidthScale,
            uMinimumWidthPixels, uMaximumWidthPixels);

    vec4 clip = clipCenter;
    if (pixelsPerMetre > 1.0e-4 && widthPixels > 0.0) {
        vec2 direction = normalize(pixelDelta);
        vec2 ndcOffset = direction * (aCorner * widthPixels / uViewportSize);
        clip.xy += ndcOffset * clip.w;
    }
    gl_Position = clip;

    vCurrentClip = clipCenter;
    vPreviousClip = previousClip;
    vStrandParameter = aStrandParameter;
}
