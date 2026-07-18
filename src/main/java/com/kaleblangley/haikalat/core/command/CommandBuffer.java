package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.GpuTimer;
import com.kaleblangley.haikalat.backend.GlFormats;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.UniformBlock;
import com.kaleblangley.haikalat.backend.buffer.BufferUploadTarget;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.sync.GpuFenceTarget;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureCube;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/**
 * 可复用的强类型 OpenGL 命令流。
 *
 * <p>管线状态采用“最后写入生效”的 pending state，并在每个可观察 GPU 边界及命令结束时提交。
 * 资源绑定和 DSA uniform 保持记录顺序，{@link StateCache} 继续负责跨命令、跨帧的 OpenGL 去重。</p>
 */
public final class CommandBuffer {
    static final byte USE_PROGRAM = 1;
    static final byte BIND_VERTEX_ARRAY = 2;
    static final byte BIND_TEXTURE_2D = 3;
    static final byte BIND_FRAMEBUFFER = 4;
    static final byte BIND_SAMPLER = 5;
    static final byte BIND_CHECKED_SAMPLER = 6;
    static final byte BIND_UNIFORM_BLOCK = 7;
    static final byte BIND_UNIFORM_BUFFER = 8;
    static final byte BIND_STORAGE_BUFFER = 9;
    static final byte BIND_IMAGE = 10;
    static final byte DISPATCH_COMPUTE = 11;
    static final byte MEMORY_BARRIER = 12;
    static final byte DRAW_MESH = 13;
    static final byte DRAW_ELEMENTS = 14;
    static final byte DRAW_ARRAYS = 15;
    static final byte DRAW_ARRAYS_INSTANCED = 16;
    static final byte DRAW_ELEMENTS_INSTANCED = 17;
    static final byte DRAW_MESH_INSTANCED = 18;
    static final byte APPLY_PIPELINE_STATE = 19;
    static final byte CLEAR = 26;
    static final byte BLIT_FRAMEBUFFER = 28;
    static final byte UNIFORM_MAT4 = 29;
    static final byte UNIFORM_VEC3 = 30;
    static final byte UNIFORM_VEC2 = 31;
    static final byte UNIFORM_INT = 32;
    static final byte UNIFORM_FLOAT = 33;
    static final byte CUSTOM = 34;
    static final byte INSTANCED_BATCH = 35;
    static final byte BEGIN_GPU_TIMER = 36;
    static final byte END_GPU_TIMER = 37;
    static final byte DRAW_INSTANCED_BATCH = 38;
    static final byte PREPARE_INSTANCED_BATCH = 39;
    static final byte DRAW_PREPARED_INSTANCED_BATCH = 40;
    static final byte FINISH_PREPARED_INSTANCED_BATCH = 41;
    static final byte UPLOAD_TEXTURE_REGION = 42;
    static final byte INSERT_GPU_FENCE = 43;
    static final byte BIND_TEXTURE_CUBE = 44;
    static final byte BIND_IMAGE_CUBE = 45;
    static final byte UNIFORM_VEC4 = 46;
    static final byte GENERATE_CUBE_MIPMAPS = 47;
    static final byte BIND_IMAGE_2D_TYPED = 48;
    static final byte PUSH_DEBUG_GROUP = 49;
    static final byte POP_DEBUG_GROUP = 50;

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

    /** 记录带执行期生命周期校验的 cubemap sampling binding。 */
    public CommandBuffer bindTextureCube(int unit, TextureCube texture, Sampler sampler) {
        Objects.requireNonNull(texture, "texture").ensureOpen();
        if (unit < 0) throw new IllegalArgumentException("texture unit must be non-negative");
        opcode(BIND_TEXTURE_CUBE);
        integer(unit);
        object(texture);
        if (sampler == null) {
            opcode(BIND_SAMPLER);
            integer(unit);
            integer(0);
        } else {
            bindSampler(unit, sampler);
        }
        return this;
    }

    public CommandBuffer bindTextureCube(int unit, TextureCube texture) {
        return bindTextureCube(unit, texture, null);
    }

    /**
     * 记录正式的动态纹理 region upload 边界。
     *
     * <p>payload 在记录时复制为 command-owned direct buffer，因此调用方随后修改
     * position、limit 或底层字节不会改变执行结果。上传前先提交最终 pending pipeline state，
     * 且执行路径不使用 {@link #custom(Runnable)}。</p>
     *
     * @param texture 目标纹理
     * @param x 左边界
     * @param y 下边界
     * @param width 区域宽度，允许为零
     * @param height 区域高度，允许为零
     * @param pixels 紧密排列的像素 payload；可以是 heap 或 direct buffer
     * @return 当前命令缓冲区
     */
    public CommandBuffer uploadTextureRegion(Texture2D texture,
                                              int x, int y, int width, int height,
                                              ByteBuffer pixels) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(pixels, "pixels");
        int requiredBytes = texture.requiredRegionBytes(x, y, width, height);
        if (pixels.remaining() < requiredBytes) {
            throw new IllegalArgumentException("texture upload payload is too small: required "
                    + requiredBytes + ", remaining " + pixels.remaining());
        }

        ByteBuffer source = pixels.duplicate();
        source.limit(source.position() + requiredBytes);
        ByteBuffer copied = ByteBuffer.allocateDirect(requiredBytes).order(pixels.order());
        copied.put(source).flip();
        ByteBuffer stablePayload = copied.asReadOnlyBuffer().order(copied.order());

        flushPendingState();
        opcode(UPLOAD_TEXTURE_REGION);
        integer(x);
        integer(y);
        integer(width);
        integer(height);
        object(texture);
        object(stablePayload);
        return this;
    }

    /**
     * 在此前录制的 GPU 工作之后插入资源生命周期 fence。
     * 这是 ring buffer 提交的正式边界，不允许执行任意渲染逻辑。
     */
    public CommandBuffer insertGpuFence(GpuFenceTarget target) {
        flushPendingState();
        GpuFenceTarget requiredTarget = Objects.requireNonNull(target, "target");
        opcode(INSERT_GPU_FENCE);
        object(requiredTarget);
        stream.gpuFenceTarget(requiredTarget);
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

    /** 记录带生命周期检查的 2D image binding。 */
    public CommandBuffer bindImage(Texture2D texture, int unit, int mipLevel,
                                   ImageAccess access, RenderFormat format) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(format, "format");
        if (unit < 0 || mipLevel != 0) {
            throw new IllegalArgumentException("Texture2D currently supports image mip level 0 only");
        }
        if (texture.format() != GlFormats.toGl(format)) {
            throw new IllegalArgumentException("2D image format must match texture storage format");
        }
        opcode(BIND_IMAGE_2D_TYPED);
        integer(unit);
        integer(mipLevel);
        integer(access.glValue());
        integer(GlFormats.toGl(format));
        object(texture);
        return this;
    }

    /** 以 layered image 覆盖 cubemap 六个 face。 */
    public CommandBuffer bindImage(TextureCube texture, int unit, int mipLevel,
                                   ImageAccess access, RenderFormat format) {
        Objects.requireNonNull(texture, "texture").ensureOpen();
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(format, "format");
        if (unit < 0 || mipLevel < 0 || mipLevel >= texture.mipLevels()) {
            throw new IllegalArgumentException("invalid cubemap image binding");
        }
        if (texture.format() != format) {
            throw new IllegalArgumentException("cubemap image format must match storage format");
        }
        opcode(BIND_IMAGE_CUBE);
        integer(unit);
        integer(mipLevel);
        integer(access.glValue());
        integer(GlFormats.toGl(format));
        object(texture);
        return this;
    }

    /** 在已写入 mip 0 后为 cubemap 生成完整 mip 链。 */
    public CommandBuffer generateMipmaps(TextureCube texture) {
        Objects.requireNonNull(texture, "texture").ensureOpen();
        if (texture.mipLevels() <= 1) return this;
        flushPendingState();
        opcode(GENERATE_CUBE_MIPMAPS);
        object(texture);
        return this;
    }

    /**
     * 记录 compute 边界；提交 dispatch 前先提交最终 pending state。
     *
     * @param groupsX X 方向工作组数量
     * @param groupsY Y 方向工作组数量
     * @param groupsZ Z 方向工作组数量
     * @return 当前命令缓冲区
     */
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

    /**
     * 记录 GPU 内存顺序边界；发出 barrier 前先提交 pending state。
     *
     * @param barriers OpenGL memory barrier 位集合
     * @return 当前命令缓冲区
     */
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

    /**
     * 上传一次实例快照，供后续多个 RenderGraph pass 复用。
     *
     * @param batch      实例批次
     * @param transforms 当前帧稳定变换
     * @return 当前命令缓冲区
     */
    public CommandBuffer prepareInstancedBatch(InstancedMeshBatch batch,
                                               Iterable<Matrix4f> transforms) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(transforms, "transforms");
        flushPendingState();
        opcode(PREPARE_INSTANCED_BATCH);
        object(batch);
        object(copyTransforms(transforms));
        return this;
    }

    /**
     * 使用最近准备的实例数据绘制一次，不重复上传或推进 ring slot。
     *
     * @param batch      已准备的实例批次
     * @param drawnCount 可选的绘制数量回调
     * @return 当前命令缓冲区
     */
    public CommandBuffer drawPreparedInstancedBatch(InstancedMeshBatch batch,
                                                     IntConsumer drawnCount) {
        Objects.requireNonNull(batch, "batch");
        flushPendingState();
        opcode(DRAW_PREPARED_INSTANCED_BATCH);
        object(batch);
        object(drawnCount);
        return this;
    }

    public CommandBuffer drawPreparedInstancedBatch(InstancedMeshBatch batch) {
        return drawPreparedInstancedBatch(batch, null);
    }

    /**
     * 结束多 pass 实例复用，在最后一次 draw 后插入当前 ring slot 的 GPU fence。
     *
     * @param batch 已准备的实例批次
     * @return 当前命令缓冲区
     */
    public CommandBuffer finishPreparedInstancedBatch(InstancedMeshBatch batch) {
        Objects.requireNonNull(batch, "batch");
        flushPendingState();
        opcode(FINISH_PREPARED_INSTANCED_BATCH);
        object(batch);
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

    /** 设置正面绕序；状态在下一个可观察 GPU 边界前折叠提交。 */
    public CommandBuffer frontFace(com.kaleblangley.haikalat.core.FrontFace winding) {
        pendingState.frontFace(Objects.requireNonNull(winding, "winding").glValue());
        return this;
    }

    /**
     * 设置 scissor test；状态在下一个可观察 GPU 边界前与其他 pending state 一起提交。
     *
     * @param enable 是否启用 scissor test
     * @return 当前命令缓冲区
     */
    public CommandBuffer enableScissor(boolean enable) {
        pendingState.enableScissor(enable);
        return this;
    }

    /**
     * 设置左下角原点、framebuffer 像素单位的 scissor rectangle。
     *
     * @param x      非负左边界
     * @param y      非负下边界
     * @param width  非负宽度，允许为零
     * @param height 非负高度，允许为零
     * @return 当前命令缓冲区
     */
    public CommandBuffer scissor(int x, int y, int width, int height) {
        pendingState.scissor(x, y, width, height);
        return this;
    }

    /**
     * 设置 framebuffer sRGB 编码状态；该状态会与其他 pending pipeline state 一起折叠。
     *
     * @param enable 是否让 OpenGL 对目标 framebuffer 执行 sRGB 编码
     * @return 当前命令缓冲区
     */
    public CommandBuffer enableFramebufferSrgb(boolean enable) {
        pendingState.enableFramebufferSrgb(enable);
        return this;
    }

    public CommandBuffer blendFunc(int sourceRgb, int destinationRgb) {
        pendingState.blendFunc(sourceRgb, destinationRgb);
        return this;
    }

    /**
     * 合并一组 pending state，但不会重排或跨越可观察命令边界。
     *
     * @param blendMode 材质混合模式
     * @param depthTest 是否启用深度测试
     * @return 当前命令缓冲区
     */
    public CommandBuffer materialState(BlendMode blendMode, boolean depthTest) {
        Objects.requireNonNull(blendMode, "blendMode");
        pendingState.materialState(blendMode, depthTest);
        return this;
    }

    /**
     * 记录 clear 边界，确保 depthMask 在深度清除之前生效。
     *
     * @param color 是否清除颜色附件
     * @param depth 是否清除深度附件
     * @return 当前命令缓冲区
     */
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

    public CommandBuffer setUniformVec4(ShaderProgram shader, String name, Vector4f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        return uniformVec4(shader, shader.uniformLocation(name), value.x, value.y, value.z, value.w);
    }

    public CommandBuffer trySetUniformVec4(ShaderProgram shader, String name, Vector4f value) {
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int location = shader.uniformLocationOrMinusOne(name);
        return location < 0 ? this : uniformVec4(shader, location, value.x, value.y, value.z, value.w);
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

    /**
     * 记录 RenderGraph profile 使用的正式 query 开始边界。
     *
     * @param timer GPU 计时器
     * @return 当前命令缓冲区
     */
    public CommandBuffer beginGpuTimer(GpuTimer timer) {
        return beginGpuTimer(timer, 0L);
    }

    /** 记录带提交帧身份的 GPU query 开始边界。 */
    public CommandBuffer beginGpuTimer(GpuTimer timer, long submissionSequence) {
        Objects.requireNonNull(timer, "timer");
        if (submissionSequence < 0L) throw new IllegalArgumentException("submissionSequence must be non-negative");
        flushPendingState();
        opcode(BEGIN_GPU_TIMER);
        longValue(submissionSequence);
        object(timer);
        return this;
    }

    /**
     * 记录 RenderGraph profile 使用的正式 query 结束边界。
     *
     * @param timer GPU 计时器
     * @return 当前命令缓冲区
     */
    public CommandBuffer endGpuTimer(GpuTimer timer) {
        Objects.requireNonNull(timer, "timer");
        flushPendingState();
        opcode(END_GPU_TIMER);
        object(timer);
        return this;
    }

    /** 记录 typed OpenGL debug group；该边界不会改变渲染状态。 */
    public CommandBuffer pushDebugGroup(String label) {
        String safe = Objects.requireNonNull(label, "label").trim();
        if (safe.isEmpty()) throw new IllegalArgumentException("debug group label must not be blank");
        flushPendingState();
        opcode(PUSH_DEBUG_GROUP);
        object(safe);
        return this;
    }

    /** 记录 typed OpenGL debug group 结束边界。 */
    public CommandBuffer popDebugGroup() {
        flushPendingState();
        opcode(POP_DEBUG_GROUP);
        return this;
    }

    /**
     * 记录完整的逃生口屏障：回调前提交 pending state，回调后使持久状态缓存失效，
     * 因为任意 OpenGL 状态都可能已经被外部代码修改。
     *
     * @param action 持有当前 OpenGL context 时执行的外部操作
     * @return 当前命令缓冲区
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

    byte recordedOpcodeAt(int commandIndex) {
        return stream.opcodeAt(commandIndex);
    }

    Object objectPayloadAt(int payloadIndex) {
        return stream.objectAt(payloadIndex);
    }

    /**
     * 按记录顺序执行命令，并在执行前提交最后一组 pending pipeline state。
     *
     * @param cache 跨命令、跨帧复用的 OpenGL 状态缓存
     */
    public void execute(StateCache cache) {
        flushPendingState();
        CommandExecutor.execute(stream, cache);
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

    private CommandBuffer uniformVec4(ShaderProgram shader, int location,
                                      float x, float y, float z, float w) {
        opcode(UNIFORM_VEC4);
        integer(location);
        integer(floatBits(x));
        integer(floatBits(y));
        integer(floatBits(z));
        integer(floatBits(w));
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
