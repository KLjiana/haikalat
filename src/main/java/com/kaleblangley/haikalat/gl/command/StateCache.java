package com.kaleblangley.haikalat.gl.command;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.*;

public final class StateCache {
    private static final int MAX_TEXTURE_UNITS = 32;

    private int currentProgram;
    private int currentVAO;
    private int currentArrayBuffer;
    private int currentElementBuffer;
    private int activeTextureUnit;
    private final int[] boundTextures2D = new int[MAX_TEXTURE_UNITS];
    private int currentReadFramebuffer;
    private int currentDrawFramebuffer;
    private int viewportX;
    private int viewportY;
    private int viewportW;
    private int viewportH;
    private boolean viewportSet;
    private boolean blendEnabled;
    private boolean blendCached;
    private int blendSrcRGB;
    private int blendDstRGB;
    private boolean blendFuncCached;
    private boolean depthWriteEnabled;
    private boolean depthWriteCached;
    private boolean depthTestEnabled;
    private boolean depthTestCached;

    public StateCache() {
        invalidate();
    }

    public void useProgram(int program) {
        if (program != currentProgram) {
            glUseProgram(program);
            currentProgram = program;
        }
    }

    public void bindVertexArray(int vao) {
        if (vao != currentVAO) {
            glBindVertexArray(vao);
            currentVAO = vao;
        }
    }

    public void bindArrayBuffer(int buffer) {
        if (buffer != currentArrayBuffer) {
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            currentArrayBuffer = buffer;
        }
    }

    public void bindElementBuffer(int buffer) {
        if (buffer != currentElementBuffer) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
            currentElementBuffer = buffer;
        }
    }

    public void activeTexture(int unit) {
        if (unit != activeTextureUnit) {
            glActiveTexture(GL_TEXTURE0 + unit);
            activeTextureUnit = unit;
        }
    }

    public void bindTexture2D(int unit, int texture) {
        activeTexture(unit);
        if (boundTextures2D[unit] != texture) {
            glBindTexture(GL_TEXTURE_2D, texture);
            boundTextures2D[unit] = texture;
        }
    }

    public void bindFramebuffer(int target, int fbo) {
        if (target == GL_READ_FRAMEBUFFER) {
            if (fbo != currentReadFramebuffer) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
                currentReadFramebuffer = fbo;
            }
        } else if (target == GL_DRAW_FRAMEBUFFER) {
            if (fbo != currentDrawFramebuffer) {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
                currentDrawFramebuffer = fbo;
            }
        } else {
            if (fbo != currentReadFramebuffer || fbo != currentDrawFramebuffer) {
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                currentReadFramebuffer = fbo;
                currentDrawFramebuffer = fbo;
            }
        }
    }

    public void viewport(int x, int y, int w, int h) {
        if (!viewportSet || x != viewportX || y != viewportY || w != viewportW || h != viewportH) {
            glViewport(x, y, w, h);
            viewportX = x;
            viewportY = y;
            viewportW = w;
            viewportH = h;
            viewportSet = true;
        }
    }

    public void enableBlend(boolean enable) {
        if (!blendCached || enable != blendEnabled) {
            if (enable) {
                glEnable(GL_BLEND);
            } else {
                glDisable(GL_BLEND);
            }
            blendEnabled = enable;
            blendCached = true;
        }
    }

    public void blendFunc(int srcRGB, int dstRGB) {
        if (!blendFuncCached || srcRGB != blendSrcRGB || dstRGB != blendDstRGB) {
            glBlendFunc(srcRGB, dstRGB);
            blendSrcRGB = srcRGB;
            blendDstRGB = dstRGB;
            blendFuncCached = true;
        }
    }

    public void depthMask(boolean write) {
        if (!depthWriteCached || write != depthWriteEnabled) {
            glDepthMask(write);
            depthWriteEnabled = write;
            depthWriteCached = true;
        }
    }

    public void enableDepthTest(boolean enable) {
        if (!depthTestCached || enable != depthTestEnabled) {
            if (enable) {
                glEnable(GL_DEPTH_TEST);
            } else {
                glDisable(GL_DEPTH_TEST);
            }
            depthTestEnabled = enable;
            depthTestCached = true;
        }
    }

    public void clear(int mask) {
        glClear(mask);
    }

    public void clearColor(float r, float g, float b, float a) {
        glClearColor(r, g, b, a);
    }

    public void invalidate() {
        currentProgram = 0;
        currentVAO = 0;
        currentArrayBuffer = 0;
        currentElementBuffer = 0;
        activeTextureUnit = 0;
        Arrays.fill(boundTextures2D, 0);
        currentReadFramebuffer = 0;
        currentDrawFramebuffer = 0;
        viewportSet = false;
        blendCached = false;
        blendFuncCached = false;
        depthWriteCached = false;
        depthTestCached = false;
    }

    public void invalidateFramebuffer() {
        currentReadFramebuffer = 0;
        currentDrawFramebuffer = 0;
    }

    public int currentProgram() {
        return currentProgram;
    }

    public int currentVAO() {
        return currentVAO;
    }
}
