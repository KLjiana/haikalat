#version 330 core
out vec4 FragColor;
in vec2 vUv;
uniform sampler2D uScene;
uniform vec2 uInvResolution;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 center = texture(uScene, vUv).rgb;
    vec3 north = texture(uScene, vUv + vec2(0.0, uInvResolution.y)).rgb;
    vec3 south = texture(uScene, vUv - vec2(0.0, uInvResolution.y)).rgb;
    vec3 east  = texture(uScene, vUv + vec2(uInvResolution.x, 0.0)).rgb;
    vec3 west  = texture(uScene, vUv - vec2(uInvResolution.x, 0.0)).rgb;

    float edge = abs(luma(north) - luma(south)) + abs(luma(east) - luma(west));
    float blend = smoothstep(0.08, 0.35, edge);

    vec3 aa = (center * 0.5) + ((north + south + east + west) * 0.125);
    FragColor = vec4(mix(center, aa, blend), 1.0);
}
