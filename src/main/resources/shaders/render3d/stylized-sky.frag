#version 460 core

layout (location = 0) in vec2 vUv;
layout (location = 0) out vec4 fragColor;

uniform mat4 uInverseProjection;
uniform mat4 uInverseViewRotation;
uniform vec3 uZenithColor;
uniform vec3 uHorizonColor;
uniform vec3 uNadirColor;
uniform vec3 uSunDirection;
uniform vec3 uSunColor;
uniform float uSunIntensity;
uniform float uSunAngularRadius;
uniform float uHaloIntensity;

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 viewFar = uInverseProjection * vec4(ndc, 1.0, 1.0);
    vec3 viewDirection = normalize(viewFar.xyz / max(viewFar.w, 1.0e-5));
    vec3 direction = normalize((uInverseViewRotation * vec4(viewDirection, 0.0)).xyz);
    float horizon = smoothstep(-0.16, 0.32, direction.y);
    vec3 sky = mix(uNadirColor, uHorizonColor, horizon);
    sky = mix(sky, uZenithColor, smoothstep(0.22, 0.95, direction.y));

    vec3 toSun = normalize(-uSunDirection);
    float sunCosine = dot(direction, toSun);
    float disc = smoothstep(cos(uSunAngularRadius), 1.0, sunCosine);
    float halo = pow(max(sunCosine, 0.0), 32.0) * uHaloIntensity;
    sky += uSunColor * (disc * uSunIntensity + halo);
    fragColor = vec4(max(sky, vec3(0.0)), 1.0);
}
