#version 460 core

/*
 * Shared scene-normal attachment for the deterministic hair-strand set
 * (#46 v1).
 *
 * The previous opaque/receiver surface wrote the world normal of the geometry
 * behind the lock; the forward hair ribbons are a transparent overlay that
 * never enters that pass. This stage replaces the normal at pixels the forward
 * pass really covers, so normal consumers (SSR/SSGI gates, screen-space
 * subsurface gathering, TAA-adjacent temporal denoise) see the visible ribbon
 * instead of the receiver behind it.
 *
 * The ribbon surface normal is the normal of the plane the camera-facing
 * ribbon lies in: it is perpendicular to the strand tangent and lies in the
 * tangent/view plane, i.e. `cross(side, tangent)` with the same `side` the
 * vertex expansion used. That is the geometric normal of the flat ribbon and
 * the only stable normal an analytic strand has; the Kajiya-Kay forward stage
 * shades the tangent directly and therefore has no second normal convention.
 *
 * Encoding: this pass is declared against the shared `sceneNormal` attachment,
 * which the surface layout plans as RG16F. The value written here is the same
 * `vec4(octEncode(normal), 0.0, 1.0)` layout `scene-surface.frag` writes; an
 * RG attachment stores only xy, and a future wider attachment keeps the same
 * convention. The CUTOUT alpha cutoff matches the forward fragment stage, so
 * pixels the forward draw discarded keep the receiver's normal.
 */

layout(location = 0) out vec4 outNormal;

in vec3 vTangent;
in vec3 vSide;
in vec3 vViewDirection;
in float vStrandParameter;

uniform int uTransparencyMode;
uniform float uAlpha;
uniform float uAlphaCutoff;

float strandAlpha() {
    // The root is thinner/softer than the tip; the profile is deterministic and
    // shared with the forward, shadow-caster and velocity fragment stages.
    float profile = mix(0.55, 1.0, smoothstep(0.0, 0.3, vStrandParameter));
    return clamp(uAlpha * profile, 0.0, 1.0);
}

// Mirrors octEncode() in shaders/render3d/surface/scene-surface.frag exactly.
vec2 octEncode(vec3 normal) {
    vec3 n = normalize(normal);
    vec2 oct = n.xy / (abs(n.x) + abs(n.y) + abs(n.z) + 1.0e-8);
    oct = n.z >= 0.0 ? oct : (1.0 - abs(oct.yx)) * sign(oct + vec2(1.0e-8));
    return oct;
}

vec3 ribbonNormal() {
    vec3 tangent = normalize(vTangent);
    vec3 side = normalize(vSide);
    vec3 normal = cross(side, tangent);
    float lengthSquared = dot(normal, normal);
    if (lengthSquared > 1.0e-10) {
        return normal * inversesqrt(lengthSquared);
    }
    // A strand exactly parallel to the view direction makes both the side
    // fallback and the ribbon plane degenerate. Keep a deterministic unit
    // normal (the view direction) instead of normalizing zero.
    vec3 fallback = normalize(vViewDirection);
    float fallbackLengthSquared = dot(fallback, fallback);
    return fallbackLengthSquared > 1.0e-10 ? fallback : vec3(0.0, 0.0, 1.0);
}

void main() {
    float alpha = strandAlpha();
    if (alpha <= 0.0) {
        discard;
    }
    if (uTransparencyMode == 0 && alpha < uAlphaCutoff) {
        discard;
    }
    outNormal = vec4(octEncode(ribbonNormal()), 0.0, 1.0);
}
