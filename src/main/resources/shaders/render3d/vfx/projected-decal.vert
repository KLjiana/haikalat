#version 460 core

layout (location = 0) in vec3 aPosition;

uniform mat4 uProjectionView;
uniform mat4 uModel;

void main() {
    // BuiltinMeshData.coloredCube spans [-0.5, 0.5] on every axis.  Local z is
    // remapped into the decal receiver interval [0, 1] that uModel scales from
    // +surfaceOffset in front of the plane to -thickness behind it.
    vec3 decalSpace = vec3(aPosition.x, aPosition.y, aPosition.z + 0.5);
    gl_Position = uProjectionView * (uModel * vec4(decalSpace, 1.0));
}
