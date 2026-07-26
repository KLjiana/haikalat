package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;

/** Compute + SSBO GPU 粒子受控实验；运行路径不执行 CPU readback。 */
public final class GpuParticleExperiment implements GlResource {
    public static final int MAX_PARTICLES = 1_048_576;
    private static final int LOCAL_SIZE = 64;
    private static final long PARTICLE_BYTES = 8L * Float.BYTES;

    private final int capacity;
    private final long storageBytes;
    private final GlBuffer particles;
    private final ShaderProgram updateProgram;
    private final ShaderProgram drawProgram;
    private final VertexArray vertexArray;
    private float elapsedSeconds;
    private Statistics statistics;
    private boolean closed;

    public GpuParticleExperiment(int capacity) {
        if (capacity <= 0 || capacity > MAX_PARTICLES) {
            throw new IllegalArgumentException("capacity must be in [1, " + MAX_PARTICLES + "]");
        }
        this.capacity = capacity;
        storageBytes = Math.multiplyExact(PARTICLE_BYTES, capacity);
        GlBuffer createdParticles = null;
        ShaderProgram createdUpdate = null;
        ShaderProgram createdDraw = null;
        VertexArray createdVertexArray = null;
        try {
            ByteBuffer initial = BufferUtils.createByteBuffer(Math.toIntExact(storageBytes));
            initial.limit(initial.capacity());
            createdParticles = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).upload(initial);
            createdUpdate = ShaderProgram.fromComputeResource(GpuParticleExperiment.class,
                    "/shaders/render3d/vfx/gpu-particle.comp");
            createdDraw = ShaderProgram.builder()
                    .resource(GpuParticleExperiment.class, ShaderStage.VERTEX,
                            "/shaders/render3d/vfx/gpu-particle.vert")
                    .resource(GpuParticleExperiment.class, ShaderStage.GEOMETRY,
                            "/shaders/render3d/vfx/gpu-particle.geom")
                    .resource(GpuParticleExperiment.class, ShaderStage.FRAGMENT,
                            "/shaders/render3d/vfx/gpu-particle.frag")
                    .link();
            createdVertexArray = new VertexArray();
        } catch (RuntimeException | Error failure) {
            closeSuppressing(createdVertexArray, failure);
            closeSuppressing(createdDraw, failure);
            closeSuppressing(createdUpdate, failure);
            closeSuppressing(createdParticles, failure);
            throw failure;
        }
        particles = createdParticles;
        updateProgram = createdUpdate;
        drawProgram = createdDraw;
        vertexArray = createdVertexArray;
        statistics = new Statistics(capacity, groups(), storageBytes, 0L);
    }

    public int capacity() {
        return capacity;
    }

    public long storageBytes() {
        return storageBytes;
    }

    /** 记录一次 compute 更新、显式 barrier 和一次点批次绘制。 */
    public Statistics record(CommandBuffer commands, float deltaSeconds,
                             Matrix4fc viewProjection, float viewportAspect) {
        ensureOpen();
        CommandBuffer cmd = Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(viewProjection, "viewProjection");
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        if (!Float.isFinite(viewportAspect) || viewportAspect <= 0.0f) {
            throw new IllegalArgumentException("viewportAspect must be finite and positive");
        }
        elapsedSeconds = Math.min(Float.MAX_VALUE, elapsedSeconds + deltaSeconds);

        cmd.bindShader(updateProgram)
                .bindStorageBuffer(0, particles, 0L, storageBytes)
                .setUniformFloat(updateProgram, "uDeltaSeconds", deltaSeconds)
                .setUniformFloat(updateProgram, "uTimeSeconds", elapsedSeconds)
                .setUniformInt(updateProgram, "uParticleCount", capacity)
                .dispatchCompute(groups(), 1, 1)
                .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                .materialState(BlendMode.ADDITIVE, true)
                .enableCullFace(false)
                .bindShader(drawProgram)
                .bindStorageBuffer(0, particles, 0L, storageBytes)
                .setUniformMat4(drawProgram, "uViewProjection", new Matrix4f(viewProjection))
                .setUniformFloat(drawProgram, "uViewportAspect", viewportAspect)
                .bindVertexArray(vertexArray.id())
                .drawArrays(GL_POINTS, 0, capacity)
                .materialState(BlendMode.OPAQUE, true);
        statistics = new Statistics(capacity, groups(), storageBytes,
                Math.incrementExact(statistics.framesRecorded()));
        return statistics;
    }

    public Statistics statistics() {
        ensureOpen();
        return statistics;
    }

    @Override
    public int id() {
        return particles.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        vertexArray.close();
        drawProgram.close();
        updateProgram.close();
        particles.close();
        closed = true;
    }

    private int groups() {
        return (capacity + LOCAL_SIZE - 1) / LOCAL_SIZE;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("GPU particle experiment is closed");
    }

    private static void closeSuppressing(AutoCloseable value, Throwable failure) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    public record Statistics(int particles, int workGroups, long storageBytes,
                             long framesRecorded) {
    }
}
