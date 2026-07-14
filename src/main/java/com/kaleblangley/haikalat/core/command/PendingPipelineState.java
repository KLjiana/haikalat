package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.state.PipelineStateSink;
import com.kaleblangley.haikalat.core.BlendMode;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;

/**
 * Last-writer-wins pipeline state collected between observable GPU command boundaries.
 * It intentionally has no knowledge of OpenGL or the persistent backend cache.
 */
final class PendingPipelineState {
    private static final int VIEWPORT = 1;
    private static final int BLEND_ENABLE = 1 << 1;
    private static final int BLEND_FUNCTION = 1 << 2;
    private static final int DEPTH_MASK = 1 << 3;
    private static final int DEPTH_TEST = 1 << 4;
    private static final int CULL_FACE = 1 << 5;
    private static final int CLEAR_COLOR = 1 << 6;

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

    /** Applies each final dirty value exactly once in a stable dependency-friendly order. */
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
        dirty = 0;
    }

    /** Encodes one primitive-only state packet and consumes the pending values. */
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
        dirty = 0;
    }

    /** Decodes one state packet, applies it to the persistent cache, and returns the new cursor. */
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
        return cursor;
    }

    void reset() {
        dirty = 0;
    }

    boolean isDirty() {
        return dirty != 0;
    }
}
