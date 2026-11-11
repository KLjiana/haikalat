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
// BEGIN VERTEX_DISPLACEMENT
uniform int uDisplacementEnabled;
uniform float uDisplacementAmplitude;
uniform float uDisplacementFrequency;
uniform float uDisplacementSpeed;
uniform float uDisplacementPhase;
uniform vec3 uDisplacementAxis;
uniform vec3 uDisplacementWaveDirection;
uniform float uDisplacementTime;
uniform float uDisplacementPreviousTime;

vec3 vertexDisplacementOffset(vec3 localPosition, float timeSeconds) {
    if (uDisplacementEnabled == 0 || uDisplacementAmplitude <= 0.0) {
        return vec3(0.0);
    }
    vec3 axis = uDisplacementAxis;
    float axisLengthSquared = dot(axis, axis);
    if (axisLengthSquared <= 1.0e-12) {
        return vec3(0.0);
    }
    vec3 waveDirection = uDisplacementWaveDirection;
    float waveLengthSquared = dot(waveDirection, waveDirection);
    if (waveLengthSquared <= 1.0e-12) {
        return vec3(0.0);
    }
    axis *= inversesqrt(axisLengthSquared);
    waveDirection *= inversesqrt(waveLengthSquared);
    float phase = 6.28318530718 * (uDisplacementFrequency
            * dot(localPosition, waveDirection) + uDisplacementSpeed * timeSeconds)
            + uDisplacementPhase;
    return axis * (uDisplacementAmplitude * sin(phase));
}

vec3 vertexDisplacementPosition(vec3 localPosition, float timeSeconds) {
    if (uDisplacementEnabled == 0 || uDisplacementAmplitude <= 0.0) {
        return localPosition;
    }
    return localPosition + vertexDisplacementOffset(localPosition, timeSeconds);
}

vec3 vertexDisplacementNormal(vec3 localPosition, vec3 localNormal, float timeSeconds) {
    if (uDisplacementEnabled == 0 || uDisplacementAmplitude <= 0.0) {
        return localNormal;
    }
    vec3 axis = uDisplacementAxis;
    float axisLengthSquared = dot(axis, axis);
    if (axisLengthSquared <= 1.0e-12) {
        return localNormal;
    }
    vec3 waveDirection = uDisplacementWaveDirection;
    float waveLengthSquared = dot(waveDirection, waveDirection);
    if (waveLengthSquared <= 1.0e-12) {
        return localNormal;
    }
    axis *= inversesqrt(axisLengthSquared);
    waveDirection *= inversesqrt(waveLengthSquared);
    float phase = 6.28318530718 * (uDisplacementFrequency
            * dot(localPosition, waveDirection) + uDisplacementSpeed * timeSeconds)
            + uDisplacementPhase;
    float slope = 6.28318530718 * uDisplacementFrequency * uDisplacementAmplitude * cos(phase);
    float denominator = 1.0 + slope * dot(axis, waveDirection);
    if (abs(denominator) <= 1.0e-4) {
        return localNormal;
    }
    vec3 perturbed = localNormal
            - waveDirection * (slope * dot(axis, localNormal) / denominator);
    float lengthSquared = dot(perturbed, perturbed);
    return lengthSquared > 1.0e-12 ? normalize(perturbed) : localNormal;
}
// END VERTEX_DISPLACEMENT
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
    vec3 position = vertexDisplacementPosition(morphPosition(), uDisplacementTime);
    gl_Position = uLightSpace * uModel * skinMatrix() * vec4(position, 1.0);
}
