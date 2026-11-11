#version 330 core
/*
 * SMAA 1x blending weight calculation (second pass).
 *
 * GLSL 330 port of SMAABlendingWeightCalculationPS and its helpers from
 * SMAA.hlsl, commit 71c806a838bdd7d517df19192a20f0c61b3ca29d.
 * Copyright (C) 2013 Jorge Jimenez, Jose I. Echevarria, Belen Masia,
 * Fernando Navarro, Diego Gutierrez.  MIT License; full text and provenance in
 * docs/licenses/smaa.md.
 *
 * SMAA 1x uses the HIGH preset: threshold 0.1, 16 linear search steps,
 * 8 diagonal steps and 25% corner rounding.  Sub-sample indices are the
 * SMAA 1x zero vector, so every area lookup uses sub-texture slot 0.
 */
out vec4 FragColor;
in vec2 vUv;

uniform sampler2D uEdges;
uniform sampler2D uArea;
uniform sampler2D uSearch;
uniform vec2 uInvResolution;
uniform vec2 uResolution;

const int SMAA_MAX_SEARCH_STEPS = 16;
const int SMAA_MAX_SEARCH_STEPS_DIAG = 8;
const float SMAA_AREATEX_MAX_DISTANCE = 16.0;
const float SMAA_AREATEX_MAX_DISTANCE_DIAG = 20.0;
const vec2 SMAA_AREATEX_PIXEL_SIZE = vec2(1.0 / 160.0, 1.0 / 560.0);
const float SMAA_AREATEX_SUBTEX_SIZE = 1.0 / 7.0;
const vec2 SMAA_SEARCHTEX_SIZE = vec2(66.0, 33.0);
const vec2 SMAA_SEARCHTEX_PACKED_SIZE = vec2(64.0, 16.0);
const float SMAA_CORNER_ROUNDING_NORM = 25.0 / 100.0;

vec4 sampleEdges(vec2 texcoord) {
    return textureLod(uEdges, texcoord, 0.0);
}

vec4 sampleEdgesOffset(vec2 texcoord, ivec2 offset) {
    return textureLod(uEdges, texcoord + vec2(offset) * uInvResolution, 0.0);
}

vec2 sampleArea(vec2 texcoord) {
    return textureLod(uArea, texcoord, 0.0).rg;
}

float sampleSearch(vec2 texcoord) {
    return textureLod(uSearch, texcoord, 0.0).r;
}

void moveConditional(bvec2 condition, inout vec2 variable, vec2 value) {
    if (condition.x) variable.x = value.x;
    if (condition.y) variable.y = value.y;
}

void moveConditional(bvec4 condition, inout vec4 variable, vec4 value) {
    moveConditional(condition.xy, variable.xy, value.xy);
    moveConditional(condition.zw, variable.zw, value.zw);
}

vec2 decodeDiagonalBilinearAccess(vec2 e) {
    e.r = e.r * abs(5.0 * e.r - 5.0 * 0.75);
    return round(e);
}

vec4 decodeDiagonalBilinearAccess(vec4 e) {
    e.rb = e.rb * abs(5.0 * e.rb - 5.0 * 0.75);
    return round(e);
}

vec2 searchDiagonal1(vec2 texcoord, vec2 direction, out vec2 e) {
    vec4 coord = vec4(texcoord, -1.0, 1.0);
    vec3 advance = vec3(uInvResolution, 1.0);
    while (coord.z < float(SMAA_MAX_SEARCH_STEPS_DIAG - 1) && coord.w > 0.9) {
        coord.xyz += advance * vec3(direction, 1.0);
        e = sampleEdges(coord.xy).rg;
        coord.w = dot(e, vec2(0.5));
    }
    return coord.zw;
}

vec2 searchDiagonal2(vec2 texcoord, vec2 direction, out vec2 e) {
    vec4 coord = vec4(texcoord, -1.0, 1.0);
    coord.x += 0.25 * uInvResolution.x;
    vec3 advance = vec3(uInvResolution, 1.0);
    while (coord.z < float(SMAA_MAX_SEARCH_STEPS_DIAG - 1) && coord.w > 0.9) {
        coord.xyz += advance * vec3(direction, 1.0);
        e = sampleEdges(coord.xy).rg;
        e = decodeDiagonalBilinearAccess(e);
        coord.w = dot(e, vec2(0.5));
    }
    return coord.zw;
}

vec2 areaDiagonal(vec2 dist, vec2 e) {
    vec2 texcoord = vec2(SMAA_AREATEX_MAX_DISTANCE_DIAG) * e + dist;
    texcoord = SMAA_AREATEX_PIXEL_SIZE * texcoord + 0.5 * SMAA_AREATEX_PIXEL_SIZE;
    texcoord.x += 0.5;
    // SMAA 1x sub-sample offset is zero, matching the reference's
    // subsampleIndices.z.
    texcoord.y += SMAA_AREATEX_SUBTEX_SIZE * 0.0;
    return sampleArea(texcoord);
}

vec2 calculateDiagonalWeights(vec2 texcoord, vec2 e) {
    vec2 weights = vec2(0.0);

    vec4 d;
    vec2 end;
    if (e.r > 0.0) {
        d.xz = searchDiagonal1(texcoord, vec2(-1.0, 1.0), end);
        d.x += float(end.y > 0.9);
    } else {
        d.xz = vec2(0.0);
    }
    d.yw = searchDiagonal1(texcoord, vec2(1.0, -1.0), end);

    if (d.x + d.y > 2.0) {
        vec4 coords = vec4(-d.x + 0.25, d.x, d.y, -d.y - 0.25)
                * uInvResolution.xyxy + texcoord.xyxy;
        vec4 c;
        c.xy = sampleEdgesOffset(coords.xy, ivec2(-1, 0)).rg;
        c.zw = sampleEdgesOffset(coords.zw, ivec2(1, 0)).rg;
        c.yxwz = decodeDiagonalBilinearAccess(c.xyzw);
        vec2 cc = vec2(2.0) * c.xz + c.yw;
        moveConditional(bvec2(step(0.9, d.zw)), cc, vec2(0.0));
        weights += areaDiagonal(d.xy, cc);
    }

    d.xz = searchDiagonal2(texcoord, vec2(-1.0, -1.0), end);
    if (sampleEdgesOffset(texcoord, ivec2(1, 0)).r > 0.0) {
        d.yw = searchDiagonal2(texcoord, vec2(1.0, 1.0), end);
        d.y += float(end.y > 0.9);
    } else {
        d.yw = vec2(0.0);
    }

    if (d.x + d.y > 2.0) {
        vec4 coords = vec4(-d.x, -d.x, d.y, d.y)
                * uInvResolution.xyxy + texcoord.xyxy;
        vec4 c;
        c.x = sampleEdgesOffset(coords.xy, ivec2(-1, 0)).g;
        c.y = sampleEdgesOffset(coords.xy, ivec2(0, -1)).r;
        c.zw = sampleEdgesOffset(coords.zw, ivec2(1, 0)).gr;
        vec2 cc = vec2(2.0) * c.xz + c.yw;
        moveConditional(bvec2(step(0.9, d.zw)), cc, vec2(0.0));
        weights += areaDiagonal(d.xy, cc).gr;
    }

    return weights;
}

float searchLength(vec2 e, float offset) {
    vec2 scale = SMAA_SEARCHTEX_SIZE * vec2(0.5, -1.0);
    vec2 bias = SMAA_SEARCHTEX_SIZE * vec2(offset, 1.0);
    scale += vec2(-1.0, 1.0);
    bias += vec2(0.5, -0.5);
    scale *= 1.0 / SMAA_SEARCHTEX_PACKED_SIZE;
    bias *= 1.0 / SMAA_SEARCHTEX_PACKED_SIZE;
    return sampleSearch(scale * e + bias);
}

float searchXLeft(vec2 texcoord, float end) {
    vec2 e = vec2(0.0, 1.0);
    while (texcoord.x > end && e.g > 0.8281 && e.r == 0.0) {
        e = sampleEdges(texcoord).rg;
        texcoord -= vec2(2.0, 0.0) * uInvResolution;
    }
    float offset = -(255.0 / 127.0) * searchLength(e, 0.0) + 3.25;
    return uInvResolution.x * offset + texcoord.x;
}

float searchXRight(vec2 texcoord, float end) {
    vec2 e = vec2(0.0, 1.0);
    while (texcoord.x < end && e.g > 0.8281 && e.r == 0.0) {
        e = sampleEdges(texcoord).rg;
        texcoord += vec2(2.0, 0.0) * uInvResolution;
    }
    float offset = -(255.0 / 127.0) * searchLength(e, 0.5) + 3.25;
    return -uInvResolution.x * offset + texcoord.x;
}

float searchYUp(vec2 texcoord, float end) {
    vec2 e = vec2(1.0, 0.0);
    while (texcoord.y > end && e.r > 0.8281 && e.g == 0.0) {
        e = sampleEdges(texcoord).rg;
        texcoord -= vec2(0.0, 2.0) * uInvResolution;
    }
    float offset = -(255.0 / 127.0) * searchLength(e.gr, 0.0) + 3.25;
    return uInvResolution.y * offset + texcoord.y;
}

float searchYDown(vec2 texcoord, float end) {
    vec2 e = vec2(1.0, 0.0);
    while (texcoord.y < end && e.r > 0.8281 && e.g == 0.0) {
        e = sampleEdges(texcoord).rg;
        texcoord += vec2(0.0, 2.0) * uInvResolution;
    }
    float offset = -(255.0 / 127.0) * searchLength(e.gr, 0.5) + 3.25;
    return -uInvResolution.y * offset + texcoord.y;
}

vec2 area(vec2 dist, float e1, float e2) {
    vec2 texcoord = vec2(SMAA_AREATEX_MAX_DISTANCE)
            * round(4.0 * vec2(e1, e2)) + dist;
    texcoord = SMAA_AREATEX_PIXEL_SIZE * texcoord + 0.5 * SMAA_AREATEX_PIXEL_SIZE;
    // SMAA 1x sub-sample offset is zero, matching subsampleIndices.x/.y.
    texcoord.y += SMAA_AREATEX_SUBTEX_SIZE * 0.0;
    return sampleArea(texcoord);
}

void detectHorizontalCornerPattern(inout vec2 weights, vec4 texcoord, vec2 d) {
    vec2 leftRight = step(d.xy, d.yx);
    vec2 rounding = (1.0 - SMAA_CORNER_ROUNDING_NORM) * leftRight;
    rounding /= leftRight.x + leftRight.y;

    vec2 factor = vec2(1.0);
    factor.x -= rounding.x * sampleEdgesOffset(texcoord.xy, ivec2(0, 1)).r;
    factor.x -= rounding.y * sampleEdgesOffset(texcoord.zw, ivec2(1, 1)).r;
    factor.y -= rounding.x * sampleEdgesOffset(texcoord.xy, ivec2(0, -2)).r;
    factor.y -= rounding.y * sampleEdgesOffset(texcoord.zw, ivec2(1, -2)).r;

    weights *= clamp(factor, 0.0, 1.0);
}

void detectVerticalCornerPattern(inout vec2 weights, vec4 texcoord, vec2 d) {
    vec2 leftRight = step(d.xy, d.yx);
    vec2 rounding = (1.0 - SMAA_CORNER_ROUNDING_NORM) * leftRight;
    rounding /= leftRight.x + leftRight.y;

    vec2 factor = vec2(1.0);
    factor.x -= rounding.x * sampleEdgesOffset(texcoord.xy, ivec2(1, 0)).g;
    factor.x -= rounding.y * sampleEdgesOffset(texcoord.zw, ivec2(1, 1)).g;
    factor.y -= rounding.x * sampleEdgesOffset(texcoord.xy, ivec2(-2, 0)).g;
    factor.y -= rounding.y * sampleEdgesOffset(texcoord.zw, ivec2(-2, 1)).g;

    weights *= clamp(factor, 0.0, 1.0);
}

void main() {
    vec4 weights = vec4(0.0);

    vec2 e = sampleEdges(vUv).rg;

    vec2 pixelCoord = vUv * uResolution;
    vec2 offset0xy = vUv + vec2(-0.25, -0.125) * uInvResolution;
    vec2 offset0zw = vUv + vec2(1.25, -0.125) * uInvResolution;
    vec2 offset1xy = vUv + vec2(-0.125, -0.25) * uInvResolution;
    vec2 offset1zw = vUv + vec2(-0.125, 1.25) * uInvResolution;
    float endXLeft = offset0xy.x - 2.0 * float(SMAA_MAX_SEARCH_STEPS) * uInvResolution.x;
    float endXRight = offset0zw.x + 2.0 * float(SMAA_MAX_SEARCH_STEPS) * uInvResolution.x;
    float endYUp = offset1xy.y - 2.0 * float(SMAA_MAX_SEARCH_STEPS) * uInvResolution.y;
    float endYDown = offset1zw.y + 2.0 * float(SMAA_MAX_SEARCH_STEPS) * uInvResolution.y;

    if (e.g > 0.0) {
        weights.rg = calculateDiagonalWeights(vUv, e);
        if (weights.r + weights.g == 0.0) {
            float leftX = searchXLeft(offset0xy, endXLeft);
            float crossingY = offset1xy.y;
            float leftEdge = sampleEdges(vec2(leftX, crossingY)).r;

            float rightX = searchXRight(offset0zw, endXRight);
            vec2 d = abs(round(vec2(uResolution.x * leftX, uResolution.x * rightX)
                    - pixelCoord.xx));
            vec2 sqrtD = sqrt(d);
            float rightEdge = sampleEdgesOffset(vec2(rightX, crossingY), ivec2(1, 0)).r;

            weights.rg = area(sqrtD, leftEdge, rightEdge);
            detectHorizontalCornerPattern(weights.rg,
                    vec4(leftX, vUv.y, rightX, vUv.y), d);
        } else {
            // Diagonal patterns take priority; skip vertical processing.
            e.r = 0.0;
        }
    }

    if (e.r > 0.0) {
        float topY = searchYUp(offset1xy, endYUp);
        float crossingX = offset0xy.x;
        float topEdge = sampleEdges(vec2(crossingX, topY)).g;

        float bottomY = searchYDown(offset1zw, endYDown);
        vec2 d = abs(round(vec2(uResolution.y * topY, uResolution.y * bottomY)
                - pixelCoord.yy));
        vec2 sqrtD = sqrt(d);
        float bottomEdge = sampleEdgesOffset(vec2(crossingX, bottomY), ivec2(0, 1)).g;

        weights.ba = area(sqrtD, topEdge, bottomEdge);
        detectVerticalCornerPattern(weights.ba,
                vec4(vUv.x, topY, vUv.x, bottomY), d);
    }

    FragColor = weights;
}
