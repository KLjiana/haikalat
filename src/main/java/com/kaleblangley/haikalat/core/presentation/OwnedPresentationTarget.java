package com.kaleblangley.haikalat.core.presentation;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.material.ResourceOwnership;

/**
 * Render-thread owner for a Haikalat-created presentation framebuffer and textures.
 */
public final class OwnedPresentationTarget implements AutoCloseable {
    private final long ownerThreadId = Thread.currentThread().threadId();
    private final Framebuffer framebuffer;
    private final PresentationTarget target;
    private boolean closed;

    private OwnedPresentationTarget(int width, int height, RenderFormat colorFormat,
                                    boolean depthStencil, long generation) {
        FramebufferDescriptor.Builder descriptor = FramebufferDescriptor.builder(width, height)
                .colorTexture(colorFormat);
        if (depthStencil) descriptor.depthStencilTexture();
        else descriptor.depthTexture();
        framebuffer = Framebuffer.fromDescriptor(descriptor.build());
        ExternalAttachment color = new ExternalAttachment(
                framebuffer.colorAttachment(), AttachmentRole.COLOR, colorFormat,
                width, height, 1, ResourceOwnership.OWNED);
        ExternalAttachment depth = new ExternalAttachment(
                framebuffer.depthAttachment(),
                depthStencil ? AttachmentRole.DEPTH_STENCIL : AttachmentRole.DEPTH,
                depthStencil ? RenderFormat.DEPTH24_STENCIL8 : RenderFormat.DEPTH_COMPONENT24,
                width, height, 1, ResourceOwnership.OWNED);
        target = PresentationTarget.builder(width, height)
                .framebuffer(framebuffer.id())
                .generation(generation)
                .framebufferOwnership(ResourceOwnership.OWNED)
                .color(color)
                .depth(depth)
                .build();
    }

    public static OwnedPresentationTarget create(int width, int height,
                                                 RenderFormat colorFormat,
                                                 boolean depthStencil,
                                                 long generation) {
        if (colorFormat == RenderFormat.DEPTH_COMPONENT24
                || colorFormat == RenderFormat.DEPTH24_STENCIL8) {
            throw new IllegalArgumentException("owned presentation target requires a color format");
        }
        return new OwnedPresentationTarget(width, height,
                java.util.Objects.requireNonNull(colorFormat, "colorFormat"),
                depthStencil, generation);
    }

    public PresentationTarget target() {
        ensureOpen();
        return target;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (closed) return;
        framebuffer.close();
        closed = true;
    }

    private void ensureOpen() {
        requireOwnerThread();
        if (closed) {
            throw new IllegalStateException("owned presentation target is closed");
        }
    }

    private void requireOwnerThread() {
        long current = Thread.currentThread().threadId();
        if (current != ownerThreadId) {
            throw new IllegalStateException("owned presentation target belongs to render thread "
                    + ownerThreadId + ", current=" + current);
        }
    }
}
