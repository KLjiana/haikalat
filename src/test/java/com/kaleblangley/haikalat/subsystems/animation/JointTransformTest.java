package com.kaleblangley.haikalat.subsystems.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JointTransformTest {
    @Test
    void constructorCopiesInputsAndNormalizesRotation() {
        Vector3f translation = new Vector3f(1.0f, 2.0f, 3.0f);
        Quaternionf rotation = new Quaternionf(0.0f, 0.0f, 2.0f, 0.0f);
        Vector3f scale = new Vector3f(2.0f, 3.0f, 4.0f);

        JointTransform transform = new JointTransform(translation, rotation, scale);
        translation.zero();
        rotation.identity();
        scale.set(1.0f);

        assertEquals(new Vector3f(1.0f, 2.0f, 3.0f), transform.translation());
        assertEquals(1.0f, transform.rotation().lengthSquared(), 1.0e-6f);
        assertEquals(new Vector3f(2.0f, 3.0f, 4.0f), transform.scale());
        assertNotSame(transform.translation(), transform.translation());
        assertEquals(1.0f, transform.matrix().m30(), 0.0f);
        assertEquals(2.0f, transform.matrix().m31(), 0.0f);
        assertEquals(3.0f, transform.matrix().m32(), 0.0f);
    }

    @Test
    void invalidComponentsFailAtTheValueBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new JointTransform(
                new Vector3f(Float.NaN, 0.0f, 0.0f), new Quaternionf(), new Vector3f(1.0f)));
        assertThrows(IllegalArgumentException.class, () -> new JointTransform(
                new Vector3f(), new Quaternionf(0.0f, 0.0f, 0.0f, 0.0f), new Vector3f(1.0f)));
        assertThrows(IllegalArgumentException.class, () -> new JointTransform(
                new Vector3f(), new Quaternionf(), new Vector3f(Float.POSITIVE_INFINITY)));
    }
}
