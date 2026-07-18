package com.kaleblangley.haikalat.backend.state;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.glBindBufferRange;
import static org.lwjgl.opengl.GL42.GL_MAX_IMAGE_UNITS;
import static org.lwjgl.opengl.GL42.glBindImageTexture;
import static org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

public final class StateCache implements PipelineStateSink {
    private int currentProgram;
    private int currentVAO;
    private int activeTextureUnit;
    private int[] boundTextures2D = new int[0];
    private int[] boundTexturesCube = new int[0];
    private int[] lastTextureTargets = new int[0];
    private int[] boundSamplers = new int[0];
    private int[] uniformBuffers = new int[0];
    private long[] uniformBufferOffsets = new long[0];
    private long[] uniformBufferSizes = new long[0];
    private int[] storageBuffers = new int[0];
    private long[] storageBufferOffsets = new long[0];
    private long[] storageBufferSizes = new long[0];
    private int[] imageTextures = new int[0];
    private int[] imageLevels = new int[0];
    private boolean[] imageLayered = new boolean[0];
    private int[] imageLayers = new int[0];
    private int[] imageAccess = new int[0];
    private int[] imageFormats = new int[0];
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
    private int frontFace;
    private boolean frontFaceCached;
    private boolean scissorEnabled;
    private boolean scissorEnableCached;
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;
    private boolean scissorRectangleCached;
    private boolean framebufferSrgbEnabled;
    private boolean framebufferSrgbCached;
    private float clearRed;
    private float clearGreen;
    private float clearBlue;
    private float clearAlpha;
    private boolean clearColorCached;
    private long appliedChanges;
    private long avoidedChanges;

    public StateCache() {
        invalidate();
    }

    /**
     * 若与当前缓存值不同，则调用 glUseProgram 并更新缓存。
     *
     * @param program 要使用的着色器程序 ID
     */
    public void useProgram(int program) {
        if (changeRequired(program != currentProgram)) {
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
        if (changeRequired(vao != currentVAO)) {
            glBindVertexArray(vao);
            currentVAO = vao;
        }
    }

    /**
     * 若与当前纹理单元不同，则激活对应的纹理单元。
     *
     * @param unit 纹理单元索引
     */
    private void activeTexture(int unit) {
        requireTextureUnit(unit);
        if (changeRequired(unit != activeTextureUnit)) {
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
        requireTextureUnit(unit);
        activeTexture(unit);
        if (changeRequired(boundTextures2D[unit] != texture
                || lastTextureTargets[unit] != GL_TEXTURE_2D)) {
            glBindTexture(GL_TEXTURE_2D, texture);
            boundTextures2D[unit] = texture;
            lastTextureTargets[unit] = GL_TEXTURE_2D;
        }
    }

    /** 在指定纹理单元绑定 cubemap，并把 target 计入缓存键。 */
    public void bindTextureCube(int unit, int texture) {
        requireTextureUnit(unit);
        activeTexture(unit);
        if (changeRequired(boundTexturesCube[unit] != texture
                || lastTextureTargets[unit] != GL_TEXTURE_CUBE_MAP)) {
            glBindTexture(GL_TEXTURE_CUBE_MAP, texture);
            boundTexturesCube[unit] = texture;
            lastTextureTargets[unit] = GL_TEXTURE_CUBE_MAP;
        }
    }

    public void bindSampler(int unit, int sampler) {
        requireTextureUnit(unit);
        if (changeRequired(boundSamplers[unit] != sampler)) {
            glBindSampler(unit, sampler);
            boundSamplers[unit] = sampler;
        }
    }

    public void bindUniformBufferRange(int bindingPoint, int buffer, long offset, long size) {
        ensureUniformBufferBinding(bindingPoint);
        if (changeRequired(uniformBuffers[bindingPoint] != buffer
                || uniformBufferOffsets[bindingPoint] != offset
                || uniformBufferSizes[bindingPoint] != size)) {
            glBindBufferRange(GL_UNIFORM_BUFFER, bindingPoint, buffer, offset, size);
            uniformBuffers[bindingPoint] = buffer;
            uniformBufferOffsets[bindingPoint] = offset;
            uniformBufferSizes[bindingPoint] = size;
        }
    }

    public void bindStorageBufferRange(int bindingPoint, int buffer, long offset, long size) {
        ensureStorageBufferBinding(bindingPoint);
        if (changeRequired(storageBuffers[bindingPoint] != buffer
                || storageBufferOffsets[bindingPoint] != offset
                || storageBufferSizes[bindingPoint] != size)) {
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, bindingPoint, buffer, offset, size);
            storageBuffers[bindingPoint] = buffer;
            storageBufferOffsets[bindingPoint] = offset;
            storageBufferSizes[bindingPoint] = size;
        }
    }

    public void bindImageTexture(int unit, int texture, int level, boolean layered,
                                 int layer, int access, int format) {
        ensureImageUnit(unit);
        if (changeRequired(imageTextures[unit] != texture || imageLevels[unit] != level
                || imageLayered[unit] != layered || imageLayers[unit] != layer
                || imageAccess[unit] != access || imageFormats[unit] != format)) {
            glBindImageTexture(unit, texture, level, layered, layer, access, format);
            imageTextures[unit] = texture;
            imageLevels[unit] = level;
            imageLayered[unit] = layered;
            imageLayers[unit] = layer;
            imageAccess[unit] = access;
            imageFormats[unit] = format;
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
            if (changeRequired(fbo != currentReadFramebuffer)) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
                currentReadFramebuffer = fbo;
            }
        } else if (target == GL_DRAW_FRAMEBUFFER) {
            if (changeRequired(fbo != currentDrawFramebuffer)) {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
                currentDrawFramebuffer = fbo;
            }
        } else {
            if (changeRequired(fbo != currentReadFramebuffer || fbo != currentDrawFramebuffer)) {
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
        if (changeRequired(!viewportSet || x != viewportX || y != viewportY
                || w != viewportW || h != viewportH)) {
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
        if (changeRequired(!blendCached || enable != blendEnabled)) {
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
        if (changeRequired(!blendFuncCached || srcRGB != blendSrcRGB || dstRGB != blendDstRGB)) {
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
        if (changeRequired(!depthWriteCached || write != depthWriteEnabled)) {
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
        if (changeRequired(!depthTestCached || enable != depthTestEnabled)) {
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
        if (changeRequired(!cullFaceCached || enable != cullFaceEnabled)) {
            if (enable) {
                glEnable(GL_CULL_FACE);
            } else {
                glDisable(GL_CULL_FACE);
            }
            cullFaceEnabled = enable;
            cullFaceCached = true;
        }
    }

    /** 设置正面绕序并跳过重复的 glFrontFace。 */
    @Override
    public void frontFace(int winding) {
        if (winding != GL_CCW && winding != GL_CW) {
            throw new IllegalArgumentException("front face must be GL_CCW or GL_CW");
        }
        if (changeRequired(!frontFaceCached || frontFace != winding)) {
            glFrontFace(winding);
            frontFace = winding;
            frontFaceCached = true;
        }
    }

    /** 启用或禁用 scissor test，并跳过重复的 OpenGL 状态提交。 */
    @Override
    public void enableScissor(boolean enable) {
        if (changeRequired(!scissorEnableCached || enable != scissorEnabled)) {
            if (enable) {
                glEnable(GL_SCISSOR_TEST);
            } else {
                glDisable(GL_SCISSOR_TEST);
            }
            scissorEnabled = enable;
            scissorEnableCached = true;
        }
    }

    /** 设置左下角原点的 framebuffer scissor rectangle。 */
    @Override
    public void scissor(int x, int y, int width, int height) {
        if (changeRequired(!scissorRectangleCached || x != scissorX || y != scissorY
                || width != scissorWidth || height != scissorHeight)) {
            glScissor(x, y, width, height);
            scissorX = x;
            scissorY = y;
            scissorWidth = width;
            scissorHeight = height;
            scissorRectangleCached = true;
        }
    }

    /** 启用或禁用 framebuffer sRGB 编码，并跳过重复提交。 */
    public void enableFramebufferSrgb(boolean enable) {
        if (changeRequired(!framebufferSrgbCached || enable != framebufferSrgbEnabled)) {
            if (enable) {
                glEnable(GL_FRAMEBUFFER_SRGB);
            } else {
                glDisable(GL_FRAMEBUFFER_SRGB);
            }
            framebufferSrgbEnabled = enable;
            framebufferSrgbCached = true;
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
        if (changeRequired(!clearColorCached || Float.compare(r, clearRed) != 0
                || Float.compare(g, clearGreen) != 0 || Float.compare(b, clearBlue) != 0
                || Float.compare(a, clearAlpha) != 0)) {
            glClearColor(r, g, b, a);
            clearRed = r;
            clearGreen = g;
            clearBlue = b;
            clearAlpha = a;
            clearColorCached = true;
        }
    }

    /**
     * 将所有 GL 状态缓存重置为默认值，使其在下一次调用时强制同步。
     */
    public void invalidate() {
        currentProgram = -1;
        currentVAO = -1;
        activeTextureUnit = -1;
        Arrays.fill(boundTextures2D, -1);
        Arrays.fill(boundTexturesCube, -1);
        Arrays.fill(lastTextureTargets, -1);
        Arrays.fill(boundSamplers, -1);
        Arrays.fill(uniformBuffers, -1);
        Arrays.fill(uniformBufferOffsets, -1L);
        Arrays.fill(uniformBufferSizes, -1L);
        Arrays.fill(storageBuffers, -1);
        Arrays.fill(storageBufferOffsets, -1L);
        Arrays.fill(storageBufferSizes, -1L);
        Arrays.fill(imageTextures, -1);
        Arrays.fill(imageLevels, -1);
        Arrays.fill(imageLayers, -1);
        Arrays.fill(imageAccess, -1);
        Arrays.fill(imageFormats, -1);
        currentReadFramebuffer = -1;
        currentDrawFramebuffer = -1;
        viewportSet = false;
        blendCached = false;
        blendFuncCached = false;
        depthWriteCached = false;
        depthTestCached = false;
        cullFaceCached = false;
        frontFaceCached = false;
        scissorEnableCached = false;
        scissorRectangleCached = false;
        framebufferSrgbCached = false;
        clearColorCached = false;
    }

    /** Invalidates VAO state after a draw path binds a vertex array directly. */
    public void invalidateVertexArray() {
        currentVAO = -1;
    }

    /** Lifetime counters useful for overlays, profiling and state-cache regression tests. */
    public Statistics statistics() {
        return new Statistics(appliedChanges, avoidedChanges);
    }

    public void resetStatistics() {
        appliedChanges = 0L;
        avoidedChanges = 0L;
    }

    private boolean changeRequired(boolean required) {
        if (required) appliedChanges++;
        else avoidedChanges++;
        return required;
    }

    private void requireTextureUnit(int unit) {
        if (boundTextures2D.length == 0) {
            int count = positiveLimit(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, "texture units");
            boundTextures2D = initializedInts(count);
            boundTexturesCube = initializedInts(count);
            lastTextureTargets = initializedInts(count);
            boundSamplers = initializedInts(count);
        }
        if (unit < 0 || unit >= boundTextures2D.length) {
            throw new IllegalArgumentException("texture unit out of range: " + unit);
        }
    }

    private void ensureUniformBufferBinding(int bindingPoint) {
        if (uniformBuffers.length == 0) {
            int count = positiveLimit(GL_MAX_UNIFORM_BUFFER_BINDINGS, "uniform buffer bindings");
            uniformBuffers = initializedInts(count);
            uniformBufferOffsets = initializedLongs(count);
            uniformBufferSizes = initializedLongs(count);
        }
        requireIndex(bindingPoint, uniformBuffers.length, "uniform buffer");
    }

    private void ensureStorageBufferBinding(int bindingPoint) {
        if (storageBuffers.length == 0) {
            int count = positiveLimit(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, "storage buffer bindings");
            storageBuffers = initializedInts(count);
            storageBufferOffsets = initializedLongs(count);
            storageBufferSizes = initializedLongs(count);
        }
        requireIndex(bindingPoint, storageBuffers.length, "storage buffer");
    }

    private void ensureImageUnit(int unit) {
        if (imageTextures.length == 0) {
            int count = positiveLimit(GL_MAX_IMAGE_UNITS, "image units");
            imageTextures = initializedInts(count);
            imageLevels = initializedInts(count);
            imageLayered = new boolean[count];
            imageLayers = initializedInts(count);
            imageAccess = initializedInts(count);
            imageFormats = initializedInts(count);
        }
        requireIndex(unit, imageTextures.length, "image unit");
    }

    private static int positiveLimit(int query, String kind) {
        int count = glGetInteger(query);
        if (count <= 0) throw new IllegalStateException("OpenGL reported no " + kind);
        return count;
    }

    private static int[] initializedInts(int count) {
        int[] values = new int[count];
        Arrays.fill(values, -1);
        return values;
    }

    private static long[] initializedLongs(int count) {
        long[] values = new long[count];
        Arrays.fill(values, -1L);
        return values;
    }

    private static void requireIndex(int index, int limit, String kind) {
        if (index < 0 || index >= limit) throw new IllegalArgumentException(
                kind + " binding out of range: " + index + " (limit " + limit + ")");
    }

    public record Statistics(long appliedChanges, long avoidedChanges) {
    }
}
