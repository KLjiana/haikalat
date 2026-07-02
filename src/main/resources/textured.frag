#version 330 core
in vec2 vTexCoord;
out vec4 FragColor;
uniform sampler2D uTexture;
uniform vec3 uTint;
void main() {
    vec4 texColor = texture(uTexture, vTexCoord);
    FragColor = vec4(texColor.rgb * uTint, texColor.a);
}
