package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;

import java.util.Objects;

/**
 * Candidate-first, double-buffered output storage for temporal upscaling.
 *
 * <p>Each slot owns two colour attachments:</p>
 * <ol>
 *   <li>{@code color} (RGBA16F): the resolved linear colour before sharpening;
 *       this is the history source for the next frame;</li>
 *   <li>{@code state} (RGBA16F): positive view-space depth in {@code r}, the
 *       frame exposure scalar in {@code g} and a surface-valid flag in
 *       {@code a}.</li>
 * </ol>
 *
 * <p>When sharpening is enabled the history also owns one single-buffered
 * {@code RGBA16F} sharpen target.  The read slot is never replaced until the
 * whole frame has committed successfully; resize/rebuild allocates a complete
 * candidate first and the retired attachments are closed by the candidate
 * handle.  A failed frame keeps the previous read slot; a failed resize leaves
 * the active attachments untouched.</p>
 */
final class TemporalUpscalingHistory implements AutoCloseable {
    private static final String FAIL_RESIZE_PROPERTY =
            "haikalat.test.failTemporalUpscalingResizeAllocation";

    private final boolean sharpening;
    private Framebuffer[] slots;
    private Framebuffer sharpenTarget;
    private int readIndex;
    private boolean valid;
    private boolean prepared;

    TemporalUpscalingHistory(int width, int height, boolean sharpening) {
        this.sharpening = sharpening;
        slots = createPair(width, height);
        if (sharpening) {
            sharpenTarget = createSharpenTarget(width, height);
        }
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

    int readStateTexture() {
        return readFramebuffer().colorAttachment(1);
    }

    int writeColorTexture() {
        return writeFramebuffer().colorAttachment(0);
    }

    int writeStateTexture() {
        return writeFramebuffer().colorAttachment(1);
    }

    /** @return the sharpened output framebuffer, or {@code null} when sharpening is off */
    Framebuffer sharpenFramebuffer() {
        ensureOpen();
        return sharpenTarget;
    }

    /** @return the sharpened output texture, or {@code 0} when sharpening is off */
    int sharpenColorTexture() {
        return sharpenTarget == null ? 0 : sharpenTarget.colorAttachment(0);
    }

    /** @return the texture downstream passes must sample for the final upscaled colour */
    int outputColorTexture() {
        return sharpening ? sharpenColorTexture() : writeColorTexture();
    }

    /** @return the committed final output after {@link #commitSuccessfulFrame()} */
    Framebuffer readOutputFramebuffer() {
        return sharpening ? sharpenTarget : readFramebuffer();
    }

    /** @return the committed final output texture after {@link #commitSuccessfulFrame()} */
    int readOutputColorTexture() {
        return sharpening ? sharpenColorTexture() : readColorTexture();
    }

    boolean valid() {
        return valid;
    }

    boolean prepared() {
        return prepared;
    }

    int width() {
        ensureOpen();
        return slots[0].width();
    }

    int height() {
        ensureOpen();
        return slots[0].height();
    }

    /** Marks the write slot as this frame's candidate.  Does not swap anything. */
    void prepareFrame() {
        ensureOpen();
        prepared = true;
    }

    /** Publishes the candidate only after the whole frame succeeded. */
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
            return new ResizeCandidate(this, null, null);
        }
        if (Boolean.getBoolean(FAIL_RESIZE_PROPERTY)) {
            System.clearProperty(FAIL_RESIZE_PROPERTY);
            throw new IllegalStateException("injected temporal-upscaling history resize failure");
        }
        Framebuffer[] candidate = createPair(width, height);
        Framebuffer candidateSharpen = null;
        try {
            if (sharpening) {
                candidateSharpen = createSharpenTarget(width, height);
            }
            return new ResizeCandidate(this, candidate, candidateSharpen);
        } catch (RuntimeException | Error failure) {
            closePair(candidate);
            throw failure;
        }
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
        private final TemporalUpscalingHistory owner;
        private Framebuffer[] candidate;
        private Framebuffer candidateSharpen;
        private Framebuffer[] retired;
        private Framebuffer retiredSharpen;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(TemporalUpscalingHistory owner, Framebuffer[] candidate,
                                Framebuffer candidateSharpen) {
            this.owner = owner;
            this.candidate = candidate;
            this.candidateSharpen = candidateSharpen;
        }

        private void commitInto(TemporalUpscalingHistory expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "temporal-upscaling resize candidate belongs to another history");
            }
            if (closed) {
                throw new IllegalStateException(
                        "temporal-upscaling resize candidate is closed");
            }
            if (committed) {
                throw new IllegalStateException(
                        "temporal-upscaling resize candidate already committed");
            }
            if (candidate != null) {
                retired = owner.slots;
                retiredSharpen = owner.sharpenTarget;
                owner.slots = candidate;
                candidate = null;
                owner.sharpenTarget = candidateSharpen;
                candidateSharpen = null;
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
            if (candidateSharpen != null) {
                try {
                    candidateSharpen.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } finally {
                    candidateSharpen = null;
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
            if (retiredSharpen != null) {
                try {
                    retiredSharpen.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                } finally {
                    retiredSharpen = null;
                }
            }
            if (failure != null) throw failure;
        }
    }

    @Override
    public void close() {
        Framebuffer[] previous = slots;
        Framebuffer previousSharpen = sharpenTarget;
        slots = null;
        sharpenTarget = null;
        valid = false;
        prepared = false;
        closePair(previous);
        if (previousSharpen != null) previousSharpen.close();
    }

    private void ensureOpen() {
        if (slots == null) {
            throw new IllegalStateException("temporal-upscaling history is closed");
        }
    }

    private static Framebuffer[] createPair(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "temporal-upscaling history extent must be positive");
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
                .colorTexture(RenderFormat.RGBA16F)
                .colorTexture(RenderFormat.RGBA16F)
                .build());
    }

    private static Framebuffer createSharpenTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.RGBA16F)
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
