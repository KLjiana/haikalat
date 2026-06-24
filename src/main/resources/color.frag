#version 330 core
in vec3 vshColor;
out vec4 FragColor;

void main()
{
    FragColor = vec4(vshColor, 1.0);
}