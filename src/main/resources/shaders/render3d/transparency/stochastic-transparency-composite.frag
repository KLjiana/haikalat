#version 460 core

// Premultiplied composite of the stochastic resolve over the caller's bound HDR
// target.  The fixed-function blend is GL_ONE / GL_ONE_MINUS_SRC_ALPHA, so the
// resolved premultiplied colour is added and the target is attenuated by the
// coverage.  Non-finite values and zero coverage produce a zero source, which
// leaves the target exactly unchanged (important for empty pixels and for the
// disabled/no-op comparison).

in vec2 vUv;

layout(location = 0) out vec4 fragColor;

uniform sampler2D uResolvedColor;

const float ST_MAX_COLOR = 6.5504e4;

void main() {
    vec4 resolved = texture(uResolvedColor, vUv);
    if (any(isnan(resolved)) || any(isinf(resolved))) {
        fragColor = vec4(0.0);
        return;
    }
    float coverage = clamp(resolved.a, 0.0, 1.0);
    if (!(coverage > 0.0)) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 premultiplied = clamp(resolved.rgb, vec3(0.0), vec3(ST_MAX_COLOR));
    fragColor = vec4(premultiplied, coverage);
}
