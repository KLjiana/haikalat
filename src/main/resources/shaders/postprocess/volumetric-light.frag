#version 460 core

layout (location = 0) in vec2 vUv;
layout (location = 0) out vec4 fragColor;

uniform mat4 uInverseViewProjection;
uniform vec3 uCameraPosition;
uniform vec3 uLightPosition;
uniform vec3 uLightDirection;
uniform vec3 uLightColor;
uniform int uSteps;
uniform float uMaximumDistance;
uniform float uDensity;
uniform float uAnisotropy;
uniform float uRange;
uniform float uInnerConeCosine;
uniform float uOuterConeCosine;
uniform float uIntensity;

float phaseHenyeyGreenstein(float cosineTheta, float g) {
    float g2 = g * g;
    float denominator = pow(max(1.0e-4, 1.0 + g2 - 2.0 * g * cosineTheta), 1.5);
    return (1.0 - g2) / (12.56637061436 * denominator);
}

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 farPoint = uInverseViewProjection * vec4(ndc, 1.0, 1.0);
    farPoint /= farPoint.w;
    vec3 rayDirection = normalize(farPoint.xyz - uCameraPosition);
    float stepLength = uMaximumDistance / float(uSteps);
    float phase = phaseHenyeyGreenstein(
            clamp(dot(normalize(uLightDirection), -rayDirection), -1.0, 1.0),
            uAnisotropy);
    float integrated = 0.0;

    for (int index = 0; index < 128; index++) {
        if (index >= uSteps) break;
        float distanceAlongRay = (float(index) + 0.5) * stepLength;
        vec3 samplePosition = uCameraPosition + rayDirection * distanceAlongRay;
        vec3 fromLight = samplePosition - uLightPosition;
        float lightDistance = length(fromLight);
        vec3 lightRay = fromLight / max(lightDistance, 1.0e-4);
        float cone = smoothstep(uOuterConeCosine, uInnerConeCosine,
                dot(lightRay, normalize(uLightDirection)));
        float rangeAttenuation = exp(-lightDistance / uRange);
        integrated += cone * rangeAttenuation * stepLength;
    }

    vec3 scattering = uLightColor * integrated * uDensity * phase * uIntensity;
    fragColor = vec4(scattering, 1.0);
}
