package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UiSdfPrimitiveTest {
    @Test
    void roundedShapeClampsRadiiToHalfExtentsAndFreezesParameters() {
        UiDisplayList list = new UiDisplayList()
                .addSdfShape(new UiScreenRect(4, 5, 20, 10),
                        UiSdfShape.roundedRect(100, 8, 6, 4),
                        UiSdfDecoration.solid(UiColor.WHITE)
                                .withBorder(UiColor.BLACK, 2.0f),
                        UiBlendMode.PREMULTIPLIED_ALPHA);

        UiDisplayList snapshot = list.freeze();
        assertEquals(UiPrimitiveKind.SDF_SHAPE, snapshot.primitiveKind(0));
        assertEquals(UiShaderVariant.SDF, snapshot.primitiveShader(0));
        assertEquals(UiSdfShape.Kind.ROUNDED_RECT, snapshot.sdfKind(0));
        assertEquals(5.0f, snapshot.sdfRadius(0, 0));
        assertEquals(5.0f, snapshot.sdfRadius(0, 1));
        assertEquals(2.0f, snapshot.sdfParameter(0, 3));
        assertThrows(IllegalStateException.class, snapshot::clear);
    }

    @Test
    void gradientStopsAreSortedAndRequireFullDomainCoverage() {
        UiGradient gradient = new UiGradient(List.of(
                new UiGradientStop(1.0f, UiColor.WHITE),
                new UiGradientStop(0.0f, UiColor.BLACK)), 0.5f);
        assertEquals(0.0f, gradient.stops().getFirst().offset());
        assertEquals(1.0f, gradient.stops().getLast().offset());
        assertThrows(IllegalArgumentException.class,
                () -> new UiGradient(List.of(new UiGradientStop(0.2f, UiColor.WHITE),
                        new UiGradientStop(1.0f, UiColor.BLACK)), 0.0f));
    }

    @Test
    void sdfShapesDoNotMergeAcrossPaintParameters() {
        UiDisplayList list = new UiDisplayList()
                .addSdfShape(new UiScreenRect(0, 0, 10, 10), UiSdfShape.roundedRect(2),
                        UiSdfDecoration.solid(UiColor.WHITE), UiBlendMode.PREMULTIPLIED_ALPHA)
                .addSdfShape(new UiScreenRect(10, 0, 10, 10), UiSdfShape.circle(),
                        UiSdfDecoration.solid(UiColor.WHITE), UiBlendMode.PREMULTIPLIED_ALPHA);
        UiBatcher.Result batches = new UiBatcher().batch(list);
        assertEquals(2, batches.size());
        assertEquals(UiShaderVariant.SDF, batches.shader(0));
        assertEquals(UiShaderVariant.SDF, batches.shader(1));
    }
}
