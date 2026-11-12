#version 460 core

// Stochastic transparency surface pass-through.  One instance is a world-space
// parallelogram: center is the midpoint and aHalfRight/aHalfUp are the edge
// vectors from the centre to the right/top edge.  The shared unit quad provides
// the corner in [-1, 1].  gl_InstanceID is forwarded as a flat layer identity so
// the stochastic threshold decorrelates overlapping surfaces without an extra
// instance attribute; the pass writes no depth outside its own sample target.

layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec3 aCenter;
layout(location = 2) in vec3 aHalfRight;
layout(location = 3) in vec3 aHalfUp;
layout(location = 4) in vec4 aColor;

uniform mat4 uView;
uniform mat4 uProjection;

out vec4 vColor;
flat out uint vInstanceId;

void main() {
    vec3 world = aCenter + aHalfRight * aCorner.x + aHalfUp * aCorner.y;
    vec4 viewPosition = uView * vec4(world, 1.0);
    vColor = aColor;
    vInstanceId = uint(gl_InstanceID);
    gl_Position = uProjection * viewPosition;
}
