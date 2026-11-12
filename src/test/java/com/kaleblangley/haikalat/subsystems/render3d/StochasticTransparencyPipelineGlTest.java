package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencyPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glGetTexImage;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/**
 * Real-GL pipeline path for the caller-selected stochastic-transparency set.
 *
 * <p>Proves that the production pipeline inserts the composite after the scene
 * colour producer and before the post-process chain, that an enabled set is
 * visible, that disabling restores the baseline byte-for-byte, and that the
 * shared opaque depth is never written.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class StochasticTransparencyPipelineGlTest {
    private static final int SIZE = 32;
    private static final OitSurface QUAD = OitSurface.of(
            new Vector3f(0.0f, 0.0f, -2.0f), new Vector3f(2.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 2.0f, 0.0f), new Vector3f(1.0f, 0.2f, 0.1f), 1.0f);

    @Test
    void selectedSurfaceSetCompositesAndDisabledSettingsRestoreTheBaseline() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window);
                try {
                    // Without settings the staged set must not create a pass or a draw.
                    pipeline.build();
                    assertFalse(pipeline.graph().hasPass(StochasticTransparencyPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    pipeline.applyStochasticTransparency(
                            StochasticTransparencySettings.defaults());
                    assertTrue(pipeline.graph().hasPass(StochasticTransparencyPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] composited = pipeline.captureLinearHdrRgbaFloat();
                    int centre = (SIZE / 2 * SIZE + SIZE / 2) * 4;
                    assertEquals(1.0f, composited[centre], 0.02f);
                    assertEquals(0.2f, composited[centre + 1], 0.02f);
                    assertEquals(0.1f, composited[centre + 2], 0.02f);
                    assertTrue(different(baseline, composited),
                            "the selected surface set must modify the scene colour");

                    pipeline.applyStochasticTransparency(
                            StochasticTransparencySettings.disabled());
                    assertFalse(pipeline.graph().hasPass(StochasticTransparencyPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] restored = pipeline.captureLinearHdrRgbaFloat();
                    assertArrayBitsEqual(baseline, restored,
                            "disabling must restore the byte-identical baseline");
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("stochastic transparency pipeline composition");
        }
    }

    @Test
    void stochasticCompositeNeverWritesTheSharedOpaqueDepth() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window)
                        .stochasticTransparency(StochasticTransparencySettings.defaults());
                try (var host = OwnedPresentationTarget.create(SIZE, SIZE,
                        RenderFormat.RGBA8, true, 1)) {
                    pipeline.build();
                    int[] depthTexture = new int[1];
                    addDepthProbe(pipeline, depthTexture);
                    seedHostDepth(host, 0.5f);
                    pipeline.execute(new GlRenderDevice(), camera(), borrowedTarget(host),
                            1f / 60f);

                    float[] depth = readDepth(depthTexture[0]);
                    for (int index = 0; index < depth.length; index++) {
                        assertEquals(0.5f, depth[index],
                                "stochastic composite wrote shared depth at index " + index);
                    }
                    float[] composited = pipeline.captureLinearHdrRgbaFloat();
                    int centre = (SIZE / 2 * SIZE + SIZE / 2) * 4;
                    assertEquals(1.0f, composited[centre], 0.02f,
                            "the selected set must still composite over the scene colour");
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("stochastic transparency pipeline depth ownership");
        }
    }

    private static RenderPipeline pipeline(GlfwWindow window) {
        return new RenderPipeline(window, new Scene(camera()), null, RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build())
                .stochasticTransparencySurfaces(List.of(QUAD));
    }

    private static ExternalCamera camera() {
        return VolumetricMediumGlTest.camera(false, 1.0f);
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("Stochastic transparency wiring").build();
    }

    private static void addDepthProbe(RenderPipeline pipeline, int[] depthTexture) {
        var depth = pipeline.activeGenerationForTest().topology().sceneSurface()
                .single(com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH);
        if (depth == null) throw new IllegalStateException("fixture has no shared depth");
        pipeline.graph().addPass("StochasticDepthProbe")
                .computeOnly()
                .dependsOn(pipeline.finalPassName())
                .execute((resources, commands) ->
                        depthTexture[0] = resources.depthAttachment(depth.name()));
    }

    private static void seedHostDepth(OwnedPresentationTarget host, float value) {
        org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER,
                host.target().drawFramebufferId());
        glClearDepth(value);
        glClear(GL_DEPTH_BUFFER_BIT);
        org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Re-declares the owned host framebuffer as a borrowed target for the depth copy. */
    private static PresentationTarget borrowedTarget(OwnedPresentationTarget host) {
        PresentationTarget owned = host.target();
        var color = owned.color().orElseThrow();
        var depth = owned.depth().orElseThrow();
        return PresentationTarget.builder(SIZE, SIZE)
                .framebuffer(owned.drawFramebufferId())
                .color(ExternalAttachment.borrowedColor(color.textureId(), color.format(),
                        SIZE, SIZE))
                .depth(ExternalAttachment.borrowedDepth(depth.textureId(), depth.format(),
                        SIZE, SIZE))
                .build();
    }

    private static float[] readDepth(int texture) {
        int framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(SIZE * SIZE);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
            org.lwjgl.opengl.GL11.glDrawBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, SIZE, SIZE,
                    org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, GL_FLOAT, buffer);
            float[] result = new float[SIZE * SIZE];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }
    }

    private static boolean different(float[] left, float[] right) {
        for (int index = 0; index < left.length; index++) {
            if (Float.floatToIntBits(left[index]) != Float.floatToIntBits(right[index])) {
                return true;
            }
        }
        return false;
    }

    private static void assertArrayBitsEqual(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Float.floatToIntBits(expected[index]),
                    Float.floatToIntBits(actual[index]), message + " at index " + index);
        }
    }
}
