package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import org.joml.Matrix4d;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent-reference invariants for the SSGI sampling, fallback and
 * compositing math.
 *
 * <p>The reference is a double-precision re-implementation of the documented
 * equations, not a copy of the shader text. These cases pin the properties the
 * feature claims: a background-only view adds nothing (no double-counted IBL
 * ambient), a direct-lit receiver is never re-added, the offscreen budget is
 * clamped and non-black but cannot create energy from a zero ambient, one hit
 * bounce scales with receiver albedo and strength, and every bounded budget
 * stays finite and physically bounded.</p>
 */
class SsgiIndirectReferenceTest {
    private static final int SIZE = 32;
    private static final double FIELD_OF_VIEW = Math.toRadians(55.0);
    private static final Matrix4d PROJECTION = new Matrix4d().perspective(FIELD_OF_VIEW,
            1.0, 0.1, 100.0);
    private static final SsgiIndirectReference.Camera CAMERA =
            SsgiIndirectReference.Camera.identity(PROJECTION);
    private static final SsgiIndirectReference.Environment NO_ENVIRONMENT =
            SsgiIndirectReference.Environment.unavailable();

    private static final SsgiSettings ONE_BOUNCE = SsgiSettings.builder().enabled(true)
            .sampleCount(16).stepCount(8).maxDistanceMetres(8.0f)
            .thicknessMetres(1.0f).strength(1.0f).maximumOffscreenFraction(0.0f)
            .edgeFadePixels(0.0f).ambientFallback(0.0f, 0.0f, 0.0f).build();

    @Test
    void aBackgroundOnlyViewIsAnExactNoOp() {
        Fixture fixture = Fixture.backgroundOnly().build();
        SsgiSettings settings = SsgiSettings.defaults();
        for (int[] pixel : new int[][]{{0, 0}, {SIZE / 2, SIZE / 2}, {SIZE - 1, SIZE - 1}}) {
            double[] withEnvironment = SsgiIndirectReference.renderPixel(fixture.scene(),
                    CAMERA, settings, SsgiIndirectReference.Environment.uniform(0.6, 0.4, 0.2),
                    pixel[0], pixel[1]);
            assertPixelsEqual(fixture.base(pixel[0], pixel[1]), withEnvironment, 0.0,
                    "a background pixel must keep the exact base colour");
            double[] withFallback = SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA,
                    settings, NO_ENVIRONMENT, pixel[0], pixel[1]);
            assertPixelsEqual(fixture.base(pixel[0], pixel[1]), withFallback, 0.0,
                    "the uniform ambient fallback must not add a second ambient term");
        }
    }

    @Test
    void offscreenFallbackIsBoundedNonBlackAndCannotCreateEnergyFromZero() {
        Fixture fixture = Fixture.backgroundOnly().receiver(1, 1, -6.0, 0.5).build();
        SsgiSettings base = SsgiSettings.builder().enabled(true).sampleCount(8).stepCount(8)
                .maxDistanceMetres(8.0f).thicknessMetres(1.0f).strength(1.0f)
                .maximumOffscreenFraction(1.0f).edgeFadePixels(0.0f)
                .ambientFallback(0.4f, 0.4f, 0.4f).build();
        double[] zeroBudget = SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA,
                base.withMaximumOffscreenFraction(0.0f), NO_ENVIRONMENT, 1, 1);
        assertPixelsEqual(fixture.base(1, 1), zeroBudget, 1.0e-9,
                "a zero offscreen budget must add nothing");

        double[] halfBudget = SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA,
                base.withMaximumOffscreenFraction(0.5f), NO_ENVIRONMENT, 1, 1);
        double[] fullBudget = SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA,
                base, NO_ENVIRONMENT, 1, 1);
        assertTrue(fullBudget[0] > fixture.base(1, 1)[0],
                "offscreen rays must contribute the ambient approximation instead of black");
        assertTrue(halfBudget[0] >= zeroBudget[0] && halfBudget[0] <= fullBudget[0],
                "the offscreen budget must be monotonic and clamped");
        assertTrue(fullBudget[0] - fixture.base(1, 1)[0] <= 0.5 * 0.4 + 1.0e-9,
                "the offscreen contribution must not exceed the clamped ambient budget "
                        + "(albedo * fraction * ambient radiance)");

        double[] zeroAmbient = SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA,
                base.withAmbientFallback(0.0f, 0.0f, 0.0f), NO_ENVIRONMENT, 1, 1);
        assertPixelsEqual(fixture.base(1, 1), zeroAmbient, 1.0e-9,
                "a zero ambient fallback can never create energy");
    }

    @Test
    void hitRadianceAddsOneAlbedoWeightedBounceAndNeverReAddsTheReceiver() {
        Fixture dark = Fixture.surround(-5.0, 0.0, 1.0, true)
                .receiver(12, 16, -6.0, 0.5).build();
        Fixture bright = Fixture.surround(-5.0, 2.0, 1.0, true)
                .receiver(12, 16, -6.0, 0.5).build();
        double[] darkResult = SsgiIndirectReference.renderPixel(dark.scene(), CAMERA,
                ONE_BOUNCE, NO_ENVIRONMENT, 12, 16);
        double[] brightResult = SsgiIndirectReference.renderPixel(bright.scene(), CAMERA,
                ONE_BOUNCE, NO_ENVIRONMENT, 12, 16);
        double[] base = dark.base(12, 16);
        assertPixelsEqual(base, darkResult, 1.0e-9,
                "a dark neighbourhood contributes nothing");
        assertTrue(brightResult[0] > base[0],
                "a bright visible neighbour must contribute one indirect bounce");
        double added = brightResult[0] - base[0];
        assertTrue(added <= 2.0 * 0.5 + 1.0e-9,
                "one bounce may not exceed albedo * maximum neighbour radiance");

        double[] doubledAlbedo = SsgiIndirectReference.renderPixel(
                doubledReceiverAlbedo(bright, 12, 16), CAMERA, ONE_BOUNCE, NO_ENVIRONMENT,
                12, 16);
        double doubledAdded = doubledAlbedo[0] - base[0];
        assertEquals(2.0, doubledAdded / added, 1.0e-6,
                "the added bounce must be linear in the receiver albedo");

        double[] halfStrength = SsgiIndirectReference.renderPixel(bright.scene(), CAMERA,
                ONE_BOUNCE.withStrength(0.5f), NO_ENVIRONMENT, 12, 16);
        assertEquals(0.5, (halfStrength[0] - base[0]) / added, 1.0e-6,
                "the added bounce must be linear in the requested strength");
    }

    @Test
    void aDirectLitReceiverWithoutVisibleNeighboursIsNeverChanged() {
        Fixture fixture = Fixture.backgroundOnly().receiver(10, 10, -4.0, 0.9)
                .receiverColour(10.0, 6.0, 3.0).build();
        SsgiSettings settings = SsgiSettings.defaults().withMaximumOffscreenFraction(0.0f)
                .withAmbientFallback(0.0f, 0.0f, 0.0f);
        double[] result = SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA, settings,
                NO_ENVIRONMENT, 10, 10);
        assertPixelsEqual(fixture.base(10, 10), result, 0.0,
                "the receiver's own direct/ambient colour must never be re-added");
    }

    @Test
    void depthRoughnessAndNormalGatesRejectTheBrightSurface() {
        Fixture brightFar = Fixture.surround(-2.0, 2.0, 1.0, true)
                .receiver(12, 16, -6.0, 0.5).build();
        Fixture darkFar = Fixture.surround(-2.0, 0.02, 1.0, true)
                .receiver(12, 16, -6.0, 0.5).build();
        assertPixelsEqual(darkFar.base(12, 16), SsgiIndirectReference.renderPixel(
                        brightFar.scene(), CAMERA, ONE_BOUNCE, NO_ENVIRONMENT, 12, 16),
                1.0e-9,
                "a surface farther away than the thickness window must be rejected");

        Fixture brightFlipped = Fixture.surround(-5.0, 2.0, 1.0, false)
                .receiver(12, 16, -6.0, 0.5).build();
        assertPixelsEqual(darkFar.base(12, 16), SsgiIndirectReference.renderPixel(
                        brightFlipped.scene(), CAMERA, ONE_BOUNCE, NO_ENVIRONMENT, 12, 16),
                1.0e-9,
                "a hit whose geometric normal faces away from the ray must be rejected");

        Fixture mirrorSmooth = Fixture.surround(-5.0, 2.0, 0.0, true)
                .receiver(12, 16, -6.0, 0.5).build();
        assertPixelsEqual(darkFar.base(12, 16), SsgiIndirectReference.renderPixel(
                        mirrorSmooth.scene(), CAMERA, ONE_BOUNCE, NO_ENVIRONMENT, 12, 16),
                1.0e-9,
                "a mirror-like hit surface must not leak its specular radiance into diffuse GI");
    }

    @Test
    void everyBoundedBudgetStaysFiniteAndWithinThePhysicalBound() {
        Fixture fixture = Fixture.surround(-5.0, 3.0, 1.0, true)
                .receiver(8, 16, -6.0, 0.5).build();
        for (int samples : new int[]{1, 2, 4, 8, 16}) {
            for (int steps : new int[]{1, 4, 8, 16}) {
                SsgiSettings settings = SsgiSettings.builder().enabled(true)
                        .sampleCount(samples).stepCount(steps).maxDistanceMetres(8.0f)
                        .thicknessMetres(1.0f).strength(1.0f)
                        .maximumOffscreenFraction(0.5f).edgeFadePixels(0.0f)
                        .ambientFallback(0.1f, 0.1f, 0.1f).build();
                double[] result = SsgiIndirectReference.renderPixel(fixture.scene(), CAMERA,
                        settings, SsgiIndirectReference.Environment.uniform(0.2, 0.2, 0.2),
                        8, 16);
                for (double value : result) {
                    assertTrue(Double.isFinite(value), "budget " + samples + "x" + steps
                            + " produced a non-finite channel");
                }
                double added = result[0] - fixture.base(8, 16)[0];
                assertTrue(added >= -1.0e-12, "the pass must never subtract energy");
                assertTrue(added <= 3.0 * 0.5 + 0.2 * 0.5 + 1.0e-9,
                        "budget " + samples + "x" + steps
                                + " exceeded the physical single-bounce bound");
            }
        }
    }

    @Test
    void deterministicSequenceAndHelpersMatchTheFrozenShaderContract() {
        assertEquals(3.14159265359, SsgiIndirectReference.PI);
        assertEquals(0.61803398875, SsgiIndirectReference.GOLDEN_RATIO);
        double[] normal = {0.0, 0.0, 1.0};
        double[] sum = new double[3];
        for (int index = 0; index < 64; index++) {
            double[] direction = SsgiIndirectReference.cosineHemisphere(normal, index, 64, 0.0);
            double length = Math.sqrt(direction[0] * direction[0]
                    + direction[1] * direction[1] + direction[2] * direction[2]);
            assertEquals(1.0, length, 1.0e-12);
            assertTrue(direction[2] >= 0.0, "cosine sampling must stay in the hemisphere");
            sum[0] += direction[0];
            sum[1] += direction[1];
            sum[2] += direction[2];
        }
        assertEquals(0.0, sum[0] / 64.0, 0.05);
        assertEquals(0.0, sum[1] / 64.0, 0.05);
        assertTrue(sum[2] / 64.0 > 0.4, "the cosine-weighted mean must point along the normal");

        double[] decoded = SsgiIndirectReference.octDecode(0.0, 0.0);
        assertPixelsEqual(new double[]{0.0, 0.0, 1.0}, decoded, 1.0e-12,
                "octahedral centre must decode to +Z");
        double[] flipped = SsgiIndirectReference.octDecode(1.0, 1.0);
        assertPixelsEqual(new double[]{0.0, 0.0, -1.0}, flipped, 1.0e-9,
                "octahedral (1,1) must decode to -Z");
        for (int x = 0; x < 64; x++) {
            double noise = SsgiIndirectReference.interleavedGradientNoise(x + 0.5, 17.5);
            assertTrue(noise >= 0.0 && noise < 1.0, "interleaved noise must be in [0,1)");
        }
        assertEquals(1.0, SsgiIndirectReference.borderFade(0.5, 0.5, SIZE, SIZE, 0.0));
        assertEquals(0.0, SsgiIndirectReference.borderFade(0.0, 0.5, SIZE, SIZE, 8.0), 1.0e-12);
        assertEquals(0.0, SsgiIndirectReference.sampleRoughnessWeight(0.0, 0.15), 1.0e-12);
        assertEquals(1.0, SsgiIndirectReference.sampleRoughnessWeight(1.0, 0.15), 1.0e-12);
        assertFalse(SsgiIndirectReference.sampleRoughnessWeight(0.15, 0.15) > 0.5);
    }

    private static SsgiIndirectReference.Scene doubledReceiverAlbedo(Fixture fixture,
                                                                     int x, int y) {
        double[] albedo = fixture.scene().albedoRgb().clone();
        int pixel = y * SIZE + x;
        albedo[pixel * 3] = 1.0;
        albedo[pixel * 3 + 1] = 1.0;
        albedo[pixel * 3 + 2] = 1.0;
        SsgiIndirectReference.Scene scene = fixture.scene();
        return new SsgiIndirectReference.Scene(scene.width(), scene.height(),
                scene.colorRgb(), scene.depth(), scene.normalOct(), scene.roughness(), albedo);
    }

    private static void assertPixelsEqual(double[] expected, double[] actual, double tolerance,
                                          String message) {
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected[channel], actual[channel], tolerance,
                    message + " (channel " + channel + ")");
        }
    }

    /** Mutable synthetic fixture; the receiver is a small hole in a surrounding layer. */
    private static final class Fixture {
        private final double[] colour = new double[SIZE * SIZE * 3];
        private final double[] depth = new double[SIZE * SIZE];
        private final double[] normal = new double[SIZE * SIZE * 2];
        private final double[] roughness = new double[SIZE * SIZE];
        private final double[] albedo = new double[SIZE * SIZE * 3];
        private int receiverX = -1;
        private int receiverY = -1;
        private double receiverDepth = deviceDepth(-6.0);
        private double receiverAlbedo = 0.5;
        private double[] receiverBase = {0.02, 0.02, 0.02};

        private Fixture() {
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                depth[pixel] = 1.0;
                roughness[pixel] = 1.0;
                albedo[pixel * 3] = 0.5;
                albedo[pixel * 3 + 1] = 0.5;
                albedo[pixel * 3 + 2] = 0.5;
                colour[pixel * 3] = 0.02;
                colour[pixel * 3 + 1] = 0.02;
                colour[pixel * 3 + 2] = 0.02;
            }
        }

        static Fixture backgroundOnly() {
            return new Fixture();
        }

        /**
         * Fills the whole raster with a layer at one view depth. A layer that
         * faces the receiver uses the -Z normal; a layer that faces the camera
         * uses +Z and is therefore a backface for receiver rays.
         */
        static Fixture surround(double viewZ, double colourValue, double roughnessValue,
                                boolean facesReceiver) {
            Fixture fixture = new Fixture();
            for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                fixture.depth[pixel] = deviceDepth(viewZ);
                fixture.roughness[pixel] = roughnessValue;
                fixture.colour[pixel * 3] = colourValue;
                fixture.colour[pixel * 3 + 1] = colourValue;
                fixture.colour[pixel * 3 + 2] = colourValue;
                fixture.normal[pixel * 2] = facesReceiver ? 1.0 : 0.0;
                fixture.normal[pixel * 2 + 1] = facesReceiver ? 1.0 : 0.0;
            }
            return fixture;
        }

        Fixture receiver(int x, int y, double viewZ, double albedoValue) {
            this.receiverX = x;
            this.receiverY = y;
            this.receiverDepth = deviceDepth(viewZ);
            this.receiverAlbedo = albedoValue;
            return this;
        }

        Fixture receiverColour(double red, double green, double blue) {
            receiverBase = new double[]{red, green, blue};
            return this;
        }

        Fixture build() {
            if (receiverX >= 0) {
                int pixel = receiverY * SIZE + receiverX;
                depth[pixel] = receiverDepth;
                for (int channel = 0; channel < 3; channel++) {
                    albedo[pixel * 3 + channel] = receiverAlbedo;
                    colour[pixel * 3 + channel] = receiverBase[channel];
                }
                normal[pixel * 2] = 0.0;
                normal[pixel * 2 + 1] = 0.0;
            }
            return this;
        }

        SsgiIndirectReference.Scene scene() {
            return new SsgiIndirectReference.Scene(SIZE, SIZE, colour, depth, normal,
                    roughness, albedo);
        }

        double[] base(int x, int y) {
            int pixel = y * SIZE + x;
            return new double[]{colour[pixel * 3], colour[pixel * 3 + 1],
                    colour[pixel * 3 + 2]};
        }
    }

    private static double deviceDepth(double viewZ) {
        Vector4d clip = new Vector4d(0.0, 0.0, viewZ, 1.0).mul(PROJECTION);
        return clip.z / clip.w * 0.5 + 0.5;
    }
}
