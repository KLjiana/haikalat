#version 460 core

// Indirect draw vertex stage: the GPU active list, not the slot range, decides
// which particle storage entries become billboards. Dead particles are absent
// from the list because the update pass only appends positive lifetimes.
struct Particle {
    vec4 positionLife;
    vec4 velocitySeed;
};

layout (std430, binding = 0) readonly buffer ParticleBuffer {
    Particle particles[];
};

layout (std430, binding = 2) readonly buffer ActiveIndexBuffer {
    uint activeIndices[];
};

uniform mat4 uViewProjection;
uniform int uCapacity;
uniform vec4 uColor;

layout (location = 0) out vec4 vColor;

void main() {
    uint vertex = uint(gl_VertexID);
    if (vertex >= uint(uCapacity)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vColor = vec4(0.0);
        return;
    }
    uint index = activeIndices[vertex];
    if (index >= uint(uCapacity)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vColor = vec4(0.0);
        return;
    }
    Particle particle = particles[index];
    gl_Position = uViewProjection * vec4(particle.positionLife.xyz, 1.0);
    float alpha = smoothstep(0.0, 0.35, particle.positionLife.w);
    vColor = vec4(uColor.rgb, uColor.a * alpha);
}
