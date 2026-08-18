#version 460 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aTexCoord;
layout (location = 5) in vec4 aJoints;
layout (location = 6) in vec4 aWeights;
layout(std430, binding = 7) readonly buffer JointPaletteBlock { mat4 uJointMatrices[]; };
layout(std430, binding = 8) readonly buffer MorphDeltaBlock { vec4 uMorphDeltas[]; };
layout(std430, binding = 9) readonly buffer MorphWeightBlock { float uMorphWeights[]; };
uniform mat4 uLightSpace;
uniform mat4 uModel;
uniform int uSkinningEnabled;
uniform int uMorphTargetCount;
uniform int uMorphVertexCount;
out vec2 vTexCoord;
mat4 skinMatrix() {
    if (uSkinningEnabled == 0) return mat4(1.0);
    uvec4 joints = uvec4(aJoints + vec4(0.5));
    vec4 weights = max(aWeights, vec4(0.0));
    float total = dot(weights, vec4(1.0));
    weights = total > 0.0 ? weights / total : vec4(1.0, 0.0, 0.0, 0.0);
    return uJointMatrices[joints.x] * weights.x + uJointMatrices[joints.y] * weights.y
        + uJointMatrices[joints.z] * weights.z + uJointMatrices[joints.w] * weights.w;
}
vec3 morphPosition() {
    vec3 position = aPos;
    for (int target = 0; target < uMorphTargetCount; target++) {
        float weight = uMorphWeights[target];
        if (weight == 0.0) continue;
        int base = (target * uMorphVertexCount + gl_VertexID) * 3;
        position += uMorphDeltas[base].xyz * weight;
    }
    return position;
}
void main() {
    vTexCoord = aTexCoord;
    gl_Position = uLightSpace * uModel * skinMatrix() * vec4(morphPosition(), 1.0);
}
