package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetTexImage;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RG32F;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL acceptance for the optional GI temporal accumulation of #39.
 *
 * <p>The fixture is the same analytic receiver-hole as {@code SsgiPassGlTest},
 * extended with the shared single-sample velocity/previous-depth/validity
 * channels. Expected values come from {@link SsgiTemporalReference}, which
 * evaluates the independent double-precision reprojection, rejection, clamp and
 * blend equations; no expected pixel is copied from a previous GPU render.</p>
 *
 * <p>Guarded by {@code -Dhaikalat.glSmoke=true}; a plain {@code test} run skips
 * it, so it is not evidence until the serialized GPU queue executes it.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SsgiTemporalGlTest {
    private static final int SIZE = 48;
    private static final int PROBE_X = SIZE / 2;
    private static final int PROBE_Y = SIZE / 2;
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(55.0);
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(FIELD_OF_VIEW,
            1.0f, 0.1f, 100.0f);
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();
    private static final Matrix4d REFERENCE_PROJECTION = new Matrix4d(PROJECTION);
    private static final SsgiIndirectReference.Camera CAMERA =
            SsgiIndirectReference.Camera.identity(REFERENCE_PROJECTION);
    private static final SsgiIndirectReference.Environment NO_ENVIRONMENT =
            SsgiIndirectReference.Environment.unavailable();
    private static final float TOLERANCE = 0.02f;
    /** First receiver-plane column for the jitter counterexample. */
    private static final int RECEIVER_START = 23;
    /** Half-width of the six-metre receiver row used by the convergence case. */
    private static final int RECEIVER_WINDOW_HALF = 1;
    /** Alternating TAA sub-pixel jitter magnitude in output pixels. */
    private static final float JITTER_PIXELS = 0.5f;
    private static final float JITTER_UV = JITTER_PIXELS / SIZE;
    /** Three-period receiver albedo pattern: a 3x3 window spans the full range. */
    private static final float[] ALBEDO_PATTERN = {0.95f, 0.10f, 0.55f};

    private static final SsgiSettings TEMPORAL = SsgiSettings.builder()
            .enabled(true).sampleCount(1).stepCount(8).maxDistanceMetres(8.0f)
            .thicknessMetres(1.0f).strength(1.0f).maximumOffscreenFraction(0.0f)
            .edgeFadePixels(0.0f).ambientFallback(0.0f, 0.0f, 0.0f)
            .temporalBlend(0.9f).historyFrames(64).temporalDepthRejection(0.02f)
            .temporalNormalRejection(0.9f).temporalVelocityRejection(64.0f).build();
    /**
     * Four-sample convergence fixture settings: the extra hemisphere samples
     * reduce the per-frame rotation variance so the jittered and unjittered
     * recurrences can be compared without their independent residuals
     * dominating the difference.
     */
    private static final SsgiSettings TEMPORAL_SMOOTH = TEMPORAL.withSampleCount(4)
            .withTemporalBlend(0.95f);

    @Test
    void temporalAccumulationConvergesTowardTheHighSampleReference() {
        try (GlfwWindow window = window("SSGI temporal convergence")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.directional(2.0f);
            try (Textures textures = new Textures(fixture);
                 SsgiPass pass = new SsgiPass(TEMPORAL, SIZE, SIZE)) {
                float[] first = null;
                float[] last = null;
                for (int frame = 0; frame < 64; frame++) {
                    device.invalidateState();
                    float[] output = runFrame(pass, device, textures, identityView());
                    if (frame == 0) first = output;
                    if (frame == 63) last = output;
                }
                double[] expected = SsgiTemporalReference.temporalPrediction(
                        fixture.scene(), CAMERA, TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y, 64);
                double[] average = SsgiTemporalReference.rotationAverage(
                        fixture.scene(), CAMERA, TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y, 512);
                double[] base = SsgiTemporalReference.sceneBase(fixture.scene(), PROBE_X,
                        PROBE_Y);
                double[] expectedComposite = SsgiTemporalReference.composite(base, expected);
                assertTrue(pass.historyValid(), "a successful frame must commit the history");
                assertTrue(pass.historyAcceptedFrames() >= 64,
                        "the retained-sample counter must reach the configured horizon");
                assertMatches(last, PROBE_X, PROBE_Y, expectedComposite,
                        "accumulated frame must match the independent recurrence");
                double firstError = error(first, PROBE_X, PROBE_Y, average, base);
                double lastError = error(last, PROBE_X, PROBE_Y, average, base);
                assertTrue(firstError > 1.0e-3,
                        "the first frame must carry the one-sample rotation variance");
                assertTrue(lastError < firstError * 0.6,
                        "temporal accumulation must converge toward the rotation average: "
                                + lastError + " vs " + firstError);
                assertTrue(lastError < 0.05,
                        "the converged residual must stay bounded: " + lastError);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void nonzeroJitterReprojectsToTheStableNeighbourAndRejectsTheCounterfactuals() {
        try (GlfwWindow window = window("SSGI nonzero jitter reprojection")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.patternedReceiver(2.0f);
            try (Textures textures = new Textures(fixture);
                 SsgiPass pass = new SsgiPass(TEMPORAL, SIZE, SIZE)) {
                int frames = 8;
                float[][] actual = new float[frames][];
                for (int frame = 0; frame < frames; frame++) {
                    device.invalidateState();
                    actual[frame] = runFrame(pass, device, textures, identityView(),
                            jitterUvX(frame), 0.0f);
                }
                assertEquals(frames, pass.historyAcceptedFrames(),
                        "alternating sub-pixel jitter must not look like a camera cut");
                assertTrue(pass.historyValid());

                double[][] contract = jitteredPrediction(fixture, frames,
                        Reprojection.CONTRACT, TEMPORAL);
                double[][] missing = jitteredPrediction(fixture, frames,
                        Reprojection.MISSING_JITTER, TEMPORAL);
                double[][] flipped = jitteredPrediction(fixture, frames,
                        Reprojection.FLIPPED_JITTER, TEMPORAL);

                // Frame 1 reads x+1, frame 2 x-1: the contract recurrence is
                // exact, and both wrong formulas address the history at the
                // current pixel or the mirrored neighbour instead.
                for (int frame : new int[]{1, 2, 5, 7}) {
                    assertMatches(actual[frame], PROBE_X, PROBE_Y, contract[frame],
                            "frame " + frame + " must follow the jitter-corrected recurrence");
                }
                assertDiffers(actual[frames - 1], PROBE_X, PROBE_Y,
                        missing[frames - 1], 0.03,
                        "the jitter-omitting history address must not reproduce the contract");
                assertDiffers(actual[frames - 1], PROBE_X, PROBE_Y,
                        flipped[frames - 1], 0.03,
                        "a reversed jitter sign must not reproduce the contract");
                assertTrue(distance(contract[frames - 1], missing[frames - 1]) > 0.03,
                        "the counterfactuals must be distinguishable from the contract");
                assertTrue(distance(contract[frames - 1], flipped[frames - 1]) > 0.03,
                        "the two counterfactuals must be distinguishable from each other");

                // Reveal a nearer surface at the probe while the jitter keeps
                // alternating. The jitter-corrected history tap is a six-metre
                // receiver pixel, but the previous-depth channel now expects
                // three metres, so the stale history must be rejected rather
                // than blended through the new jitter address.
                Fixture reveal = fixture.withReceiverDepth(3.0f);
                textures.upload(reveal);
                device.invalidateState();
                float[] afterReveal = runFrame(pass, device, textures, identityView(),
                        jitterUvX(frames), 0.0f);
                double[] revealSpatial = SsgiTemporalReference.spatialAdded(
                        reveal.scene(), CAMERA, TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y,
                        TEMPORAL.sampleCount(),
                        SsgiTemporalReference.rotationForFrame(frames));
                double[] revealExpected = SsgiTemporalReference.composite(
                        asDouble(reveal.base(PROBE_X, PROBE_Y)), revealSpatial);
                assertMatches(afterReveal, PROBE_X, PROBE_Y, revealExpected,
                        "a depth reveal under nonzero jitter must fall back to the spatial sample");
                double ghostWeight = SsgiTemporalReference.historyWeight(
                        TEMPORAL.temporalBlend(), TEMPORAL.historyFrames(), frames + 1);
                double[] ghost = SsgiTemporalReference.composite(
                        asDouble(reveal.base(PROBE_X, PROBE_Y)),
                        SsgiTemporalReference.blend(
                                contract[frames - 1], revealSpatial, ghostWeight));
                assertTrue(distance(asDouble(pixel(afterReveal, PROBE_X, PROBE_Y)), ghost) > 0.1,
                        "the jittered reveal must not blend the stale six-metre history");
                assertEquals(frames + 1, pass.historyAcceptedFrames(),
                        "the reveal frame is still committed with one retained sample");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void nonzeroJitterConvergesLikeTheUnjitteredReferenceAndCutStillResets() {
        try (GlfwWindow window = window("SSGI nonzero jitter convergence")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.windowPlate(2.0f);
            try (Textures textures = new Textures(fixture);
                 SsgiPass pass = new SsgiPass(TEMPORAL_SMOOTH, SIZE, SIZE)) {
                float[][] outputs = new float[64][];
                for (int frame = 0; frame < outputs.length; frame++) {
                    device.invalidateState();
                    outputs[frame] = runFrame(pass, device, textures, identityView(),
                            jitterUvX(frame), 0.0f);
                }
                assertTrue(pass.historyValid());
                assertEquals(64, pass.historyAcceptedFrames(),
                        "alternating jitter must keep committing history");
                double[] jitteredAverage = averagePixels(outputs, 56, 64, PROBE_X, PROBE_Y);
                assertTrue(distance(jitteredAverage,
                                averagePixels(outputs, 48, 56, PROBE_X, PROBE_Y)) < 0.02,
                        "the jittered history must be settled after the retained-sample ramp");
                assertTrue(distance(asDouble(pixel(outputs[0], PROBE_X, PROBE_Y)),
                                jitteredAverage) > 1.0e-3,
                        "the first jittered frame must carry the one-sample rotation variance");

                // Zero-jitter control on the same smooth fixture: the
                // jitter-corrected accumulation must settle to the same value.
                // The fixture intentionally gives the +/-1 pixel jitter taps
                // nearly identical spatial GI, so the comparison is about the
                // accumulation contract, not a different scene.
                float[][] zeroOutputs = new float[64][];
                try (SsgiPass unjittered = new SsgiPass(TEMPORAL_SMOOTH, SIZE, SIZE)) {
                    for (int frame = 0; frame < zeroOutputs.length; frame++) {
                        device.invalidateState();
                        zeroOutputs[frame] = runFrame(unjittered, device, textures,
                                identityView());
                    }
                    assertEquals(64, unjittered.historyAcceptedFrames());
                }
                double[] zeroGpuAverage = averagePixels(zeroOutputs, 56, 64, PROBE_X, PROBE_Y);
                assertTrue(distance(zeroGpuAverage,
                                averagePixels(zeroOutputs, 48, 56, PROBE_X, PROBE_Y)) < 0.02,
                        "the zero-jitter control must be settled after the retained-sample ramp");
                assertTrue(distance(jitteredAverage, zeroGpuAverage) < 0.1,
                        "a smooth receiver must converge to the same value with or without "
                                + "nonzero jitter: " + distance(jitteredAverage, zeroGpuAverage));

                // A camera cut with nonzero jitter must still discard the whole
                // history and restart from the spatial sample.
                Fixture dark = Fixture.uniform(0.05f);
                textures.upload(dark);
                Matrix4f view = new Matrix4f().translate(-6.0f, 0.0f, 0.0f);
                Matrix4f inverseView = new Matrix4f().translate(6.0f, 0.0f, 0.0f);
                device.invalidateState();
                float[] afterCut = runFrame(pass, device, textures,
                        new View(view, inverseView), jitterUvX(64), -JITTER_UV * 0.5f);
                assertEquals(1, pass.historyAcceptedFrames(),
                        "a nonzero-jitter camera cut must reset the retained-sample count");
                double[] cutSpatial = SsgiTemporalReference.spatialAdded(dark.scene(), CAMERA,
                        TEMPORAL_SMOOTH, NO_ENVIRONMENT, PROBE_X, PROBE_Y,
                        TEMPORAL_SMOOTH.sampleCount(),
                        SsgiTemporalReference.rotationForFrame(0));
                assertMatches(afterCut, PROBE_X, PROBE_Y,
                        SsgiTemporalReference.composite(
                                asDouble(dark.base(PROBE_X, PROBE_Y)), cutSpatial),
                        "the first cut frame must be spatial even with nonzero jitter");
            }

            // The disabled temporal path stays byte-identical to the pre-temporal
            // spatial output even when the caller has nonzero jitter available.
            SsgiSettings spatial = SsgiSettings.defaults();
            SsgiSettings zeroBlend = SsgiSettings.defaults().withTemporalBlend(0.0f)
                    .withHistoryFrames(32).withTemporalDepthRejection(0.05f);
            try (Textures textures = new Textures(Fixture.patternedReceiver(2.0f));
                 SsgiPass baseline = new SsgiPass(spatial);
                 SsgiPass disabledTemporal = new SsgiPass(zeroBlend, SIZE, SIZE);
                 Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F)) {
                assertFalse(disabledTemporal.temporalActive(),
                        "a zero blend must not allocate a jitter-consuming temporal stage");
                float[] reference = renderSpatial(device, baseline, textures, target);
                float[] zero = renderSpatial(device, disabledTemporal, textures, target);
                assertArrayEquals(reference, zero, 0.0f,
                        "disabling temporal must keep the exact spatial output");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void dynamicRevealRejectsStaleHistoryInsteadOfGhosting() {
        try (GlfwWindow window = window("SSGI temporal reveal")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture bright = Fixture.uniform(2.0f);
            try (Textures textures = new Textures(bright);
                 SsgiPass pass = new SsgiPass(TEMPORAL, SIZE, SIZE)) {
                for (int frame = 0; frame < 8; frame++) {
                    device.invalidateState();
                    runFrame(pass, device, textures, identityView());
                }
                float[] settled = readAttachment(pass.readFramebuffer(), 2);
                float[] settledBase = bright.base(PROBE_X, PROBE_Y);
                double[] settledAdded = added(settled, settledBase);
                assertTrue(settledAdded[0] > 0.05,
                        "the settled history must contain the bright indirect bounce");

                // Reveal a nearer surface at the probe. The history state still
                // holds the old surface depth, so the reprojected history tap is
                // rejected instead of blended into the new surface.
                Fixture reveal = Fixture.uniform(2.0f).withReceiverDepth(3.0f);
                textures.upload(reveal);
                device.invalidateState();
                float[] afterReveal = runFrame(pass, device, textures, identityView());
                double[] expectedSpatial = SsgiTemporalReference.spatialAdded(
                        reveal.scene(), CAMERA, TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y,
                        TEMPORAL.sampleCount(),
                        SsgiTemporalReference.rotationForFrame(8));
                double[] expected = SsgiTemporalReference.composite(
                        asDouble(reveal.base(PROBE_X, PROBE_Y)), expectedSpatial);
                assertMatches(afterReveal, PROBE_X, PROBE_Y, expected,
                        "a depth disocclusion must fall back to the current spatial sample");
                double weight = SsgiTemporalReference.historyWeight(TEMPORAL.temporalBlend(),
                        TEMPORAL.historyFrames(), 9);
                double[] ghostedAdded = SsgiTemporalReference.blend(settledAdded,
                        expectedSpatial, weight);
                double ghost = reveal.base(PROBE_X, PROBE_Y)[0] + ghostedAdded[0];
                double actual = pixel(afterReveal, PROBE_X, PROBE_Y)[0];
                assertTrue(Math.abs(actual - ghost) > 0.1,
                        "the rejected output must differ from the ghosted blend " + ghost);

                // The same depth with a flipped normal is a different surface
                // even when the reprojection itself is unchanged.
                Fixture flipped = Fixture.uniform(2.0f).withReceiverNormal(1.0f, 0.0f);
                textures.upload(flipped);
                device.invalidateState();
                float[] afterFlip = runFrame(pass, device, textures, identityView());
                double[] flippedSpatial = SsgiTemporalReference.spatialAdded(
                        flipped.scene(), CAMERA, TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y,
                        TEMPORAL.sampleCount(),
                        SsgiTemporalReference.rotationForFrame(9));
                assertMatches(afterFlip, PROBE_X, PROBE_Y,
                        SsgiTemporalReference.composite(asDouble(flipped.base(PROBE_X, PROBE_Y)),
                                flippedSpatial),
                        "a normal mismatch must reject the stale history");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void brightnessStepConvergesWithoutPermanentGhosting() {
        try (GlfwWindow window = window("SSGI temporal brightness step")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture bright = Fixture.plate(2.0f);
            try (Textures textures = new Textures(bright);
                 SsgiPass pass = new SsgiPass(TEMPORAL, SIZE, SIZE)) {
                for (int frame = 0; frame < 8; frame++) {
                    device.invalidateState();
                    runFrame(pass, device, textures, identityView());
                }
                double[] brightAdded = added(readAttachment(pass.readFramebuffer(), 2),
                        bright.base(PROBE_X, PROBE_Y));
                assertTrue(brightAdded[0] > 0.05, "the history must hold the bright bounce");

                Fixture dim = Fixture.plate(0.05f);
                textures.upload(dim);
                float[] afterDim = null;
                for (int frame = 8; frame < 10; frame++) {
                    device.invalidateState();
                    afterDim = runFrame(pass, device, textures, identityView());
                }
                double[] dimSpatial = SsgiTemporalReference.spatialAdded(dim.scene(), CAMERA,
                        TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y, TEMPORAL.sampleCount(),
                        SsgiTemporalReference.rotationForFrame(9));
                double[] dimExpected = SsgiTemporalReference.composite(
                        asDouble(dim.base(PROBE_X, PROBE_Y)), dimSpatial);
                assertMatches(afterDim, PROBE_X, PROBE_Y, dimExpected,
                        "a dark brightness step must clamp the stale bright history in one frame");
                double dimAdded = pixel(afterDim, PROBE_X, PROBE_Y)[0]
                        - dim.base(PROBE_X, PROBE_Y)[0];
                assertTrue(dimAdded < brightAdded[0] * 0.25,
                        "the old bright bounce must not survive the dim step: "
                                + dimAdded + " vs " + brightAdded[0]);

                Fixture brightAgain = Fixture.plate(2.0f);
                textures.upload(brightAgain);
                float[] afterBright = null;
                // A dark history converging upward is not ghosting; the
                // neighbourhood clamp only bounds bright stale values. The EMA
                // must still settle on the new bright signal without leaving a
                // permanent dark residue.
                for (int frame = 10; frame < 58; frame++) {
                    device.invalidateState();
                    afterBright = runFrame(pass, device, textures, identityView());
                }
                double[] brightAverage = SsgiTemporalReference.rotationAverage(
                        brightAgain.scene(), CAMERA, TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y,
                        512);
                double[] brightExpected = SsgiTemporalReference.composite(
                        asDouble(brightAgain.base(PROBE_X, PROBE_Y)), brightAverage);
                assertMatches(afterBright, PROBE_X, PROBE_Y, brightExpected,
                        "a bright step must converge to the new bounce without ghosting");
                double finalAdded = pixel(afterBright, PROBE_X, PROBE_Y)[0]
                        - brightAgain.base(PROBE_X, PROBE_Y)[0];
                assertTrue(finalAdded > brightAdded[0] * 0.75,
                        "the new bright bounce must recover after the step: "
                                + finalAdded + " vs " + brightAdded[0]);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void cameraCutResetsTheWholeHistory() {
        try (GlfwWindow window = window("SSGI temporal camera cut")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture bright = Fixture.uniform(2.0f);
            try (Textures textures = new Textures(bright);
                 SsgiPass pass = new SsgiPass(TEMPORAL, SIZE, SIZE)) {
                for (int frame = 0; frame < 8; frame++) {
                    device.invalidateState();
                    runFrame(pass, device, textures, identityView());
                }
                assertEquals(8, pass.historyAcceptedFrames());
                double[] settledAdded = added(readAttachment(pass.readFramebuffer(), 2),
                        bright.base(PROBE_X, PROBE_Y));

                Fixture dark = Fixture.uniform(0.05f);
                textures.upload(dark);
                Matrix4f view = new Matrix4f().translate(-6.0f, 0.0f, 0.0f);
                Matrix4f inverseView = new Matrix4f().translate(6.0f, 0.0f, 0.0f);
                device.invalidateState();
                float[] afterCut = runFrame(pass, device, textures,
                        new View(view, inverseView));
                assertEquals(1, pass.historyAcceptedFrames(),
                        "a camera cut must discard the retained-sample count");
                double[] spatial = SsgiTemporalReference.spatialAdded(dark.scene(), CAMERA,
                        TEMPORAL, NO_ENVIRONMENT, PROBE_X, PROBE_Y, TEMPORAL.sampleCount(),
                        SsgiTemporalReference.rotationForFrame(0));
                assertMatches(afterCut, PROBE_X, PROBE_Y,
                        SsgiTemporalReference.composite(asDouble(dark.base(PROBE_X, PROBE_Y)),
                                spatial),
                        "the first cut frame must be spatial, not a blend with old history");
                double weight = SsgiTemporalReference.historyWeight(TEMPORAL.temporalBlend(),
                        TEMPORAL.historyFrames(), 9);
                double ghost = dark.base(PROBE_X, PROBE_Y)[0]
                        + SsgiTemporalReference.blend(settledAdded, spatial, weight)[0];
                assertTrue(Math.abs(pixel(afterCut, PROBE_X, PROBE_Y)[0] - ghost) > 0.1,
                        "the cut output must differ from a ghosted blend " + ghost);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void zeroBlendIsByteIdenticalAndAllocatesNoHistory() {
        try (GlfwWindow window = window("SSGI temporal disabled")) {
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.directional(2.0f);
            SsgiSettings spatial = SsgiSettings.defaults();
            SsgiSettings zeroBlend = SsgiSettings.defaults().withTemporalBlend(0.0f)
                    .withHistoryFrames(32).withTemporalDepthRejection(0.05f);
            try (Textures textures = new Textures(fixture);
                 SsgiPass baseline = new SsgiPass(spatial);
                 SsgiPass compatible = new SsgiPass(SsgiSettings.defaults());
                 SsgiPass disabledTemporal = new SsgiPass(zeroBlend, SIZE, SIZE);
                 Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F)) {
                assertFalse(disabledTemporal.temporalActive(),
                        "a zero blend must not allocate the temporal program or history");
                assertFalse(disabledTemporal.historyValid());
                assertEquals(0, disabledTemporal.historyAcceptedFrames());
                assertThrows(IllegalStateException.class,
                        disabledTemporal::writeFramebuffer,
                        "a still pass must not expose a history target");
                float[] reference = renderSpatial(device, baseline, textures, target);
                float[] oldConstructor = renderSpatial(device, compatible, textures, target);
                float[] zero = renderSpatial(device, disabledTemporal, textures, target);
                assertArrayEquals(reference, oldConstructor, 0.0f,
                        "the pre-temporal constructor must produce the exact same pixels");
                assertArrayEquals(reference, zero, 0.0f,
                        "temporalBlend == 0 must be the exact pre-existing spatial output");
                assertThrows(IllegalArgumentException.class,
                        () -> new SsgiPass(TEMPORAL, 0, 0),
                        "a temporal request without an extent must fail closed");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void resizeFailureRollbackAndCloseLeaveNoLeakedResourcesOrDepthWrites() {
        try (GlfwWindow window = window("SSGI temporal lifecycle")) {
            int baselineResources = GlDebug.resources().liveResources().size();
            GlRenderDevice device = new GlRenderDevice();
            Fixture fixture = Fixture.uniform(1.0f);
            try (Textures textures = new Textures(fixture)) {
                float[] depthBefore = readDepth(textures.depth);
                try (SsgiPass pass = new SsgiPass(TEMPORAL, SIZE, SIZE)) {
                    for (int frame = 0; frame < 3; frame++) {
                        device.invalidateState();
                        runFrame(pass, device, textures, identityView());
                    }
                    float[] committed = readAttachment(pass.readFramebuffer(), 2);
                    assertArrayEquals(depthBefore, readDepth(textures.depth), 0.0f,
                            "the temporal stage must never write the shared scene depth");

                    pass.beginFrame();
                    CommandBuffer candidate = device.createCommandBuffer();
                    pass.recordTemporal(candidate, new SsgiPass.TemporalInputs(
                            textures.colour.id(), textures.depth.id(), textures.normal.id(),
                            textures.roughness.id(), textures.albedo.id(),
                            textures.velocity.id(), textures.previousDepth.id(),
                            textures.validity.id(), INVERSE_PROJECTION, PROJECTION,
                            new Matrix4f(), null, SIZE, SIZE));
                    device.execute(candidate);
                    pass.frameFailed();
                    assertArrayEquals(committed, readAttachment(pass.readFramebuffer(), 2),
                            0.0f, "a failed frame must keep the previous committed output");

                    System.setProperty(SsgiTemporalStorage.FAIL_RESIZE_PROPERTY, "true");
                    try {
                        assertThrows(IllegalStateException.class,
                                () -> pass.prepareResize(SIZE + 16, SIZE + 16));
                    } finally {
                        System.clearProperty(SsgiTemporalStorage.FAIL_RESIZE_PROPERTY);
                    }
                    assertEquals(SIZE, pass.readFramebuffer().width(),
                            "a failed resize candidate must not replace the active history");

                    pass.resize(SIZE + 16, SIZE + 16);
                    assertEquals(SIZE + 16, pass.readFramebuffer().width());
                    assertFalse(pass.historyValid(),
                            "a resize boundary must invalidate the history");
                    pass.close();
                    pass.close();
                    assertTrue(pass.isClosed(), "close must be idempotent");
                }
            }
            assertEquals(baselineResources, GlDebug.resources().liveResources().size(),
                    "close must release the temporal program, staging target and history slots");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void pipelinePublishesTheTemporalTargetAndRejectsMissingVelocity() {
        try (GlfwWindow window = window("SSGI temporal pipeline")) {
            RenderSettings hdr = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .vsync(false)
                    .build();
            PostProcessSettings effects = PostProcessSettings.defaults()
                    .withSsgi(SsgiSettings.defaults().withTemporalBlend(0.8f));
            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr, effects,
                    SIZE, SIZE, null, TEMPORAL_SURFACE, null, null, null, null);
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
                        "the temporal stage must stay one graph pass before tone mapping");
                assertEquals(PostProcessTargets.SSGI_COLOR, pipeline.linearHdrTextureName());
                assertTrue(graph.description().passes().stream()
                                .filter(pass -> pass.name().equals(PostProcessTargets.SSGI_PASS))
                                .findFirst().orElseThrow()
                                .directDependencies().contains(PostProcessTargets.GEOMETRY_PASS),
                        "the external-target SSGI pass must still depend on its surface producer");
            }

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr, effects,
                    SIZE, SIZE, null, SPATIAL_SURFACE, null, null, null, null);
                 RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
                graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                        .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                        .noClear()
                        .execute((resources, commands) -> {
                        });
                assertThrows(IllegalStateException.class, () -> pipeline.addFinalPass(graph),
                        "a temporal request without shared velocity must fail closed");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    // ---------------------------------------------------------------------
    // Frame, fixture and texture helpers
    // ---------------------------------------------------------------------

    private record View(Matrix4f view, Matrix4f inverseView) {
        static View identity() {
            return new View(new Matrix4f(), new Matrix4f());
        }
    }

    private static View identityView() {
        return View.identity();
    }

    private static float[] runFrame(SsgiPass pass, GlRenderDevice device, Textures textures,
                                    View view) {
        return runFrame(pass, device, textures, view, 0.0f, 0.0f);
    }

    private static float[] runFrame(SsgiPass pass, GlRenderDevice device, Textures textures,
                                    View view, float jitterUvX, float jitterUvY) {
        pass.beginFrame();
        CommandBuffer commands = device.createCommandBuffer();
        pass.recordTemporal(commands, new SsgiPass.TemporalInputs(
                textures.colour.id(), textures.depth.id(), textures.normal.id(),
                textures.roughness.id(), textures.albedo.id(), textures.velocity.id(),
                textures.previousDepth.id(), textures.validity.id(),
                INVERSE_PROJECTION, PROJECTION, view.view(), null, SIZE, SIZE,
                jitterUvX, jitterUvY));
        device.execute(commands);
        pass.frameSucceeded();
        return readAttachment(pass.readFramebuffer(), 2);
    }

    /** Alternating TAA-style horizontal jitter for a zero-based frame index. */
    private static float jitterUvX(int frame) {
        return (frame & 1) == 0 ? JITTER_UV : -JITTER_UV;
    }

    /** Which reprojection formula a counterfactual prediction applies. */
    private enum Reprojection {
        /** Documented contract: {@code uv + velocity - currentJitter + previousJitter}. */
        CONTRACT,
        /** The pre-fix formula that ignored TAA jitter. */
        MISSING_JITTER,
        /** The contract with the jitter sign reversed. */
        FLIPPED_JITTER
    }

    /** Horizontal history offset in pixels for one frame and formula. */
    private static int jitterOffsetPixels(int frame, Reprojection reprojection) {
        int sign = (frame & 1) == 0 ? 1 : -1;
        int previousSign = ((frame - 1) & 1) == 0 ? 1 : -1;
        return switch (reprojection) {
            case CONTRACT -> (previousSign - sign) / 2;
            case MISSING_JITTER -> 0;
            case FLIPPED_JITTER -> (sign - previousSign) / 2;
        };
    }

    /**
     * Independent frame-by-frame composite recurrence for the static
     * receiver-plane fixture.
     *
     * <p>The probe row keeps the accepted history of every column. At frame
     * {@code f} the contract history tap is at
     * {@code x + velocity - jitter(f) + jitter(f-1)}; the two counterfactuals
     * omit the jitter term or flip its sign. The expected spatial GI at each
     * pixel comes from {@link SsgiTemporalReference#spatialAdded}, the blend
     * weights from {@link SsgiTemporalReference#historyWeight} and the accepted
     * history is clamped to the depth-matched current 3x3 range exactly like
     * the shader. Nothing is read back from the GPU.</p>
     */
    private static double[][] jitteredPrediction(Fixture fixture, int frames,
                                                 Reprojection reprojection,
                                                 SsgiSettings settings) {
        double[][][][] spatial = new double[frames][3][SIZE][3];
        for (int frame = 0; frame < frames; frame++) {
            double rotation = SsgiTemporalReference.rotationForFrame(frame);
            for (int row = 0; row < 3; row++) {
                int y = PROBE_Y - 1 + row;
                for (int x = 0; x < SIZE; x++) {
                    spatial[frame][row][x] = SsgiTemporalReference.spatialAdded(
                            fixture.scene(), CAMERA, settings, NO_ENVIRONMENT, x, y,
                            settings.sampleCount(), rotation);
                }
            }
        }
        double[][] history = new double[SIZE][3];
        double[][] composite = new double[frames][];
        for (int frame = 0; frame < frames; frame++) {
            double[][] next = new double[SIZE][3];
            double weight = SsgiTemporalReference.historyWeight(settings.temporalBlend(),
                    settings.historyFrames(), frame + 1);
            for (int x = 0; x < SIZE; x++) {
                double[] center = spatial[frame][1][x];
                if (frame == 0) {
                    next[x] = center.clone();
                    continue;
                }
                int q = x + jitterOffsetPixels(frame, reprojection);
                if (!fixture.isReceiver(q, PROBE_Y) || q < 0 || q >= SIZE) {
                    // The tap lands outside the six-metre receiver surface (or
                    // the raster) and would be rejected as a depth mismatch.
                    next[x] = center.clone();
                    continue;
                }
                double[] resolved = SsgiTemporalReference.blend(history[q], center, weight);
                // The shader clamps accepted history to the depth-matched
                // current 3x3 range; a column left of RECEIVER_START is the
                // closer layer and has no matching depth.
                double[] low = center.clone();
                double[] high = center.clone();
                boolean hasNeighbour = false;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int neighbourX = x + dx;
                        int neighbourY = PROBE_Y + dy;
                        if (neighbourX < 0 || neighbourX >= SIZE
                                || !fixture.isReceiver(neighbourX, neighbourY)) {
                            continue;
                        }
                        double[] neighbour = spatial[frame][1 + dy][neighbourX];
                        for (int channel = 0; channel < 3; channel++) {
                            low[channel] = Math.min(low[channel], neighbour[channel]);
                            high[channel] = Math.max(high[channel], neighbour[channel]);
                        }
                        hasNeighbour = true;
                    }
                }
                if (hasNeighbour) {
                    resolved = SsgiTemporalReference.clampNeighbourhood(resolved, low, high);
                }
                next[x] = resolved;
            }
            history = next;
            composite[frame] = SsgiTemporalReference.composite(
                    asDouble(fixture.base(PROBE_X, PROBE_Y)), history[PROBE_X]);
        }
        return composite;
    }

    private static double distance(float[] image, int x, int y, double[] expected) {
        float[] actual = pixel(image, x, y);
        double dx = actual[0] - expected[0];
        double dy = actual[1] - expected[1];
        double dz = actual[2] - expected[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distance(double[] first, double[] second) {
        double dx = first[0] - second[0];
        double dy = first[1] - second[1];
        double dz = first[2] - second[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distance(float[] first, float[] second) {
        double dx = first[0] - second[0];
        double dy = first[1] - second[1];
        double dz = first[2] - second[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void assertDiffers(float[] output, int x, int y, double[] counterfactual,
                                      double minimum, String label) {
        double distance = distance(output, x, y, counterfactual);
        assertTrue(distance > minimum, label + ": distance " + distance
                + " must exceed " + minimum);
    }

    private static float[] renderSpatial(GlRenderDevice device, SsgiPass pass,
                                         Textures textures, Framebuffer target) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        CommandBuffer commands = device.createCommandBuffer();
        pass.recordIntoCurrentTarget(commands, textures.colour.id(), textures.depth.id(),
                textures.normal.id(), textures.roughness.id(), textures.albedo.id(),
                INVERSE_PROJECTION, PROJECTION, new Matrix4f(), null, SIZE, SIZE);
        device.execute(commands);
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[SIZE * SIZE * 4];
        pixels.get(result);
        return result;
    }

    private static float[] readAttachment(Framebuffer framebuffer, int attachment) {
        framebuffer.bind();
        glReadBuffer(GL_COLOR_ATTACHMENT0 + attachment);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[SIZE * SIZE * 4];
        pixels.get(result);
        return result;
    }

    private static float[] readDepth(Texture2D depth) {
        glBindTexture(GL_TEXTURE_2D, depth.id());
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RED, GL_FLOAT, pixels);
        float[] result = new float[SIZE * SIZE];
        pixels.get(result);
        return result;
    }

    private static float[] pixel(float[] image, int x, int y) {
        int offset = (y * SIZE + x) * 4;
        return new float[]{image[offset], image[offset + 1], image[offset + 2]};
    }

    private static double[] added(float[] image, float[] base) {
        float[] pixel = pixel(image, PROBE_X, PROBE_Y);
        return new double[]{pixel[0] - base[0], pixel[1] - base[1], pixel[2] - base[2]};
    }

    private static double[] asDouble(float[] value) {
        return new double[]{value[0], value[1], value[2]};
    }

    private static void assertMatches(float[] output, int x, int y, double[] expected,
                                      String label) {
        float[] actual = pixel(output, x, y);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals((float) expected[channel], actual[channel], TOLERANCE,
                    label + " (channel " + channel + ")");
        }
    }

    /** Average composite RGB of one probe pixel over frames {@code [from, to)}. */
    private static double[] averagePixels(float[][] frames, int from, int to, int x, int y) {
        double[] sum = new double[3];
        for (int frame = from; frame < to; frame++) {
            float[] value = pixel(frames[frame], x, y);
            sum[0] += value[0];
            sum[1] += value[1];
            sum[2] += value[2];
        }
        int count = to - from;
        return new double[]{sum[0] / count, sum[1] / count, sum[2] / count};
    }

    private static double error(float[] output, int x, int y, double[] expectedAdded,
                                double[] base) {
        float[] actual = pixel(output, x, y);
        double dx = actual[0] - (base[0] + expectedAdded[0]);
        double dy = actual[1] - (base[1] + expectedAdded[1]);
        double dz = actual[2] - (base[2] + expectedAdded[2]);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static GlfwWindow window(String title) {
        GlfwWindow window = new GlfwWindow.Builder().dimensions(64, 64).title(title)
                .visible(false).build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        return window;
    }

    private static float deviceDepth(float viewZ) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, viewZ, 1.0f).mul(PROJECTION);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    /** Synthetic surface with the extra shared temporal channels. */
    private static final class Fixture {
        private final float[] colour = new float[SIZE * SIZE * 4];
        private final float[] depth = new float[SIZE * SIZE];
        private final float[] normal = new float[SIZE * SIZE * 4];
        private final float[] roughness = new float[SIZE * SIZE];
        private final float[] albedo = new float[SIZE * SIZE * 4];
        private final float[] viewDepth = new float[SIZE * SIZE];
        private final float[] previousDepth = new float[SIZE * SIZE];
        private final float[] validity = new float[SIZE * SIZE];
        private final float[] velocity = new float[SIZE * SIZE * 2];
        private float receiverViewDepth = 6.0f;
        private float receiverNormalX;
        private float receiverNormalY;
        private float[] receiverBase = {0.02f, 0.02f, 0.02f};

        private Fixture() {
        }

        /** True when the pixel is on the six-metre receiver surface. */
        boolean isReceiver(int x, int y) {
            return Math.abs(viewDepth[y * SIZE + x] - 6.0f) < 1.0e-4f;
        }

        static Fixture directional(float bright) {
            Fixture fixture = new Fixture();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int pixel = y * SIZE + x;
                    float value = x < SIZE / 2 ? bright : 0.02f;
                    fixture.fillSurround(pixel, value, -5.0f);
                }
            }
            fixture.fillReceiver();
            fixture.syncPreviousDepth();
            return fixture;
        }

        static Fixture uniform(float value) {
            Fixture fixture = new Fixture();
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                fixture.fillSurround(pixel, value, -5.0f);
            }
            fixture.fillReceiver();
            fixture.syncPreviousDepth();
            return fixture;
        }

        /**
         * A flat receiver plane at six metres with a closer bright strip at
         * five metres just left of the probe. The probe's depth-matched 3x3
         * neighbourhood is the same receiver plane, so a uniform emissive step
         * on the strip updates the whole clamp range in one frame.
         */
        static Fixture plate(float layerBrightness) {
            Fixture fixture = new Fixture();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int pixel = y * SIZE + x;
                    fixture.viewDepth[pixel] = 6.0f;
                    fixture.depth[pixel] = deviceDepth(-6.0f);
                    fixture.colour[pixel * 4] = 0.02f;
                    fixture.colour[pixel * 4 + 1] = 0.02f;
                    fixture.colour[pixel * 4 + 2] = 0.02f;
                    fixture.colour[pixel * 4 + 3] = 1.0f;
                    fixture.normal[pixel * 4] = 0.0f;
                    fixture.normal[pixel * 4 + 1] = 0.0f;
                    fixture.normal[pixel * 4 + 3] = 1.0f;
                    fixture.roughness[pixel] = 1.0f;
                    fixture.albedo[pixel * 4] = 0.5f;
                    fixture.albedo[pixel * 4 + 1] = 0.5f;
                    fixture.albedo[pixel * 4 + 2] = 0.5f;
                    fixture.albedo[pixel * 4 + 3] = 1.0f;
                    fixture.validity[pixel] = 1.0f;
                    if (x >= PROBE_X - 10 && x < PROBE_X - 1) {
                        // Closer layer with a receiver-facing normal, inside the
                        // probe's hemisphere reach but outside its 3x3 window.
                        fixture.viewDepth[pixel] = 5.0f;
                        fixture.depth[pixel] = deviceDepth(-5.0f);
                        fixture.colour[pixel * 4] = layerBrightness;
                        fixture.colour[pixel * 4 + 1] = layerBrightness;
                        fixture.colour[pixel * 4 + 2] = layerBrightness;
                        fixture.normal[pixel * 4] = 1.0f;
                        fixture.normal[pixel * 4 + 1] = 1.0f;
                    }
                }
            }
            fixture.syncPreviousDepth();
            return fixture;
        }

        /**
         * A three-pixel six-metre receiver row surrounded by a closer uniform
         * bright layer. The probe and both {@code x +/- 1} jitter taps are on
         * the receiver, so their history states match, while nearly every
         * hemisphere ray leaves the one-pixel-tall row and hits the uniform
         * layer. The spatial GI is therefore strong and almost independent of
         * the per-pixel rotation sequence, letting the convergence case compare
         * the jittered and unjittered accumulations directly.
         */
        static Fixture windowPlate(float layerBrightness) {
            Fixture fixture = new Fixture();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int pixel = y * SIZE + x;
                    boolean receiver = Math.abs(x - PROBE_X) <= RECEIVER_WINDOW_HALF
                            && y == PROBE_Y;
                    if (receiver) {
                        fixture.fillReceiverPlane(pixel, -6.0f, 0.5f);
                    } else {
                        fixture.fillSurround(pixel, layerBrightness, -5.0f);
                    }
                }
            }
            fixture.syncPreviousDepth();
            return fixture;
        }

        /**
         * Same receiver geometry as {@link #windowPlate(float)} but with a
         * three-period albedo pattern on the receiver plane. The spatial GI at
         * the probe therefore alternates strongly with the pixel column, so a
         * history tap at the stable neighbour (contract) produces a different
         * accumulation from a tap at the current pixel (missing jitter) or at
         * the mirrored neighbour (flipped jitter sign).
         */
        static Fixture patternedReceiver(float layerBrightness) {
            return plateWithAlbedo(RECEIVER_START, layerBrightness, true);
        }

        private static Fixture plateWithAlbedo(int receiverStart, float layerBrightness,
                                               boolean pattern) {
            Fixture fixture = new Fixture();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int pixel = y * SIZE + x;
                    if (x < receiverStart) {
                        fixture.fillSurround(pixel, layerBrightness, -5.0f);
                    } else {
                        float albedo = pattern
                                ? ALBEDO_PATTERN[x % ALBEDO_PATTERN.length] : 0.5f;
                        fixture.fillReceiverPlane(pixel, -6.0f, albedo);
                    }
                }
            }
            // The probe pixel is part of the receiver plane above and already
            // carries the patterned albedo; do not overwrite it with the
            // uniform probe fill.
            fixture.syncPreviousDepth();
            return fixture;
        }

        private void fillReceiverPlane(int pixel, float viewZ, float albedoValue) {
            viewDepth[pixel] = -viewZ;
            depth[pixel] = deviceDepth(viewZ);
            colour[pixel * 4] = receiverBase[0];
            colour[pixel * 4 + 1] = receiverBase[1];
            colour[pixel * 4 + 2] = receiverBase[2];
            colour[pixel * 4 + 3] = 1.0f;
            normal[pixel * 4] = 0.0f;
            normal[pixel * 4 + 1] = 0.0f;
            normal[pixel * 4 + 2] = 0.0f;
            normal[pixel * 4 + 3] = 1.0f;
            roughness[pixel] = 1.0f;
            albedo[pixel * 4] = albedoValue;
            albedo[pixel * 4 + 1] = albedoValue;
            albedo[pixel * 4 + 2] = albedoValue;
            albedo[pixel * 4 + 3] = 1.0f;
            validity[pixel] = 1.0f;
        }

        Fixture withReceiverDepth(float viewZ) {
            Fixture copy = copy();
            copy.receiverViewDepth = viewZ;
            copy.fillReceiver();
            copy.syncPreviousDepth();
            return copy;
        }

        Fixture withReceiverNormal(float octX, float octY) {
            Fixture copy = copy();
            copy.receiverNormalX = octX;
            copy.receiverNormalY = octY;
            copy.fillReceiver();
            copy.syncPreviousDepth();
            return copy;
        }

        private void fillSurround(int pixel, float value, float viewZ) {
            viewDepth[pixel] = -viewZ;
            depth[pixel] = deviceDepth(viewZ);
            colour[pixel * 4] = value;
            colour[pixel * 4 + 1] = value;
            colour[pixel * 4 + 2] = value;
            colour[pixel * 4 + 3] = 1.0f;
            // oct (1,1) decodes to -Z: the layer faces the receiver.
            normal[pixel * 4] = 1.0f;
            normal[pixel * 4 + 1] = 1.0f;
            normal[pixel * 4 + 3] = 1.0f;
            roughness[pixel] = 1.0f;
            albedo[pixel * 4] = 0.5f;
            albedo[pixel * 4 + 1] = 0.5f;
            albedo[pixel * 4 + 2] = 0.5f;
            albedo[pixel * 4 + 3] = 1.0f;
            validity[pixel] = 1.0f;
        }

        private void fillReceiver() {
            int pixel = PROBE_Y * SIZE + PROBE_X;
            viewDepth[pixel] = receiverViewDepth;
            depth[pixel] = deviceDepth(-receiverViewDepth);
            colour[pixel * 4] = receiverBase[0];
            colour[pixel * 4 + 1] = receiverBase[1];
            colour[pixel * 4 + 2] = receiverBase[2];
            colour[pixel * 4 + 3] = 1.0f;
            normal[pixel * 4] = receiverNormalX;
            normal[pixel * 4 + 1] = receiverNormalY;
            normal[pixel * 4 + 3] = 1.0f;
            roughness[pixel] = 1.0f;
            albedo[pixel * 4] = 0.5f;
            albedo[pixel * 4 + 1] = 0.5f;
            albedo[pixel * 4 + 2] = 0.5f;
            albedo[pixel * 4 + 3] = 1.0f;
        }

        private void syncPreviousDepth() {
            System.arraycopy(viewDepth, 0, previousDepth, 0, viewDepth.length);
        }

        private Fixture copy() {
            Fixture copy = new Fixture();
            System.arraycopy(colour, 0, copy.colour, 0, colour.length);
            System.arraycopy(depth, 0, copy.depth, 0, depth.length);
            System.arraycopy(normal, 0, copy.normal, 0, normal.length);
            System.arraycopy(roughness, 0, copy.roughness, 0, roughness.length);
            System.arraycopy(albedo, 0, copy.albedo, 0, albedo.length);
            System.arraycopy(viewDepth, 0, copy.viewDepth, 0, viewDepth.length);
            System.arraycopy(previousDepth, 0, copy.previousDepth, 0, previousDepth.length);
            System.arraycopy(validity, 0, copy.validity, 0, validity.length);
            System.arraycopy(velocity, 0, copy.velocity, 0, velocity.length);
            copy.receiverViewDepth = receiverViewDepth;
            copy.receiverNormalX = receiverNormalX;
            copy.receiverNormalY = receiverNormalY;
            copy.receiverBase = receiverBase;
            return copy;
        }

        float[] base(int x, int y) {
            int offset = (y * SIZE + x) * 4;
            return new float[]{colour[offset], colour[offset + 1], colour[offset + 2]};
        }

        SsgiIndirectReference.Scene scene() {
            double[] sceneColour = new double[SIZE * SIZE * 3];
            double[] sceneNormal = new double[SIZE * SIZE * 2];
            double[] sceneAlbedo = new double[SIZE * SIZE * 3];
            double[] sceneDepth = new double[SIZE * SIZE];
            double[] sceneRoughness = new double[SIZE * SIZE];
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                for (int channel = 0; channel < 3; channel++) {
                    sceneColour[pixel * 3 + channel] = colour[pixel * 4 + channel];
                    sceneAlbedo[pixel * 3 + channel] = albedo[pixel * 4 + channel];
                }
                sceneNormal[pixel * 2] = normal[pixel * 4];
                sceneNormal[pixel * 2 + 1] = normal[pixel * 4 + 1];
                sceneDepth[pixel] = depth[pixel];
                sceneRoughness[pixel] = roughness[pixel];
            }
            return new SsgiIndirectReference.Scene(SIZE, SIZE, sceneColour, sceneDepth,
                    sceneNormal, sceneRoughness, sceneAlbedo);
        }
    }

    /** Owned texture set borrowed by the pass for one fixture lifetime. */
    private static final class Textures implements AutoCloseable {
        private final Texture2D colour;
        private final Texture2D depth;
        private final Texture2D normal;
        private final Texture2D roughness;
        private final Texture2D albedo;
        private final Texture2D velocity;
        private final Texture2D previousDepth;
        private final Texture2D validity;

        Textures(Fixture fixture) {
            colour = floatTexture(4, SIZE, fixture.colour);
            depth = floatTexture(1, SIZE, fixture.depth);
            normal = floatTexture(4, SIZE, fixture.normal);
            roughness = floatTexture(1, SIZE, fixture.roughness);
            albedo = floatTexture(4, SIZE, fixture.albedo);
            velocity = floatTexture(2, SIZE, fixture.velocity);
            previousDepth = floatTexture(1, SIZE, fixture.previousDepth);
            validity = floatTexture(1, SIZE, fixture.validity);
        }

        void upload(Fixture fixture) {
            uploadTexture(colour, 4, fixture.colour);
            uploadTexture(depth, 1, fixture.depth);
            uploadTexture(normal, 4, fixture.normal);
            uploadTexture(roughness, 1, fixture.roughness);
            uploadTexture(albedo, 4, fixture.albedo);
            uploadTexture(velocity, 2, fixture.velocity);
            uploadTexture(previousDepth, 1, fixture.previousDepth);
            uploadTexture(validity, 1, fixture.validity);
        }

        @Override
        public void close() {
            colour.close();
            depth.close();
            normal.close();
            roughness.close();
            albedo.close();
            velocity.close();
            previousDepth.close();
            validity.close();
        }
    }

    private static Texture2D floatTexture(int channels, int size, float[] data) {
        if (channels == 2) {
            // Texture2D has no dynamic RG32F upload path; widen the velocity to
            // RGBA32F. The shader only reads .rg, so the extra channels are inert.
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
        uploadTexture(texture, channels, data);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static void uploadTexture(Texture2D texture, int channels, float[] data) {
        if (channels == 2) {
            float[] expanded = new float[SIZE * SIZE * 4];
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                expanded[pixel * 4] = data[pixel * 2];
                expanded[pixel * 4 + 1] = data[pixel * 2 + 1];
                expanded[pixel * 4 + 3] = 1.0f;
            }
            channels = 4;
            data = expanded;
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(
                SIZE * SIZE * channels * Float.BYTES);
        buffer.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, SIZE, SIZE, buffer);
    }

    private static final SurfaceInputs TEMPORAL_SURFACE = new SurfaceInputs() {
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
                case VELOCITY -> new Input(channel, PostProcessTargets.SCENE_VELOCITY,
                        PostProcessTargets.GEOMETRY_PASS);
                case PREVIOUS_SURFACE_DEPTH -> new Input(channel,
                        PostProcessTargets.SCENE_PREVIOUS_DEPTH,
                        PostProcessTargets.GEOMETRY_PASS);
                case VALIDITY -> new Input(channel, PostProcessTargets.SCENE_VALIDITY,
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

    private static final SurfaceInputs SPATIAL_SURFACE = new SurfaceInputs() {
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
