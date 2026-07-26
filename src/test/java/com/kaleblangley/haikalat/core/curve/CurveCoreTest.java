package com.kaleblangley.haikalat.core.curve;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurveCoreTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void presetsHaveExactEndpointsAndKnownMidpoints() {
        assertEquals(0.0f, Curves.LINEAR.sample(0.0f));
        assertEquals(1.0f, Curves.LINEAR.sample(1.0f));
        assertEquals(0.125f, Curves.EASE_IN_CUBIC.sample(0.5f), EPSILON);
        assertEquals(0.875f, Curves.EASE_OUT_CUBIC.sample(0.5f), EPSILON);
        assertEquals(0.5f, Curves.EASE_IN_OUT_CUBIC.sample(0.5f), EPSILON);
        Curve1f spring = Curves.spring(7.0f, 11.0f);
        assertEquals(0.0f, spring.sample(0.0f));
        assertEquals(1.0f, spring.sample(1.0f));
        assertTrue(Float.isFinite(spring.sample(0.37f)));
    }

    @Test
    void normalizedInputsAndCurveParametersAreValidated() {
        for (Curve1f curve : new Curve1f[]{Curves.LINEAR, Curves.EASE_IN_CUBIC,
                Curves.EASE_OUT_CUBIC, Curves.EASE_IN_OUT_CUBIC,
                Curves.spring(4.0f, 8.0f), Curves.cubicBezier(0.2f, -1.0f, 0.8f, 2.0f)}) {
            assertThrows(IllegalArgumentException.class, () -> curve.sample(-0.01f));
            assertThrows(IllegalArgumentException.class, () -> curve.sample(1.01f));
            assertThrows(IllegalArgumentException.class, () -> curve.sample(Float.NaN));
        }
        assertThrows(IllegalArgumentException.class, () -> Curves.spring(-1.0f, 2.0f));
        assertThrows(IllegalArgumentException.class, () -> Curves.spring(1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> Curves.cubicBezier(-0.1f, 0.0f, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> Curves.cubicBezier(0.0f, Float.POSITIVE_INFINITY, 1.0f, 1.0f));
    }

    @Test
    void cubicBezierSolvesTimeAxisAndFallsBackForFlatDerivatives() {
        Curve1f identity = Curves.cubicBezier(0.0f, 0.0f, 1.0f, 1.0f);
        for (int index = 0; index <= 20; index++) {
            float time = index / 20.0f;
            assertEquals(time, identity.sample(time), 2.0e-5f);
        }
        Curve1f ease = Curves.cubicBezier(0.25f, 0.1f, 0.25f, 1.0f);
        assertEquals(0.8024f, ease.sample(0.5f), 5.0e-4f);
        Curve1f flatStart = Curves.cubicBezier(0.0f, 0.5f, 0.0f, 0.8f);
        assertTrue(Float.isFinite(flatStart.sample(0.0001f)));
        Curve1f symmetric = Curves.cubicBezier(0.42f, 0.0f, 0.58f, 1.0f);
        assertEquals(0.5f, symmetric.sample(0.5f), EPSILON);
    }

    @Test
    void floatTrackSamplesAllSegmentModesAndDefensivelyCopiesKeys() {
        FloatTrack.Key[] keys = {
                new FloatTrack.Key(0.0f, 2.0f, FloatTrack.Interpolation.STEP),
                new FloatTrack.Key(0.25f, 4.0f, FloatTrack.Interpolation.LINEAR),
                new FloatTrack.Key(0.5f, 8.0f, 0.0f, 4.0f,
                        FloatTrack.Interpolation.CUBIC_HERMITE),
                new FloatTrack.Key(1.0f, 10.0f, 0.0f, 0.0f,
                        FloatTrack.Interpolation.LINEAR)
        };
        FloatTrack track = new FloatTrack(keys);
        keys[0] = new FloatTrack.Key(0.0f, 99.0f, FloatTrack.Interpolation.LINEAR);
        assertEquals(2.0f, track.sample(0.1f), EPSILON);
        assertEquals(6.0f, track.sample(0.375f), EPSILON);
        assertEquals(9.25f, track.sample(0.75f), EPSILON);
        assertEquals(2.0f, track.sample(0.0f));
        assertEquals(10.0f, track.sample(1.0f));
        assertThrows(IllegalArgumentException.class, FloatTrack::new);
        assertThrows(IllegalArgumentException.class, () -> new FloatTrack(
                new FloatTrack.Key(0.5f, 0.0f, FloatTrack.Interpolation.LINEAR),
                new FloatTrack.Key(0.5f, 1.0f, FloatTrack.Interpolation.LINEAR)));
        FloatTrack overflowing = new FloatTrack(
                new FloatTrack.Key(0.0f, Float.MAX_VALUE, 0.0f, Float.MAX_VALUE,
                        FloatTrack.Interpolation.CUBIC_HERMITE),
                new FloatTrack.Key(1.0f, Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f,
                        FloatTrack.Interpolation.LINEAR));
        assertThrows(IllegalStateException.class, () -> overflowing.sample(0.5f));
    }

    @Test
    void colorGradientSupportsHdrAndCallerOwnedDestination() {
        ColorGradient gradient = new ColorGradient(
                new ColorGradient.Stop(0.2f, 2.0f, 0.0f, 0.0f, 1.0f),
                new ColorGradient.Stop(0.8f, 0.0f, 1.0f, 2.0f, 0.0f));
        Vector4f destination = new Vector4f();
        assertTrue(destination == gradient.sample(0.5f, destination));
        assertEquals(new Vector4f(1.0f, 0.5f, 1.0f, 0.5f), destination);
        gradient.sample(0.0f, destination);
        assertEquals(new Vector4f(2.0f, 0.0f, 0.0f, 1.0f), destination);
        gradient.sample(1.0f, destination);
        assertEquals(new Vector4f(0.0f, 1.0f, 2.0f, 0.0f), destination);
        assertThrows(IllegalArgumentException.class, () -> new ColorGradient.Stop(
                0.0f, -1.0f, 0.0f, 0.0f, 1.0f));
    }

    @Test
    void bezierPathProvidesEndpointsTangentsAndMonotonicArcLookup() {
        BezierPath3f path = new BezierPath3f(
                new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f),
                new Vector3f(1.0f, 1.0f, 0.0f), new Vector3f(2.0f, 1.0f, 0.0f));
        Vector3f value = new Vector3f();
        path.sample(0.0f, value);
        assertEquals(new Vector3f(0.0f, 0.0f, 0.0f), value);
        path.sample(1.0f, value);
        assertEquals(new Vector3f(2.0f, 1.0f, 0.0f), value);
        path.tangent(0.0f, value);
        assertEquals(new Vector3f(3.0f, 0.0f, 0.0f), value);
        float previous = -1.0f;
        for (int index = 0; index <= 20; index++) {
            float parameter = path.parameterAtNormalizedDistance(index / 20.0f);
            assertTrue(parameter >= previous);
            previous = parameter;
        }
        BezierPath3f degenerate = new BezierPath3f(new Vector3f(), new Vector3f(),
                new Vector3f(), new Vector3f());
        assertEquals(0.0f, degenerate.parameterAtNormalizedDistance(0.75f));
        degenerate.tangent(0.5f, value);
        assertEquals(new Vector3f(), value);
    }

    @Test
    void curveLutPreservesEndpointsAndBoundsApproximationError() {
        CurveLut lut = new CurveLut(Curves.EASE_IN_OUT_CUBIC, 129);
        assertEquals(129, lut.sampleCount());
        assertEquals(0.0f, lut.sample(0.0f));
        assertEquals(1.0f, lut.sample(1.0f));
        for (int index = 0; index <= 100; index++) {
            float time = index / 100.0f;
            assertEquals(Curves.EASE_IN_OUT_CUBIC.sample(time), lut.sample(time), 2.0e-4f);
        }
        assertThrows(IllegalArgumentException.class, () -> new CurveLut(Curves.LINEAR, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CurveLut(time -> Float.NaN, 4));
    }
}
