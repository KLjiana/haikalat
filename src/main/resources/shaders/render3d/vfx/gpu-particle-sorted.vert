#version 460 core

// Indirect draw vertex stage for the sorted alpha-particle path (#26).
//
// The compute sort publishes storage indices into sortedIndices. The indirect
// command still comes from the counter buffer, so activeCount and the draw
// range are exactly those of the #27 owner; only the index indirection is new.
// Entries beyond the sorted prefix (a partial sort because the active set is
// larger than the configured sort bound) fall back to the published
// activeIndices order. Invalid or sentinel indices are placed off-screen.
//
// This program is separate from gpu-particle-indirect.vert on purpose: a
// disabled sort keeps compiling and recording the untouched #27 path, byte for
// byte.
struct Particle {
    vec4 positionLife;
    vec4 velocitySeed;
};

layout (std430, binding = 0) readonly buffer ParticleBuffer {
    Particle particles[];
};

layout (std430, binding = 1) readonly buffer CounterBuffer {
    uint activeCount;
    uint instanceCount;
    uint firstVertex;
    uint baseInstance;
} counters;

layout (std430, binding = 2) readonly buffer ActiveIndexBuffer {
    uint activeIndices[];
};

layout (std430, binding = 3) readonly buffer SortedIndexBuffer {
    uint sortedIndices[];
};

uniform mat4 uViewProjection;
uniform int uCapacity;
uniform int uMaximumSortedParticles;
uniform vec4 uColor;

layout (location = 0) out vec4 vColor;

void main() {
    uint vertex = uint(gl_VertexID);
    if (vertex >= uint(uCapacity)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vColor = vec4(0.0);
        return;
    }
    uint count = min(counters.activeCount, uint(uCapacity));
    uint sorted = min(count, uint(uMaximumSortedParticles));
    uint index = vertex < sorted ? sortedIndices[vertex] : activeIndices[vertex];
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
