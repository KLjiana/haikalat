#version 330 core
out vec4 fragColor;
uniform sampler2D uSource;
uniform vec2 uViewport;
uniform int uEnvironment;
void main() {
    vec2 uv = gl_FragCoord.xy / uViewport;
    vec3 world = texture(uSource, uv).rgb;
    float veil = .62 - .10 * smoothstep(.15, .9, 1.0 - uv.y);
    if (uEnvironment == 1) veil *= .55;
    if (uEnvironment == 3) veil *= .85;
    fragColor = vec4(world * veil * vec3(.94, 1.0, 1.02), 1.0);
}
