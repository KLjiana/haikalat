#version 330 core
in vec2 vTexCoord;
out vec4 FragColor;
uniform sampler2D uTexture;
uniform vec3 uTint;
void main() {
    FragColor = texture(uTexture, vTexCoord) * vec4(uTint, 1.0);
}
