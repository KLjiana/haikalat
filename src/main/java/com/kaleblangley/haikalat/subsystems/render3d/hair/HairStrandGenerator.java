package com.kaleblangley.haikalat.subsystems.render3d.hair;

import org.joml.Vector3f;

import java.util.Objects;

/**
 * Deterministic procedural strand control-point generator.
 *
 * <p>The generator is GL-free and described entirely by closed-form equations:
 * roots lie on a disc perpendicular to the configured flow direction, every
 * control point advances along that direction and receives a bounded lateral
 * curl plus a SplitMix64-derived jitter. The same seed always produces the same
 * points, so the JVM reference can re-derive every coordinate without a GPU or
 * an external asset.</p>
 *
 * <p>All trigonometry and hashing is evaluated in {@code double}; the returned
 * points are the single-precision production values. The JVM reference test
 * implements the same equations independently in double precision and compares
 * the published points.</p>
 */
final class HairStrandGenerator {
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long STRAND_SALT = 0xD1B54A32D192ED03L;
    private static final long ROOT_POINT = 0x101L;
    private static final long RANDOM_POINT = 0x51A7L;
    private static final long JITTER_BASE = 0x1000L;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double CURVE_SWIRL = 1.7;
    private static final double POINT_JITTER_SCALE = 0.04;
    private static final double LATERAL_SCALE = 0.25;

    private HairStrandGenerator() {
    }

    /**
     * Generates the control-point polyline of one strand.
     *
     * @param settings    validated strand settings
     * @param strandIndex zero-based index inside the level's strand set
     * @param strandCount number of strands in the level (root distribution)
     * @param segments    number of segments; the returned array has
     *                    {@code (segments + 1) * 3} floats
     * @return xyz control points in local space
     */
    static float[] controlPoints(HairStrandSettings settings, int strandIndex, int strandCount,
                                 int segments) {
        Objects.requireNonNull(settings, "settings");
        if (strandIndex < 0 || strandIndex >= strandCount) {
            throw new IllegalArgumentException("strandIndex must be in [0, strandCount)");
        }
        if (strandCount < 1) {
            throw new IllegalArgumentException("strandCount must be positive");
        }
        if (segments < 1) {
            throw new IllegalArgumentException("segments must be positive");
        }
        double[] basis = orthonormalBasis(settings.flowDirectionInternal());
        double rightX = basis[0];
        double rightY = basis[1];
        double rightZ = basis[2];
        double upX = basis[3];
        double upY = basis[4];
        double upZ = basis[5];
        double flowX = settings.flowDirectionInternal().x;
        double flowY = settings.flowDirectionInternal().y;
        double flowZ = settings.flowDirectionInternal().z;
        double phase = TWO_PI * unit(hash(settings.seed(), strandIndex, (int) ROOT_POINT));
        double rootRadius = settings.rootSpreadMetres()
                * Math.sqrt((strandIndex + 0.5) / (double) strandCount);
        double rootX = rightX * Math.cos(phase) * rootRadius + upX * Math.sin(phase) * rootRadius;
        double rootY = rightY * Math.cos(phase) * rootRadius + upY * Math.sin(phase) * rootRadius;
        double rootZ = rightZ * Math.cos(phase) * rootRadius + upZ * Math.sin(phase) * rootRadius;
        double curveAngle = phase * CURVE_SWIRL;
        double curveX = rightX * Math.cos(curveAngle) + upX * Math.sin(curveAngle);
        double curveY = rightY * Math.cos(curveAngle) + upY * Math.sin(curveAngle);
        double curveZ = rightZ * Math.cos(curveAngle) + upZ * Math.sin(curveAngle);
        double curl = settings.curlMetres();
        double length = settings.strandLengthMetres();

        float[] points = new float[(segments + 1) * 3];
        for (int point = 0; point <= segments; point++) {
            double t = point / (double) segments;
            double arch = Math.sin(Math.PI * t);
            double lateral = curl * length * LATERAL_SCALE * arch;
            double jitterScale = curl * length * POINT_JITTER_SCALE * arch;
            long pointSeed = JITTER_BASE + point * 3L;
            double jitterX = symmetric(hash(settings.seed(), strandIndex, (int) pointSeed))
                    * jitterScale;
            double jitterY = symmetric(hash(settings.seed(), strandIndex, (int) pointSeed + 1))
                    * jitterScale;
            double jitterZ = symmetric(hash(settings.seed(), strandIndex, (int) pointSeed + 2))
                    * jitterScale;
            int offset = point * 3;
            points[offset] = (float) (rootX + flowX * length * t + curveX * lateral + jitterX);
            points[offset + 1] = (float) (rootY + flowY * length * t + curveY * lateral + jitterY);
            points[offset + 2] = (float) (rootZ + flowZ * length * t + curveZ * lateral + jitterZ);
        }
        return points;
    }

    /** Per-strand deterministic random value in {@code [0, 1)}. */
    static float randomValue(HairStrandSettings settings, int strandIndex) {
        return (float) unit(hash(settings.seed(), strandIndex, (int) RANDOM_POINT));
    }

    /** Builds an orthonormal {@code (right, up)} basis around the normalized flow. */
    static double[] orthonormalBasis(Vector3f flow) {
        Objects.requireNonNull(flow, "flow");
        double fx = flow.x;
        double fy = flow.y;
        double fz = flow.z;
        double length = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(length > 1.0e-12)) {
            throw new IllegalArgumentException("flow must be finite and non-zero");
        }
        fx /= length;
        fy /= length;
        fz /= length;
        double hintX;
        double hintY;
        double hintZ;
        if (Math.abs(fy) < 0.9) {
            hintX = 0.0;
            hintY = 1.0;
            hintZ = 0.0;
        } else {
            hintX = 1.0;
            hintY = 0.0;
            hintZ = 0.0;
        }
        double rightX = fy * hintZ - fz * hintY;
        double rightY = fz * hintX - fx * hintZ;
        double rightZ = fx * hintY - fy * hintX;
        double rightLength = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
        rightX /= rightLength;
        rightY /= rightLength;
        rightZ /= rightLength;
        double upX = rightY * fz - rightZ * fy;
        double upY = rightZ * fx - rightX * fz;
        double upZ = rightX * fy - rightY * fx;
        return new double[]{rightX, rightY, rightZ, upX, upY, upZ};
    }

    /** SplitMix64 finalizer over a seed and two stable indices. */
    static long hash(long seed, int strandIndex, int pointIndex) {
        long value = seed + GOLDEN_GAMMA * (strandIndex + 1L)
                + STRAND_SALT * (pointIndex + 1L);
        return mix(value);
    }

    static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** Maps the top 53 hash bits to {@code [0, 1)} without a floating bias. */
    static double unit(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }

    private static double symmetric(long bits) {
        return unit(bits) * 2.0 - 1.0;
    }
}
