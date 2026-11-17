package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * The single source of truth for temporal projection jitter.  Rasterisation uses
 * the jittered projection while motion vectors use the stable projection; the
 * resolve pass converts the per-frame jitter into UV space with the sign that
 * the actual projection produces (locked by TemporalJitterTest).
 *
 * <p>Two deterministic four-phase sequences share the same sub-pixel pattern
 * ({@code (+-0.25, +-0.25)} pixels):</p>
 * <ul>
 *   <li>the native TAA sequence, enabled only by {@link AntiAliasingMode#TAA} and
 *       keyed by the render frame index ({@link #offsetPixels});</li>
 *   <li>the internal reduced-scene sequence used when {@code renderScale < 1},
 *       keyed by the generation-owned reduced-jitter phase
 *       ({@link #reducedOffsetPixels}).  TAA is rejected at a reduced scale, so
 *       the two sequences can never be active on the same raster.</li>
 * </ul>
 *
 * <h2>Reduced-raster jitter space</h2>
 *
 * <p>The reduced scene raster shares the output camera projection: the frozen
 * frame camera already carries the output-aspect stable projection and the
 * reduced passes only change the raster extent.  A normalized raster UV
 * therefore selects the same view ray in the reduced (R) and output (O)
 * rasters, and a normalized UV delta is dimensionally shared by both.</p>
 *
 * <p>{@link #applyReducedProjection} translates clip XY by
 * {@code -delta * clip.w} with {@code delta = 2 * pixels / extent}, so the
 * rasterised geometry moves by {@code -pixels / extent} in normalized UV.  A
 * surface that would be rasterised at the stable output position {@code s}
 * therefore appears at {@code s + uvOffset}, where
 * {@code uvOffset = -pixels / extent} (see {@link #reducedUvOffset}).  The
 * existing upscaler samples {@code outputUv + uCurrentJitter}, so the uniform it
 * must receive is exactly that displacement {@code uvOffset}: the jitter has to
 * be normalized by the <b>reduced</b> extent (quarter of a reduced raster pixel
 * at that raster's scale), never by the output extent, and it is not an
 * output-raster jitter.  At a 0.5 scale a {@code 0.25} reduced-pixel offset is
 * {@code 0.5} output pixels.</p>
 */
public final class TemporalJitter {
    /** Number of phases in the deterministic reduced-raster sequence. */
    public static final int REDUCED_PHASE_COUNT = 4;
    /** Sub-pixel magnitude of every phase in reduced-raster pixels. */
    static final float PHASE_PIXEL_OFFSET = 0.25f;

    private TemporalJitter() {
    }

    static boolean enabled(AntiAliasingMode mode) {
        return mode == AntiAliasingMode.TAA;
    }

    /** Sub-pixel offset in pixels, in the range [-0.25, 0.25]. */
    static Vector2f offsetPixels(AntiAliasingMode mode, int frameIndex, Vector2f destination) {
        Vector2f result = destination == null ? new Vector2f() : destination;
        if (!enabled(mode)) {
            return result.set(0.0f, 0.0f);
        }
        return patternPixels(frameIndex & 3, result);
    }

    /**
     * Sub-pixel offset in reduced-raster pixels for one phase of the internal
     * reduced-scene sequence.
     *
     * <p>The four phases are the same rotationally symmetric pattern as the
     * native TAA sequence, so the set of sampled sub-pixel positions is
     * deterministic and identical in shape.  A phase outside
     * {@code [0, REDUCED_PHASE_COUNT)} wraps.</p>
     *
     * @param phase  deterministic reduced-raster phase (normally {@code 0..3})
     * @param destination optional destination vector
     * @return the offset in reduced-raster pixels, in {@code [-0.25, 0.25]}
     */
    public static Vector2f reducedOffsetPixels(int phase, Vector2f destination) {
        Vector2f result = destination == null ? new Vector2f() : destination;
        return patternPixels(Math.floorMod(phase, REDUCED_PHASE_COUNT), result);
    }

    /** Applies the frozen projection delta used by CameraUniforms. */
    public static void applyProjection(Matrix4f projection, int width, int height,
                                AntiAliasingMode mode, int frameIndex) {
        if (!enabled(mode) || width <= 0 || height <= 0) {
            return;
        }
        applyProjectionPixels(projection, width, height,
                patternX(frameIndex), patternY(frameIndex));
    }

    /**
     * Applies one phase of the internal reduced-scene jitter to the projected
     * camera used by the reduced raster passes.
     *
     * <p>See the class documentation for the space and sign derivation.  This
     * is the rasterisation half of the contract; {@link #reducedUvOffset}
     * returns the matching sample displacement that the internal temporal
     * upscaler must receive in {@code uCurrentJitter}.</p>
     *
     * @param projection projection to mutate in place
     * @param width      reduced raster width in pixels
     * @param height     reduced raster height in pixels
     * @param phase      deterministic reduced-raster phase
     */
    public static void applyReducedProjection(Matrix4f projection, int width, int height,
                                              int phase) {
        if (width <= 0 || height <= 0) {
            return;
        }
        applyProjectionPixels(projection, width, height,
                patternX(phase), patternY(phase));
    }

    /**
     * UV-space offset produced by the jittered projection relative to the
     * stable projection. Clip XY is translated by -delta * clip W, so the
     * NDC offset is independent of depth for both perspective and orthographic
     * cameras.
     */
    public static Vector2f uvOffset(AntiAliasingMode mode, int frameIndex, int width, int height,
                             Vector2f destination) {
        Vector2f result = destination == null ? new Vector2f() : destination;
        if (!enabled(mode) || width <= 0 || height <= 0) {
            return result.set(0.0f, 0.0f);
        }
        Vector2f pixels = offsetPixels(mode, frameIndex, null);
        return result.set(-pixels.x / width, -pixels.y / height);
    }

    /**
     * Geometry displacement in reduced-raster normalized UV produced by
     * {@link #applyReducedProjection} for the same phase.
     *
     * <p>This is the value the internal temporal upscaler must receive as
     * {@code uCurrentJitter}: a surface at the stable output UV {@code s} is
     * rasterised at {@code s + reducedUvOffset(...)}, and the shader samples
     * {@code outputUv + uCurrentJitter}.  The offset is normalized by the
     * reduced extent; it is deliberately not expressed in output pixels.</p>
     *
     * @param phase  deterministic reduced-raster phase
     * @param width  reduced raster width in pixels
     * @param height reduced raster height in pixels
     * @param destination optional destination vector
     * @return the displacement in reduced-raster normalized UV
     */
    public static Vector2f reducedUvOffset(int phase, int width, int height,
                                           Vector2f destination) {
        Vector2f result = destination == null ? new Vector2f() : destination;
        if (width <= 0 || height <= 0) {
            return result.set(0.0f, 0.0f);
        }
        return result.set(-patternX(phase) / width, -patternY(phase) / height);
    }

    private static float patternX(int phase) {
        return (phase & 1) == 0 ? -PHASE_PIXEL_OFFSET : PHASE_PIXEL_OFFSET;
    }

    private static float patternY(int phase) {
        return (phase & 2) == 0 ? -PHASE_PIXEL_OFFSET : PHASE_PIXEL_OFFSET;
    }

    private static Vector2f patternPixels(int phase, Vector2f destination) {
        return destination.set(patternX(phase), patternY(phase));
    }

    private static void applyProjectionPixels(Matrix4f projection, int width, int height,
                                              float pixelX, float pixelY) {
        float deltaX = pixelX * 2.0f / width;
        float deltaY = pixelY * 2.0f / height;
        // Translate clip XY by -delta * clip W. Perspective changes m20/m21;
        // orthographic changes m30/m31, retaining a depth-independent offset.
        projection.m00(projection.m00() - deltaX * projection.m03());
        projection.m10(projection.m10() - deltaX * projection.m13());
        projection.m20(projection.m20() - deltaX * projection.m23());
        projection.m30(projection.m30() - deltaX * projection.m33());
        projection.m01(projection.m01() - deltaY * projection.m03());
        projection.m11(projection.m11() - deltaY * projection.m13());
        projection.m21(projection.m21() - deltaY * projection.m23());
        projection.m31(projection.m31() - deltaY * projection.m33());
    }
}
