package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureCube;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;
import static org.lwjgl.opengl.GL45.glTextureSubImage3D;

/**
 * Real-GL acceptance for #39, the bounded screen-space indirect diffuse.
 *
 * <p>Fixtures are analytic synthetic surfaces: a receiver hole inside a
 * surrounding layer whose depth, normal, roughness and albedo are controlled
 * per case. The expected pixels come from the independent double reference on
 * the same fixture, not from a previous GPU render, so a wrong march/fallback
 * cannot bless itself. Cases discriminate the bounded budget, the offscreen
 * ambient fallback, the sky/IBL negative control, the missing direct re-add,
 * depth/normal/roughness rejection, the disabled baseline and the resize/close
 * transaction.</p>
 *
 * <p>This class is guarded by {@code -Dhaikalat.glSmoke=true}; a plain
 * {@code test} run skips it, so it is not evidence until the serialized GPU
 * queue executes it.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SsgiPassGlTest {
    private static final int SIZE = 48;
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(55.0);
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(FIELD_OF_VIEW,
            1.0f, 0.1f, 100.0f);
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();
    private static final Matrix4d REFERENCE_PROJECTION = new Matrix4d(PROJECTION);
    private static final Matrix4d REFERENCE_INVERSE_PROJECTION =
            new Matrix4d(INVERSE_PROJECTION);
    private static final SsgiIndirectReference.Camera CAMERA =
            new SsgiIndirectReference.Camera(REFERENCE_INVERSE_PROJECTION,
                    REFERENCE_PROJECTION, new Matrix4d());
    private static final float TOLERANCE = 0.02f;

    @Test
    void boundedIndirectScalesWithAlbedoAndStaysFinite() {
        try (GlfwWindow window = window("SSGI bounded")) {
            GlRenderDevice device = new GlRenderDevice();
            SsgiSettings settings = oneBounce().build();
            Fixture halfAlbedo = Fixture.surround(-5.0f, 2.0f, 1.0f, true)
                    .receiver(18, 24, -6.0f, 0.5f).build();
            Fixture fullAlbedo = halfAlbedo.withReceiverAlbedo(1.0f);
            float[] half = render(device, halfAlbedo, settings, null);
            float[] full = render(device, fullAlbedo, settings, null);
            assertFinite(half);
            assertFinite(full);
            int probe = pixel(18, 24);
            double[] expectedHalf = reference(halfAlbedo, settings, null, 18, 24);
            assertMatchesReference(half, probe, expectedHalf, "half-albedo receiver");
            assertTrue(half[probe * 4] > halfAlbedo.base(18, 24)[0],
                    "a bright visible neighbour must add one indirect bounce");
            float halfAdded = half[probe * 4] - (float) halfAlbedo.base(18, 24)[0];
            float fullAdded = full[probe * 4] - (float) halfAlbedo.base(18, 24)[0];
            assertEquals(2.0f, fullAdded / halfAdded, 0.02f,
                    "the added bounce must be linear in receiver albedo");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void uniformAmbientEnvironmentIsAnExactNoOpOnBackgroundPixels() {
        try (GlfwWindow window = window("SSGI ambient no-op")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.backgroundOnly().build();
            SsgiSettings settings = SsgiSettings.defaults();
            float[] output = render(device, fixture, settings,
                    new float[]{0.6f, 0.4f, 0.2f});
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(fixture.colour()[pixel * 4 + channel],
                            output[pixel * 4 + channel], 0.0f,
                            "a background pixel must keep the exact base colour: the pass "
                                    + "must not add the IBL ambient a second time");
                }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void inRasterSkyContributesNothingToTheReceiver() {
        try (GlfwWindow window = window("SSGI sky control")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.backgroundOnly()
                    .receiver(SIZE / 2, SIZE / 2, -6.0f, 0.8f).build();
            SsgiSettings noOffscreen = SsgiSettings.defaults()
                    .withMaximumOffscreenFraction(0.0f);
            float[] withEnvironment = render(device, fixture, noOffscreen,
                    new float[]{4.0f, 4.0f, 4.0f});
            float[] withFallback = render(device, fixture, noOffscreen, null);
            int probe = pixel(SIZE / 2, SIZE / 2);
            assertEquals(fixture.colour()[probe * 4], withEnvironment[probe * 4], 0.0f,
                    "a bright sky/IBL environment must not brighten a receiver that only "
                            + "sees background; the forward IBL term already carries it");
            assertEquals(fixture.colour()[probe * 4], withFallback[probe * 4], 0.0f);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void offscreenRaysUseTheAmbientFallbackInsteadOfBlack() {
        try (GlfwWindow window = window("SSGI offscreen fallback")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.backgroundOnly().receiver(1, 1, -6.0f, 0.5f).build();
            SsgiSettings base = SsgiSettings.builder().enabled(true).sampleCount(8)
                    .stepCount(8).maxDistanceMetres(8.0f).thicknessMetres(1.0f)
                    .strength(1.0f).maximumOffscreenFraction(1.0f).edgeFadePixels(0.0f)
                    .ambientFallback(0.4f, 0.4f, 0.4f).build();
            float[] zeroBudget = render(device, fixture,
                    base.withMaximumOffscreenFraction(0.0f), null);
            float[] fullBudget = render(device, fixture, base, null);
            int probe = pixel(1, 1);
            float baseValue = fixture.colour()[probe * 4];
            assertEquals(baseValue, zeroBudget[probe * 4], 1.0e-6f,
                    "a zero offscreen budget must add nothing");
            assertTrue(fullBudget[probe * 4] > baseValue + 1.0e-4f,
                    "offscreen rays must contribute the ambient approximation, not black");
            assertMatchesReference(fullBudget, probe,
                    reference(fixture, base, null, 1, 1), "offscreen fallback");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void largerBudgetsConvergeTowardTheIndependentReference() {
        try (GlfwWindow window = window("SSGI budget")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.surround(-5.0f, 2.0f, 1.0f, true)
                    .receiver(18, 24, -6.0f, 0.5f).build();
            SsgiSettings one = oneBounce().sampleCount(1).stepCount(8).build();
            SsgiSettings eight = oneBounce().sampleCount(8).stepCount(8).build();
            SsgiSettings sixteen = oneBounce().sampleCount(16).stepCount(8).build();
            float[] oneOutput = render(device, fixture, one, null);
            float[] eightOutput = render(device, fixture, eight, null);
            int probe = pixel(18, 24);
            double[] oneReference = reference(fixture, one, null, 18, 24);
            double[] eightReference = reference(fixture, eight, null, 18, 24);
            double[] sixteenReference = reference(fixture, sixteen, null, 18, 24);
            assertMatchesReference(oneOutput, probe, oneReference, "one-sample budget");
            assertMatchesReference(eightOutput, probe, eightReference, "eight-sample budget");
            double oneError = Math.abs(oneOutput[probe * 4] - sixteenReference[0]);
            double eightError = Math.abs(eightOutput[probe * 4] - sixteenReference[0]);
            assertTrue(oneError >= eightError - 0.01,
                    "a larger sample budget must not move away from the converged value: "
                            + "one=" + oneError + " eight=" + eightError);
            assertTrue(eightOutput[probe * 4] >= fixture.colour()[probe * 4],
                    "the pass must never subtract energy");
            assertTrue(eightOutput[probe * 4] - fixture.colour()[probe * 4]
                            <= 2.0f * 0.5f + TOLERANCE,
                    "the physical single-bounce bound must hold");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void depthNormalAndRoughnessGatesRejectBrightSamples() {
        try (GlfwWindow window = window("SSGI gates")) {
            GlRenderDevice device = new GlRenderDevice();
            SsgiSettings settings = oneBounce().build();
            int probe = pixel(18, 24);
            float[] darkFar = render(device, Fixture.surround(-2.0f, 0.02f, 1.0f, true)
                    .receiver(18, 24, -6.0f, 0.5f).build(), settings, null);
            float[] brightFar = render(device, Fixture.surround(-2.0f, 2.0f, 1.0f, true)
                    .receiver(18, 24, -6.0f, 0.5f).build(), settings, null);
            assertEquals(darkFar[probe * 4], brightFar[probe * 4], 1.0e-6f,
                    "a surface separated by more than the thickness window must be rejected");
            float[] darkFlipped = render(device, Fixture.surround(-5.0f, 0.02f, 1.0f, false)
                    .receiver(18, 24, -6.0f, 0.5f).build(), settings, null);
            float[] brightFlipped = render(device, Fixture.surround(-5.0f, 2.0f, 1.0f, false)
                    .receiver(18, 24, -6.0f, 0.5f).build(), settings, null);
            assertEquals(darkFlipped[probe * 4], brightFlipped[probe * 4], 1.0e-6f,
                    "a hit whose normal faces away from the ray must be rejected");
            float[] mirror = render(device, Fixture.surround(-5.0f, 2.0f, 0.0f, true)
                    .receiver(18, 24, -6.0f, 0.5f).build(), settings, null);
            assertEquals(darkFlipped[probe * 4], mirror[probe * 4], 1.0e-6f,
                    "a mirror-like hit must not leak specular radiance into diffuse GI");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void aDirectLitReceiverIsNeverReAdded() {
        try (GlfwWindow window = window("SSGI direct control")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.backgroundOnly()
                    .receiver(20, 20, -4.0f, 0.9f).build();
            fixture.setColour(20, 20, 10.0f, 6.0f, 3.0f);
            SsgiSettings settings = SsgiSettings.defaults()
                    .withMaximumOffscreenFraction(0.0f)
                    .withAmbientFallback(0.0f, 0.0f, 0.0f);
            float[] output = render(device, fixture, settings, null);
            int probe = pixel(20, 20);
            assertEquals(fixture.colour()[probe * 4], output[probe * 4], 0.0f,
                    "the receiver's own direct colour must never be added again");
            assertEquals(fixture.colour()[probe * 4 + 1], output[probe * 4 + 1], 0.0f);
            assertEquals(fixture.colour()[probe * 4 + 2], output[probe * 4 + 2], 0.0f);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void halfResolutionResolveInterpolatesWithinTheSurfaceAndRejectsEdges() {
        try (GlfwWindow window = window("SSGI half-resolution edges")) {
            GlRenderDevice device = new GlRenderDevice();
            SsgiSettings settings = oneBounce().halfResolution(true).build();
            int reconstructedPixel = pixel(19, 24);
            Fixture sharedSurface = Fixture.surround(-5f, 2f, 1f, true)
                    .receiver(18, 24, -6f, 0.5f).build()
                    .receiver(19, 24, -6f, 0.5f).build();
            float[] onSurface = render(device, sharedSurface, settings, null);
            float base = sharedSurface.colour()[reconstructedPixel * 4];
            assertTrue(onSurface[reconstructedPixel * 4] > base + 0.05f,
                    "a full-resolution pixel between matching taps should receive the bounce");

            Fixture depthEdge = sharedSurface.copy();
            depthEdge.depth()[reconstructedPixel] = deviceDepth(-8f);
            float[] acrossDepth = render(device, depthEdge, settings, null);
            assertEquals(base, acrossDepth[reconstructedPixel * 4], 0f);

            Fixture normalEdge = sharedSurface.copy();
            normalEdge.normal()[reconstructedPixel * 4] = 1f;
            normalEdge.normal()[reconstructedPixel * 4 + 1] = 1f;
            normalEdge.depth()[pixel(20, 24)] = 1f;
            float[] acrossNormal = render(device, normalEdge, settings, null);
            assertEquals(base, acrossNormal[reconstructedPixel * 4], 0f);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void halfResolutionFilterSmoothsASlopedReceiverWithoutCrossingADepthStep() {
        try (GlfwWindow window = window("SSGI sloped receiver")) {
            GlRenderDevice device = new GlRenderDevice();
            int traceSize = SIZE / 2;
            float[] signal = new float[traceSize * traceSize * 4];
            for (int y = 0; y < traceSize; y++) {
                for (int x = 0; x < traceSize; x++) {
                    int pixel = (y * traceSize + x) * 4;
                    signal[pixel] = x == 4 ? 1f : 0f;
                    signal[pixel + 3] = 2f + 0.016f * x + (x >= 16 ? 0.7f : 0f);
                }
            }
            // A zero signed octahedral coordinate is the same +Z normal across both planes.
            try (Texture2D trace = floatTexture(traceSize, 4, signal);
                 Texture2D normal = floatTexture(4, new float[SIZE * SIZE * 4]);
                 Framebuffer filtered = Framebuffer.mrt(traceSize, traceSize, GL_RGBA32F);
                 SsgiPass pass = new SsgiPass(SsgiSettings.defaults().withHalfResolution(true))) {
                filtered.bind();
                glViewport(0, 0, traceSize, traceSize);
                device.execute(pass.recordHalfResolutionFilter(device.createCommandBuffer(),
                        trace.id(), normal.id(), true));
                FloatBuffer rgba = BufferUtils.createFloatBuffer(4);
                glReadPixels(12, 12, 1, 1, GL_RGBA, GL_FLOAT, rgba);
                assertTrue(rgba.get(0) > 0.02f,
                        "a bright sample eight taps away on the same slope should smooth into this receiver");
                assertEquals(2f + 12 * 0.016f, rgba.get(3), 1.0e-6f,
                        "a filter must keep the centre's geometry depth");
                glReadPixels(18, 12, 1, 1, GL_RGBA, GL_FLOAT, rgba);
                assertEquals(0f, rgba.get(0), 0f, "a depth step must stop the colour from bleeding");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void pipelineInsertsOnePassAndDisabledResizeCloseStayClean() {
        try (GlfwWindow window = window("SSGI pipeline")) {
            RenderSettings hdr = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .vsync(false)
                    .build();
            int baseline = GlDebug.resources().liveResources().size();

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults(), SIZE, SIZE, null, NO_SURFACE, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                assertFalse(graph.hasPass(PostProcessTargets.SSGI_PASS));
                assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                                ToneMappingMode.ACES), graph.description().executionOrder(),
                        "a disabled SSGI request must keep the exact baseline colour chain");
            }

            SsgiSettings settings = SsgiSettings.defaults();
            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults().withSsgi(settings), SIZE, SIZE, null,
                    SSGI_SURFACE, null, null, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                pipeline.validateSurfaceInputs();
                assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                                PostProcessTargets.SSGI_PASS,
                                PostProcessTargets.TONE_MAPPING_PASS,
                                PostProcessTargets.PRESENT_PASS),
                        graph.description().executionOrder(),
                        "SSGI must run on the linear HDR colour before tone mapping");
                assertEquals(PostProcessTargets.SSGI_COLOR, pipeline.linearHdrTextureName());
                assertTrue(graph.description().passes().stream()
                                .filter(pass -> pass.name().equals(PostProcessTargets.SSGI_PASS))
                                .findFirst().orElseThrow()
                                .directDependencies().contains(PostProcessTargets.GEOMETRY_PASS),
                        "the SSGI pass must depend on its surface producer");

                pipeline.resize(SIZE + 16, SIZE + 16);
                pipeline.prepareResize(SIZE + 16, SIZE + 16).close();
            }

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults().withSsgi(settings.withHalfResolution(true)),
                    49, 51, null, SSGI_SURFACE, null, null, null, null);
                 RenderGraph graph = new RenderGraph(49, 51)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear().execute((resources, commands) -> {});
                pipeline.addFinalPass(graph);
                var halfSizePasses = graph.description().passes().stream()
                        .filter(pass -> pass.width() == 25 && pass.height() == 26).toList();
                assertEquals(3, halfSizePasses.size(), "trace and both filters round odd extents up");
                var composite = graph.description().passes().stream()
                        .filter(pass -> pass.name().equals(PostProcessTargets.SSGI_PASS))
                        .findFirst().orElseThrow();
                assertEquals(49, composite.width());
                assertEquals(51, composite.height());
                assertEquals(PostProcessTargets.SSGI_COLOR, pipeline.linearHdrTextureName());
            }

            assertThrows(IllegalArgumentException.class,
                    () -> PostProcessPipeline.create(RenderSettings.builder()
                                    .antiAliasingMode(AntiAliasingMode.NONE)
                                    .toneMappingMode(ToneMappingMode.NONE)
                                    .vsync(false).build(),
                            PostProcessSettings.defaults().withSsgi(settings),
                            SIZE, SIZE, null, SSGI_SURFACE, null, null, null, null),
                    "SSGI requires HDR tone mapping");

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults().withSsgi(settings), SIZE, SIZE, null,
                    NO_SURFACE, null, null, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                assertThrows(IllegalStateException.class, () -> pipeline.addFinalPass(graph),
                        "a missing ALBEDO surface input must be rejected");
            }

            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "pipeline close must release the SSGI shader and quad");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static GlfwWindow window(String title) {
        GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE).title(title)
                .visible(false).build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        return window;
    }

    private static SsgiSettings.Builder oneBounce() {
        return SsgiSettings.builder().enabled(true).sampleCount(8).stepCount(8)
                .maxDistanceMetres(8.0f).thicknessMetres(1.0f).strength(1.0f)
                .maximumOffscreenFraction(0.0f).edgeFadePixels(0.0f)
                .ambientFallback(0.0f, 0.0f, 0.0f);
    }

    private static void assertMatchesReference(float[] output, int pixel, double[] expected,
                                               String label) {
        for (int channel = 0; channel < 3; channel++) {
            assertEquals((float) expected[channel], output[pixel * 4 + channel], TOLERANCE,
                    label + " pixel must match the independent double reference (channel "
                            + channel + ")");
        }
    }

    private static double[] reference(Fixture fixture, SsgiSettings settings,
                                      float[] uniformIrradiance, int x, int y) {
        SsgiIndirectReference.Environment referenceEnvironment = uniformIrradiance == null
                ? SsgiIndirectReference.Environment.unavailable()
                : SsgiIndirectReference.Environment.uniform(uniformIrradiance[0],
                        uniformIrradiance[1], uniformIrradiance[2]);
        return SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA, settings,
                referenceEnvironment, x, y);
    }

    private static float[] render(GlRenderDevice device, Fixture fixture, SsgiSettings settings,
                                  float[] uniformIrradiance) {
        try (Texture2D colour = floatTexture(4, fixture.colour());
             Texture2D depth = floatTexture(1, fixture.depth());
             Texture2D normal = floatTexture(4, fixture.normal());
             Texture2D roughness = floatTexture(1, fixture.roughness());
             Texture2D albedo = floatTexture(4, fixture.albedo());
             TextureCube cube = uniformIrradiance == null ? null
                     : uniformCube(uniformIrradiance[0], uniformIrradiance[1],
                             uniformIrradiance[2]);
             Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F);
             Framebuffer trace = settings.halfResolution() ? Framebuffer.mrt(SIZE / 2, SIZE / 2,
                     GL_RGBA16F) : null;
             Framebuffer filtered = settings.halfResolution() ? Framebuffer.mrt(SIZE / 2, SIZE / 2,
                     GL_RGBA16F) : null;
             SsgiPass pass = new SsgiPass(settings)) {
            SsgiEnvironmentSource environment = cube == null ? null
                    : new SsgiEnvironmentSource() {
                        @Override
                        public boolean bindIrradiance(CommandBuffer cmd, int unit) {
                            cmd.bindTextureCube(unit, cube);
                            return true;
                        }

                        @Override
                        public float rotationRadians() {
                            return 0.0f;
                        }

                        @Override
                        public float intensity() {
                            return 1.0f;
                        }
                    };
            Framebuffer spatial = trace == null ? target : trace;
            spatial.bind();
            glViewport(0, 0, spatial.width(), spatial.height());
            CommandBuffer commands = device.createCommandBuffer();
            pass.recordIntoCurrentTarget(commands, colour.id(), depth.id(), normal.id(),
                    roughness.id(), albedo.id(), INVERSE_PROJECTION, PROJECTION,
                    new Matrix4f(), environment, SIZE, SIZE);
            device.execute(commands);
            if (trace != null) {
                filtered.bind();
                device.execute(pass.recordHalfResolutionFilter(device.createCommandBuffer(),
                        trace.colorAttachment(), normal.id(), true));
                trace.bind();
                device.execute(pass.recordHalfResolutionFilter(device.createCommandBuffer(),
                        filtered.colorAttachment(), normal.id(), false));
                target.bind();
                glViewport(0, 0, SIZE, SIZE);
                device.execute(pass.recordHalfResolutionResolve(device.createCommandBuffer(),
                        trace.colorAttachment(), colour.id(), depth.id(), normal.id(), albedo.id(),
                        INVERSE_PROJECTION));
            }
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[SIZE * SIZE * 4];
            pixels.get(result);
            return result;
        }
    }

    private static Texture2D floatTexture(int channels, float[] data) {
        return floatTexture(SIZE, channels, data);
    }

    private static Texture2D floatTexture(int size, int channels, float[] data) {
        int format = channels == 1 ? GL_R32F : GL_RGBA32F;
        Texture2D texture = Texture2D.createEmpty(size, size, format);
        ByteBuffer upload = BufferUtils.createByteBuffer(
                size * size * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, size, size, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static void assertFinite(float[] image) {
        for (float value : image) {
            assertTrue(Float.isFinite(value), "SSGI output must be finite");
        }
    }

    private static int pixel(int x, int y) {
        return y * SIZE + x;
    }

    private static TextureCube uniformCube(float red, float green, float blue) {
        TextureCube cube = TextureCube.create(1, 1, RenderFormat.RGBA16F);
        ByteBuffer data = BufferUtils.createByteBuffer(6 * 4 * Float.BYTES);
        for (int face = 0; face < 6; face++) {
            data.putFloat(red).putFloat(green).putFloat(blue).putFloat(1.0f);
        }
        data.flip();
        glTextureSubImage3D(cube.id(), 0, 0, 0, 0, 1, 1, 6, GL_RGBA, GL_FLOAT, data);
        return cube;
    }

    /** Synthetic fixture: a receiver hole inside a surrounding layer. */
    private static final class Fixture {
        private final float[] colour = new float[SIZE * SIZE * 4];
        private final float[] depth = new float[SIZE * SIZE];
        private final float[] normal = new float[SIZE * SIZE * 4];
        private final float[] roughness = new float[SIZE * SIZE];
        private final float[] albedo = new float[SIZE * SIZE * 4];
        private int receiverX = -1;
        private int receiverY = -1;
        private float receiverDepth = deviceDepth(-6.0f);
        private float receiverAlbedo = 0.5f;
        private float[] receiverBase = {0.02f, 0.02f, 0.02f};

        private Fixture() {
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                depth[pixel] = 1.0f;
                roughness[pixel] = 1.0f;
                albedo[pixel * 4] = 0.5f;
                albedo[pixel * 4 + 1] = 0.5f;
                albedo[pixel * 4 + 2] = 0.5f;
                albedo[pixel * 4 + 3] = 1.0f;
                colour[pixel * 4] = 0.02f;
                colour[pixel * 4 + 1] = 0.02f;
                colour[pixel * 4 + 2] = 0.02f;
                colour[pixel * 4 + 3] = 1.0f;
                normal[pixel * 4 + 3] = 1.0f;
            }
        }

        static Fixture backgroundOnly() {
            return new Fixture();
        }

        static Fixture surround(float viewZ, float colourValue, float roughnessValue,
                                boolean facesReceiver) {
            Fixture fixture = new Fixture();
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                fixture.depth[pixel] = deviceDepth(viewZ);
                fixture.roughness[pixel] = roughnessValue;
                fixture.colour[pixel * 4] = colourValue;
                fixture.colour[pixel * 4 + 1] = colourValue;
                fixture.colour[pixel * 4 + 2] = colourValue;
                float oct = facesReceiver ? 1.0f : 0.0f;
                fixture.normal[pixel * 4] = oct;
                fixture.normal[pixel * 4 + 1] = oct;
            }
            return fixture;
        }

        Fixture receiver(int x, int y, float viewZ, float albedoValue) {
            receiverX = x;
            receiverY = y;
            receiverDepth = deviceDepth(viewZ);
            receiverAlbedo = albedoValue;
            return this;
        }

        Fixture withReceiverAlbedo(float value) {
            Fixture copy = copy();
            copy.receiverAlbedo = value;
            return copy.build();
        }

        Fixture build() {
            if (receiverX >= 0) {
                int pixel = receiverY * SIZE + receiverX;
                depth[pixel] = receiverDepth;
                albedo[pixel * 4] = receiverAlbedo;
                albedo[pixel * 4 + 1] = receiverAlbedo;
                albedo[pixel * 4 + 2] = receiverAlbedo;
                colour[pixel * 4] = receiverBase[0];
                colour[pixel * 4 + 1] = receiverBase[1];
                colour[pixel * 4 + 2] = receiverBase[2];
                normal[pixel * 4] = 0.0f;
                normal[pixel * 4 + 1] = 0.0f;
            }
            return this;
        }

        Fixture copy() {
            Fixture copy = new Fixture();
            System.arraycopy(colour, 0, copy.colour, 0, colour.length);
            System.arraycopy(depth, 0, copy.depth, 0, depth.length);
            System.arraycopy(normal, 0, copy.normal, 0, normal.length);
            System.arraycopy(roughness, 0, copy.roughness, 0, roughness.length);
            System.arraycopy(albedo, 0, copy.albedo, 0, albedo.length);
            copy.receiverX = receiverX;
            copy.receiverY = receiverY;
            copy.receiverDepth = receiverDepth;
            copy.receiverBase = receiverBase;
            return copy;
        }

        void setColour(int x, int y, float red, float green, float blue) {
            int pixel = y * SIZE + x;
            colour[pixel * 4] = red;
            colour[pixel * 4 + 1] = green;
            colour[pixel * 4 + 2] = blue;
        }

        float[] colour() {
            return colour;
        }

        float[] depth() {
            return depth;
        }

        float[] normal() {
            return normal;
        }

        float[] roughness() {
            return roughness;
        }

        float[] albedo() {
            return albedo;
        }

        double[] base(int x, int y) {
            int pixel = y * SIZE + x;
            return new double[]{colour[pixel * 4], colour[pixel * 4 + 1],
                    colour[pixel * 4 + 2]};
        }

        SsgiIndirectReference.Scene scene() {
            double[] referenceColour = new double[SIZE * SIZE * 3];
            double[] referenceNormal = new double[SIZE * SIZE * 2];
            double[] referenceAlbedo = new double[SIZE * SIZE * 3];
            double[] referenceDepth = new double[SIZE * SIZE];
            double[] referenceRoughness = new double[SIZE * SIZE];
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                for (int channel = 0; channel < 3; channel++) {
                    referenceColour[pixel * 3 + channel] = colour[pixel * 4 + channel];
                    referenceAlbedo[pixel * 3 + channel] = albedo[pixel * 4 + channel];
                }
                referenceNormal[pixel * 2] = normal[pixel * 4];
                referenceNormal[pixel * 2 + 1] = normal[pixel * 4 + 1];
                referenceDepth[pixel] = depth[pixel];
                referenceRoughness[pixel] = roughness[pixel];
            }
            return new SsgiIndirectReference.Scene(SIZE, SIZE, referenceColour,
                    referenceDepth, referenceNormal, referenceRoughness, referenceAlbedo);
        }
    }

    private static float deviceDepth(float viewZ) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, viewZ, 1.0f).mul(PROJECTION);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static final SurfaceInputs NO_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return null;
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                           Input input) {
            throw new UnsupportedOperationException();
        }
    };

    private static final SurfaceInputs SSGI_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return switch (channel) {
                case DEPTH -> new Input(channel, PostProcessTargets.SCENE_DEPTH,
                        PostProcessTargets.GEOMETRY_PASS);
                case NORMAL -> new Input(channel, PostProcessTargets.SCENE_NORMAL,
                        PostProcessTargets.GEOMETRY_PASS);
                case ROUGHNESS -> new Input(channel, PostProcessTargets.SCENE_ROUGHNESS,
                        PostProcessTargets.GEOMETRY_PASS);
                case ALBEDO -> new Input(channel, PostProcessTargets.SCENE_ALBEDO,
                        PostProcessTargets.GEOMETRY_PASS);
                default -> null;
            };
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                           Input input) {
            throw new UnsupportedOperationException();
        }
    };
}
