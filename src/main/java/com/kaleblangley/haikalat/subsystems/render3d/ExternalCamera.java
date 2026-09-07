package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Immutable per-frame camera snapshot supplied by an embedding host.
 *
 * <p>All inputs are copied so command recording cannot observe later host
 * mutations.</p>
 */
public final class ExternalCamera extends Camera {
    private final Matrix4f view;
    private final Matrix4f projection;
    private final Matrix4f viewProjection;
    private final Matrix4f inverseView;
    private final Matrix4f inverseProjection;
    private final Matrix4f inverseViewProjection;
    private final Vector3f position;
    private final float partialTick;
    private final float nearPlane;
    private final float farPlane;
    private final long revision;

    public ExternalCamera(Matrix4f view, Matrix4f projection, Matrix4f viewProjection,
                          Vector3f position, float partialTick) {
        this(view, projection, viewProjection, position, partialTick,
                CameraProjection.NEAR_PLANE, CameraProjection.FAR_PLANE, 0L);
    }

    public ExternalCamera(Matrix4f view, Matrix4f projection, Matrix4f viewProjection,
                          Vector3f position, float partialTick,
                          float nearPlane, float farPlane, long revision) {
        this.view = copyFinite(view, "view");
        this.projection = copyFinite(projection, "projection");
        this.viewProjection = copyFinite(viewProjection, "viewProjection");
        this.position = new Vector3f(Objects.requireNonNull(position, "position"));
        if (!this.position.isFinite()) {
            throw new IllegalArgumentException("external camera position must be finite");
        }
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("partialTick must be finite");
        }
        if (!Float.isFinite(nearPlane) || !Float.isFinite(farPlane)
                || nearPlane <= 0.0f || farPlane <= nearPlane) {
            throw new IllegalArgumentException(
                    "external camera planes must satisfy 0 < near < far");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("external camera revision must be non-negative");
        }
        this.partialTick = partialTick;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.revision = revision;
        inverseView = invert(this.view, "view");
        inverseProjection = invert(this.projection, "projection");
        inverseViewProjection = invert(this.viewProjection, "viewProjection");
    }

    /** Creates a snapshot and computes {@code projection * view}. */
    public static ExternalCamera of(Matrix4f view, Matrix4f projection,
                                    Vector3f position, float partialTick) {
        Matrix4f requiredView = Objects.requireNonNull(view, "view");
        Matrix4f requiredProjection = Objects.requireNonNull(projection, "projection");
        return new ExternalCamera(requiredView, requiredProjection,
                new Matrix4f(requiredProjection).mul(requiredView),
                position, partialTick);
    }

    public Matrix4f view() {
        return new Matrix4f(view);
    }

    public Matrix4f projection() {
        return new Matrix4f(projection);
    }

    public Matrix4f viewProjection() {
        return new Matrix4f(viewProjection);
    }

    public Matrix4f inverseView() {
        return new Matrix4f(inverseView);
    }

    public Matrix4f inverseProjection() {
        return new Matrix4f(inverseProjection);
    }

    public Matrix4f inverseViewProjection() {
        return new Matrix4f(inverseViewProjection);
    }

    @Override
    public Vector3f position() {
        return new Vector3f(position);
    }

    @Override
    Vector3f positionInternal() {
        return position;
    }

    public float partialTick() {
        return partialTick;
    }

    public float nearPlane() {
        return nearPlane;
    }

    public float farPlane() {
        return farPlane;
    }

    public long revision() {
        return revision;
    }

    @Override
    public Matrix4f getViewMatrix(Matrix4f destination) {
        return Objects.requireNonNull(destination, "destination").set(view);
    }

    @Override
    public Matrix4f getViewMatrix() {
        return new Matrix4f(view);
    }

    @Override
    long visibilityRevision() {
        return revision;
    }

    Matrix4f projection(Matrix4f destination) {
        return Objects.requireNonNull(destination, "destination").set(projection);
    }

    private static Matrix4f copyFinite(Matrix4f matrix, String name) {
        Matrix4f copy = new Matrix4f(Objects.requireNonNull(matrix, name));
        if (!copy.isFinite()) {
            throw new IllegalArgumentException("external camera " + name + " must be finite");
        }
        return copy;
    }

    private static Matrix4f invert(Matrix4f matrix, String name) {
        Matrix4f inverse = new Matrix4f(matrix);
        if (Math.abs(inverse.determinant()) <= 1.0e-8f) {
            throw new IllegalArgumentException("external camera " + name + " must be invertible");
        }
        return inverse.invert();
    }
}
