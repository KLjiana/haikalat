package com.kaleblangley.haikalat.core.curve;

import org.joml.Vector4f;

import java.util.Objects;

/** Immutable multi-stop linear RGBA gradient. RGB values remain in the caller's linear space. */
public final class ColorGradient {
    private final Stop[] stops;

    public ColorGradient(Stop... stops) {
        Objects.requireNonNull(stops, "stops");
        if (stops.length == 0) throw new IllegalArgumentException("gradient requires at least one stop");
        this.stops = stops.clone();
        float previous = -1.0f;
        for (int index = 0; index < this.stops.length; index++) {
            Stop stop = Objects.requireNonNull(this.stops[index], "stops[" + index + "]");
            if (stop.time <= previous) {
                throw new IllegalArgumentException("stop times must be strictly increasing");
            }
            previous = stop.time;
        }
    }

    /** Samples into caller-owned storage without allocating. */
    public Vector4f sample(float normalizedTime, Vector4f destination) {
        float time = CurveMath.requireNormalizedTime(normalizedTime);
        Vector4f result = Objects.requireNonNull(destination, "destination");
        if (time <= stops[0].time) return stops[0].write(result);
        int last = stops.length - 1;
        if (time >= stops[last].time) return stops[last].write(result);

        int low = segmentIndex(time, last);
        Stop start = stops[low];
        Stop end = stops[low + 1];
        float progress = (time - start.time) / (end.time - start.time);
        return result.set(
                lerp(start.red, end.red, progress),
                lerp(start.green, end.green, progress),
                lerp(start.blue, end.blue, progress),
                lerp(start.alpha, end.alpha, progress));
    }

    public int stopCount() {
        return stops.length;
    }

    public Stop stop(int index) {
        return stops[index];
    }

    private static float lerp(float first, float second, float progress) {
        return first + (second - first) * progress;
    }

    private int segmentIndex(float time, int last) {
        if (stops.length <= 4) {
            int index = 0;
            while (index + 1 < last && time >= stops[index + 1].time) index++;
            return index;
        }
        int low = 0;
        int high = last;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (stops[middle].time <= time) low = middle;
            else high = middle;
        }
        return low;
    }

    public record Stop(float time, float red, float green, float blue, float alpha) {
        public Stop {
            CurveMath.requireNormalizedTime(time);
            requireNonNegative(red, "red");
            requireNonNegative(green, "green");
            requireNonNegative(blue, "blue");
            CurveMath.requireFinite(alpha, "alpha");
            if (alpha < 0.0f || alpha > 1.0f) {
                throw new IllegalArgumentException("alpha must be within [0, 1]");
            }
        }

        private Vector4f write(Vector4f destination) {
            return destination.set(red, green, blue, alpha);
        }

        private static void requireNonNegative(float value, String name) {
            CurveMath.requireFinite(value, name);
            if (value < 0.0f) throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
