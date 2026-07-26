package com.kaleblangley.haikalat.core.curve;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** Immutable cubic Bezier path with a precomputed approximate arc-length table. */
public final class BezierPath3f {
    public static final int DEFAULT_ARC_SEGMENTS = 64;

    private final Vector3f p0;
    private final Vector3f p1;
    private final Vector3f p2;
    private final Vector3f p3;
    private final float[] cumulativeLengths;
    private final float totalLength;

    public BezierPath3f(Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3) {
        this(p0, p1, p2, p3, DEFAULT_ARC_SEGMENTS);
    }

    public BezierPath3f(Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3,
                        int arcSegments) {
        if (arcSegments < 2) throw new IllegalArgumentException("arcSegments must be at least 2");
        this.p0 = copyFinite(p0, "p0");
        this.p1 = copyFinite(p1, "p1");
        this.p2 = copyFinite(p2, "p2");
        this.p3 = copyFinite(p3, "p3");
        cumulativeLengths = new float[arcSegments + 1];
        Vector3f previous = new Vector3f(this.p0);
        Vector3f current = new Vector3f();
        double length = 0.0;
        for (int index = 1; index <= arcSegments; index++) {
            sampleUnchecked((float) index / arcSegments, current);
            length += previous.distance(current);
            cumulativeLengths[index] = (float) length;
            previous.set(current);
        }
        totalLength = (float) length;
        if (!Float.isFinite(totalLength)) {
            throw new IllegalArgumentException("path control points produce a non-finite arc length");
        }
    }

    public void sample(float parameter, Vector3f destination) {
        sampleUnchecked(CurveMath.requireNormalizedTime(parameter),
                Objects.requireNonNull(destination, "destination"));
    }

    /** Writes the analytic derivative. Degenerate tangents are represented by the zero vector. */
    public void tangent(float parameter, Vector3f destination) {
        float value = CurveMath.requireNormalizedTime(parameter);
        Vector3f result = Objects.requireNonNull(destination, "destination");
        float inverse = 1.0f - value;
        float first = 3.0f * inverse * inverse;
        float second = 6.0f * inverse * value;
        float third = 3.0f * value * value;
        result.set(
                first * (p1.x - p0.x) + second * (p2.x - p1.x) + third * (p3.x - p2.x),
                first * (p1.y - p0.y) + second * (p2.y - p1.y) + third * (p3.y - p2.y),
                first * (p1.z - p0.z) + second * (p2.z - p1.z) + third * (p3.z - p2.z));
    }

    /**
     * Maps normalized distance to the sampled path parameter. A zero-length path maps every
     * distance to parameter zero.
     */
    public float parameterAtNormalizedDistance(float normalizedDistance) {
        float distance = CurveMath.requireNormalizedTime(normalizedDistance);
        if (distance == 0.0f || totalLength == 0.0f) return 0.0f;
        if (distance == 1.0f) return 1.0f;
        float target = distance * totalLength;
        int low = 0;
        int high = cumulativeLengths.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (cumulativeLengths[middle] <= target) low = middle;
            else high = middle;
        }
        float first = cumulativeLengths[low];
        float second = cumulativeLengths[high];
        float local = second == first ? 0.0f : (target - first) / (second - first);
        return (low + local) / (cumulativeLengths.length - 1);
    }

    public float approximateLength() {
        return totalLength;
    }

    private void sampleUnchecked(float parameter, Vector3f destination) {
        float inverse = 1.0f - parameter;
        float first = inverse * inverse * inverse;
        float second = 3.0f * inverse * inverse * parameter;
        float third = 3.0f * inverse * parameter * parameter;
        float fourth = parameter * parameter * parameter;
        destination.set(p0).mul(first).fma(second, p1).fma(third, p2).fma(fourth, p3);
    }

    private static Vector3f copyFinite(Vector3fc value, String name) {
        Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must contain finite values");
        }
        return new Vector3f(value);
    }
}
