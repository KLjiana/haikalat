package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class Transform {
    private static final AtomicLong MUTATION_EPOCH = new AtomicLong();

    private final Vector3f position = new Vector3f();
    private final Vector3f rotationRadians = new Vector3f();
    private final Vector3f scale = new Vector3f(1.0f, 1.0f, 1.0f);
    private long revision;

    public static Transform identity() {
        return new Transform();
    }

    public static Transform at(float x, float y, float z) {
        return new Transform().position(x, y, z);
    }

    public Transform position(float x, float y, float z) {
        long nextRevision = Math.incrementExact(revision);
        position.set(x, y, z);
        commitRevision(nextRevision);
        return this;
    }

    public Transform position(Vector3f value) {
        long nextRevision = Math.incrementExact(revision);
        position.set(Objects.requireNonNull(value, "value"));
        commitRevision(nextRevision);
        return this;
    }

    public Transform rotationRadians(float x, float y, float z) {
        long nextRevision = Math.incrementExact(revision);
        rotationRadians.set(x, y, z);
        commitRevision(nextRevision);
        return this;
    }

    public Transform scale(float value) {
        long nextRevision = Math.incrementExact(revision);
        scale.set(value, value, value);
        commitRevision(nextRevision);
        return this;
    }

    public Transform scale(float x, float y, float z) {
        long nextRevision = Math.incrementExact(revision);
        scale.set(x, y, z);
        commitRevision(nextRevision);
        return this;
    }

    public Vector3f position() {
        return new Vector3f(position);
    }

    public Vector3f rotationRadians() {
        return new Vector3f(rotationRadians);
    }

    public Vector3f scale() {
        return new Vector3f(scale);
    }

    public Matrix4f matrix() {
        return matrix(new Matrix4f());
    }

    public Matrix4f matrix(Matrix4f out) {
        return out.identity()
                .translate(position)
                .rotateXYZ(rotationRadians.x, rotationRadians.y, rotationRadians.z)
                .scale(scale);
    }

    long revision() {
        return revision;
    }

    static long mutationEpoch() {
        return MUTATION_EPOCH.get();
    }

    private void commitRevision(long nextRevision) {
        revision = nextRevision;
        MUTATION_EPOCH.incrementAndGet();
    }
}
