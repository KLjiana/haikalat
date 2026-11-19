package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Pixel-level transparent-collector boundary for the production GPU-particle
 * VFX stage.
 *
 * <p>The earlier acceptance only compared two manually ordered draws or an
 * always-true pass-list expression; it never rendered the real
 * {@link RenderPipeline} stage against a weighted-OIT collector. This class
 * renders the production chain on a 45-degree camera and measures the linear
 * HDR composite:</p>
 *
 * <ul>
 *   <li>an OIT surface with full coverage and an additive particle behind it:
 *       the resolved pixel must not gain the particle contribution;</li>
 *   <li>an OIT surface with half coverage and a particle in front of it: the
 *       resolved pixel gains exactly the un-occluded half of the particle
 *       contribution, which is the documented "collector composites over the
 *       particle" order;</li>
 *   <li>a particle in front of the surface plane but outside its screen
 *       coverage: the resolved pixel gains the full additive contribution.</li>
 * </ul>
 *
 * <p>The expected particle contribution is measured from a collector-free
 * pipeline (background and particle-only frames), so the oracle does not call
 * the particle shader or the OIT math used by the assertion target. If the
 * particle stage ran after the OIT resolve, the behind pixel would gain the full
 * contribution and the half-coverage pixel would miss the 0.5 factor; the test
 * failed exactly that way before the stage was moved in front of the
 * collector.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GpuParticleVfxBoundaryGlTest {
    private static final float FOV_Y_RADIANS = (float) Math.PI / 2.0f;
    private static final int BEHIND_EMISSION = 24;
    private static final int FRONT_EMISSION = 64;
    private static final float PARTICLE_SIZE = 0.22f;
    private static final float TOLERANCE = 2.0e-2f;
    private static final float MINIMUM_PARTICLE_DELTA = 0.2f;

    @Test
    void oitResolveCompositesOverTheParticleStageRecordedBeforeIt() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            while (glGetError() != GL_NO_ERROR) {
                // Drain context-creation warnings before the acceptance frame.
            }
            // A decorated window cannot be narrower than the caption buttons on
            // Windows, so every frame size below comes from the real window.
            int width = window.width();
            int height = window.height();
            assertTrue(width > 0 && height > 0);
            float aspect = width / (float) height;
            Scene scene = new Scene(camera(aspect));
            try (var tracking = GlDebug.acquireResourceTracking()) {
                int baseline = GlDebug.resources().liveResources().size();
                GlRenderDevice device = new GlRenderDevice();
                Recorder recorder = new Recorder(width, height, aspect);
                RenderPipeline withOit = pipeline(window, scene, recorder, aspect, true);
                RenderPipeline withoutOit = pipeline(window, scene, recorder, aspect, false);
                try {
                    withOit.build();
                    withoutOit.build();
                    recorder.graph = withOit.graph();

                    List<String> order = withOit.graph().description().executionOrder();
                    assertTrue(order.indexOf(PostProcessTargets.VFX_COMPOSITE_PASS) >= 0,
                            "the particle stage must be present: " + order);
                    assertTrue(order.indexOf(PostProcessTargets.VFX_COMPOSITE_PASS)
                                    < order.indexOf(WeightedOitPass.PASS_NAME),
                            "the particle stage must execute before the OIT resolve: " + order);

                    float[] background = capture(withoutOit, device, recorder, Mode.NONE);
                    float[] surfaceOnly = capture(withOit, device, recorder, Mode.NONE);
                    float[] behindOnly = capture(withoutOit, device, recorder, Mode.BEHIND);
                    float[] frontOnly = capture(withoutOit, device, recorder, Mode.FRONT);
                    float[] behindComposite = capture(withOit, device, recorder, Mode.BEHIND);
                    float[] frontComposite = capture(withOit, device, recorder, Mode.FRONT);

                    assertEquals(List.of(PostProcessTargets.VFX_COMPOSITE_PASS),
                            recorder.observerPasses,
                            "the recorder must run inside the VFX composite pass: "
                                    + recorder.observerPasses);

                    int behindPixel = maxDelta(behindOnly, background, width, height,
                            column -> column >= 0.05f * width && column <= 0.30f * width);
                    float behindContribution = delta(behindOnly, background, behindPixel);
                    assertTrue(behindContribution > MINIMUM_PARTICLE_DELTA,
                            "fixture: the behind particle must be visible without the collector: "
                                    + behindContribution + " at " + pixel(behindPixel, width));
                    assertEquals(0.0f, delta(behindComposite, surfaceOnly, behindPixel),
                            TOLERANCE,
                            "a full-coverage collector must hide the particle behind it; pixel="
                                    + pixel(behindPixel, width) + " composite="
                                    + rgba(behindComposite, behindPixel) + " surface="
                                    + rgba(surfaceOnly, behindPixel));

                    int halfPixel = maxDelta(frontOnly, background, width, height,
                            column -> column >= 0.68f * width && column <= 0.95f * width);
                    float halfContribution = delta(frontOnly, background, halfPixel);
                    assertTrue(halfContribution > MINIMUM_PARTICLE_DELTA,
                            "fixture: the front particle must be visible over the right surface: "
                                    + halfContribution + " at " + pixel(halfPixel, width));
                    assertEquals(0.5f * halfContribution,
                            delta(frontComposite, surfaceOnly, halfPixel), TOLERANCE,
                            "the 0.5-coverage collector must composite over the particle, keeping "
                                    + "exactly (1 - alpha) of it; pixel=" + pixel(halfPixel, width)
                                    + " particle=" + halfContribution + " composite="
                                    + rgba(frontComposite, halfPixel) + " surface="
                                    + rgba(surfaceOnly, halfPixel));

                    int openPixel = maxDelta(frontOnly, background, width, height,
                            column -> column >= 0.42f * width && column <= 0.58f * width);
                    float openContribution = delta(frontOnly, background, openPixel);
                    assertTrue(openContribution > MINIMUM_PARTICLE_DELTA,
                            "fixture: the uncovered front particle must be visible: "
                                    + openContribution + " at " + pixel(openPixel, width));
                    assertEquals(frontOnly[openPixel * 4], frontComposite[openPixel * 4],
                            TOLERANCE,
                            "a pixel outside every selected surface must be unchanged by the "
                                    + "collector; pixel=" + pixel(openPixel, width));
                    assertEquals(openContribution, delta(frontComposite, background, openPixel),
                            TOLERANCE,
                            "the uncovered particle must keep its full additive contribution");

                    assertEquals(GL_NO_ERROR, glGetError());
                    System.out.println(String.format(Locale.ROOT,
                            "GPU particle/OIT boundary: behindContribution=%.4f "
                                    + "behindCompositeDelta=%.4f halfContribution=%.4f "
                                    + "halfCompositeDelta=%.4f openContribution=%.4f "
                                    + "openCompositeDelta=%.4f",
                            behindContribution, delta(behindComposite, surfaceOnly, behindPixel),
                            halfContribution, delta(frontComposite, surfaceOnly, halfPixel),
                            openContribution, delta(frontComposite, background, openPixel)));
                } finally {
                    withOit.close();
                    withoutOit.close();
                    recorder.close();
                }
                assertEquals(baseline, GlDebug.resources().liveResources().size(),
                        "the two pipelines, particle owners and renderers must release every "
                                + "tracked resource");
                GlDebug.assertNoError("GPU particle/OIT boundary");
            }
        }
    }

    private static RenderPipeline pipeline(GlfwWindow window, Scene scene, Recorder recorder,
                                           float aspect, boolean weightedOit) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build())
                .hdrVfxWithCamera(recorder);
        if (weightedOit) {
            pipeline.weightedOit(WeightedOitSettings.defaults());
            pipeline.weightedOitSurfaces(List.of(
                    fullSurface(aspect), halfSurface(aspect)));
        }
        return pipeline;
    }

    private static float[] capture(RenderPipeline pipeline, GlRenderDevice device, Recorder recorder,
                                   Mode mode) {
        recorder.mode = mode;
        recorder.graph = pipeline.graph();
        recorder.observerPasses.clear();
        pipeline.execute(device, 1.0f / 60.0f);
        return pipeline.captureLinearHdrRgbaFloat();
    }

    private static float delta(float[] withParticles, float[] reference, int pixel) {
        int offset = pixel * 4;
        return (withParticles[offset] - reference[offset])
                + (withParticles[offset + 1] - reference[offset + 1])
                + (withParticles[offset + 2] - reference[offset + 2]);
    }

    private static int maxDelta(float[] withParticles, float[] reference, int width, int height,
                                java.util.function.IntPredicate columnFilter) {
        int best = -1;
        float bestValue = -Float.MAX_VALUE;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!columnFilter.test(x)) continue;
                int pixel = y * width + x;
                float value = delta(withParticles, reference, pixel);
                if (value > bestValue) {
                    bestValue = value;
                    best = pixel;
                }
            }
        }
        return best;
    }

    private static String pixel(int pixel, int width) {
        return (pixel % width) + "," + (pixel / width);
    }

    private static String rgba(float[] pixels, int pixel) {
        return String.format(Locale.ROOT, "(%.4f, %.4f, %.4f, %.4f)",
                pixels[pixel * 4], pixels[pixel * 4 + 1], pixels[pixel * 4 + 2],
                pixels[pixel * 4 + 3]);
    }

    /**
     * Left third of the screen at view depth two: fully covered, green.
     *
     * <p>NDC x maps to world x through {@code depth * tan(fovY / 2) * aspect},
     * so the fixture keeps the same screen coverage on any real window width.</p>
     */
    private static OitSurface fullSurface(float aspect) {
        float halfWidth = 2.0f * aspect;
        return OitSurface.of(
                new Vector3f(-0.625f * halfWidth, 0.0f, -2.0f),
                new Vector3f(0.375f * halfWidth, 0.0f, 0.0f),
                new Vector3f(0.0f, 2.0f, 0.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), 1.0f);
    }

    /** Right third at view depth two: half covered, blue. */
    private static OitSurface halfSurface(float aspect) {
        float halfWidth = 2.0f * aspect;
        return OitSurface.of(
                new Vector3f(0.625f * halfWidth, 0.0f, -2.0f),
                new Vector3f(0.375f * halfWidth, 0.0f, 0.0f),
                new Vector3f(0.0f, 2.0f, 0.0f),
                new Vector3f(0.0f, 0.0f, 1.0f), 0.5f);
    }

    private static ExternalCamera camera(float aspect) {
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective(FOV_Y_RADIANS, aspect,
                0.125f, 64.0f);
        return new ExternalCamera(view, projection, new Matrix4f(projection).mul(view),
                new Vector3f(), 0.0f, 0.125f, 64.0f, 1L);
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(64, 64).visible(false)
                .title("GPU particle VFX transparent boundary").build();
    }

    private enum Mode { NONE, BEHIND, FRONT }

    /**
     * Records the production consumer stage into the current VFX composite
     * target and captures the pass that is executing, so the order assertion is
     * backed by an observed pass name rather than the source layout alone.
     */
    private static final class Recorder implements RenderPipeline.CameraPassExecutor {
        private final int width;
        private final int height;
        private final float aspect;
        private final VfxRenderer behindRenderer = new VfxRenderer();
        private final VfxRenderer frontRenderer = new VfxRenderer();
        private final GpuParticleSystem behind;
        private final GpuParticleSystem front;
        private final List<String> observerPasses = new ArrayList<>();
        private Mode mode = Mode.NONE;
        private RenderGraph graph;

        private Recorder(int width, int height, float aspect) {
            this.width = width;
            this.height = height;
            this.aspect = aspect;
            this.behind = new GpuParticleSystem(behindSettings(aspect));
            this.front = new GpuParticleSystem(frontSettings(aspect));
            behindRenderer.gpuParticleSettings(new GpuParticleVfx.Settings(
                    1.0f / 60.0f, BEHIND_EMISSION, GpuParticleVfx.SortingMode.NONE));
            frontRenderer.gpuParticleSettings(new GpuParticleVfx.Settings(
                    1.0f / 60.0f, FRONT_EMISSION, GpuParticleVfx.SortingMode.NONE));
        }

        @Override
        public void execute(PassResources resources, CommandBuffer commands,
                            ExternalCamera camera) {
            observerPasses.add(String.valueOf(resources.recordingPass(graph)));
            int depth = resources.depthAttachment(PostProcessTargets.VFX_SCENE_DEPTH);
            assertTrue(depth > 0, "the VFX composite must expose the opaque scene depth");
            if (mode == Mode.BEHIND) {
                behindRenderer.gpuParticleVfx().replay();
                behindRenderer.recordGpuParticles(commands, behind, behindSettings(aspect),
                        camera.projection(), camera.view(), depth, width, height);
            } else if (mode == Mode.FRONT) {
                frontRenderer.gpuParticleVfx().replay();
                frontRenderer.recordGpuParticles(commands, front, frontSettings(aspect),
                        camera.projection(), camera.view(), depth, width, height);
            }
        }

        private void close() {
            behind.close();
            front.close();
            behindRenderer.close();
            frontRenderer.close();
        }
    }

    /** Behind the left surface (view depth three), covered by it on screen. */
    private static GpuParticleSettings behindSettings(float aspect) {
        return new GpuParticleSettings(BEHIND_EMISSION,
                new Vector3f(-2.7f * aspect, -1.5f, -3.15f),
                new Vector3f(-1.2f * aspect, 1.5f, -2.85f),
                10.0f, 0.0f, 0.0f, 0.0f, PARTICLE_SIZE,
                new Vector3f(1.0f, 0.0f, 0.0f), 1.0f);
    }

    /** In front of both surfaces (view depth one), spanning gap and right third. */
    private static GpuParticleSettings frontSettings(float aspect) {
        return new GpuParticleSettings(FRONT_EMISSION,
                new Vector3f(-0.15f * aspect, -0.5f, -1.15f),
                new Vector3f(0.9f * aspect, 0.5f, -0.85f),
                10.0f, 0.0f, 0.0f, 0.0f, PARTICLE_SIZE,
                new Vector3f(1.0f, 0.0f, 0.0f), 1.0f);
    }
}
