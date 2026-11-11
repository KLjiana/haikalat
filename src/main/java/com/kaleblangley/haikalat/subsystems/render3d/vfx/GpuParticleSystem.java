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
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL14.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL42.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;

/**
 * Production GPU owner for the compute-driven particle active set.
 *
 * <p>One instance owns the particle storage buffer, the GPU-owned counter that
 * also is the {@code DrawArraysIndirectCommand}, the compacted active-index
 * buffer, the reset/update/draw programs and an empty VAO. A record performs
 * three GPU steps without a CPU count read:</p>
 *
 * <ol>
 *   <li>a one-invocation dispatch zeroes the active count and re-arms the
 *       indirect command;</li>
 *   <li>a compute dispatch clears slots on replay, spawns the bounded emission
 *       window, integrates life and appends live indices with an atomic
 *       {@code activeCount} increment;</li>
 *   <li>after a shader-storage + command barrier the draw program fetches
 *       {@code activeIndices[gl_VertexID]} and records
 *       {@code glDrawArraysIndirect(GL_POINTS)} from that counter. The
 *       recorder sets {@code GL_FUNC_ADD} explicitly and restores the opaque
 *       blend/depth/cull defaults afterwards, so it does not depend on the
 *       caller's previous blend equation or cull state.</li>
 * </ol>
 *
 * <p>When {@link GpuParticleSortingSettings} is enabled the owner adds a
 * bounded GPU sort between the update and the draw: one preparation dispatch
 * writes {@code (key, tie)} pairs and storage indices for the padded sort
 * capacity, then one dispatch per bitonic compare-exchange step reorders them,
 * and a separate draw program consumes {@code sortedIndices}. The active count,
 * the indirect command and the draw range are unchanged; the CPU still never
 * reads the counter. The enabled path draws with premultiplied alpha-over
 * because only an order-dependent blend can observe the sorted order, while the
 * disabled path records the exact #27 command sequence and additive blend.
 * See {@code docs/planning/v0.28.1-s3-particle-sorting.md}.</p>
 *
 * <p>Empty sets are not special-cased on the CPU: the GPU count is zero and
 * the indirect draw generates no primitives. Capacity changes prepare a
 * replacement candidate through {@link #resized(int)}; the caller publishes it
 * and retires this owner. Call {@link #close()} on the GPU owner thread.</p>
 *
 * <p>Limits: no CPU readback drives the draw, no sorting across owners, no
 * transparency resolve and no depth collision. See
 * {@code docs/planning/v0.28.1-s2-particle-indirect.md} and
 * {@code docs/planning/v0.28.1-s3-particle-sorting.md}.</p>
 */
public final class GpuParticleSystem implements GlResource {
    /** Hard upper bound shared with {@link GpuParticleSettings}. */
    public static final int MAX_PARTICLES = GpuParticleSettings.MAXIMUM_CAPACITY;
    private static final int PARTICLE_BYTES = 8 * Float.BYTES;
    private static final int COUNTER_BYTES = 4 * Integer.BYTES;
    private static final int LOCAL_SIZE = GpuParticleSortPlan.LOCAL_SIZE;

    private final int capacity;
    private final int workGroups;
    private final long particleStorageBytes;
    private final long activeIndexStorageBytes;
    private final long sortKeyStorageBytes;
    private final long sortedIndexStorageBytes;
    private final long storageBytes;
    private final GlBuffer particles;
    private final GlBuffer counters;
    private final GlBuffer activeIndices;
    private final GlBuffer sortKeys;
    private final GlBuffer sortedIndices;
    private final ShaderProgram resetProgram;
    private final ShaderProgram updateProgram;
    private final ShaderProgram drawProgram;
    private final ShaderProgram sortKeyProgram;
    private final ShaderProgram sortStepProgram;
    private final ShaderProgram sortedDrawProgram;
    private final VertexArray vertexArray;
    private final GpuParticleEmission emission;
    private final GpuParticleSortingSettings sorting;
    private final GpuParticleSortPlan sortPlan;
    private final Matrix4f viewProjectionScratch = new Matrix4f();
    private final Matrix4f viewScratch = new Matrix4f();
    private final Vector3f spawnMinimumScratch = new Vector3f();
    private final Vector3f spawnMaximumScratch = new Vector3f();
    private final Vector4f colorScratch = new Vector4f();
    private GpuParticleSettings settings;
    private Statistics statistics;
    private boolean closed;

    public GpuParticleSystem(GpuParticleSettings settings) {
        this(settings, GpuParticleSortingSettings.disabled());
    }

    /**
     * Creates an owner with an explicit sorting generation.
     *
     * <p>The sort bound is fixed for the lifetime of this owner because the
     * scratch buffers are allocated here. Change it through a
     * {@link #resized(int, GpuParticleSortingSettings)} candidate instead of
     * mutating a published owner.</p>
     *
     * @param settings particle configuration
     * @param sorting  sorting configuration; {@link GpuParticleSortingSettings#disabled()}
     *                 selects the exact #27 indirect path
     */
    public GpuParticleSystem(GpuParticleSettings settings, GpuParticleSortingSettings sorting) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sorting = Objects.requireNonNull(sorting, "sorting");
        capacity = settings.capacity();
        if (sorting.enabled() && sorting.maximumSortedParticles() > capacity) {
            throw new IllegalArgumentException("maximumSortedParticles must not exceed capacity "
                    + capacity + ": " + sorting.maximumSortedParticles());
        }
        workGroups = (capacity + LOCAL_SIZE - 1) / LOCAL_SIZE;
        particleStorageBytes = Math.multiplyExact((long) capacity, PARTICLE_BYTES);
        activeIndexStorageBytes = Math.multiplyExact((long) capacity, Integer.BYTES);
        sortPlan = sorting.enabled() ? new GpuParticleSortPlan(sorting.maximumSortedParticles())
                : null;
        sortKeyStorageBytes = sortPlan == null ? 0L
                : Math.multiplyExact((long) sortPlan.sortCapacity(), 2L * Integer.BYTES);
        sortedIndexStorageBytes = sortPlan == null ? 0L
                : Math.multiplyExact((long) sortPlan.sortCapacity(), Integer.BYTES);
        storageBytes = Math.addExact(Math.addExact(Math.addExact(
                Math.addExact(particleStorageBytes, activeIndexStorageBytes), COUNTER_BYTES),
                sortKeyStorageBytes), sortedIndexStorageBytes);

        GlBuffer createdParticles = null;
        GlBuffer createdCounters = null;
        GlBuffer createdActiveIndices = null;
        GlBuffer createdSortKeys = null;
        GlBuffer createdSortedIndices = null;
        ShaderProgram createdReset = null;
        ShaderProgram createdUpdate = null;
        ShaderProgram createdDraw = null;
        ShaderProgram createdSortKey = null;
        ShaderProgram createdSortStep = null;
        ShaderProgram createdSortedDraw = null;
        VertexArray createdVertexArray = null;
        try {
            // Storage starts uninitialized on purpose: the first record always
            // carries the reset step, which writes every slot before any read.
            createdParticles = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                    .allocate(particleStorageBytes);
            createdCounters = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                    .allocate(COUNTER_BYTES);
            createdActiveIndices = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                    .allocate(activeIndexStorageBytes);
            createdReset = ShaderProgram.fromComputeResource(GpuParticleSystem.class,
                    "/shaders/render3d/vfx/gpu-particle-reset.comp");
            createdUpdate = ShaderProgram.fromComputeResource(GpuParticleSystem.class,
                    "/shaders/render3d/vfx/gpu-particle-indirect.comp");
            createdDraw = ShaderProgram.builder()
                    .resource(GpuParticleSystem.class, ShaderStage.VERTEX,
                            "/shaders/render3d/vfx/gpu-particle-indirect.vert")
                    .resource(GpuParticleSystem.class, ShaderStage.GEOMETRY,
                            "/shaders/render3d/vfx/gpu-particle-indirect.geom")
                    .resource(GpuParticleSystem.class, ShaderStage.FRAGMENT,
                            "/shaders/render3d/vfx/gpu-particle-indirect.frag")
                    .link();
            if (sortPlan != null) {
                createdSortKeys = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                        .allocate(sortKeyStorageBytes);
                createdSortedIndices = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW)
                        .allocate(sortedIndexStorageBytes);
                createdSortKey = ShaderProgram.fromComputeResource(GpuParticleSystem.class,
                        "/shaders/render3d/vfx/gpu-particle-sort-key.comp");
                createdSortStep = ShaderProgram.fromComputeResource(GpuParticleSystem.class,
                        "/shaders/render3d/vfx/gpu-particle-sort-step.comp");
                createdSortedDraw = ShaderProgram.builder()
                        .resource(GpuParticleSystem.class, ShaderStage.VERTEX,
                                "/shaders/render3d/vfx/gpu-particle-sorted.vert")
                        .resource(GpuParticleSystem.class, ShaderStage.GEOMETRY,
                                "/shaders/render3d/vfx/gpu-particle-indirect.geom")
                        .resource(GpuParticleSystem.class, ShaderStage.FRAGMENT,
                                "/shaders/render3d/vfx/gpu-particle-indirect.frag")
                        .link();
            }
            createdVertexArray = new VertexArray();
        } catch (RuntimeException | Error failure) {
            closeSuppressing(createdVertexArray, failure);
            closeSuppressing(createdSortedDraw, failure);
            closeSuppressing(createdSortStep, failure);
            closeSuppressing(createdSortKey, failure);
            closeSuppressing(createdDraw, failure);
            closeSuppressing(createdUpdate, failure);
            closeSuppressing(createdReset, failure);
            closeSuppressing(createdSortedIndices, failure);
            closeSuppressing(createdSortKeys, failure);
            closeSuppressing(createdActiveIndices, failure);
            closeSuppressing(createdCounters, failure);
            closeSuppressing(createdParticles, failure);
            throw failure;
        }
        particles = createdParticles;
        counters = createdCounters;
        activeIndices = createdActiveIndices;
        sortKeys = createdSortKeys;
        sortedIndices = createdSortedIndices;
        resetProgram = createdReset;
        updateProgram = createdUpdate;
        drawProgram = createdDraw;
        sortKeyProgram = createdSortKey;
        sortStepProgram = createdSortStep;
        sortedDrawProgram = createdSortedDraw;
        vertexArray = createdVertexArray;
        emission = new GpuParticleEmission(capacity);
        statistics = new Statistics(capacity, workGroups, 0, 0, storageBytes, 0L, 0L,
                sorting.enabled() ? 1 + sortPlan.steps() : 0);
        copySettings();
    }

    public int capacity() {
        return capacity;
    }

    public int workGroups() {
        return workGroups;
    }

    public long storageBytes() {
        return storageBytes;
    }

    /** @return the settings used by the next record (defensive vectors) */
    public GpuParticleSettings settings() {
        ensureOpen();
        return settings;
    }

    /** @return the immutable sorting generation of this owner */
    public GpuParticleSortingSettings sortingSettings() {
        ensureOpen();
        return sorting;
    }

    /**
     * Applies a settings value for future records without reallocating storage.
     *
     * <p>Particles already alive keep their remaining life; new spawns use the
     * new lifetime, speed, gravity, size and colour. Capacity changes must go
     * through {@link #resized(int)} because the particle storage is allocated by
     * the owner generation.</p>
     *
     * @param value bounded settings with the same capacity
     */
    public void updateSettings(GpuParticleSettings value) {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        if (value.capacity() != capacity) {
            throw new IllegalArgumentException("capacity changes require resized(int)");
        }
        settings = value;
        copySettings();
    }

    /** Queues particles for the next record; the queue is bounded by capacity. */
    public void emit(int count) {
        ensureOpen();
        emission.emit(count);
    }

    /** @return particles queued for the next record */
    public int pendingEmission() {
        ensureOpen();
        return emission.pendingEmission();
    }

    /**
     * Makes the next record a deterministic replay start.
     *
     * <p>Queued emission is dropped, the emission cursor and seed frame return
     * to zero, and the update dispatch clears every particle slot before it
     * spawns. Replaying the same emit/record sequence therefore reproduces the
     * same active set and pixels. Sort scratch is not reset: every enabled
     * record rewrites the complete padded scratch before reading it.</p>
     */
    public void reset() {
        ensureOpen();
        emission.reset();
    }

    /**
     * Prepares a replacement candidate with another capacity and the same sort
     * generation.
     *
     * <p>This owner and its live particles are unchanged. The caller publishes
     * the returned candidate only after all GPU resources were created and then
     * closes this owner, matching the render3d candidate/retire contract.</p>
     *
     * @param newCapacity capacity in [1, {@link #MAX_PARTICLES}]
     * @return a new, independent owner
     */
    public GpuParticleSystem resized(int newCapacity) {
        ensureOpen();
        return resized(newCapacity, sorting);
    }

    /**
     * Prepares a replacement candidate with another capacity and sort
     * generation.
     *
     * @param newCapacity capacity in [1, {@link #MAX_PARTICLES}]
     * @param newSorting  sorting generation for the candidate; its bound must
     *                    fit {@code newCapacity}
     * @return a new, independent owner
     */
    public GpuParticleSystem resized(int newCapacity, GpuParticleSortingSettings newSorting) {
        ensureOpen();
        return new GpuParticleSystem(settings.withCapacity(newCapacity), newSorting);
    }

    /**
     * Records reset, compute update, an optional GPU sort, barriers and one
     * indirect point draw without a view matrix.
     *
     * <p>This is the exact #27 signature and remains valid for owners whose
     * sorting is disabled. An enabled owner must use
     * {@link #record(CommandBuffer, float, Matrix4fc, Matrix4fc, float)} so the
     * sort key can be derived from the view.</p>
     *
     * @param commands       target command buffer
     * @param deltaSeconds   finite non-negative frame delta in seconds
     * @param viewProjection camera view-projection for the billboard centres
     * @param viewportAspect positive render-target aspect ratio
     * @return the current statistics snapshot
     */
    public Statistics record(CommandBuffer commands, float deltaSeconds,
                             Matrix4fc viewProjection, float viewportAspect) {
        ensureOpen();
        if (sorting.enabled()) {
            throw new IllegalArgumentException("enabled particle sorting requires the record "
                    + "overload with a view matrix");
        }
        return recordInternal(commands, deltaSeconds, viewProjection, null, viewportAspect);
    }

    /**
     * Records reset, compute update, an optional GPU sort, barriers and one
     * indirect point draw.
     *
     * <p>This method never reads the active count, never maps the buffers and
     * never decides the draw range on the CPU. The recorded draw always
     * consumes the GPU counter, including when it is zero.</p>
     *
     * @param commands       target command buffer
     * @param deltaSeconds   finite non-negative frame delta in seconds
     * @param viewProjection camera view-projection for the billboard centres
     * @param view           camera view matrix used by the sort key; required
     *                       when sorting is enabled and ignored otherwise
     * @param viewportAspect positive render-target aspect ratio
     * @return the current statistics snapshot
     */
    public Statistics record(CommandBuffer commands, float deltaSeconds,
                             Matrix4fc viewProjection, Matrix4fc view, float viewportAspect) {
        ensureOpen();
        Objects.requireNonNull(view, "view");
        return recordInternal(commands, deltaSeconds, viewProjection, view, viewportAspect);
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
        if (closed) {
            return;
        }
        closed = true;
        vertexArray.close();
        if (sortedDrawProgram != null) {
            sortedDrawProgram.close();
        }
        if (sortStepProgram != null) {
            sortStepProgram.close();
        }
        if (sortKeyProgram != null) {
            sortKeyProgram.close();
        }
        drawProgram.close();
        updateProgram.close();
        resetProgram.close();
        if (sortedIndices != null) {
            sortedIndices.close();
        }
        if (sortKeys != null) {
            sortKeys.close();
        }
        activeIndices.close();
        counters.close();
        particles.close();
    }

    private Statistics recordInternal(CommandBuffer commands, float deltaSeconds,
                                      Matrix4fc viewProjection, Matrix4fc view,
                                      float viewportAspect) {
        CommandBuffer cmd = Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(viewProjection, "viewProjection");
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        if (!Float.isFinite(viewportAspect) || viewportAspect <= 0.0f) {
            throw new IllegalArgumentException("viewportAspect must be finite and positive");
        }
        GpuParticleEmission.Step step = emission.beginFrame(deltaSeconds);
        recordStep(cmd, step, deltaSeconds, viewProjection, view, viewportAspect);
        statistics = new Statistics(capacity, workGroups, step.spawnCount(),
                emission.pendingEmission(), storageBytes,
                Math.incrementExact(statistics.framesRecorded()),
                statistics.resetFrames() + (step.clearParticles() ? 1L : 0L),
                sorting.enabled() ? 1 + sortPlan.steps() : 0);
        return statistics;
    }

    private void recordStep(CommandBuffer cmd, GpuParticleEmission.Step step,
                            float deltaSeconds, Matrix4fc viewProjection, Matrix4fc view,
                            float viewportAspect) {
        viewProjectionScratch.set(viewProjection);
        cmd.bindShader(resetProgram)
                .bindStorageBuffer(1, counters, 0L, COUNTER_BYTES)
                .dispatchCompute(1, 1, 1)
                .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                .bindShader(updateProgram)
                .bindStorageBuffer(0, particles, 0L, particleStorageBytes)
                .bindStorageBuffer(1, counters, 0L, COUNTER_BYTES)
                .bindStorageBuffer(2, activeIndices, 0L, activeIndexStorageBytes)
                .setUniformInt(updateProgram, "uCapacity", capacity)
                .setUniformInt(updateProgram, "uEmitStart", step.spawnStart())
                .setUniformInt(updateProgram, "uEmitCount", step.spawnCount())
                .setUniformInt(updateProgram, "uClearParticles", step.clearParticles() ? 1 : 0)
                .setUniformInt(updateProgram, "uFrameIndex", step.frameIndex())
                .setUniformFloat(updateProgram, "uDeltaSeconds", deltaSeconds)
                .setUniformFloat(updateProgram, "uLifetimeSeconds", settings.lifetimeSeconds())
                .setUniformFloat(updateProgram, "uGravity", settings.gravity())
                .setUniformFloat(updateProgram, "uMinimumSpeed", settings.minimumSpeed())
                .setUniformFloat(updateProgram, "uMaximumSpeed", settings.maximumSpeed())
                .setUniformVec3(updateProgram, "uSpawnMin", spawnMinimumScratch)
                .setUniformVec3(updateProgram, "uSpawnMax", spawnMaximumScratch)
                .dispatchCompute(workGroups, 1, 1);
        if (sorting.enabled()) {
            recordSort(cmd, Objects.requireNonNull(view, "view"));
            cmd.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT)
                    .materialState(BlendMode.ALPHA, true)
                    .blendEquation(GL_FUNC_ADD)
                    .blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                    .enableCullFace(false)
                    .bindShader(sortedDrawProgram)
                    .bindStorageBuffer(1, counters, 0L, COUNTER_BYTES)
                    .bindStorageBuffer(3, sortedIndices, 0L, sortedIndexStorageBytes)
                    .bindStorageBuffer(0, particles, 0L, particleStorageBytes)
                    .bindStorageBuffer(2, activeIndices, 0L, activeIndexStorageBytes)
                    .setUniformMat4(sortedDrawProgram, "uViewProjection", viewProjectionScratch)
                    .setUniformInt(sortedDrawProgram, "uCapacity", capacity)
                    .setUniformInt(sortedDrawProgram, "uMaximumSortedParticles",
                            sorting.maximumSortedParticles())
                    .setUniformFloat(sortedDrawProgram, "uViewportAspect", viewportAspect)
                    .setUniformFloat(sortedDrawProgram, "uParticleSize", settings.particleSize())
                    .setUniformVec4(sortedDrawProgram, "uColor", colorScratch)
                    .bindVertexArray(vertexArray.id())
                    .drawArraysIndirect(GL_POINTS, counters, 0L)
                    .materialState(BlendMode.OPAQUE, true)
                    .enableCullFace(true);
            return;
        }
        cmd.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT)
                .materialState(BlendMode.ADDITIVE, true)
                .blendEquation(GL_FUNC_ADD)
                .enableCullFace(false)
                .bindShader(drawProgram)
                .bindStorageBuffer(0, particles, 0L, particleStorageBytes)
                .bindStorageBuffer(2, activeIndices, 0L, activeIndexStorageBytes)
                .setUniformMat4(drawProgram, "uViewProjection", viewProjectionScratch)
                .setUniformInt(drawProgram, "uCapacity", capacity)
                .setUniformFloat(drawProgram, "uViewportAspect", viewportAspect)
                .setUniformFloat(drawProgram, "uParticleSize", settings.particleSize())
                .setUniformVec4(drawProgram, "uColor", colorScratch)
                .bindVertexArray(vertexArray.id())
                .drawArraysIndirect(GL_POINTS, counters, 0L)
                .materialState(BlendMode.OPAQUE, true)
                .enableCullFace(true);
    }

    /**
     * Records the preparation dispatch and every dependent bitonic step.
     *
     * <p>Each step is separated by a shader-storage barrier; the caller adds the
     * final storage + command barrier before the draw. The sort reads the
     * GPU-owned counter but never writes it, so the indirect command and the
     * draw range stay the ones the update dispatch published.</p>
     */
    private void recordSort(CommandBuffer cmd, Matrix4fc view) {
        viewScratch.set(view);
        // The preparation dispatch reads the active list, the counter and the
        // particle positions written by the update dispatch.
        cmd.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                .bindShader(sortKeyProgram)
                .bindStorageBuffer(0, particles, 0L, particleStorageBytes)
                .bindStorageBuffer(1, counters, 0L, COUNTER_BYTES)
                .bindStorageBuffer(2, activeIndices, 0L, activeIndexStorageBytes)
                .bindStorageBuffer(3, sortKeys, 0L, sortKeyStorageBytes)
                .bindStorageBuffer(4, sortedIndices, 0L, sortedIndexStorageBytes)
                .setUniformInt(sortKeyProgram, "uCapacity", capacity)
                .setUniformInt(sortKeyProgram, "uSortCapacity", sortPlan.sortCapacity())
                .setUniformInt(sortKeyProgram, "uMaximumSortedParticles",
                        sorting.maximumSortedParticles())
                .setUniformMat4(sortKeyProgram, "uView", viewScratch)
                .setUniformInt(sortKeyProgram, "uSortKey",
                        sorting.key() == GpuParticleSortingSettings.SortKey.VIEW_DEPTH ? 0 : 1)
                .setUniformInt(sortKeyProgram, "uBackToFront",
                        sorting.direction() == GpuParticleSortingSettings.SortDirection.BACK_TO_FRONT
                                ? 1 : 0)
                .setUniformInt(sortKeyProgram, "uStableTies",
                        sorting.equalKeys() == GpuParticleSortingSettings.EqualKeyPolicy.STABLE
                                ? 1 : 0)
                .dispatchCompute(sortPlan.prepareGroups(), 1, 1);
        if (sortPlan.steps() == 0) {
            return;
        }
        cmd.memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                .bindShader(sortStepProgram)
                .bindStorageBuffer(1, counters, 0L, COUNTER_BYTES)
                .bindStorageBuffer(3, sortKeys, 0L, sortKeyStorageBytes)
                .bindStorageBuffer(4, sortedIndices, 0L, sortedIndexStorageBytes)
                .setUniformInt(sortStepProgram, "uSortCapacity", sortPlan.sortCapacity())
                .setUniformInt(sortStepProgram, "uMaximumSortedParticles",
                        sorting.maximumSortedParticles());
        for (int step = 0; step < sortPlan.steps(); step++) {
            cmd.setUniformInt(sortStepProgram, "uBlockSize", sortPlan.blockSize(step))
                    .setUniformInt(sortStepProgram, "uCompareDistance",
                            sortPlan.compareDistance(step))
                    .dispatchCompute(sortPlan.stepGroups(), 1, 1)
                    .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
    }

    private void copySettings() {
        Vector3f minimum = settings.spawnMinimum();
        Vector3f maximum = settings.spawnMaximum();
        Vector3f color = settings.color();
        spawnMinimumScratch.set(minimum);
        spawnMaximumScratch.set(maximum);
        colorScratch.set(color.x, color.y, color.z, settings.intensity());
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("GPU particle system is closed");
        }
    }

    private static void closeSuppressing(AutoCloseable value, Throwable failure) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Test-only white-box handles for the active-set vs full-range comparison.
     *
     * <p>Package-private on purpose: production consumers drive the owner only
     * through {@link #record}. A GL acceptance test binds an identity index
     * buffer to reproduce the naive full-range draw with the same program and
     * reads the counter buffer as a diagnostic oracle after execution. The sort
     * handles are zero when this owner's sorting is disabled.</p>
     */
    record VerificationHandles(int drawProgram, int particleBuffer, int activeIndexBuffer,
                               int counterBuffer, int vertexArray, int capacity,
                               int sortKeyProgram, int sortStepProgram, int sortedDrawProgram,
                               int sortKeyBuffer, int sortedIndexBuffer, int sortCapacity) {
    }

    VerificationHandles verificationHandles() {
        ensureOpen();
        return new VerificationHandles(drawProgram.id(), particles.id(),
                activeIndices.id(), counters.id(), vertexArray.id(), capacity,
                sortKeyProgram == null ? 0 : sortKeyProgram.id(),
                sortStepProgram == null ? 0 : sortStepProgram.id(),
                sortedDrawProgram == null ? 0 : sortedDrawProgram.id(),
                sortKeys == null ? 0 : sortKeys.id(),
                sortedIndices == null ? 0 : sortedIndices.id(),
                sortPlan == null ? 0 : sortPlan.sortCapacity());
    }

    /**
     * Per-record statistics. The live count is intentionally absent because it
     * only exists on the GPU; {@code requestedEmission} is the CPU queue that
     * was offered to the update dispatch, not a confirmed spawn count.
     * {@code sortDispatches} counts the recorded preparation and compare-exchange
     * dispatches and is zero for the disabled path.
     */
    public record Statistics(int capacity, int workGroups, int requestedEmission,
                             int pendingEmission, long storageBytes, long framesRecorded,
                             long resetFrames, int sortDispatches) {
    }
}
