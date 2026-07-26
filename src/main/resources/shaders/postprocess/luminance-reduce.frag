#version 330 core
out vec2 FragReduction;

uniform sampler2D uInput;
uniform vec2 uInputSize;
uniform vec2 uOutputSize;
uniform int uInputHasWeights;

void main() {
    ivec2 inputSize = ivec2(uInputSize + vec2(0.5));
    ivec2 outputSize = ivec2(uOutputSize + vec2(0.5));
    ivec2 outputCoord = ivec2(gl_FragCoord.xy);
    ivec2 begin = outputCoord * inputSize / outputSize;
    ivec2 end = (outputCoord + ivec2(1)) * inputSize / outputSize;

    vec2 total = vec2(0.0);
    for (int y = begin.y; y < end.y; ++y) {
        for (int x = begin.x; x < end.x; ++x) {
            ivec2 inputCoord = ivec2(x, y);
            vec2 sampleValue = uInputHasWeights != 0
                    ? texelFetch(uInput, inputCoord, 0).rg
                    : vec2(texelFetch(uInput, inputCoord, 0).r, 1.0);
            total += sampleValue;
        }
    }
    FragReduction = total;
}
