#version 460 core
// Bounded screen-space subsurface diffusion.
//
// The pass reads the already shaded linear HDR colour, the shared single-sample
// device depth and octahedral world normal, and the SUBSURFACE material mask
// written by scene-surface.frag. It performs one fixed 16-tap Poisson-disk
// gather inside the per-pixel world-space radius (converted to pixels from the
// reconstructed view position at the same depth), rejects depth/normal
// boundaries and leaves a non-scattering pixel bit-exact. There is no history,
// no private target and no read-back.
//
// Signal contract: the alpha of the linear HDR colour carries the bounded
// diffuse share of the rendered surface radiance, written by pbr-forward.frag
// while the shared SUBSURFACE mask is allocated (alpha 1 = fully diffuse,
// alpha 0 = fully specular/emissive). The gather only diffuses
// `colour.rgb * alpha`; the remaining `colour.rgb * (1 - alpha)` is added back
// unchanged. A low-luminance specular highlight therefore cannot be diffused
// and a bright diffuse surface is not protected by a luminance heuristic.
//
// This pass is the declared consumer of selective fragment shading rate
// (feature #42). Colour and depth samples use an explicit LOD 0 so a coarse
// fragment footprint cannot change implicit-derivative LOD selection; the
// surface mask and normals already use texelFetch. All inputs have a single
// mip level, so the explicit LOD is behaviour-preserving at 1x1 shading rate.

in vec2 vUv;

layout(location = 0) out vec4 FragColor;

uniform sampler2D uColor;
uniform sampler2D uDepth;
uniform sampler2D uNormal;
uniform sampler2D uSubsurface;
uniform vec2 uViewportSize;
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform float uMaxDistanceMetres;
uniform float uMaxRadiusMetres;

// Compile-time bounds. The authored radius can never create an unbounded
// gather and the tap table is fixed.
const int SUBSURFACE_TAP_COUNT = 16;
const float SUBSURFACE_MAX_RADIUS_PIXELS = 64.0;
const float SUBSURFACE_DEPTH_TOLERANCE = 0.03;
const float SUBSURFACE_MIN_DEPTH_TOLERANCE = 0.01;
const float SUBSURFACE_NORMAL_TOLERANCE = 0.85;
const float SUBSURFACE_EDGE_FADE_PIXELS = 8.0;
const float SUBSURFACE_KERNEL_FALLOFF = 2.5;

// Fixed 16-tap Poisson disk bounded to |r| <= 1.24. The array stays in sync
// with the independent Java reference (SubsurfaceScatteringReference.POISSON_TAPS).
const vec2 SUBSURFACE_TAPS[SUBSURFACE_TAP_COUNT] = vec2[SUBSURFACE_TAP_COUNT](
    vec2(-0.94201624, -0.39906216),
    vec2(0.94558609, -0.76890725),
    vec2(-0.09418410, -0.92938870),
    vec2(0.34495938, 0.29387760),
    vec2(-0.91588581, 0.45771432),
    vec2(-0.81544232, -0.87912464),
    vec2(-0.38277543, 0.27676845),
    vec2(0.97484398, 0.75648379),
    vec2(0.44323325, -0.97511554),
    vec2(0.53742981, -0.47373420),
    vec2(-0.26496911, -0.41893023),
    vec2(0.79197514, 0.19090188),
    vec2(-0.24188840, 0.99706507),
    vec2(-0.81409955, 0.91437590),
    vec2(0.19984126, 0.78641367),
    vec2(0.14383161, -0.14100790));

vec3 decodeOctahedral(vec2 oct) {
    vec3 normal = vec3(oct.x, oct.y, 1.0 - abs(oct.x) - abs(oct.y));
    if (normal.z < 0.0) {
        vec2 signOct = vec2(oct.x >= 0.0 ? 1.0 : -1.0,
                oct.y >= 0.0 ? 1.0 : -1.0);
        normal.x = (1.0 - abs(oct.y)) * signOct.x;
        normal.y = (1.0 - abs(oct.x)) * signOct.y;
    }
    return normalize(normal);
}

vec3 viewPosition(vec2 uv, float deviceDepth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, deviceDepth * 2.0 - 1.0, 1.0);
    vec4 view = uInverseProjection * clip;
    return view.xyz / view.w;
}

// Bounded diffuse share from the colour alpha. A missing/non-finite alpha fails
// closed to the legacy fully-diffuse signal, never to a NaN gather.
float diffuseShare(vec4 color) {
    float share = color.a;
    return share >= 0.0 && share <= 1.0 ? share : 1.0;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewportSize;
    vec4 centerColor = textureLod(uColor, uv, 0.0);
    vec4 centerMask = texelFetch(uSubsurface, ivec2(gl_FragCoord.xy), 0);
    float centerDeviceDepth = textureLod(uDepth, uv, 0.0).r;
    bool centerValid = centerMask.r > 0.0 && centerMask.a > 0.5
            && centerDeviceDepth < 1.0;
    if (!centerValid) {
        FragColor = vec4(centerColor.rgb, centerColor.a);
        return;
    }

    vec3 centerView = viewPosition(uv, centerDeviceDepth);
    if (!(centerView.z < 0.0)) {
        FragColor = vec4(centerColor.rgb, centerColor.a);
        return;
    }
    vec3 centerWorld = (uInverseView * vec4(centerView, 1.0)).xyz;
    vec3 centerNormal = decodeOctahedral(texelFetch(uNormal, ivec2(gl_FragCoord.xy), 0).rg);
    // The specular/emissive part is carried separately and is never part of the
    // diffusion sum or of the replacement value.
    float centerShare = diffuseShare(centerColor);
    vec3 centerDiffuse = centerColor.rgb * centerShare;

    // Reconstruct the world-space size of one pixel at the center depth. The
    // same device-depth surface is sampled one pixel to the right/up, so the
    // conversion follows the perspective projection without needing a second
    // camera matrix.
    vec3 rightView = viewPosition(uv + vec2(1.0 / uViewportSize.x, 0.0),
            centerDeviceDepth);
    vec3 upView = viewPosition(uv + vec2(0.0, 1.0 / uViewportSize.y),
            centerDeviceDepth);
    float metresPerPixelX = max(abs(rightView.x - centerView.x), 1.0e-5);
    float metresPerPixelY = max(abs(upView.y - centerView.y), 1.0e-5);
    float metresPerPixel = 0.5 * (metresPerPixelX + metresPerPixelY);
    float radiusMetres = min(centerMask.g, max(uMaxRadiusMetres, 0.0));
    float radiusPixels = clamp(radiusMetres / metresPerPixel, 1.0,
            SUBSURFACE_MAX_RADIUS_PIXELS);

    float edgePixels = min(
            min(uv.x, 1.0 - uv.x) * uViewportSize.x,
            min(uv.y, 1.0 - uv.y) * uViewportSize.y);
    float edgeFade = smoothstep(0.0, SUBSURFACE_EDGE_FADE_PIXELS, edgePixels);

    float centerWeight = 1.0;
    vec3 sum = centerDiffuse * centerWeight;
    float weightSum = centerWeight;

    for (int tapIndex = 0; tapIndex < SUBSURFACE_TAP_COUNT; ++tapIndex) {
        vec2 tap = SUBSURFACE_TAPS[tapIndex];
        vec2 sampleUv = uv + tap * radiusPixels / uViewportSize;
        if (sampleUv.x <= 0.0 || sampleUv.x >= 1.0
                || sampleUv.y <= 0.0 || sampleUv.y >= 1.0) {
            continue;
        }
        ivec2 samplePixel = ivec2(clamp(floor(sampleUv * uViewportSize),
                vec2(0.0), uViewportSize - vec2(1.0)));
        vec4 sampleMask = texelFetch(uSubsurface, samplePixel, 0);
        if (sampleMask.r <= 0.0 || sampleMask.a <= 0.5) continue;

        float sampleDeviceDepth = textureLod(uDepth, sampleUv, 0.0).r;
        if (sampleDeviceDepth >= 1.0) continue;
        vec3 sampleView = viewPosition(sampleUv, sampleDeviceDepth);
        if (!(sampleView.z < 0.0)) continue;

        vec3 sampleWorld = (uInverseView * vec4(sampleView, 1.0)).xyz;
        vec3 delta = sampleWorld - centerWorld;
        if (length(delta) > max(uMaxDistanceMetres, 0.0)) continue;

        vec3 sampleNormal = decodeOctahedral(
                texelFetch(uNormal, samplePixel, 0).rg);
        if (dot(sampleNormal, centerNormal) < SUBSURFACE_NORMAL_TOLERANCE) {
            continue;
        }
        // Reject a different depth layer: the sample must lie on the same
        // tangent plane as the center, not merely near it in view depth.
        float tolerance = max(SUBSURFACE_MIN_DEPTH_TOLERANCE,
                SUBSURFACE_DEPTH_TOLERANCE * abs(centerView.z));
        if (abs(dot(delta, centerNormal)) > tolerance) continue;

        vec4 sampleColor = textureLod(uColor, sampleUv, 0.0);
        // Only the tap's diffuse share enters the gather. A specular or
        // emissive neighbour therefore cannot bleed into the scattering halo
        // regardless of its luminance.
        vec3 sampleDiffuse = sampleColor.rgb * diffuseShare(sampleColor);
        float radial = length(tap);
        float weight = exp(-SUBSURFACE_KERNEL_FALLOFF * radial * radial);
        sum += sampleDiffuse * weight;
        weightSum += weight;
    }

    vec3 diffused = sum / max(weightSum, 1.0e-5);
    // The scatter amount is proportional to the centre's own diffuse share:
    // a fully specular pixel (share 0) must neither be replaced by its blurred
    // copy nor receive diffuse radiance it has no diffuse albedo for.
    float blend = clamp(centerMask.r, 0.0, 1.0) * edgeFade
            * clamp(centerShare, 0.0, 1.0);
    if (!(blend > 0.0)) {
        FragColor = vec4(centerColor.rgb, centerColor.a);
        return;
    }
    // Replace only the centre's diffuse share with the diffused diffuse and add
    // the centre specular/emissive back untouched.
    vec3 scattered = mix(centerDiffuse, diffused, blend);
    FragColor = vec4(centerColor.rgb - centerDiffuse + scattered, centerColor.a);
}
