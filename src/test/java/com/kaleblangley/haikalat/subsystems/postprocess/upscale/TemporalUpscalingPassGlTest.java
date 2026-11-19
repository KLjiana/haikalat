package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSource;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL contract for temporal upscaling from an independently rendered
 * low-resolution colour signal.
 *
 * <p>Cases are independent and use four analytically generated fixtures:
 * a smooth scene for the native-reference bound, a jittered high-frequency
 * scene for the temporal-versus-spatial comparison, a world-locked moving
 * scene for reprojection, and depth/exposure cuts for history resets.  All
 * references are computed on the CPU from the analytic signal, not from the
 * pass output.  The last cases check the candidate resize/failure transaction,
 * the exact disabled no-op at colour-chain level and the pipeline insertion and
 * rejection rules.  The class is guarded and must be run by the serial GPU
 * queue.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TemporalUpscalingPassGlTest {
    private static final int OUTPUT_WIDTH = 64;
    private static final int OUTPUT_HEIGHT = 64;
    private static final int INPUT_WIDTH = 32;
    private static final int INPUT_HEIGHT = 32;
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(60.0);
    private static final float TAN_HALF_FOV = (float) Math.tan(FIELD_OF_VIEW * 0.5);
    private static final float PLANE_VIEW_DEPTH = 2.0f;
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            FIELD_OF_VIEW, 1.0f, 0.1f, 100.0f);
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final float PLANE_DEVICE_DEPTH = deviceDepth(PROJECTION, PLANE_VIEW_DEPTH);

    private static final TemporalUpscalingSettings CONVERGENCE =
            TemporalUpscalingSettings.builder()
                    .enabled(true)
                    .renderScale(0.5f)
                    .historyBlend(0.9f)
                    .sharpness(0.0f)
                    .reactiveThreshold(1.0f)
                    .exposureThreshold(1.0f)
                    .maxReprojectionPixels(64.0f)
                    .quality(TemporalUpscalingSettings.Quality.HIGH)
                    .build();

    private static final TemporalUpscalingSettings REFERENCE = CONVERGENCE.withSharpness(0.25f);

    private static final TemporalUpscalingSettings RESET = CONVERGENCE
            .withReactiveThreshold(0.5f)
            .withExposureThreshold(0.1f)
            .withQuality(TemporalUpscalingSettings.Quality.MEDIUM);

    @Test
    void lowResolutionInputUpscalesToTheTargetAndMatchesTheNativeReference() {
        try (GlfwWindow window = window("Temporal Upscaling Reference")) {
            int baseline = GlDebug.resources().liveResources().size();
            GlRenderDevice device = new GlRenderDevice();
            try (Textures textures = new Textures();
                 TemporalUpscalingPass pass = new TemporalUpscalingPass(REFERENCE,
                         OUTPUT_WIDTH, OUTPUT_HEIGHT)) {
                float[] lowResolution = supersampledScene(INPUT_WIDTH, INPUT_HEIGHT, 4);
                float[] nativeReference = supersampledScene(OUTPUT_WIDTH, OUTPUT_HEIGHT, 4);
                float[] output = null;
                for (int frame = 0; frame < 4; frame++) {
                    output = runFrame(pass, device, textures,
                            staticFrame(lowResolution, 1.0f));
                }
                assertTrue(pass.historyValid());
                assertEquals(OUTPUT_WIDTH, pass.readOutputFramebuffer().width());
                assertEquals(OUTPUT_HEIGHT, pass.readOutputFramebuffer().height());
                assertFinite(output);
                double meanAbsoluteError = meanAbsoluteError(output, nativeReference);
                double maximumError = maximumChannelError(output, nativeReference);
                assertTrue(meanAbsoluteError < 0.04,
                        "upscaled frame MAE must stay bounded, got " + meanAbsoluteError);
                assertTrue(maximumError < 0.25,
                        "upscaled frame maximum error must stay bounded, got "
                                + maximumError);
                double edgeError = edgeBandError(output, nativeReference);
                assertTrue(edgeError < 0.20,
                        "edge/detail error must stay bounded, got " + edgeError);
            }
            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "the pass must release its programs, quad, history and sharpen target");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void temporalReconstructionBeatsSpatialBilinearAgainstTheNativeReference() {
        try (GlfwWindow window = window("Temporal Upscaling Reconstruction")) {
            GlRenderDevice device = new GlRenderDevice();
            try (Textures textures = new Textures();
                 TemporalUpscalingPass pass = new TemporalUpscalingPass(CONVERGENCE,
                         OUTPUT_WIDTH, OUTPUT_HEIGHT)) {
                float[] nativeReference = highFrequencyScene(OUTPUT_WIDTH, OUTPUT_HEIGHT, 8, 0.0);
                float[] firstFrameInput = jitteredHighFrequencyScene(0.0f, 0.0f);
                float[] spatial = bilinearUpsample(firstFrameInput, 0.0f, 0.0f);
                double frameZeroError = meanAbsoluteError(spatial, nativeReference);
                assertTrue(frameZeroError > 0.05,
                        "the single-frame bilinear reference must show visible aliasing, got "
                                + frameZeroError);

                // Independent upper bound for any phase-averaging reconstructor:
                // the uniform average of the per-phase bilinear samples.  The
                // current sample position must include the jitter, exactly like
                // the production `outputUv + jitter`; sampling the jittered
                // fixture at the unshifted output UV would average shifted copies
                // and be worse than a single frame.
                float[] phaseAverageRgba = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT * 4];
                for (float jitterPixels : JITTER_PHASES) {
                    float jitterUv = jitterPixels / INPUT_WIDTH;
                    float[] input = jitteredHighFrequencyScene(jitterUv, jitterUv);
                    float[] phase = bilinearUpsample(input, jitterUv, jitterUv);
                    for (int index = 0; index < phase.length; index++) {
                        phaseAverageRgba[index] += phase[index] / JITTER_PHASES.length;
                    }
                }
                double phaseAverageError = meanAbsoluteError(phaseAverageRgba, nativeReference);
                assertTrue(phaseAverageError < frameZeroError * 0.9,
                        "the phase-averaged reference itself must improve on one frame: "
                                + phaseAverageError + " vs " + frameZeroError);

                float[] temporal = null;
                for (int frame = 0; frame < 24; frame++) {
                    float jitterPixels = JITTER_PHASES[frame % JITTER_PHASES.length];
                    float jitterUv = jitterPixels / INPUT_WIDTH;
                    float[] input = jitteredHighFrequencyScene(jitterUv, jitterUv);
                    temporal = runFrame(pass, device, textures, new Frame(
                            INPUT_WIDTH, INPUT_HEIGHT, input, constantDepth(INPUT_WIDTH,
                            INPUT_HEIGHT, PLANE_DEVICE_DEPTH), null, null, null, null,
                            jitterUv, jitterUv, 1.0f,
                            jitteredInverseProjection(jitterUv, jitterUv),
                            IDENTITY, PROJECTION));
                }
                assertTrue(pass.historyValid());
                double temporalError = meanAbsoluteError(temporal, nativeReference);
                assertTrue(temporalError < frameZeroError,
                        "temporal reconstruction must beat single-frame bilinear: "
                                + temporalError + " vs " + frameZeroError);
                assertTrue(temporalError < frameZeroError * 0.85,
                        "temporal reconstruction must improve by the measured margin: "
                                + temporalError + " vs " + frameZeroError);
                assertTrue(temporalError <= phaseAverageError * 1.10,
                        "temporal reconstruction must approach the phase-average bound: "
                                + temporalError + " vs " + phaseAverageError);
                assertTrue(temporalError < 0.045,
                        "temporal reconstruction must stay close to the native reference, got "
                                + temporalError);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void cameraMotionCutDisocclusionAndExposureDoNotSmearTheHistory() {
        try (GlfwWindow window = window("Temporal Upscaling Motion")) {
            GlRenderDevice device = new GlRenderDevice();
            assertCameraMotionTracksTheCurrentFrame(device);
            assertVelocityReprojectionFollowsARigidObject(device);
            assertReactiveExposureAndDisocclusionResetTheHistory(device);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static void assertCameraMotionTracksTheCurrentFrame(GlRenderDevice device) {
        try (Textures textures = new Textures();
             TemporalUpscalingPass tracking = new TemporalUpscalingPass(CONVERGENCE,
                     OUTPUT_WIDTH, OUTPUT_HEIGHT);
             TemporalUpscalingPass sameUv = new TemporalUpscalingPass(CONVERGENCE,
                     OUTPUT_WIDTH, OUTPUT_HEIGHT)) {
            float[] truth = null;
            float[] tracked = null;
            float[] sameUvFrame = null;
            float finalCameraX = 0.0f;
            for (int frame = 0; frame < 24; frame++) {
                float cameraX = frame * 0.05f;
                finalCameraX = cameraX;
                float[] input = worldScene(INPUT_WIDTH, INPUT_HEIGHT, cameraX, 1);
                truth = worldScene(OUTPUT_WIDTH, OUTPUT_HEIGHT, cameraX, 4);
                Matrix4f stableViewProjection = new Matrix4f(PROJECTION)
                        .mul(new Matrix4f().translate(-cameraX, 0.0f, 0.0f));
                Matrix4f inverseView = new Matrix4f().translate(cameraX, 0.0f, 0.0f);
                Frame reprojectedFrame = new Frame(INPUT_WIDTH, INPUT_HEIGHT, input,
                        constantDepth(INPUT_WIDTH, INPUT_HEIGHT, PLANE_DEVICE_DEPTH),
                        null, null, null, null, 0.0f, 0.0f, 1.0f,
                        INVERSE_PROJECTION, inverseView, stableViewProjection);
                tracked = runFrame(tracking, device, textures, reprojectedFrame);
                // The same-UV control uses the velocity contract with a zero
                // displacement, so history is accepted on the current pixel
                // while the world-locked content moves across the raster.  This
                // is the real no-reprojection control; rejecting history (a zero
                // pixel budget) would instead produce a sharp single-frame
                // spatial result and is not a reproducibility control.
                sameUvFrame = runFrame(sameUv, device, textures, new Frame(
                        INPUT_WIDTH, INPUT_HEIGHT, input,
                        constantDepth(INPUT_WIDTH, INPUT_HEIGHT, PLANE_DEVICE_DEPTH),
                        constantVelocity(INPUT_WIDTH, INPUT_HEIGHT, 0.0f, 0.0f),
                        constantDepth(INPUT_WIDTH, INPUT_HEIGHT, PLANE_VIEW_DEPTH),
                        null, null, 0.0f, 0.0f, 1.0f,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION));
            }
            double trackedError = meanAbsoluteError(tracked, truth);
            double sameUvError = meanAbsoluteError(sameUvFrame, truth);
            assertTrue(trackedError < 0.06,
                    "reprojected history must track the moving camera, got " + trackedError);
            assertTrue(trackedError < sameUvError * 0.8,
                    "reprojection must reduce the motion smear against a same-UV history "
                            + "control: " + trackedError + " vs " + sameUvError);

            // Once the camera stops the history must converge back to the current
            // frame; a misaligned reprojection would leave a permanent ghost.
            double settleError = trackedError;
            for (int frame = 0; frame < 32; frame++) {
                float[] input = worldScene(INPUT_WIDTH, INPUT_HEIGHT, finalCameraX, 1);
                truth = worldScene(OUTPUT_WIDTH, OUTPUT_HEIGHT, finalCameraX, 4);
                Matrix4f stableViewProjection = new Matrix4f(PROJECTION)
                        .mul(new Matrix4f().translate(-finalCameraX, 0.0f, 0.0f));
                Matrix4f inverseView = new Matrix4f().translate(finalCameraX, 0.0f, 0.0f);
                tracked = runFrame(tracking, device, textures, new Frame(
                        INPUT_WIDTH, INPUT_HEIGHT, input,
                        constantDepth(INPUT_WIDTH, INPUT_HEIGHT, PLANE_DEVICE_DEPTH),
                        null, null, null, null, 0.0f, 0.0f, 1.0f,
                        INVERSE_PROJECTION, inverseView, stableViewProjection));
                settleError = meanAbsoluteError(tracked, truth);
            }
            assertTrue(settleError < 0.035,
                    "a stopped camera must converge back to the current frame, got "
                            + settleError);
            assertTrue(settleError < trackedError,
                    "settling must reduce the motion error: " + settleError + " vs "
                            + trackedError);
        }
    }

    private static void assertVelocityReprojectionFollowsARigidObject(
            GlRenderDevice device) {
        try (Textures textures = new Textures();
             TemporalUpscalingPass tracking = new TemporalUpscalingPass(CONVERGENCE,
                     OUTPUT_WIDTH, OUTPUT_HEIGHT);
             TemporalUpscalingPass sameUv = new TemporalUpscalingPass(CONVERGENCE,
                     OUTPUT_WIDTH, OUTPUT_HEIGHT)) {
            float[] truth = null;
            float[] tracked = null;
            float[] control = null;
            for (int frame = 0; frame < 8; frame++) {
                int barX = 4 + frame;
                float[] input = objectScene(INPUT_WIDTH, INPUT_HEIGHT, barX + 0.5f, 1);
                truth = objectScene(OUTPUT_WIDTH, OUTPUT_HEIGHT, 2.0f * barX + 1.0f, 4);
                float[] velocity = constantVelocity(INPUT_WIDTH, INPUT_HEIGHT,
                        -1.0f / INPUT_WIDTH, 0.0f);
                Frame velocityFrame = new Frame(INPUT_WIDTH, INPUT_HEIGHT, input,
                        constantDepth(INPUT_WIDTH, INPUT_HEIGHT, PLANE_DEVICE_DEPTH),
                        velocity,
                        constantDepth(INPUT_WIDTH, INPUT_HEIGHT, PLANE_VIEW_DEPTH),
                        null, null, 0.0f, 0.0f, 1.0f,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION);
                tracked = runFrame(tracking, device, textures, velocityFrame);
                // The same-UV control omits the velocity pair, so the static
                // camera reprojects every pixel onto itself while the object moves.
                control = runFrame(sameUv, device, textures, new Frame(
                        INPUT_WIDTH, INPUT_HEIGHT, input,
                        constantDepth(INPUT_WIDTH, INPUT_HEIGHT, PLANE_DEVICE_DEPTH),
                        null, null, null, null, 0.0f, 0.0f, 1.0f,
                        INVERSE_PROJECTION, IDENTITY, PROJECTION));
            }
            double trackedError = meanAbsoluteError(tracked, truth);
            double controlError = meanAbsoluteError(control, truth);
            assertTrue(trackedError < 0.12,
                    "velocity history must follow the rigid object, got " + trackedError);
            assertTrue(trackedError < controlError * 0.8,
                    "velocity reprojection must beat the same-UV control: "
                            + trackedError + " vs " + controlError);
        }
    }


    private static void assertReactiveExposureAndDisocclusionResetTheHistory(
            GlRenderDevice device) {
        try (Textures textures = new Textures();
             TemporalUpscalingPass shockReset = new TemporalUpscalingPass(
                     RESET.withReactiveThreshold(0.5f).withExposureThreshold(1.0f),
                     OUTPUT_WIDTH, OUTPUT_HEIGHT);
             TemporalUpscalingPass shockOblivious = new TemporalUpscalingPass(
                     RESET.withReactiveThreshold(1.0f).withExposureThreshold(1.0f),
                     OUTPUT_WIDTH, OUTPUT_HEIGHT);
             TemporalUpscalingPass exposureAware = new TemporalUpscalingPass(
                     RESET.withReactiveThreshold(1.0f).withExposureThreshold(0.1f),
                     OUTPUT_WIDTH, OUTPUT_HEIGHT);
             TemporalUpscalingPass exposureOblivious = new TemporalUpscalingPass(
                     RESET.withReactiveThreshold(1.0f).withExposureThreshold(1.0f),
                     OUTPUT_WIDTH, OUTPUT_HEIGHT);
             TemporalUpscalingPass disocclusion = new TemporalUpscalingPass(
                     RESET.withReactiveThreshold(1.0f).withExposureThreshold(1.0f),
                     OUTPUT_WIDTH, OUTPUT_HEIGHT)) {
            // A relative luminance drop from 0.8 to 0.3 sits inside the current
            // checkerboard AABB, so only the reactive threshold can reject it.
            runFrame(shockReset, device, textures, staticFrame(
                    checkerScene(INPUT_WIDTH, INPUT_HEIGHT, 0.8f, 0.2f, false), 1.0f));
            float[] afterShock = runFrame(shockReset, device, textures, staticFrame(
                    checkerScene(INPUT_WIDTH, INPUT_HEIGHT, 0.9f, 0.3f, true), 1.0f));
            assertTrue(afterShock[0] < 0.4f,
                    "a luminance shock must reset the bright history, got " + afterShock[0]);

            runFrame(shockOblivious, device, textures, staticFrame(
                    checkerScene(INPUT_WIDTH, INPUT_HEIGHT, 0.8f, 0.2f, false), 1.0f));
            float[] noShock = runFrame(shockOblivious, device, textures, staticFrame(
                    checkerScene(INPUT_WIDTH, INPUT_HEIGHT, 0.9f, 0.3f, true), 1.0f));
            assertTrue(noShock[0] > 0.6f,
                    "the shock-oblivious control must blend the in-range history, got "
                            + noShock[0]);

            // An exposure change beyond the window rejects history even when the
            // luminance does not shock; the oblivious control accepts it.
            runFrame(exposureAware, device, textures, staticFrame(
                    checkerScene(INPUT_WIDTH, INPUT_HEIGHT, 0.8f, 0.2f, false), 1.0f));
            runFrame(exposureOblivious, device, textures, staticFrame(
                    checkerScene(INPUT_WIDTH, INPUT_HEIGHT, 0.8f, 0.2f, false), 1.0f));
            Frame exposureJump = staticFrame(
                    checkerScene(INPUT_WIDTH, INPUT_HEIGHT, 0.8f, 0.2f, true), 2.0f);
            float[] aware = runFrame(exposureAware, device, textures, exposureJump);
            float[] oblivious = runFrame(exposureOblivious, device, textures, exposureJump);
            assertTrue(aware[0] < 0.3f,
                    "an exposure jump must reject the old history, got " + aware[0]);
            assertTrue(oblivious[0] > 0.6f,
                    "the exposure-oblivious control must keep the old history, got "
                            + oblivious[0]);

            // A depth disocclusion must reject the previous near surface even
            // though the current checkerboard AABB still contains its colour.
            runFrame(disocclusion, device, textures, depthScene(INPUT_WIDTH, INPUT_HEIGHT,
                    1.0f, 0.8f, 0.0f, 0.0f));
            Frame revealedFrame = new Frame(INPUT_WIDTH, INPUT_HEIGHT,
                    redBlueCheckerScene(INPUT_WIDTH, INPUT_HEIGHT),
                    constantDepth(INPUT_WIDTH, INPUT_HEIGHT,
                            deviceDepth(PROJECTION, 4.0f)),
                    null, null, null, null, 0.0f, 0.0f, 1.0f,
                    INVERSE_PROJECTION, IDENTITY, PROJECTION);
            float[] revealed = runFrame(disocclusion, device, textures, revealedFrame);
            assertTrue(revealed[0] < 0.2f,
                    "a disoccluded surface must not keep the previous red history, got "
                            + revealed[0]);
            assertTrue(revealed[2] > 0.6f,
                    "the revealed blue surface must be visible, got " + revealed[2]);

            // An explicit cut invalidates history even when the signal does not
            // change enough to trigger the reactive window.
            runFrame(shockOblivious, device, textures,
                    staticFrame(uniformScene(INPUT_WIDTH, INPUT_HEIGHT, 0.9f, 0.1f, 0.1f),
                            1.0f));
            shockOblivious.invalidate();
            assertFalse(shockOblivious.historyValid());
            float[] cut = runFrame(shockOblivious, device, textures,
                    staticFrame(uniformScene(INPUT_WIDTH, INPUT_HEIGHT, 0.1f, 0.9f, 0.1f),
                            1.0f));
            assertEquals(0.1f, cut[0], 0.02f);
            assertEquals(0.9f, cut[1], 0.02f);
        }
    }

    @Test
    void dynamicInputExtentAndCandidateResizeTransactionsAreBounded() {
        try (GlfwWindow window = window("Temporal Upscaling Resize")) {
            int baseline = GlDebug.resources().liveResources().size();
            GlRenderDevice device = new GlRenderDevice();
            TemporalUpscalingPass pass = null;
            try (Textures textures = new Textures()) {
                pass = new TemporalUpscalingPass(REFERENCE, OUTPUT_WIDTH, OUTPUT_HEIGHT);
                for (int frame = 0; frame < 3; frame++) {
                    runFrame(pass, device, textures, staticFrame(
                            supersampledScene(INPUT_WIDTH, INPUT_HEIGHT, 2), 1.0f));
                }
                assertEquals(OUTPUT_WIDTH, pass.readOutputFramebuffer().width());

                // Dynamic resolution: the input extent changes while the output
                // extent and the history storage stay untouched.
                int dynamicWidth = 43;
                int dynamicHeight = 29;
                for (int frame = 0; frame < 3; frame++) {
                    float[] input = supersampledScene(dynamicWidth, dynamicHeight, 2);
                    runFrame(pass, device, textures, new Frame(dynamicWidth, dynamicHeight,
                            input, constantDepth(dynamicWidth, dynamicHeight,
                            PLANE_DEVICE_DEPTH), null, null, null, null, 0.0f, 0.0f,
                            1.0f, INVERSE_PROJECTION, IDENTITY, PROJECTION));
                }
                assertTrue(pass.historyValid(),
                        "an input-extent change must keep the output history valid");
                assertEquals(OUTPUT_WIDTH, pass.readOutputFramebuffer().width());
                assertEquals(OUTPUT_HEIGHT, pass.readOutputFramebuffer().height());

                // A prepared candidate that is not committed must leave the
                // active storage untouched.
                TemporalUpscalingPass.ResizeCandidate abandoned =
                        pass.prepareResize(48, 40);
                abandoned.close();
                assertEquals(OUTPUT_WIDTH, pass.readOutputFramebuffer().width());

                // Injected allocation failure must be observable and recoverable.
                System.setProperty("haikalat.test.failTemporalUpscalingResizeAllocation",
                        "true");
                TemporalUpscalingPass resizeTarget = pass;
                assertThrows(IllegalStateException.class,
                        () -> resizeTarget.prepareResize(48, 40));
                assertFalse(Boolean.getBoolean(
                        "haikalat.test.failTemporalUpscalingResizeAllocation"),
                        "the injected failure marker must be consumed exactly once");
                assertEquals(OUTPUT_WIDTH, pass.readOutputFramebuffer().width());

                pass.resize(48, 40);
                assertFalse(pass.historyValid(),
                        "a committed resize must invalidate the paired camera pose history");
                assertEquals(48, pass.readFramebuffer().width());
                assertEquals(40, pass.readFramebuffer().height());
                float[] resized = runFrame(pass, device, textures, new Frame(
                        dynamicWidth, dynamicHeight,
                        supersampledScene(dynamicWidth, dynamicHeight, 2),
                        constantDepth(dynamicWidth, dynamicHeight, PLANE_DEVICE_DEPTH),
                        null, null, null, null, 0.0f, 0.0f, 1.0f, INVERSE_PROJECTION,
                        IDENTITY, PROJECTION));
                assertEquals(48 * 40 * 4, resized.length);
                assertTrue(pass.historyValid());

                // A failed frame must keep the committed read slot and history.
                float[] committedRed = runFrame(pass, device, textures, new Frame(
                        dynamicWidth, dynamicHeight,
                        uniformScene(dynamicWidth, dynamicHeight, 0.8f, 0.0f, 0.0f),
                        constantDepth(dynamicWidth, dynamicHeight, PLANE_DEVICE_DEPTH),
                        null, null, null, null, 0.0f, 0.0f, 1.0f, INVERSE_PROJECTION,
                        IDENTITY, PROJECTION));
                assertTrue(committedRed[0] > 0.7f);
                Frame failedFrame = new Frame(dynamicWidth, dynamicHeight,
                        uniformScene(dynamicWidth, dynamicHeight, 0.0f, 0.8f, 0.0f),
                        constantDepth(dynamicWidth, dynamicHeight, PLANE_DEVICE_DEPTH),
                        null, null, null, null, 0.0f, 0.0f, 1.0f, INVERSE_PROJECTION,
                        IDENTITY, PROJECTION);
                textures.upload(failedFrame);
                pass.beginFrame();
                CommandBuffer commands = device.createCommandBuffer();
                pass.record(commands, new TemporalUpscalingInputs(
                        textures.color.id(), textures.depth.id(), 0, 0, 0, 0,
                        failedFrame.inputWidth, failedFrame.inputHeight, 0.0f, 0.0f,
                        1.0f, INVERSE_PROJECTION, IDENTITY, PROJECTION));
                device.execute(commands);
                pass.frameFailed();
                assertTrue(pass.historyValid(),
                        "a failed frame must not invalidate the committed history");
                assertEquals(0.8f, readFloat(pass.readFramebuffer(), 48, 40)[0], 0.02f,
                        "a failed frame must not replace the committed read slot");

                float[] recovered = runFrame(pass, device, textures, new Frame(
                        dynamicWidth, dynamicHeight,
                        uniformScene(dynamicWidth, dynamicHeight, 0.0f, 0.0f, 0.9f),
                        constantDepth(dynamicWidth, dynamicHeight, PLANE_DEVICE_DEPTH),
                        null, null, null, null, 0.0f, 0.0f, 1.0f, INVERSE_PROJECTION,
                        IDENTITY, PROJECTION));
                assertTrue(recovered[2] > 0.8f);
            } finally {
                if (pass != null) {
                    pass.close();
                    pass.close();
                }
            }
            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "close must release every tracked resource and be idempotent");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void disabledPipelineMatchesTheNativePathByteForByte() {
        try (GlfwWindow window = window("Temporal Upscaling Disabled")) {
            RenderSettings hdr = hdrSettings();
            RenderedImage nativePath = renderBaseline(hdr, PostProcessSettings.defaults());
            RenderedImage explicitDisabled = renderBaseline(hdr,
                    PostProcessSettings.builder()
                            .temporalUpscaling(TemporalUpscalingSettings.disabled())
                            .build());
            assertArrayEquals(nativePath.bytes, explicitDisabled.bytes,
                    "disabled temporal upscaling must be byte-identical to the native path");
            assertEquals(nativePath.executionOrder, explicitDisabled.executionOrder,
                    "disabled temporal upscaling must not change the colour-chain topology");
            assertFalse(explicitDisabled.executionOrder.contains(
                    PostProcessTargets.TEMPORAL_UPSCALING_PASS));
            assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                            ToneMappingMode.ACES),
                    explicitDisabled.executionOrder);
        }
    }

    @Test
    void pipelineInsertsOneUpscalePassBeforeTheHdrChainAndRejectsMissingSource() {
        try (GlfwWindow window = window("Temporal Upscaling Pipeline")) {
            GlRenderDevice device = new GlRenderDevice();
            RenderSettings hdr = hdrSettings();
            PostProcessSettings active = PostProcessSettings.builder()
                    .temporalUpscaling(TemporalUpscalingSettings.defaults().withSharpness(0.2f))
                    .build();
            try (Texture2D color = colorTexture(INPUT_WIDTH, INPUT_HEIGHT);
                 Texture2D depth = depthTexture(INPUT_WIDTH, INPUT_HEIGHT,
                         PLANE_DEVICE_DEPTH);
                 Framebuffer target = Framebuffer.colorOnly(OUTPUT_WIDTH, OUTPUT_HEIGHT);
                 PostProcessPipeline pipeline = PostProcessPipeline.create(hdr, active,
                         OUTPUT_WIDTH, OUTPUT_HEIGHT, null, NO_SURFACE, null, null,
                         new TemporalUpscalingSource(color.id(), depth.id(),
                                 INPUT_WIDTH, INPUT_HEIGHT));
                 RenderGraph graph = new RenderGraph(OUTPUT_WIDTH, OUTPUT_HEIGHT)) {
                pipeline.beginFrame(pose(0.0f, 0.0f));
                pipeline.addFinalPass(graph);
                pipeline.synchronizeTemporalImports(graph);
                assertTrue(graph.hasPass(PostProcessTargets.TEMPORAL_UPSCALING_PASS));
                assertEquals(PostProcessTargets.TEMPORAL_UPSCALING_COLOR,
                        pipeline.linearHdrTextureName());
                assertEquals(java.util.List.of(
                                PostProcessTargets.TEMPORAL_UPSCALING_PASS,
                                PostProcessTargets.TONE_MAPPING_PASS,
                                PostProcessTargets.PRESENT_PASS),
                        graph.description().executionOrder());
                var description = graph.description().passes().stream()
                        .filter(pass -> pass.name().equals(
                                PostProcessTargets.TEMPORAL_UPSCALING_PASS))
                        .findFirst().orElseThrow();
                assertEquals(RenderGraph.TargetKind.EXTERNAL, description.targetKind());
                assertTrue(description.borrowedExternalStorage().contains(
                        PostProcessTargets.TEMPORAL_UPSCALING_INPUT_COLOR));
                assertTrue(description.borrowedExternalStorage().contains(
                        PostProcessTargets.TEMPORAL_UPSCALING_INPUT_DEPTH));

                PresentationTarget presentation = PresentationTarget.borrowed(
                        target.id(),
                        ExternalAttachment.borrowedColor(target.colorAttachment(0),
                                RenderFormat.RGBA8, OUTPUT_WIDTH, OUTPUT_HEIGHT),
                        null, OUTPUT_WIDTH, OUTPUT_HEIGHT, 1L);
                graph.compile();
                device.invalidateState();
                graph.execute(device, presentation);
                pipeline.frameSucceeded();
                assertTrue(pipeline.temporalUpscalingHistoryValid(),
                        "the pipeline path must commit the upscaling history");
                byte[] bytes = readBytes(target, OUTPUT_WIDTH, OUTPUT_HEIGHT);
                boolean anyNonZero = false;
                for (byte value : bytes) {
                    if (value != 0) {
                        anyNonZero = true;
                        break;
                    }
                }
                assertTrue(anyNonZero, "the upscaled pipeline path must present pixel data");
                assertThrows(IllegalStateException.class,
                        () -> pipeline.prepareResize(OUTPUT_WIDTH * 2, OUTPUT_HEIGHT * 2),
                        "in-place colour-chain resize cannot pair a generation-stable source");
            }

            assertThrows(IllegalArgumentException.class, () -> {
                try (PostProcessPipeline ignored = PostProcessPipeline.create(hdr, active,
                        OUTPUT_WIDTH, OUTPUT_HEIGHT, null, NO_SURFACE, null, null, null)) {
                    // an active upscaler without its reduced source must be rejected
                }
            });
            assertThrows(IllegalArgumentException.class, () -> {
                try (Texture2D color = colorTexture(INPUT_WIDTH, INPUT_HEIGHT);
                     Texture2D depth = depthTexture(INPUT_WIDTH, INPUT_HEIGHT,
                             PLANE_DEVICE_DEPTH);
                     PostProcessPipeline ignored = PostProcessPipeline.create(hdr,
                             PostProcessSettings.defaults(), OUTPUT_WIDTH, OUTPUT_HEIGHT,
                             null, NO_SURFACE, null, null,
                             new TemporalUpscalingSource(color.id(), depth.id(),
                                     INPUT_WIDTH, INPUT_HEIGHT))) {
                    // a stray source while disabled must be rejected
                }
            });
            RenderSettings taa = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.TAA)
                    .toneMappingMode(ToneMappingMode.ACES).vsync(false).build();
            assertThrows(IllegalArgumentException.class, () -> {
                try (Texture2D color = colorTexture(INPUT_WIDTH, INPUT_HEIGHT);
                     Texture2D depth = depthTexture(INPUT_WIDTH, INPUT_HEIGHT,
                             PLANE_DEVICE_DEPTH);
                     PostProcessPipeline ignored = PostProcessPipeline.create(taa, active,
                             OUTPUT_WIDTH, OUTPUT_HEIGHT, null, NO_SURFACE, null, null,
                             new TemporalUpscalingSource(color.id(), depth.id(),
                                     INPUT_WIDTH, INPUT_HEIGHT))) {
                    // full-resolution TAA on top of the upscaler must be rejected
                }
            });
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    // ------------------------------------------------------------------
    // Frame, texture and fixture plumbing
    // ------------------------------------------------------------------

    private record Frame(int inputWidth, int inputHeight, float[] color, float[] depth,
                         float[] velocity, float[] previousDepth, byte[] reactive,
                         byte[] uiMask, float jitterX, float jitterY, float exposure,
                         Matrix4f inverseProjection, Matrix4f inverseView,
                         Matrix4f stableViewProjection) {
    }

    private static final class Textures implements AutoCloseable {
        private final Texture2D color = floatTexture(GL_RGBA32F);
        private final Texture2D depth = floatTexture(GL_R32F);
        private final Texture2D velocity = floatTexture(GL_RGBA32F);
        private final Texture2D previousDepth = floatTexture(GL_R32F);
        private final Texture2D reactive = byteTexture();
        private final Texture2D uiMask = byteTexture();

        void upload(Frame frame) {
            uploadFloat(color, 4, frame.inputWidth, frame.inputHeight, frame.color);
            uploadFloat(depth, 1, frame.inputWidth, frame.inputHeight, frame.depth);
            if (frame.velocity != null) {
                uploadFloat(velocity, 4, frame.inputWidth, frame.inputHeight,
                        widenVelocity(frame.velocity, frame.inputWidth * frame.inputHeight));
            }
            if (frame.previousDepth != null) {
                uploadFloat(previousDepth, 1, frame.inputWidth, frame.inputHeight,
                        frame.previousDepth);
            }
            if (frame.reactive != null) {
                uploadBytes(reactive, frame.inputWidth, frame.inputHeight, frame.reactive);
            }
            if (frame.uiMask != null) {
                uploadBytes(uiMask, frame.inputWidth, frame.inputHeight, frame.uiMask);
            }
        }

        @Override
        public void close() {
            color.close();
            depth.close();
            velocity.close();
            previousDepth.close();
            reactive.close();
            uiMask.close();
        }
    }

    private static float[] runFrame(TemporalUpscalingPass pass, GlRenderDevice device,
                                    Textures textures, Frame frame) {
        textures.upload(frame);
        device.invalidateState();
        pass.beginFrame();
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, new TemporalUpscalingInputs(
                textures.color.id(),
                textures.depth.id(),
                frame.velocity == null ? 0 : textures.velocity.id(),
                frame.previousDepth == null ? 0 : textures.previousDepth.id(),
                frame.reactive == null ? 0 : textures.reactive.id(),
                frame.uiMask == null ? 0 : textures.uiMask.id(),
                frame.inputWidth, frame.inputHeight, frame.jitterX, frame.jitterY,
                frame.exposure, frame.inverseProjection, frame.inverseView,
                frame.stableViewProjection));
        device.execute(commands);
        pass.frameSucceeded();
        Framebuffer output = pass.readOutputFramebuffer();
        return readFloat(output, output.width(), output.height());
    }

    private static Frame staticFrame(float[] color, float exposure) {
        int width = INPUT_WIDTH;
        int height = INPUT_HEIGHT;
        return new Frame(width, height, color,
                constantDepth(width, height, PLANE_DEVICE_DEPTH),
                null, null, null, null, 0.0f, 0.0f, exposure,
                INVERSE_PROJECTION, IDENTITY, PROJECTION);
    }

    private static Frame depthScene(int width, int height, float viewDepth,
                                    float red, float green, float blue) {
        return new Frame(width, height, uniformScene(width, height, red, green, blue),
                constantDepth(width, height, deviceDepth(PROJECTION, viewDepth)),
                null, null, null, null, 0.0f, 0.0f, 1.0f,
                INVERSE_PROJECTION, IDENTITY, PROJECTION);
    }

    private static Matrix4f jitteredInverseProjection(float jitterX, float jitterY) {
        return new Matrix4f(INVERSE_PROJECTION)
                .mul(new Matrix4f().translate(-2.0f * jitterX, -2.0f * jitterY, 0.0f));
    }

    private static final float[] JITTER_PHASES = {
            0.0f, 0.3f, -0.3f, 0.15f, -0.15f, 0.25f, -0.25f, 0.05f};

    // ------------------------------------------------------------------
    // Analytic fixtures (all references are CPU-side)
    // ------------------------------------------------------------------

    private static float[] supersampledScene(int width, int height, int samples) {
        float[] pixels = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] colour = new double[3];
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        double u = (x + (sx + 0.5) / samples) / width;
                        double v = (y + (sy + 0.5) / samples) / height;
                        double[] sample = smoothScene(u, v);
                        colour[0] += sample[0];
                        colour[1] += sample[1];
                        colour[2] += sample[2];
                    }
                }
                int offset = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    pixels[offset + channel] = (float) (colour[channel]
                            / (samples * samples));
                }
                pixels[offset + 3] = 1.0f;
            }
        }
        return pixels;
    }

    private static double[] smoothScene(double u, double v) {
        double red = 0.30 + 0.40 * u;
        double green = 0.25 + 0.30 * v;
        double blue = 0.20 + 0.20 * (1.0 - u);
        double dx = u - 0.62;
        double dy = v - 0.42;
        if (dx * dx + dy * dy < 0.12 * 0.12) {
            red += 0.35;
            green += 0.15;
            blue -= 0.10;
        }
        if (Math.abs(v - 0.78) < 0.03) {
            red += 0.25;
            green += 0.25;
            blue += 0.25;
        }
        return new double[]{clamp01(red), clamp01(green), clamp01(blue)};
    }

    private static float[] jitteredHighFrequencyScene(float jitterX, float jitterY) {
        float[] pixels = new float[INPUT_WIDTH * INPUT_HEIGHT * 4];
        for (int y = 0; y < INPUT_HEIGHT; y++) {
            for (int x = 0; x < INPUT_WIDTH; x++) {
                double u = (x + 0.5) / INPUT_WIDTH - jitterX;
                double v = (y + 0.5) / INPUT_HEIGHT - jitterY;
                double[] colour = highFrequencyScene(u, v, 0.0);
                int offset = (y * INPUT_WIDTH + x) * 4;
                pixels[offset] = (float) colour[0];
                pixels[offset + 1] = (float) colour[1];
                pixels[offset + 2] = (float) colour[2];
                pixels[offset + 3] = 1.0f;
            }
        }
        return pixels;
    }

    private static float[] highFrequencyScene(int width, int height, int samples,
                                              double phase) {
        float[] pixels = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] colour = new double[3];
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        double u = (x + (sx + 0.5) / samples) / width;
                        double v = (y + (sy + 0.5) / samples) / height;
                        double[] sample = highFrequencyScene(u, v, phase);
                        colour[0] += sample[0];
                        colour[1] += sample[1];
                        colour[2] += sample[2];
                    }
                }
                int offset = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    pixels[offset + channel] = (float) (colour[channel]
                            / (samples * samples));
                }
                pixels[offset + 3] = 1.0f;
            }
        }
        return pixels;
    }

    private static double[] highFrequencyScene(double u, double v, double phase) {
        double signal = 0.5 + 0.35
                * Math.sin(2.0 * Math.PI * (18.0 * u + phase))
                * Math.cos(2.0 * Math.PI * 10.0 * v);
        return new double[]{clamp01(signal),
                clamp01(0.5 + 0.30 * (signal - 0.5)),
                clamp01(0.5 + 0.20 * (signal - 0.5))};
    }

    private static float[] worldScene(int width, int height, float cameraX, int samples) {
        float[] pixels = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] colour = new double[3];
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        double u = (x + (sx + 0.5) / samples) / width;
                        double v = (y + (sy + 0.5) / samples) / height;
                        double ndcX = u * 2.0 - 1.0;
                        double ndcY = v * 2.0 - 1.0;
                        double worldX = ndcX * TAN_HALF_FOV * PLANE_VIEW_DEPTH + cameraX;
                        double worldY = ndcY * TAN_HALF_FOV * PLANE_VIEW_DEPTH;
                        double[] sample = worldPattern(worldX, worldY);
                        colour[0] += sample[0];
                        colour[1] += sample[1];
                        colour[2] += sample[2];
                    }
                }
                int offset = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    pixels[offset + channel] = (float) (colour[channel]
                            / (samples * samples));
                }
                pixels[offset + 3] = 1.0f;
            }
        }
        return pixels;
    }

    private static double[] worldPattern(double worldX, double worldY) {
        double signal = 0.5 + 0.30
                * Math.sin(2.0 * Math.PI * worldX / 0.19)
                * Math.cos(2.0 * Math.PI * worldY / 0.45);
        double bar = worldX > -0.25 && worldX < 0.05 ? 0.35 : 0.0;
        return new double[]{clamp01(signal + bar),
                clamp01(0.5 + 0.25 * (signal - 0.5) + bar),
                clamp01(0.5 + 0.20 * (signal - 0.5))};
    }

    private static float[] objectScene(int width, int height, float barCentrePixels,
                                       int samples) {
        float[] pixels = new float[width * height * 4];
        int halfBar = Math.max(1, width / 16);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] colour = new double[3];
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        double sampleX = x + (sx + 0.5) / samples;
                        double sampleY = y + (sy + 0.5) / samples;
                        boolean inside = Math.abs(sampleX - barCentrePixels) <= halfBar;
                        colour[0] += inside ? 0.90 : 0.12 + 0.10 * sampleY / height;
                        colour[1] += inside ? 0.20 : 0.10 + 0.05 * sampleY / height;
                        colour[2] += inside ? 0.10 : 0.30 - 0.10 * sampleX / width;
                    }
                }
                int offset = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    pixels[offset + channel] = (float) clamp01(colour[channel]
                            / (samples * samples));
                }
                pixels[offset + 3] = 1.0f;
            }
        }
        return pixels;
    }

    private static float[] constantVelocity(int width, int height, float x, float y) {
        float[] velocity = new float[width * height * 2];
        for (int pixel = 0; pixel < width * height; pixel++) {
            velocity[pixel * 2] = x;
            velocity[pixel * 2 + 1] = y;
        }
        return velocity;
    }

    // ------------------------------------------------------------------
    // Numeric helpers
    // ------------------------------------------------------------------

    /**
     * Manual CPU bilinear upsample of the low-resolution RGBA fixture to the
     * output grid.  The sample position is shifted by the frame jitter exactly
     * like the production {@code outputUv + uCurrentJitter}: a jittered fixture
     * carries its content at {@code texel centre - jitter}, so the stable output
     * position is only recovered when the sample offset is included.
     */
    private static float[] bilinearUpsample(float[] inputRgba, float jitterU, float jitterV) {
        double[] source = toDoubles(inputRgba);
        float[] output = new float[OUTPUT_WIDTH * OUTPUT_HEIGHT * 4];
        for (int y = 0; y < OUTPUT_HEIGHT; y++) {
            for (int x = 0; x < OUTPUT_WIDTH; x++) {
                double[] sample = TemporalUpscalingReference.bilinearRgba(source,
                        INPUT_WIDTH, INPUT_HEIGHT,
                        (x + 0.5) / OUTPUT_WIDTH + jitterU,
                        (y + 0.5) / OUTPUT_HEIGHT + jitterV);
                int offset = (y * OUTPUT_WIDTH + x) * 4;
                output[offset] = (float) sample[0];
                output[offset + 1] = (float) sample[1];
                output[offset + 2] = (float) sample[2];
                output[offset + 3] = (float) sample[3];
            }
        }
        return output;
    }

    private static double meanAbsoluteError(float[] actual, float[] reference) {
        double sum = 0.0;
        int pixels = reference.length / 4;
        for (int pixel = 0; pixel < pixels; pixel++) {
            int offset = pixel * 4;
            for (int channel = 0; channel < 3; channel++) {
                sum += Math.abs(actual[offset + channel] - reference[offset + channel]);
            }
        }
        return sum / (pixels * 3.0);
    }

    private static double maximumChannelError(float[] actual, float[] reference) {
        double maximum = 0.0;
        for (int index = 0; index < reference.length; index++) {
            if (index % 4 == 3) continue;
            maximum = Math.max(maximum, Math.abs(actual[index] - reference[index]));
        }
        return maximum;
    }

    /**
     * Error over pixels that contain a strong analytic edge in the native
     * reference (the disc and the horizontal band).
     */
    private static double edgeBandError(float[] actual, float[] reference) {
        double sum = 0.0;
        int count = 0;
        for (int y = 0; y < OUTPUT_HEIGHT; y++) {
            for (int x = 0; x < OUTPUT_WIDTH; x++) {
                int offset = (y * OUTPUT_WIDTH + x) * 4;
                double u = (x + 0.5) / OUTPUT_WIDTH;
                double v = (y + 0.5) / OUTPUT_HEIGHT;
                double dx = u - 0.62;
                double dy = v - 0.42;
                boolean discEdge = Math.abs(Math.sqrt(dx * dx + dy * dy) - 0.12) < 0.04;
                boolean bandEdge = Math.abs(Math.abs(v - 0.78) - 0.03) < 0.02;
                if (!discEdge && !bandEdge) continue;
                for (int channel = 0; channel < 3; channel++) {
                    sum += Math.abs(actual[offset + channel] - reference[offset + channel]);
                }
                count += 3;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static void assertFinite(float[] pixels) {
        for (float value : pixels) {
            assertTrue(Float.isFinite(value), "upscaled output must be finite");
        }
    }

    /**
     * One-input-pixel checkerboard.  A 3x3 neighbourhood always spans both
     * values, so the temporal clamp cannot hide an accept/reject decision.
     */
    private static float[] checkerScene(int width, int height, float high, float low,
                                        boolean flip) {
        float[] pixels = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean even = ((x + y) & 1) == 0;
                float value = even == flip ? low : high;
                int offset = (y * width + x) * 4;
                pixels[offset] = value;
                pixels[offset + 1] = value;
                pixels[offset + 2] = value;
                pixels[offset + 3] = 1.0f;
            }
        }
        return pixels;
    }

    /** Checkerboard with red and blue cells; pixel (0, 0) is blue. */
    private static float[] redBlueCheckerScene(int width, int height) {
        float[] pixels = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean blue = ((x + y) & 1) == 0;
                int offset = (y * width + x) * 4;
                pixels[offset] = blue ? 0.0f : 0.8f;
                pixels[offset + 2] = blue ? 0.8f : 0.0f;
                pixels[offset + 3] = 1.0f;
            }
        }
        return pixels;
    }

    private static float[] uniformScene(int width, int height, float red, float green,
                                        float blue) {        float[] pixels = new float[width * height * 4];
        for (int pixel = 0; pixel < width * height; pixel++) {
            int offset = pixel * 4;
            pixels[offset] = red;
            pixels[offset + 1] = green;
            pixels[offset + 2] = blue;
            pixels[offset + 3] = 1.0f;
        }
        return pixels;
    }

    private static float[] constantDepth(int width, int height, float depth) {
        float[] values = new float[width * height];
        Arrays.fill(values, depth);
        return values;
    }

    private static double[] toDoubles(float[] values) {
        double[] converted = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            converted[index] = values[index];
        }
        return converted;
    }

    private static float[] widenVelocity(float[] velocity, int pixels) {        float[] widened = new float[pixels * 4];
        for (int pixel = 0; pixel < pixels; pixel++) {
            widened[pixel * 4] = velocity[pixel * 2];
            widened[pixel * 4 + 1] = velocity[pixel * 2 + 1];
            widened[pixel * 4 + 3] = 1.0f;
        }
        return widened;
    }

    private static float deviceDepth(Matrix4f projection, float viewDepth) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDepth, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static double clamp01(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    // ------------------------------------------------------------------
    // GL resources and readback
    // ------------------------------------------------------------------

    private static Texture2D floatTexture(int format) {
        Texture2D texture = Texture2D.createEmpty(OUTPUT_WIDTH, OUTPUT_HEIGHT, format);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static Texture2D byteTexture() {
        Texture2D texture = Texture2D.createEmpty(OUTPUT_WIDTH, OUTPUT_HEIGHT, GL_R8);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static Texture2D colorTexture(int width, int height) {
        Texture2D texture = Texture2D.createEmpty(width, height, GL_RGBA32F);
        texture.setFiltering(GL_LINEAR, GL_LINEAR);
        return texture;
    }

    private static Texture2D depthTexture(int width, int height, float depth) {
        Texture2D texture = Texture2D.createEmpty(width, height, GL_R32F);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        uploadFloat(texture, 1, width, height, constantDepth(width, height, depth));
        return texture;
    }

    private static void uploadFloat(Texture2D texture, int channels, int width, int height,
                                    float[] data) {
        ByteBuffer upload = BufferUtils.createByteBuffer(
                width * height * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        upload.limit(width * height * channels * Float.BYTES);
        texture.uploadRegion(0, 0, width, height, upload);
    }

    private static void uploadBytes(Texture2D texture, int width, int height, byte[] data) {
        ByteBuffer upload = BufferUtils.createByteBuffer(width * height);
        upload.put(data);
        upload.flip();
        texture.uploadRegion(0, 0, width, height, upload);
    }

    private static float[] readFloat(Framebuffer framebuffer, int width, int height) {
        framebuffer.bind();
        glViewport(0, 0, width, height);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[width * height * 4];
        pixels.get(result);
        return result;
    }

    private static byte[] readBytes(Framebuffer framebuffer, int width, int height) {
        framebuffer.bind();
        glViewport(0, 0, width, height);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        byte[] result = new byte[width * height * 4];
        pixels.get(result);
        return result;
    }

    // ------------------------------------------------------------------
    // Colour-chain baseline and pipeline wiring
    // ------------------------------------------------------------------

    private record RenderedImage(byte[] bytes, java.util.List<String> executionOrder) {
    }

    private static RenderedImage renderBaseline(RenderSettings settings,
                                                PostProcessSettings effects) {
        GlRenderDevice device = new GlRenderDevice();
        try (Framebuffer target = Framebuffer.colorOnly(OUTPUT_WIDTH, OUTPUT_HEIGHT);
             PostProcessPipeline pipeline = PostProcessPipeline.create(settings, effects,
                     OUTPUT_WIDTH, OUTPUT_HEIGHT, null, NO_SURFACE, null);
             RenderGraph graph = new RenderGraph(OUTPUT_WIDTH, OUTPUT_HEIGHT)) {
            if (!graph.hasPass(PostProcessTargets.GEOMETRY_PASS)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> commands
                                .clearColor(0.2f, 0.4f, 0.8f, 1.0f)
                                .clear(true, false));
            }
            pipeline.beginFrame(pose(0.0f, 0.0f));
            pipeline.addFinalPass(graph);
            pipeline.synchronizeTemporalImports(graph);
            PresentationTarget presentation = PresentationTarget.borrowed(target.id(),
                    ExternalAttachment.borrowedColor(target.colorAttachment(0),
                            RenderFormat.RGBA8, OUTPUT_WIDTH, OUTPUT_HEIGHT),
                    null, OUTPUT_WIDTH, OUTPUT_HEIGHT, 1L);
            graph.compile();
            device.invalidateState();
            graph.execute(device, presentation);
            pipeline.frameSucceeded();
            return new RenderedImage(readBytes(target, OUTPUT_WIDTH, OUTPUT_HEIGHT),
                    graph.description().executionOrder());
        }
    }

    private static TemporalFramePose pose(float jitterX, float jitterY) {
        return new TemporalFramePose(1.0f / 60.0f, OUTPUT_WIDTH, OUTPUT_HEIGHT,
                jitterX, jitterY, INVERSE_PROJECTION, IDENTITY, PROJECTION, null, null);
    }

    private static RenderSettings hdrSettings() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
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

    // ------------------------------------------------------------------
    // Window and device identity
    // ------------------------------------------------------------------

    private static GlfwWindow window(String title) {
        GlfwWindow window = new GlfwWindow.Builder().dimensions(OUTPUT_WIDTH,
                OUTPUT_HEIGHT).title(title).visible(false).build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        recordDevice();
        return window;
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
            Files.writeString(directory.resolve("temporal-upscaling-device.txt"),
                    text.toString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot record the temporal-upscaling GL device identity", failure);
        }
    }
}
