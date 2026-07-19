package com.kaleblangley.haikalat.core.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Bounds3fTest {
    @Test
    void finiteBoundsAcceptDegenerateAxesAndHaveStableValueSemantics() {
        Bounds3f point = Bounds3f.of(-0.0f, 2.0f, 3.0f, 0.0f, 2.0f, 3.0f);

        assertTrue(point.isFinite());
        assertFalse(point.isUnbounded());
        assertEquals(Bounds3f.of(0.0f, 2.0f, 3.0f, 0.0f, 2.0f, 3.0f), point);
        assertEquals(point.hashCode(), Bounds3f.of(0, 2, 3, 0, 2, 3).hashCode());
    }

    @Test
    void unboundedIsExplicitAndRejectsInvalidFiniteRanges() {
        assertSame(Bounds3f.unbounded(), Bounds3f.unbounded());
        assertTrue(Bounds3f.unbounded().isUnbounded());
        assertNotEquals(Bounds3f.unbounded(), Bounds3f.of(0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Bounds3f.of(1, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Bounds3f.of(Float.NaN, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Bounds3f.of(0, 0, 0, Float.POSITIVE_INFINITY, 1, 1));
    }
}
