package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Structured per-frame inputs for one {@link StochasticTransparencyPass} run.
 *
 * <p>All matrices are copied defensively.  {@code view} and {@code projection}
 * rasterise the selected transparent surfaces; they must be the <em>stable</em>
 * (jitter-free) matrices that produced {@code sceneDepthTexture}.  Temporal
 * accumulation reprojects the current surface through {@code inverseProjection}
 * and {@code inverseView}, then through the previous successful stable
 * view-projection held by the pass.  The pass never reads {@code view} or
 * {@code projection} back from the GPU and never writes the borrowed depth.</p>
 *
 * <p>{@code sceneDepthTexture} is a borrowed single-sample scene depth texture
 * (the shared depth produced by the host).  Zero means "not provided": that is
 * legal only while temporal accumulation is disabled, because reprojection and
 * depth rejection have no meaning without a surface depth.  The depth texture
 * itself is never bound as a render target by the pass.</p>
 *
 * <p>Jitter: this version is specified for a stable raster.  A host that
 * rasterises the scene with TAA jitter must supply the matching unjittered
 * depth (or delay the stochastic collection until after the jittered pass); the
 * first version does not re-apply a previous-frame UV jitter.</p>
 */
public record StochasticTransparencyInputs(
        Matrix4f view,
        Matrix4f projection,
        Matrix4f inverseProjection,
        Matrix4f inverseView,
        int sceneDepthTexture) {

    public StochasticTransparencyInputs {
        view = requireFinite(view, "view");
        projection = requireFinite(projection, "projection");
        inverseProjection = requireFinite(inverseProjection, "inverseProjection");
        inverseView = requireFinite(inverseView, "inverseView");
        if (sceneDepthTexture < 0) {
            throw new IllegalArgumentException("sceneDepthTexture must be zero or a texture name");
        }
    }

    /** @return true when a borrowed scene depth is available for temporal reprojection */
    public boolean hasSceneDepth() {
        return sceneDepthTexture > 0;
    }

    private static Matrix4f requireFinite(Matrix4f matrix, String name) {
        Objects.requireNonNull(matrix, name);
        if (!matrix.isFinite()) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return new Matrix4f(matrix);
    }
}
