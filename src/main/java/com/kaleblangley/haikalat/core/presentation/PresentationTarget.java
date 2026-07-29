package com.kaleblangley.haikalat.core.presentation;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.material.ResourceOwnership;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable destination for one rendered frame.
 *
 * <p>A target is only a descriptor. It never presents, swaps buffers, or
 * changes attachment storage. Imported targets must normally be
 * {@link ResourceOwnership#BORROWED}.</p>
 */
public final class PresentationTarget {
    private final int drawFramebufferId;
    private final int readFramebufferId;
    private final int width;
    private final int height;
    private final int samples;
    private final long generation;
    private final ResourceOwnership framebufferOwnership;
    private final ExternalAttachment color;
    private final ExternalAttachment depth;
    private final ExternalAttachment stencil;

    private PresentationTarget(Builder builder) {
        drawFramebufferId = builder.drawFramebufferId;
        readFramebufferId = builder.readFramebufferId;
        width = builder.width;
        height = builder.height;
        samples = builder.samples;
        generation = builder.generation;
        framebufferOwnership = Objects.requireNonNull(
                builder.framebufferOwnership, "framebufferOwnership");
        color = builder.color;
        depth = builder.depth;
        stencil = builder.stencil;
        validate();
    }

    public static Builder builder(int width, int height) {
        return new Builder(width, height);
    }

    /** Describes the platform default framebuffer without assuming ownership. */
    public static PresentationTarget defaultFramebuffer(int width, int height) {
        return builder(width, height)
                .framebuffers(0, 0)
                .framebufferOwnership(ResourceOwnership.BORROWED)
                .build();
    }

    /** Common single-FBO host target with borrowed color and optional depth. */
    public static PresentationTarget borrowed(int framebufferId,
                                              ExternalAttachment color,
                                              ExternalAttachment depth,
                                              int width, int height,
                                              long generation) {
        return builder(width, height)
                .framebuffers(framebufferId, framebufferId)
                .generation(generation)
                .color(color)
                .depth(depth)
                .framebufferOwnership(ResourceOwnership.BORROWED)
                .build();
    }

    public int drawFramebufferId() {
        return drawFramebufferId;
    }

    public int readFramebufferId() {
        return readFramebufferId;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int samples() {
        return samples;
    }

    public long generation() {
        return generation;
    }

    public ResourceOwnership framebufferOwnership() {
        return framebufferOwnership;
    }

    public Optional<ExternalAttachment> color() {
        return Optional.ofNullable(color);
    }

    public Optional<ExternalAttachment> depth() {
        return Optional.ofNullable(depth);
    }

    public Optional<ExternalAttachment> stencil() {
        return Optional.ofNullable(stencil);
    }

    public boolean hasDepth() {
        return depth != null && depth.hasDepth();
    }

    public boolean hasStencil() {
        return (depth != null && depth.hasStencil())
                || (stencil != null && stencil.hasStencil());
    }

    public boolean isRenderable() {
        return width > 0 && height > 0;
    }

    public RenderFormat colorFormat() {
        return color == null ? RenderFormat.RGBA8 : color.format();
    }

    private void validate() {
        if (drawFramebufferId < 0 || readFramebufferId < 0) {
            throw new IllegalArgumentException("framebuffer ids must be non-negative");
        }
        if (width < 0 || height < 0 || (width == 0) != (height == 0)) {
            throw new IllegalArgumentException(
                    "presentation extent must be positive or exactly 0x0");
        }
        if (samples <= 0) {
            throw new IllegalArgumentException("presentation samples must be positive");
        }
        if (generation < 0L) {
            throw new IllegalArgumentException("presentation generation must be non-negative");
        }
        if (framebufferOwnership == ResourceOwnership.OWNED
                && (drawFramebufferId == 0 || readFramebufferId == 0)) {
            throw new IllegalArgumentException("default framebuffer cannot be Haikalat-owned");
        }
        if (color != null && color.role() != AttachmentRole.COLOR) {
            throw new IllegalArgumentException("presentation color must have COLOR role");
        }
        if (depth != null && !depth.hasDepth()) {
            throw new IllegalArgumentException("presentation depth must have a depth role");
        }
        if (stencil != null && !stencil.hasStencil()) {
            throw new IllegalArgumentException("presentation stencil must have a stencil role");
        }
        validateOwnership("color", color);
        validateOwnership("depth", depth);
        validateOwnership("stencil", stencil);
        if (isRenderable()) {
            validateAttachment("color", color);
            validateAttachment("depth", depth);
            validateAttachment("stencil", stencil);
        }
    }

    private void validateOwnership(String name, ExternalAttachment attachment) {
        if (attachment != null && attachment.ownership() != framebufferOwnership) {
            throw new IllegalArgumentException(name + " attachment ownership "
                    + attachment.ownership() + " does not match framebuffer ownership "
                    + framebufferOwnership);
        }
    }

    private void validateAttachment(String name, ExternalAttachment attachment) {
        if (attachment == null) return;
        if (attachment.width() != width || attachment.height() != height) {
            throw new IllegalArgumentException(name + " attachment extent "
                    + attachment.width() + "x" + attachment.height()
                    + " does not match presentation extent " + width + "x" + height);
        }
        if (attachment.samples() != samples) {
            throw new IllegalArgumentException(name + " attachment samples "
                    + attachment.samples() + " do not match presentation samples " + samples);
        }
    }

    public static final class Builder {
        private final int width;
        private final int height;
        private int drawFramebufferId;
        private int readFramebufferId;
        private int samples = 1;
        private long generation;
        private ResourceOwnership framebufferOwnership = ResourceOwnership.BORROWED;
        private ExternalAttachment color;
        private ExternalAttachment depth;
        private ExternalAttachment stencil;

        private Builder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public Builder framebuffer(int framebufferId) {
            return framebuffers(framebufferId, framebufferId);
        }

        public Builder framebuffers(int drawFramebufferId, int readFramebufferId) {
            this.drawFramebufferId = drawFramebufferId;
            this.readFramebufferId = readFramebufferId;
            return this;
        }

        public Builder samples(int samples) {
            this.samples = samples;
            return this;
        }

        public Builder generation(long generation) {
            this.generation = generation;
            return this;
        }

        public Builder framebufferOwnership(ResourceOwnership ownership) {
            framebufferOwnership = Objects.requireNonNull(ownership, "ownership");
            return this;
        }

        public Builder color(ExternalAttachment color) {
            this.color = color;
            return this;
        }

        public Builder depth(ExternalAttachment depth) {
            this.depth = depth;
            return this;
        }

        public Builder stencil(ExternalAttachment stencil) {
            this.stencil = stencil;
            return this;
        }

        public PresentationTarget build() {
            return new PresentationTarget(this);
        }
    }
}
