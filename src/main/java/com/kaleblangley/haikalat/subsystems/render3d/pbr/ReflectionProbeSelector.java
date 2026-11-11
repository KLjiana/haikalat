package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * GL-free local reflection-probe selection.
 *
 * <p>For one world point the selector evaluates every active probe's bounded
 * influence weight, keeps up to {@link #MAX_SELECTED_PROBES} probes and
 * normalises the result. The returned {@link Selection} carries the selected
 * probes (original settings-list index + weight) and the remaining global
 * environment weight. A point outside every probe returns
 * {@link Selection#globalOnly()}, which makes the PBR shader keep its original
 * global IBL path.</p>
 *
 * <p>The weight field is continuous by construction. Inside the influence
 * volume the weight rises from {@code 0.5} at the boundary to {@code 1} at the
 * centre; in the blend shell it falls from {@code 0.5} to {@code 0}. Both
 * limits agree at the boundary, so moving a receiver across it or through an
 * overlap of two probes cannot produce a weight jump. A zero
 * {@code blendDistance} deliberately disables the outside ramp and leaves a
 * documented hard edge at the influence boundary.</p>
 *
 * <p>{@link #localCorrectionDirection} implements the same box/sphere
 * projection used by the PBR fragment shader: the reflected ray is intersected
 * with the probe's influence volume and the cubemap is sampled from the probe
 * centre toward that hit. This is a local approximation, not a captured scene
 * depth cube; it removes the worst "environment at infinity" parallax error
 * inside one volume but has no occlusion or parallax information beyond the
 * projection box.</p>
 */
public final class ReflectionProbeSelector {
    /** Highest number of probes one receiver can blend. */
    public static final int MAX_SELECTED_PROBES = ReflectionProbeSettings.MAX_PROBE_COUNT;
    private static final float EPSILON = 1.0e-6f;

    private ReflectionProbeSelector() {
    }

    /**
     * Selects the probes influencing {@code worldPoint}.
     *
     * @throws IllegalArgumentException when the point is not finite
     */
    public static Selection select(List<ReflectionProbeSettings> probes,
                                   Vector3fc worldPoint) {
        Objects.requireNonNull(probes, "probes");
        if (probes.size() > MAX_SELECTED_PROBES) {
            throw new IllegalArgumentException("at most " + MAX_SELECTED_PROBES
                    + " probes can be selected, got " + probes.size());
        }
        requirePoint(worldPoint);
        List<Candidate> candidates = new ArrayList<>(probes.size());
        for (int index = 0; index < probes.size(); index++) {
            ReflectionProbeSettings probe = Objects.requireNonNull(probes.get(index),
                    "probes[" + index + "]");
            if (!probe.active()) continue;
            float weight = influenceWeight(probe, worldPoint);
            if (weight > 0.0f) {
                candidates.add(new Candidate(index, weight,
                        distanceSquared(probe.position(), worldPoint)));
            }
        }
        if (candidates.isEmpty()) return Selection.globalOnly();
        candidates.sort(Comparator.comparingDouble(Candidate::weight).reversed()
                .thenComparingDouble(Candidate::distanceSquared)
                .thenComparingInt(Candidate::probeIndex));
        int selected = Math.min(candidates.size(), MAX_SELECTED_PROBES);
        float total = 0.0f;
        for (int index = 0; index < selected; index++) {
            total += candidates.get(index).weight();
        }
        if (!(total > 0.0f)) return Selection.globalOnly();
        float scale = total > 1.0f ? 1.0f / total : 1.0f;
        float globalWeight = total >= 1.0f ? 0.0f : 1.0f - total;
        List<SelectedProbe> result = new ArrayList<>(selected);
        for (int index = 0; index < selected; index++) {
            Candidate candidate = candidates.get(index);
            result.add(new SelectedProbe(candidate.probeIndex(), candidate.weight() * scale,
                    candidate.distanceSquared()));
        }
        return new Selection(result, globalWeight);
    }

    /**
     * Probe influence weight at one world point.
     *
     * <p>Inside the influence volume: {@code 0.5 + 0.5 * smoothstep(inset)}
     * where {@code inset} is the fraction from the volume boundary to the
     * centre along the nearest-face distance. In the blend shell:
     * {@code 0.5 * (1 - smoothstep(outside / blendDistance))}. Both sides meet
     * at {@code 0.5} on the boundary. Outside the blend shell the weight is
     * zero.</p>
     */
    static float influenceWeight(ReflectionProbeSettings probe, Vector3fc point) {
        float outside = outsideDistance(probe, point);
        if (outside <= 0.0f) {
            float coreDepth = probe.shape() == ReflectionProbeSettings.Shape.SPHERE
                    ? probe.radius() : minimumHalfExtent(probe);
            if (!(coreDepth > EPSILON)) return 1.0f;
            float inset = clamp01(-outside / coreDepth);
            return 0.5f + 0.5f * smoothstep(inset);
        }
        float blend = probe.blendDistance();
        if (!(blend > EPSILON)) return 0.0f;
        return 0.5f * (1.0f - smoothstep(clamp01(outside / blend)));
    }

    /**
     * Signed distance from {@code point} to the probe's influence volume
     * boundary: negative inside, zero on the boundary and positive outside.
     */
    private static float outsideDistance(ReflectionProbeSettings probe, Vector3fc point) {
        Vector3f local = new Vector3f(point).sub(probe.position());
        if (probe.shape() == ReflectionProbeSettings.Shape.SPHERE) {
            return local.length() - probe.radius();
        }
        Vector3f half = probe.halfExtents();
        float dx = Math.abs(local.x) - half.x;
        float dy = Math.abs(local.y) - half.y;
        float dz = Math.abs(local.z) - half.z;
        float outside = (float) Math.sqrt(
                Math.max(dx, 0.0f) * Math.max(dx, 0.0f)
                        + Math.max(dy, 0.0f) * Math.max(dy, 0.0f)
                        + Math.max(dz, 0.0f) * Math.max(dz, 0.0f));
        return outside + Math.min(Math.max(dx, Math.max(dy, dz)), 0.0f);
    }

    /**
     * Local-corrected cubemap sample direction for one probe.
     *
     * <p>The world-space reflection ray is intersected with the probe's
     * box/sphere influence volume, and the returned direction points from the
     * probe centre toward that intersection. If the ray misses the volume the
     * unmodified normalised reflection direction is returned. The caller must
     * still apply the probe's own rotation to the result and combine probes by
     * {@link #select}.</p>
     *
     * @param destination receives the normalised direction
     * @return {@code destination}
     */
    public static Vector3f localCorrectionDirection(ReflectionProbeSettings probe,
                                                     Vector3fc worldPoint,
                                                     Vector3fc reflectionDirection,
                                                     Vector3f destination) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(destination, "destination");
        requirePoint(worldPoint);
        Objects.requireNonNull(reflectionDirection, "reflectionDirection");
        if (!Float.isFinite(reflectionDirection.x()) || !Float.isFinite(reflectionDirection.y())
                || !Float.isFinite(reflectionDirection.z())) {
            throw new IllegalArgumentException("reflectionDirection must be finite");
        }
        Vector3f direction = new Vector3f(reflectionDirection);
        if (direction.lengthSquared() <= EPSILON) {
            throw new IllegalArgumentException("reflectionDirection must be non-zero");
        }
        direction.normalize();
        Vector3f origin = new Vector3f(worldPoint);
        boolean hit = probe.shape() == ReflectionProbeSettings.Shape.SPHERE
                ? intersectSphere(probe, origin, direction, destination)
                : intersectBox(probe, origin, direction, destination);
        if (!hit) {
            return destination.set(direction);
        }
        Vector3f fromCentre = destination.sub(probe.position());
        float lengthSquared = fromCentre.lengthSquared();
        if (!(lengthSquared > EPSILON)) return destination.set(direction);
        return fromCentre.mul(1.0f / (float) Math.sqrt(lengthSquared));
    }

    private static boolean intersectBox(ReflectionProbeSettings probe, Vector3f origin,
                                        Vector3f direction, Vector3f destination) {
        Vector3f half = probe.halfExtents();
        Vector3f centre = probe.position();
        Vector3f minimum = new Vector3f(centre).sub(half);
        Vector3f maximum = new Vector3f(centre).add(half);
        float tNear = Float.NEGATIVE_INFINITY;
        float tFar = Float.POSITIVE_INFINITY;
        float[] o = {origin.x, origin.y, origin.z};
        float[] d = {direction.x, direction.y, direction.z};
        float[] lo = {minimum.x, minimum.y, minimum.z};
        float[] hi = {maximum.x, maximum.y, maximum.z};
        for (int axis = 0; axis < 3; axis++) {
            float component = Math.abs(d[axis]) < EPSILON
                    ? (d[axis] < 0.0f ? -EPSILON : EPSILON) : d[axis];
            float first = (lo[axis] - o[axis]) / component;
            float second = (hi[axis] - o[axis]) / component;
            float axisNear = Math.min(first, second);
            float axisFar = Math.max(first, second);
            tNear = Math.max(tNear, axisNear);
            tFar = Math.min(tFar, axisFar);
            if (tNear > tFar) return false;
        }
        if (tFar < 0.0f) return false;
        float distance = tNear > EPSILON ? tNear : tFar;
        if (!(distance > 0.0f)) return false;
        destination.set(origin).fma(distance, direction);
        return true;
    }

    private static boolean intersectSphere(ReflectionProbeSettings probe, Vector3f origin,
                                           Vector3f direction, Vector3f destination) {
        Vector3f offset = new Vector3f(origin).sub(probe.position());
        float b = offset.dot(direction);
        float c = offset.lengthSquared() - probe.radius() * probe.radius();
        float discriminant = b * b - c;
        if (discriminant < 0.0f) return false;
        float root = (float) Math.sqrt(discriminant);
        float near = -b - root;
        float far = -b + root;
        float distance = near > EPSILON ? near : far;
        if (!(distance > 0.0f)) return false;
        destination.set(origin).fma(distance, direction);
        return true;
    }

    private static float minimumHalfExtent(ReflectionProbeSettings probe) {
        Vector3f half = probe.halfExtents();
        return Math.min(half.x, Math.min(half.y, half.z));
    }

    private static float distanceSquared(Vector3f first, Vector3fc second) {
        float dx = first.x - second.x();
        float dy = first.y - second.y();
        float dz = first.z - second.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void requirePoint(Vector3fc point) {
        Objects.requireNonNull(point, "worldPoint");
        if (!Float.isFinite(point.x()) || !Float.isFinite(point.y())
                || !Float.isFinite(point.z())) {
            throw new IllegalArgumentException("worldPoint must be finite");
        }
    }

    private static float smoothstep(float value) {
        float t = clamp01(value);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private record Candidate(int probeIndex, float weight, float distanceSquared) {
    }

    /** One probe selected for a receiver. */
    public record SelectedProbe(int probeIndex, float weight, float distanceSquared) {
        public SelectedProbe {
            if (probeIndex < 0) {
                throw new IllegalArgumentException("probeIndex must be non-negative");
            }
            if (!Float.isFinite(weight) || weight < 0.0f || weight > 1.0f) {
                throw new IllegalArgumentException("weight must be finite and in [0, 1]");
            }
            if (!Float.isFinite(distanceSquared) || distanceSquared < 0.0f) {
                throw new IllegalArgumentException("distanceSquared must be finite and non-negative");
            }
        }
    }

    /**
     * Immutable selection result. {@code globalWeight + sum(probe weights)} is
     * one within float rounding; the PBR shader normalises defensively.
     */
    public record Selection(List<SelectedProbe> probes, float globalWeight) {
        public Selection {
            probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
            if (probes.size() > MAX_SELECTED_PROBES) {
                throw new IllegalArgumentException("a receiver supports at most "
                        + MAX_SELECTED_PROBES + " local probes");
            }
            if (!Float.isFinite(globalWeight) || globalWeight < 0.0f || globalWeight > 1.0f) {
                throw new IllegalArgumentException("globalWeight must be finite and in [0, 1]");
            }
        }

        public static Selection globalOnly() {
            return new Selection(List.of(), 1.0f);
        }

        public boolean isEmpty() {
            return probes.isEmpty();
        }

        public int count() {
            return probes.size();
        }

        public float totalProbeWeight() {
            float total = 0.0f;
            for (SelectedProbe probe : probes) total += probe.weight();
            return total;
        }
    }
}
