// Shared water domain: a horizontal finite cap with vertical sides. There is
// no synthetic bottom; opaque scene depth (or a caller's distance cap) ends it.
uniform float uWaterLevel;
uniform vec2 uWaterMinimum;
uniform vec2 uWaterMaximum;

bool insideWaterFootprint(vec2 xz) {
    return all(greaterThanEqual(xz, uWaterMinimum)) && all(lessThanEqual(xz, uWaterMaximum));
}

bool clipWaterAxis(float origin, float direction, float low, float high, inout vec2 interval) {
    if (abs(direction) < 1.0e-7) return origin >= low && origin <= high;
    float a = (low - origin) / direction;
    float b = (high - origin) / direction;
    interval = vec2(max(interval.x, min(a, b)), min(interval.y, max(a, b)));
    return interval.y > interval.x;
}

// Distances along a normalized ray, in world metres.
vec2 waterInterval(vec3 origin, vec3 direction, float limit) {
    vec2 interval = vec2(0.0, limit);
    if (!clipWaterAxis(origin.x, direction.x, uWaterMinimum.x, uWaterMaximum.x, interval)
            || !clipWaterAxis(origin.z, direction.z, uWaterMinimum.y, uWaterMaximum.y, interval)) {
        return vec2(0.0);
    }
    if (direction.y > 1.0e-7) interval.y = min(interval.y, (uWaterLevel - origin.y) / direction.y);
    else if (direction.y < -1.0e-7) interval.x = max(interval.x, (uWaterLevel - origin.y) / direction.y);
    else if (origin.y > uWaterLevel) return vec2(0.0);
    return interval.y > interval.x ? interval : vec2(0.0);
}
