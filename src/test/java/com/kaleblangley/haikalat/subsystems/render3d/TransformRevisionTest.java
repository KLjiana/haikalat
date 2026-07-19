package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformRevisionTest {
    @Test
    void settersAdvanceRevisionButReadsAndMatrixBuildsDoNot() {
        Transform transform = Transform.identity();
        assertEquals(0L, transform.revision());

        transform.position(1.0f, 2.0f, 3.0f);
        transform.position(new Vector3f(4.0f, 5.0f, 6.0f));
        transform.rotationRadians(0.1f, 0.2f, 0.3f);
        transform.scale(2.0f);
        transform.scale(2.0f, 3.0f, 4.0f);
        assertEquals(5L, transform.revision());

        Vector3f position = transform.position();
        assertNotSame(position, transform.position());
        position.zero();
        transform.rotationRadians();
        transform.scale();
        transform.matrix();
        transform.matrix(new Matrix4f());

        assertEquals(5L, transform.revision());
        assertEquals(new Vector3f(4.0f, 5.0f, 6.0f), transform.position());
    }

    @Test
    void transformSourceTracksRevisionAndFixedSourceCopiesInput() {
        Transform transform = Transform.at(1.0f, 2.0f, 3.0f);
        RevisionedModelSource.TransformSource source =
                new RevisionedModelSource.TransformSource(transform);
        assertEquals(transform.revision(), source.revision());
        transform.scale(3.0f);
        assertEquals(transform.revision(), source.revision());

        Matrix4f supplied = new Matrix4f().translation(7.0f, 8.0f, 9.0f);
        RevisionedModelSource.FixedSource fixed =
                new RevisionedModelSource.FixedSource(supplied);
        supplied.translation(100.0f, 100.0f, 100.0f);
        Matrix4f actual = new Matrix4f();
        fixed.update(actual, 99);

        assertEquals(0L, fixed.revision());
        assertEquals(7.0f, actual.m30());
        assertEquals(8.0f, actual.m31());
        assertEquals(9.0f, actual.m32());
    }

    @Test
    void fixedSourceRejectsNonFiniteAndPerspectiveMatrices() {
        Matrix4f nonFinite = new Matrix4f();
        nonFinite.m00(Float.NaN);
        Matrix4f perspective = new Matrix4f();
        perspective.m03(0.25f);

        assertThrows(IllegalArgumentException.class,
                () -> new RevisionedModelSource.FixedSource(nonFinite));
        assertThrows(IllegalArgumentException.class,
                () -> new RevisionedModelSource.FixedSource(perspective));
    }

    @Test
    void arbitraryUpdaterIsNotAssumedToBeStatic() {
        SceneObject.ModelUpdater updater = (out, frame) -> out.translation(frame, 0.0f, 0.0f);
        assertTrue(!(updater instanceof RevisionedModelSource));
    }
}
