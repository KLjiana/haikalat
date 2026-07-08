package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

public final class Transform {
    private final Vector3f position = new Vector3f();
    private final Vector3f rotationRadians = new Vector3f();
    private final Vector3f scale = new Vector3f(1.0f, 1.0f, 1.0f);

    public static Transform identity() {
        return new Transform();
    }

    public static Transform at(float x, float y, float z) {
        return new Transform().position(x, y, z);
    }

    public Transform position(float x, float y, float z) {
        position.set(x, y, z);
        return this;
    }

    public Transform position(Vector3f value) {
        position.set(Objects.requireNonNull(value, "value"));
        return this;
    }

    public Transform rotationRadians(float x, float y, float z) {
        rotationRadians.set(x, y, z);
        return this;
    }

    public Transform scale(float value) {
        scale.set(value, value, value);
        return this;
    }

    public Transform scale(float x, float y, float z) {
        scale.set(x, y, z);
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
}
