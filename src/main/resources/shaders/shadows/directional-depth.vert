#version 460 core
layout (location = 0) in vec3 aPos;
layout (location = 5) in vec4 aJoints;
layout (location = 6) in vec4 aWeights;

layout(std430, binding = 7) readonly buffer JointPaletteBlock {
    mat4 uJointMatrices[];
};

uniform mat4 uLightSpace;
uniform mat4 uModel;
uniform int uSkinningEnabled;

mat4 skinMatrix() {
    if (uSkinningEnabled == 0) {
        return mat4(1.0);
    }
    uvec4 joints = uvec4(aJoints + vec4(0.5));
    vec4 weights = max(aWeights, vec4(0.0));
    float totalWeight = dot(weights, vec4(1.0));
    weights = totalWeight > 0.0 ? weights / totalWeight : vec4(1.0, 0.0, 0.0, 0.0);
    return uJointMatrices[joints.x] * weights.x
        + uJointMatrices[joints.y] * weights.y
        + uJointMatrices[joints.z] * weights.z
        + uJointMatrices[joints.w] * weights.w;
}

void main() {
    gl_Position = uLightSpace * uModel * skinMatrix() * vec4(aPos, 1.0);
}
