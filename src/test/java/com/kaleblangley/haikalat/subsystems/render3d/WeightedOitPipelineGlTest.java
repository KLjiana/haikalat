package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL production path for the caller-selected weighted-OIT collection.
 *
 * <p>Proves that the pipeline inserts the resolve over the final HDR scene
 * colour, that a staged set visibly contributes, that an explicitly declared
 * empty set is an exact no-op, that disabling restores the baseline
 * byte-for-byte, and that the resolve graph pass has no shared-depth
 * attachment.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class WeightedOitPipelineGlTest {
    private static final int SIZE = 32;
    private static final OitSurface QUAD = OitSurface.of(
            new Vector3f(0.0f, 0.0f, -2.0f), new Vector3f(2.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 2.0f, 0.0f), new Vector3f(1.0f, 0.2f, 0.1f), 1.0f);

    @Test
    void declaredEmptySetIsANoOpAndAStagedSetComposites() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window);
                try {
                    pipeline.build();
                    assertFalse(pipeline.graph().hasPass(WeightedOitPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    // Selecting the strategy with an explicitly empty set must
                    // add the pass but record no draw and leave the target intact.
                    pipeline.weightedOitSurfaces(List.of());
                    pipeline.applyWeightedOit(WeightedOitSettings.defaults());
                    assertTrue(pipeline.graph().hasPass(WeightedOitPass.PASS_NAME));
                    var oitPass = pass(pipeline, WeightedOitPass.PASS_NAME);
                    assertNull(oitPass.depthAttachment(),
                            "the OIT resolve must not attach or write the shared depth");
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(),
                            "a declared empty collection must be an exact no-op");

                    pipeline.weightedOitSurfaces(List.of(QUAD));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] composited = pipeline.captureLinearHdrRgbaFloat();
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    int centre = ((height / 2) * width + width / 2) * 4;
                    assertEquals(1.0f, composited[centre], 0.02f);
                    assertEquals(0.2f, composited[centre + 1], 0.02f);
                    assertEquals(0.1f, composited[centre + 2], 0.02f);
                    assertTrue(different(baseline, composited),
                            "the selected OIT set must modify the scene colour");

                    pipeline.applyWeightedOit(WeightedOitSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(WeightedOitPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(),
                            "disabling OIT must restore the byte-identical baseline");
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("weighted OIT pipeline composition");
        }
    }

    @Test
    void oitAndStochasticCannotRecordTheSameSelectedSurface() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            RenderPipeline pipeline = pipeline(window)
                    .stochasticTransparency(
                            com.kaleblangley.haikalat.subsystems.render3d.transparency
                                    .StochasticTransparencySettings.defaults());
            try {
                pipeline.build();
                pipeline.stochasticTransparencySurfaces(List.of(QUAD));
                pipeline.applyWeightedOit(WeightedOitSettings.defaults());
                pipeline.weightedOitSurfaces(List.of());
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> pipeline.weightedOitSurfaces(List.of(QUAD)),
                        "the same surface cannot enter both collections");
            } finally {
                pipeline.close();
            }
        }
    }

    private static RenderPipeline pipeline(GlfwWindow window) {
        return new RenderPipeline(window, new Scene(camera()), null, RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build());
    }

    private static ExternalCamera camera() {
        return VolumetricMediumGlTest.camera(false, 1.0f);
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("Weighted OIT wiring").build();
    }

    private static RenderGraph.PassDescription pass(RenderPipeline pipeline, String name) {
        RenderGraph.PassDescription description = pipeline.graph().description().passes()
                .stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElse(null);
        assertNotNull(description, "missing pass " + name);
        return description;
    }

    private static boolean different(float[] left, float[] right) {
        if (left.length != right.length) return true;
        for (int index = 0; index < left.length; index++) {
            if (Float.floatToIntBits(left[index]) != Float.floatToIntBits(right[index])) {
                return true;
            }
        }
        return false;
    }
}
