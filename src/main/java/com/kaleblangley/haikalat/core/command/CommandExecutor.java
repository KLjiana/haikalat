package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.GpuTimer;
import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

import static com.kaleblangley.haikalat.core.command.CommandBuffer.*;
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
 * 解释并执行 {@link CommandStream} 中已经定型的 opcode。
 *
 * <p>该类不参与命令录制和 pending state 合并，只负责严格按照记录顺序调用
 * {@link StateCache} 或对应的 OpenGL 边界操作。</p>
 */
final class CommandExecutor {
    private CommandExecutor() {
    }

    static void execute(CommandStream stream, StateCache cache) {
        Objects.requireNonNull(cache, "cache");
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
                    float x = Float.intBitsToFloat(stream.integerAt(integerCursor++));
                    float y = Float.intBitsToFloat(stream.integerAt(integerCursor++));
                    float z = Float.intBitsToFloat(stream.integerAt(integerCursor++));
                    ((ShaderProgram) stream.objectAt(objectCursor++)).setVec3(location, x, y, z);
                }
                case UNIFORM_VEC2 -> {
                    int location = stream.integerAt(integerCursor++);
                    float x = Float.intBitsToFloat(stream.integerAt(integerCursor++));
                    float y = Float.intBitsToFloat(stream.integerAt(integerCursor++));
                    ((ShaderProgram) stream.objectAt(objectCursor++)).setVec2(location, x, y);
                }
                case UNIFORM_INT -> {
                    int location = stream.integerAt(integerCursor++);
                    int value = stream.integerAt(integerCursor++);
                    ((ShaderProgram) stream.objectAt(objectCursor++)).setInt(location, value);
                }
                case UNIFORM_FLOAT -> {
                    int location = stream.integerAt(integerCursor++);
                    float value = Float.intBitsToFloat(stream.integerAt(integerCursor++));
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
                    try {
                        submission.beginFrame();
                        submission.submitAll(transforms);
                        submission.drawn(submission.flush());
                    } finally {
                        cache.invalidateVertexArray();
                    }
                }
                case DRAW_INSTANCED_BATCH -> {
                    InstancedMeshBatch batch =
                            (InstancedMeshBatch) stream.objectAt(objectCursor++);
                    @SuppressWarnings("unchecked")
                    List<Matrix4f> transforms =
                            (List<Matrix4f>) stream.objectAt(objectCursor++);
                    IntConsumer drawnCount =
                            (IntConsumer) stream.objectAt(objectCursor++);
                    try {
                        batch.beginFrame();
                        batch.submitOwnedSnapshots(transforms);
                        int drawn = batch.flush();
                        if (drawnCount != null) drawnCount.accept(drawn);
                    } finally {
                        cache.invalidateVertexArray();
                    }
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
}
