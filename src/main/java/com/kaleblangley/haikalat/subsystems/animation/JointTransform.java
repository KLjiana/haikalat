package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

/** 不可变的关节局部平移、旋转与缩放。 */
public final class JointTransform {
    private static final float MIN_QUATERNION_LENGTH_SQUARED = 1.0e-12f;
    private static final JointTransform IDENTITY = new JointTransform(
            new Vector3f(), new Quaternionf(), new Vector3f(1.0f));

    private final Vector3f translation;
    private final Quaternionf rotation;
    private final Vector3f scale;

    public JointTransform(Vector3fc translation, Quaternionfc rotation, Vector3fc scale) {
        this.translation = copyFinite(translation, "translation");
        this.rotation = copyNormalized(rotation, "rotation");
        this.scale = copyFinite(scale, "scale");
    }

    public static JointTransform identity() {
        return IDENTITY;
    }

    public Vector3fc translation() {
        return new Vector3f(translation);
    }

    public Quaternionfc rotation() {
        return new Quaternionf(rotation);
    }

    public Vector3fc scale() {
        return new Vector3f(scale);
    }

    public Matrix4f matrix() {
        return matrix(new Matrix4f());
    }

    public Matrix4f matrix(Matrix4f destination) {
        return Objects.requireNonNull(destination, "destination")
                .identity()
                .translate(translation)
                .rotate(rotation)
                .scale(scale);
    }

    void copyTranslation(Vector3f destination) {
        destination.set(translation);
    }

    void copyRotation(Quaternionf destination) {
        destination.set(rotation);
    }

    void copyScale(Vector3f destination) {
        destination.set(scale);
    }

    static void requireFinite(Vector3fc value, String name) {
        Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y()) || !Float.isFinite(value.z())) {
            throw new IllegalArgumentException(name + " must contain only finite components");
        }
    }

    static void setNormalized(Quaternionf destination, Quaternionfc value, String name) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(value, name);
        float x = value.x();
        float y = value.y();
        float z = value.z();
        float w = value.w();
        float lengthSquared = x * x + y * y + z * z + w * w;
        if (!Float.isFinite(lengthSquared) || lengthSquared < MIN_QUATERNION_LENGTH_SQUARED) {
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        }
        destination.set(x, y, z, w).normalize();
    }

    private static Vector3f copyFinite(Vector3fc value, String name) {
        requireFinite(value, name);
        return new Vector3f(value);
    }

    private static Quaternionf copyNormalized(Quaternionfc value, String name) {
        Quaternionf result = new Quaternionf();
        setNormalized(result, value, name);
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof JointTransform transform)) return false;
        return translation.equals(transform.translation)
                && rotation.equals(transform.rotation)
                && scale.equals(transform.scale);
    }

    @Override
    public int hashCode() {
        return Objects.hash(translation, rotation, scale);
    }

    @Override
    public String toString() {
        return "JointTransform[translation=" + translation + ", rotation=" + rotation
                + ", scale=" + scale + ']';
    }
}
