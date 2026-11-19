package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortDirection;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortKey;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Independent CPU oracle for the sorted particle path.
 *
 * <p>Two separate references live here, both written for the test tree and
 * never called by production code:</p>
 *
 * <ul>
 *   <li>{@link #stableSort} re-implements the documented depth/tie order with a
 *       stable Java sort, so a GPU-sorted index buffer can be compared against
 *       a second implementation of the same contract.</li>
 *   <li>{@link #composite} re-implements the billboard projection, falloff and
 *       premultiplied alpha-over equation of the sorted draw program and
 *       composites one draw per billboard in the given order. It is used to
 *       check the pixels of a multi-emitter scene against an independent
 *       reference, including the difference between far-to-near and
 *       near-to-far composition.</li>
 * </ul>
 *
 * <p>The metric helpers mirror the sort-key shader for finite values:
 * view depth is {@code -(view * world).z}, distance is the Euclidean length of
 * the view-space position. No production helper is used to compute expected
 * values.</p>
 */
final class GpuParticleSortingReference {
    private GpuParticleSortingReference() {
    }

    /** View-space metric of one world-space particle centre. */
    static float metric(Matrix4fc view, Vector3f world, SortKey key) {
        Vector4f position = view.transform(new Vector4f(world, 1.0f));
        if (key == SortKey.VIEW_DEPTH) {
            return -position.z;
        }
        return (float) Math.sqrt(position.x * position.x
                + position.y * position.y + position.z * position.z);
    }

    /**
     * Stable oracle order of active indices.
     *
     * <p>{@code metrics[i]} belongs to {@code activeIndices[i]}. Equal metrics
     * keep their input order for both directions, which is the documented
     * stable policy. Java's object sort is stable, so a reversed comparator
     * still preserves the equal-key order.</p>
     *
     * @param activeIndices published active list order
     * @param metrics       primary key per active-list position
     * @param direction     near-first or far-first
     * @return active indices in oracle order
     */
    static int[] stableSort(int[] activeIndices, float[] metrics, SortDirection direction) {
        if (activeIndices.length != metrics.length) {
            throw new IllegalArgumentException("metrics must match the active list length");
        }
        Integer[] positions = new Integer[activeIndices.length];
        for (int index = 0; index < positions.length; index++) {
            positions[index] = index;
        }
        Arrays.sort(positions, (left, right) -> {
            int comparison = Float.compare(metrics[left], metrics[right]);
            return direction == SortDirection.BACK_TO_FRONT ? -comparison : comparison;
        });
        int[] result = new int[activeIndices.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = activeIndices[positions[index]];
        }
        return result;
    }

    /**
     * Premultiplied alpha-over composite of billboards drawn in the given
     * order.
     *
     * @param width           target width in pixels
     * @param height          target height in pixels
     * @param viewProjection  camera matrix used by the draw
     * @param drawOrder       billboards in submission order
     * @return RGBA8 pixels, row zero at the bottom like {@code glReadPixels}
     */
    static byte[] composite(int width, int height, Matrix4fc viewProjection,
                            List<Billboard> drawOrder) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("target size must be positive");
        }
        byte[] pixels = new byte[width * height * 4];
        for (int offset = 3; offset < pixels.length; offset += 4) {
            pixels[offset] = (byte) 0xFF;
        }
        for (Billboard billboard : drawOrder) {
            drawBillboard(pixels, width, height, viewProjection, billboard);
        }
        return pixels;
    }

    private static void drawBillboard(byte[] pixels, int width, int height,
                                      Matrix4fc viewProjection, Billboard billboard) {
        Vector4f clip = viewProjection.transform(new Vector4f(billboard.center(), 1.0f));
        if (!(clip.w > 1.0e-6f)) {
            return;
        }
        float inverseW = 1.0f / clip.w;
        float centerX = (clip.x * inverseW * 0.5f + 0.5f) * width - 0.5f;
        float centerY = (clip.y * inverseW * 0.5f + 0.5f) * height - 0.5f;
        // The geometry stage offsets the clip position by
        // corner * worldHalfSize * projectionScale; after the perspective divide
        // the pixel half-size is size * projectionScale * height / (2 * w).
        float halfPixels = billboard.worldHalfSize() * viewProjection.m11()
                * height * 0.5f * inverseW;
        if (!(halfPixels > 0.0f) || !Float.isFinite(halfPixels)) {
            return;
        }
        float vertexAlpha = smoothstep(0.0f, 0.35f, billboard.lifetimeSeconds());
        float alpha = billboard.intensity() * vertexAlpha;
        Vector3f color = billboard.color();
        int minimumX = Math.max(0, (int) Math.floor(centerX - halfPixels));
        int maximumX = Math.min(width - 1, (int) Math.ceil(centerX + halfPixels));
        int minimumY = Math.max(0, (int) Math.floor(centerY - halfPixels));
        int maximumY = Math.min(height - 1, (int) Math.ceil(centerY + halfPixels));
        for (int y = minimumY; y <= maximumY; y++) {
            float v = (y - centerY) / halfPixels;
            for (int x = minimumX; x <= maximumX; x++) {
                float u = (x - centerX) / halfPixels;
                if (u < -1.0f || u > 1.0f || v < -1.0f || v > 1.0f) {
                    continue;
                }
                float radiusSquared = u * u + v * v;
                if (radiusSquared > 1.0f) {
                    continue;
                }
                float falloff = (float) (Math.exp(-3.5f * radiusSquared)
                        * smoothstep(1.0f, 0.65f, radiusSquared));
                float sourceAlpha = alpha * falloff;
                int offset = (y * width + x) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    float source = color.get(channel) * falloff * alpha;
                    float destination = Byte.toUnsignedInt(pixels[offset + channel]) / 255.0f;
                    float blended = source + destination * (1.0f - sourceAlpha);
                    pixels[offset + channel] = (byte) Math.round(
                            Math.max(0.0f, Math.min(1.0f, blended)) * 255.0f);
                }
            }
        }
    }

    /** GLSL smoothstep with the same edge order as the fragment shader. */
    static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    /** One opaque emitter billboard used by the pixel oracle. */
    record Billboard(Vector3f center, float lifetimeSeconds, Vector3f color,
                     float intensity, float worldHalfSize) {
        Billboard {
            center = new Vector3f(center);
            color = new Vector3f(color);
            if (!Float.isFinite(lifetimeSeconds) || lifetimeSeconds < 0.0f) {
                throw new IllegalArgumentException("lifetime must be finite and non-negative");
            }
            if (!Float.isFinite(intensity) || intensity < 0.0f) {
                throw new IllegalArgumentException("intensity must be finite and non-negative");
            }
            if (!Float.isFinite(worldHalfSize) || worldHalfSize <= 0.0f) {
                throw new IllegalArgumentException("worldHalfSize must be positive");
            }
        }

        @Override
        public Vector3f center() {
            return new Vector3f(center);
        }

        @Override
        public Vector3f color() {
            return new Vector3f(color);
        }
    }

    /** @return a mutable copy of the drawn-byte scene, for order comparisons */
    static List<Billboard> reversed(List<Billboard> billboards) {
        List<Billboard> result = new ArrayList<>(billboards);
        java.util.Collections.reverse(result);
        return result;
    }
}
