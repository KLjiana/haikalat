#version 460 core

/*
 * Swept-ribbon vertex stage for the deterministic hair-strand set (#46).
 *
 * The generated geometry stores the strand centreline, its tangent, the
 * normalized parameter along the strand, the ribbon corner sign and a
 * per-strand random value. This stage expands the centreline into a ribbon in
 * screen space: it projects the centre and a short world-space probe along the
 * camera-facing side vector, converts that difference to pixels, and offsets
 * the clip-space vertex by `corner * desiredWidthPixels / 2`. The requested
 * width is clamped to [uMinimumWidthPixels, uMaximumWidthPixels] and scaled by
 * the LOD factor, so neither a close-up nor a distant lock can produce a
 * degenerate or unbounded ribbon. This is not a card: width follows the
 * generated strand, while the strand path, tangent and parameter are the
 * procedural control-point data.
 */

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aTangent;
layout(location = 2) in float aStrandParameter;
layout(location = 3) in float aCorner;
layout(location = 4) in float aRandom;

uniform mat4 uView;
uniform mat4 uProjection;
uniform mat4 uModel;
uniform mat4 uDirectionalLightSpace;
uniform vec3 uCameraPosition;
uniform vec2 uViewportSize;
uniform float uWidthPixels;
uniform float uMinimumWidthPixels;
uniform float uMaximumWidthPixels;
uniform float uWidthScale;
uniform float uRootRadiusMetres;

out vec3 vWorldPosition;
out vec3 vTangent;
out vec3 vViewDirection;
out float vStrandParameter;
out float vRandom;
out vec4 vLightSpacePosition;

void main() {
    vec3 world = (uModel * vec4(aPosition, 1.0)).xyz;
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

    mat4 viewProjection = uProjection * uView;
    vec4 clipCenter = viewProjection * vec4(world, 1.0);
    if (!(clipCenter.w > 1.0e-6)) {
        // Collapse vertices behind the eye; the rasterizer discards them.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vWorldPosition = world;
        vTangent = tangent;
        vViewDirection = viewDirection;
        vStrandParameter = aStrandParameter;
        vRandom = aRandom;
        vLightSpacePosition = uDirectionalLightSpace * vec4(world, 1.0);
        return;
    }

    float probeLength = max(uRootRadiusMetres, 1.0e-4);
    vec4 clipProbe = viewProjection * vec4(world + side * probeLength, 1.0);
    vec2 ndcCenter = clipCenter.xy / clipCenter.w;
    vec2 ndcProbe = clipProbe.xy / max(clipProbe.w, 1.0e-6);
    vec2 pixelDelta = (ndcProbe - ndcCenter) * 0.5 * uViewportSize;
    float pixelsPerMetre = length(pixelDelta) / probeLength;
    float widthPixels = clamp(uWidthPixels * uWidthScale,
            uMinimumWidthPixels, uMaximumWidthPixels);

    vec4 clip = clipCenter;
    if (pixelsPerMetre > 1.0e-4 && widthPixels > 0.0) {
        // ndcPerPixel = pixelDelta / pixelsPerMetre / probeLength; the offset in
        // NDC for a full pixel width is therefore normalize(pixelDelta) * width
        // / viewport. Multiplying by clip.w applies it in clip space.
        vec2 direction = normalize(pixelDelta);
        vec2 ndcOffset = direction * (aCorner * widthPixels / uViewportSize);
        clip.xy += ndcOffset * clip.w;
    }
    gl_Position = clip;

    vWorldPosition = world;
    vTangent = tangent;
    vViewDirection = viewDirection;
    vStrandParameter = aStrandParameter;
    vRandom = aRandom;
    vLightSpacePosition = uDirectionalLightSpace * vec4(world, 1.0);
}
