#version 460 core

layout(location = 0) out vec4 FragColor;

uniform sampler2D uBounce;
uniform sampler2D uNormal;
uniform int uHorizontal;

const int FILTER_RADIUS = 18;

vec3 octDecode(vec2 oct) {
    vec3 n = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx))
                * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

// Depth changes even on a continuous floor seen from above. Estimate the slope
// only when both neighbouring depths agree with a local surface.
float localDepthSlope(ivec2 pixel, ivec2 axis, ivec2 last, float depth) {
    if (any(lessThan(pixel - axis, ivec2(0))) || any(greaterThan(pixel + axis, last))) {
        return 0.0;
    }
    float previous = texelFetch(uBounce, pixel - axis, 0).a;
    float next = texelFetch(uBounce, pixel + axis, 0).a;
    float leftSlope = depth - previous;
    float rightSlope = next - depth;
    float localLimit = 0.012 * max(depth, 1.0);
    if (previous <= 0.0 || next <= 0.0 || leftSlope * rightSlope <= 0.0
            || max(abs(leftSlope), abs(rightSlope)) > localLimit) {
        return 0.0;
    }
    // The smaller slope is conservative near a silhouette or depth step.
    return sign(leftSlope) * min(abs(leftSlope), abs(rightSlope));
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 center = texelFetch(uBounce, pixel, 0);
    FragColor = center;
    if (center.a <= 0.0) return;

    vec3 normal = octDecode(texelFetch(uNormal, pixel * 2, 0).rg);
    ivec2 axis = uHorizontal != 0 ? ivec2(1, 0) : ivec2(0, 1);
    ivec2 last = textureSize(uBounce, 0) - 1;
    float slope = localDepthSlope(pixel, axis, last, center.a);
    float depthWindow = 0.018 * max(center.a, 1.0);
    vec3 sum = center.rgb;
    float total = 1.0;
    for (int offset = -FILTER_RADIUS; offset <= FILTER_RADIUS; offset++) {
        if (offset == 0) continue;
        ivec2 tap = pixel + offset * axis;
        if (any(lessThan(tap, ivec2(0))) || any(greaterThan(tap, last))) continue;
        vec4 candidate = texelFetch(uBounce, tap, 0);
        float depthDifference = abs(candidate.a - (center.a + slope * float(offset)));
        if (candidate.a <= 0.0 || depthDifference >= depthWindow) continue;
        vec3 tapNormal = octDecode(texelFetch(uNormal, tap * 2, 0).rg);
        float distance = float(offset) / float(FILTER_RADIUS + 1);
        float weight = (1.0 - distance * distance) * (1.0 - depthDifference / depthWindow)
                * smoothstep(0.85, 1.0, dot(normal, tapNormal));
        sum += candidate.rgb * weight;
        total += weight;
    }
    FragColor.rgb = sum / total;
}
