package com.kaleblangley.haikalat.subsystems.ui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiCompositorFoundationTest {
    @Test
    void layerDescriptionClampsBlurAndDisplayListKeepsBalancedBoundaries() {
        UiLayerDescription layer = UiLayerDescription.builder("glass_panel",
                        new UiScreenRect(10, 20, 400, 260))
                .blur(100.0f, 4)
                .effect(UiLayerDescription.Effect.GLOW)
                .dirtyRevision(7L)
                .build();
        assertEquals(64.0f, layer.blurRadius());
        assertEquals(104_000L, layer.estimatedPixels());

        UiDisplayList list = new UiDisplayList();
        list.beginLayer(layer)
                .addSolidQuad(new UiScreenRect(10, 20, 10, 10),
                        0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA)
                .endLayer();
        assertEquals(UiPrimitiveKind.LAYER_BEGIN, list.primitiveKind(0));
        assertEquals(layer, list.primitiveLayer(0));
        assertEquals(1, new UiBatcher().batch(list).size());
        assertEquals(3, list.freeze().primitiveCount());

        UiDisplayList unbalanced = new UiDisplayList();
        unbalanced.beginLayer(layer);
        assertThrows(IllegalStateException.class, unbalanced::freeze);
    }

    @Test
    void optionsRejectUndefinedCompositorStateAndBlurKernelIsNormalized() {
        UiLayerDescription layer = UiLayerDescription.builder("panel",
                new UiScreenRect(0, 0, 100, 100)).build();
        assertThrows(IllegalArgumentException.class, () -> UiAttachmentOptions.builder()
                .layers(List.of(layer)).build());
        assertThrows(IllegalArgumentException.class, () -> UiLayerDescription.builder("glass",
                        new UiScreenRect(0, 0, 100, 100))
                .backdropRequired(true).build());

        float[] weights = UiBlurKernel.gaussian(12.0f);
        double sum = weights[0];
        for (int index = 1; index < weights.length; index++) sum += weights[index] * 2.0;
        assertEquals(1.0, sum, 1.0e-5);
        assertTrue(weights[0] > weights[weights.length - 1]);
    }
}
