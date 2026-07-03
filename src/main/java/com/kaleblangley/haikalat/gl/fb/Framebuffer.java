package com.kaleblangley.haikalat.gl.fb;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30.glBlitFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30.glRenderbufferStorage;
import static org.lwjgl.opengl.GL30.glRenderbufferStorageMultisample;

public final class Framebuffer implements GlResource {
    private final int id;
    private final int colorAttachment;
    private final int depthAttachment;
    private final int width;
    private final int height;
    private final int samples;
    private final boolean multisampled;
    private boolean closed;

    public Framebuffer(int id, int colorAttachment, int depthAttachment, int width, int height, int samples, boolean multisampled) {
        this.id = id;
        this.colorAttachment = colorAttachment;
        this.depthAttachment = depthAttachment;
        this.width = width;
        this.height = height;
        this.samples = samples;
        this.multisampled = multisampled;
    }

    public static Framebuffer singleSampled(int width, int height) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        int colorTexture = org.lwjgl.opengl.GL11.glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);

        int depthRbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthRbo);

        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        ensureComplete(fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new Framebuffer(fbo, colorTexture, depthRbo, width, height, 1, false);
    }

    public static Framebuffer multiSampled(int width, int height, int samples) {
        if (samples < 2) {
            throw new IllegalArgumentException("samples must be >= 2");
        }

        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        int colorRbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, colorRbo);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRbo);

        int depthRbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthRbo);

        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glReadBuffer(GL_NONE);
        ensureComplete(fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new Framebuffer(fbo, colorRbo, depthRbo, width, height, samples, true);
    }

    public Framebuffer bind() {
        ensureOpen();
        glBindFramebuffer(GL_FRAMEBUFFER, id);
        glViewport(0, 0, width, height);
        return this;
    }

    public Framebuffer unbind(int defaultWidth, int defaultHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, defaultWidth, defaultHeight);
        return this;
    }

    public Framebuffer clear(boolean color, boolean depth) {
        int mask = 0;
        if (color) {
            mask |= GL_COLOR_BUFFER_BIT;
        }
        if (depth) {
            mask |= GL_DEPTH_BUFFER_BIT;
        }
        glClear(mask);
        return this;
    }

    public Framebuffer blitToDefault(int targetWidth, int targetHeight) {
        ensureOpen();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, id);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, width, height, 0, 0, targetWidth, targetHeight, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return this;
    }

    public Framebuffer blitColorTo(Framebuffer target) {
        ensureOpen();
        target.ensureOpen();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, id);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.id);
        glBlitFramebuffer(0, 0, width, height, 0, 0, target.width, target.height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return this;
    }

    public int colorAttachment() {
        return colorAttachment;
    }

    public int depthAttachment() {
        return depthAttachment;
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

    public boolean multisampled() {
        return multisampled;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (multisampled) {
            glDeleteRenderbuffers(colorAttachment);
        } else {
            glDeleteTextures(colorAttachment);
        }
        glDeleteRenderbuffers(depthAttachment);
        glDeleteFramebuffers(id);
        closed = true;
    }

    public void ensureOpen() {
        if (closed) {
            throw new GlException("Framebuffer is closed");
        }
    }

    private static void ensureComplete(int fbo) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new GlException("Framebuffer incomplete: " + status);
        }
    }
}
