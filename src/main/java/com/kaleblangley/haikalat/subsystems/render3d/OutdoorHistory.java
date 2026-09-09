package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.RenderFormat;

import java.util.Objects;

/**
 * Candidate-first single-color history used by the outdoor volumetric
 * temporal chain.  TAA uses the richer {@link TaaHistory} instead.
 */
final class OutdoorHistory implements AutoCloseable {
    private final RenderFormat format;
    private Framebuffer framebuffer;
    private boolean valid;

    OutdoorHistory(int width, int height) {
        this(width, height, RenderFormat.RGBA8);
    }

    OutdoorHistory(int width, int height, RenderFormat format) {
        this.format = format;
        this.framebuffer = createFramebuffer(width, height);
    }

    Framebuffer framebuffer() {
        return framebuffer;
    }

    float historyWeight() {
        return valid ? 0.90f : 0.0f;
    }

    void markValid() {
        valid = true;
    }

    boolean valid() {
        return valid;
    }

    void invalidate() {
        ensureOpen();
        valid = false;
    }

    ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0
                || framebuffer.width() == width && framebuffer.height() == height) {
            return new ResizeCandidate(this, null);
        }
        Framebuffer candidate = createFramebuffer(width, height);
        return new ResizeCandidate(this, candidate);
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
        private final OutdoorHistory owner;
        private Framebuffer candidate;
        private Framebuffer retired;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(OutdoorHistory owner, Framebuffer candidate) {
            this.owner = owner;
            this.candidate = candidate;
        }

        private void commitInto(OutdoorHistory expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("outdoor resize candidate belongs to another history");
            }
            if (closed) throw new IllegalStateException("outdoor resize candidate is closed");
            if (committed) throw new IllegalStateException("outdoor resize candidate already committed");
            if (candidate != null) {
                retired = owner.framebuffer;
                owner.framebuffer = candidate;
                candidate = null;
                owner.valid = false;
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
                    candidate.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                } finally {
                    candidate = null;
                }
            }
            if (retired != null) {
                try {
                    retired.close();
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
        if (framebuffer != null) {
            framebuffer.close();
            framebuffer = null;
        }
        valid = false;
    }

    private Framebuffer createFramebuffer(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(format)
                .build());
    }

    private void ensureOpen() {
        if (framebuffer == null) throw new IllegalStateException("outdoor history is closed");
    }
}
