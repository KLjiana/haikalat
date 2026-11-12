package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;

import java.util.Objects;

/**
 * Candidate-first GI storage for one temporal SSGI owner.
 *
 * <p>The storage owns:</p>
 * <ol>
 *   <li>one private RGBA16F staging target that receives the current spatial
 *       added bounce;</li>
 *   <li>two history slots, each with an RGBA16F colour attachment (the
 *       accumulated added bounce), an RGBA16F state attachment (positive
 *       view-space depth in {@code r}, octahedral normal in {@code gb} and a
 *       surface flag in {@code a}) and an RGBA16F composite attachment (the
 *       colour-chain output, base plus accumulated bounce).</li>
 * </ol>
 *
 * <p>A new storage starts with {@code valid == false}. The temporal shader
 * binds the history textures but only samples them while {@code uHistoryValid}
 * is non-zero, so uninitialized history is never read. The write slot is fully
 * overwritten by one full-screen draw before it can be published, and a failed
 * frame keeps the previous read slot. Resize allocates a complete candidate
 * first and the retired slots are closed by the candidate handle.</p>
 */
final class SsgiTemporalStorage implements AutoCloseable {
    static final String FAIL_RESIZE_PROPERTY =
            "haikalat.test.failSsgiTemporalResizeAllocation";

    private Framebuffer spatial;
    private Framebuffer[] slots;
    private int readIndex;
    private boolean valid;
    private boolean prepared;
    private int acceptedFrames;

    SsgiTemporalStorage(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("temporal SSGI extent must be positive");
        }
        Framebuffer createdSpatial = null;
        try {
            createdSpatial = create(width, height, 1);
            spatial = createdSpatial;
            slots = createPair(width, height);
        } catch (RuntimeException failure) {
            if (createdSpatial != null) createdSpatial.close();
            throw failure;
        }
    }

    Framebuffer spatialFramebuffer() {
        ensureOpen();
        return spatial;
    }

    Framebuffer readFramebuffer() {
        ensureOpen();
        return slots[readIndex];
    }

    Framebuffer writeFramebuffer() {
        ensureOpen();
        return slots[1 - readIndex];
    }

    int writeColorTexture() {
        return writeFramebuffer().colorAttachment(2);
    }

    int readHistoryColorTexture() {
        return readFramebuffer().colorAttachment(0);
    }

    int readStateTexture() {
        return readFramebuffer().colorAttachment(1);
    }

    boolean valid() {
        return valid;
    }

    boolean prepared() {
        return prepared;
    }

    int acceptedFrames() {
        return acceptedFrames;
    }

    int width() {
        return slots[0].width();
    }

    int height() {
        return slots[0].height();
    }

    /** Marks the write slot as this frame's candidate. Does not swap anything. */
    void prepareFrame() {
        ensureOpen();
        if (prepared) {
            throw new IllegalStateException("temporal SSGI frame has already begun");
        }
        prepared = true;
    }

    /** Publishes the candidate only after the whole frame succeeded. */
    void commitSuccessfulFrame() {
        ensureOpen();
        if (!prepared) return;
        readIndex = 1 - readIndex;
        valid = true;
        prepared = false;
        acceptedFrames = Math.min(acceptedFrames + 1,
                SsgiSettings.MAXIMUM_HISTORY_FRAMES);
    }

    void discardFrame() {
        prepared = false;
    }

    /** Stops trusting the previous history until a new frame is committed. */
    void invalidate() {
        valid = false;
        prepared = false;
        acceptedFrames = 0;
    }

    /**
     * Discards the previous history while keeping the current frame candidate
     * marked as prepared. Used when a camera cut is detected during recording:
     * the current frame still commits, but it starts a fresh history.
     */
    void invalidateForCut() {
        valid = false;
        acceptedFrames = 0;
    }

    ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0
                || slots[0].width() == width && slots[0].height() == height) {
            return new ResizeCandidate(this, null);
        }
        if (Boolean.getBoolean(FAIL_RESIZE_PROPERTY)) {
            System.clearProperty(FAIL_RESIZE_PROPERTY);
            throw new IllegalStateException("injected temporal SSGI resize failure");
        }
        return new ResizeCandidate(this, new SsgiTemporalStorage(width, height));
    }

    void commitResize(ResizeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    static final class ResizeCandidate implements AutoCloseable {
        private final SsgiTemporalStorage owner;
        private Framebuffer candidateSpatial;
        private Framebuffer[] candidateSlots;
        private Framebuffer retiredSpatial;
        private Framebuffer[] retiredSlots;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(SsgiTemporalStorage owner, SsgiTemporalStorage candidate) {
            this.owner = owner;
            if (candidate != null) {
                candidateSpatial = candidate.spatial;
                candidateSlots = candidate.slots;
                // The candidate handle owns the resources now; the wrapper is
                // never used again.
                candidate.spatial = null;
                candidate.slots = null;
                candidate.valid = false;
                candidate.acceptedFrames = 0;
            }
        }

        private void commitInto(SsgiTemporalStorage expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "temporal SSGI resize candidate belongs to another storage");
            }
            if (closed) {
                throw new IllegalStateException("temporal SSGI resize candidate is closed");
            }
            if (committed) {
                throw new IllegalStateException(
                        "temporal SSGI resize candidate already committed");
            }
            if (candidateSlots != null) {
                retiredSpatial = owner.spatial;
                retiredSlots = owner.slots;
                owner.spatial = candidateSpatial;
                owner.slots = candidateSlots;
                owner.readIndex = 0;
                owner.valid = false;
                owner.prepared = false;
                owner.acceptedFrames = 0;
                candidateSpatial = null;
                candidateSlots = null;
            }
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            if (candidateSlots != null) {
                try {
                    closeResources(candidateSpatial, candidateSlots);
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                } finally {
                    candidateSpatial = null;
                    candidateSlots = null;
                }
            }
            if (retiredSlots != null) {
                try {
                    closeResources(retiredSpatial, retiredSlots);
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } finally {
                    retiredSpatial = null;
                    retiredSlots = null;
                }
            }
            if (failure != null) throw failure;
        }
    }

    @Override
    public void close() {
        Framebuffer previousSpatial = spatial;
        Framebuffer[] previousSlots = slots;
        spatial = null;
        slots = null;
        valid = false;
        prepared = false;
        acceptedFrames = 0;
        closeResources(previousSpatial, previousSlots);
    }

    private void ensureOpen() {
        if (slots == null) throw new IllegalStateException("temporal SSGI storage is closed");
    }

    private static Framebuffer[] createPair(int width, int height) {
        Framebuffer first = null;
        try {
            first = create(width, height, 3);
            Framebuffer second = create(width, height, 3);
            return new Framebuffer[]{first, second};
        } catch (RuntimeException failure) {
            if (first != null) first.close();
            throw failure;
        }
    }

    private static Framebuffer create(int width, int height, int colorAttachments) {
        FramebufferDescriptor.Builder builder = FramebufferDescriptor.builder(width, height);
        for (int index = 0; index < colorAttachments; index++) {
            builder.colorTexture(RenderFormat.RGBA16F);
        }
        return Framebuffer.fromDescriptor(builder.build());
    }

    private static void closeResources(Framebuffer spatial, Framebuffer[] pair) {
        RuntimeException failure = null;
        if (spatial != null) {
            try {
                spatial.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
        if (pair != null) {
            for (int index = pair.length - 1; index >= 0; index--) {
                Framebuffer framebuffer = pair[index];
                if (framebuffer == null) continue;
                try {
                    framebuffer.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) throw failure;
    }
}
