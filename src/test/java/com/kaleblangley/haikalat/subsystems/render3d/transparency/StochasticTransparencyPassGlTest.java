package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT1;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL11.glReadBuffer;

/**
 * Real-GL acceptance for the stochastic-transparency pass (S4 #37).
 *
 * <p>Runs the production {@link StochasticTransparencyPass} against synthetic
 * HDR targets and a borrowed depth fixture, and compares float readbacks with
 * independent double-precision references: the exact sorted alpha composite,
 * the binomial acceptance distribution, the bounded running-average weight and
 * the adjacent weighted-OIT model.  The cases cover the required matrix:
 * probability/coverage statistics, seed and sample-count convergence, temporal
 * noise reduction with camera tracking and a camera cut, velocity/reactive
 * rejection, the OIT/sorted neighbourhood with explicit error bounds, the
 * byte-identical disabled path and resize/failure/close lifecycle.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class StochasticTransparencyPassGlTest {
    private static final int SIZE = 64;
    private static final float FOV_Y_RADIANS = (float) Math.toRadians(45.0);
    private static final float TAN_HALF_FOV = (float) Math.tan(FOV_Y_RADIANS * 0.5);
    private static final float BACKGROUND_RED = 0.05f;
    private static final float BACKGROUND_GREEN = 0.10f;
    private static final float BACKGROUND_BLUE = 0.15f;
    private static final Vector3f BACKGROUND =
            new Vector3f(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE);
    private static final int MAXIMUM_SURFACES = 64;
    private static final String FAIL_RESIZE_PROPERTY =
            "haikalat.test.failStochasticTransparencyResizeAllocation";

    // ------------------------------------------------------------------
    // a) probability / coverage / moments
    // ------------------------------------------------------------------

    @Test
    void coverageMatchesTheAcceptanceProbabilityAndMomentsStayConsistent() {
        try (Fixture fixture = new Fixture(); Framebuffer target = hdrTarget()) {
            StochasticTransparencySettings single = settings(1, 0x5EED_5EED, false).build();
            try (StochasticTransparencyPass pass =
                         new StochasticTransparencyPass(SIZE, SIZE, single)) {
                OitSurface surface = screenQuad(4.0f, 0.9f, 0.2f, 0.1f, 0.3f);
                renderFrame(fixture, pass, target, List.of(surface),
                        inputs(viewForCameraX(0.0f), 0), single);
                FloatBuffer coverage = resolvedCoverage(fixture, pass);
                int accepted = 0;
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    float value = coverage.get(pixel * 4 + 3);
                    assertTrue(value == 0.0f || value == 1.0f,
                            "one sample must produce a binary coverage, got " + value);
                    if (value > 0.5f) {
                        accepted++;
                    }
                }
                double expected = 0.3 * SIZE * SIZE;
                double sigma = Math.sqrt(SIZE * SIZE * 0.3 * 0.7);
                assertEquals(expected, accepted, 4.0 * sigma,
                        "accepted pixel count must be the binomial expectation");

                FloatBuffer state = resolvedState(fixture, pass);
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    float value = coverage.get(pixel * 4 + 3);
                    assertEquals(value, state.get(pixel * 4), 1.0e-3f,
                            "the state mean must equal the single-frame coverage");
                    assertEquals(value * value, state.get(pixel * 4 + 1), 1.0e-3f,
                            "the single-frame second moment must be coverage squared");
                }

                FloatBuffer pixels = readColor(fixture, target);
                int hit = findCoverage(coverage, true);
                int miss = findCoverage(coverage, false);
                assertPixel(new Vector3f(0.9f, 0.2f, 0.1f), pixels, hit, 2.0e-3f,
                        "an accepted sample must composite the surface colour");
                assertPixel(BACKGROUND, pixels, miss, 2.0e-3f,
                        "a rejected sample must leave the background");
                assertEquals(GL_NO_ERROR, glGetError());
            }

            StochasticTransparencySettings four = settings(4, 0x5EED_5EED, false).build();
            try (StochasticTransparencyPass pass =
                         new StochasticTransparencyPass(SIZE, SIZE, four)) {
                OitSurface surface = screenQuad(4.0f, 0.9f, 0.2f, 0.1f, 0.3f);
                renderFrame(fixture, pass, target, List.of(surface),
                        inputs(viewForCameraX(0.0f), 0), four);
                FloatBuffer coverage = resolvedCoverage(fixture, pass);
                double sum = 0.0;
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    float value = coverage.get(pixel * 4 + 3);
                    double levels = value * 4.0;
                    assertEquals(Math.rint(levels), levels, 1.0e-3,
                            "four samples must quantise coverage in quarters");
                    assertTrue(value >= 0.0f && value <= 1.0f);
                    sum += value;
                }
                assertEquals(0.3, sum / (SIZE * SIZE), 0.02,
                        "the sample-average coverage must match the acceptance probability");

                FloatBuffer accumulation = readAttachment(fixture,
                        pass.accumulationFramebufferId(), 4,
                        GL_COLOR_ATTACHMENT0, pass.width(), pass.height());
                for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                    assertEquals(coverage.get(pixel * 4 + 3) * 4.0f,
                            accumulation.get(pixel * 4 + 3), 1.0e-3f,
                            "the accumulation alpha must be the accepted sample count");
                }
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    // ------------------------------------------------------------------
    // b) seed / sample-count convergence to the same expected colour
    // ------------------------------------------------------------------

    @Test
    void differentSeedsAndSampleCountsConvergeToTheSameExpectation() {
        try (Fixture fixture = new Fixture(); Framebuffer target = hdrTarget()) {
            List<OitSurface> surfaces = List.of(
                    screenQuad(3.0f, 0.90f, 0.15f, 0.05f, 0.60f),
                    screenQuad(9.0f, 0.05f, 0.15f, 0.90f, 0.40f));
            StochasticTransparencySettings seedA = settings(2, 1234, true)
                    .maximumHistoryLength(32).build();
            StochasticTransparencySettings seedB = settings(4, 8765, true)
                    .maximumHistoryLength(32).build();

            double[] expected = StochasticTransparencyReference.expectedComposite(
                    StochasticTransparencyReference.layers(seedA, surfaces),
                    new double[]{BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE});
            double expectedCoverage = StochasticTransparencyReference.expectedCoverage(
                    StochasticTransparencyReference.layers(seedA, surfaces));

            double[] meanA = runStaticAccumulation(fixture, target, surfaces, seedA, 48);
            double[] meanB = runStaticAccumulation(fixture, target, surfaces, seedB, 48);
            assertRegionMean(expected, meanA, 0.05, "seed A accumulated estimate");
            assertRegionMean(expected, meanB, 0.05, "seed B with four samples");
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(meanA[channel], meanB[channel], 0.045,
                        "swapping the seed and sample count must converge to the same colour");
            }

            // The expected coverage is 1 - (1 - .6)(1 - .4) = .76.
            assertEquals(0.76, expectedCoverage, 1.0e-6);
            StochasticTransparencySettings coverageSettings = seedA;
            int coverageDepth = depthFixture(fixture, 3.0f);
            try (StochasticTransparencyPass pass =
                         new StochasticTransparencyPass(SIZE, SIZE, coverageSettings)) {
                for (int frame = 0; frame < 48; frame++) {
                    renderFrame(fixture, pass, target, surfaces,
                            inputs(viewForCameraX(0.0f), coverageDepth),
                            coverageSettings);
                }
                FloatBuffer coverage = resolvedCoverage(fixture, pass);
                assertEquals(expectedCoverage, regionMean(coverage, 24, 24, 16, 16, 3),
                        0.04, "two-layer expected coverage");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    // ------------------------------------------------------------------
    // c) temporal accumulation: noise reduction, tracking, cut
    // ------------------------------------------------------------------

    @Test
    void temporalAccumulationReducesInstabilityAndConvergesToTheExpectedColour() {
        try (Fixture fixture = new Fixture(); Framebuffer target = hdrTarget()) {
            OitSurface surface = screenQuad(4.0f, 0.9f, 0.2f, 0.1f, 0.5f);
            double[] expected = {
                    0.5 * 0.9 + 0.5 * BACKGROUND_RED,
                    0.5 * 0.2 + 0.5 * BACKGROUND_GREEN,
                    0.5 * 0.1 + 0.5 * BACKGROUND_BLUE};

            double[] singleMae = new double[1];
            double[] singleInstability = new double[1];
            measureTemporalSeries(fixture, target, List.of(surface), false, 1,
                    singleMae, singleInstability);
            double[] accumulatedMae = new double[1];
            double[] accumulatedInstability = new double[1];
            measureTemporalSeries(fixture, target, List.of(surface), true, 32,
                    accumulatedMae, accumulatedInstability);

            assertTrue(singleMae[0] > 0.12,
                    "one-sample spatial noise must be visible, got " + singleMae[0]);
            assertTrue(accumulatedMae[0] < 0.04,
                    "temporal accumulation must converge to the expectation, got "
                            + accumulatedMae[0]);
            assertTrue(accumulatedMae[0] < singleMae[0] * 0.35,
                    "accumulated error must be much smaller than one frame: "
                            + accumulatedMae[0] + " vs " + singleMae[0]);
            assertTrue(accumulatedInstability[0] < singleInstability[0] * 0.25,
                    "frame-to-frame instability must drop: " + accumulatedInstability[0]
                            + " vs " + singleInstability[0]);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void cameraMotionTracksTheDepthFixtureAndDoesNotLeaveAPermanentGhost() {
        try (Fixture fixture = new Fixture(); Framebuffer target = hdrTarget()) {
            OitSurface surface = new OitSurface(0.0f, 0.0f, -4.0f,
                    0.5f, 0.0f, 0.0f,
                    0.0f, 0.5f, 0.0f,
                    0.9f, 0.2f, 0.1f, 0.5f);
            StochasticTransparencySettings settings = settings(4, 0x5EED_5EED, true)
                    .maximumHistoryLength(16).neighborhoodClampRadius(2).build();
            int depthTexture = depthFixture(fixture, 4.0f);
            try (StochasticTransparencyPass pass =
                         new StochasticTransparencyPass(SIZE, SIZE, settings)) {
                for (int frame = 0; frame < 20; frame++) {
                    renderFrame(fixture, pass, target, List.of(surface),
                            inputs(viewForCameraX(0.0f), depthTexture), settings);
                }
                for (int frame = 1; frame <= 10; frame++) {
                    float cameraX = 0.05f * frame;
                    renderFrame(fixture, pass, target, List.of(surface),
                            inputs(viewForCameraX(cameraX), depthTexture), settings);
                }
                // Convergence region: must be covered by the world-static quad
                // for the whole sequence and be at least the clamp radius away
                // from both moving edges.
                //   camera 0.0: screen px = 32 + viewX * 19.314, quad half
                //               width = 0.5 * 19.314 = 9.657 -> x 22.343..41.657
                //   camera 0.5: view centre = -0.5 -> x 12.686..32.000
                // x=24..28 is inside both; its 5x5 clamp neighbourhood x=22..30
                // is also inside (x=22 centre 22.5 > 22.343, x=30 centre 30.5 < 32).
                // The old x=19..25 region contained columns 19..21 that were
                // outside for all 20 warm-up frames and only entered during the
                // move; with w = 16/17 their history cannot converge from zero
                // in 24 static frames (0.941^24 = 0.232 residual), which is
                // sweep-in latency, not a permanent ghost.
                int regionX = 24;
                int regionY = 29;
                int regionWidth = 5;
                int regionHeight = 7;
                double[] expected = {
                        0.5 * 0.9 + 0.5 * BACKGROUND_RED,
                        0.5 * 0.2 + 0.5 * BACKGROUND_GREEN,
                        0.5 * 0.1 + 0.5 * BACKGROUND_BLUE};
                FloatBuffer movedPixels = readColor(fixture, target);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(expected[channel],
                            regionMean(movedPixels, regionX, regionY, regionWidth,
                                    regionHeight, channel),
                            0.06, "tracked interior channel " + channel);
                }

                // Columns that the world-static quad has visibly left must not
                // keep the old coverage; they are more than the clamp radius
                // beyond the new right edge.
                FloatBuffer movedCoverage = resolvedCoverage(fixture, pass);
                assertEquals(0.0, regionMean(movedCoverage, 36, 28, 5, 9, 3), 0.03,
                        "the vacated band must not ghost the accumulated coverage");

                for (int frame = 0; frame < 24; frame++) {
                    renderFrame(fixture, pass, target, List.of(surface),
                            inputs(viewForCameraX(0.5f), depthTexture), settings);
                }
                FloatBuffer settledPixels = readColor(fixture, target);
                FloatBuffer settledCoverage = resolvedCoverage(fixture, pass);
                FloatBuffer settledState = resolvedState(fixture, pass);
                FloatBuffer lastFrameHits = readAttachment(fixture,
                        pass.accumulationFramebufferId(), 4, GL_COLOR_ATTACHMENT0,
                        pass.width(), pass.height());
                String diagnostics = " composite="
                        + regionMean(settledPixels, regionX, regionY, regionWidth,
                        regionHeight, 0)
                        + "/" + regionMean(settledPixels, regionX, regionY,
                        regionWidth, regionHeight, 1)
                        + "/" + regionMean(settledPixels, regionX, regionY,
                        regionWidth, regionHeight, 2)
                        + " resolvedCoverage=" + regionMean(settledCoverage, regionX,
                        regionY, regionWidth, regionHeight, 3)
                        + " stateMean=" + regionMean(settledState, regionX, regionY,
                        regionWidth, regionHeight, 0)
                        + " stateM2=" + regionMean(settledState, regionX, regionY,
                        regionWidth, regionHeight, 1)
                        + " lastFrameCoverage=" + (regionMean(lastFrameHits, regionX,
                        regionY, regionWidth, regionHeight, 3)
                        / settings.samplesPerFrame())
                        + " historyLength=" + pass.historyLength();
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(expected[channel],
                            regionMean(settledPixels, regionX, regionY, regionWidth,
                                    regionHeight, channel),
                            0.035, "stopped-camera convergence channel " + channel
                                    + diagnostics);
                }
                assertEquals(0.0, regionMean(settledCoverage, 36, 28, 5, 9, 3), 0.01,
                        "no permanent ghost after the camera stops");
                assertTrue(pass.historyValid());
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void cameraCutAndExplicitInvalidateDoNotCarryTheOldColour() {
        try (Fixture fixture = new Fixture(); Framebuffer target = hdrTarget()) {
            StochasticTransparencySettings settings = settings(4, 0x5EED_5EED, true)
                    .maximumHistoryLength(16).build();
            int depthTexture = depthFixture(fixture, 2.0f);
            try (StochasticTransparencyPass pass =
                         new StochasticTransparencyPass(SIZE, SIZE, settings)) {
                OitSurface red = screenQuadAt(0.0f, 2.0f, 0.9f, 0.1f, 0.1f, 1.0f);
                for (int frame = 0; frame < 8; frame++) {
                    renderFrame(fixture, pass, target, List.of(red),
                            inputs(viewForCameraX(0.0f), depthTexture), settings);
                }
                assertTrue(pass.historyValid());

                // A five-metre jump exceeds the pass' automatic camera-cut
                // threshold; the first frame after it must be the current green.
                OitSurface green = screenQuadAt(5.0f, 2.0f, 0.1f, 0.8f, 0.2f, 1.0f);
                renderFrame(fixture, pass, target, List.of(green),
                        inputs(viewForCameraX(5.0f), depthTexture), settings);
                FloatBuffer afterCut = readColor(fixture, target);
                assertPixel(new Vector3f(0.1f, 0.8f, 0.2f), afterCut,
                        pixel(32, 32), 0.03f, "automatic camera cut");
                assertTrue(afterCut.get(pixel(32, 32) * 4) < 0.25f,
                        "no old red may survive the automatic cut");

                for (int frame = 0; frame < 8; frame++) {
                    renderFrame(fixture, pass, target, List.of(red),
                            inputs(viewForCameraX(5.0f), depthTexture), settings);
                }
                pass.invalidate();
                assertTrue(!pass.historyValid());
                renderFrame(fixture, pass, target, List.of(green),
                        inputs(viewForCameraX(5.0f), depthTexture), settings);
                FloatBuffer afterInvalidate = readColor(fixture, target);
                assertPixel(new Vector3f(0.1f, 0.8f, 0.2f), afterInvalidate,
                        pixel(32, 32), 0.03f, "explicit invalidate");
                assertTrue(afterInvalidate.get(pixel(32, 32) * 4) < 0.25f,
                        "no old red may survive the explicit invalidate");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void velocityAndReactiveWindowsDiscardHistoryWhenConfigured() {
        try (Fixture fixture = new Fixture(); Framebuffer target = hdrTarget()) {
            int depthTexture = depthFixture(fixture, 2.0f);
            OitSurface opaqueRed = screenQuadAt(0.0f, 2.0f, 0.9f, 0.1f, 0.1f, 1.0f);
            // A 0.5 m jump at 2 m is about 19.3 screen pixels: beyond the 16 px
            // rejection budget but still inside the previous raster, so the
            // unlimited-budget control can actually accept the history.
            OitSurface translucentAfterJump =
                    screenQuadAt(0.5f, 2.0f, 0.1f, 0.8f, 0.2f, 0.25f);
            OitSurface translucentAtRest =
                    screenQuadAt(0.0f, 2.0f, 0.1f, 0.8f, 0.2f, 0.25f);

            StochasticTransparencySettings rejectedVelocity = settings(4, 77, true)
                    .velocityRejectionPixels(16.0f).maximumHistoryLength(16).build();
            StochasticTransparencySettings acceptedVelocity = settings(4, 77, true)
                    .velocityRejectionPixels(4096.0f).maximumHistoryLength(16).build();
            double[] rejected = warmThenShock(fixture, target, depthTexture,
                    opaqueRed, translucentAfterJump, rejectedVelocity, 0.5f);
            double[] accepted = warmThenShock(fixture, target, depthTexture,
                    opaqueRed, translucentAfterJump, acceptedVelocity, 0.5f);
            assertTrue(rejected[1] < 0.2,
                    "a reprojection beyond the velocity budget must reset the moment: "
                            + rejected[1]);
            assertTrue(accepted[1] > 0.5,
                    "the same frame with an unlimited budget must keep history: "
                            + accepted[1]);
            assertTrue(rejected[0] < accepted[0] - 0.1,
                    "rejected history must not smear the old coverage: "
                            + rejected[0] + " vs " + accepted[0]);

            StochasticTransparencySettings reactive = settings(4, 77, true)
                    .reactiveThreshold(0.25f).maximumHistoryLength(16).build();
            StochasticTransparencySettings notReactive = settings(4, 77, true)
                    .reactiveThreshold(1.0f).maximumHistoryLength(16).build();
            double[] shocked = warmThenShock(fixture, target, depthTexture,
                    opaqueRed, translucentAtRest, reactive, 0.0f);
            double[] controlled = warmThenShock(fixture, target, depthTexture,
                    opaqueRed, translucentAtRest, notReactive, 0.0f);
            assertTrue(shocked[1] < 0.2,
                    "a coverage shock beyond the reactive window must reset the moment: "
                            + shocked[1]);
            assertTrue(controlled[1] > 0.5,
                    "the disabled reactive window must keep history: " + controlled[1]);
            assertTrue(shocked[0] < controlled[0] - 0.1,
                    "reactive rejection must not blend the old coverage: "
                            + shocked[0] + " vs " + controlled[0]);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    // ------------------------------------------------------------------
    // d) adjacent sorted / OIT comparison with explicit error bounds
    // ------------------------------------------------------------------

    @Test
    void stochasticAndWeightedOitStayInsideTheirSortedReferenceBounds() {
        try (Fixture fixture = new Fixture(); Framebuffer target = hdrTarget()) {
            OitSurface near = screenQuad(2.0f, 0.85f, 0.20f, 0.10f, 0.60f);
            OitSurface far = screenQuad(8.0f, 0.10f, 0.40f, 0.85f, 0.40f);
            List<OitSurface> surfaces = List.of(near, far);
            StochasticTransparencySettings stochastic = settings(8, 0x5EED_5EED, true)
                    .maximumHistoryLength(32).build();
            int depthTexture = depthFixture(fixture, 2.0f);

            double[] sorted = sortedComposite(surfaces,
                    new double[]{BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE});
            double[] weighted = weightedModel(surfaces,
                    new double[]{BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE});
            double twoLayerBound = twoLayerWeightedErrorBound(near, far);
            double[] stochasticMean = new double[3];

            try (StochasticTransparencyPass pass =
                         new StochasticTransparencyPass(SIZE, SIZE, stochastic)) {
                for (int frame = 0; frame < 40; frame++) {
                    renderFrame(fixture, pass, target, surfaces,
                            inputs(viewForCameraX(0.0f), depthTexture), stochastic);
                }
                FloatBuffer stochasticPixels = readColor(fixture, target);
                stochasticMean[0] = regionMean(stochasticPixels, 24, 24, 16, 16, 0);
                stochasticMean[1] = regionMean(stochasticPixels, 24, 24, 16, 16, 1);
                stochasticMean[2] = regionMean(stochasticPixels, 24, 24, 16, 16, 2);
                assertRegionMean(sorted, stochasticMean, 0.05,
                        "stochastic estimate versus the exact sorted composite");
            }

            WeightedOitSettings oitSettings = WeightedOitSettings.builder()
                    .enabled(true).depthWeighting(false).depthScale(10.0f)
                    .referenceDepth(20.0f).minimumWeight(1.0e-4f).maximumWeight(300.0f)
                    .alphaThreshold(0.0f).maximumSurfaces(8).build();
            try (WeightedOitPass oit = new WeightedOitPass(SIZE, SIZE, oitSettings)) {
                CommandBuffer cmd = fixture.device.createCommandBuffer();
                cmd.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                        .clearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f)
                        .clear(true, false);
                oit.record(cmd, surfaces, new Matrix4f(), projection(), oitSettings);
                cmd.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE);
                oit.resolve(cmd, oitSettings);
                fixture.device.execute(cmd);
                fixture.device.invalidateState();
                FloatBuffer oitPixels = readColor(fixture, target);
                double[] oitMean = {
                        regionMean(oitPixels, 24, 24, 16, 16, 0),
                        regionMean(oitPixels, 24, 24, 16, 16, 1),
                        regionMean(oitPixels, 24, 24, 16, 16, 2)};
                assertRegionMean(weighted, oitMean, 2.0e-3,
                        "weighted OIT must match the independent weighted model");
                assertRegionMean(sorted, oitMean, twoLayerBound + 3.0e-3,
                        "weighted OIT must stay inside the two-layer error bound");
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(stochasticMean[channel], oitMean[channel],
                            0.05 + twoLayerBound,
                            "the stochastic estimate and weighted OIT must stay within "
                                    + "the statistical tolerance plus the WBOIT bound");
                }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    // ------------------------------------------------------------------
    // e) disabled is byte-for-byte identical to the baseline
    // ------------------------------------------------------------------

    @Test
    void disabledSettingsAreByteIdenticalToTheBaselineAndCreateNoWork() {
        try (Fixture fixture = new Fixture();
             Framebuffer baseline = hdrTarget();
             Framebuffer withNoOp = hdrTarget()) {
            StochasticTransparencySettings active = settings(4, 0x5EED_5EED, false).build();
            StochasticTransparencySettings disabled = active.withEnabled(false);

            writeContent(fixture, baseline);
            FloatBuffer expected = readColor(fixture, baseline);

            try (StochasticTransparencyPass pass =
                         new StochasticTransparencyPass(SIZE, SIZE, active)) {
                writeContent(fixture, withNoOp);
                CommandBuffer cmd = fixture.device.createCommandBuffer();
                cmd.bindFramebuffer(withNoOp).viewport(0, 0, SIZE, SIZE);
                pass.record(cmd, List.of(screenQuad(3.0f, 0.9f, 0.2f, 0.1f, 0.5f)),
                        new StochasticTransparencyInputs(viewForCameraX(0.0f),
                                projection(), new Matrix4f(projection()).invert(),
                                new Matrix4f().invert(), 0),
                        disabled);
                pass.resolve(cmd, disabled);
                fixture.device.execute(cmd);
                pass.frameSucceeded();
                fixture.device.invalidateState();
                FloatBuffer actual = readColor(fixture, withNoOp);
                assertEquals(expected.capacity(), actual.capacity());
                for (int index = 0; index < expected.capacity(); index++) {
                    assertEquals(Float.floatToRawIntBits(expected.get(index)),
                            Float.floatToRawIntBits(actual.get(index)),
                            "disabled readback must be byte-identical at " + index);
                }
                assertTrue(!pass.historyValid(), "a disabled frame must not commit history");
                assertEquals(0, pass.historyLength());

                // An active record followed by a disabled resolve discards the
                // candidate and must still leave the caller target untouched.
                CommandBuffer activeRecord = fixture.device.createCommandBuffer();
                activeRecord.bindFramebuffer(withNoOp).viewport(0, 0, SIZE, SIZE);
                pass.record(activeRecord, List.of(), inputs(viewForCameraX(0.0f), 0),
                        active);
                pass.resolve(activeRecord, disabled);
                fixture.device.execute(activeRecord);
                pass.frameSucceeded();
                fixture.device.invalidateState();
                FloatBuffer afterActiveRecord = readColor(fixture, withNoOp);
                for (int index = 0; index < expected.capacity(); index++) {
                    assertEquals(Float.floatToRawIntBits(expected.get(index)),
                            Float.floatToRawIntBits(afterActiveRecord.get(index)),
                            "an active record with a disabled resolve must not composite");
                }
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new StochasticTransparencyPass(SIZE, SIZE, disabled),
                    "disabled settings must not create a pass or attachments");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    // ------------------------------------------------------------------
    // f) resize, failure rollback, borrowed depth and close
    // ------------------------------------------------------------------

    @Test
    void resizeFailureAndCloseReleaseEveryTrackedResourceAndPreserveDepth() {
        try (Fixture fixture = new Fixture();
             Framebuffer target = hdrTarget();
             Framebuffer depth = depthFixtureFramebuffer()) {
            StochasticTransparencySettings settings = settings(4, 0x5EED_5EED, true)
                    .maximumHistoryLength(16).build();
            setDepth(fixture, depth, 4.0f);
            FloatBuffer depthBefore = readDepth(fixture, depth);
            GlDebug.resourceTracking(true);
            try {
                StochasticTransparencyPass pass =
                        new StochasticTransparencyPass(SIZE, SIZE, settings);
                try {
                    List<OitSurface> surfaces =
                            List.of(screenQuad(4.0f, 0.3f, 0.5f, 0.7f, 0.6f));
                    StochasticTransparencyInputs inputs =
                            inputs(viewForCameraX(0.0f), depth.id());
                    renderFrame(fixture, pass, target, surfaces, inputs, settings);
                    assertTrue(pass.historyValid());

                    pass.resize(32, 24);
                    assertEquals(32, pass.width());
                    assertEquals(24, pass.height());
                    assertTrue(!pass.historyValid(), "a resize must invalidate history");
                    renderFrame(fixture, pass, target, surfaces, inputs, settings);
                    assertEquals(1, pass.historyLength(),
                            "the first frame after a resize starts a fresh history");

                    pass.resize(32, 24);
                    assertEquals(32, pass.width(), "a same-extent resize must be a no-op");
                    assertThrows(IllegalArgumentException.class, () -> pass.resize(0, 8));

                    System.setProperty(FAIL_RESIZE_PROPERTY, "true");
                    try {
                        assertThrows(IllegalStateException.class,
                                () -> pass.resize(24, 24));
                    } finally {
                        System.clearProperty(FAIL_RESIZE_PROPERTY);
                    }
                    assertEquals(32, pass.width(),
                            "a failed candidate must keep the active extent");
                    assertEquals(24, pass.height());
                    renderFrame(fixture, pass, target, surfaces, inputs, settings);
                    assertTrue(pass.historyValid(),
                            "the retained allocation must still work after a failed resize");

                    // A failed frame keeps the previously committed history and
                    // does not advance the reprojection pose.
                    int lengthBeforeFailure = pass.historyLength();
                    CommandBuffer failing = fixture.device.createCommandBuffer();
                    failing.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                            .clearColor(BACKGROUND_RED, BACKGROUND_GREEN,
                                    BACKGROUND_BLUE, 1.0f)
                            .clear(true, false);
                    pass.record(failing, surfaces, inputs, settings);
                    fixture.device.execute(failing);
                    pass.frameFailed();
                    assertTrue(pass.historyValid(),
                            "frameFailed must keep the committed history");
                    assertEquals(lengthBeforeFailure, pass.historyLength(),
                            "frameFailed must not advance the history length");

                    FloatBuffer depthAfter = readDepth(fixture, depth);
                    for (int index = 0; index < depthBefore.capacity(); index++) {
                        assertEquals(Float.floatToRawIntBits(depthBefore.get(index)),
                                Float.floatToRawIntBits(depthAfter.get(index)),
                                "the borrowed scene depth must never be written");
                    }
                    assertThrows(GlException.class,
                            () -> pass.resolve(fixture.device.createCommandBuffer(),
                                    settings),
                            "a resolve without a record must be rejected");
                } finally {
                    pass.close();
                    pass.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "closing the pass must release every tracked resource: "
                                + GlDebug.resources().liveResources());
                assertEquals(GlDebug.resources().createdCount(),
                        GlDebug.resources().closedCount());
                assertThrows(GlException.class,
                        () -> pass.record(fixture.device.createCommandBuffer(),
                                List.of(), inputs(viewForCameraX(0.0f), depth.id()),
                                settings),
                        "a closed pass must reject further work");
                assertEquals(GL_NO_ERROR, glGetError());
            } finally {
                GlDebug.resourceTracking(false);
            }
        }
    }

    // ------------------------------------------------------------------
    // Temporal helpers
    // ------------------------------------------------------------------

    /**
     * Runs a temporal pass and returns the width-three centre-region mean of the
     * final composite.  The shared target is cleared to the background on every
     * frame, so the readback is the pass contribution over a fresh background.
     */
    private static double[] runStaticAccumulation(Fixture fixture, Framebuffer target,
                                                  List<OitSurface> surfaces,
                                                  StochasticTransparencySettings settings,
                                                  int frames) {
        int depthTexture = depthFixture(fixture, 3.0f);
        try (StochasticTransparencyPass pass =
                     new StochasticTransparencyPass(SIZE, SIZE, settings)) {
            for (int frame = 0; frame < frames; frame++) {
                renderFrame(fixture, pass, target, surfaces,
                        inputs(viewForCameraX(0.0f), depthTexture), settings);
            }
            FloatBuffer pixels = readColor(fixture, target);
            return new double[]{
                    regionMean(pixels, 24, 24, 16, 16, 0),
                    regionMean(pixels, 24, 24, 16, 16, 1),
                    regionMean(pixels, 24, 24, 16, 16, 2)};
        }
    }

    private static void measureTemporalSeries(Fixture fixture, Framebuffer target,
                                              List<OitSurface> surfaces,
                                              boolean temporal, int maximumHistory,
                                              double[] maeOut, double[] instabilityOut) {
        StochasticTransparencySettings settings = settings(1, 0x5EED_5EED, temporal)
                .maximumHistoryLength(Math.max(2, maximumHistory)).build();
        int depthTexture = depthFixture(fixture, 4.0f);
        try (StochasticTransparencyPass pass =
                     new StochasticTransparencyPass(SIZE, SIZE, settings)) {
            int warmup = 32;
            for (int frame = 0; frame < warmup; frame++) {
                renderFrame(fixture, pass, target, surfaces,
                        inputs(viewForCameraX(0.0f), depthTexture), settings);
            }
            double[] expected = {
                    0.5 * 0.9 + 0.5 * BACKGROUND_RED,
                    0.5 * 0.2 + 0.5 * BACKGROUND_GREEN,
                    0.5 * 0.1 + 0.5 * BACKGROUND_BLUE};
            FloatBuffer previous = readColor(fixture, target);
            double mae = 0.0;
            double instability = 0.0;
            int frames = 8;
            for (int frame = 0; frame < frames; frame++) {
                renderFrame(fixture, pass, target, surfaces,
                        inputs(viewForCameraX(0.0f), depthTexture), settings);
                FloatBuffer current = readColor(fixture, target);
                mae += regionAbsoluteError(current, 24, 24, 16, 16, expected);
                instability += regionAbsoluteDifference(current, previous, 24, 24, 16, 16);
                previous = current;
            }
            maeOut[0] = mae / frames;
            instabilityOut[0] = instability / frames;
            if (temporal) {
                assertTrue(pass.historyValid());
                assertTrue(pass.historyLength() > 1);
            }
        }
    }

    /**
     * Warm-up with an opaque layer, then one frame with a translucent layer.
     * The shock frame may jump the camera (velocity rejection) or stay at rest
     * (coverage shock).  Returns the centre-region mean of the resolved coverage
     * and the coverage second moment.
     */
    private static double[] warmThenShock(Fixture fixture, Framebuffer target,
                                          int depthTexture, OitSurface warmupSurface,
                                          OitSurface shockSurface,
                                          StochasticTransparencySettings settings,
                                          float shockCameraX) {
        try (StochasticTransparencyPass pass =
                     new StochasticTransparencyPass(SIZE, SIZE, settings)) {
            for (int frame = 0; frame < 16; frame++) {
                renderFrame(fixture, pass, target, List.of(warmupSurface),
                        inputs(viewForCameraX(0.0f), depthTexture), settings);
            }
            renderFrame(fixture, pass, target, List.of(shockSurface),
                    inputs(viewForCameraX(shockCameraX), depthTexture), settings);
            FloatBuffer coverage = resolvedCoverage(fixture, pass);
            FloatBuffer state = resolvedState(fixture, pass);
            return new double[]{
                    regionMean(coverage, 28, 28, 9, 9, 3),
                    regionMean(state, 28, 28, 9, 9, 1)};
        }
    }

    // ------------------------------------------------------------------
    // Rendering, fixture and readback helpers
    // ------------------------------------------------------------------

    private static void renderFrame(Fixture fixture, StochasticTransparencyPass pass,
                                    Framebuffer target, List<OitSurface> surfaces,
                                    StochasticTransparencyInputs inputs,
                                    StochasticTransparencySettings settings) {
        CommandBuffer cmd = fixture.device.createCommandBuffer();
        cmd.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                .clearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f)
                .clear(true, false);
        pass.record(cmd, surfaces, inputs, settings);
        cmd.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE);
        pass.resolve(cmd, settings);
        fixture.device.execute(cmd);
        pass.frameSucceeded();
        fixture.device.invalidateState();
    }

    private static void writeContent(Fixture fixture, Framebuffer target) {
        CommandBuffer cmd = fixture.device.createCommandBuffer();
        cmd.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                .clearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f)
                .clear(true, false)
                .enableScissor(true).scissor(0, 0, SIZE / 2, SIZE)
                .clearColor(0.7f, 0.8f, 0.9f, 1.0f)
                .clear(true, false)
                // flush the scissor-off state as its own bounded command; a
                // pending state left at execute time is emitted *before* the
                // command stream, so a bare enableScissor(false) would not
                // restore the whole-target state for the following frame.
                .enableScissor(false)
                .clear(false, false);
        fixture.device.execute(cmd);
        fixture.device.invalidateState();
    }

    private static StochasticTransparencyInputs inputs(Matrix4f view, int depthTexture) {
        Matrix4f projection = projection();
        return new StochasticTransparencyInputs(view, projection,
                new Matrix4f(projection).invert(), new Matrix4f(view).invert(),
                depthTexture);
    }

    private static Matrix4f viewForCameraX(float cameraX) {
        return new Matrix4f().translate(-cameraX, 0.0f, 0.0f);
    }

    private static Matrix4f projection() {
        return new Matrix4f().perspective(FOV_Y_RADIANS, 1.0f, 0.1f, 100.0f);
    }

    /** World-space quad covering the whole viewport with a small safety margin. */
    private static OitSurface screenQuad(float viewDepth, float red, float green,
                                         float blue, float alpha) {
        float halfExtent = viewDepth * TAN_HALF_FOV * 1.25f;
        return new OitSurface(0.0f, 0.0f, -viewDepth,
                halfExtent, 0.0f, 0.0f,
                0.0f, halfExtent, 0.0f,
                red, green, blue, alpha);
    }

    /** Viewport-covering quad centred on the camera at {@code cameraX}. */
    private static OitSurface screenQuadAt(float cameraX, float viewDepth,
                                           float red, float green, float blue,
                                           float alpha) {
        float halfExtent = viewDepth * TAN_HALF_FOV * 1.25f;
        return new OitSurface(cameraX, 0.0f, -viewDepth,
                halfExtent, 0.0f, 0.0f,
                0.0f, halfExtent, 0.0f,
                red, green, blue, alpha);
    }

    private static StochasticTransparencySettings.Builder settings(int samples, int seed,
                                                                    boolean temporal) {
        return StochasticTransparencySettings.builder()
                .enabled(true).samplesPerFrame(samples).seed(seed)
                .jitterMode(StochasticTransparencySettings.JitterMode.HASH)
                .alphaThreshold(0.0f).coverageGamma(1.0f)
                .temporalAccumulation(temporal).historyWeight(1.0f)
                .maximumHistoryLength(16).neighborhoodClampRadius(2)
                .reactiveThreshold(1.0f).velocityRejectionPixels(32.0f)
                .depthRejectionThreshold(0.02f).maximumSurfaces(MAXIMUM_SURFACES);
    }

    private static int depthFixture(Fixture fixture, float viewDepth) {
        Framebuffer fixtureFramebuffer = depthFixtureFramebuffer();
        setDepth(fixture, fixtureFramebuffer, viewDepth);
        fixture.depthFixtures.add(fixtureFramebuffer);
        return fixtureFramebuffer.depthAttachment();
    }

    private static Framebuffer depthFixtureFramebuffer() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA8)
                .depthTexture()
                .build());
    }

    private static void setDepth(Fixture fixture, Framebuffer depth, float viewDepth) {
        depth.bind();
        GL11.glClearDepth(deviceDepthForViewDepth(viewDepth));
        GL11.glClear(GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(1.0);
        fixture.device.invalidateState();
    }

    private static float deviceDepthForViewDepth(float viewDepth) {
        Vector4f clip = projection().transform(new Vector4f(0.0f, 0.0f, -viewDepth, 1.0f));
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static FloatBuffer resolvedCoverage(Fixture fixture,
                                                StochasticTransparencyPass pass) {
        return readAttachment(fixture, pass.readResolvedFramebufferId(), 4,
                GL_COLOR_ATTACHMENT0, pass.width(), pass.height());
    }

    private static FloatBuffer resolvedState(Fixture fixture,
                                             StochasticTransparencyPass pass) {
        return readAttachment(fixture, pass.readResolvedFramebufferId(), 4,
                GL_COLOR_ATTACHMENT1, pass.width(), pass.height());
    }

    private static FloatBuffer readAttachment(Fixture fixture, int framebufferId,
                                              int components, int attachment,
                                              int width, int height) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebufferId);
        glReadBuffer(attachment);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * components);
        glReadPixels(0, 0, width, height,
                components == 4 ? GL_RGBA : GL11.GL_RED, GL_FLOAT, pixels);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        fixture.device.invalidateState();
        return pixels;
    }

    private static FloatBuffer readColor(Fixture fixture, Framebuffer target) {
        target.bind();
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        fixture.device.invalidateState();
        return pixels;
    }

    private static FloatBuffer readDepth(Fixture fixture, Framebuffer depth) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, depth.id());
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE);
        glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, pixels);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        fixture.device.invalidateState();
        return pixels;
    }

    private static Framebuffer hdrTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .build());
    }

    // ------------------------------------------------------------------
    // Independent references
    // ------------------------------------------------------------------

    private static double[] sortedComposite(List<OitSurface> surfaces, double[] background) {
        List<OitSurface> sorted = new ArrayList<>(surfaces);
        sorted.sort(Comparator.comparingDouble(OitSurface::centerZ));
        double[] result = background.clone();
        for (OitSurface surface : sorted) {
            for (int channel = 0; channel < 3; channel++) {
                double colour = switch (channel) {
                    case 0 -> surface.red();
                    case 1 -> surface.green();
                    default -> surface.blue();
                };
                result[channel] = colour * surface.alpha()
                        + result[channel] * (1.0 - surface.alpha());
            }
        }
        return result;
    }

    private static double[] weightedModel(List<OitSurface> surfaces, double[] background) {
        double transmittance = 1.0;
        double weightSum = 0.0;
        double[] colour = new double[3];
        for (OitSurface surface : surfaces) {
            transmittance *= 1.0 - surface.alpha();
            weightSum += surface.alpha();
            colour[0] += surface.red() * surface.alpha();
            colour[1] += surface.green() * surface.alpha();
            colour[2] += surface.blue() * surface.alpha();
        }
        double coverage = 1.0 - transmittance;
        if (weightSum <= 1.0e-12) {
            return background.clone();
        }
        return new double[]{
                colour[0] / weightSum * coverage + background[0] * transmittance,
                colour[1] / weightSum * coverage + background[1] * transmittance,
                colour[2] / weightSum * coverage + background[2] * transmittance};
    }

    private static double twoLayerWeightedErrorBound(OitSurface near, OitSurface far) {
        double nearAlpha = near.alpha();
        double farAlpha = far.alpha();
        double coefficient = nearAlpha * nearAlpha * farAlpha / (nearAlpha + farAlpha);
        double maxColourDifference = Math.max(
                Math.abs(near.red() - far.red()),
                Math.max(Math.abs(near.green() - far.green()),
                        Math.abs(near.blue() - far.blue())));
        return coefficient * maxColourDifference;
    }

    // ------------------------------------------------------------------
    // Assertion / statistics helpers
    // ------------------------------------------------------------------

    private static double regionMean(FloatBuffer pixels, int x, int y, int width,
                                     int height, int channel) {
        double sum = 0.0;
        int count = 0;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                sum += pixels.get((row * SIZE + column) * 4 + channel);
                count++;
            }
        }
        return sum / count;
    }

    private static double regionAbsoluteError(FloatBuffer pixels, int x, int y,
                                              int width, int height, double[] expected) {
        double sum = 0.0;
        int count = 0;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                int base = (row * SIZE + column) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    sum += Math.abs(pixels.get(base + channel) - expected[channel]);
                    count++;
                }
            }
        }
        return sum / count;
    }

    private static double regionAbsoluteDifference(FloatBuffer first, FloatBuffer second,
                                                   int x, int y, int width, int height) {
        double sum = 0.0;
        int count = 0;
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                int base = (row * SIZE + column) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    sum += Math.abs(first.get(base + channel) - second.get(base + channel));
                    count++;
                }
            }
        }
        return sum / count;
    }

    private static void assertRegionMean(double[] expected, double[] actual,
                                         double tolerance, String message) {
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected[channel], actual[channel], tolerance,
                    message + " channel " + channel);
        }
    }

    private static int findCoverage(FloatBuffer coverage, boolean accepted) {
        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
            boolean hit = coverage.get(pixel * 4 + 3) > 0.5f;
            if (hit == accepted) {
                return pixel;
            }
        }
        throw new AssertionError("no pixel with accepted=" + accepted);
    }

    private static void assertPixel(Vector3f expected, FloatBuffer pixels, int index,
                                    float tolerance, String message) {
        assertEquals(expected.x, pixels.get(index * 4), tolerance, message + " red");
        assertEquals(expected.y, pixels.get(index * 4 + 1), tolerance, message + " green");
        assertEquals(expected.z, pixels.get(index * 4 + 2), tolerance, message + " blue");
    }

    private static int pixel(int x, int y) {
        return y * SIZE + x;
    }

    /** Hidden window, capabilities and device for one real-GL case. */
    private static final class Fixture implements AutoCloseable {
        private final GlfwWindow window;
        private final GlRenderDevice device;
        private final List<Framebuffer> depthFixtures = new ArrayList<>();

        private Fixture() {
            window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                    .title("Stochastic transparency GL test").visible(false).build();
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            device = new GlRenderDevice();
        }

        @Override
        public void close() {
            for (int index = depthFixtures.size() - 1; index >= 0; index--) {
                depthFixtures.get(index).close();
            }
            depthFixtures.clear();
            window.close();
        }
    }
}
