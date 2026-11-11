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

layout(std430, binding = 8) readonly buffer MorphDeltaBlock {
    vec4 uMorphDeltas[];
};

layout(std430, binding = 9) readonly buffer MorphWeightBlock {
    float uMorphWeights[];
};

layout(std430, binding = 10) readonly buffer PreviousJointPaletteBlock {
    mat4 uPreviousJointMatrices[];
};

layout(std430, binding = 11) readonly buffer PreviousMorphWeightBlock {
    float uPreviousMorphWeights[];
};

layout(std140) uniform CameraBlock {
    mat4 uProjection;
    mat4 uView;
};

uniform mat4 uModel;
uniform mat4 uPreviousModel;
uniform mat4 uCurrentStableViewProjection;
uniform mat4 uPreviousStableViewProjection;
uniform mat4 uPreviousView;
uniform vec3 uCameraPosition;
uniform int uSkinningEnabled;
uniform int uMorphTargetCount;
uniform int uMorphVertexCount;
uniform int uPreviousValid;

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
    vec3 perturbed = localNormal - waveDirection * (slope * dot(axis, localNormal));
    float lengthSquared = dot(perturbed, perturbed);
    return lengthSquared > 1.0e-12 ? normalize(perturbed) : localNormal;
}
// END VERTEX_DISPLACEMENT

out vec2 vTexCoord;
out vec4 vVertexColor;
out vec3 vWorldNormal;
out vec3 vWorldPosition;
out vec4 vCurrentClip;
out vec4 vPreviousClip;
out float vPreviousViewDepth;
out float vPreviousValid;

mat4 skinMatrixCurrent() {
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

mat4 skinMatrixPrevious() {
    if (uSkinningEnabled == 0) {
        return mat4(1.0);
    }
    uvec4 joints = uvec4(aJoints + vec4(0.5));
    vec4 weights = max(aWeights, vec4(0.0));
    float totalWeight = dot(weights, vec4(1.0));
    weights = totalWeight > 0.0 ? weights / totalWeight : vec4(1.0, 0.0, 0.0, 0.0);
    return uPreviousJointMatrices[joints.x] * weights.x
        + uPreviousJointMatrices[joints.y] * weights.y
        + uPreviousJointMatrices[joints.z] * weights.z
        + uPreviousJointMatrices[joints.w] * weights.w;
}

void applyMorph(inout vec3 position, inout vec3 normal, inout vec3 tangent, bool previous) {
    for (int target = 0; target < uMorphTargetCount; target++) {
        float weight = previous ? uPreviousMorphWeights[target] : uMorphWeights[target];
        if (weight == 0.0) {
            continue;
        }
        int base = (target * uMorphVertexCount + gl_VertexID) * 3;
        position += uMorphDeltas[base].xyz * weight;
        normal += uMorphDeltas[base + 1].xyz * weight;
        tangent += uMorphDeltas[base + 2].xyz * weight;
    }
}

void main() {
    bool hasPrevious = uPreviousValid != 0;
    vec3 position = aPosition;
    vec3 localNormal = aNormal;
    vec3 localTangent = aTangent.xyz;
    applyMorph(position, localNormal, localTangent, false);
    if (dot(localNormal, localNormal) <= 1.0e-12) {
        localNormal = aNormal;
    }

    vec3 previousPosition = position;
    vec3 previousNormal = localNormal;
    vec3 previousTangent = localTangent;
    if (hasPrevious) {
        previousPosition = aPosition;
        previousNormal = aNormal;
        previousTangent = aTangent.xyz;
        applyMorph(previousPosition, previousNormal, previousTangent, true);
        if (dot(previousNormal, previousNormal) <= 1.0e-12) {
            previousNormal = aNormal;
        }
    }

    // Fixed deformation order: morph -> local vertex displacement -> skinning.
    // The previous position uses the committed previous time with the same
    // model parameters; a parameter edit invalidates previous validity.
    vec3 restPosition = position;
    position = vertexDisplacementPosition(restPosition, uDisplacementTime);
    localNormal = vertexDisplacementNormal(restPosition, localNormal, uDisplacementTime);
    if (hasPrevious) {
        vec3 previousRestPosition = previousPosition;
        previousPosition = vertexDisplacementPosition(previousRestPosition, uDisplacementPreviousTime);
    }
    mat4 currentModelSkin = uModel * skinMatrixCurrent();
    mat4 previousModelSkin = hasPrevious ? (uPreviousModel * skinMatrixPrevious()) : currentModelSkin;
    vec4 world = currentModelSkin * vec4(position, 1.0);
    vec4 previousWorld = previousModelSkin * vec4(previousPosition, 1.0);

    mat3 normalMatrix = transpose(inverse(mat3(currentModelSkin)));
    vTexCoord = aTexCoord;
    vVertexColor = aColor;
    vWorldPosition = world.xyz;
    vWorldNormal = normalize(normalMatrix * localNormal);
    vCurrentClip = uCurrentStableViewProjection * world;
    vPreviousClip = uPreviousStableViewProjection * previousWorld;
    vPreviousViewDepth = -(uPreviousView * previousWorld).z;
    vPreviousValid = hasPrevious && vPreviousClip.w > 0.0 ? 1.0 : 0.0;
    // Use the exact CameraBlock matrices and multiplication order as the
    // forward shader so shared-depth LEQUAL keeps bit-identical fragments.
    gl_Position = uProjection * uView * world;
}
