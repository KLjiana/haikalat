#version 460 core

// Full-screen quad for the six-face DDGI irradiance relight. One draw covers
// one probe face tile; the viewport selects the tile and uTileOrigin tells the
// fragment stage where that tile starts in the atlas. Only clip-space position
// is needed.

layout(location = 0) in vec3 aPosition;

void main() {
    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
}
