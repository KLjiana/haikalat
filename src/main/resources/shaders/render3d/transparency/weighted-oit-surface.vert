#version 460 core

// Weighted blended OIT surface pass-through.  One instance is a world-space
// parallelogram: center is the midpoint and aHalfRight/aHalfUp are the edge
// vectors from the centre to the right/top edge.  Corners come from the shared
// unit quad in [-1, 1]; the view depth is computed here so the fragment weight
// uses the interpolated surface depth rather than the depth buffer.

layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec3 aCenter;
layout(location = 2) in vec3 aHalfRight;
layout(location = 3) in vec3 aHalfUp;
layout(location = 4) in vec4 aColor;

uniform mat4 uView;
uniform mat4 uProjection;

out vec4 vColor;
out float vViewDepth;

void main() {
    vec3 world = aCenter + aHalfRight * aCorner.x + aHalfUp * aCorner.y;
    vec4 viewPosition = uView * vec4(world, 1.0);
    vColor = aColor;
    vViewDepth = -viewPosition.z;
    gl_Position = uProjection * viewPosition;
}
