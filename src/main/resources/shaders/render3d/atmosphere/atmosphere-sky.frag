#version 460 core

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

uniform mat4 uInverseProjection;
uniform mat4 uInverseViewRotation;
uniform vec3 uSunDirection;
uniform float uSunIntensity;
uniform float uExposure;
uniform float uSunAngularRadius;
uniform float uSunDiscIntensity;
uniform sampler2D uSkyViewLut;

void main() {
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 viewFar = uInverseProjection * vec4(ndc, 1.0, 1.0);
    vec3 viewDirection = normalize(viewFar.xyz / max(viewFar.w, 1.0e-5));
    vec3 direction = normalize((uInverseViewRotation * vec4(viewDirection, 0.0)).xyz);
    vec3 toSun = normalize(-uSunDirection);

    float zenithAngle = acos(clamp(direction.y, -1.0, 1.0));
    float lutX = clamp(zenithAngle / (0.5 * ATMOSPHERE_PI), 0.0, 1.0);
    float lutY = 0.5;
    if (length(direction.xz) > 1.0e-5) {
        vec3 sunHorizontal = vec3(toSun.x, 0.0, toSun.z);
        float horizontalLength = length(sunHorizontal);
        sunHorizontal = horizontalLength > 1.0e-5
                ? sunHorizontal / horizontalLength : vec3(0.0, 0.0, 1.0);
        vec3 sunRight = normalize(cross(vec3(0.0, 1.0, 0.0), sunHorizontal));
        float azimuth = atan(dot(direction, sunRight), dot(direction, sunHorizontal));
        lutY = azimuth / (2.0 * ATMOSPHERE_PI) + 0.5;
    }

    vec3 radiance = texture(uSkyViewLut, vec2(lutX, lutY)).rgb * uSunIntensity;

    // The solar disc is a bounded display term outside the single-scattering
    // integral; it fades below the local horizontal instead of showing a sun
    // through the implicit planet.  Working in angle space keeps very small
    // angular radii from collapsing both smoothstep edges to 1.0 in float.
    float sunCosine = clamp(dot(direction, toSun), -1.0, 1.0);
    float sunAngle = acos(sunCosine);
    float disc = 1.0 - smoothstep(uSunAngularRadius * 0.6, uSunAngularRadius, sunAngle);
    float sunAboveHorizon = smoothstep(-0.02, 0.02, toSun.y);
    radiance += vec3(uSunDiscIntensity) * disc * sunAboveHorizon;

    // Clamp to the largest finite half-float value so an allowed but extreme
    // sun-intensity/exposure combination cannot turn the HDR target into inf.
    vec3 exposed = max(radiance, vec3(0.0)) * uExposure;
    fragColor = vec4(min(exposed, vec3(60000.0)), 1.0);
}
