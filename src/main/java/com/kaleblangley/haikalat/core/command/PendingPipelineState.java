package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.state.PipelineStateSink;
import com.kaleblangley.haikalat.core.BlendMode;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;

/**
 * 收集两个可观察 GPU 命令边界之间“最后写入生效”的管线状态。
 * 该类刻意不感知 OpenGL，也不感知 backend 的持久状态缓存。
 */
final class PendingPipelineState {
    private static final int VIEWPORT = 1;
    private static final int BLEND_ENABLE = 1 << 1;
    private static final int BLEND_FUNCTION = 1 << 2;
    private static final int DEPTH_MASK = 1 << 3;
    private static final int DEPTH_TEST = 1 << 4;
    private static final int CULL_FACE = 1 << 5;
    private static final int CLEAR_COLOR = 1 << 6;
    private static final int FRAMEBUFFER_SRGB = 1 << 7;

    private int dirty;
    private int viewportX;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;
    private boolean blendEnabled;
    private int blendSourceRgb;
    private int blendDestinationRgb;
    private boolean depthWriteEnabled;
    private boolean depthTestEnabled;
    private boolean cullFaceEnabled;
    private boolean framebufferSrgbEnabled;
    private float clearRed;
    private float clearGreen;
    private float clearBlue;
    private float clearAlpha;

    void viewport(int x, int y, int width, int height) {
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;
        dirty |= VIEWPORT;
    }

    void enableBlend(boolean enable) {
        blendEnabled = enable;
        dirty |= BLEND_ENABLE;
    }

    void blendFunc(int sourceRgb, int destinationRgb) {
        blendSourceRgb = sourceRgb;
        blendDestinationRgb = destinationRgb;
        dirty |= BLEND_FUNCTION;
    }

    void depthMask(boolean write) {
        depthWriteEnabled = write;
        dirty |= DEPTH_MASK;
    }

    void enableDepthTest(boolean enable) {
        depthTestEnabled = enable;
        dirty |= DEPTH_TEST;
    }

    void enableCullFace(boolean enable) {
        cullFaceEnabled = enable;
        dirty |= CULL_FACE;
    }

    void enableFramebufferSrgb(boolean enable) {
        framebufferSrgbEnabled = enable;
        dirty |= FRAMEBUFFER_SRGB;
    }

    void clearColor(float red, float green, float blue, float alpha) {
        clearRed = red;
        clearGreen = green;
        clearBlue = blue;
        clearAlpha = alpha;
        dirty |= CLEAR_COLOR;
    }

    void materialState(BlendMode blendMode, boolean depthTest) {
        switch (blendMode) {
            case OPAQUE -> {
                enableBlend(false);
                depthMask(true);
            }
            case ALPHA -> {
                blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                enableBlend(true);
                depthMask(false);
            }
            case ADDITIVE -> {
                blendFunc(GL_ONE, GL_ONE);
                enableBlend(true);
                depthMask(false);
            }
        }
        enableDepthTest(depthTest);
    }

    /** 按稳定且便于维护依赖的顺序，将每个最终脏值应用一次。 */
    void flush(PipelineStateSink target) {
        int changes = dirty;
        if (changes == 0) return;
        if ((changes & VIEWPORT) != 0) {
            target.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        }
        if ((changes & CLEAR_COLOR) != 0) {
            target.clearColor(clearRed, clearGreen, clearBlue, clearAlpha);
        }
        if ((changes & BLEND_FUNCTION) != 0) {
            target.blendFunc(blendSourceRgb, blendDestinationRgb);
        }
        if ((changes & BLEND_ENABLE) != 0) target.enableBlend(blendEnabled);
        if ((changes & DEPTH_MASK) != 0) target.depthMask(depthWriteEnabled);
        if ((changes & DEPTH_TEST) != 0) target.enableDepthTest(depthTestEnabled);
        if ((changes & CULL_FACE) != 0) target.enableCullFace(cullFaceEnabled);
        if ((changes & FRAMEBUFFER_SRGB) != 0) target.enableFramebufferSrgb(framebufferSrgbEnabled);
        dirty = 0;
    }

    /** 将 pending 值编码为一个只含基础类型的状态包，并消费这些值。 */
    void writeTo(CommandStream stream, byte opcode) {
        int changes = dirty;
        if (changes == 0) return;
        stream.opcode(opcode);
        stream.integer(changes);
        if ((changes & VIEWPORT) != 0) {
            stream.integer(viewportX);
            stream.integer(viewportY);
            stream.integer(viewportWidth);
            stream.integer(viewportHeight);
        }
        if ((changes & CLEAR_COLOR) != 0) {
            stream.integer(Float.floatToRawIntBits(clearRed));
            stream.integer(Float.floatToRawIntBits(clearGreen));
            stream.integer(Float.floatToRawIntBits(clearBlue));
            stream.integer(Float.floatToRawIntBits(clearAlpha));
        }
        if ((changes & BLEND_FUNCTION) != 0) {
            stream.integer(blendSourceRgb);
            stream.integer(blendDestinationRgb);
        }
        if ((changes & BLEND_ENABLE) != 0) stream.integer(blendEnabled ? 1 : 0);
        if ((changes & DEPTH_MASK) != 0) stream.integer(depthWriteEnabled ? 1 : 0);
        if ((changes & DEPTH_TEST) != 0) stream.integer(depthTestEnabled ? 1 : 0);
        if ((changes & CULL_FACE) != 0) stream.integer(cullFaceEnabled ? 1 : 0);
        if ((changes & FRAMEBUFFER_SRGB) != 0) stream.integer(framebufferSrgbEnabled ? 1 : 0);
        dirty = 0;
    }

    /** 解码一个状态包、应用到持久缓存，并返回新的整数游标。 */
    static int applyEncoded(CommandStream stream, int cursor, PipelineStateSink target) {
        int changes = stream.integerAt(cursor++);
        if ((changes & VIEWPORT) != 0) {
            target.viewport(stream.integerAt(cursor++), stream.integerAt(cursor++),
                    stream.integerAt(cursor++), stream.integerAt(cursor++));
        }
        if ((changes & CLEAR_COLOR) != 0) {
            target.clearColor(Float.intBitsToFloat(stream.integerAt(cursor++)),
                    Float.intBitsToFloat(stream.integerAt(cursor++)),
                    Float.intBitsToFloat(stream.integerAt(cursor++)),
                    Float.intBitsToFloat(stream.integerAt(cursor++)));
        }
        if ((changes & BLEND_FUNCTION) != 0) {
            target.blendFunc(stream.integerAt(cursor++), stream.integerAt(cursor++));
        }
        if ((changes & BLEND_ENABLE) != 0) target.enableBlend(stream.integerAt(cursor++) != 0);
        if ((changes & DEPTH_MASK) != 0) target.depthMask(stream.integerAt(cursor++) != 0);
        if ((changes & DEPTH_TEST) != 0) target.enableDepthTest(stream.integerAt(cursor++) != 0);
        if ((changes & CULL_FACE) != 0) target.enableCullFace(stream.integerAt(cursor++) != 0);
        if ((changes & FRAMEBUFFER_SRGB) != 0) {
            target.enableFramebufferSrgb(stream.integerAt(cursor++) != 0);
        }
        return cursor;
    }

    void reset() {
        dirty = 0;
    }

    boolean isDirty() {
        return dirty != 0;
    }
}
