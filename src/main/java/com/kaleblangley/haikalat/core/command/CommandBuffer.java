package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform2f;
import static org.lwjgl.opengl.GL20.glUniform3f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBlitFramebuffer;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.glBindBufferRange;

public final class CommandBuffer {
    private final List<Consumer<StateCache>> commands = new ArrayList<>(64);

    /**
     * 记录一条使用着色器程序的命令。
     *
     * @param program OpenGL 程序 ID
     * @return 自身，支持链式调用
     */
    public CommandBuffer useProgram(int program) {
        commands.add(cache -> cache.useProgram(program));
        return this;
    }

    /**
     * 记录一条绑定 VAO 的命令。
     *
     * @param vao VAO ID
     * @return 自身，支持链式调用
     */
    public CommandBuffer bindVertexArray(int vao) {
        commands.add(cache -> cache.bindVertexArray(vao));
        return this;
    }

    /**
     * 记录一条在指定纹理单元上绑定 2D 纹理的命令。
     *
     * @param unit    纹理单元索引
     * @param texture 纹理 ID
     * @return 自身，支持链式调用
     */
    public CommandBuffer bindTexture(int unit, int texture) {
        commands.add(cache -> cache.bindTexture2D(unit, texture));
        return this;
    }

    /**
     * 记录一条按目标绑定帧缓冲的命令。
     *
     * @param target 帧缓冲目标
     * @param fbo    FBO ID
     * @return 自身，支持链式调用
     */
    public CommandBuffer bindFramebuffer(int target, int fbo) {
        commands.add(cache -> cache.bindFramebuffer(target, fbo));
        return this;
    }

    public CommandBuffer bindDefaultFramebuffer() {
        return bindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * 记录绑定 ShaderProgram 的命令（通过 useProgram 实现）。
     *
     * @param shader 着色器程序
     * @return 自身，支持链式调用
     */
    public CommandBuffer bindShader(ShaderProgram shader) {
        Objects.requireNonNull(shader, "shader");
        return useProgram(shader.id());
    }

    /**
     * 记录绑定 Mesh 的 VAO 的命令。
     *
     * @param mesh 网格数据
     * @return 自身，支持链式调用
     */
    public CommandBuffer bindMesh(Mesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        return bindVertexArray(mesh.vertexArray().id());
    }

    /**
     * 记录在指定单元上绑定 Texture2D 的命令。
     *
     * @param unit    纹理单元
     * @param texture 纹理对象
     * @return 自身，支持链式调用
     */
    public CommandBuffer bindTexture(int unit, Texture2D texture) {
        Objects.requireNonNull(texture, "texture");
        bindTexture(unit, texture.id());
        commands.add(cache -> cache.bindSampler(unit, 0));
        return this;
    }

    public CommandBuffer bindSampler(int unit, Sampler sampler) {
        Objects.requireNonNull(sampler, "sampler");
        int samplerId = sampler.id();
        commands.add(cache -> {
            sampler.ensureOpen();
            cache.bindSampler(unit, samplerId);
        });
        return this;
    }

    public CommandBuffer bindTexture(int unit, Texture2D texture, Sampler sampler) {
        bindTexture(unit, texture);
        if (sampler != null) {
            bindSampler(unit, sampler);
        }
        return this;
    }

    public CommandBuffer bindUniformBlock(int bindingPoint, UniformBlock block) {
        Objects.requireNonNull(block, "block");
        int buffer = block.id();
        int size = block.sizeBytes();
        commands.add(cache -> {
            block.flush();
            glBindBufferRange(GL_UNIFORM_BUFFER, bindingPoint, buffer, 0, size);
        });
        return this;
    }

    /**
     * 记录绑定 Framebuffer 的命令。
     *
     * @param fb 帧缓冲对象
     * @return 自身，支持链式调用
     */
    public CommandBuffer bindFramebuffer(Framebuffer fb) {
        Objects.requireNonNull(fb, "fb");
        return bindFramebuffer(GL_FRAMEBUFFER, fb.id());
    }

    /**
     * 记录绘制 Mesh 的命令（假设 VAO 已被绑定）。
     *
     * @param mesh 要绘制的网格
     * @return 自身，支持链式调用
     */
    public CommandBuffer drawMesh(Mesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        commands.add(cache -> mesh.drawBound());
        return this;
    }

    /**
     * 记录一条索引绘制命令。
     *
     * @param mode  绘制图元类型
     * @param count 索引数量
     * @param type  索引数据类型
     * @return 自身，支持链式调用
     */
    public CommandBuffer drawElements(int mode, int count, int type) {
        commands.add(cache -> glDrawElements(mode, count, type, 0L));
        return this;
    }

    /**
     * 记录一条顶点数组绘制命令。
     *
     * @param mode  绘制图元类型
     * @param first 起始顶点索引
     * @param count 顶点数量
     * @return 自身，支持链式调用
     */
    public CommandBuffer drawArrays(int mode, int first, int count) {
        commands.add(cache -> glDrawArrays(mode, first, count));
        return this;
    }

    /**
     * 记录实例化绘制 Mesh 的命令。
     *
     * @param mesh          网格
     * @param instanceCount 实例数量
     * @return 自身，支持链式调用
     */
    public CommandBuffer drawMeshInstanced(Mesh mesh, int instanceCount) {
        Objects.requireNonNull(mesh, "mesh");
        commands.add(cache -> mesh.drawInstancedBound(instanceCount));
        return this;
    }

    /**
     * Records the standard instanced batch upload/draw path.
     *
     * <p>The transform iterable is copied immediately during recording, so callers may
     * pass data from a producer thread as long as each {@link Matrix4f} represents the
     * intended value at record time. The command executes on the render thread and runs
     * {@code beginFrame -> submitAll -> flush}. The underlying batch upload and draw
     * calls own their GL state setup; they do not currently go through {@link StateCache}.</p>
     *
     * @param batch instanced batch to upload and draw
     * @param transforms transforms to submit to the batch
     * @return this command buffer
     */
    public CommandBuffer drawInstancedBatch(InstancedMeshBatch batch, Iterable<Matrix4f> transforms) {
        return drawInstancedBatch(batch, transforms, null);
    }

    /**
     * Records the standard instanced batch upload/draw path and reports the drawn
     * instance count after execution.
     *
     * <p>See {@link #drawInstancedBatch(InstancedMeshBatch, Iterable)} for StateCache
     * and cross-thread capture rules.</p>
     *
     * @param batch instanced batch to upload and draw
     * @param transforms transforms to submit to the batch
     * @param drawnCount receives the result of {@link InstancedMeshBatch#flush()}, may be null
     * @return this command buffer
     */
    public CommandBuffer drawInstancedBatch(InstancedMeshBatch batch, Iterable<Matrix4f> transforms,
                                            IntConsumer drawnCount) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(transforms, "transforms");
        return recordInstancedBatch(new InstancedBatchSubmission() {
            @Override
            public void beginFrame() {
                batch.beginFrame();
            }

            @Override
            public void submitAll(Iterable<Matrix4f> submittedTransforms) {
                batch.submitAll(submittedTransforms);
            }

            @Override
            public int flush() {
                return batch.flush();
            }

            @Override
            public void drawn(int count) {
                if (drawnCount != null) {
                    drawnCount.accept(count);
                }
            }
        }, transforms);
    }

    /**
     * 记录设置视口的命令。
     *
     * @param x 左下角 x
     * @param y 左下角 y
     * @param w 宽度
     * @param h 高度
     * @return 自身，支持链式调用
     */
    public CommandBuffer viewport(int x, int y, int w, int h) {
        commands.add(cache -> cache.viewport(x, y, w, h));
        return this;
    }

    /**
     * 记录启用/禁用混合的命令。
     *
     * @param enable true 启用
     * @return 自身，支持链式调用
     */
    public CommandBuffer enableBlend(boolean enable) {
        commands.add(cache -> cache.enableBlend(enable));
        return this;
    }

    /**
     * 记录深度掩码的命令。
     *
     * @param write true 允许深度写入
     * @return 自身，支持链式调用
     */
    public CommandBuffer depthMask(boolean write) {
        commands.add(cache -> cache.depthMask(write));
        return this;
    }

    /**
     * 记录启用/禁用深度测试的命令。
     *
     * @param enable true 启用
     * @return 自身，支持链式调用
     */
    public CommandBuffer enableDepthTest(boolean enable) {
        commands.add(cache -> cache.enableDepthTest(enable));
        return this;
    }

    /**
     * 记录设置混合函数的命令。
     *
     * @param srcRGB 源因子
     * @param dstRGB 目标因子
     * @return 自身，支持链式调用
     */
    public CommandBuffer blendFunc(int srcRGB, int dstRGB) {
        commands.add(cache -> cache.blendFunc(srcRGB, dstRGB));
        return this;
    }

    /**
     * 记录清除缓冲区的命令。
     *
     * @param color true 清除颜色缓冲区
     * @param depth true 清除深度缓冲区
     * @return 自身，支持链式调用
     */
    public CommandBuffer clear(boolean color, boolean depth) {
        int mask = (color ? GL_COLOR_BUFFER_BIT : 0) | (depth ? GL_DEPTH_BUFFER_BIT : 0);
        commands.add(cache -> cache.clear(mask));
        return this;
    }

    /**
     * 记录设置清除颜色的命令。
     *
     * @param r 红色分量
     * @param g 绿色分量
     * @param b 蓝色分量
     * @param a 透明分量
     * @return 自身，支持链式调用
     */
    public CommandBuffer clearColor(float r, float g, float b, float a) {
        commands.add(cache -> cache.clearColor(r, g, b, a));
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
        commands.add(cache -> {
            cache.bindFramebuffer(GL_READ_FRAMEBUFFER, sourceFbo);
            cache.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFbo);
            glBlitFramebuffer(0, 0, sourceWidth, sourceHeight,
                    0, 0, targetWidth, targetHeight,
                    GL_COLOR_BUFFER_BIT, GL_NEAREST);
            cache.bindFramebuffer(GL_FRAMEBUFFER, 0);
        });
        return this;
    }

    /**
     * 记录设置 mat4 uniform 的命令（捕获副本以避免外部修改）。
     *
     * @param shader 目标着色器程序
     * @param name   uniform 名称
     * @param value  矩阵值
     * @return 自身，支持链式调用
     */
    public CommandBuffer setUniformMat4(ShaderProgram shader, String name, Matrix4f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocation(name);
        Matrix4f copy = new Matrix4f(value);
        commands.add(cache -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = stack.mallocFloat(16);
                copy.get(buf);
                glUniformMatrix4fv(location, false, buf);
            }
        });
        return this;
    }

    public CommandBuffer trySetUniformMat4(ShaderProgram shader, String name, Matrix4f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocationOrMinusOne(name);
        if (location < 0) {
            return this;
        }
        Matrix4f copy = new Matrix4f(value);
        commands.add(cache -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = stack.mallocFloat(16);
                copy.get(buf);
                glUniformMatrix4fv(location, false, buf);
            }
        });
        return this;
    }

    /**
     * 记录设置 vec3 uniform 的命令。
     *
     * @param shader 着色器程序
     * @param name   uniform 名称
     * @param value  向量值
     * @return 自身，支持链式调用
     */
    public CommandBuffer setUniformVec3(ShaderProgram shader, String name, Vector3f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocation(name);
        float x = value.x;
        float y = value.y;
        float z = value.z;
        commands.add(cache -> glUniform3f(location, x, y, z));
        return this;
    }

    public CommandBuffer trySetUniformVec3(ShaderProgram shader, String name, Vector3f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocationOrMinusOne(name);
        if (location < 0) {
            return this;
        }
        float x = value.x;
        float y = value.y;
        float z = value.z;
        commands.add(cache -> glUniform3f(location, x, y, z));
        return this;
    }

    /**
     * 记录设置 vec2 uniform 的命令。
     *
     * @param shader 着色器程序
     * @param name   uniform 名称
     * @param x      x 分量
     * @param y      y 分量
     * @return 自身，支持链式调用
     */
    public CommandBuffer setUniformVec2(ShaderProgram shader, String name, float x, float y) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocation(name);
        commands.add(cache -> glUniform2f(location, x, y));
        return this;
    }

    /**
     * 记录设置 int uniform 的命令。
     *
     * @param shader 着色器程序
     * @param name   uniform 名称
     * @param value  整数值
     * @return 自身，支持链式调用
     */
    public CommandBuffer setUniformInt(ShaderProgram shader, String name, int value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocation(name);
        commands.add(cache -> glUniform1i(location, value));
        return this;
    }

    public CommandBuffer trySetUniformInt(ShaderProgram shader, String name, int value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocationOrMinusOne(name);
        if (location < 0) {
            return this;
        }
        commands.add(cache -> glUniform1i(location, value));
        return this;
    }

    /**
     * 记录设置 float uniform 的命令。
     *
     * @param shader 着色器程序
     * @param name   uniform 名称
     * @param value  浮点值
     * @return 自身，支持链式调用
     */
    public CommandBuffer setUniformFloat(ShaderProgram shader, String name, float value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocation(name);
        commands.add(cache -> glUniform1f(location, value));
        return this;
    }

    public CommandBuffer trySetUniformFloat(ShaderProgram shader, String name, float value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        int location = shader.uniformLocationOrMinusOne(name);
        if (location < 0) {
            return this;
        }
        commands.add(cache -> glUniform1f(location, value));
        return this;
    }

    /**
     * 记录一条自定义 GL 命令。
     *
     * <p>Use this only as a temporary escape hatch. Captured data must be immutable,
     * copied before recording, or owned by the render thread until execution. Do not
     * capture mutable producer-thread objects whose contents can change before the
     * command buffer is executed.</p>
     *
     * @param action 要执行的 Runnable
     * @return 自身，支持链式调用
     */
    public CommandBuffer custom(Runnable action) {
        Objects.requireNonNull(action, "action");
        commands.add(cache -> action.run());
        return this;
    }

    /** 清空所有已记录的命令。 */
    public void reset() {
        commands.clear();
    }

    /**
     * 依次执行所有已记录的命令，使用给定的状态缓存。
     *
     * @param cache 用于去重的状态缓存
     */
    public void execute(StateCache cache) {
        for (Consumer<StateCache> cmd : commands) {
            cmd.accept(cache);
        }
    }

    /** @return 当前已记录的命令数量 */
    public int commandCount() {
        return commands.size();
    }

    CommandBuffer recordInstancedBatch(InstancedBatchSubmission submission, Iterable<Matrix4f> transforms) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(transforms, "transforms");
        List<Matrix4f> copiedTransforms = new ArrayList<>();
        for (Matrix4f transform : transforms) {
            copiedTransforms.add(new Matrix4f(Objects.requireNonNull(transform, "transform")));
        }
        commands.add(cache -> {
            submission.beginFrame();
            submission.submitAll(copiedTransforms);
            submission.drawn(submission.flush());
        });
        return this;
    }
}

interface InstancedBatchSubmission {
    void beginFrame();

    void submitAll(Iterable<Matrix4f> transforms);

    int flush();

    default void drawn(int count) {
    }
}
