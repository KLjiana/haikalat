#version 330 core
in vec3 vshColor;
in vec2 fragUv;
out vec4 FragColor;

uniform sampler2D Tex1;
uniform sampler2D Tex2;
void main()
{
    FragColor = mix(texture(Tex1, fragUv), texture(Tex2, fragUv), 0.2);
}