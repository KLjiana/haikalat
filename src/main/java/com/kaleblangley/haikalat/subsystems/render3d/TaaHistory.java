package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.RenderFormat;

final class TaaHistory implements AutoCloseable {
    private final RenderFormat format;
    private Framebuffer framebuffer;
    private boolean valid;

    TaaHistory(int width, int height) {
        this(width, height, RenderFormat.RGBA8);
    }

    TaaHistory(int width, int height, RenderFormat format) {
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

    void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        // Allocate the candidate first. If creation fails, the current history remains usable.
        Framebuffer candidate = createFramebuffer(width, height);
        Framebuffer previous = framebuffer;
        framebuffer = candidate;
        valid = false;
        if (previous != null) {
            previous.close();
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
}
