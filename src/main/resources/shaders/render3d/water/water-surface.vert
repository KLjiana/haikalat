#version 460 core

layout(location = 0) in vec3 aPosition;

uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;
uniform int uRepairNearPlane;

out vec3 vWorldPosition;

void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorldPosition = world.xyz;
    gl_Position = uRepairNearPlane == 1 ? vec4(aPosition.xz * 2.0, 0.0, 1.0)
            : uProjection * uView * world;
}
