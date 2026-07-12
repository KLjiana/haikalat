#version 460 core

uniform mat4 uProjView;
uniform int uShape;
uniform int uColumns;
uniform int uRows;
uniform float uSpacing;
uniform float uScale;
uniform float uTime;

out vec3 vColor;

const vec2 TRIANGLE[3] = vec2[3](
    vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.0, 0.5)
);

const vec2 QUAD[6] = vec2[6](
    vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.5, 0.5),
    vec2(-0.5, -0.5), vec2(0.5, 0.5), vec2(-0.5, 0.5)
);

const vec3 CUBE_CORNERS[8] = vec3[8](
    vec3(-0.5, -0.5, -0.5), vec3(0.5, -0.5, -0.5),
    vec3(0.5, 0.5, -0.5), vec3(-0.5, 0.5, -0.5),
    vec3(-0.5, -0.5, 0.5), vec3(0.5, -0.5, 0.5),
    vec3(0.5, 0.5, 0.5), vec3(-0.5, 0.5, 0.5)
);

const int CUBE_INDICES[36] = int[36](
    4, 5, 6, 4, 6, 7, 1, 0, 3, 1, 3, 2,
    0, 4, 7, 0, 7, 3, 5, 1, 2, 5, 2, 6,
    7, 6, 2, 7, 2, 3, 0, 1, 5, 0, 5, 4
);

vec3 localVertex() {
    if (uShape == 0) return vec3(TRIANGLE[gl_VertexID], 0.0);
    if (uShape == 1) return vec3(QUAD[gl_VertexID], 0.0);
    return CUBE_CORNERS[CUBE_INDICES[gl_VertexID]];
}

void main() {
    int column = gl_InstanceID % uColumns;
    int row = gl_InstanceID / uColumns;
    vec2 gridCenter = vec2(float(uColumns - 1), float(uRows - 1)) * 0.5;
    vec2 translation = (vec2(column, row) - gridCenter) * uSpacing;
    float depth = uShape == 2 ? float((gl_InstanceID % 17) - 8) * 0.008 : 0.0;

    float angle = float(gl_InstanceID % 31) * 0.017 + uTime * 0.15;
    float c = cos(angle);
    float s = sin(angle);
    vec3 local = localVertex() * uScale;
    local.xy = mat2(c, -s, s, c) * local.xy;

    uint hash = uint(gl_InstanceID) * 1664525u + 1013904223u;
    vColor = vec3(
        0.25 + 0.75 * float(hash & 255u) / 255.0,
        0.25 + 0.75 * float((hash >> 8u) & 255u) / 255.0,
        0.25 + 0.75 * float((hash >> 16u) & 255u) / 255.0
    );
    gl_Position = uProjView * vec4(local + vec3(translation, depth), 1.0);
}
