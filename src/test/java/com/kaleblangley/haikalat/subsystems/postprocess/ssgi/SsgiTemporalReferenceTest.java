package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent reprojection/rejection/convergence math for the temporal SSGI
 * stage.
 *
 * <p>The expected values below are hand-computed from the documented equations
 * and the independent double reference, not from {@code SsgiPass} or
 * {@code SsgiSettings.historyWeightFor}. The real-GL test consumes the same
 * reference helpers as its oracle.</p>
 */
class SsgiTemporalReferenceTest {
    private static final int SIZE = 48;
    private static final SsgiSettings TEMPORAL = SsgiSettings.builder()
            .enabled(true).sampleCount(1).stepCount(8).maxDistanceMetres(8.0f)
            .thicknessMetres(1.0f).strength(1.0f).maximumOffscreenFraction(0.0f)
            .edgeFadePixels(0.0f).ambientFallback(0.0f, 0.0f, 0.0f)
            .temporalBlend(0.9f).historyFrames(64).temporalDepthRejection(0.02f)
            .temporalNormalRejection(0.9f).temporalVelocityRejection(64.0f).build();

    @Test
    void reprojectionIsTheStableRasterVelocityOffsetInPixels() {
        double[] previous = SsgiTemporalReference.previousUv(0.5, 0.25, 0.125, -0.0625);
        assertArrayEquals(new double[]{0.625, 0.1875}, previous, 1.0e-12);
        assertEquals(5.656854249492381, SsgiTemporalReference.reprojectionPixels(
                0.5, 0.25, 0.625, 0.1875, 32, 64), 1.0e-9);
        assertTrue(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, 3.0, 3.01,
                0.0, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5));
        assertFalse(SsgiTemporalReference.accepts(0.0, 0.0, 0.0, 3.0, 3.01,
                        0.0, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5),
                "validity 0 is a cut/invalid surface and must reject history");
        assertFalse(SsgiTemporalReference.accepts(0.5, 0.0, 1.0, 3.0, 3.0,
                        0.0, 0.0, 0.0, 0.0, TEMPORAL, 256, 256, 0.5, 0.5),
                "a reprojection beyond the pixel budget must reject history");
        assertFalse(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, -1.0, 3.0,
                        0.0, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5),
                "a missing previous depth must reject history");
        assertFalse(SsgiTemporalReference.accepts(0.02, 0.0, 1.0, 3.0, 3.0,
                        0.0, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.99, 0.5),
                "a reprojection outside the raster must reject history");
    }

    @Test
    void taaJitterMovesTheHistoryAddressByTheJitterDelta() {
        double halfPixel = 0.5 / SIZE;
        // Current raster +0.5 px, previous raster -0.5 px: the same surface was
        // one full pixel left in the previous raster, so the raw velocity
        // address (0 here) must move left by one pixel.
        double[] corrected = SsgiTemporalReference.previousUv(
                0.5, 0.5, 0.0, 0.0, halfPixel, 0.0, -halfPixel, 0.0);
        assertArrayEquals(new double[]{0.5 - 1.0 / SIZE, 0.5}, corrected, 1.0e-12);
        double[] missing = SsgiTemporalReference.previousUvWithoutJitter(
                0.5, 0.5, 0.0, 0.0);
        assertArrayEquals(new double[]{0.5, 0.5}, missing, 1.0e-12);
        double[] flipped = SsgiTemporalReference.previousUvWithFlippedJitter(
                0.5, 0.5, 0.0, 0.0, halfPixel, 0.0, -halfPixel, 0.0);
        assertArrayEquals(new double[]{0.5 + 1.0 / SIZE, 0.5}, flipped, 1.0e-12);
        assertEquals(1.0, SsgiTemporalReference.reprojectionPixels(
                0.5, 0.5, corrected[0], corrected[1], SIZE, SIZE), 1.0e-12);
        assertEquals(1.0, SsgiTemporalReference.reprojectionPixels(
                0.5, 0.5, flipped[0], flipped[1], SIZE, SIZE), 1.0e-12);
        // A vertical jitter follows the same contract independently.
        double[] vertical = SsgiTemporalReference.previousUv(
                0.25, 0.75, 0.0, 0.0, 0.0, -halfPixel, 0.0, halfPixel);
        assertArrayEquals(new double[]{0.25, 0.75 + 1.0 / SIZE}, vertical, 1.0e-12);
    }

    @Test
    void jitteredRecurrenceSeparatesTheContractFromMissingOrFlippedJitter() {
        // Frame 0 is spatial-only. With +0.5/-0.5 px alternating jitter and a
        // static signal S(x), frame 1 reads the neighbour under the contract:
        //   H1(x) = 0.5 S0(x + 1) + 0.5 S1(x)
        // and frame 2 reads the other neighbour:
        //   H2(x) = (2/3) H1(x - 1) + (1/3) S2(x).
        // The missing-jitter and flipped variants read x and x + 1 at frame 2,
        // so a three-period signal S = {1.0, 0.0, 0.5} separates all three.
        double[] signal = {1.0, 0.0, 0.5}; // x mod 3
        double[] contract = recurrenceAtFrameTwo(signal, false, false);
        double[] missing = recurrenceAtFrameTwo(signal, true, false);
        double[] flipped = recurrenceAtFrameTwo(signal, false, true);
        // Frame 2 contract at x mod 3 == 0: neighbours are x-1 (index 2) and x.
        assertEquals((2.0 / 3.0) * (0.5 * signal[0] + 0.5 * signal[2])
                + (1.0 / 3.0) * signal[0], contract[0], 1.0e-12);
        assertEquals((2.0 / 3.0) * (0.5 * signal[0] + 0.5 * signal[1])
                + (1.0 / 3.0) * signal[0], flipped[0], 1.0e-12);
        assertEquals((2.0 / 3.0) * (0.5 * signal[0] + 0.5 * signal[0])
                + (1.0 / 3.0) * signal[0], missing[0], 1.0e-12);
        assertTrue(Math.abs(contract[0] - missing[0]) > 0.1,
                "dropping the jitter correction must change the accumulated history");
        assertTrue(Math.abs(contract[0] - flipped[0]) > 0.1,
                "flipping the jitter sign must change the accumulated history");
        assertTrue(Math.abs(missing[0] - flipped[0]) > 0.1,
                "the two wrong variants must also differ from each other");
    }

    private static double[] recurrenceAtFrameTwo(double[] signal, boolean missingJitter,
                                                 boolean flippedJitter) {
        double[] frameOne = new double[3];
        for (int x = 0; x < 3; x++) {
            int offset = missingJitter ? 0 : flippedJitter ? -1 : 1;
            frameOne[x] = 0.5 * signal[Math.floorMod(x + offset, 3)] + 0.5 * signal[x];
        }
        double[] frameTwo = new double[3];
        for (int x = 0; x < 3; x++) {
            int offset = missingJitter ? 0 : flippedJitter ? 1 : -1;
            frameTwo[x] = (2.0 / 3.0) * frameOne[Math.floorMod(x + offset, 3)]
                    + (1.0 / 3.0) * signal[x];
        }
        return frameTwo;
    }

    @Test
    void depthRejectionUsesTheRelativeWindowWithAMetreFloor() {
        assertEquals(0.02, SsgiTemporalReference.depthTolerance(0.02, 0.0), 1.0e-12);
        assertEquals(0.02, SsgiTemporalReference.depthTolerance(0.02, 0.5), 1.0e-12);
        assertEquals(0.08, SsgiTemporalReference.depthTolerance(0.02, 4.0), 1.0e-12);
        assertEquals(1.0e-4, SsgiTemporalReference.depthTolerance(0.0, 10.0), 1.0e-12);
        assertTrue(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, 4.0, 4.07,
                0.0, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5));
        assertFalse(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, 4.0, 4.09,
                        0.0, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5),
                "a depth beyond the absolute window must reject history");
    }

    @Test
    void normalRejectionComparesTheDecodedDotProduct() {
        // oct (0,0) decodes to (0,0,1); oct (1,0) decodes to (1,0,0) -> dot 0.
        assertFalse(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, 3.0, 3.0,
                1.0, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5));
        // oct (0.2,0) -> normalize(0.2,0,0.8) -> dot 0.970 with (0,0,1).
        assertTrue(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, 3.0, 3.0,
                0.2, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5));
        // oct (0.4,0) -> dot 0.832: rejected by 0.9, accepted by 0.8.
        assertFalse(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, 3.0, 3.0,
                0.4, 0.0, 0.0, 0.0, TEMPORAL, 16, 16, 0.5, 0.5));
        SsgiSettings lenient = TEMPORAL.withTemporalNormalRejection(0.8f);
        assertTrue(SsgiTemporalReference.accepts(0.0, 0.0, 1.0, 3.0, 3.0,
                0.4, 0.0, 0.0, 0.0, lenient, 16, 16, 0.5, 0.5));
    }

    @Test
    void historyWeightRampsPerSampleAndSaturates() {
        assertEquals(0.0, SsgiTemporalReference.historyWeight(0.9, 8, 0), 0.0);
        assertEquals(0.0, SsgiTemporalReference.historyWeight(0.9, 8, 1), 0.0);
        assertEquals(0.5, SsgiTemporalReference.historyWeight(0.9, 8, 2), 0.0);
        assertEquals(2.0 / 3.0, SsgiTemporalReference.historyWeight(0.9, 8, 3), 1.0e-12);
        assertEquals(7.0 / 8.0, SsgiTemporalReference.historyWeight(0.9, 8, 8), 1.0e-12);
        assertEquals(7.0 / 8.0, SsgiTemporalReference.historyWeight(0.9, 8, 100), 1.0e-12);
        assertEquals(0.5, SsgiTemporalReference.historyWeight(0.5, 8, 100), 0.0);
        assertEquals(0.0, SsgiTemporalReference.historyWeight(0.0, 8, 100), 0.0);
    }

    @Test
    void blendAndNeighbourhoodClampAreBoundedByTheCurrentGiRange() {
        assertArrayEquals(new double[]{0.6, 0.8, 0.9},
                SsgiTemporalReference.blend(new double[]{1.0, 1.0, 1.0},
                        new double[]{0.0, 0.5, 0.75}, 0.6), 1.0e-12);
        assertArrayEquals(new double[]{0.5, 0.5, 0.5},
                SsgiTemporalReference.clampNeighbourhood(new double[]{1.0, 1.0, 1.0},
                        new double[]{0.1, 0.2, 0.3}, new double[]{0.5, 0.5, 0.5}), 0.0);
        assertArrayEquals(new double[]{0.1, 0.2, 0.3},
                SsgiTemporalReference.clampNeighbourhood(new double[]{0.0, 0.0, 0.0},
                        new double[]{0.1, 0.2, 0.3}, new double[]{0.5, 0.5, 0.5}), 0.0);
    }

    @Test
    void temporalPredictionConvergesTowardTheRotationAverage() {
        SsgiIndirectReference.Scene scene = directionalScene(2.0);
        SsgiIndirectReference.Camera camera = camera();
        SsgiIndirectReference.Environment environment =
                SsgiIndirectReference.Environment.unavailable();
        int probeX = SIZE / 2;
        int probeY = SIZE / 2;
        double[] first = SsgiTemporalReference.spatialAdded(scene, camera, TEMPORAL,
                environment, probeX, probeY, TEMPORAL.sampleCount(),
                SsgiTemporalReference.rotationForFrame(0));
        double[] average = SsgiTemporalReference.rotationAverage(scene, camera, TEMPORAL,
                environment, probeX, probeY, 512);
        double[] predicted = SsgiTemporalReference.temporalPrediction(scene, camera, TEMPORAL,
                environment, probeX, probeY, 96);
        double firstError = distance(first, average);
        double predictedError = distance(predicted, average);
        assertTrue(firstError > 1.0e-4, "the one-sample first frame must vary with rotation");
        assertTrue(predictedError < firstError * 0.6,
                "the temporal recurrence must converge toward the rotation average: "
                        + predictedError + " vs " + firstError);
        assertTrue(predictedError < 0.1,
                "the converged residual must stay bounded: " + predictedError);
    }

    @Test
    void cameraCutDetectsTranslationRotationAndProjectionDiscontinuity() {
        Matrix4f identityInverse = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(55.0),
                1.0f, 0.1f, 100.0f);
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        assertFalse(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                identityInverse, inverseProjection));

        Matrix4f translated = new Matrix4f().translate(3.0f, 0.0f, 0.0f);
        assertFalse(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                translated, inverseProjection), "a three-metre move is not a cut");
        Matrix4f farTranslation = new Matrix4f().translate(5.0f, 0.0f, 0.0f);
        assertTrue(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                farTranslation, inverseProjection), "a five-metre jump is a cut");

        Matrix4f quarterTurn = new Matrix4f().rotationY((float) Math.toRadians(90.0));
        assertTrue(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                new Matrix4f(quarterTurn).invert(), inverseProjection),
                "a 90-degree turn is a cut");
        Matrix4f smallTurn = new Matrix4f().rotationY((float) Math.toRadians(20.0));
        assertFalse(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                new Matrix4f(smallTurn).invert(), inverseProjection),
                "a 20-degree turn is ordinary motion, not a cut");

        Matrix4f narrower = new Matrix4f().perspective((float) Math.toRadians(40.0),
                1.0f, 0.1f, 100.0f);
        assertTrue(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                        identityInverse, new Matrix4f(narrower).invert()),
                "a projection/depth-mapping change is a cut");
        assertFalse(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                        identityInverse, new Matrix4f(jittered(projection, 0.5f)).invert()),
                "a sideways clip-space jitter must not look like a cut");

        Matrix4f invalid = new Matrix4f();
        invalid.m00(Float.NaN);
        assertTrue(SsgiPass.looksLikeCameraCut(identityInverse, inverseProjection,
                identityInverse, invalid), "a non-finite matrix must fail closed");
    }

    private static Matrix4f jittered(Matrix4f projection, float pixels) {
        Matrix4f jittered = new Matrix4f(projection);
        float deltaX = pixels * 2.0f / SIZE;
        float deltaY = pixels * 2.0f / SIZE;
        jittered.m00(jittered.m00() - deltaX * jittered.m03());
        jittered.m10(jittered.m10() - deltaX * jittered.m13());
        jittered.m20(jittered.m20() - deltaX * jittered.m23());
        jittered.m30(jittered.m30() - deltaX * jittered.m33());
        jittered.m01(jittered.m01() - deltaY * jittered.m03());
        jittered.m11(jittered.m11() - deltaY * jittered.m13());
        jittered.m21(jittered.m21() - deltaY * jittered.m23());
        jittered.m31(jittered.m31() - deltaY * jittered.m33());
        return jittered;
    }

    private static SsgiIndirectReference.Camera camera() {
        Matrix4d projection = new Matrix4d(new Matrix4f().perspective(
                (float) Math.toRadians(55.0), 1.0f, 0.1f, 100.0f));
        return SsgiIndirectReference.Camera.identity(projection);
    }

    private static SsgiIndirectReference.Scene directionalScene(double brightRadiance) {
        int pixels = SIZE * SIZE;
        double[] colour = new double[pixels * 3];
        double[] depth = new double[pixels];
        double[] normal = new double[pixels * 2];
        double[] roughness = new double[pixels];
        double[] albedo = new double[pixels * 3];
        Matrix4d projection = new Matrix4d(new Matrix4f().perspective(
                (float) Math.toRadians(55.0), 1.0f, 0.1f, 100.0f));
        double surroundDepth = deviceDepth(projection, -5.0);
        double receiverDepth = deviceDepth(projection, -6.0);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int pixel = y * SIZE + x;
                // One bright half and one dark half make the single-sample
                // estimate depend on the frame's azimuth rotation.
                double value = x < SIZE / 2 ? brightRadiance : 0.02;
                colour[pixel * 3] = value;
                colour[pixel * 3 + 1] = value;
                colour[pixel * 3 + 2] = value;
                depth[pixel] = surroundDepth;
                roughness[pixel] = 1.0;
                // The surround faces the receiver (oct 1,1 decodes to -Z) so
                // the receiver's hemisphere hits survive the backface gate.
                normal[pixel * 2] = 1.0;
                normal[pixel * 2 + 1] = 1.0;
                albedo[pixel * 3] = 0.5;
                albedo[pixel * 3 + 1] = 0.5;
                albedo[pixel * 3 + 2] = 0.5;
            }
        }
        int receiver = (SIZE / 2) * SIZE + SIZE / 2;
        depth[receiver] = receiverDepth;
        colour[receiver * 3] = 0.02;
        colour[receiver * 3 + 1] = 0.02;
        colour[receiver * 3 + 2] = 0.02;
        // The receiver faces the camera; the surround's opposite normal is set
        // in the loop above.
        normal[receiver * 2] = 0.0;
        normal[receiver * 2 + 1] = 0.0;
        return new SsgiIndirectReference.Scene(SIZE, SIZE, colour, depth, normal, roughness,
                albedo);
    }

    private static double deviceDepth(Matrix4d projection, double viewZ) {
        Vector4d clip = new Vector4d(0.0, 0.0, viewZ, 1.0).mul(projection);
        return clip.z / clip.w * 0.5 + 0.5;
    }

    private static double distance(double[] first, double[] second) {
        double dx = first[0] - second[0];
        double dy = first[1] - second[1];
        double dz = first[2] - second[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
