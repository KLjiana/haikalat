#version 460 core

layout(location = 0) out vec4 FragColor;

uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform sampler2D uAlbedo;
// RGB is the incident bounce with strength applied. Alpha is positive view depth.
uniform sampler2D uTrace;
uniform mat4 uInverseProjection;

vec3 octDecode(vec2 oct) {
    vec3 n = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx))
                * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 base = texelFetch(uColor, pixel, 0);
    float depth = texelFetch(uDepth, pixel, 0).r;
    vec3 albedo = clamp(texelFetch(uAlbedo, pixel, 0).rgb, 0.0, 1.0);
    FragColor = base;
    if (depth >= 1.0 || dot(albedo, vec3(1.0)) <= 0.0) return;

    vec2 uv = gl_FragCoord.xy / vec2(textureSize(uDepth, 0));
    vec4 view = uInverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (view.w <= 0.0) return;
    float viewDepth = -view.z / view.w;
    vec3 normal = octDecode(texelFetch(uNormal, pixel, 0).rg);
    // The trace pass samples the top-left texel of each 2×2 block in texture coordinates.
    vec2 tracePosition = vec2(pixel) * 0.5;
    ivec2 first = ivec2(tracePosition);
    vec2 fraction = fract(tracePosition);
    ivec2 last = textureSize(uTrace, 0) - 1;
    float depthWindow = 0.02 * max(viewDepth, 1.0);
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            float spatialWeight = (x == 0 ? 1.0 - fraction.x : fraction.x)
                    * (y == 0 ? 1.0 - fraction.y : fraction.y);
            if (spatialWeight <= 0.0) continue;
            ivec2 tap = min(first + ivec2(x, y), last);
            vec4 traced = texelFetch(uTrace, tap, 0);
            float depthDifference = abs(traced.a - viewDepth);
            if (traced.a <= 0.0 || depthDifference >= depthWindow) continue;
            vec3 tapNormal = octDecode(texelFetch(uNormal, tap * 2, 0).rg);
            float weight = spatialWeight * (1.0 - depthDifference / depthWindow)
                    * smoothstep(0.85, 1.0, dot(normal, tapNormal));
            sum += traced.rgb * weight;
            total += weight;
        }
    }
    if (total > 1e-5) {
        vec3 result = base.rgb + albedo * (sum / total);
        if (!any(isnan(result)) && !any(isinf(result))) FragColor.rgb = result;
    }
}
