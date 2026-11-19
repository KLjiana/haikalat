package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;

/**
 * Independent test-side reference for voxel indexing, mip downsampling,
 * trilinear mip sampling and cone footprint math.
 *
 * <p>Deliberately written with different control flow from the production
 * helpers: voxel lookup searches every candidate interval instead of using one
 * floor expression, mip downsampling accumulates corner weights explicitly, and
 * trilinear sampling expands the eight corner weights instead of nesting three
 * one-dimensional interpolations.</p>
 */
final class VctReference {
    private VctReference() {
    }

    static int voxelAxis(VctSettings settings, float world, int axis) {
        float minimum = axis(settings.center(), axis) - settings.halfExtent();
        float size = 2.0f * settings.halfExtent() / settings.resolution();
        for (int voxel = 0; voxel < settings.resolution(); voxel++) {
            float low = minimum + voxel * size;
            float high = low + size;
            if (world >= low && world < high) return voxel;
        }
        if (world < minimum) return 0;
        int last = settings.resolution() - 1;
        float lastLow = minimum + last * size;
        return world >= lastLow ? last : 0;
    }

    static boolean contains(VctSettings settings, float x, float y, float z) {
        return Math.abs(x - settings.center().x) <= settings.halfExtent()
                && Math.abs(y - settings.center().y) <= settings.halfExtent()
                && Math.abs(z - settings.center().z) <= settings.halfExtent();
    }

    /** Explicit corner-weight downsample; returns packed RGBA. */
    static float[] downsample(float[] source, int sourceDimension) {
        int destinationDimension = sourceDimension / 2;
        float[] destination = new float[destinationDimension * destinationDimension
                * destinationDimension * 4];
        for (int z = 0; z < destinationDimension; z++) {
            for (int y = 0; y < destinationDimension; y++) {
                for (int x = 0; x < destinationDimension; x++) {
                    double red = 0.0;
                    double green = 0.0;
                    double blue = 0.0;
                    double weight = 0.0;
                    float maximumCoverage = 0.0f;
                    for (int corner = 0; corner < 8; corner++) {
                        int sx = x * 2 + (corner & 1);
                        int sy = y * 2 + ((corner >> 1) & 1);
                        int sz = z * 2 + ((corner >> 2) & 1);
                        int offset = ((sz * sourceDimension + sy) * sourceDimension + sx) * 4;
                        double alpha = source[offset + 3];
                        red += source[offset] * alpha;
                        green += source[offset + 1] * alpha;
                        blue += source[offset + 2] * alpha;
                        weight += alpha;
                        maximumCoverage = Math.max(maximumCoverage, source[offset + 3]);
                    }
                    int offset = ((z * destinationDimension + y) * destinationDimension + x) * 4;
                    if (weight > 0.0) {
                        destination[offset] = (float) (red / weight);
                        destination[offset + 1] = (float) (green / weight);
                        destination[offset + 2] = (float) (blue / weight);
                    }
                    destination[offset + 3] = maximumCoverage;
                }
            }
        }
        return destination;
    }

    /** Independent trilinear sample with explicit eight-corner weights. */
    static float[] sample(float[] level, int dimension, float u, float v, float w) {
        float x = Math.max(0.0f, Math.min(dimension - 1, u * dimension - 0.5f));
        float y = Math.max(0.0f, Math.min(dimension - 1, v * dimension - 0.5f));
        float z = Math.max(0.0f, Math.min(dimension - 1, w * dimension - 0.5f));
        int x0 = Math.min(dimension - 1, (int) Math.floor(x));
        int y0 = Math.min(dimension - 1, (int) Math.floor(y));
        int z0 = Math.min(dimension - 1, (int) Math.floor(z));
        int x1 = Math.min(dimension - 1, x0 + 1);
        int y1 = Math.min(dimension - 1, y0 + 1);
        int z1 = Math.min(dimension - 1, z0 + 1);
        float fx = x - x0;
        float fy = y - y0;
        float fz = z - z0;
        float[] result = new float[4];
        for (int channel = 0; channel < 4; channel++) {
            double accum = 0.0;
            for (int corner = 0; corner < 8; corner++) {
                int cx = (corner & 1) == 0 ? x0 : x1;
                int cy = ((corner >> 1) & 1) == 0 ? y0 : y1;
                int cz = ((corner >> 2) & 1) == 0 ? z0 : z1;
                float wx = cx == x0 ? 1.0f - fx : fx;
                float wy = cy == y0 ? 1.0f - fy : fy;
                float wz = cz == z0 ? 1.0f - fz : fz;
                int offset = ((cz * dimension + cy) * dimension + cx) * 4;
                accum += level[offset + channel] * (double) wx * wy * wz;
            }
            result[channel] = (float) accum;
        }
        return result;
    }

    /** Independent two-level mip blend. */
    static float[] sampleMip(float[] base, int baseDimension, float u, float v, float w,
                             float lod) {
        int levels = 1 + (31 - Integer.numberOfLeadingZeros(baseDimension));
        float clamped = Math.max(0.0f, Math.min(levels - 1, lod));
        int lower = (int) clamped;
        int upper = Math.min(levels - 1, lower + 1);
        float fraction = clamped - lower;
        float[] low = sample(level(base, baseDimension, lower),
                Math.max(1, baseDimension >> lower), u, v, w);
        if (upper == lower || fraction == 0.0f) return low;
        float[] high = sample(level(base, baseDimension, upper),
                Math.max(1, baseDimension >> upper), u, v, w);
        for (int channel = 0; channel < 4; channel++) {
            low[channel] = low[channel] * (1.0f - fraction) + high[channel] * fraction;
        }
        return low;
    }

    private static float[] level(float[] base, int baseDimension, int level) {
        float[] current = base;
        int dimension = baseDimension;
        for (int index = 0; index < level; index++) {
            current = downsample(current, dimension);
            dimension /= 2;
        }
        return current;
    }

    static float mipLevelForCone(float radius, float voxelSize) {
        if (radius <= voxelSize * 0.5f) return 0.0f;
        return (float) (Math.log(2.0 * radius / voxelSize) / Math.log(2.0));
    }

    static float apertureForRoughness(float roughness, float scale) {
        return Math.max(0.02f, Math.min(1.2f, roughness * roughness * scale));
    }

    static float stepDistance(float voxelSize, float radius) {
        return Math.max(voxelSize, 0.75f * radius);
    }

    private static float axis(Vector3f value, int axis) {
        if (axis == 0) return value.x;
        if (axis == 1) return value.y;
        return value.z;
    }
}
