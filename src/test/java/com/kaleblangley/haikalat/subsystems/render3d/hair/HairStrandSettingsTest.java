package com.kaleblangley.haikalat.subsystems.render3d.hair;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration and LOD contracts of the bounded hair-strand feature (#46).
 *
 * <p>The pass is GPU-owned, so the JVM layer pins the pure value contract:
 * bounded ranges, an exact disabled no-op, the inclusive LOD thresholds, the
 * level budgets and the far-width scale. Shader and geometry contracts are in
 * {@link HairStrandReferenceTest} and {@link HairStrandShaderTest}.</p>
 */
class HairStrandSettingsTest {
    @Test
    void disabledIsAnExactNoOpAndCannotCreateAPass() {
        HairStrandSettings disabled = HairStrandSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        HairStrandSettings enabled = disabled.withEnabled(true);
        assertTrue(enabled.active());
        assertEquals(disabled.seed(), enabled.seed());
        assertEquals(disabled.strandCount(), enabled.strandCount());
        assertEquals(disabled.transparency(), enabled.transparency());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new HairStrandPass(disabled, 64, 64));
        assertTrue(failure.getMessage().contains("disabled"),
                "a disabled settings value must be rejected before any GL resource exists");
    }

    @Test
    void lodThresholdsAreInclusiveAndPixelBounded() {
        HairStrandSettings settings = base().build();
        assertEquals(HairStrandSettings.Lod.HIGH, settings.selectLod(4.0, 48.0));
        assertEquals(HairStrandSettings.Lod.MEDIUM, settings.selectLod(4.0, 47.999));
        assertEquals(HairStrandSettings.Lod.MEDIUM, settings.selectLod(12.0, 24.0));
        assertEquals(HairStrandSettings.Lod.LOW, settings.selectLod(12.0, 23.999));
        assertEquals(HairStrandSettings.Lod.LOW, settings.selectLod(12.001, 1_000.0));
        assertEquals(HairStrandSettings.Lod.LOW, settings.selectLod(0.0, 0.0),
                "a sub-pixel lock must not pay for the high level even when close");
        assertThrows(IllegalArgumentException.class, () -> settings.selectLod(-1.0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> settings.selectLod(1.0, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> settings.selectLod(Double.NaN, 10.0));
    }

    @Test
    void levelBudgetsShrinkStrandsAndSegmentsMonotonically() {
        HairStrandSettings settings = base().strandCount(7).segmentsPerStrand(9).build();
        assertEquals(7, settings.strandCountFor(HairStrandSettings.Lod.HIGH));
        assertEquals(4, settings.strandCountFor(HairStrandSettings.Lod.MEDIUM));
        assertEquals(2, settings.strandCountFor(HairStrandSettings.Lod.LOW));
        assertEquals(9, settings.segmentsFor(HairStrandSettings.Lod.HIGH));
        assertEquals(7, settings.segmentsFor(HairStrandSettings.Lod.MEDIUM));
        assertEquals(4, settings.segmentsFor(HairStrandSettings.Lod.LOW));

        HairStrandSettings minimum = base().strandCount(1).segmentsPerStrand(2).build();
        assertEquals(1, minimum.strandCountFor(HairStrandSettings.Lod.LOW));
        assertEquals(2, minimum.segmentsFor(HairStrandSettings.Lod.MEDIUM));
        assertEquals(1, minimum.segmentsFor(HairStrandSettings.Lod.LOW));
    }

    @Test
    void widthScaleIsBoundedAndEndsAtTheConfiguredFarScale() {
        HairStrandSettings settings = base().lodWidthScale(0.5f).build();
        assertEquals(1.0, settings.widthScaleFor(0.0), 0.0);
        assertEquals(1.0, settings.widthScaleFor(4.0), 1.0e-9);
        assertEquals(0.75, settings.widthScaleFor(8.0), 1.0e-6);
        assertEquals(0.5, settings.widthScaleFor(12.0), 1.0e-9);
        assertEquals(0.5, settings.widthScaleFor(10_000.0), 1.0e-9);
        assertThrows(IllegalArgumentException.class, () -> settings.widthScaleFor(-1.0));
    }

    @Test
    void vectorsAreCopiedOnConstructionAndAccess() {
        Vector3f flow = new Vector3f(0.0f, -2.0f, 0.0f);
        Vector3f root = new Vector3f(0.1f, 0.2f, 0.3f);
        HairStrandSettings settings = base().flowDirection(flow).rootColor(root).build();
        flow.set(1.0f, 0.0f, 0.0f);
        root.set(9.0f);
        assertEquals(0.0f, settings.flowDirection().x, 0.0f);
        assertEquals(-1.0f, settings.flowDirection().y, 0.0f);
        assertEquals(0.1f, settings.rootColor().x, 0.0f);
        settings.flowDirection().set(4.0f, 5.0f, 6.0f);
        assertEquals(-1.0f, settings.flowDirection().y, 0.0f);
    }

    @Test
    void outOfRangeValuesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> base().strandCount(0).build());
        assertThrows(IllegalArgumentException.class, () -> base().strandCount(513).build());
        assertThrows(IllegalArgumentException.class, () -> base().segmentsPerStrand(1).build());
        assertThrows(IllegalArgumentException.class, () -> base().flowDirection(new Vector3f()).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().flowDirection(new Vector3f(Float.NaN, 0, 0)).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().strandRadiusMetres(0.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().strandRadiusMetres(0.3f).build());
        assertThrows(IllegalArgumentException.class, () -> base().widthPixels(0.5f).build());
        assertThrows(IllegalArgumentException.class, () -> base().widthPixels(33.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().minimumWidthPixels(9.0f).maximumWidthPixels(8.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().widthPixels(8.0f).minimumWidthPixels(9.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().nearDistanceMetres(12.0f).farDistanceMetres(12.0f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().nearDistanceMetres(13.0f).farDistanceMetres(12.0f).build());
        assertThrows(IllegalArgumentException.class, () -> base().alpha(1.5f).build());
        assertThrows(IllegalArgumentException.class, () -> base().alphaCutoff(1.0f).build());
        assertThrows(IllegalArgumentException.class, () -> base().alphaCutoff(-0.1f).build());
        assertThrows(IllegalArgumentException.class,
                () -> base().rootColor(new Vector3f(-1.0f, 0.0f, 0.0f)).build());
        assertThrows(IllegalArgumentException.class, () -> base().primaryExponent(0.0f).build());
        assertThrows(IllegalArgumentException.class, () -> base().secondaryWeight(2.0f).build());
        assertThrows(IllegalArgumentException.class, () -> base().colorVariation(-0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> base().lodWidthScale(0.1f).build());
        assertThrows(IllegalArgumentException.class, () -> base().lodMinimumPixels(0.0f).build());
    }

    @Test
    void builderDefaultsAreActiveAndBounded() {
        HairStrandSettings settings = HairStrandSettings.builder().build();
        assertTrue(settings.active());
        assertTrue(settings.strandCount() >= HairStrandSettings.MINIMUM_STRANDS);
        assertTrue(settings.segmentsPerStrand() >= HairStrandSettings.MINIMUM_SEGMENTS);
        assertTrue(settings.minimumWidthPixels() <= settings.widthPixels());
        assertTrue(settings.widthPixels() <= settings.maximumWidthPixels());
        assertTrue(settings.nearDistanceMetres() < settings.farDistanceMetres());
        assertEquals(HairStrandSettings.Transparency.SORTED_ALPHA, settings.transparency());
    }

    private static HairStrandSettings.Builder base() {
        return HairStrandSettings.builder()
                .strandCount(8)
                .segmentsPerStrand(8)
                .strandLengthMetres(0.6f)
                .rootSpreadMetres(0.0f)
                .curlMetres(0.05f)
                .widthPixels(3.0f)
                .minimumWidthPixels(1.5f)
                .maximumWidthPixels(8.0f)
                .nearDistanceMetres(4.0f)
                .farDistanceMetres(12.0f)
                .lodMinimumPixels(48.0f)
                .lodWidthScale(0.5f)
                .alpha(0.85f)
                .alphaCutoff(0.35f)
                .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
                .rootColor(new Vector3f(0.1f, 0.07f, 0.05f))
                .tipColor(new Vector3f(0.42f, 0.30f, 0.18f))
                .diffuseStrength(0.9f)
                .specularStrength(2.5f)
                .primaryExponent(48.0f)
                .secondaryExponent(128.0f)
                .secondaryWeight(0.5f)
                .colorVariation(0.25f)
                .castsShadow(true)
                .seed(HairStrandSettings.DEFAULT_SEED);
    }
}
