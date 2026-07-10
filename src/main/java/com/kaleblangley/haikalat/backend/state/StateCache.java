package com.kaleblangley.haikalat.backend.state;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.glBindSampler;

public final class StateCache {
    private static final int MAX_TEXTURE_UNITS = 32;

    private int currentProgram;
    private int currentVAO;
    private int currentArrayBuffer;
    private int currentElementBuffer;
    private int activeTextureUnit;
    private final int[] boundTextures2D = new int[MAX_TEXTURE_UNITS];
    private final int[] boundSamplers = new int[MAX_TEXTURE_UNITS];
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
    private boolean cullFaceEnabled;
    private boolean cullFaceCached;

    public StateCache() {
        invalidate();
    }

    /**
     * 若与当前缓存值不同，则调用 glUseProgram 并更新缓存。
     *
     * @param program 要使用的着色器程序 ID
     */
    public void useProgram(int program) {
        if (program != currentProgram) {
            glUseProgram(program);
            currentProgram = program;
        }
    }

    /**
     * 若与当前缓存值不同，则调用 glBindVertexArray 并更新缓存。
     *
     * @param vao 要绑定的 VAO ID
     */
    public void bindVertexArray(int vao) {
        if (vao != currentVAO) {
            glBindVertexArray(vao);
            currentVAO = vao;
        }
    }

    /**
     * 若与当前缓存值不同，则绑定 GL_ARRAY_BUFFER 并更新缓存。
     *
     * @param buffer 要绑定的缓冲区 ID
     */
    public void bindArrayBuffer(int buffer) {
        if (buffer != currentArrayBuffer) {
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            currentArrayBuffer = buffer;
        }
    }

    /**
     * 若与当前缓存值不同，则绑定 GL_ELEMENT_ARRAY_BUFFER 并更新缓存。
     *
     * @param buffer 要绑定的元素缓冲区 ID
     */
    public void bindElementBuffer(int buffer) {
        if (buffer != currentElementBuffer) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
            currentElementBuffer = buffer;
        }
    }

    /**
     * 若与当前纹理单元不同，则激活对应的纹理单元。
     *
     * @param unit 纹理单元索引
     */
    public void activeTexture(int unit) {
        if (unit != activeTextureUnit) {
            glActiveTexture(GL_TEXTURE0 + unit);
            activeTextureUnit = unit;
        }
    }

    /**
     * 在指定纹理单元上绑定 2D 纹理，避免重复绑定同一纹理。
     *
     * @param unit    纹理单元索引
     * @param texture 纹理 ID
     */
    public void bindTexture2D(int unit, int texture) {
        activeTexture(unit);
        if (boundTextures2D[unit] != texture) {
            glBindTexture(GL_TEXTURE_2D, texture);
            boundTextures2D[unit] = texture;
        }
    }

    public void bindSampler(int unit, int sampler) {
        if (boundSamplers[unit] != sampler) {
            glBindSampler(unit, sampler);
            boundSamplers[unit] = sampler;
        }
    }

    /**
     * 按指定的帧缓冲目标绑定 FBO，避免重复绑定。
     *
     * @param target 帧缓冲目标（GL_READ_FRAMEBUFFER / GL_DRAW_FRAMEBUFFER / GL_FRAMEBUFFER）
     * @param fbo    帧缓冲对象 ID
     */
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

    /**
     * 若视口参数与缓存不同，则调用 glViewport 并缓存新值。
     *
     * @param x 视口左下角 x 坐标
     * @param y 视口左下角 y 坐标
     * @param w 视口宽度
     * @param h 视口高度
     */
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

    /**
     * 若混合状态与缓存不同，则启用或禁用 GL_BLEND。
     *
     * @param enable true 启用混合，false 禁用混合
     */
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

    /**
     * 若混合函数参数与缓存不同，则调用 glBlendFunc 并缓存。
     *
     * @param srcRGB 源因子
     * @param dstRGB 目标因子
     */
    public void blendFunc(int srcRGB, int dstRGB) {
        if (!blendFuncCached || srcRGB != blendSrcRGB || dstRGB != blendDstRGB) {
            glBlendFunc(srcRGB, dstRGB);
            blendSrcRGB = srcRGB;
            blendDstRGB = dstRGB;
            blendFuncCached = true;
        }
    }

    /**
     * 若深度写入状态与缓存不同，则调用 glDepthMask 并缓存。
     *
     * @param write true 允许深度写入，false 禁止
     */
    public void depthMask(boolean write) {
        if (!depthWriteCached || write != depthWriteEnabled) {
            glDepthMask(write);
            depthWriteEnabled = write;
            depthWriteCached = true;
        }
    }

    /**
     * 若深度测试状态与缓存不同，则启用或禁用 GL_DEPTH_TEST。
     *
     * @param enable true 启用深度测试，false 禁用
     */
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

    /** Enables or disables face culling while avoiding redundant GL state changes. */
    public void enableCullFace(boolean enable) {
        if (!cullFaceCached || enable != cullFaceEnabled) {
            if (enable) {
                glEnable(GL_CULL_FACE);
            } else {
                glDisable(GL_CULL_FACE);
            }
            cullFaceEnabled = enable;
            cullFaceCached = true;
        }
    }

    /**
     * 直接调用 glClear，不做状态缓存。
     *
     * @param mask 清除掩码
     */
    public void clear(int mask) {
        glClear(mask);
    }

    /**
     * 设置清除颜色（不缓存状态，每次调用均直接执行）。
     *
     * @param r 红色分量
     * @param g 绿色分量
     * @param b 蓝色分量
     * @param a 透明分量
     */
    public void clearColor(float r, float g, float b, float a) {
        glClearColor(r, g, b, a);
    }

    /**
     * 将所有 GL 状态缓存重置为默认值，使其在下一次调用时强制同步。
     */
    public void invalidate() {
        currentProgram = 0;
        currentVAO = 0;
        currentArrayBuffer = 0;
        currentElementBuffer = 0;
        activeTextureUnit = 0;
        Arrays.fill(boundTextures2D, 0);
        Arrays.fill(boundSamplers, 0);
        currentReadFramebuffer = 0;
        currentDrawFramebuffer = 0;
        viewportSet = false;
        blendCached = false;
        blendFuncCached = false;
        depthWriteCached = false;
        depthTestCached = false;
        cullFaceCached = false;
    }

    /**
     * 仅重置帧缓冲相关缓存，使下次绑定时强制同步。
     */
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
