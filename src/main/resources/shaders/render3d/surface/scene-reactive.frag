#version 330 core

layout (location = 0) out float FragReactive;

uniform float uTemporalReactive;

void main() {
    FragReactive = uTemporalReactive;
}
