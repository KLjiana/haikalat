#version 460 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in vec4 aTangent;
layout(location = 4) in vec4 aColor;
layout(location = 5) in vec4 aJoints;
layout(location = 6) in vec4 aWeights;

layout(std430, binding = 7) readonly buffer JointPaletteBlock {
    mat4 uJointMatrices[];
};

layout(std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};

uniform mat4 uModel;
uniform mat4 uDirectionalLightSpace;
uniform int uSkinningEnabled;

out vec2 vTexCoord;
out vec3 vWorldPosition;
out vec3 vNormal;
out vec3 vTangent;
out float vTangentHandedness;
out vec4 vDirectionalLightPosition;
out vec4 vVertexColor;

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
    mat4 modelSkin = uModel * skinMatrix();
    vec4 world = modelSkin * vec4(aPosition, 1.0);
    mat3 normalMatrix = transpose(inverse(mat3(modelSkin)));
    vec3 n = normalize(normalMatrix * aNormal);
    vec3 t = normalize(normalMatrix * aTangent.xyz);
    vTexCoord = aTexCoord;
    vWorldPosition = world.xyz;
    vNormal = n;
    vTangent = normalize(t - n * dot(n, t));
    vTangentHandedness = aTangent.w * sign(determinant(mat3(modelSkin)));
    vVertexColor = aColor;
    vDirectionalLightPosition = uDirectionalLightSpace * world;
    gl_Position = uProjection * uView * world;
}
