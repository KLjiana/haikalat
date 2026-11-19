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
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/**
 * Real-GL pipeline path for the local volumetric-particle composition.
 *
 * <p>Proves the production order: the particle output appears after the fog
 * opaque composite, only inside its projected bounds, and never writes the
 * shared single-sample opaque depth. The colour case also separates the three
 * stages (fog composite, pass-owned target, graph colour) and prints their
 * values so a failure identifies the broken hand-off instead of guessing.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricParticlePipelineGlTest {
    private static final int SIZE = 32;
    private static final String DIAGNOSTIC = "PARTICLE-PIPELINE-DIAG";

    @Test
    void particleMediumIsVisibleAndBoundedInThePipelineColour() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                RenderPipeline pipeline = pipeline(window);
                try {
                    pipeline.build();
                    var generation = pipeline.activeGenerationForTest();
                    assertNotNull(generation.volumetricParticles());

                    SceneBufferChannel channel = SceneBufferChannel.DEPTH;
                    var depthAttachment = generation.topology().sceneSurface().single(channel);
                    assertNotNull(depthAttachment, "the fog composition must expose single depth");

                    // Prove graph execution of the bridge: a dependent probe runs
                    // immediately after the particle pass and captures the exact
                    // shared depth texture the executor consumed.
                    AtomicInteger probeRuns = new AtomicInteger();
                    int[] depthTexture = new int[1];
                    pipeline.graph().addPass("ParticleBridgeProbe")
                            .computeOnly()
                            .dependsOn(VolumetricParticlePass.PASS_NAME)
                            .execute((resources, commands) -> {
                                probeRuns.incrementAndGet();
                                depthTexture[0] = resources.depthAttachment(
                                        depthAttachment.name());
                            });

                    // The fixture relies on a cleared (no-occluder) depth; restore
                    // the GL default explicitly so a previously run case cannot
                    // leak a different clear depth into this graph.
                    glClearDepth(1.0f);
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);

                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(pipeline.graph().hasPass(VolumetricParticlePass.PASS_NAME),
                            "the active pipeline must declare the particle graph bridge");
                    assertTrue(order.indexOf(VolumetricPassBuilder.COMPOSITE_PASS)
                                    < order.indexOf(VolumetricParticlePass.PASS_NAME),
                            "particles must run after the fog composite: " + order);
                    assertTrue(order.indexOf(VolumetricParticlePass.PASS_NAME)
                                    < order.indexOf(VolumetricPassBuilder.TRANSPARENT_PASS),
                            "transparency must consume the particle bridge: " + order);
                    assertEquals(1, probeRuns.get(),
                            "the particle bridge was not executed by the graph");

                    // The window framebuffer is content-scaled on HiDPI hosts, so the
                    // graph extent is authoritative for every readback and loop.
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    float[] fog = pipeline.graph().readColorAttachmentRgbaFloat(
                            VolumetricPassBuilder.COMPOSITE_COLOR);
                    float[] graphColour = pipeline.graph().readColorAttachmentRgbaFloat(
                            VolumetricParticlePass.COLOR_TEXTURE);
                    float[] passTarget = readColorTexture(
                            generation.volumetricParticles().colorTexture(), width, height);
                    float[] depth = readDepth(depthTexture[0], width, height);
                    assertEquals(width * height * 4, fog.length,
                            "the fog composite readback must cover the graph extent");
                    assertEquals(fog.length, graphColour.length);
                    assertEquals(fog.length, passTarget.length,
                            "the pass target must cover the same graph extent");

                    int changed = countDifferences(fog, graphColour);
                    int passChanged = countDifferences(fog, passTarget);
                    int center = ((height / 2) * width + width / 2) * 4;
                    float depthCenter = depth[(height / 2) * width + width / 2];
                    String diagnostic = DIAGNOSTIC
                            + " extent=" + width + "x" + height
                            + " executions=" + probeRuns.get()
                            + " order=" + order
                            + " lens=[fog:" + fog.length + ",pass:" + passTarget.length
                            + ",graph:" + graphColour.length + "]"
                            + " depthCenter=" + depthCenter
                            + " fogCenter=" + value(fog, center)
                            + " passCenter=" + value(passTarget, center)
                            + " graphCenter=" + value(graphColour, center)
                            + " fogVsPassChanged=" + passChanged
                            + " fogVsGraphChanged=" + changed;
                    System.out.println(diagnostic);

                    // Precondition: the fixture must actually be unoccluded. A
                    // leaked clear depth or a depth-writing pass shows up here.
                    assertTrue(depthCenter >= 1.0f - 1.0e-6f,
                            "the particle fixture must start from a cleared depth; " + diagnostic);
                    assertTrue(passChanged > 0,
                            "the pass-owned particle target is unchanged; "
                                    + "record/colour source/depth is wrong; " + diagnostic);
                    assertTrue(changed > 0,
                            "the particle medium must visibly modify the fog-composited HDR colour; "
                                    + diagnostic);

                    int minX = width;
                    int minY = height;
                    int maxX = -1;
                    int maxY = -1;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int index = (y * width + x) * 4;
                            if (!samePixel(fog, graphColour, index)) {
                                minX = Math.min(minX, x);
                                minY = Math.min(minY, y);
                                maxX = Math.max(maxX, x);
                                maxY = Math.max(maxY, y);
                            }
                        }
                    }
                    assertTrue((maxX - minX + 1) < width * 0.9f
                                    && (maxY - minY + 1) < height * 0.9f,
                            "the changed region must stay inside the projected body bounds: "
                                    + (maxX - minX + 1) + "x" + (maxY - minY + 1)
                                    + "; " + diagnostic);

                    // The outer two-pixel border is outside the projected body; the
                    // pass copies the source there and must leave it byte-identical.
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            if (x >= 2 && x < width - 2 && y >= 2 && y < height - 2) continue;
                            int index = (y * width + x) * 4;
                            for (int component = 0; component < 4; component++) {
                                assertEquals(Float.floatToIntBits(fog[index + component]),
                                        Float.floatToIntBits(graphColour[index + component]),
                                        "border pixel (" + x + "," + y + ") changed; "
                                                + diagnostic);
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
                    int width = pipeline.graph().width();
                    int height = pipeline.graph().height();
                    float[] depth = readDepth(depthTexture[0], width, height);
                    for (int index = 0; index < depth.length; index++) {
                        assertEquals(0.5f, depth[index], 1.0e-5f,
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
        // Mirrors the proven pass-level fixture: a large, bright body in front of
        // the camera so the in-scatter is unambiguous at 32x32.
        return new VolumetricParticleSettings(true, 1.5f, 0.2f, 0.8f,
                new Vector3f(0.0f, 0.0f, -3.0f), new Vector3f(1.0f, 1.0f, 1.0f),
                48, 0.05f, new Vector3f(0.3f, -1.0f, 0.2f),
                new Vector3f(3.5f, 3.2f, 3.0f),
                0.2f, VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("Volumetric particle wiring").build();
    }

    private static void addDepthProbe(RenderPipeline pipeline, int[] depthTexture) {
        var depth = pipeline.activeGenerationForTest().topology().sceneSurface()
                .single(SceneBufferChannel.DEPTH);
        if (depth == null) throw new IllegalStateException("fixture has no shared depth");
        pipeline.graph().addPass("ParticleDepthProbe")
                .computeOnly()
                .dependsOn(pipeline.finalPassName())
                .execute((resources, commands) ->
                        depthTexture[0] = resources.depthAttachment(depth.name()));
    }

    private static void seedHostDepth(OwnedPresentationTarget host, float value) {
        float previousClearDepth = org.lwjgl.opengl.GL11.glGetFloat(
                org.lwjgl.opengl.GL11.GL_DEPTH_CLEAR_VALUE);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, host.target().drawFramebufferId());
            glClearDepth(value);
            glClear(GL_DEPTH_BUFFER_BIT);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            // glClearDepth is process/context state; a leaked 0.5 would make later
            // cases clear their shared depth in front of the particle body.
            glClearDepth(previousClearDepth);
        }
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

    private static float[] readColorTexture(int texture, int width, int height) {
        int framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height,
                    org.lwjgl.opengl.GL11.GL_RGBA, GL_FLOAT, buffer);
            float[] result = new float[width * height * 4];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }
    }

    private static float[] readDepth(int texture, int width, int height) {
        int framebuffer = org.lwjgl.opengl.GL30.glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height);
        try {
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(GL_FRAMEBUFFER,
                    org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
            org.lwjgl.opengl.GL11.glDrawBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL11.GL_NONE);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height,
                    org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, GL_FLOAT, buffer);
            float[] result = new float[width * height];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            org.lwjgl.opengl.GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
        }
    }

    private static int countDifferences(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("arrays must have the same length");
        }
        int changed = 0;
        for (int index = 0; index < left.length; index += 4) {
            if (!samePixel(left, right, index)) {
                changed++;
            }
        }
        return changed;
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

    private static String value(float[] pixels, int index) {
        return "(" + pixels[index] + "," + pixels[index + 1] + ","
                + pixels[index + 2] + "," + pixels[index + 3] + ")";
    }

    private static void glBindFramebuffer(int target, int framebuffer) {
        org.lwjgl.opengl.GL30.glBindFramebuffer(target, framebuffer);
    }
}
