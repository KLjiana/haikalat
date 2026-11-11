package com.kaleblangley.haikalat.subsystems.postprocess.denoise;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalDenoiseSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ssr.SsrPass;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL acceptance for the temporal denoiser bound to the SSR signal.
 *
 * <p>Cases are intentionally independent:</p>
 * <ol>
 *   <li>a deterministic synthetic noise sequence must converge toward its
 *       independently computed 512-frame high-sample reference;</li>
 *   <li>camera and rigid-object motion must reproject correctly; two negative
 *       controls (a zero pixel budget and a zero velocity) show that the
 *       improvement comes from reprojection rather than from the denoiser
 *       simply clamping everything;</li>
 *   <li>a depth disocclusion, a normal flip and a luminance shock must reset
 *       the history instead of smearing the old signal;</li>
 *   <li>a static frame must remain bit-stable and update its history moments;</li>
 *   <li>resize, failed-frame rollback, injected resize failure and double close
 *       must leave no tracked resource and no GL error;</li>
 *   <li>the real {@link SsrPass} output is the noisy signal and the denoised
 *       result converges to a separately rendered mean-radiance reference;</li>
 *   <li>the colour-chain assembly inserts exactly one denoise pass after SSR
 *       and rejects an active denoise request without the required signal.</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TemporalDenoisePassGlTest {
    private static final int SIZE = 32;
    private static final float NOISE_AMPLITUDE = 0.15f;
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(60.0);
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            FIELD_OF_VIEW, 1.0f, 0.1f, 100.0f);
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final float PLANE_VIEW_DEPTH = 2.0f;
    private static final float PLANE_DEVICE_DEPTH = deviceDepth(
            PROJECTION, PLANE_VIEW_DEPTH);

    private static final TemporalDenoiseSettings CONVERGENCE_SETTINGS =
            TemporalDenoiseSettings.builder()
                    .enabled(true)
                    .historyLengthFrames(10)
                    .neighborhoodClampRadius(2)
                    .depthRejectionThreshold(0.02f)
                    .normalRejectionThreshold(0.85f)
                    .reactiveThreshold(1.0f)
                    .maxReprojectionPixels(64.0f)
                    .build();

    private static final TemporalDenoiseSettings RESET_SETTINGS =
            TemporalDenoiseSettings.builder()
                    .enabled(true)
                    .historyLengthFrames(10)
                    .neighborhoodClampRadius(2)
                    .depthRejectionThreshold(0.02f)
                    .normalRejectionThreshold(0.85f)
                    .reactiveThreshold(0.5f)
                    .maxReprojectionPixels(64.0f)
                    .build();

    @Test
    void syntheticNoiseSequenceConvergesToTheHighSampleReference() {
        try (GlfwWindow window = window("Temporal Denoise Synthetic");) {
            int baseline = GlDebug.resources().liveResources().size();
            try (Texture2D noisy = floatTexture(4, SIZE, new float[SIZE * SIZE * 4]);
                 Texture2D depth = floatTexture(1, SIZE, constantDepth(SIZE, PLANE_DEVICE_DEPTH));
                 Texture2D normal = floatTexture(4, SIZE, constantNormal(SIZE, new Vector3f(0, 0, 1)));
                 TemporalDenoisePass pass = new TemporalDenoisePass(CONVERGENCE_SETTINGS, SIZE, SIZE)) {
                setNearest(noisy);
                setNearest(depth);
                setNearest(normal);
                GlRenderDevice device = new GlRenderDevice();
                float[] reference = highSampleReference(512);

                double first = 0.0;
                double atEight = 0.0;
                double atTwenty = 0.0;
                double last = 0.0;
                for (int frame = 0; frame < 40; frame++) {
                    float[] input = noisyFrame(frame);
                    upload(noisy, 4, SIZE, input);
                    device.invalidateState();
                    runFrame(pass, device, noisy, depth, normal, null, null,
                            INVERSE_PROJECTION, IDENTITY, PROJECTION);
                    float[] output = readAttachment(pass.readFramebuffer(), 0, SIZE);
                    double error = rmse(output, reference, SIZE * SIZE);
                    assertTrue(Double.isFinite(error), "NaN/Inf at frame " + frame);
                    if (frame == 0) first = error;
                    if (frame == 8) atEight = error;
                    if (frame == 20) atTwenty = error;
                    if (frame == 39) last = error;
                }
                assertTrue(pass.historyValid(),
                        "history must be committed after the first successful frame");
                assertTrue(first > 0.05, "the first frame must contain the injected noise");
                assertTrue(atEight < first * 0.8,
                        "the first eight frames must visibly reduce the error");
                assertTrue(atTwenty < first * 0.65,
                        "twenty frames must be closer to the high-sample reference");
                assertTrue(last < 0.06,
                        "the converged error must stay below the measured noise floor: " + last);
                assertTrue(last < first * 0.5,
                        "the converged error must be at most half of the first frame: "
                                + last + " vs " + first);
            }
            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "the pass must release its program, quad and history");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void cameraMotionReprojectionTracksWithoutSmearing() {
        try (GlfwWindow window = window("Temporal Denoise Camera Motion");) {
            try (Texture2D noisy = floatTexture(4, SIZE, new float[SIZE * SIZE * 4]);
                 Texture2D depth = floatTexture(1, SIZE, constantDepth(SIZE, PLANE_DEVICE_DEPTH));
                 Texture2D normal = floatTexture(4, SIZE, constantNormal(SIZE, new Vector3f(0, 0, 1)));
                 TemporalDenoisePass tracking = new TemporalDenoisePass(
                         CONVERGENCE_SETTINGS, SIZE, SIZE);
                 TemporalDenoisePass frozen = new TemporalDenoisePass(
                         CONVERGENCE_SETTINGS.withMaxReprojectionPixels(0.0f), SIZE, SIZE)) {
                setNearest(noisy);
                setNearest(depth);
                setNearest(normal);
                GlRenderDevice device = new GlRenderDevice();
                double first = 0.0;
                double trackedFinal = 0.0;
                double frozenFinal = 0.0;
                for (int frame = 0; frame < 32; frame++) {
                    float cameraX = frame * 0.01f;
                    Matrix4f view = new Matrix4f().translate(-cameraX, 0.0f, 0.0f);
                    Matrix4f inverseView = new Matrix4f().translate(cameraX, 0.0f, 0.0f);
                    Matrix4f stableViewProjection = new Matrix4f(PROJECTION).mul(view);
                    float[] truth = movingPattern(inverseView);
                    float[] input = addNoise(truth, frame);
                    upload(noisy, 4, SIZE, input);
                    device.invalidateState();
                    runFrame(tracking, device, noisy, depth, normal, null, null,
                            INVERSE_PROJECTION, inverseView, stableViewProjection);
                    runFrame(frozen, device, noisy, depth, normal, null, null,
                            INVERSE_PROJECTION, inverseView, stableViewProjection);
                    double tracked = rmse(readAttachment(tracking.readFramebuffer(), 0, SIZE),
                            truth, SIZE * SIZE);
                    double frozenError = rmse(readAttachment(frozen.readFramebuffer(), 0, SIZE),
                            truth, SIZE * SIZE);
                    if (frame == 0) first = tracked;
                    if (frame == 31) {
                        trackedFinal = tracked;
                        frozenFinal = frozenError;
                    }
                }
                assertTrue(first > 0.05, "the first tracked frame must contain noise");
                assertTrue(trackedFinal < 0.1,
                        "reprojected history must track the moving camera: " + trackedFinal);
                assertTrue(trackedFinal < frozenFinal * 0.7,
                        "reprojection must beat a zero-pixel-budget control: "
                                + trackedFinal + " vs " + frozenFinal);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void velocityReprojectionFollowsARigidObjectAndRejectsAStaticHistoryControl() {
        try (GlfwWindow window = window("Temporal Denoise Object Motion");) {
            try (Texture2D noisy = floatTexture(4, SIZE, new float[SIZE * SIZE * 4]);
                 Texture2D depth = floatTexture(1, SIZE, constantDepth(SIZE, PLANE_DEVICE_DEPTH));
                 Texture2D normal = floatTexture(4, SIZE, constantNormal(SIZE, new Vector3f(0, 0, 1)));
                 Texture2D velocity = floatTexture(2, SIZE, constantVelocity(SIZE, -1.0f / SIZE));
                 Texture2D previousDepth = floatTexture(1, SIZE,
                         constantDepth(SIZE, PLANE_DEVICE_DEPTH));
                 TemporalDenoisePass tracking = new TemporalDenoisePass(
                         CONVERGENCE_SETTINGS, SIZE, SIZE);
                 TemporalDenoisePass sameUv = new TemporalDenoisePass(
                         CONVERGENCE_SETTINGS, SIZE, SIZE)) {
                setNearest(noisy);
                setNearest(depth);
                setNearest(normal);
                setNearest(velocity);
                setNearest(previousDepth);
                GlRenderDevice device = new GlRenderDevice();
                double trackedFinal = 0.0;
                double sameUvFinal = 0.0;
                for (int frame = 0; frame < 16; frame++) {
                    int objectX = 4 + frame;
                    float[] truth = movingObject(objectX);
                    float[] input = addNoise(truth, frame);
                    upload(noisy, 4, SIZE, input);
                    device.invalidateState();
                    runFrame(tracking, device, noisy, depth, normal, velocity, previousDepth,
                            INVERSE_PROJECTION, IDENTITY, PROJECTION);
                    runFrame(sameUv, device, noisy, depth, normal, null, null,
                            INVERSE_PROJECTION, IDENTITY, PROJECTION);
                    if (frame == 15) {
                        trackedFinal = rmse(
                                readAttachment(tracking.readFramebuffer(), 0, SIZE),
                                truth, SIZE * SIZE);
                        sameUvFinal = rmse(
                                readAttachment(sameUv.readFramebuffer(), 0, SIZE),
                                truth, SIZE * SIZE);
                    }
                }
                assertTrue(trackedFinal < 0.12,
                        "velocity history must follow the rigid object: " + trackedFinal);
                assertTrue(trackedFinal < sameUvFinal * 0.75,
                        "velocity reprojection must beat the same-UV control: "
                                + trackedFinal + " vs " + sameUvFinal);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void disocclusionNormalFlipAndBrightnessShockResetTheHistory() {
        try (GlfwWindow window = window("Temporal Denoise Resets");) {
            try (Texture2D noisy = floatTexture(4, SIZE, new float[SIZE * SIZE * 4]);
                 Texture2D depth = floatTexture(1, SIZE, constantDepth(SIZE, PLANE_DEVICE_DEPTH));
                 Texture2D normal = floatTexture(4, SIZE, constantNormal(SIZE, new Vector3f(0, 0, 1)));
                 TemporalDenoisePass pass = new TemporalDenoisePass(RESET_SETTINGS, SIZE, SIZE)) {
                setNearest(noisy);
                setNearest(depth);
                setNearest(normal);
                GlRenderDevice device = new GlRenderDevice();

                upload(noisy, 4, SIZE, uniformColour(SIZE, 4.0f, 0.0f, 0.0f));
                device.invalidateState();
                runFrame(pass, device, noisy, depth, normal, null, null,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION);

                upload(noisy, 4, SIZE, uniformColour(SIZE, 0.05f, 0.05f, 0.05f));
                device.invalidateState();
                runFrame(pass, device, noisy, depth, normal, null, null,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION);
                float[] afterShock = readAttachment(pass.readFramebuffer(), 0, SIZE);
                assertEquals(0.05f, afterShock[0], 0.01f,
                        "a tenfold luminance shock must reset the bright history");
                assertEquals(0.05f, afterShock[1], 0.01f);
                assertEquals(0.05f, afterShock[2], 0.01f);

                upload(noisy, 4, SIZE, uniformColour(SIZE, 4.0f, 0.0f, 0.0f));
                device.invalidateState();
                runFrame(pass, device, noisy, depth, normal, null, null,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION);

                upload(noisy, 4, SIZE, uniformColour(SIZE, 0.0f, 0.0f, 1.0f));
                upload(depth, 1, SIZE, constantDepth(SIZE,
                        deviceDepth(PROJECTION, 4.0f)));
                device.invalidateState();
                runFrame(pass, device, noisy, depth, normal, null, null,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION);
                float[] afterDisocclusion = readAttachment(pass.readFramebuffer(), 0, SIZE);
                assertEquals(0.0f, afterDisocclusion[0], 0.01f,
                        "a depth disocclusion must not keep the red history");
                assertEquals(1.0f, afterDisocclusion[2], 0.01f,
                        "the current blue sample must survive the disocclusion");

                upload(normal, 4, SIZE, constantNormal(SIZE, new Vector3f(0.0f, 1.0f, 0.0f)));
                upload(noisy, 4, SIZE, uniformColour(SIZE, 0.0f, 1.0f, 0.0f));
                device.invalidateState();
                runFrame(pass, device, noisy, depth, normal, null, null,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION);
                float[] afterNormalFlip = readAttachment(pass.readFramebuffer(), 0, SIZE);
                assertEquals(0.0f, afterNormalFlip[0], 0.01f,
                        "a normal flip must reject the previous normal");
                assertEquals(1.0f, afterNormalFlip[1], 0.01f,
                        "the current green sample must survive the normal flip");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void staticFramesRemainStableAndUpdateTheLuminanceMoments() {
        try (GlfwWindow window = window("Temporal Denoise Static");) {
            try (Texture2D noisy = floatTexture(4, SIZE,
                    uniformColour(SIZE, 0.3f, 0.4f, 0.5f));
                 Texture2D depth = floatTexture(1, SIZE, constantDepth(SIZE, PLANE_DEVICE_DEPTH));
                 Texture2D normal = floatTexture(4, SIZE, constantNormal(SIZE, new Vector3f(0, 0, 1)));
                 TemporalDenoisePass pass = new TemporalDenoisePass(RESET_SETTINGS, SIZE, SIZE)) {
                setNearest(noisy);
                setNearest(depth);
                setNearest(normal);
                GlRenderDevice device = new GlRenderDevice();
                for (int frame = 0; frame < 8; frame++) {
                    device.invalidateState();
                    runFrame(pass, device, noisy, depth, normal, null, null,
                            INVERSE_PROJECTION, IDENTITY, PROJECTION);
                    float[] output = readAttachment(pass.readFramebuffer(), 0, SIZE);
                    for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                        // The history colour attachment is RGBA16F; the
                        // half-float representation of 0.3/0.4 differs from the
                        // decimal literal by less than one half ULP.
                        assertEquals(0.3f, output[pixel * 4], 1.0e-3f,
                                "static red must not drift at frame " + frame);
                        assertEquals(0.4f, output[pixel * 4 + 1], 1.0e-3f,
                                "static green must not drift at frame " + frame);
                        assertEquals(0.5f, output[pixel * 4 + 2], 1.0e-3f,
                                "static blue must not drift at frame " + frame);
                    }
                }
                assertTrue(pass.historyValid());
                float[] moments = readMoments(pass.readFramebuffer(), SIZE);
                float luminance = 0.2126f * 0.3f + 0.7152f * 0.4f + 0.0722f * 0.5f;
                assertEquals(luminance, moments[0], 2.0e-3f,
                        "history mean must equal the static luminance");
                assertEquals(luminance * luminance, moments[1], 2.0e-3f,
                        "history mean-square must equal the static squared luminance");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void resizeFailureRollbackAndCloseAreIdempotentWithoutLeakedResources() {
        try (GlfwWindow window = window("Temporal Denoise Lifecycle");) {
            int baseline = GlDebug.resources().liveResources().size();
            try (Texture2D noisy = floatTexture(4, SIZE,
                    uniformColour(SIZE, 0.3f, 0.4f, 0.5f));
                 Texture2D depth = floatTexture(1, SIZE, constantDepth(SIZE, PLANE_DEVICE_DEPTH));
                 Texture2D normal = floatTexture(4, SIZE, constantNormal(SIZE, new Vector3f(0, 0, 1)));
                 TemporalDenoisePass pass = new TemporalDenoisePass(RESET_SETTINGS, SIZE, SIZE)) {
                setNearest(noisy);
                setNearest(depth);
                setNearest(normal);
                GlRenderDevice device = new GlRenderDevice();
                runFrame(pass, device, noisy, depth, normal, null, null,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION);
                float[] committed = readAttachment(pass.readFramebuffer(), 0, SIZE);

                // A recorded but failed frame must not replace the read slot.
                pass.beginFrame();
                CommandBuffer candidate = device.createCommandBuffer();
                pass.record(candidate, new TemporalDenoiseInputs(
                        noisy.id(), depth.id(), normal.id(), 0, 0,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION));
                device.execute(candidate);
                pass.frameFailed();
                assertArrayEquals(committed,
                        readAttachment(pass.readFramebuffer(), 0, SIZE), 1.0e-6f,
                        "a failed frame must keep the previously committed colour");

                System.setProperty("haikalat.test.failTemporalDenoiseResizeAllocation", "true");
                try {
                    assertThrows(IllegalStateException.class,
                            () -> pass.prepareResize(SIZE + 16, SIZE + 16));
                } finally {
                    System.clearProperty("haikalat.test.failTemporalDenoiseResizeAllocation");
                }
                assertEquals(SIZE, pass.readFramebuffer().width(),
                        "a failed resize candidate must not replace the active history");

                pass.resize(SIZE + 16, SIZE + 16);
                assertEquals(SIZE + 16, pass.readFramebuffer().width());
                assertEquals(SIZE + 16, pass.writeFramebuffer().height());
                pass.close();
                pass.close();
                assertTrue(pass.isClosed(), "close must be idempotent");
            }
            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "close must release the program, quad and every history attachment");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void realSsrSignalConvergesToTheMeanRadianceReference() {
        try (GlfwWindow window = window("Temporal Denoise SSR Signal");) {
            int baseline = GlDebug.resources().liveResources().size();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr",
                    PbrEnvironmentSettings.testQuality());
                 Sampler cubeSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                         GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
                 SsrPass ssr = new SsrPass(SsrSettings.builder().enabled(true)
                         .maxDistanceMetres(12.0f).maxSteps(48).thicknessMetres(1.0f)
                         .roughnessCutoff(0.4f).minimumConfidence(0.15f)
                         .strength(0.9f).edgeFadePixels(8.0f).build());
                 TemporalDenoisePass denoise = new TemporalDenoisePass(
                         CONVERGENCE_SETTINGS, SSR_SIZE, SSR_SIZE)) {
                SsrEnvironmentSource source = environment(environment, cubeSampler);
                Fixture meanFixture = fixture(16.0f);
                try (Texture2D meanColour = floatTexture(4, SSR_SIZE, meanFixture.colour());
                     Texture2D depth = floatTexture(1, SSR_SIZE, meanFixture.depth());
                     Texture2D normal = floatTexture(4, SSR_SIZE, meanFixture.normal());
                     Texture2D roughness = byteTexture(SSR_SIZE, meanFixture.roughness());
                     Framebuffer referenceTarget = Framebuffer.mrt(SSR_SIZE, SSR_SIZE, GL_RGBA32F);
                     Framebuffer signalTarget = Framebuffer.mrt(SSR_SIZE, SSR_SIZE, GL_RGBA32F)) {
                    renderSsr(device, ssr, referenceTarget, meanColour, depth, normal,
                            roughness, source);
                    float[] reference = readAttachment(referenceTarget, 0, SSR_SIZE);

                    double first = 0.0;
                    double last = 0.0;
                    for (int frame = 0; frame < 40; frame++) {
                        // Inject a deterministic per-pixel radiance noise into
                        // the wall.  The mean-colour reference is therefore the
                        // exact expectation of this real SSR signal, while the
                        // neighbourhood clamp still sees spatial variation.
                        float[] frameColour = meanFixture.colour().clone();
                        for (int y = 0; y < SSR_SIZE; y++) {
                            for (int x = 0; x < SSR_SIZE; x++) {
                                int offset = (y * SSR_SIZE + x) * 4;
                                if (frameColour[offset] > 1.0f) {
                                    frameColour[offset] = 16.0f
                                            + 4.0f * noise(x, y, frame);
                                    frameColour[offset + 1] = 0.2f;
                                    frameColour[offset + 2] = 0.1f;
                                }
                            }
                        }
                        upload(meanColour, 4, SSR_SIZE, frameColour);
                        device.invalidateState();
                        renderSsr(device, ssr, signalTarget, meanColour, depth, normal,
                                roughness, source);
                        // The denoiser consumes the real SSR attachment directly,
                        // as the production colour chain would; only the test error
                        // metric reads pixels back.
                        runFrame(denoise, device, signalTarget.colorAttachment(0),
                                depth, normal, SSR_INVERSE_PROJECTION,
                                SSR_INVERSE_VIEW, SSR_VIEW_PROJECTION);
                        double error = rmse(readAttachment(
                                denoise.readFramebuffer(), 0, SSR_SIZE), reference,
                                SSR_SIZE * SSR_SIZE);
                        if (frame == 0) first = error;
                        if (frame == 39) last = error;
                    }
                    assertTrue(first > 0.05,
                            "the real SSR signal must change between frames");
                    assertTrue(last < first * 0.6,
                            "the denoiser must converge against the real SSR signal: "
                                    + last + " vs " + first);
                    assertTrue(last < 1.0,
                            "the residual error against the mean-radiance reference must stay bounded");
                }
            }
            assertEquals(baseline, GlDebug.resources().liveResources().size());
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void pipelineInsertsOneDenoisePassAfterSsrAndRejectsTheMissingSignal() {
        try (GlfwWindow window = window("Temporal Denoise Pipeline");) {
            RenderSettings render = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .vsync(false)
                    .build();
            PostProcessSettings effects = PostProcessSettings.defaults()
                    .withSsr(SsrSettings.defaults())
                    .withTemporalDenoise(TemporalDenoiseSettings.defaults());
            try (PostProcessPipeline pipeline = PostProcessPipeline.create(render, effects,
                    SIZE, SIZE, null, DENOISE_SURFACE, null, NO_ENVIRONMENT);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                pipeline.validateSurfaceInputs();
                assertEquals(java.util.List.of(PostProcessTargets.GEOMETRY_PASS,
                                PostProcessTargets.SSR_PASS,
                                PostProcessTargets.TEMPORAL_DENOISE_PASS,
                                PostProcessTargets.TONE_MAPPING_PASS,
                                PostProcessTargets.PRESENT_PASS),
                        graph.description().executionOrder(),
                        "the denoiser must run after SSR and before tone mapping");
                assertTrue(graph.hasPass(PostProcessTargets.TEMPORAL_DENOISE_PASS));
                assertEquals(PostProcessTargets.TEMPORAL_DENOISE_COLOR,
                        pipeline.linearHdrTextureName(),
                        "the HDR capture and downstream effects must see the denoised colour");
            }

            assertThrows(IllegalArgumentException.class,
                    () -> PostProcessPipeline.create(render,
                            PostProcessSettings.defaults()
                                    .withTemporalDenoise(TemporalDenoiseSettings.defaults()),
                            SIZE, SIZE, null, DENOISE_SURFACE, null, NO_ENVIRONMENT),
                    "temporal denoising has a hard precondition on an existing signal");

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(render,
                    PostProcessSettings.defaults(), SIZE, SIZE, null, NO_SURFACE, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                pipeline.addFinalPass(graph);
                assertFalse(graph.hasPass(PostProcessTargets.TEMPORAL_DENOISE_PASS));
                assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                                ToneMappingMode.ACES),
                        graph.description().executionOrder(),
                        "the disabled request must keep the exact colour-chain baseline");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------

    private static final int SSR_SIZE = 48;
    private static final Vector3f EYE = new Vector3f(0.0f, 1.6f, 4.0f);
    private static final Vector3f CENTRE = new Vector3f(0.0f, 1.0f, -3.0f);
    private static final float SSR_FIELD_OF_VIEW = (float) Math.toRadians(55.0);
    private static final float WALL_Z = -3.0f;
    private static final float WALL_HALF_WIDTH = 1.2f;
    private static final float WALL_HEIGHT = 2.0f;
    private static final Matrix4f SSR_VIEW = new Matrix4f().lookAt(EYE, CENTRE,
            new Vector3f(0.0f, 1.0f, 0.0f));
    private static final Matrix4f SSR_PROJECTION = new Matrix4f().perspective(
            SSR_FIELD_OF_VIEW, 1.0f, 0.1f, 100.0f);
    private static final Matrix4f SSR_VIEW_PROJECTION = new Matrix4f(SSR_PROJECTION)
            .mul(SSR_VIEW);
    private static final Matrix4f SSR_INVERSE_VIEW = new Matrix4f(SSR_VIEW).invert();
    private static final Matrix4f SSR_INVERSE_PROJECTION = new Matrix4f(SSR_PROJECTION)
            .invert();

    private static GlfwWindow window(String title) {
        GlfwWindow window = new GlfwWindow.Builder().dimensions(64, 64)
                .title(title).visible(false).build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        recordDevice();
        return window;
    }

    private static void runFrame(TemporalDenoisePass pass, GlRenderDevice device,
                                 Texture2D noisy, Texture2D depth, Texture2D normal,
                                 Texture2D velocity, Texture2D previousDepth,
                                 Matrix4f inverseProjection, Matrix4f inverseView,
                                 Matrix4f stableViewProjection) {
        pass.beginFrame();
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, new TemporalDenoiseInputs(
                noisy.id(), depth.id(), normal.id(),
                velocity == null ? 0 : velocity.id(),
                previousDepth == null ? 0 : previousDepth.id(),
                inverseProjection, inverseView, stableViewProjection));
        device.execute(commands);
        pass.frameSucceeded();
    }

    private static void runFrame(TemporalDenoisePass pass, GlRenderDevice device,
                                 int noisyTexture, Texture2D depth, Texture2D normal,
                                 Matrix4f inverseProjection, Matrix4f inverseView,
                                 Matrix4f stableViewProjection) {
        pass.beginFrame();
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, new TemporalDenoiseInputs(
                noisyTexture, depth.id(), normal.id(), 0, 0,
                inverseProjection, inverseView, stableViewProjection));
        device.execute(commands);
        pass.frameSucceeded();
    }

    private static float[] noisyFrame(int frame) {
        float[] colour = new float[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                float truth = 0.55f + 0.12f
                        * (float) (Math.sin(x * 0.4) * Math.cos(y * 0.3));
                float value = truth + NOISE_AMPLITUDE * noise(x, y, frame);
                colour[offset] = value;
                colour[offset + 1] = value;
                colour[offset + 2] = value;
                colour[offset + 3] = 1.0f;
            }
        }
        return colour;
    }

    private static float[] highSampleReference(int samples) {
        float[] reference = new float[SIZE * SIZE * 4];
        for (int frame = 0; frame < samples; frame++) {
            float[] frameColour = noisyFrame(frame);
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                int offset = pixel * 4;
                reference[offset] += frameColour[offset] / samples;
                reference[offset + 1] += frameColour[offset + 1] / samples;
                reference[offset + 2] += frameColour[offset + 2] / samples;
                reference[offset + 3] = 1.0f;
            }
        }
        return reference;
    }

    private static float[] movingPattern(Matrix4f inverseView) {
        float[] truth = new float[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                Vector3f world = worldPoint(x, y, SIZE, inverseView);
                // A sharp world-locked edge makes a missing reprojection
                // immediately visible; the 0.277 px/frame camera shift would be
                // almost invisible on a low-frequency gradient.
                float value = world.x > 0.0f ? 0.8f : 0.2f;
                truth[offset] = value;
                truth[offset + 1] = value;
                truth[offset + 2] = value;
                truth[offset + 3] = 1.0f;
            }
        }
        return truth;
    }

    private static float[] movingObject(int objectX) {
        float[] truth = new float[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                float value = x >= objectX && x < objectX + 8 ? 0.9f : 0.1f;
                truth[offset] = value;
                truth[offset + 1] = value;
                truth[offset + 2] = value;
                truth[offset + 3] = 1.0f;
            }
        }
        return truth;
    }

    private static Vector3f worldPoint(int x, int y, int size, Matrix4f inverseView) {
        float ndcX = (x + 0.5f) / size * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / size * 2.0f - 1.0f;
        Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f).mul(INVERSE_PROJECTION);
        near.div(near.w);
        Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f).mul(INVERSE_PROJECTION);
        far.div(far.w);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y,
                far.z - near.z).normalize();
        float t = PLANE_VIEW_DEPTH / -direction.z;
        return new Vector3f(direction).mul(t).mulPosition(inverseView);
    }

    private static float[] addNoise(float[] truth, int frame) {
        float[] noisy = truth.clone();
        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
            int x = pixel % SIZE;
            int y = pixel / SIZE;
            float value = NOISE_AMPLITUDE * noise(x, y, frame);
            noisy[pixel * 4] = Math.max(0.0f, noisy[pixel * 4] + value);
            noisy[pixel * 4 + 1] = Math.max(0.0f, noisy[pixel * 4 + 1] + value);
            noisy[pixel * 4 + 2] = Math.max(0.0f, noisy[pixel * 4 + 2] + value);
        }
        return noisy;
    }

    private static float noise(int x, int y, int frame) {
        int hash = x * 73856093 ^ y * 19349663 ^ frame * 83492791;
        hash ^= hash >>> 13;
        hash *= 1274126177;
        hash ^= hash >>> 16;
        return (hash & 0xffff) / 65535.0f * 2.0f - 1.0f;
    }

    private static float[] constantDepth(int size, float depth) {
        float[] values = new float[size * size];
        Arrays.fill(values, depth);
        return values;
    }

    private static float[] constantNormal(int size, Vector3f normal) {
        float[] values = new float[size * size * 4];
        float[] oct = octEncode(normal);
        for (int pixel = 0; pixel < size * size; pixel++) {
            values[pixel * 4] = oct[0];
            values[pixel * 4 + 1] = oct[1];
            values[pixel * 4 + 3] = 1.0f;
        }
        return values;
    }

    private static float[] constantVelocity(int size, float velocityX) {
        float[] values = new float[size * size * 2];
        for (int pixel = 0; pixel < size * size; pixel++) {
            values[pixel * 2] = velocityX;
        }
        return values;
    }

    private static float[] uniformColour(int size, float red, float green, float blue) {
        float[] values = new float[size * size * 4];
        for (int pixel = 0; pixel < size * size; pixel++) {
            values[pixel * 4] = red;
            values[pixel * 4 + 1] = green;
            values[pixel * 4 + 2] = blue;
            values[pixel * 4 + 3] = 1.0f;
        }
        return values;
    }

    private static float deviceDepth(Matrix4f projection, float viewDepth) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDepth, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static float[] octEncode(Vector3f normal) {
        Vector3f n = new Vector3f(normal).normalize();
        float denominator = Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z) + 1.0e-8f;
        float x = n.x / denominator;
        float y = n.y / denominator;
        if (n.z < 0.0f) {
            float signX = n.x >= 0.0f ? 1.0f : -1.0f;
            float signY = n.y >= 0.0f ? 1.0f : -1.0f;
            float originalX = x;
            x = (1.0f - Math.abs(y)) * signX;
            y = (1.0f - Math.abs(originalX)) * signY;
        }
        return new float[]{x, y};
    }

    private static Texture2D floatTexture(int channels, int size, float[] data) {
        if (channels == 2) {
            // Texture2D has no dynamic RG32F upload path; widen the velocity to
            // RGBA32F.  The shader only reads .rg, so the extra channels are inert.
            float[] expanded = new float[size * size * 4];
            for (int pixel = 0; pixel < size * size; pixel++) {
                expanded[pixel * 4] = data[pixel * 2];
                expanded[pixel * 4 + 1] = data[pixel * 2 + 1];
                expanded[pixel * 4 + 3] = 1.0f;
            }
            channels = 4;
            data = expanded;
        }
        int format = channels == 1 ? GL_R32F : GL_RGBA32F;
        Texture2D texture = Texture2D.createEmpty(size, size, format);
        upload(texture, channels, size, data);
        return texture;
    }

    private static Texture2D byteTexture(int size, byte[] data) {
        Texture2D texture = Texture2D.createEmpty(size, size,
                org.lwjgl.opengl.GL30.GL_R8);
        ByteBuffer upload = BufferUtils.createByteBuffer(size * size);
        upload.put(data);
        upload.flip();
        texture.uploadRegion(0, 0, size, size, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static void upload(Texture2D texture, int channels, int size, float[] data) {
        ByteBuffer upload = BufferUtils.createByteBuffer(
                size * size * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, size, size, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
    }

    private static void setNearest(Texture2D texture) {
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
    }

    private static float[] readAttachment(Framebuffer framebuffer, int attachment, int size) {
        framebuffer.bind();
        glViewport(0, 0, size, size);
        glReadBuffer(GL_COLOR_ATTACHMENT0 + attachment);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(size * size * 4);
        glReadPixels(0, 0, size, size, GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[size * size * 4];
        pixels.get(result);
        return result;
    }

    private static float[] readMoments(Framebuffer framebuffer, int size) {
        framebuffer.bind();
        glViewport(0, 0, size, size);
        glReadBuffer(GL_COLOR_ATTACHMENT0 + 2);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(size * size * 2);
        glReadPixels(0, 0, size, size, GL_RG, GL_FLOAT, pixels);
        float[] result = new float[size * size * 2];
        pixels.get(result);
        return result;
    }

    private static double rmse(float[] actual, float[] reference, int pixels) {
        double sum = 0.0;
        for (int pixel = 0; pixel < pixels; pixel++) {
            int offset = pixel * 4;
            for (int channel = 0; channel < 3; channel++) {
                double difference = actual[offset + channel]
                        - reference[offset + channel];
                sum += difference * difference;
            }
        }
        return Math.sqrt(sum / (pixels * 3.0));
    }

    private static void assertArrayEquals(float[] expected, float[] actual,
                                          float tolerance, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], tolerance,
                    message + " at " + index);
        }
    }

    // ---------------------------------------------------------------------
    // SSR fixture used only by the real-signal case
    // ---------------------------------------------------------------------

    private record Fixture(float[] colour, float[] depth, float[] normal,
                           byte[] roughness) {
    }

    private static Fixture fixture(float wallLevel) {
        float[] colour = new float[SSR_SIZE * SSR_SIZE * 4];
        float[] depth = new float[SSR_SIZE * SSR_SIZE];
        float[] normal = new float[SSR_SIZE * SSR_SIZE * 4];
        byte[] roughness = new byte[SSR_SIZE * SSR_SIZE];
        Arrays.fill(depth, 1.0f);
        for (int y = 0; y < SSR_SIZE; y++) {
            for (int x = 0; x < SSR_SIZE; x++) {
                int index = y * SSR_SIZE + x;
                int offset = index * 4;
                float ndcX = (x + 0.5f) / SSR_SIZE * 2.0f - 1.0f;
                float ndcY = (y + 0.5f) / SSR_SIZE * 2.0f - 1.0f;
                Vector3f direction = viewRay(ndcX, ndcY);
                float floorT = direction.y < 0.0f ? -EYE.y / direction.y
                        : Float.MAX_VALUE;
                float wallT = direction.z < 0.0f ? (WALL_Z - EYE.z) / direction.z
                        : Float.MAX_VALUE;
                boolean floorHit = floorT > 0.0f && floorT < Float.MAX_VALUE;
                boolean wallHit = false;
                if (wallT > 0.0f && wallT < Float.MAX_VALUE) {
                    Vector3f hit = new Vector3f(EYE).fma(wallT, direction);
                    wallHit = Math.abs(hit.x) <= WALL_HALF_WIDTH
                            && hit.y >= 0.0f && hit.y <= WALL_HEIGHT;
                }
                boolean wallChosen = wallHit && (!floorHit || wallT < floorT);
                boolean sky = !wallChosen && !floorHit;
                Vector3f position;
                Vector3f surfaceNormal;
                float surfaceRoughness;
                float red;
                float green;
                float blue;
                if (wallChosen) {
                    position = new Vector3f(EYE).fma(wallT, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = 0.9f;
                    red = wallLevel;
                    green = 0.2f;
                    blue = 0.1f;
                } else if (floorHit) {
                    position = new Vector3f(EYE).fma(floorT, direction);
                    surfaceNormal = new Vector3f(0.0f, 1.0f, 0.0f);
                    surfaceRoughness = 0.05f;
                    red = 0.05f;
                    green = 0.06f;
                    blue = 0.07f;
                } else {
                    position = new Vector3f(EYE).fma(1.0f, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = 1.0f;
                    red = 0.01f;
                    green = 0.02f;
                    blue = 0.03f;
                }
                if (!sky) {
                    Vector4f viewPosition = new Vector4f(position, 1.0f).mul(SSR_VIEW);
                    Vector4f clip = new Vector4f(viewPosition).mul(SSR_PROJECTION);
                    depth[index] = clip.z / clip.w * 0.5f + 0.5f;
                }
                float[] oct = octEncode(surfaceNormal);
                normal[offset] = oct[0];
                normal[offset + 1] = oct[1];
                normal[offset + 3] = 1.0f;
                roughness[index] = (byte) Math.round(surfaceRoughness * 255.0f);
                colour[offset] = red;
                colour[offset + 1] = green;
                colour[offset + 2] = blue;
                colour[offset + 3] = 1.0f;
            }
        }
        return new Fixture(colour, depth, normal, roughness);
    }

    private static Vector3f viewRay(float ndcX, float ndcY) {
        Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f)
                .mul(SSR_INVERSE_PROJECTION);
        near.div(near.w);
        Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f)
                .mul(SSR_INVERSE_PROJECTION);
        far.div(far.w);
        Vector3f nearWorld = new Vector3f(near.x, near.y, near.z)
                .mulPosition(SSR_INVERSE_VIEW);
        Vector3f farWorld = new Vector3f(far.x, far.y, far.z)
                .mulPosition(SSR_INVERSE_VIEW);
        return farWorld.sub(nearWorld).normalize();
    }

    private static void renderSsr(GlRenderDevice device, SsrPass ssr,
                                  Framebuffer target, Texture2D colour,
                                  Texture2D depth, Texture2D normal,
                                  Texture2D roughness, SsrEnvironmentSource source) {
        target.bind();
        glViewport(0, 0, SSR_SIZE, SSR_SIZE);
        CommandBuffer commands = device.createCommandBuffer();
        ssr.recordIntoCurrentTarget(commands, colour.id(), depth.id(), normal.id(),
                roughness.id(), SSR_INVERSE_PROJECTION, SSR_INVERSE_VIEW, SSR_VIEW,
                SSR_VIEW_PROJECTION, source, SSR_SIZE, SSR_SIZE);
        device.execute(commands);
    }

    private static SsrEnvironmentSource environment(PbrEnvironment environment,
                                                    Sampler sampler) {
        return new SsrEnvironmentSource() {
            @Override
            public float bindPrefilteredSpecular(CommandBuffer cmd, int unit) {
                cmd.bindTextureCube(unit, environment.prefilteredSpecular(), sampler);
                return environment.prefilteredSpecular().mipLevels() - 1.0f;
            }

            @Override
            public float rotationRadians() {
                return environment.rotationRadians();
            }

            @Override
            public float intensity() {
                return environment.intensity();
            }
        };
    }

    private static void recordDevice() {
        String vendor = glGetString(GL_VENDOR);
        String renderer = glGetString(GL_RENDERER);
        String version = glGetString(GL_VERSION);
        var snapshot = GlDebug.messages();
        StringBuilder text = new StringBuilder();
        text.append("vendor=").append(vendor).append('\n')
                .append("renderer=").append(renderer).append('\n')
                .append("version=").append(version).append('\n')
                .append("debugMessages=").append(snapshot.messages().size())
                .append(" dropped=").append(snapshot.droppedCount()).append('\n');
        for (var message : snapshot.messages()) {
            text.append("  ").append(message.severity()).append(' ')
                    .append(message.source()).append(' ').append(message.type())
                    .append(" id=").append(message.driverId()).append(' ')
                    .append(message.message()).append('\n');
        }
        try {
            Path directory = Path.of("build", "reports", "gl");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("temporal-denoise-device.txt"),
                    text.toString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot record the temporal-denoise GL device identity", failure);
        }
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

    private static final SurfaceInputs DENOISE_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return switch (channel) {
                case DEPTH -> new Input(channel, PostProcessTargets.SCENE_DEPTH,
                        PostProcessTargets.GEOMETRY_PASS);
                case NORMAL -> new Input(channel, PostProcessTargets.SCENE_NORMAL,
                        PostProcessTargets.GEOMETRY_PASS);
                case ROUGHNESS -> new Input(channel, PostProcessTargets.SCENE_ROUGHNESS,
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

    private static final SsrEnvironmentSource NO_ENVIRONMENT = new SsrEnvironmentSource() {
        @Override
        public float bindPrefilteredSpecular(CommandBuffer cmd, int unit) {
            return -1.0f;
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
}
