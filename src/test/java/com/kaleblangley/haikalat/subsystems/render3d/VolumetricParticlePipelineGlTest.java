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
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Real-GL pipeline path for the local volumetric-particle composition.
 *
 * <p>This is not a bathymetry of the pass itself (that class already has its own
 * GL contract); it proves the production {@code RenderPipeline} order: the
 * particle output appears after the fog opaque composite, only inside its
 * projected bounds, and never writes the shared single-sample opaque depth.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricParticlePipelineGlTest {
    private static final int SIZE = 32;

    @Test
    void particleColourIsVisibleInsideTheBodyAndBoundedOutsideIt() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window);
                try {
                    pipeline.build();
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    var generation = pipeline.activeGenerationForTest();
                    assertNotNull(generation.volumetricParticles());

                    float[] fog = pipeline.graph().readColorAttachmentRgbaFloat(
                            VolumetricPassBuilder.COMPOSITE_COLOR);
                    float[] particles = pipeline.graph().readColorAttachmentRgbaFloat(
                            VolumetricParticlePass.COLOR_TEXTURE);
                    assertEquals(fog.length, particles.length);

                    int changed = 0;
                    int minX = SIZE;
                    int minY = SIZE;
                    int maxX = -1;
                    int maxY = -1;
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            int index = (y * SIZE + x) * 4;
                            if (!samePixel(fog, particles, index)) {
                                changed++;
                                minX = Math.min(minX, x);
                                minY = Math.min(minY, y);
                                maxX = Math.max(maxX, x);
                                maxY = Math.max(maxY, y);
                            }
                        }
                    }
                    assertTrue(changed > 0,
                            "the particle medium must visibly modify the fog-composited HDR colour");
                    assertTrue((maxX - minX + 1) < SIZE * 0.9f
                                    && (maxY - minY + 1) < SIZE * 0.9f,
                            "the changed region must stay inside the projected body bounds: "
                                    + (maxX - minX + 1) + "x" + (maxY - minY + 1));

                    // The outer two-pixel border is outside the projected body; the
                    // pass copies the source there and must leave it byte-identical.
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            if (x >= 2 && x < SIZE - 2 && y >= 2 && y < SIZE - 2) continue;
                            int index = (y * SIZE + x) * 4;
                            for (int component = 0; component < 4; component++) {
                                assertEquals(Float.floatToIntBits(fog[index + component]),
                                        Float.floatToIntBits(particles[index + component]),
                                        "border pixel (" + x + "," + y + ") changed");
                            }
                        }
                    }
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("particle pipeline colour composition");
        }
    }

    @Test
    void particlePassNeverWritesTheSharedOpaqueDepth() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window);
                try (var host = OwnedPresentationTarget.create(SIZE, SIZE,
                        RenderFormat.RGBA8, true, 1)) {
                    pipeline.build();
                    int[] depthTexture = new int[1];
                    addDepthProbe(pipeline, depthTexture);
                    seedHostDepth(host, 0.5f);
                    pipeline.execute(new GlRenderDevice(), camera(), borrowedTarget(host),
                            1f / 60f);

                    assertTrue(depthTexture[0] > 0, "the shared depth texture must be readable");
                    float[] depth = readDepth(depthTexture[0]);
                    for (int index = 0; index < depth.length; index++) {
                        assertEquals(0.5f, depth[index],
                                "particle composition wrote shared depth at index " + index);
                    }
                } finally {
                    pipeline.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("particle pipeline depth ownership");
        }
    }

    private static RenderPipeline pipeline(GlfwWindow window) {
        Scene scene = new Scene(camera());
        return new RenderPipeline(window, scene, null, RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build())
                .volumetricFog(VolumetricMediumGlTest.settings(FogMediumSettings.vacuum(),
                        List.of()))
                .volumetricParticles(particles());
    }

    private static ExternalCamera camera() {
        return VolumetricMediumGlTest.camera(false, 1.0f);
    }

    private static VolumetricParticleSettings particles() {
        return new VolumetricParticleSettings(true, 1.0f, 0.2f, 0.8f,
                new Vector3f(0.0f, 0.0f, -3.0f), new Vector3f(0.6f, 0.6f, 0.6f),
                32, 0.1f, new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f),
                0.0f, VolumetricParticleSettings.FogBlend.ADDITIVE, 1.0f, 1.0f);
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("Volumetric particle wiring").build();
    }

    private static void addDepthProbe(RenderPipeline pipeline, int[] depthTexture) {
        var depth = pipeline.activeGenerationForTest().topology().sceneSurface()
                .single(com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH);
        if (depth == null) throw new IllegalStateException("fixture has no shared depth");
        pipeline.graph().addPass("ParticleDepthProbe")
                .computeOnly()
                .dependsOn(pipeline.finalPassName())
                .execute((resources, commands) ->
                        depthTexture[0] = resources.depthAttachment(depth.name()));
    }

    private static void seedHostDepth(OwnedPresentationTarget host, float value) {
        glBindFramebuffer(GL_FRAMEBUFFER, host.target().drawFramebufferId());
        glClearDepth(value);
        glClear(GL_DEPTH_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Re-declares the owned host framebuffer as a borrowed presentation target so
     * the pipeline copies the seeded host depth into its shared opaque depth.
     */
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

    private static void glBindFramebuffer(int target, int framebuffer) {
        org.lwjgl.opengl.GL30.glBindFramebuffer(target, framebuffer);
    }

    private static boolean samePixel(float[] left, float[] right, int index) {
        for (int component = 0; component < 4; component++) {
            if (Float.floatToIntBits(left[index + component])
                    != Float.floatToIntBits(right[index + component])) {
                return false;
            }
        }
        return true;
    }
}
