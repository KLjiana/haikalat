#version 460 core
uniform vec2 uViewport;
uniform float uPhase;
uniform int uChecker;
layout(location=0) out vec4 outColor;
void main() {
    vec2 p=vec2(gl_FragCoord.x/uViewport.x*640.0,(1.0-gl_FragCoord.y/uViewport.y)*360.0);
    float checker=mod(floor((p.x+uPhase)/16.0)+floor(p.y/16.0),2.0);
    outColor=uChecker==0?vec4(0,0,1,1):vec4(mix(vec3(.08,.14,.25),vec3(.45,.26,.08),checker),1);
}
