package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Bounded, camera-ordered projected-decal draw list.
 *
 * <p>Builds one plan per frame from an author list.  The list keeps the
 * nearest {@code maximumDecals} decals and orders those far-to-near so
 * overlapping alpha coverage composites deterministically; equal distances
 * fall back to the ascending author {@code sequence}.  Anchors behind the
 * camera plane are dropped conservatively before ordering; off-screen volumes
 * are left to rasterization.  The bound and the ordering are pure CPU logic and
 * are shared by the renderer and its JVM contract test.</p>
 */
final class ProjectedDecalPlan {
    private static final ProjectedDecal[] NO_DECALS = new ProjectedDecal[0];

    private final ProjectedDecalSettings settings;
    private final ProjectedDecal[] decals;
    private final int submitted;

    private ProjectedDecalPlan(ProjectedDecalSettings settings,
                               ProjectedDecal[] decals, int submitted) {
        this.settings = settings;
        this.decals = decals;
        this.submitted = submitted;
    }

    static ProjectedDecalPlan build(ProjectedDecalSettings settings,
                                    List<ProjectedDecal> authorDecals,
                                    Matrix4fc view, Vector3fc cameraPosition) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(authorDecals, "authorDecals");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(cameraPosition, "cameraPosition");
        int submitted = authorDecals.size();
        if (!settings.active() || submitted == 0) {
            return new ProjectedDecalPlan(settings, NO_DECALS, submitted);
        }
        int capacity = settings.maximumDecals();
        PriorityQueue<ProjectedDecal> nearest = new PriorityQueue<>(capacity,
                farthestFirst(cameraPosition));
        for (ProjectedDecal decal : authorDecals) {
            Objects.requireNonNull(decal, "decal");
            if (decal.opacity() <= 0.0f) continue;
            // Conservative fallback: an anchor behind the camera plane may still
            // have a partially visible volume, but it cannot be a receiver of a
            // visible fragment, so dropping it avoids useless draws without
            // inventing coverage.  Off-screen volumes are left to rasterization.
            if (decal.viewSpaceZ(view) >= 0.0f) continue;
            if (nearest.size() < capacity) {
                nearest.add(decal);
            } else if (isNearer(decal, nearest.peek(), cameraPosition)) {
                nearest.poll();
                nearest.add(decal);
            }
        }
        if (nearest.isEmpty()) {
            return new ProjectedDecalPlan(settings, NO_DECALS, submitted);
        }
        List<ProjectedDecal> ordered = new ArrayList<>(nearest);
        ordered.sort(farToNear(cameraPosition));
        return new ProjectedDecalPlan(settings, ordered.toArray(NO_DECALS), submitted);
    }

    ProjectedDecalSettings settings() {
        return settings;
    }

    int size() {
        return decals.length;
    }

    boolean isEmpty() {
        return decals.length == 0;
    }

    int submitted() {
        return submitted;
    }

    int rejected() {
        return submitted - decals.length;
    }

    ProjectedDecal decal(int index) {
        if (index < 0 || index >= decals.length) {
            throw new IndexOutOfBoundsException("decal index out of range: " + index);
        }
        return decals[index];
    }

    private static Comparator<ProjectedDecal> farthestFirst(Vector3fc cameraPosition) {
        return (left, right) -> {
            int distance = Float.compare(right.distanceSquaredTo(cameraPosition),
                    left.distanceSquaredTo(cameraPosition));
            if (distance != 0) return distance;
            return Long.compare(right.sequence(), left.sequence());
        };
    }

    private static Comparator<ProjectedDecal> farToNear(Vector3fc cameraPosition) {
        return (left, right) -> {
            int distance = Float.compare(right.distanceSquaredTo(cameraPosition),
                    left.distanceSquaredTo(cameraPosition));
            if (distance != 0) return distance;
            return Long.compare(left.sequence(), right.sequence());
        };
    }

    private static boolean isNearer(ProjectedDecal candidate, ProjectedDecal current,
                                    Vector3fc cameraPosition) {
        float candidateDistance = candidate.distanceSquaredTo(cameraPosition);
        float currentDistance = current.distanceSquaredTo(cameraPosition);
        if (candidateDistance < currentDistance) return true;
        if (candidateDistance > currentDistance) return false;
        return candidate.sequence() < current.sequence();
    }
}
