package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;

import java.util.Objects;

/**
 * Candidate-first, double-buffered TAA history.  Each slot stores the resolved
 * linear HDR color and the current view-space linear depth it was produced
 * from.  The read slot is never replaced until the whole frame has committed
 * successfully; resize/rebuild allocates a complete candidate first.
 */
final class TaaHistory implements AutoCloseable {
    private final RenderFormat colorFormat;
    private Framebuffer[] slots;
    private int readIndex;
    private boolean valid;
    private boolean prepared;

    TaaHistory(int width, int height, RenderFormat colorFormat) {
        this.colorFormat = Objects.requireNonNull(colorFormat, "colorFormat");
        this.slots = createPair(width, height, colorFormat);
    }

    Framebuffer readFramebuffer() {
        ensureOpen();
        return slots[readIndex];
    }

    Framebuffer writeFramebuffer() {
        ensureOpen();
        return slots[1 - readIndex];
    }

    int readColorTexture() {
        return readFramebuffer().colorAttachment(0);
    }

    int readDepthTexture() {
        return readFramebuffer().colorAttachment(1);
    }

    int writeColorTexture() {
        return writeFramebuffer().colorAttachment(0);
    }

    int writeDepthTexture() {
        return writeFramebuffer().colorAttachment(1);
    }

    boolean valid() {
        return valid;
    }

    /** Marks the write slot as this frame's candidate.  Does not swap anything. */
    void prepareFrame() {
        ensureOpen();
        prepared = true;
    }

    /** Publishes the candidate only after the frame succeeded as a whole. */
    void commitSuccessfulFrame() {
        ensureOpen();
        if (!prepared) return;
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
        if (Boolean.getBoolean("haikalat.test.failTaaResizeAllocation")) {
            System.clearProperty("haikalat.test.failTaaResizeAllocation");
            throw new IllegalStateException("injected TAA history resize allocation failure");
        }
        return new ResizeCandidate(this, createPair(width, height, colorFormat));
    }

    void commitResize(ResizeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
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
        private final TaaHistory owner;
        private Framebuffer[] candidate;
        private Framebuffer[] retired;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(TaaHistory owner, Framebuffer[] candidate) {
            this.owner = owner;
            this.candidate = candidate;
        }

        private void commitInto(TaaHistory expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("TAA resize candidate belongs to another history");
            }
            if (closed) throw new IllegalStateException("TAA resize candidate is closed");
            if (committed) throw new IllegalStateException("TAA resize candidate already committed");
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
            if (closed) return;
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
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } finally {
                    retired = null;
                }
            }
            if (failure != null) throw failure;
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
        if (slots == null) throw new IllegalStateException("TAA history is closed");
    }

    private static Framebuffer[] createPair(int width, int height, RenderFormat colorFormat) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("TAA history extent must be positive");
        }
        Framebuffer first = null;
        try {
            first = create(width, height, colorFormat);
            Framebuffer second = create(width, height, colorFormat);
            return new Framebuffer[]{first, second};
        } catch (RuntimeException failure) {
            if (first != null) first.close();
            throw failure;
        }
    }

    private static Framebuffer create(int width, int height, RenderFormat colorFormat) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(colorFormat)
                .colorTexture(RenderFormat.R32F)
                .build());
    }

    private static void closePair(Framebuffer[] pair) {
        if (pair == null) return;
        RuntimeException failure = null;
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
        if (failure != null) throw failure;
    }
}
