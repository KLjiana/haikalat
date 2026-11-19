// Shared DDGI probe atlas layout, face basis and weighting helpers.
//
// This file is injected after the #version line of ddgi-relight.frag and
// ddgi-indirect.frag by DdgiShaderSource. It declares no #version of its own.
//
// Atlas layout (frozen, mirrored by DdgiSettings and DdgiProbeReference):
//  - probe depth atlas: one 2D depth texture, width = probeCount * 6 * R and
//    height = R. Probe p, face f occupies the pixel column range
//    [p * 6 * R + f * R, p * 6 * R + (f + 1) * R) at row 0..R-1.
//  - irradiance atlas: one 2D RGBA16F texture, width = probeCount * 3 * I and
//    height = 2 * I. Probe p owns a 3 x 2 tile of I x I face tiles starting at
//    column p * 3 * I. Face f is at tile column f % 3 and row f / 3.
//  - face order: +X, -X, +Y, -Y, +Z, -Z.
//
// A probe centre is gridOrigin + (index + 0.5) * spacing. Rays, visibility and
// the indirect query all use the same grid bounds; the query fades out at the
// border instead of extrapolating outside the bounds.

#ifndef DDGI_COMMON_GLSL
#define DDGI_COMMON_GLSL

uniform sampler2D uProbeDepthAtlas;
uniform sampler2D uIrradianceAtlas;
uniform int uProbesX;
uniform int uProbesY;
uniform int uProbesZ;
uniform vec3 uGridOrigin;
uniform float uSpacing;
uniform float uBorderFadeMetres;
uniform int uDepthFaceResolution;
uniform float uDepthNear;
uniform float uDepthFar;
uniform int uIrradianceFaceResolution;

vec3 ddgiProbePosition(int index) {
    int slice = uProbesX * uProbesY;
    int z = index / slice;
    int remainder = index - z * slice;
    int y = remainder / uProbesX;
    int x = remainder - y * uProbesX;
    return uGridOrigin + (vec3(float(x), float(y), float(z)) + vec3(0.5)) * uSpacing;
}

int ddgiProbeIndex(ivec3 coordinate) {
    return coordinate.x + coordinate.y * uProbesX
            + coordinate.z * uProbesX * uProbesY;
}

vec3 ddgiFaceForward(int face) {
    if (face == 0) return vec3(1.0, 0.0, 0.0);
    if (face == 1) return vec3(-1.0, 0.0, 0.0);
    if (face == 2) return vec3(0.0, 1.0, 0.0);
    if (face == 3) return vec3(0.0, -1.0, 0.0);
    if (face == 4) return vec3(0.0, 0.0, 1.0);
    return vec3(0.0, 0.0, -1.0);
}

vec3 ddgiFaceUpHint(int face) {
    if (face == 2) return vec3(0.0, 0.0, 1.0);
    if (face == 3) return vec3(0.0, 0.0, -1.0);
    return vec3(0.0, -1.0, 0.0);
}

vec3 ddgiFaceRight(int face) {
    return normalize(cross(ddgiFaceForward(face), ddgiFaceUpHint(face)));
}

vec3 ddgiFaceUp(int face) {
    return cross(ddgiFaceRight(face), ddgiFaceForward(face));
}

int ddgiFaceIndex(vec3 direction) {
    vec3 magnitude = abs(direction);
    if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z) {
        return direction.x >= 0.0 ? 0 : 1;
    }
    if (magnitude.y >= magnitude.z) {
        return direction.y >= 0.0 ? 2 : 3;
    }
    return direction.z >= 0.0 ? 4 : 5;
}

vec2 ddgiFaceUv(int face, vec3 relative) {
    vec3 forward = ddgiFaceForward(face);
    vec3 right = ddgiFaceRight(face);
    vec3 up = ddgiFaceUp(face);
    float denominator = max(abs(dot(relative, forward)), 1.0e-6);
    return vec2(0.5 * (dot(relative, right) / denominator + 1.0),
            0.5 * (dot(relative, up) / denominator + 1.0));
}

vec3 ddgiFaceVector(int face, vec2 uv) {
    vec2 signedUv = uv * 2.0 - 1.0;
    return normalize(ddgiFaceForward(face)
            + ddgiFaceRight(face) * signedUv.x
            + ddgiFaceUp(face) * signedUv.y);
}

float ddgiLinearDepth(float sampledDepth) {
    float ndc = sampledDepth * 2.0 - 1.0;
    float denominator = uDepthFar + uDepthNear - ndc * (uDepthFar - uDepthNear);
    return (2.0 * uDepthNear * uDepthFar) / max(denominator, 1.0e-6);
}

vec2 ddgiProbeDepthTexel(int probe, int face, vec2 uv) {
    float resolution = float(uDepthFaceResolution);
    ivec2 local = ivec2(clamp(uv, vec2(0.0), vec2(0.999999)) * resolution);
    local = clamp(local, ivec2(0), ivec2(uDepthFaceResolution - 1));
    return vec2(float((probe * 6 + face) * uDepthFaceResolution + local.x),
            float(local.y));
}

float ddgiProbeDepthDistance(int probe, vec3 relative) {
    int face = ddgiFaceIndex(relative);
    vec2 texel = ddgiProbeDepthTexel(probe, face, ddgiFaceUv(face, relative));
    float sampled = texelFetch(uProbeDepthAtlas, ivec2(texel), 0).r;
    float axis = abs(dot(normalize(relative), ddgiFaceForward(face)));
    return ddgiLinearDepth(sampled) / max(axis, 1.0e-4);
}

float ddgiBorderWeight(vec3 position) {
    vec3 minimum = uGridOrigin;
    vec3 maximum = minimum + vec3(float(uProbesX), float(uProbesY), float(uProbesZ))
            * uSpacing;
    if (any(lessThan(position, minimum)) || any(greaterThan(position, maximum))) {
        return 0.0;
    }
    float fade = max(uBorderFadeMetres, 1.0e-4);
    vec3 distance = min(position - minimum, maximum - position);
    float nearest = min(distance.x, min(distance.y, distance.z));
    return clamp(nearest / fade, 0.0, 1.0);
}

// Bounded visibility gate for one probe. The probe depth atlas stores the
// nearest surface along the receiver direction; when the receiver lies clearly
// behind that surface the probe may not see it. This is a hard gate with a
// tolerance of 5% of one probe spacing (or 2% of the receiver distance), so an
// occluded probe contributes exactly nothing and a fully occluded receiver is
// exactly black instead of leaking a fraction of the nearest probe.
float ddgiProbeVisibility(int probe, vec3 receiver) {
    vec3 relative = receiver - ddgiProbePosition(probe);
    float distance = length(relative);
    if (distance <= 1.0e-4) {
        return 1.0;
    }
    float recorded = ddgiProbeDepthDistance(probe, relative);
    float difference = distance - recorded;
    float tolerance = max(uSpacing * 0.05, distance * 0.02);
    return difference <= tolerance ? 1.0 : 0.0;
}

// Filter visibility comparisons, not stored depths: interpolating depths
// before comparing would invent a surface at occluder silhouettes.
float ddgiFilteredProbeVisibility(int probe, vec3 relative, vec3 normal) {
    float distance = length(relative);
    if (distance <= 1.0e-4) return 1.0;

    int face = ddgiFaceIndex(relative);
    float resolution = float(uDepthFaceResolution);
    vec2 texel = ddgiFaceUv(face, relative) * resolution - 0.5;
    ivec2 first = ivec2(floor(texel));
    vec2 fraction = fract(texel);
    vec3 forward = ddgiFaceForward(face);
    float axialDepth = dot(forward, relative);
    float tolerance = max(uSpacing * 0.05, distance * 0.02) * axialDepth / distance;
    float planeOffset = dot(normal, relative);
    vec2 planeSlope = vec2(dot(normal, ddgiFaceRight(face)), dot(normal, ddgiFaceUp(face)));
    float planeForward = dot(normal, forward);
    int atlasColumn = (probe * 6 + face) * uDepthFaceResolution;
    float visible = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            ivec2 tap = clamp(first + ivec2(x, y), ivec2(0), ivec2(uDepthFaceResolution - 1));
            float recorded = ddgiLinearDepth(texelFetch(uProbeDepthAtlas,
                    tap + ivec2(atlasColumn, 0), 0).r);

            // Compare at this texel's ray, where the depth was actually rasterized.
            // The tangent plane predicts the receiver's depth for that direction.
            vec2 rayUv = (vec2(tap) + 0.5) * (2.0 / resolution) - 1.0;
            float planeRay = planeForward + dot(planeSlope, rayUv);
            float expected = axialDepth;
            if (planeOffset < -0.05 * distance && planeRay < -0.05) {
                float planeDepth = planeOffset / planeRay;
                if (abs(planeDepth - axialDepth) <= 0.25 * axialDepth) expected = planeDepth;
            }
            float transmittance = 1.0 - smoothstep(0.5 * tolerance, tolerance, expected - recorded);
            float weight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                    * (y == 0 ? 1.0 - fraction.y : fraction.y);
            visible += transmittance * weight;
        }
    }
    return visible;
}

vec3 ddgiAtlasTexel(int probe, int face, ivec2 localTexel) {
    int tileWidth = 3 * uIrradianceFaceResolution;
    int faceX = face % 3;
    int faceY = face / 3;
    ivec2 coordinate = ivec2(
            probe * tileWidth + faceX * uIrradianceFaceResolution + localTexel.x,
            faceY * uIrradianceFaceResolution + localTexel.y);
    return texelFetch(uIrradianceAtlas, coordinate, 0).rgb;
}

vec3 ddgiSampleProbeRadiance(int probe, vec3 normal) {
    int face = ddgiFaceIndex(normal);
    vec2 uv = clamp(ddgiFaceUv(face, normal), vec2(0.0), vec2(1.0));
    float resolution = float(uIrradianceFaceResolution);
    vec2 texel = uv * resolution - 0.5;
    vec2 base = floor(texel);
    vec2 fraction = texel - base;
    vec3 sum = vec3(0.0);
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            ivec2 local = ivec2(base) + ivec2(x, y);
            local = clamp(local, ivec2(0), ivec2(uIrradianceFaceResolution - 1));
            float weight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                    * (y == 0 ? 1.0 - fraction.y : fraction.y);
            sum += ddgiAtlasTexel(probe, face, local) * weight;
        }
    }
    return sum;
}

// Trilinear probe weighting with the documented normal-side gate and bounded
// visibility. Out-of-grid samples return exactly zero, and the result fades to
// zero over uBorderFadeMetres at the grid border so probe light cannot leak
// past the allocated grid.
vec3 ddgiSampleIrradiance(vec3 position, vec3 normal, bool filterVisibility) {
    float border = ddgiBorderWeight(position);
    if (!(border > 0.0)) {
        return vec3(0.0);
    }
    vec3 normalized = position - uGridOrigin;
    normalized = normalized / uSpacing - vec3(0.5);
    vec3 base = floor(normalized);
    vec3 fraction = normalized - base;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int corner = 0; corner < 8; corner++) {
        int dx = corner & 1;
        int dy = (corner >> 1) & 1;
        int dz = (corner >> 2) & 1;
        ivec3 coordinate = ivec3(int(base.x) + dx, int(base.y) + dy, int(base.z) + dz);
        coordinate = clamp(coordinate, ivec3(0),
                ivec3(uProbesX - 1, uProbesY - 1, uProbesZ - 1));
        int probe = ddgiProbeIndex(coordinate);
        vec3 toProbe = ddgiProbePosition(probe) - position;
        float normalWeight = max(dot(normalize(toProbe + vec3(1.0e-5)), normal), 0.0);
        float weight = (dx == 1 ? fraction.x : 1.0 - fraction.x)
                * (dy == 1 ? fraction.y : 1.0 - fraction.y)
                * (dz == 1 ? fraction.z : 1.0 - fraction.z)
                * normalWeight;
        if (weight <= 0.0) continue;
        float visibility = filterVisibility ? ddgiFilteredProbeVisibility(probe, -toProbe, normal)
                : ddgiProbeVisibility(probe, position);
        sum += ddgiSampleProbeRadiance(probe, normal) * weight * visibility;
        weightSum += weight;
    }
    if (weightSum <= 1.0e-6) {
        return vec3(0.0);
    }
    return border * sum / weightSum;
}

#endif
