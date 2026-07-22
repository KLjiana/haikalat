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
    private static final int SCISSOR_ENABLE = 1 << 8;
    private static final int SCISSOR_RECTANGLE = 1 << 9;
    private static final int FRONT_FACE = 1 << 10;

    private int dirty;
    private int known;
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
    private int frontFace;
    private boolean framebufferSrgbEnabled;
    private boolean scissorEnabled;
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;
    private float clearRed;
    private float clearGreen;
    private float clearBlue;
    private float clearAlpha;

    void viewport(int x, int y, int width, int height) {
        if ((known & VIEWPORT) != 0 && viewportX == x && viewportY == y
                && viewportWidth == width && viewportHeight == height) return;
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;
        known |= VIEWPORT;
        dirty |= VIEWPORT;
    }

    void enableBlend(boolean enable) {
        if ((known & BLEND_ENABLE) != 0 && blendEnabled == enable) return;
        blendEnabled = enable;
        known |= BLEND_ENABLE;
        dirty |= BLEND_ENABLE;
    }

    void blendFunc(int sourceRgb, int destinationRgb) {
        if ((known & BLEND_FUNCTION) != 0 && blendSourceRgb == sourceRgb
                && blendDestinationRgb == destinationRgb) return;
        blendSourceRgb = sourceRgb;
        blendDestinationRgb = destinationRgb;
        known |= BLEND_FUNCTION;
        dirty |= BLEND_FUNCTION;
    }

    void depthMask(boolean write) {
        if ((known & DEPTH_MASK) != 0 && depthWriteEnabled == write) return;
        depthWriteEnabled = write;
        known |= DEPTH_MASK;
        dirty |= DEPTH_MASK;
    }

    void enableDepthTest(boolean enable) {
        if ((known & DEPTH_TEST) != 0 && depthTestEnabled == enable) return;
        depthTestEnabled = enable;
        known |= DEPTH_TEST;
        dirty |= DEPTH_TEST;
    }

    void enableCullFace(boolean enable) {
        if ((known & CULL_FACE) != 0 && cullFaceEnabled == enable) return;
        cullFaceEnabled = enable;
        known |= CULL_FACE;
        dirty |= CULL_FACE;
    }

    void frontFace(int winding) {
        if ((known & FRONT_FACE) != 0 && frontFace == winding) return;
        frontFace = winding;
        known |= FRONT_FACE;
        dirty |= FRONT_FACE;
    }

    void enableFramebufferSrgb(boolean enable) {
        if ((known & FRAMEBUFFER_SRGB) != 0 && framebufferSrgbEnabled == enable) return;
        framebufferSrgbEnabled = enable;
        known |= FRAMEBUFFER_SRGB;
        dirty |= FRAMEBUFFER_SRGB;
    }

    void enableScissor(boolean enable) {
        if ((known & SCISSOR_ENABLE) != 0 && scissorEnabled == enable) return;
        scissorEnabled = enable;
        known |= SCISSOR_ENABLE;
        dirty |= SCISSOR_ENABLE;
    }

    void scissor(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("scissor rectangle must be non-negative");
        }
        if ((known & SCISSOR_RECTANGLE) != 0 && scissorX == x && scissorY == y
                && scissorWidth == width && scissorHeight == height) return;
        scissorX = x;
        scissorY = y;
        scissorWidth = width;
        scissorHeight = height;
        known |= SCISSOR_RECTANGLE;
        dirty |= SCISSOR_RECTANGLE;
    }

    void clearColor(float red, float green, float blue, float alpha) {
        if ((known & CLEAR_COLOR) != 0
                && Float.floatToRawIntBits(clearRed) == Float.floatToRawIntBits(red)
                && Float.floatToRawIntBits(clearGreen) == Float.floatToRawIntBits(green)
                && Float.floatToRawIntBits(clearBlue) == Float.floatToRawIntBits(blue)
                && Float.floatToRawIntBits(clearAlpha) == Float.floatToRawIntBits(alpha)) return;
        clearRed = red;
        clearGreen = green;
        clearBlue = blue;
        clearAlpha = alpha;
        known |= CLEAR_COLOR;
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
        if ((changes & FRONT_FACE) != 0) target.frontFace(frontFace);
        if ((changes & SCISSOR_RECTANGLE) != 0) {
            target.scissor(scissorX, scissorY, scissorWidth, scissorHeight);
        }
        if ((changes & SCISSOR_ENABLE) != 0) target.enableScissor(scissorEnabled);
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
        if ((changes & FRONT_FACE) != 0) stream.integer(frontFace);
        if ((changes & SCISSOR_RECTANGLE) != 0) {
            stream.integer(scissorX);
            stream.integer(scissorY);
            stream.integer(scissorWidth);
            stream.integer(scissorHeight);
        }
        if ((changes & SCISSOR_ENABLE) != 0) stream.integer(scissorEnabled ? 1 : 0);
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
        if ((changes & FRONT_FACE) != 0) target.frontFace(stream.integerAt(cursor++));
        if ((changes & SCISSOR_RECTANGLE) != 0) {
            target.scissor(stream.integerAt(cursor++), stream.integerAt(cursor++),
                    stream.integerAt(cursor++), stream.integerAt(cursor++));
        }
        if ((changes & SCISSOR_ENABLE) != 0) {
            target.enableScissor(stream.integerAt(cursor++) != 0);
        }
        if ((changes & FRAMEBUFFER_SRGB) != 0) {
            target.enableFramebufferSrgb(stream.integerAt(cursor++) != 0);
        }
        return cursor;
    }

    void reset() {
        dirty = 0;
        known = 0;
    }

    /** 任意外部 GL 回调之后，后续相同 setter 也必须重新编码。 */
    void invalidateKnownState() {
        known = 0;
    }

    boolean isDirty() {
        return dirty != 0;
    }
}
