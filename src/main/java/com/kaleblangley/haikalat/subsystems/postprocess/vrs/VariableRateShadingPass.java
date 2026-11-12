package com.kaleblangley.haikalat.subsystems.postprocess.vrs;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingCapability;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Owner of the {@code R8UI} shading-rate image for the declared colour-chain
 * consumer.
 *
 * <p>Construction is candidate-first: the complete tile map is computed and
 * uploaded before the pass becomes visible.  Resize allocates and uploads a new
 * image in a {@link ResizeCandidate}; only {@link #commitResize} swaps it in and
 * closes the retired image, so a failed candidate leaves the active extent and
 * image untouched.</p>
 *
 * <p>The pass owns exactly one texture and no framebuffer, history or read-back.
 * {@link #recordBegin(CommandBuffer)} and {@link #recordEnd(CommandBuffer)} add
 * the typed host-state scope around the consumer draw; the executor captures
 * the previous image/palette immediately before applying this image and restores
 * it immediately after the draw, including failure paths.</p>
 */
public final class VariableRateShadingPass implements AutoCloseable {
    private final VariableRateShadingSettings settings;
    private final VariableRateShadingCapability capability;
    private Texture2D rateImage;
    private VariableRateShadingPlan plan;
    private boolean closed;

    /**
     * Allocates the rate image and uploads the initial tile map.
     *
     * @param settings   active request
     * @param capability probed capability that advertises the NV image path
     * @param width      consumer framebuffer width in pixels
     * @param height     consumer framebuffer height in pixels
     */
    public VariableRateShadingPass(VariableRateShadingSettings settings,
                                   VariableRateShadingCapability capability,
                                   int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.capability = Objects.requireNonNull(capability, "capability");
        if (!settings.active()) {
            throw new IllegalArgumentException(
                    "a variable-rate shading pass requires an active request");
        }
        if (!capability.nvImagePathAvailable()) {
            throw new IllegalArgumentException("variable-rate shading image path is unavailable: "
                    + capability.unsupportedReason());
        }
        Allocation allocation = allocate(settings, capability, width, height);
        this.plan = allocation.plan();
        this.rateImage = allocation.image();
    }

    /** @return the request this pass implements */
    public VariableRateShadingSettings settings() {
        return settings;
    }

    /** Records the begin of the host-state scope around the consumer draw. */
    public void recordBegin(CommandBuffer cmd) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        cmd.beginVariableRateShading(rateImage, plan.palette());
    }

    /** Records the end of the host-state scope after the consumer draw. */
    public void recordEnd(CommandBuffer cmd) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        cmd.endVariableRateShading();
    }

    VariableRateShadingPlan plan() {
        return plan;
    }

    Texture2D rateImage() {
        return rateImage;
    }

    /**
     * Allocates a complete resized rate image without changing the active one.
     *
     * @param width  new consumer width
     * @param height new consumer height
     * @return a candidate that must be committed or closed
     */
    public ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("resize extents must be positive");
        }
        if (width == plan.targetWidth() && height == plan.targetHeight()) {
            return new ResizeCandidate(this, null);
        }
        return new ResizeCandidate(this, allocate(settings, capability, width, height));
    }

    /**
     * Publishes a previously prepared candidate and retires the old image.
     *
     * @param candidate candidate returned by {@link #prepareResize(int, int)}
     */
    public void commitResize(ResizeCandidate candidate) {
        ensureOpen();
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    /** @return whether this pass has released its rate image */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        Texture2D image = rateImage;
        rateImage = null;
        plan = null;
        closed = true;
        if (image != null) image.close();
    }

    private static Allocation allocate(VariableRateShadingSettings settings,
                                       VariableRateShadingCapability capability,
                                       int width, int height) {
        VariableRateShadingPlan plan = VariableRateShadingPlan.create(width, height, settings,
                capability.texelWidth(), capability.texelHeight(), capability.paletteSize());
        if (!plan.selective()) {
            throw new IllegalArgumentException(
                    "the consumer extent cannot host a selective shading-rate split");
        }
        Texture2D image = Texture2D.createR8ui(plan.tilesX(), plan.tilesY());
        try {
            byte[] indices = plan.tileIndices();
            ByteBuffer payload = ByteBuffer.allocateDirect(indices.length);
            payload.put(indices).flip();
            image.uploadRegion(0, 0, plan.tilesX(), plan.tilesY(), payload);
            return new Allocation(plan, image);
        } catch (RuntimeException | Error failure) {
            try {
                image.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void replace(Allocation allocation) {
        Texture2D retired = rateImage;
        rateImage = allocation.image();
        plan = allocation.plan();
        if (retired != null) retired.close();
    }

    private void ensureOpen() {
        if (closed) throw new GlException("VariableRateShadingPass is closed");
    }

    private record Allocation(VariableRateShadingPlan plan, Texture2D image) {
    }

    /**
     * Candidate-first resize transaction for the rate image.
     *
     * <p>{@link #close()} on an uncommitted candidate releases the candidate
     * image only; the active pass keeps its previous extent.</p>
     */
    public static final class ResizeCandidate implements AutoCloseable {
        private final VariableRateShadingPass owner;
        private Allocation allocation;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(VariableRateShadingPass owner, Allocation allocation) {
            this.owner = owner;
            this.allocation = allocation;
        }

        private void commitInto(VariableRateShadingPass expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "shading-rate resize candidate belongs to another pass");
            }
            if (closed) throw new IllegalStateException("shading-rate resize candidate is closed");
            if (committed) {
                throw new IllegalStateException("shading-rate resize candidate already committed");
            }
            if (allocation == null) {
                committed = true;
                return;
            }
            Allocation candidate = allocation;
            allocation = null;
            owner.replace(candidate);
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            Allocation candidate = allocation;
            allocation = null;
            if (!committed && candidate != null) {
                candidate.image().close();
            }
        }
    }
}
