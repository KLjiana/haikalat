package com.kaleblangley.haikalat.subsystems.render3d.vct;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * GL-free dirty-region planner for incremental voxel updates.
 *
 * <p>The planner converts world AABBs to clamped voxel regions, merges
 * overlapping/touching regions, folds an over-cap list into one bounding
 * region, and charges the base clear/raster work plus every affected mip level
 * against {@link VctSettings#maxVoxelsPerUpdate()}. A budget overrun is
 * fail-closed: {@link VctPass} throws before recording any command, so the
 * published volume is never left partially updated.</p>
 */
final class VctUpdatePlanner {
    /** A dirty region covering this fraction of the base volume escalates to a full update. */
    static final float FULL_UPDATE_FRACTION = 0.5f;

    private VctUpdatePlanner() {
    }

    record VoxelRegion(int minX, int minY, int minZ,
                       int sizeX, int sizeY, int sizeZ) {
        VoxelRegion {
            if (minX < 0 || minY < 0 || minZ < 0
                    || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("voxel region must be non-empty and non-negative");
            }
        }

        long voxels() {
            return (long) sizeX * sizeY * sizeZ;
        }

        int maxX() { return minX + sizeX; }
        int maxY() { return minY + sizeY; }
        int maxZ() { return minZ + sizeZ; }

        boolean intersects(VoxelRegion other) {
            return minX <= other.maxX() && other.minX <= maxX()
                    && minY <= other.maxY() && other.minY <= maxY()
                    && minZ <= other.maxZ() && other.minZ <= maxZ();
        }

        VoxelRegion union(VoxelRegion other) {
            int x0 = Math.min(minX, other.minX);
            int y0 = Math.min(minY, other.minY);
            int z0 = Math.min(minZ, other.minZ);
            int x1 = Math.max(maxX(), other.maxX());
            int y1 = Math.max(maxY(), other.maxY());
            int z1 = Math.max(maxZ(), other.maxZ());
            return new VoxelRegion(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0);
        }

        /** Region written at {@code level}; every touched base voxel is covered. */
        VoxelRegion scaledToLevel(int resolution, int level) {
            int factor = 1 << level;
            int dimension = VctVoxelMath.levelDimension(resolution, level);
            int x0 = Math.min(minX >> level, dimension - 1);
            int y0 = Math.min(minY >> level, dimension - 1);
            int z0 = Math.min(minZ >> level, dimension - 1);
            int x1 = Math.min(Math.max(x0 + 1, ceilDiv(maxX(), factor)), dimension);
            int y1 = Math.min(Math.max(y0 + 1, ceilDiv(maxY(), factor)), dimension);
            int z1 = Math.min(Math.max(z0 + 1, ceilDiv(maxZ(), factor)), dimension);
            return new VoxelRegion(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0);
        }
    }

    record Plan(boolean full, List<VoxelRegion> regions, long baseVoxels, long mipVoxels) {
        Plan {
            regions = List.copyOf(regions);
        }

        static Plan none() {
            return new Plan(false, List.of(), 0L, 0L);
        }

        boolean empty() {
            return regions.isEmpty();
        }

        long totalVoxels() {
            return baseVoxels + mipVoxels;
        }
    }

    static Plan plan(VctSettings settings, List<VctBox> dirtyBoxes, boolean forceFull) {
        Objects.requireNonNull(settings, "settings");
        int resolution = settings.resolution();
        if (forceFull) {
            return checkedPlan(settings, true, List.of(fullRegion(resolution)));
        }
        if (dirtyBoxes == null || dirtyBoxes.isEmpty()) {
            return Plan.none();
        }
        List<VoxelRegion> regions = new ArrayList<>();
        for (VctBox box : dirtyBoxes) {
            VoxelRegion region = regionFor(settings, Objects.requireNonNull(box, "dirty box"));
            if (region != null) regions.add(region);
        }
        if (regions.isEmpty()) return Plan.none();
        regions = merge(regions);
        if (regions.size() > settings.maxDirtyRegions()) {
            regions = List.of(unionAll(regions));
        }
        long baseVoxels = 0L;
        for (VoxelRegion region : regions) baseVoxels = Math.addExact(baseVoxels, region.voxels());
        long volume = (long) resolution * resolution * resolution;
        if (baseVoxels >= (long) (volume * FULL_UPDATE_FRACTION)) {
            return checkedPlan(settings, true, List.of(fullRegion(resolution)));
        }
        long mipVoxels = mipVoxels(regions, resolution);
        long total = Math.addExact(baseVoxels, mipVoxels);
        if (total > settings.maxVoxelsPerUpdate()) {
            throw new IllegalArgumentException("voxel update budget exceeded: " + total
                    + " voxels (base " + baseVoxels + ", mips " + mipVoxels + ") > "
                    + settings.maxVoxelsPerUpdate());
        }
        return new Plan(false, regions, baseVoxels, mipVoxels);
    }

    private static Plan checkedPlan(VctSettings settings, boolean full, List<VoxelRegion> regions) {
        int resolution = settings.resolution();
        long base = 0L;
        for (VoxelRegion region : regions) base = Math.addExact(base, region.voxels());
        long mips = mipVoxels(regions, resolution);
        long total = Math.addExact(base, mips);
        if (total > settings.maxVoxelsPerUpdate()) {
            throw new IllegalArgumentException("full voxel update exceeds the update budget: "
                    + total + " > " + settings.maxVoxelsPerUpdate());
        }
        return new Plan(full, regions, base, mips);
    }

    private static VoxelRegion fullRegion(int resolution) {
        return new VoxelRegion(0, 0, 0, resolution, resolution, resolution);
    }

    private static long mipVoxels(List<VoxelRegion> regions, int resolution) {
        long total = 0L;
        int levels = VctVoxelMath.mipLevels(resolution);
        for (int level = 1; level < levels; level++) {
            List<VoxelRegion> scaled = new ArrayList<>(regions.size());
            for (VoxelRegion region : regions) scaled.add(region.scaledToLevel(resolution, level));
            for (VoxelRegion region : merge(scaled)) total = Math.addExact(total, region.voxels());
        }
        return total;
    }

    /**
     * Converts a world box to a clamped voxel region. A box without volume on
     * the closed cube (or entirely outside) returns {@code null}; a box that
     * touches the boundary includes the covered edge voxel.
     */
    static VoxelRegion regionFor(VctSettings settings, VctBox box) {
        float size = VctVoxelMath.voxelSize(settings);
        int resolution = settings.resolution();
        float minimumX = settings.centerInternal().x - settings.halfExtent();
        float minimumY = settings.centerInternal().y - settings.halfExtent();
        float minimumZ = settings.centerInternal().z - settings.halfExtent();
        float maximumX = settings.centerInternal().x + settings.halfExtent();
        float maximumY = settings.centerInternal().y + settings.halfExtent();
        float maximumZ = settings.centerInternal().z + settings.halfExtent();
        float lowX = Math.max(box.minimumInternal().x, minimumX);
        float lowY = Math.max(box.minimumInternal().y, minimumY);
        float lowZ = Math.max(box.minimumInternal().z, minimumZ);
        float highX = Math.min(box.maximumInternal().x, maximumX);
        float highY = Math.min(box.maximumInternal().y, maximumY);
        float highZ = Math.min(box.maximumInternal().z, maximumZ);
        if (lowX > highX || lowY > highY || lowZ > highZ) return null;
        int dilation = settings.thinWallDilation();
        // The voxelizer rasterizes a conservative shell: its low edge is
        // floor(min) - dilation - 1 (the inward shell) and its high edge is
        // ceil(max) + dilation inclusive. The clear/raster region must cover
        // every voxel either side can write.
        int x0 = clamp((int) Math.floor((lowX - minimumX) / size) - dilation - 1,
                0, resolution - 1);
        int y0 = clamp((int) Math.floor((lowY - minimumY) / size) - dilation - 1,
                0, resolution - 1);
        int z0 = clamp((int) Math.floor((lowZ - minimumZ) / size) - dilation - 1,
                0, resolution - 1);
        int x1 = clamp((int) Math.ceil((highX - minimumX) / size) + dilation + 1,
                1, resolution);
        int y1 = clamp((int) Math.ceil((highY - minimumY) / size) + dilation + 1,
                1, resolution);
        int z1 = clamp((int) Math.ceil((highZ - minimumZ) / size) + dilation + 1,
                1, resolution);
        if (x1 <= x0) x1 = Math.min(resolution, x0 + 1);
        if (y1 <= y0) y1 = Math.min(resolution, y0 + 1);
        if (z1 <= z0) z1 = Math.min(resolution, z0 + 1);
        return new VoxelRegion(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0);
    }

    static List<VoxelRegion> merge(List<VoxelRegion> input) {
        List<VoxelRegion> regions = new ArrayList<>(input);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int first = 0; first < regions.size(); first++) {
                for (int second = first + 1; second < regions.size(); second++) {
                    if (!regions.get(first).intersects(regions.get(second))) continue;
                    VoxelRegion union = regions.get(first).union(regions.get(second));
                    regions.remove(second);
                    regions.set(first, union);
                    changed = true;
                    break outer;
                }
            }
        }
        return regions;
    }

    private static VoxelRegion unionAll(List<VoxelRegion> regions) {
        VoxelRegion union = regions.get(0);
        for (int index = 1; index < regions.size(); index++) union = union.union(regions.get(index));
        return union;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
