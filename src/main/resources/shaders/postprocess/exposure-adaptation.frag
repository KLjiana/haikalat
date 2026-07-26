#version 330 core
out float FragExposure;

uniform sampler2D uAverageLogLuminance;
uniform sampler2D uPreviousExposure;
uniform int uHistoryValid;
uniform float uInitialExposure;
uniform float uMinExposure;
uniform float uMaxExposure;
uniform float uKeyValue;
uniform float uBrightenSpeed;
uniform float uDarkenSpeed;
uniform float uDeltaSeconds;

void main() {
    vec2 reduction = texelFetch(uAverageLogLuminance, ivec2(0), 0).rg;
    float averageLogLuminance = reduction.r / max(reduction.g, 1.0);
    float averageLuminance = exp(averageLogLuminance);
    float targetExposure = clamp(
            uKeyValue / max(averageLuminance, 1e-4), uMinExposure, uMaxExposure);
    float previousExposure = uHistoryValid != 0
            ? texelFetch(uPreviousExposure, ivec2(0), 0).r
            : uInitialExposure;
    previousExposure = clamp(previousExposure, uMinExposure, uMaxExposure);
    float speed = targetExposure > previousExposure ? uBrightenSpeed : uDarkenSpeed;
    float weight = 1.0 - exp(-speed * uDeltaSeconds);
    FragExposure = clamp(mix(previousExposure, targetExposure, weight),
            uMinExposure, uMaxExposure);
}
