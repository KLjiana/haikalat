#version 460 core

in vec2 vNdc;
layout(location = 0) out vec4 FragColor;
uniform samplerCube uEnvironment;
uniform mat4 uInverseProjection;
uniform mat4 uInverseViewRotation;
uniform float uIntensity;
uniform float uRotation;

void main() {
    vec4 view = uInverseProjection * vec4(vNdc, 1.0, 1.0);
    vec3 direction = normalize((uInverseViewRotation * vec4(view.xyz / view.w, 0.0)).xyz);
    float c = cos(uRotation);
    float s = sin(uRotation);
    direction = vec3(c * direction.x - s * direction.z, direction.y,
                     s * direction.x + c * direction.z);
    FragColor = vec4(textureLod(uEnvironment, direction, 0.0).rgb * uIntensity, 1.0);
}
