package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dirty-region conversion, merge and budget contracts of the VCT update
 * planner. All values use a 32³ volume with one world unit per voxel and bounds
 * {@code [-16,16]} so every expected index is hand-checkable.
 */
class VctUpdatePlannerTest {
    private static final VctSettings SETTINGS = VctSettings.enabled(32, 16.0f)
            .withThinWallDilation(0);

    @Test
    void emptyDirtyListAndForcedFullPlanHaveDistinctWork() {
        VctUpdatePlanner.Plan empty = VctUpdatePlanner.plan(SETTINGS, List.of(), false);
        assertTrue(empty.empty());
        assertEquals(0L, empty.totalVoxels());
        assertFalse(empty.full());

        VctUpdatePlanner.Plan full = VctUpdatePlanner.plan(SETTINGS, List.of(), true);
        assertFalse(full.empty());
        assertTrue(full.full());
        assertEquals(1, full.regions().size());
        assertEquals(32L * 32L * 32L, full.baseVoxels());
        // mips: 16³ + 8³ + 4³ + 2³ + 1³ = 4096+512+64+8+1 = 4681
        assertEquals(4681L, full.mipVoxels());
        assertEquals(32768L + 4681L, full.totalVoxels());
    }

    @Test
    void worldBoxMapsToClampedVoxelRegionsAndIgnoresOutsideSpace() {
        VctUpdatePlanner.VoxelRegion region = VctUpdatePlanner.regionFor(SETTINGS,
                VctBox.of(-15.5f, -15.5f, -15.5f, -14.5f, -14.5f, -14.5f));
        assertEquals(0, region.minX());
        assertEquals(0, region.minY());
        assertEquals(0, region.minZ());
        assertEquals(3, region.sizeX());
        assertEquals(3, region.sizeY());
        assertEquals(3, region.sizeZ());

        VctUpdatePlanner.VoxelRegion clamped = VctUpdatePlanner.regionFor(SETTINGS,
                VctBox.of(-20.0f, -20.0f, -20.0f, -15.5f, -15.5f, -15.5f));
        assertEquals(0, clamped.minX());
        assertEquals(2, clamped.sizeX());
        assertEquals(0, clamped.minY());
        assertEquals(2, clamped.sizeY());

        VctUpdatePlanner.VoxelRegion edge = VctUpdatePlanner.regionFor(SETTINGS,
                VctBox.of(15.0f, 15.0f, 15.0f, 16.0f, 16.0f, 16.0f));
        assertEquals(31, edge.minX());
        assertEquals(1, edge.sizeX());

        assertEquals(null, VctUpdatePlanner.regionFor(SETTINGS,
                VctBox.of(20.0f, 0.0f, 0.0f, 22.0f, 1.0f, 1.0f)));
        assertTrue(VctUpdatePlanner.plan(SETTINGS, List.of(
                VctBox.of(20.0f, 0.0f, 0.0f, 22.0f, 1.0f, 1.0f)), false).empty());

        // Non-zero centre: each axis must use its own bounds, not the X centre.
        VctSettings offset = VctSettings.enabled(32, new Vector3f(0.0f, 4.0f, 0.0f), 8.0f)
                .withThinWallDilation(0);
        VctUpdatePlanner.VoxelRegion offsetRegion = VctUpdatePlanner.regionFor(offset,
                VctBox.of(0.0f, 4.0f, 0.0f, 0.0f, 5.0f, 0.0f));
        assertEquals(16, offsetRegion.minX());
        assertEquals(16, offsetRegion.minY());
        assertEquals(16, offsetRegion.minZ());
        assertEquals(1, offsetRegion.sizeX());
        assertEquals(3, offsetRegion.sizeY());
        assertEquals(1, offsetRegion.sizeZ());
    }

    @Test
    void touchingRegionsMergeAndTheRegionCapCollapsesToOneBoundingBox() {
        VctBox first = VctBox.of(-16.0f, 0.0f, 0.0f, -14.0f, 1.0f, 1.0f);
        VctBox touching = VctBox.of(-14.0f, 0.0f, 0.0f, -12.0f, 1.0f, 1.0f);
        VctBox distant = VctBox.of(10.0f, 0.0f, 0.0f, 11.0f, 1.0f, 1.0f);
        List<VctBox> dirty = List.of(first, touching, distant);

        VctUpdatePlanner.Plan merged = VctUpdatePlanner.plan(
                SETTINGS.withUpdateBudget(8, 4_000_000L), dirty, false);
        assertEquals(2, merged.regions().size());
        assertEquals(0, merged.regions().get(0).minX());
        assertEquals(5, merged.regions().get(0).sizeX());
        assertEquals(26, merged.regions().get(1).minX());
        assertEquals(2, merged.regions().get(1).sizeX());

        VctUpdatePlanner.Plan collapsed = VctUpdatePlanner.plan(
                SETTINGS.withUpdateBudget(1, 4_000_000L), dirty, false);
        assertEquals(1, collapsed.regions().size());
        assertEquals(0, collapsed.regions().get(0).minX());
        assertEquals(28, collapsed.regions().get(0).sizeX());
    }

    @Test
    void largeDirtyRegionEscalatesToFullUpdate() {
        VctUpdatePlanner.Plan plan = VctUpdatePlanner.plan(SETTINGS, List.of(
                VctBox.of(-16.0f, -16.0f, -16.0f, 16.0f, 16.0f, 16.0f)), false);
        assertTrue(plan.full());
        assertEquals(1, plan.regions().size());
        assertEquals(32, plan.regions().get(0).sizeX());
        assertEquals(32768L, plan.baseVoxels());
    }

    @Test
    void everyAffectedMipLevelIsChargedAndBudgetOverrunFailsClosed() {
        VctBox single = VctBox.of(-16.0f, -16.0f, -16.0f, -15.0f, -15.0f, -15.0f);
        VctUpdatePlanner.Plan plan = VctUpdatePlanner.plan(SETTINGS, List.of(single), false);
        assertEquals(8L, plan.baseVoxels());
        assertEquals(5L, plan.mipVoxels()); // levels 16,8,4,2,1
        assertEquals(13L, plan.totalVoxels());

        VctSettings tight = SETTINGS.withUpdateBudget(8, 5L);
        assertThrows(IllegalArgumentException.class,
                () -> VctUpdatePlanner.plan(tight, List.of(single), false));
        assertThrows(IllegalArgumentException.class,
                () -> VctUpdatePlanner.plan(tight, List.of(), true));
    }

    @Test
    void dilationExpandsRegionByThePotentialShellWidth() {
        VctSettings dilated = SETTINGS.withThinWallDilation(2);
        VctUpdatePlanner.VoxelRegion region = VctUpdatePlanner.regionFor(dilated,
                VctBox.of(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
        // floor(16) - 2 = 14; ceil(16) + 2 + 1 = 19 -> five voxels per axis.
        assertEquals(14, region.minX());
        assertEquals(14, region.minY());
        assertEquals(14, region.minZ());
        assertEquals(5, region.sizeX());
        assertEquals(5, region.sizeY());
        assertEquals(5, region.sizeZ());
    }

    @Test
    void movingBoxUnionCoversBothOldAndNewPositions() {
        VctBox before = VctBox.of(-8.0f, -8.0f, -8.0f, -7.0f, -7.0f, -7.0f);
        VctBox after = VctBox.of(4.0f, 4.0f, 4.0f, 5.0f, 5.0f, 5.0f);
        VctUpdatePlanner.Plan plan = VctUpdatePlanner.plan(SETTINGS,
                List.of(before.union(after)), false);
        assertEquals(1, plan.regions().size());
        VctUpdatePlanner.VoxelRegion region = plan.regions().get(0);
        assertEquals(8, region.minX());
        assertEquals(22, region.maxX());
        assertEquals(14, region.sizeX());
        assertTrue(region.voxels() >= 1L);
    }
}
