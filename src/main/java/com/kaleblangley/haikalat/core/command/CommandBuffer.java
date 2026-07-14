package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.GpuTimer;
import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.buffer.BufferUploadTarget;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBlitFramebuffer;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.glDispatchCompute;

/**
 * Reusable typed OpenGL command stream.
 *
 * <p>Pipeline setters are last-writer-wins pending state. They are flushed before every
 * observable GPU boundary and at command-buffer completion. Resource bindings and DSA uniforms
 * keep their recorded order, while {@link StateCache} remains the cross-command/frame GL cache.</p>
 */
public final class CommandBuffer {
    private static final byte USE_PROGRAM = 1;
    private static final byte BIND_VERTEX_ARRAY = 2;
    private static final byte BIND_TEXTURE_2D = 3;
    private static final byte BIND_FRAMEBUFFER = 4;
    private static final byte BIND_SAMPLER = 5;
    private static final byte BIND_CHECKED_SAMPLER = 6;
    private static final byte BIND_UNIFORM_BLOCK = 7;
    private static final byte BIND_UNIFORM_BUFFER = 8;
    private static final byte BIND_STORAGE_BUFFER = 9;
    private static final byte BIND_IMAGE = 10;
    private static final byte DISPATCH_COMPUTE = 11;
    private static final byte MEMORY_BARRIER = 12;
    private static final byte DRAW_MESH = 13;
    private static final byte DRAW_ELEMENTS = 14;
    private static final byte DRAW_ARRAYS = 15;
    private static final byte DRAW_ARRAYS_INSTANCED = 16;
    private static final byte DRAW_ELEMENTS_INSTANCED = 17;
    private static final byte DRAW_MESH_INSTANCED = 18;
    private static final byte APPLY_PIPELINE_STATE = 19;
    private static final byte CLEAR = 26;
    private static final byte BLIT_FRAMEBUFFER = 28;
    private static final byte UNIFORM_MAT4 = 29;
    private static final byte UNIFORM_VEC3 = 30;
    private static final byte UNIFORM_VEC2 = 31;
    private static final byte UNIFORM_INT = 32;
    private static final byte UNIFORM_FLOAT = 33;
    private static final byte CUSTOM = 34;
    private static final byte INSTANCED_BATCH = 35;
    private static final byte BEGIN_GPU_TIMER = 36;
    private static final byte END_GPU_TIMER = 37;
    private static final byte DRAW_INSTANCED_BATCH = 38;

    private final CommandStream stream = new CommandStream();
    private final PendingPipelineState pendingState = new PendingPipelineState();

    public CommandBuffer useProgram(int program) {
        opcode(USE_PROGRAM);
        integer(program);
        return this;
    }

    public CommandBuffer bindVertexArray(int vao) {
        opcode(BIND_VERTEX_ARRAY);
        integer(vao);
        return this;
    }

    public CommandBuffer bindTexture(int unit, int texture) {
        opcode(BIND_TEXTURE_2D);
        integer(unit);
        integer(texture);
        return this;
    }

    public CommandBuffer bindFramebuffer(int target, int fbo) {
        opcode(BIND_FRAMEBUFFER);
        integer(target);
        integer(fbo);
        return this;
    }

    public CommandBuffer bindDefaultFramebuffer() {
        return bindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public CommandBuffer bindShader(ShaderProgram shader) {
        Objects.requireNonNull(shader, "shader");
        return useProgram(shader.id());
    }

    public CommandBuffer bindMesh(Mesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        return bindVertexArray(mesh.vertexArray().id());
    }

    public CommandBuffer bindTexture(int unit, Texture2D texture) {
        Objects.requireNonNull(texture, "texture");
        bindTexture(unit, texture.id());
        opcode(BIND_SAMPLER);
        integer(unit);
        integer(0);
        return this;
    }

    public CommandBuffer bindSampler(int unit, Sampler sampler) {
        Objects.requireNonNull(sampler, "sampler");
        opcode(BIND_CHECKED_SAMPLER);
        integer(unit);
        integer(sampler.id());
        object(sampler);
        return this;
    }

    public CommandBuffer bindTexture(int unit, Texture2D texture, Sampler sampler) {
        Objects.requireNonNull(texture, "texture");
        bindTexture(unit, texture.id());
        if (sampler == null) {
            opcode(BIND_SAMPLER);
            integer(unit);
            integer(0);
        } else {
            bindSampler(unit, sampler);
        }
        return this;
    }

    public CommandBuffer bindUniformBlock(int bindingPoint, UniformBlock block) {
        Objects.requireNonNull(block, "block");
        opcode(BIND_UNIFORM_BLOCK);
        integer(bindingPoint);
        integer(block.id());
        integer(block.sizeBytes());
        object(block);
        return this;
    }

    public CommandBuffer bindUniformBuffer(int bindingPoint, BufferUploadTarget buffer,
                                           long offsetBytes, long sizeBytes) {
        Objects.requireNonNull(buffer, "buffer");
        validateBufferRange(bindingPoint, offsetBytes, sizeBytes, "uniform");
        opcode(BIND_UNIFORM_BUFFER);
        integer(bindingPoint);
        integer(buffer.id());
        longValue(offsetBytes);
        longValue(sizeBytes);
        return this;
    }

    public CommandBuffer bindStorageBuffer(int bindingPoint, BufferUploadTarget buffer,
                                           long offsetBytes, long sizeBytes) {
        Objects.requireNonNull(buffer, "buffer");
        validateBufferRange(bindingPoint, offsetBytes, sizeBytes, "shader storage");
        opcode(BIND_STORAGE_BUFFER);
        integer(bindingPoint);
        integer(buffer.id());
        longValue(offsetBytes);
        longValue(sizeBytes);
        return this;
    }

    public CommandBuffer bindImage(int unit, Texture2D texture, int level,
                                   int access, int format) {
        Objects.requireNonNull(texture, "texture");
        if (unit < 0 || level < 0) throw new IllegalArgumentException("invalid image binding");
        opcode(BIND_IMAGE);
        integer(unit);
        integer(texture.id());
        integer(level);
        integer(access);
        integer(format);
        return this;
    }

    /** Observable compute boundary: final pending graphics state is submitted first. */
    public CommandBuffer dispatchCompute(int groupsX, int groupsY, int groupsZ) {
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            throw new IllegalArgumentException("compute group counts must be positive");
        }
        flushPendingState();
        opcode(DISPATCH_COMPUTE);
        integer(groupsX);
        integer(groupsY);
        integer(groupsZ);
        return this;
    }

    /** GPU ordering boundary; pending state is flushed before the barrier is issued. */
    public CommandBuffer memoryBarrier(int barriers) {
        if (barriers == 0) throw new IllegalArgumentException("memory barrier bits must be non-zero");
        flushPendingState();
        opcode(MEMORY_BARRIER);
        integer(barriers);
        return this;
    }

    public CommandBuffer bindFramebuffer(Framebuffer framebuffer) {
        Objects.requireNonNull(framebuffer, "framebuffer");
        return bindFramebuffer(GL_FRAMEBUFFER, framebuffer.id());
    }

    public CommandBuffer drawMesh(Mesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        flushPendingState();
        opcode(DRAW_MESH);
        object(mesh);
        return this;
    }

    public CommandBuffer drawElements(int mode, int count, int type) {
        flushPendingState();
        opcode(DRAW_ELEMENTS);
        integer(mode);
        integer(count);
        integer(type);
        return this;
    }

    public CommandBuffer drawArrays(int mode, int first, int count) {
        flushPendingState();
        opcode(DRAW_ARRAYS);
        integer(mode);
        integer(first);
        integer(count);
        return this;
    }

    public CommandBuffer drawArraysInstanced(int mode, int first,
                                             int vertexCount, int instanceCount) {
        if (first < 0 || vertexCount < 0 || instanceCount < 0) {
            throw new IllegalArgumentException("draw counts must be non-negative");
        }
        flushPendingState();
        opcode(DRAW_ARRAYS_INSTANCED);
        integer(mode);
        integer(first);
        integer(vertexCount);
        integer(instanceCount);
        return this;
    }

    public CommandBuffer drawElementsInstanced(int mode, int indexCount, int indexType,
                                               long indexOffsetBytes, int instanceCount) {
        if (indexCount < 0 || indexOffsetBytes < 0L || instanceCount < 0) {
            throw new IllegalArgumentException("indexed draw counts and offset must be non-negative");
        }
        flushPendingState();
        opcode(DRAW_ELEMENTS_INSTANCED);
        integer(mode);
        integer(indexCount);
        integer(indexType);
        integer(instanceCount);
        longValue(indexOffsetBytes);
        return this;
    }

    public CommandBuffer drawMeshInstanced(Mesh mesh, int instanceCount) {
        Objects.requireNonNull(mesh, "mesh");
        flushPendingState();
        opcode(DRAW_MESH_INSTANCED);
        integer(instanceCount);
        object(mesh);
        return this;
    }

    public CommandBuffer drawInstancedBatch(InstancedMeshBatch batch,
                                            Iterable<Matrix4f> transforms) {
        return drawInstancedBatch(batch, transforms, null);
    }

    public CommandBuffer drawInstancedBatch(InstancedMeshBatch batch,
                                            Iterable<Matrix4f> transforms,
                                            IntConsumer drawnCount) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(transforms, "transforms");
        flushPendingState();
        opcode(DRAW_INSTANCED_BATCH);
        object(batch);
        object(copyTransforms(transforms));
        object(drawnCount);
        return this;
    }

    public CommandBuffer viewport(int x, int y, int width, int height) {
        pendingState.viewport(x, y, width, height);
        return this;
    }

    public CommandBuffer enableBlend(boolean enable) {
        pendingState.enableBlend(enable);
        return this;
    }

    public CommandBuffer depthMask(boolean write) {
        pendingState.depthMask(write);
        return this;
    }

    public CommandBuffer enableDepthTest(boolean enable) {
        pendingState.enableDepthTest(enable);
        return this;
    }

    public CommandBuffer enableCullFace(boolean enable) {
        pendingState.enableCullFace(enable);
        return this;
    }

    public CommandBuffer blendFunc(int sourceRgb, int destinationRgb) {
        pendingState.blendFunc(sourceRgb, destinationRgb);
        return this;
    }

    /** One pending packet; it never reorders or crosses an observable command boundary. */
    public CommandBuffer materialState(BlendMode blendMode, boolean depthTest) {
        Objects.requireNonNull(blendMode, "blendMode");
        pendingState.materialState(blendMode, depthTest);
        return this;
    }

    /** Clear is an observable boundary; depthMask is therefore applied before a depth clear. */
    public CommandBuffer clear(boolean color, boolean depth) {
        flushPendingState();
        opcode(CLEAR);
        integer((color ? GL_COLOR_BUFFER_BIT : 0) | (depth ? GL_DEPTH_BUFFER_BIT : 0));
        return this;
    }

    public CommandBuffer clearColor(float red, float green, float blue, float alpha) {
        pendingState.clearColor(red, green, blue, alpha);
        return this;
    }

    public CommandBuffer blitToDefault(Framebuffer source, int targetWidth, int targetHeight) {
        Objects.requireNonNull(source, "source");
        return blitFramebuffer(source.id(), 0, source.width(), source.height(),
                targetWidth, targetHeight);
    }

    public CommandBuffer blitColor(Framebuffer source, Framebuffer target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return blitFramebuffer(source.id(), target.id(), source.width(), source.height(),
                target.width(), target.height());
    }

    public CommandBuffer blitFramebuffer(int sourceFbo, int targetFbo,
                                         int sourceWidth, int sourceHeight,
                                         int targetWidth, int targetHeight) {
        flushPendingState();
        opcode(BLIT_FRAMEBUFFER);
        integer(sourceFbo);
        integer(targetFbo);
        integer(sourceWidth);
        integer(sourceHeight);
        integer(targetWidth);
        integer(targetHeight);
        return this;
    }

    public CommandBuffer setUniformMat4(ShaderProgram shader, String name, Matrix4f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return uniformMat4(shader, shader.uniformLocation(name), value);
    }

    public CommandBuffer trySetUniformMat4(ShaderProgram shader, String name, Matrix4f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocationOrMinusOne(name);
        return location < 0 ? this : uniformMat4(shader, location, value);
    }

    public CommandBuffer setUniformVec3(ShaderProgram shader, String name, Vector3f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return uniformVec3(shader, shader.uniformLocation(name), value.x, value.y, value.z);
    }

    public CommandBuffer trySetUniformVec3(ShaderProgram shader, String name, Vector3f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocationOrMinusOne(name);
        return location < 0 ? this : uniformVec3(shader, location, value.x, value.y, value.z);
    }

    public CommandBuffer setUniformVec2(ShaderProgram shader, String name, float x, float y) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        opcode(UNIFORM_VEC2);
        integer(shader.uniformLocation(name));
        integer(floatBits(x));
        integer(floatBits(y));
        object(shader);
        return this;
    }

    public CommandBuffer setUniformInt(ShaderProgram shader, String name, int value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        return uniformInt(shader, shader.uniformLocation(name), value);
    }

    public CommandBuffer trySetUniformInt(ShaderProgram shader, String name, int value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocationOrMinusOne(name);
        return location < 0 ? this : uniformInt(shader, location, value);
    }

    public CommandBuffer setUniformFloat(ShaderProgram shader, String name, float value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        return uniformFloat(shader, shader.uniformLocation(name), value);
    }

    public CommandBuffer trySetUniformFloat(ShaderProgram shader, String name, float value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocationOrMinusOne(name);
        return location < 0 ? this : uniformFloat(shader, location, value);
    }

    /** Formal query boundary used by RenderGraph profiling. */
    public CommandBuffer beginGpuTimer(GpuTimer timer) {
        Objects.requireNonNull(timer, "timer");
        flushPendingState();
        opcode(BEGIN_GPU_TIMER);
        object(timer);
        return this;
    }

    /** Formal query boundary used by RenderGraph profiling. */
    public CommandBuffer endGpuTimer(GpuTimer timer) {
        Objects.requireNonNull(timer, "timer");
        flushPendingState();
        opcode(END_GPU_TIMER);
        object(timer);
        return this;
    }

    /**
     * Full escape-hatch barrier: flushes pending state before the callback and invalidates the
     * persistent cache afterwards because arbitrary GL state may have changed.
     */
    public CommandBuffer custom(Runnable action) {
        Objects.requireNonNull(action, "action");
        flushPendingState();
        opcode(CUSTOM);
        object(action);
        return this;
    }

    public void reset() {
        stream.reset();
        pendingState.reset();
    }

    public int commandCount() {
        return stream.commandCount() + (pendingState.isDirty() ? 1 : 0);
    }

    int objectPayloadCount() {
        return stream.objectCount();
    }

    /** Executes typed commands in order and folds pipeline setters up to each boundary. */
    public void execute(StateCache cache) {
        Objects.requireNonNull(cache, "cache");
        flushPendingState();
        int integerCursor = 0;
        int longCursor = 0;
        int objectCursor = 0;
        for (int command = 0; command < stream.commandCount(); command++) {
                byte opcode = stream.opcodeAt(command);
                switch (opcode) {
                    case USE_PROGRAM -> cache.useProgram(stream.integerAt(integerCursor++));
                    case BIND_VERTEX_ARRAY -> cache.bindVertexArray(stream.integerAt(integerCursor++));
                    case BIND_TEXTURE_2D -> cache.bindTexture2D(
                            stream.integerAt(integerCursor++), stream.integerAt(integerCursor++));
                    case BIND_FRAMEBUFFER -> cache.bindFramebuffer(
                            stream.integerAt(integerCursor++), stream.integerAt(integerCursor++));
                    case BIND_SAMPLER -> cache.bindSampler(
                            stream.integerAt(integerCursor++), stream.integerAt(integerCursor++));
                    case BIND_CHECKED_SAMPLER -> {
                        int unit = stream.integerAt(integerCursor++);
                        int samplerId = stream.integerAt(integerCursor++);
                        Sampler sampler = (Sampler) stream.objectAt(objectCursor++);
                        sampler.ensureOpen();
                        cache.bindSampler(unit, samplerId);
                    }
                    case BIND_UNIFORM_BLOCK -> {
                        int bindingPoint = stream.integerAt(integerCursor++);
                        int buffer = stream.integerAt(integerCursor++);
                        int size = stream.integerAt(integerCursor++);
                        UniformBlock block = (UniformBlock) stream.objectAt(objectCursor++);
                        block.flush();
                        cache.bindUniformBufferRange(bindingPoint, buffer, 0L, size);
                    }
                    case BIND_UNIFORM_BUFFER -> {
                        int bindingPoint = stream.integerAt(integerCursor++);
                        int buffer = stream.integerAt(integerCursor++);
                        long offset = stream.longAt(longCursor++);
                        long size = stream.longAt(longCursor++);
                        cache.bindUniformBufferRange(bindingPoint, buffer, offset, size);
                    }
                    case BIND_STORAGE_BUFFER -> {
                        int bindingPoint = stream.integerAt(integerCursor++);
                        int buffer = stream.integerAt(integerCursor++);
                        long offset = stream.longAt(longCursor++);
                        long size = stream.longAt(longCursor++);
                        cache.bindStorageBufferRange(bindingPoint, buffer, offset, size);
                    }
                    case BIND_IMAGE -> cache.bindImageTexture(
                            stream.integerAt(integerCursor++), stream.integerAt(integerCursor++),
                            stream.integerAt(integerCursor++), false, 0,
                            stream.integerAt(integerCursor++), stream.integerAt(integerCursor++));
                    case DISPATCH_COMPUTE -> {
                        glDispatchCompute(stream.integerAt(integerCursor++),
                                stream.integerAt(integerCursor++), stream.integerAt(integerCursor++));
                    }
                    case MEMORY_BARRIER -> {
                        glMemoryBarrier(stream.integerAt(integerCursor++));
                    }
                    case DRAW_MESH -> {
                        ((Mesh) stream.objectAt(objectCursor++)).drawBound();
                    }
                    case DRAW_ELEMENTS -> {
                        glDrawElements(stream.integerAt(integerCursor++),
                                stream.integerAt(integerCursor++), stream.integerAt(integerCursor++), 0L);
                    }
                    case DRAW_ARRAYS -> {
                        glDrawArrays(stream.integerAt(integerCursor++),
                                stream.integerAt(integerCursor++), stream.integerAt(integerCursor++));
                    }
                    case DRAW_ARRAYS_INSTANCED -> {
                        glDrawArraysInstanced(stream.integerAt(integerCursor++),
                                stream.integerAt(integerCursor++), stream.integerAt(integerCursor++),
                                stream.integerAt(integerCursor++));
                    }
                    case DRAW_ELEMENTS_INSTANCED -> {
                        int mode = stream.integerAt(integerCursor++);
                        int count = stream.integerAt(integerCursor++);
                        int type = stream.integerAt(integerCursor++);
                        int instances = stream.integerAt(integerCursor++);
                        glDrawElementsInstanced(mode, count, type,
                                stream.longAt(longCursor++), instances);
                    }
                    case DRAW_MESH_INSTANCED -> {
                        int instances = stream.integerAt(integerCursor++);
                        ((Mesh) stream.objectAt(objectCursor++)).drawInstancedBound(instances);
                    }
                    case APPLY_PIPELINE_STATE -> integerCursor = PendingPipelineState.applyEncoded(
                            stream, integerCursor, cache);
                    case CLEAR -> {
                        cache.clear(stream.integerAt(integerCursor++));
                    }
                    case BLIT_FRAMEBUFFER -> {
                        int sourceFbo = stream.integerAt(integerCursor++);
                        int targetFbo = stream.integerAt(integerCursor++);
                        int sourceWidth = stream.integerAt(integerCursor++);
                        int sourceHeight = stream.integerAt(integerCursor++);
                        int targetWidth = stream.integerAt(integerCursor++);
                        int targetHeight = stream.integerAt(integerCursor++);
                        cache.bindFramebuffer(GL_READ_FRAMEBUFFER, sourceFbo);
                        cache.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFbo);
                        glBlitFramebuffer(0, 0, sourceWidth, sourceHeight,
                                0, 0, targetWidth, targetHeight, GL_COLOR_BUFFER_BIT, GL_NEAREST);
                        cache.bindFramebuffer(GL_FRAMEBUFFER, 0);
                    }
                    case UNIFORM_MAT4 -> {
                        int location = stream.integerAt(integerCursor++);
                        ShaderProgram shader = (ShaderProgram) stream.objectAt(objectCursor++);
                        Matrix4f matrix = (Matrix4f) stream.objectAt(objectCursor++);
                        shader.setMat4(location, matrix);
                    }
                    case UNIFORM_VEC3 -> {
                        int location = stream.integerAt(integerCursor++);
                        float x = floatAt(stream.integerAt(integerCursor++));
                        float y = floatAt(stream.integerAt(integerCursor++));
                        float z = floatAt(stream.integerAt(integerCursor++));
                        ((ShaderProgram) stream.objectAt(objectCursor++)).setVec3(location, x, y, z);
                    }
                    case UNIFORM_VEC2 -> {
                        int location = stream.integerAt(integerCursor++);
                        float x = floatAt(stream.integerAt(integerCursor++));
                        float y = floatAt(stream.integerAt(integerCursor++));
                        ((ShaderProgram) stream.objectAt(objectCursor++)).setVec2(location, x, y);
                    }
                    case UNIFORM_INT -> {
                        int location = stream.integerAt(integerCursor++);
                        int value = stream.integerAt(integerCursor++);
                        ((ShaderProgram) stream.objectAt(objectCursor++)).setInt(location, value);
                    }
                    case UNIFORM_FLOAT -> {
                        int location = stream.integerAt(integerCursor++);
                        float value = floatAt(stream.integerAt(integerCursor++));
                        ((ShaderProgram) stream.objectAt(objectCursor++)).setFloat(location, value);
                    }
                    case CUSTOM -> {
                        try {
                            ((Runnable) stream.objectAt(objectCursor++)).run();
                        } finally {
                            cache.invalidate();
                        }
                    }
                    case INSTANCED_BATCH -> {
                        InstancedBatchSubmission submission =
                                (InstancedBatchSubmission) stream.objectAt(objectCursor++);
                        @SuppressWarnings("unchecked")
                        List<Matrix4f> transforms =
                                (List<Matrix4f>) stream.objectAt(objectCursor++);
                        submission.beginFrame();
                        submission.submitAll(transforms);
                        submission.drawn(submission.flush());
                        cache.invalidateVertexArray();
                    }
                    case DRAW_INSTANCED_BATCH -> {
                        InstancedMeshBatch batch =
                                (InstancedMeshBatch) stream.objectAt(objectCursor++);
                        @SuppressWarnings("unchecked")
                        List<Matrix4f> transforms =
                                (List<Matrix4f>) stream.objectAt(objectCursor++);
                        IntConsumer drawnCount =
                                (IntConsumer) stream.objectAt(objectCursor++);
                        batch.beginFrame();
                        batch.submitAll(transforms);
                        int drawn = batch.flush();
                        if (drawnCount != null) drawnCount.accept(drawn);
                        cache.invalidateVertexArray();
                    }
                    case BEGIN_GPU_TIMER -> {
                        ((GpuTimer) stream.objectAt(objectCursor++)).begin();
                    }
                    case END_GPU_TIMER -> {
                        ((GpuTimer) stream.objectAt(objectCursor++)).end();
                    }
                    default -> throw new IllegalStateException("Unknown command opcode " + opcode);
                }
        }
    }

    CommandBuffer recordInstancedBatch(InstancedBatchSubmission submission,
                                       Iterable<Matrix4f> transforms) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(transforms, "transforms");
        flushPendingState();
        opcode(INSTANCED_BATCH);
        object(submission);
        object(copyTransforms(transforms));
        return this;
    }

    private CommandBuffer uniformMat4(ShaderProgram shader, int location, Matrix4f value) {
        opcode(UNIFORM_MAT4);
        integer(location);
        object(shader);
        object(new Matrix4f(value));
        return this;
    }

    private CommandBuffer uniformVec3(ShaderProgram shader, int location,
                                      float x, float y, float z) {
        opcode(UNIFORM_VEC3);
        integer(location);
        integer(floatBits(x));
        integer(floatBits(y));
        integer(floatBits(z));
        object(shader);
        return this;
    }

    private CommandBuffer uniformInt(ShaderProgram shader, int location, int value) {
        opcode(UNIFORM_INT);
        integer(location);
        integer(value);
        object(shader);
        return this;
    }

    private CommandBuffer uniformFloat(ShaderProgram shader, int location, float value) {
        opcode(UNIFORM_FLOAT);
        integer(location);
        integer(floatBits(value));
        object(shader);
        return this;
    }

    private void flushPendingState() {
        pendingState.writeTo(stream, APPLY_PIPELINE_STATE);
    }

    private void opcode(byte opcode) {
        stream.opcode(opcode);
    }

    private void integer(int value) {
        stream.integer(value);
    }

    private void longValue(long value) {
        stream.longValue(value);
    }

    private void object(Object value) {
        stream.object(value);
    }

    private static void validateBufferRange(int bindingPoint, long offsetBytes,
                                            long sizeBytes, String kind) {
        if (bindingPoint < 0 || offsetBytes < 0L || sizeBytes <= 0L) {
            throw new IllegalArgumentException("invalid " + kind + " buffer range");
        }
    }

    private static int floatBits(float value) {
        return Float.floatToRawIntBits(value);
    }

    private static float floatAt(int bits) {
        return Float.intBitsToFloat(bits);
    }

    private static List<Matrix4f> copyTransforms(Iterable<Matrix4f> transforms) {
        List<Matrix4f> copied = new ArrayList<>();
        for (Matrix4f transform : transforms) {
            copied.add(new Matrix4f(Objects.requireNonNull(transform, "transform")));
        }
        return copied;
    }
}

interface InstancedBatchSubmission {
    void beginFrame();

    void submitAll(Iterable<Matrix4f> transforms);

    int flush();

    default void drawn(int count) {
    }
}
