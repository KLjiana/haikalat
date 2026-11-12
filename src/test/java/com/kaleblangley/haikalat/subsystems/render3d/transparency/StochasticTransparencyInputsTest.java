package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the borrowed per-frame inputs of the stochastic pass:
 * defensive copies, finite-matrix validation and the optional depth rule.
 */
class StochasticTransparencyInputsTest {

    @Test
    void matricesAreCopiedDefensively() {
        Matrix4f view = new Matrix4f().translate(1.0f, 2.0f, 3.0f);
        Matrix4f projection = new Matrix4f().perspective(1.0f, 1.0f, 0.1f, 100.0f);
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Matrix4f inverseView = new Matrix4f(view).invert();
        StochasticTransparencyInputs inputs = new StochasticTransparencyInputs(
                view, projection, inverseProjection, inverseView, 42);
        assertNotSame(view, inputs.view());
        view.zero();
        projection.zero();
        inverseProjection.zero();
        inverseView.zero();
        assertTrue(inputs.view().isFinite(), "the stored matrices must not alias the inputs");
        assertTrue(inputs.projection().isFinite());
        assertEquals(42, inputs.sceneDepthTexture());
        assertTrue(inputs.hasSceneDepth());
    }

    @Test
    void zeroDepthIsTheOnlyAcceptedUnavailableValue() {
        Matrix4f identity = new Matrix4f();
        StochasticTransparencyInputs inputs = new StochasticTransparencyInputs(
                identity, identity, identity, identity, 0);
        assertFalse(inputs.hasSceneDepth());
        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyInputs(identity, identity, identity,
                        identity, -1));
    }

    @Test
    void nonFiniteMatricesAreRejected() {
        Matrix4f identity = new Matrix4f();
        Matrix4f poisoned = new Matrix4f().translate(Float.NaN, 0.0f, 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyInputs(poisoned, identity, identity,
                        identity, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyInputs(identity, poisoned, identity,
                        identity, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyInputs(identity, identity, poisoned,
                        identity, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StochasticTransparencyInputs(identity, identity, identity,
                        poisoned, 1));
        assertThrows(NullPointerException.class,
                () -> new StochasticTransparencyInputs(null, identity, identity,
                        identity, 1));
    }
}
