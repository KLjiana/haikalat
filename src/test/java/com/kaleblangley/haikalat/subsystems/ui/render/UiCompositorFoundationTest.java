package com.kaleblangley.haikalat.subsystems.ui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiCompositorFoundationTest {
    @Test
    void unsupportedOptionsFailBeforeAnyUiPassIsRegistered() {
        var rejected = new java.util.ArrayList<UiLayerDescription>();
        var bounds = new UiScreenRect(0, 0, 20, 20);
        for (var effect : UiLayerDescription.Effect.values())
            if (effect != UiLayerDescription.Effect.BLUR && effect != UiLayerDescription.Effect.BACKDROP_BLUR)
                rejected.add(UiLayerDescription.builder("unsupported", bounds).effect(effect).build());
        for (var blend : UiBlendMode.values())
            if (blend != UiBlendMode.PREMULTIPLIED_ALPHA)
                rejected.add(UiLayerDescription.builder("unsupported", bounds).blendMode(blend).build());
        rejected.add(UiLayerDescription.builder("unsupported", bounds).mask(UiLayerDescription.MaskMode.ROUNDED).build());
        rejected.add(UiLayerDescription.builder("unsupported", bounds).zOrder(1).build());
        rejected.add(UiLayerDescription.builder("unsupported", bounds).blur(2, 1).backdropBlur(2, 1).build());
        for (var description : rejected) {
            try (var graph = new com.kaleblangley.haikalat.core.graph.RenderGraph(20, 20)) {
                graph.addPass("Display").writeToBackbuffer().execute((r, c) -> {});
                var options = UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(description)).build();
                var error = assertThrows(IllegalArgumentException.class, () -> UiCompositor.initialPixels(graph, "Display", options));
                assertTrue(error.getMessage().contains("UNSUPPORTED_EFFECT"));
                assertTrue(error.getMessage().contains("unsupported"));
                assertEquals(List.of("Display"), graph.description().executionOrder());
            }
        }
    }
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
