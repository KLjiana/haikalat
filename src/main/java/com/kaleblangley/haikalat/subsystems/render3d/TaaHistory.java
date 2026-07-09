package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;

final class TaaHistory implements AutoCloseable {
    private Framebuffer framebuffer;
    private boolean valid;

    TaaHistory(int width, int height) {
        this.framebuffer = Framebuffer.singleSampled(width, height);
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
        if (framebuffer != null) {
            framebuffer.close();
        }
        framebuffer = Framebuffer.singleSampled(width, height);
        valid = false;
    }

    @Override
    public void close() {
        if (framebuffer != null) {
            framebuffer.close();
            framebuffer = null;
        }
        valid = false;
    }
}
