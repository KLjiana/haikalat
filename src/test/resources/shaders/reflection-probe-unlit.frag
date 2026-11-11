#version 460 core

layout(location = 0) out vec4 FragColor;

uniform int uUnlitVisible;

void main() {
    if (uUnlitVisible == 0) discard;
    FragColor = vec4(1.0, 0.0, 0.0, 1.0);
}
