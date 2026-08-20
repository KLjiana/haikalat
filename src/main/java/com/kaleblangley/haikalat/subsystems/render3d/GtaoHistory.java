package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

/**
 * Candidate-first, double-buffered GTAO history.  The read buffer is never
 * replaced until the owning frame has completed successfully.
 */
final class GtaoHistory implements AutoCloseable {
    private Framebuffer[] buffers;
    private int readIndex;
    private boolean valid;
    private boolean staged;

    GtaoHistory(int width, int height) {
        buffers = createPair(width, height);
    }

    Framebuffer readFramebuffer() {
        ensureOpen();
        return buffers[readIndex];
    }

    Framebuffer writeFramebuffer() {
        ensureOpen();
        return buffers[1 - readIndex];
    }

    boolean valid() {
        return valid;
    }

    void invalidate() {
        valid = false;
        staged = false;
    }

    void stage(CommandBuffer commands, Framebuffer source) {
        ensureOpen();
        if (source == null || source.width() != writeFramebuffer().width()
                || source.height() != writeFramebuffer().height()) {
            throw new IllegalArgumentException("GTAO history source extent does not match half resolution");
        }
        commands.blitColor(source, writeFramebuffer())
                .bindFramebuffer(source)
                .viewport(0, 0, source.width(), source.height());
        staged = true;
    }

    void commit() {
        if (!staged) return;
        readIndex = 1 - readIndex;
        valid = true;
        staged = false;
    }

    void discard() {
        staged = false;
    }

    ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0
                || buffers[0].width() == width && buffers[0].height() == height) {
            return new ResizeCandidate(this, null);
        }
        if (Boolean.getBoolean("haikalat.test.failGtaoResizeAllocation")) {
            System.clearProperty("haikalat.test.failGtaoResizeAllocation");
            throw new IllegalStateException("injected GTAO resize allocation failure");
        }
        return new ResizeCandidate(this, createPair(width, height));
    }

    void commitResize(ResizeCandidate candidate) {
        java.util.Objects.requireNonNull(candidate, "candidate").commitInto(this);
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
        private final GtaoHistory owner;
        private Framebuffer[] candidate;
        private Framebuffer[] retired;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(GtaoHistory owner, Framebuffer[] candidate) {
            this.owner = owner;
            this.candidate = candidate;
        }

        private void commitInto(GtaoHistory expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("GTAO resize candidate belongs to another history");
            }
            if (closed) throw new IllegalStateException("GTAO resize candidate is closed");
            if (committed) throw new IllegalStateException("GTAO resize candidate already committed");
            if (candidate != null) {
                retired = owner.buffers;
                owner.buffers = candidate;
                candidate = null;
                owner.readIndex = 0;
                owner.valid = false;
                owner.staged = false;
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
        Framebuffer[] previous = buffers;
        buffers = null;
        valid = false;
        staged = false;
        closePair(previous);
    }

    private void ensureOpen() {
        if (buffers == null) throw new IllegalStateException("GTAO history is closed");
    }

    private static Framebuffer[] createPair(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("GTAO history extent must be positive");
        }
        Framebuffer first = null;
        try {
            first = create(width, height);
            Framebuffer second = create(width, height);
            return new Framebuffer[]{first, second};
        } catch (RuntimeException failure) {
            if (first != null) first.close();
            throw failure;
        }
    }

    private static Framebuffer create(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.RG16F)
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
