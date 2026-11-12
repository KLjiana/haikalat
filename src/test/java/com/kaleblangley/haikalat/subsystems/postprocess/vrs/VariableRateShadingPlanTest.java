package com.kaleblangley.haikalat.subsystems.postprocess.vrs;

import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingRate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tile-map contract for the NV per-tile shading-rate image. */
class VariableRateShadingPlanTest {
    private static final VariableRateShadingSettings SETTINGS =
            VariableRateShadingSettings.builder()
                    .coarseRate(VariableRateShadingRate.TWO_BY_TWO)
                    .fineRegionFraction(0.5f)
                    .build();

    @Test
    void evenExtentProducesASymmetricFineBlockAndCoarseBorder() {
        VariableRateShadingPlan plan = VariableRateShadingPlan.create(
                128, 128, SETTINGS, 16, 16, 8);
        assertEquals(8, plan.tilesX());
        assertEquals(8, plan.tilesY());
        assertEquals(2, plan.fineTileStartX());
        assertEquals(6, plan.fineTileEndX());
        assertEquals(2, plan.fineTileStartY());
        assertEquals(6, plan.fineTileEndY());
        assertTrue(plan.selective());
        assertArrayEquals(new int[]{
                        VariableRateShadingRate.ONE_BY_ONE.glValue(),
                        VariableRateShadingRate.TWO_BY_TWO.glValue()},
                plan.palette());

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean fine = x >= 2 && x < 6 && y >= 2 && y < 6;
                assertEquals(fine ? VariableRateShadingPlan.FINE_PALETTE_INDEX
                                : VariableRateShadingPlan.COARSE_PALETTE_INDEX,
                        plan.tileIndex(x, y), "tile " + x + "," + y);
            }
        }
        byte[] indices = plan.tileIndices();
        assertEquals(64, indices.length);
        indices[0] = 99;
        assertFalse(plan.tileIndices()[0] == 99, "tileIndices must be a defensive copy");
        assertThrows(IllegalArgumentException.class, () -> plan.tileIndex(8, 0));
        assertThrows(IllegalArgumentException.class, () -> plan.tileIndex(-1, 0));
    }

    @Test
    void oddExtentsRoundUpAndKeepBothRegions() {
        VariableRateShadingPlan plan = VariableRateShadingPlan.create(
                100, 70, SETTINGS, 16, 16, 8);
        assertEquals(7, plan.tilesX());
        assertEquals(5, plan.tilesY());
        assertEquals(1, plan.fineTileStartX());
        assertEquals(5, plan.fineTileEndX());
        assertEquals(1, plan.fineTileStartY());
        assertEquals(4, plan.fineTileEndY());
        assertTrue(plan.selective());
        assertTrue(plan.finePixel(20, 20));
        assertFalse(plan.finePixel(0, 0));
        assertFalse(plan.finePixel(99, 69));
    }

    @Test
    void aTwoByTwoTileFrameIsStillSelective() {
        VariableRateShadingPlan plan = VariableRateShadingPlan.create(
                32, 32, SETTINGS, 16, 16, 8);
        assertEquals(2, plan.tilesX());
        assertEquals(2, plan.tilesY());
        assertTrue(plan.selective());
        assertEquals(VariableRateShadingPlan.FINE_PALETTE_INDEX, plan.tileIndex(0, 0));
        assertEquals(VariableRateShadingPlan.COARSE_PALETTE_INDEX, plan.tileIndex(1, 1));
    }

    @Test
    void minimumFractionStillLeavesBothRegionsOnAReasonableFrame() {
        VariableRateShadingSettings settings = VariableRateShadingSettings.builder()
                .fineRegionFraction(VariableRateShadingSettings.MIN_FINE_REGION_FRACTION)
                .build();
        VariableRateShadingPlan plan = VariableRateShadingPlan.create(
                128, 128, settings, 16, 16, 8);
        assertTrue(plan.selective());
        int fineTiles = 0;
        for (int y = 0; y < plan.tilesY(); y++) {
            for (int x = 0; x < plan.tilesX(); x++) {
                if (plan.tileIndex(x, y) == VariableRateShadingPlan.FINE_PALETTE_INDEX) {
                    fineTiles++;
                }
            }
        }
        assertTrue(fineTiles >= 1, "the fine region must never disappear");
        assertTrue(fineTiles < plan.tilesX() * plan.tilesY(),
                "the coarse region must never disappear");
    }

    @Test
    void aSingleTexelTargetCannotHostASelectiveSplit() {
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingPlan.create(16, 16, SETTINGS, 16, 16, 8));
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingPlan.create(20000, 20000, SETTINGS, 1, 1, 8));
    }

    @Test
    void rejectsInvalidExtentsPalettesAndDisabledRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingPlan.create(0, 32, SETTINGS, 16, 16, 8));
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingPlan.create(32, 32, SETTINGS, 0, 16, 8));
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingPlan.create(32, 32, SETTINGS, 16, 16, 1));
        assertThrows(IllegalArgumentException.class,
                () -> VariableRateShadingPlan.create(32, 32,
                        VariableRateShadingSettings.disabled(), 16, 16, 8));
        assertThrows(NullPointerException.class,
                () -> VariableRateShadingPlan.create(32, 32, null, 16, 16, 8));
    }

    @Test
    void supportsOnlyANonFullRateWithAtLeastTwoPaletteEntries() {
        assertFalse(VariableRateShadingPlan.supports(VariableRateShadingRate.ONE_BY_ONE, 8));
        assertTrue(VariableRateShadingPlan.supports(VariableRateShadingRate.TWO_BY_TWO, 8));
        assertFalse(VariableRateShadingPlan.supports(VariableRateShadingRate.TWO_BY_TWO, 1));
        assertFalse(VariableRateShadingPlan.supports(null, 8));
    }
}
