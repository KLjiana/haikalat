#version 330 core
out vec4 fragColor;
uniform vec2 uResolution;
uniform float uTime;
uniform int uEnvironment;

// A small procedural voxel diorama, authored for this demo. No external game assets.
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float heightAt(vec2 p) {
    float bank = abs(p.x - 4.0 * sin(p.y * .06));
    return floor(2.0 + min(bank * .36, 6.0) + 2.0 * sin(p.x * .09) + 1.8 * sin(p.y * .13));
}
int voxel(vec3 p) {
    float h = heightAt(p.xz);
    if (uEnvironment == 2 && p.y > 19.0 + 3.0 * sin(p.x * .14) * sin(p.z * .14)) return 4;
    if (p.y <= h) return p.y == h ? 1 : 4;
    if (p.y < 3.0) return 2;
    if (uEnvironment == 2) return 0;
    vec2 cell = floor(p.xz / 9.0);
    vec2 center = cell * 9.0 + vec2(4.0);
    float ground = heightAt(center);
    vec2 d = abs(p.xz - center);
    float seed = hash(cell);
    if (ground > 3.0 && seed > .22) {
        float trunk = 8.0 + floor(seed * 5.0);
        float level = p.y - ground;
        if (max(d.x, d.y) < .6 && level < trunk) return 3;
        float radius = floor((trunk + 3.0 - level) * .34);
        if (level > 3.0 && level < trunk + 3.0 && max(d.x, d.y) <= radius) return 5;
    }
    return 0;
}
void main() {
    vec2 uv = (gl_FragCoord.xy * 2.0 - uResolution) / uResolution.y;
    vec3 origin = vec3(-4.5 + .3 * sin(uTime * .08), 18.5, -28.5);
    if (uEnvironment == 2) origin.y = 11.5;
    vec3 forward = normalize(vec3(.10, -.25, 1.0));
    vec3 right = normalize(cross(forward, vec3(0, 1, 0)));
    vec3 up = cross(right, forward);
    vec3 ray = normalize(forward * 1.5 - right * uv.x + up * uv.y);
    vec3 sun = normalize(vec3(-.5, .8, .4));
    vec3 sky = mix(vec3(.40, .59, .56), vec3(.13, .31, .38), max(ray.y, 0.0));
    if (uEnvironment == 1) sky = mix(vec3(.8, .86, .9), vec3(.31, .46, .62), max(ray.y, 0.0));
    if (uEnvironment == 2) sky = vec3(.025, .045, .06);
    if (uEnvironment == 3) sky = mix(vec3(.34, .095, .045), vec3(.08, .035, .045), max(ray.y, 0.0));
    sky += vec3(.6, .43, .17) * pow(max(dot(ray, sun), 0.0), 40.0);
    vec3 cell = floor(origin), stepDir = sign(ray);
    vec3 inverse = 1.0 / max(abs(ray), vec3(.0001));
    vec3 side = (stepDir * (cell - origin) + stepDir * .5 + .5) * inverse;
    vec3 normal = vec3(0, 1, 0);
    float distance = 0.0;
    int material = 0;
    for (int i = 0; i < 230; i++) {
        material = voxel(cell);
        if (material != 0 || distance > 130.0) break;
        if (side.x < side.y && side.x < side.z) {
            distance = side.x; side.x += inverse.x; cell.x += stepDir.x; normal = vec3(-stepDir.x, 0, 0);
        } else if (side.y < side.z) {
            distance = side.y; side.y += inverse.y; cell.y += stepDir.y; normal = vec3(0, -stepDir.y, 0);
        } else {
            distance = side.z; side.z += inverse.z; cell.z += stepDir.z; normal = vec3(0, 0, -stepDir.z);
        }
    }
    vec3 color = sky;
    if (material != 0 && distance < 130.0) {
        vec3 albedo = material == 1 ? vec3(.18, .32, .085) : material == 2 ? vec3(.07, .29, .34)
                : material == 3 ? vec3(.23, .14, .085) : material == 5 ? vec3(.065, .21, .075) : vec3(.29, .31, .28);
        if (uEnvironment == 1 && (material == 1 || material == 5 && normal.y > 0.0)) albedo = vec3(.82, .87, .88);
        if (uEnvironment == 3) albedo = material == 2 ? vec3(1.0, .22, .025) : albedo * vec3(.8, .43, .32);
        float variation = .86 + .22 * hash(cell.xz + cell.y);
        float light = .38 + .62 * max(dot(normal, sun), 0.0);
        color = albedo * light * variation;
        if (material == 2) color += .08 * pow(max(dot(reflect(ray, normal), sun), 0.0), 20.0);
        if (uEnvironment == 2) color *= vec3(.3, .44, .6) + 1.7 * exp(-distance * .075);
        if (uEnvironment == 3 && material == 2) color = albedo * 1.4;
        float fog = 1.0 - exp(-distance * (uEnvironment == 2 ? .025 : .005));
        color = mix(color, sky, fog);
    }
    // Gentle edge protection keeps the centre open without blurring the whole world.
    vec2 screen = gl_FragCoord.xy / uResolution;
    float edge = 1.0 - smoothstep(.08, .50, screen.x) + smoothstep(.55, .98, screen.x);
    color *= 1.0 - .24 * clamp(edge, 0.0, 1.0);
    fragColor = vec4(color, 1.0);
}
