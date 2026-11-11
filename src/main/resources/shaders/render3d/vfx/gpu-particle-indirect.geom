#version 460 core

layout (points) in;
layout (triangle_strip, max_vertices = 4) out;

uniform float uViewportAspect;
uniform float uParticleSize;

layout (location = 0) in vec4 vColor[];
layout (location = 0) out vec4 gColor;
layout (location = 1) out vec2 gUv;

void emitCorner(vec2 corner) {
    vec4 center = gl_in[0].gl_Position;
    vec2 offset = vec2(corner.x / uViewportAspect, corner.y) * uParticleSize * center.w;
    gl_Position = center + vec4(offset, 0.0, 0.0);
    gColor = vColor[0];
    gUv = corner;
    EmitVertex();
}

void main() {
    // A centre behind the eye has a non-positive clip w; emitting its mirrored
    // billboard would be wrong, so the primitive is culled on the GPU.
    if (gl_in[0].gl_Position.w <= 0.0) {
        return;
    }
    emitCorner(vec2(-1.0, -1.0));
    emitCorner(vec2( 1.0, -1.0));
    emitCorner(vec2(-1.0,  1.0));
    emitCorner(vec2( 1.0,  1.0));
    EndPrimitive();
}
