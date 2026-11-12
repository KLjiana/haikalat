#version 460 core

/*
 * Depth-only shadow-caster stage for the hair-strand set (#46).
 *
 * The forward pass expands a camera-facing ribbon in screen space. A shadow map
 * needs a world-space silhouette instead, so this stage expands the same
 * centreline along a light-facing side vector by the authored strand radius.
 * The caller binds the existing shadow atlas framebuffer and dirty tile and
 * passes the same light-space matrix the ordinary shadow caster uses.
 */

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aTangent;
layout(location = 3) in float aCorner;
layout(location = 2) in float aStrandParameter;

uniform mat4 uLightSpace;
uniform mat4 uModel;
uniform vec3 uLightTravelDirection;
uniform float uStrandRadiusMetres;

out float vStrandParameter;

void main() {
    vec3 world = (uModel * vec4(aPosition, 1.0)).xyz;
    vec3 tangent = mat3(uModel) * aTangent;
    float tangentLengthSquared = dot(tangent, tangent);
    tangent = tangentLengthSquared > 1.0e-12 ? tangent * inversesqrt(tangentLengthSquared)
            : vec3(0.0, 1.0, 0.0);
    vec3 lightDirection = uLightTravelDirection;
    float lightLengthSquared = dot(lightDirection, lightDirection);
    lightDirection = lightLengthSquared > 1.0e-12
            ? lightDirection * inversesqrt(lightLengthSquared) : vec3(0.0, -1.0, 0.0);
    vec3 side = cross(tangent, lightDirection);
    float sideLengthSquared = dot(side, side);
    side = sideLengthSquared > 1.0e-10
            ? side * inversesqrt(sideLengthSquared) : vec3(1.0, 0.0, 0.0);
    world += side * (aCorner * uStrandRadiusMetres);
    vStrandParameter = aStrandParameter;
    gl_Position = uLightSpace * vec4(world, 1.0);
}
