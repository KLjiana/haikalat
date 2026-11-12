#version 460 core

/*
 * Kajiya-Kay strand shading (#46).
 *
 * The diffuse term uses the sine between the strand tangent and the light
 * direction (`sqrt(1 - (T.L)^2)`), and the two specular lobes use the sine
 * between the tangent and the half vector with independent exponents and a
 * bounded secondary weight. A strand tangent perpendicular to the half vector
 * is the highlight lobe; a tangent parallel to it is the geometric negative
 * control. Direct light is attenuated by the existing directional shadow map
 * with a fixed 3x3 PCF; a missing/zero texture means fully lit.
 */

layout(location = 0) out vec4 FragColor;

in vec3 vWorldPosition;
in vec3 vTangent;
in vec3 vViewDirection;
in float vStrandParameter;
in float vRandom;
in vec4 vLightSpacePosition;

uniform int uTransparencyMode;
uniform float uAlpha;
uniform float uAlphaCutoff;
uniform vec3 uRootColor;
uniform vec3 uTipColor;
uniform float uDiffuseStrength;
uniform float uSpecularStrength;
uniform float uPrimaryExponent;
uniform float uSecondaryExponent;
uniform float uSecondaryWeight;
uniform float uColorVariation;
uniform vec3 uLightTravelDirection;
uniform vec3 uLightColor;
uniform float uLightIntensity;
uniform vec3 uAmbientColor;
uniform int uHasDirectionalShadow;
uniform sampler2D uDirectionalShadow;
uniform float uShadowBias;
uniform float uShadowTexelSize;

float strandAlpha() {
    // The root is thinner/softer than the tip; the profile is deterministic and
    // shared with the shadow-caster fragment stage.
    float profile = mix(0.55, 1.0, smoothstep(0.0, 0.3, vStrandParameter));
    return clamp(uAlpha * profile, 0.0, 1.0);
}

float directionalShadow() {
    if (uHasDirectionalShadow == 0) {
        return 1.0;
    }
    if (!(vLightSpacePosition.w > 1.0e-6)) {
        return 1.0;
    }
    vec3 projected = vLightSpacePosition.xyz / vLightSpacePosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.x <= 0.0 || projected.x >= 1.0
            || projected.y <= 0.0 || projected.y >= 1.0
            || projected.z <= 0.0 || projected.z >= 1.0) {
        return 1.0;
    }
    float bias = max(uShadowBias, 1.0e-5);
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 uv = clamp(projected.xy + vec2(x, y) * uShadowTexelSize,
                    vec2(0.0), vec2(1.0));
            float closest = texture(uDirectionalShadow, uv).r;
            lit += projected.z - bias > closest ? 0.0 : 1.0;
        }
    }
    return lit / 9.0;
}

void main() {
    float alpha = strandAlpha();
    if (alpha <= 0.0) {
        discard;
    }
    if (uTransparencyMode == 0 && alpha < uAlphaCutoff) {
        discard;
    }

    vec3 tangent = normalize(vTangent);
    vec3 viewDirection = normalize(vViewDirection);
    vec3 lightDirection = normalize(-uLightTravelDirection);
    vec3 halfVector = lightDirection + viewDirection;
    float halfLengthSquared = dot(halfVector, halfVector);
    halfVector = halfLengthSquared > 1.0e-8 ? halfVector * inversesqrt(halfLengthSquared)
            : viewDirection;

    float tint = 1.0 + (vRandom * 2.0 - 1.0) * uColorVariation;
    vec3 baseColor = mix(uRootColor, uTipColor, vStrandParameter) * tint;
    float shadow = directionalShadow();
    vec3 radiance = uLightColor * uLightIntensity * shadow;

    float tangentDotLight = dot(tangent, lightDirection);
    float sinTangentLight = sqrt(max(1.0 - tangentDotLight * tangentDotLight, 0.0));
    vec3 diffuse = baseColor * (uAmbientColor + radiance * (uDiffuseStrength * sinTangentLight));

    float tangentDotHalf = dot(tangent, halfVector);
    float sinTangentHalf = sqrt(max(1.0 - tangentDotHalf * tangentDotHalf, 0.0));
    float primary = pow(sinTangentHalf, uPrimaryExponent);
    float secondary = pow(sinTangentHalf, uSecondaryExponent) * uSecondaryWeight;
    vec3 specular = radiance * ((primary + secondary) * uSpecularStrength);

    FragColor = vec4(diffuse + specular, alpha);
}
