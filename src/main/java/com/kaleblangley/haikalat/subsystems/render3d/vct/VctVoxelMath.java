package com.kaleblangley.haikalat.subsystems.render3d.vct;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import org.joml.Vector3f;

/**
 * GL-free voxel-space conventions shared by the owner, the update planner and
 * the JVM reference tests.
 *
 * <p>Conventions:</p>
 * <ul>
 *   <li>the volume is a closed world cube {@code [center-halfExtent,
 *       center+halfExtent]};</li>
 *   <li>voxel {@code (x,y,z)} covers
 *       {@code min + (xyz)*voxelSize .. min + (xyz+1)*voxelSize};</li>
 *   <li>world positions outside the cube clamp to the nearest edge voxel, which
 *       keeps compute dispatches bounded;</li>
 *   <li>linear index is {@code x + nx*(y + ny*z)} (x fastest), matching the
 *       3D-texture layer export order.</li>
 * </ul>
 */
public final class VctVoxelMath {
    private VctVoxelMath() {
    }

    static float voxelSize(VctSettings settings) {
        return (2.0f * settings.halfExtent()) / settings.resolution();
    }

    static Vector3f boundsMinimum(VctSettings settings) {
        return new Vector3f(settings.centerInternal()).sub(settings.halfExtent(), settings.halfExtent(), settings.halfExtent());
    }

    static int mipLevels(int resolution) {
        return 1 + (31 - Integer.numberOfLeadingZeros(resolution));
    }

    static int levelDimension(int resolution, int level) {
        if (level < 0 || level >= mipLevels(resolution)) {
            throw new IllegalArgumentException("mip level outside the chain");
        }
        return Math.max(1, resolution >> level);
    }

    /** Complete mip-chain bytes at {@code resolution}³ RGBA16F. */
    static long estimatedVolumeBytes(int resolution) {
        return Texture3D.estimatedByteSize(resolution, resolution, resolution,
                RenderFormat.RGBA16F, mipLevels(resolution));
    }

    static boolean contains(VctSettings settings, float worldX, float worldY, float worldZ) {
        float minimum = settings.centerInternal().x - settings.halfExtent();
        float maximum = settings.centerInternal().x + settings.halfExtent();
        if (worldX < minimum || worldX > maximum) return false;
        minimum = settings.centerInternal().y - settings.halfExtent();
        maximum = settings.centerInternal().y + settings.halfExtent();
        if (worldY < minimum || worldY > maximum) return false;
        minimum = settings.centerInternal().z - settings.halfExtent();
        maximum = settings.centerInternal().z + settings.halfExtent();
        return worldZ >= minimum && worldZ <= maximum;
    }

    /** Clamped axis voxel for one world coordinate. */
    static int voxelAxis(VctSettings settings, float world, int axis) {
        float minimum = axis(settings.centerInternal(), axis) - settings.halfExtent();
        int resolution = settings.resolution();
        double raw = Math.floor((world - minimum) / voxelSize(settings));
        if (raw < 0.0) return 0;
        if (raw >= resolution) return resolution - 1;
        return (int) raw;
    }

    /** Center of one voxel in world space; the input must already be in range. */
    static Vector3f voxelCenter(VctSettings settings, int x, int y, int z) {
        int resolution = settings.resolution();
        if (x < 0 || y < 0 || z < 0 || x >= resolution || y >= resolution || z >= resolution) {
            throw new IllegalArgumentException("voxel outside the volume");
        }
        Vector3f minimum = boundsMinimum(settings);
        float size = voxelSize(settings);
        return new Vector3f(minimum.x + (x + 0.5f) * size,
                minimum.y + (y + 0.5f) * size,
                minimum.z + (z + 0.5f) * size);
    }

    static long linearIndex(int x, int y, int z, int resolution) {
        if (x < 0 || y < 0 || z < 0 || x >= resolution || y >= resolution || z >= resolution) {
            throw new IllegalArgumentException("voxel outside the volume");
        }
        return (long) x + (long) resolution * (y + (long) resolution * z);
    }

    /** Normalized volume coordinate in {@code [0,1]} for a world position. */
    static Vector3f volumeUv(VctSettings settings, Vector3f world) {
        float size = 2.0f * settings.halfExtent();
        Vector3f minimum = boundsMinimum(settings);
        return new Vector3f((world.x - minimum.x) / size, (world.y - minimum.y) / size,
                (world.z - minimum.z) / size);
    }

    private static float axis(Vector3f value, int axis) {
        return switch (axis) {
            case 0 -> value.x;
            case 1 -> value.y;
            case 2 -> value.z;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2");
        };
    }

    /**
     * CPU mirror of the compute downsample contract, used as the JVM oracle:
     * radiance is coverage-weighted and destination coverage is the maximum of
     * the eight source texels. Inputs and outputs are packed RGBA rows.
     */
    public static float[] downsampleMip(float[] sourceRgba, int sourceDimension) {
        if (sourceDimension < 2 || (sourceDimension & (sourceDimension - 1)) != 0) {
            throw new IllegalArgumentException("source mip dimension must be a power of two >= 2");
        }
        int required = Math.multiplyExact(Math.multiplyExact(sourceDimension, sourceDimension),
                Math.multiplyExact(sourceDimension, 4));
        if (sourceRgba == null || sourceRgba.length != required) {
            throw new IllegalArgumentException("source mip payload must be " + required + " floats");
        }
        int destinationDimension = sourceDimension / 2;
        float[] destination = new float[Math.multiplyExact(
                Math.multiplyExact(destinationDimension, destinationDimension),
                Math.multiplyExact(destinationDimension, 4))];
        for (int z = 0; z < destinationDimension; z++) {
            for (int y = 0; y < destinationDimension; y++) {
                for (int x = 0; x < destinationDimension; x++) {
                    float weightedRed = 0.0f;
                    float weightedGreen = 0.0f;
                    float weightedBlue = 0.0f;
                    float coverage = 0.0f;
                    float weight = 0.0f;
                    for (int dz = 0; dz < 2; dz++) {
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int offset = (((z * 2 + dz) * sourceDimension
                                        + (y * 2 + dy)) * sourceDimension + (x * 2 + dx)) * 4;
                                float alpha = sourceRgba[offset + 3];
                                weightedRed += sourceRgba[offset] * alpha;
                                weightedGreen += sourceRgba[offset + 1] * alpha;
                                weightedBlue += sourceRgba[offset + 2] * alpha;
                                coverage = Math.max(coverage, alpha);
                                weight += alpha;
                            }
                        }
                    }
                    int offset = ((z * destinationDimension + y) * destinationDimension + x) * 4;
                    if (weight > 1.0e-6f) {
                        destination[offset] = weightedRed / weight;
                        destination[offset + 1] = weightedGreen / weight;
                        destination[offset + 2] = weightedBlue / weight;
                    }
                    destination[offset + 3] = coverage;
                }
            }
        }
        return destination;
    }

    /**
     * CPU trilinear sample with explicit two-level mip blend. This is the JVM
     * reference used to check that the GPU cone trace's documented footprint
     * relation lands on the expected data; production sampling happens in the
     * shader via {@code textureLod}.
     */
    static float[] sampleTrilinear(float[][] levels, int resolution, float u, float v, float w,
                                   float lod) {
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("mip levels must not be empty");
        }
        int maximumLevel = levels.length - 1;
        float clampedLod = Math.max(0.0f, Math.min(maximumLevel, lod));
        int baseLevel = (int) Math.floor(clampedLod);
        int nextLevel = Math.min(maximumLevel, baseLevel + 1);
        float fraction = clampedLod - baseLevel;
        float[] base = sampleLevel(levels[baseLevel], resolution, u, v, w);
        if (nextLevel == baseLevel || fraction <= 0.0f) return base;
        float[] next = sampleLevel(levels[nextLevel], resolution, u, v, w);
        for (int channel = 0; channel < 4; channel++) {
            base[channel] += (next[channel] - base[channel]) * fraction;
        }
        return base;
    }

    private static float[] sampleLevel(float[] level, int resolution, float u, float v, float w) {
        int dimension = Math.max(1, resolution);
        while ((long) dimension * dimension * dimension * 4 != level.length) {
            dimension /= 2;
            if (dimension < 1) {
                throw new IllegalArgumentException("mip payload does not match a halved chain");
            }
        }
        float x = Math.max(0.0f, Math.min(dimension - 1, u * dimension - 0.5f));
        float y = Math.max(0.0f, Math.min(dimension - 1, v * dimension - 0.5f));
        float z = Math.max(0.0f, Math.min(dimension - 1, w * dimension - 0.5f));
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        int x1 = Math.min(dimension - 1, x0 + 1);
        int y1 = Math.min(dimension - 1, y0 + 1);
        int z1 = Math.min(dimension - 1, z0 + 1);
        float fx = x - x0;
        float fy = y - y0;
        float fz = z - z0;
        float[] result = new float[4];
        for (int channel = 0; channel < 4; channel++) {
            float c000 = texel(level, dimension, x0, y0, z0, channel);
            float c100 = texel(level, dimension, x1, y0, z0, channel);
            float c010 = texel(level, dimension, x0, y1, z0, channel);
            float c110 = texel(level, dimension, x1, y1, z0, channel);
            float c001 = texel(level, dimension, x0, y0, z1, channel);
            float c101 = texel(level, dimension, x1, y0, z1, channel);
            float c011 = texel(level, dimension, x0, y1, z1, channel);
            float c111 = texel(level, dimension, x1, y1, z1, channel);
            float c00 = c000 + (c100 - c000) * fx;
            float c10 = c010 + (c110 - c010) * fx;
            float c01 = c001 + (c101 - c001) * fx;
            float c11 = c011 + (c111 - c011) * fx;
            float c0 = c00 + (c10 - c00) * fy;
            float c1 = c01 + (c11 - c01) * fy;
            result[channel] = c0 + (c1 - c0) * fz;
        }
        return result;
    }

    private static float texel(float[] level, int dimension, int x, int y, int z, int channel) {
        return level[((z * dimension + y) * dimension + x) * 4 + channel];
    }
}
