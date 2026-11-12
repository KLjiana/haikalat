package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;

/**
 * Candidate-first, double-buffered reconstruction history for the volumetric
 * cloud layer.
 *
 * <p>Each slot owns two full-resolution attachments:</p>
 * <ol>
 *   <li>{@code cloud} (RGBA16F): premultiplied single scatter in {@code rgb}
 *       and transmittance in {@code a};</li>
 *   <li>{@code state} (RGBA16F): the reprojection anchor's positive view depth
 *       in {@code r}, the frame exposure in {@code g}, the history weight that
 *       was actually applied in {@code b} and an anchor-valid flag in
 *       {@code a}.</li>
 * </ol>
 *
 * <p>The read slot is never replaced until the whole frame has committed
 * successfully. A failed frame keeps the previous read slot and the previous
 * camera/exposure so the next frame reprojects from the last successful frame.
 * A resize allocates a complete candidate first; the retired slots are closed
 * by the candidate handle, so a failed allocation leaves the active history
 * untouched.</p>
 */
final class CloudHistory implements AutoCloseable {
    private static final String FAIL_RESIZE_PROPERTY =
            "haikalat.test.failVolumetricCloudResizeAllocation";

    private Framebuffer[] slots;
    private int readIndex;
    private boolean valid;
    private boolean prepared;

    CloudHistory(int width, int height) {
        slots = createPair(width, height);
    }

    Framebuffer readFramebuffer() {
        ensureOpen();
        return slots[readIndex];
    }

    Framebuffer writeFramebuffer() {
        ensureOpen();
        return slots[1 - readIndex];
    }

    int readCloudTexture() {
        return readFramebuffer().colorAttachment(0);
    }

    int readStateTexture() {
        return readFramebuffer().colorAttachment(1);
    }

    int writeCloudTexture() {
        return writeFramebuffer().colorAttachment(0);
    }

    int writeStateTexture() {
        return writeFramebuffer().colorAttachment(1);
    }

    boolean valid() {
        return valid;
    }

    int width() {
        ensureOpen();
        return slots[0].width();
    }

    int height() {
        ensureOpen();
        return slots[0].height();
    }

    /** Marks the write slot as this frame's candidate. Does not swap anything. */
    void prepareFrame() {
        ensureOpen();
        prepared = true;
    }

    /** Publishes the candidate only after the whole frame succeeded. */
    void commitSuccessfulFrame() {
        ensureOpen();
        if (!prepared) {
            return;
        }
        readIndex = 1 - readIndex;
        valid = true;
        prepared = false;
    }

    void discardFrame() {
        prepared = false;
    }

    void invalidate() {
        valid = false;
        prepared = false;
    }

    ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0
                || slots[0].width() == width && slots[0].height() == height) {
            return new ResizeCandidate(this, null);
        }
        if (Boolean.getBoolean(FAIL_RESIZE_PROPERTY)) {
            System.clearProperty(FAIL_RESIZE_PROPERTY);
            throw new IllegalStateException("injected volumetric cloud history resize failure");
        }
        return new ResizeCandidate(this, createPair(width, height));
    }

    void commitResize(ResizeCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("resize candidate must not be null");
        }
        candidate.commitInto(this);
    }

    void resize(int width, int height) {
        ResizeCandidate candidate = prepareResize(width, height);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    static final class ResizeCandidate implements AutoCloseable {
        private final CloudHistory owner;
        private Framebuffer[] candidate;
        private Framebuffer[] retired;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(CloudHistory owner, Framebuffer[] candidate) {
            this.owner = owner;
            this.candidate = candidate;
        }

        private void commitInto(CloudHistory expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "volumetric cloud resize candidate belongs to another history");
            }
            if (closed) {
                throw new IllegalStateException("volumetric cloud resize candidate is closed");
            }
            if (committed) {
                throw new IllegalStateException(
                        "volumetric cloud resize candidate already committed");
            }
            if (candidate != null) {
                retired = owner.slots;
                owner.slots = candidate;
                candidate = null;
                owner.readIndex = 0;
                owner.valid = false;
                owner.prepared = false;
            }
            committed = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            if (candidate != null) {
                try {
                    closePair(candidate);
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                } finally {
                    candidate = null;
                }
            }
            if (retired != null) {
                try {
                    closePair(retired);
                } catch (RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                } finally {
                    retired = null;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Override
    public void close() {
        Framebuffer[] previous = slots;
        slots = null;
        valid = false;
        prepared = false;
        closePair(previous);
    }

    private void ensureOpen() {
        if (slots == null) {
            throw new IllegalStateException("volumetric cloud history is closed");
        }
    }

    private static Framebuffer[] createPair(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("volumetric cloud history extent must be positive");
        }
        Framebuffer first = null;
        try {
            first = create(width, height);
            Framebuffer second = create(width, height);
            return new Framebuffer[]{first, second};
        } catch (RuntimeException failure) {
            if (first != null) {
                first.close();
            }
            throw failure;
        }
    }

    private static Framebuffer create(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.RGBA16F)
                .colorTexture(RenderFormat.RGBA16F)
                .build());
    }

    private static void closePair(Framebuffer[] pair) {
        if (pair == null) {
            return;
        }
        RuntimeException failure = null;
        for (int index = pair.length - 1; index >= 0; index--) {
            Framebuffer framebuffer = pair[index];
            if (framebuffer == null) {
                continue;
            }
            try {
                framebuffer.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
