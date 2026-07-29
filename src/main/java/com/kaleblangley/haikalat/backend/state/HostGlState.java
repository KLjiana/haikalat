package com.kaleblangley.haikalat.backend.state;

import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_BINDING_3D;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.glGetInteger64i;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43.*;

/**
 * Snapshot of host OpenGL state touched by Haikalat rendering.
 *
 * <p>Capture and restore must run on the same current OpenGL context. Closing
 * the scope restores state exactly once.</p>
 */
public final class HostGlState implements AutoCloseable {
    private static final int MAX_TRACKED_TEXTURE_UNITS = 32;
    private static final int MAX_TRACKED_INDEXED_BINDINGS = 16;

    private final long threadId;
    private final int drawFramebuffer;
    private final int readFramebuffer;
    private final int readBuffer;
    private final int touchedReadFramebuffer;
    private final int touchedReadBuffer;
    private final int[] viewport;
    private final int[] scissorBox;
    private final int program;
    private final int vertexArray;
    private final int activeTexture;
    private final int arrayBuffer;
    private final int elementArrayBuffer;
    private final int uniformBuffer;
    private final int shaderStorageBuffer;
    private final int pixelPackBuffer;
    private final int pixelUnpackBuffer;
    private final int drawIndirectBuffer;
    private final int dispatchIndirectBuffer;
    private final TextureUnit[] textureUnits;
    private final IndexedBuffer[] uniformBindings;
    private final IndexedBuffer[] storageBindings;
    private final ImageBinding[] imageBindings;
    private final boolean blend;
    private final boolean depthTest;
    private final boolean cullFace;
    private final boolean stencilTest;
    private final boolean scissorTest;
    private final boolean framebufferSrgb;
    private final boolean polygonOffsetFill;
    private final int blendSrcRgb;
    private final int blendDstRgb;
    private final int blendSrcAlpha;
    private final int blendDstAlpha;
    private final int blendEquationRgb;
    private final int blendEquationAlpha;
    private final int depthFunc;
    private final boolean depthMask;
    private final int cullFaceMode;
    private final int frontFace;
    private final boolean[] colorMask;
    private final float[] clearColor;
    private final float polygonOffsetFactor;
    private final float polygonOffsetUnits;
    private final StencilFace frontStencil;
    private final StencilFace backStencil;
    private boolean restored;

    private HostGlState(int touchedReadFramebuffer) {
        threadId = Thread.currentThread().threadId();
        drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        readBuffer = glGetInteger(GL_READ_BUFFER);
        this.touchedReadFramebuffer = touchedReadFramebuffer;
        if (touchedReadFramebuffer >= 0 && touchedReadFramebuffer != readFramebuffer) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, touchedReadFramebuffer);
            touchedReadBuffer = glGetInteger(GL_READ_BUFFER);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        } else {
            touchedReadBuffer = readBuffer;
        }
        viewport = getInt4(GL_VIEWPORT);
        scissorBox = getInt4(GL_SCISSOR_BOX);
        program = glGetInteger(GL_CURRENT_PROGRAM);
        vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        elementArrayBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
        uniformBuffer = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
        shaderStorageBuffer = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        pixelPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        pixelUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        drawIndirectBuffer = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        dispatchIndirectBuffer = glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        textureUnits = captureTextureUnits();
        uniformBindings = captureIndexedBuffers(GL_MAX_UNIFORM_BUFFER_BINDINGS,
                GL_UNIFORM_BUFFER_BINDING, GL_UNIFORM_BUFFER_START, GL_UNIFORM_BUFFER_SIZE);
        storageBindings = captureIndexedBuffers(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,
                GL_SHADER_STORAGE_BUFFER_BINDING,
                GL_SHADER_STORAGE_BUFFER_START, GL_SHADER_STORAGE_BUFFER_SIZE);
        imageBindings = captureImages();
        blend = glIsEnabled(GL_BLEND);
        depthTest = glIsEnabled(GL_DEPTH_TEST);
        cullFace = glIsEnabled(GL_CULL_FACE);
        stencilTest = glIsEnabled(GL_STENCIL_TEST);
        scissorTest = glIsEnabled(GL_SCISSOR_TEST);
        framebufferSrgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
        polygonOffsetFill = glIsEnabled(GL_POLYGON_OFFSET_FILL);
        blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        blendEquationRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
        blendEquationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        depthFunc = glGetInteger(GL_DEPTH_FUNC);
        depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        cullFaceMode = glGetInteger(GL_CULL_FACE_MODE);
        frontFace = glGetInteger(GL_FRONT_FACE);
        colorMask = getBoolean4(GL_COLOR_WRITEMASK);
        clearColor = getFloat4(GL_COLOR_CLEAR_VALUE);
        polygonOffsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR);
        polygonOffsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS);
        frontStencil = captureStencil(GL_FRONT);
        backStencil = captureStencil(GL_BACK);
        glActiveTexture(activeTexture);
    }

    public static HostGlState capture() {
        return new HostGlState(-1);
    }

    /**
     * Captures global state plus the read-buffer selector of an FBO that the
     * render scope may temporarily use as a blit source.
     */
    public static HostGlState captureReadFramebuffer(int framebufferId) {
        if (framebufferId < 0) {
            throw new IllegalArgumentException("read framebuffer id must be non-negative");
        }
        return new HostGlState(framebufferId);
    }

    public boolean isRestored() {
        return restored;
    }

    public void restore() {
        if (restored) return;
        if (Thread.currentThread().threadId() != threadId) {
            throw new IllegalStateException("host GL state must be restored on capture thread "
                    + threadId + ", current=" + Thread.currentThread().threadId());
        }
        glUseProgram(program);
        glBindVertexArray(vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer);
        restoreIndexedBuffers(GL_UNIFORM_BUFFER, uniformBindings);
        restoreIndexedBuffers(GL_SHADER_STORAGE_BUFFER, storageBindings);
        restoreImages(imageBindings);
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, shaderStorageBuffer);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, drawIndirectBuffer);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, dispatchIndirectBuffer);
        restoreTextureUnits(textureUnits);
        setEnabled(GL_BLEND, blend);
        setEnabled(GL_DEPTH_TEST, depthTest);
        setEnabled(GL_CULL_FACE, cullFace);
        setEnabled(GL_STENCIL_TEST, stencilTest);
        setEnabled(GL_SCISSOR_TEST, scissorTest);
        setEnabled(GL_FRAMEBUFFER_SRGB, framebufferSrgb);
        setEnabled(GL_POLYGON_OFFSET_FILL, polygonOffsetFill);
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
        glDepthFunc(depthFunc);
        glDepthMask(depthMask);
        glCullFace(cullFaceMode);
        glFrontFace(frontFace);
        glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits);
        restoreStencil(GL_FRONT, frontStencil);
        restoreStencil(GL_BACK, backStencil);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        if (touchedReadFramebuffer >= 0 && touchedReadFramebuffer != readFramebuffer) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, touchedReadFramebuffer);
            glReadBuffer(touchedReadBuffer);
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        glReadBuffer(readBuffer);
        restored = true;
    }

    @Override
    public void close() {
        restore();
    }

    private static TextureUnit[] captureTextureUnits() {
        int count = Math.min(glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS),
                MAX_TRACKED_TEXTURE_UNITS);
        TextureUnit[] units = new TextureUnit[count];
        for (int index = 0; index < count; index++) {
            glActiveTexture(GL_TEXTURE0 + index);
            units[index] = new TextureUnit(
                    glGetInteger(GL_TEXTURE_BINDING_2D),
                    glGetInteger(GL_TEXTURE_BINDING_3D),
                    glGetInteger(GL_TEXTURE_BINDING_CUBE_MAP),
                    glGetInteger(GL_SAMPLER_BINDING));
        }
        return units;
    }

    private static void restoreTextureUnits(TextureUnit[] units) {
        for (int index = 0; index < units.length; index++) {
            TextureUnit unit = units[index];
            glActiveTexture(GL_TEXTURE0 + index);
            glBindTexture(GL_TEXTURE_2D, unit.texture2d);
            glBindTexture(GL_TEXTURE_3D, unit.texture3d);
            glBindTexture(GL_TEXTURE_CUBE_MAP, unit.textureCube);
            glBindSampler(index, unit.sampler);
        }
    }

    private static IndexedBuffer[] captureIndexedBuffers(int maximumName, int bindingName,
                                                          int startName, int sizeName) {
        int count = Math.min(glGetInteger(maximumName), MAX_TRACKED_INDEXED_BINDINGS);
        IndexedBuffer[] bindings = new IndexedBuffer[count];
        for (int index = 0; index < count; index++) {
            bindings[index] = new IndexedBuffer(
                    glGetIntegeri(bindingName, index),
                    glGetInteger64i(startName, index),
                    glGetInteger64i(sizeName, index));
        }
        return bindings;
    }

    private static void restoreIndexedBuffers(int target, IndexedBuffer[] bindings) {
        for (int index = 0; index < bindings.length; index++) {
            IndexedBuffer binding = bindings[index];
            if (binding.buffer == 0) {
                glBindBufferBase(target, index, 0);
            } else if (binding.size > 0L) {
                glBindBufferRange(target, index, binding.buffer, binding.start, binding.size);
            } else {
                glBindBufferBase(target, index, binding.buffer);
            }
        }
    }

    private static ImageBinding[] captureImages() {
        int count = Math.min(glGetInteger(GL_MAX_IMAGE_UNITS), MAX_TRACKED_INDEXED_BINDINGS);
        ImageBinding[] bindings = new ImageBinding[count];
        for (int index = 0; index < count; index++) {
            bindings[index] = new ImageBinding(
                    glGetIntegeri(GL_IMAGE_BINDING_NAME, index),
                    glGetIntegeri(GL_IMAGE_BINDING_LEVEL, index),
                    glGetIntegeri(GL_IMAGE_BINDING_LAYERED, index) != 0,
                    glGetIntegeri(GL_IMAGE_BINDING_LAYER, index),
                    glGetIntegeri(GL_IMAGE_BINDING_ACCESS, index),
                    glGetIntegeri(GL_IMAGE_BINDING_FORMAT, index));
        }
        return bindings;
    }

    private static void restoreImages(ImageBinding[] bindings) {
        for (int index = 0; index < bindings.length; index++) {
            ImageBinding binding = bindings[index];
            glBindImageTexture(index, binding.texture, binding.level,
                    binding.layered, binding.layer, binding.access, binding.format);
        }
    }

    private static StencilFace captureStencil(int face) {
        boolean back = face == GL_BACK;
        return new StencilFace(
                glGetInteger(back ? GL_STENCIL_BACK_FUNC : GL_STENCIL_FUNC),
                glGetInteger(back ? GL_STENCIL_BACK_REF : GL_STENCIL_REF),
                glGetInteger(back ? GL_STENCIL_BACK_VALUE_MASK : GL_STENCIL_VALUE_MASK),
                glGetInteger(back ? GL_STENCIL_BACK_WRITEMASK : GL_STENCIL_WRITEMASK),
                glGetInteger(back ? GL_STENCIL_BACK_FAIL : GL_STENCIL_FAIL),
                glGetInteger(back ? GL_STENCIL_BACK_PASS_DEPTH_FAIL
                        : GL_STENCIL_PASS_DEPTH_FAIL),
                glGetInteger(back ? GL_STENCIL_BACK_PASS_DEPTH_PASS
                        : GL_STENCIL_PASS_DEPTH_PASS));
    }

    private static void restoreStencil(int face, StencilFace stencil) {
        glStencilFuncSeparate(face, stencil.function, stencil.reference, stencil.valueMask);
        glStencilMaskSeparate(face, stencil.writeMask);
        glStencilOpSeparate(face, stencil.fail, stencil.depthFail, stencil.depthPass);
    }

    private static int[] getInt4(int name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(4);
            glGetIntegerv(name, values);
            return new int[]{values.get(0), values.get(1), values.get(2), values.get(3)};
        }
    }

    private static boolean[] getBoolean4(int name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer values = stack.malloc(4);
            glGetBooleanv(name, values);
            return new boolean[]{
                    values.get(0) != 0, values.get(1) != 0,
                    values.get(2) != 0, values.get(3) != 0};
        }
    }

    private static float[] getFloat4(int name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.FloatBuffer values = stack.mallocFloat(4);
            glGetFloatv(name, values);
            return new float[]{
                    values.get(0), values.get(1), values.get(2), values.get(3)};
        }
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) glEnable(capability);
        else glDisable(capability);
    }

    private record TextureUnit(int texture2d, int texture3d, int textureCube, int sampler) {
    }

    private record IndexedBuffer(int buffer, long start, long size) {
    }

    private record ImageBinding(int texture, int level, boolean layered,
                                int layer, int access, int format) {
    }

    private record StencilFace(int function, int reference, int valueMask, int writeMask,
                               int fail, int depthFail, int depthPass) {
    }
}
