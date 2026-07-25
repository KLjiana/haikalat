#version 460 core

struct Particle {
    vec4 positionLife;
    vec4 velocitySeed;
};

layout (std430, binding = 0) readonly buffer ParticleBuffer {
    Particle particles[];
};

uniform mat4 uViewProjection;

layout (location = 0) out vec4 vColor;

void main() {
    Particle particle = particles[gl_VertexID];
    gl_Position = uViewProjection * vec4(particle.positionLife.xyz, 1.0);
    float seed = particle.velocitySeed.w;
    float alpha = smoothstep(0.0, 0.35, particle.positionLife.w);
    vColor = vec4(mix(vec3(1.0, 0.2, 0.04), vec3(0.15, 0.65, 1.0), seed), alpha * 0.12);
}
