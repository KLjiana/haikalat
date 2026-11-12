package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent double-precision reference for the DDGI probe weighting, temporal
 * blend and update budget.
 *
 * <p>Deliberately does not call any production helper except reading the
 * immutable {@link DdgiSettings} values. The formulas are written again from the
 * documented contract (see {@code ddgi-common.glsl} and
 * {@code docs/planning/v0.28.1-s5-ddgi.md}), so a production/shader divergence
 * is caught by comparing against this implementation rather than by reusing
 * the implementation under test.</p>
 */
final class DdgiProbeReference {
    private DdgiProbeReference() {
    }

    /** Recorded probe-depth distance along the receiver direction; +inf = no hit. */
    @FunctionalInterface
    interface ProbeDepth {
        double recordedDistance(int probe, double x, double y, double z);
    }

    /** No recorded surface: visibility is always 1. */
    static final ProbeDepth NO_OCCLUDER = (probe, x, y, z) -> Double.POSITIVE_INFINITY;

    /** One corner of the trilinear probe neighbourhood, raw (un-normalized). */
    record ProbeWeight(int probe, double weight) {
    }

    /** World-space probe centre for a linear probe index. */
    static double[] probePosition(DdgiSettings settings, int index) {
        int probesX = settings.probesX();
        int slice = probesX * settings.probesY();
        int z = index / slice;
        int remainder = index - z * slice;
        int y = remainder / probesX;
        int x = remainder - y * probesX;
        return new double[]{
                settings.gridOriginX() + (x + 0.5) * settings.spacingMetres(),
                settings.gridOriginY() + (y + 0.5) * settings.spacingMetres(),
                settings.gridOriginZ() + (z + 0.5) * settings.spacingMetres()};
    }

    /** Border fade in {@code [0, 1]}; zero outside the inclusive grid bounds. */
    static double borderWeight(DdgiSettings settings, double x, double y, double z) {
        double maximumX = settings.gridMinimumX() + settings.gridWidth();
        double maximumY = settings.gridMinimumY() + settings.gridHeight();
        double maximumZ = settings.gridMinimumZ() + settings.gridDepth();
        if (x < settings.gridMinimumX() || x > maximumX
                || y < settings.gridMinimumY() || y > maximumY
                || z < settings.gridMinimumZ() || z > maximumZ) {
            return 0.0;
        }
        if (settings.borderFadeMetres() <= 0.0f) {
            return 1.0;
        }
        double distance = Math.min(
                Math.min(x - settings.gridMinimumX(), maximumX - x),
                Math.min(Math.min(y - settings.gridMinimumY(), maximumY - y),
                        Math.min(z - settings.gridMinimumZ(), maximumZ - z)));
        return Math.max(0.0, Math.min(1.0, distance / settings.borderFadeMetres()));
    }

    /** Hard bounded probe visibility gate; mirrors the shared shader formula. */
    static double visibility(DdgiSettings settings, int probe, double x, double y,
                             double z, ProbeDepth depth) {
        double[] position = probePosition(settings, probe);
        double dx = x - position[0];
        double dy = y - position[1];
        double dz = z - position[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 1.0e-4) {
            return 1.0;
        }
        double recorded = depth.recordedDistance(probe, x, y, z);
        double difference = distance - recorded;
        double tolerance = Math.max(settings.spacingMetres() * 0.05, distance * 0.02);
        return difference <= tolerance ? 1.0 : 0.0;
    }

    /**
     * Raw trilinear probe weights for one receiver position and unit normal.
     * Corner indices are clamped into the grid, so duplicate probes appear with
     * their summed weights; the weight of each corner is
     * {@code trilinear * max(dot(normalize(toProbe), normal), 0)}. Occlusion is
     * applied separately by {@link #visibility} so the normalizer does not
     * normalize a hidden receiver back to full brightness.
     */
    static List<ProbeWeight> weights(DdgiSettings settings, double x, double y, double z,
                                     double normalX, double normalY, double normalZ) {
        double normalLength = Math.sqrt(normalX * normalX + normalY * normalY
                + normalZ * normalZ);
        if (!(normalLength > 1.0e-9)) {
            throw new IllegalArgumentException("normal must be non-zero");
        }
        double nx = normalX / normalLength;
        double ny = normalY / normalLength;
        double nz = normalZ / normalLength;
        double gx = (x - settings.gridMinimumX()) / settings.spacingMetres() - 0.5;
        double gy = (y - settings.gridMinimumY()) / settings.spacingMetres() - 0.5;
        double gz = (z - settings.gridMinimumZ()) / settings.spacingMetres() - 0.5;
        double baseX = Math.floor(gx);
        double baseY = Math.floor(gy);
        double baseZ = Math.floor(gz);
        double fractionX = gx - baseX;
        double fractionY = gy - baseY;
        double fractionZ = gz - baseZ;
        List<ProbeWeight> result = new ArrayList<>(8);
        for (int corner = 0; corner < 8; corner++) {
            int dx = corner & 1;
            int dy = (corner >> 1) & 1;
            int dz = (corner >> 2) & 1;
            int clampedX = clamp((int) baseX + dx, 0, settings.probesX() - 1);
            int clampedY = clamp((int) baseY + dy, 0, settings.probesY() - 1);
            int clampedZ = clamp((int) baseZ + dz, 0, settings.probesZ() - 1);
            int probe = clampedX + clampedY * settings.probesX()
                    + clampedZ * settings.probesX() * settings.probesY();
            double[] position = probePosition(settings, probe);
            double toX = position[0] - x;
            double toY = position[1] - y;
            double toZ = position[2] - z;
            double toLength = Math.sqrt(toX * toX + toY * toY + toZ * toZ);
            double normalWeight = toLength <= 1.0e-9 ? 0.0
                    : Math.max(0.0, (toX * nx + toY * ny + toZ * nz) / toLength);
            double trilinear = (dx == 1 ? fractionX : 1.0 - fractionX)
                    * (dy == 1 ? fractionY : 1.0 - fractionY)
                    * (dz == 1 ? fractionZ : 1.0 - fractionZ);
            result.add(new ProbeWeight(probe, trilinear * normalWeight));
        }
        return result;
    }

    /**
     * Reference indirect query: normalized probe weighting times the per-probe
     * radiance, faded by the grid border, with the bounded hard visibility gate.
     * Returns exactly zero outside the grid, when the normal-side weights all
     * vanish, or when every contributing probe is occluded.
     */
    static double sample(DdgiSettings settings, double x, double y, double z,
                         double normalX, double normalY, double normalZ,
                         double[] probeRadiance, ProbeDepth depth) {
        double border = borderWeight(settings, x, y, z);
        if (!(border > 0.0)) {
            return 0.0;
        }
        double sum = 0.0;
        double weightSum = 0.0;
        for (ProbeWeight entry : weights(settings, x, y, z, normalX, normalY, normalZ)) {
            double visibility = visibility(settings, entry.probe(), x, y, z, depth);
            sum += probeRadiance[entry.probe()] * entry.weight() * visibility;
            weightSum += entry.weight();
        }
        if (weightSum <= 1.0e-6) {
            return 0.0;
        }
        return border * sum / weightSum;
    }

    /** Surface normal lookup for the single-ray update reference. */
    @FunctionalInterface
    interface SurfaceNormal {
        /** @return a non-zero world-space normal at the supplied hit position */
        double[] normalAt(double x, double y, double z);
    }

    /**
     * Single-ray update reference: marches one ray from a probe through the
     * supplied recorded-distance function, evaluates the bounded analytic direct
     * light at the nearest hit and returns the sky term on a miss. Mirrors the
     * relight loop, including the first-hit early exit and the direct radiance
     * formula {@code albedo / PI * max(dot(n, -L), 0) * lightRadiance}.
     */
    static double updateRay(DdgiSettings settings, int probe,
                            double directionX, double directionY, double directionZ,
                            ProbeDepth depth, SurfaceNormal surfaceNormal,
                            double lightX, double lightY, double lightZ,
                            double lightRadiance, double albedo, double skyRadiance) {
        double[] position = probePosition(settings, probe);
        double length = Math.sqrt(directionX * directionX + directionY * directionY
                + directionZ * directionZ);
        if (!(length > 1.0e-9)) {
            throw new IllegalArgumentException("direction must be non-zero");
        }
        double dx = directionX / length;
        double dy = directionY / length;
        double dz = directionZ / length;
        double lightLength = Math.sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ);
        if (!(lightLength > 1.0e-9)) {
            throw new IllegalArgumentException("light direction must be non-zero");
        }
        double lx = lightX / lightLength;
        double ly = lightY / lightLength;
        double lz = lightZ / lightLength;
        double near = Math.min(1.0, Math.max(0.02, settings.spacingMetres() * 0.01));
        double march = Math.max(settings.maximumRayDistance(), near + 1.0e-3);
        int steps = Math.max(settings.raySteps(), 1);
        for (int step = 1; step <= steps; step++) {
            double t = near + (march - near) * (step / (double) steps);
            double x = position[0] + dx * t;
            double y = position[1] + dy * t;
            double z = position[2] + dz * t;
            double recorded = depth.recordedDistance(probe, x, y, z);
            if (recorded < t) {
                double hit = Math.max(near, Math.min(march, recorded));
                double[] normal = surfaceNormal.normalAt(position[0] + dx * hit,
                        position[1] + dy * hit, position[2] + dz * hit);
                double normalLength = Math.sqrt(normal[0] * normal[0]
                        + normal[1] * normal[1] + normal[2] * normal[2]);
                if (!(normalLength > 1.0e-9)) {
                    throw new IllegalArgumentException("surface normal must be non-zero");
                }
                double incidence = Math.max(-(normal[0] * lx + normal[1] * ly
                        + normal[2] * lz) / normalLength, 0.0);
                return albedo * incidence / Math.PI * lightRadiance;
            }
        }
        return skyRadiance;
    }

    /** Temporal blend used by the relight stage, with the configured clamp. */
    static double hysteresis(double previous, double raw, double factor, double maximum) {
        double blended = raw * (1.0 - factor) + previous * factor;
        return Math.max(0.0, Math.min(maximum, blended));
    }

    /** Round-robin budget schedule; mirrors {@link DdgiProbeBudget}. */
    static int[] schedule(int probeCount, int budget, int cursor) {
        int count = Math.min(budget, probeCount);
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = (cursor + 1 + index) % probeCount;
        }
        return result;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
