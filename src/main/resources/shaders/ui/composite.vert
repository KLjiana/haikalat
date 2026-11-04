#version 460 core
uniform vec2 uViewport;
uniform vec4 uRect;
out vec2 vUv;
void main() {
    const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
    vec2 p=corners[gl_VertexID];
    vec2 position=(uRect.xy+p*uRect.zw)/uViewport;
    gl_Position=vec4(position.x*2-1,1-position.y*2,0,1);
    vUv=vec2(p.x,1-p.y);
}
