package com.kaleblangley.haikalat.subsystems.postprocess;

import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenDistortionTest {
    @Test
    void recordDefensivelyCopiesScreenVectors() {
        Vector2f center = new Vector2f(0.5f, 0.5f);
        Vector2f extent = new Vector2f(0.25f, 0.5f);
        Vector2f offset = new Vector2f(0.05f, -0.02f);
        ScreenDistortion distortion = new ScreenDistortion(4L, 1, center, extent, offset,
                0.8f, 0.7f, 0.01f, 0.4f, 0.2f, 1.5f);
        center.set(0.0f);
        extent.set(2.0f);
        offset.set(0.2f, 0.2f);

        assertEquals(new Vector2f(0.5f, 0.5f), distortion.centerUv());
        assertEquals(new Vector2f(0.25f, 0.5f), distortion.extentUv());
        assertEquals(new Vector2f(0.05f, -0.02f), distortion.uvOffset());
        assertEquals(new ScreenDistortion.Key(4L, 1), distortion.key());
    }

    @Test
    void rejectsOutOfContractValues() {
        assertThrows(IllegalArgumentException.class, () -> distortion(0L, 0, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> distortion(1L, -1, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(1.1f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(), 1.0f, 0.5f, 0.0f, 0.0f,
                        0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.0f, 0.5f), new Vector2f(), 1.0f, 0.5f, 0.0f, 0.0f,
                        0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(5.0f, 0.5f), new Vector2f(), 1.0f, 0.5f, 0.0f, 0.0f,
                        0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(0.26f, 0.0f), 1.0f, 0.5f, 0.0f,
                        0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(), 1.1f, 0.5f, 0.0f, 0.0f,
                        0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(), 1.0f, 1.1f, 0.0f, 0.0f,
                        0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(), 1.0f, 0.5f, 1.1f, 0.0f,
                        0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(), 1.0f, 0.5f, 0.0f, Float.NaN,
                        0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(), 1.0f, 0.5f, 0.0f, 0.0f,
                        1.1f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new ScreenDistortion(1L, 0, new Vector2f(0.5f, 0.5f),
                        new Vector2f(0.5f), new Vector2f(), 1.0f, 0.5f, 0.0f, 0.0f,
                        0.0f, -1.0f));
    }

    @Test
    void ordersFarToNearAndBreaksDepthTiesByIdentity() {
        ScreenDistortion farthest = distortion(5L, 0, 0.9f);
        ScreenDistortion tieLater = distortion(2L, 0, 0.5f);
        ScreenDistortion tieEarlier = distortion(1L, 3, 0.5f);
        ScreenDistortion nearest = distortion(9L, 0, 0.1f);

        List<ScreenDistortion> ordered = ScreenDistortion.orderForComposite(
                List.of(nearest, tieLater, farthest, tieEarlier), 8);

        assertEquals(List.of(
                        new ScreenDistortion.Key(5L, 0),
                        new ScreenDistortion.Key(1L, 3),
                        new ScreenDistortion.Key(2L, 0),
                        new ScreenDistortion.Key(9L, 0)),
                ordered.stream().map(ScreenDistortion::key).toList());
    }

    @Test
    void boundKeepsTheNearestContributions() {
        ScreenDistortion farthest = distortion(1L, 0, 0.9f);
        ScreenDistortion middle = distortion(2L, 0, 0.7f);
        ScreenDistortion nearer = distortion(3L, 0, 0.5f);
        ScreenDistortion nearest = distortion(4L, 0, 0.3f);

        List<ScreenDistortion> ordered = ScreenDistortion.orderForComposite(
                List.of(farthest, middle, nearer, nearest), 2);

        assertEquals(List.of(
                        new ScreenDistortion.Key(3L, 0),
                        new ScreenDistortion.Key(4L, 0)),
                ordered.stream().map(ScreenDistortion::key).toList());
    }

    @Test
    void rejectsDuplicateIdentitiesAndInvalidBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> ScreenDistortion.orderForComposite(
                        List.of(distortion(1L, 0, 0.5f), distortion(1L, 0, 0.2f)), 4));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenDistortion.orderForComposite(List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenDistortion.orderForComposite(List.of(),
                        ScreenDistortion.MAXIMUM_CONTRIBUTIONS + 1));
        assertThrows(NullPointerException.class,
                () -> ScreenDistortion.orderForComposite(null, 4));
    }

    @Test
    void emptyStackIsAValidExactCopyInput() {
        assertTrue(ScreenDistortion.orderForComposite(List.of(), 4).isEmpty());
    }

    private static ScreenDistortion distortion(long effectId, int index, float depth) {
        return new ScreenDistortion(effectId, index, new Vector2f(0.5f, 0.5f),
                new Vector2f(0.5f), new Vector2f(0.05f, 0.0f), 1.0f, depth, 0.01f,
                0.0f, 0.0f, 0.0f);
    }
}
