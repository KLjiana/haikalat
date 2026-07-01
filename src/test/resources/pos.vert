#version 330 core
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 uv;
out vec3 vshColor;
out vec2 fragUv;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
void main()
{
    gl_Position = projection * view * model * vec4(aPos, 1.0f);
    vshColor = vec3(1, 1, 1);
    fragUv = uv;
}