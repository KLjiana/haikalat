#version 330 core
in vec2 vUv;
out float FragLogLuminance;

uniform sampler2D uHdrScene;
void main() {
    vec3 linearRgb = texture(uHdrScene, vUv).rgb;
    float luminance = dot(linearRgb, vec3(0.2126, 0.7152, 0.0722));
    FragLogLuminance = log(max(luminance, 1e-4));
}
