package com.kaleblangley.haikalat.subsystems.ui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UiScreenRectTest {
    @Test
    void fractionalDpiUsesOutwardFloorCeilAndBottomLeftOrigin() {
        UiScreenRect logical = new UiScreenRect(1.25, 2.25, 3.5, 4.5);

        GlScissorRect scissor = logical.toGlScissor(1.5, 1.5, 10, 12);

        assertEquals(new GlScissorRect(1, 1, 7, 8), scissor);
    }

    @Test
    void conversionClampsEveryEdgeToFramebuffer() {
        UiScreenRect partlyOutside = new UiScreenRect(-2.0, -1.0, 5.0, 4.0);
        UiScreenRect beyondRightBottom = new UiScreenRect(3.0, 2.0, 8.0, 8.0);

        assertEquals(new GlScissorRect(0, 0, 6, 6),
                partlyOutside.toGlScissor(2.0, 2.0, 8, 6));
        assertEquals(new GlScissorRect(6, 0, 2, 2),
                beyondRightBottom.toGlScissor(2.0, 2.0, 8, 6));
    }

    @Test
    void nestedClipsIntersectBeforeAnyDpiRounding() {
        UiScreenRect outer = new UiScreenRect(0.4, 0.4, 2.4, 2.4);
        UiScreenRect inner = new UiScreenRect(1.6, 1.6, 2.4, 2.4);

        UiScreenRect intersection = outer.intersect(inner);

        assertEquals(1.6, intersection.x(), 1.0e-12);
        assertEquals(1.6, intersection.y(), 1.0e-12);
        assertEquals(1.2, intersection.width(), 1.0e-12);
        assertEquals(1.2, intersection.height(), 1.0e-12);
        assertEquals(new GlScissorRect(2, 6, 2, 2),
                intersection.toGlScissor(1.25, 1.25, 10, 10));
    }

    @Test
    void emptyLogicalClipNeverExpandsToOnePixel() {
        GlScissorRect verticalEmpty = new UiScreenRect(1.25, 2.25, 0.0, 4.5)
                .toGlScissor(1.5, 1.5, 10, 12);
        GlScissorRect horizontalEmpty = new UiScreenRect(1.25, 2.25, 3.5, 0.0)
                .toGlScissor(1.5, 1.5, 10, 12);

        assertEquals(0, verticalEmpty.width());
        assertEquals(0, horizontalEmpty.height());
    }

    @Test
    void rejectsInvalidGeometryScaleAndFramebuffer() {
        assertThrows(IllegalArgumentException.class,
                () -> new UiScreenRect(0.0, 0.0, -1.0, 1.0));
        UiScreenRect valid = new UiScreenRect(0.0, 0.0, 1.0, 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> valid.toGlScissor(0.0, 1.0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> valid.toGlScissor(1.0, 1.0, -1, 1));
    }
}
