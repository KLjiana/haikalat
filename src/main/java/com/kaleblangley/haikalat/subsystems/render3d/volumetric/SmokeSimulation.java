package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.lwjgl.opengl.GL46.*;

/**
 * Exclusive GPU owner of the bounded smoke simulation.
 *
 * <p>The owner holds two {@code RGBA16F} 3D textures (density in {@code r},
 * temperature in {@code g}) and the two compute programs that fill and step
 * them. One {@link #step(CommandBuffer, int)} call records
 * {@code steps} fixed {@code dt} dispatches: read the current field, write the
 * next field, barrier and swap. Because the shader has no time, frame or random
 * input and every source rate is a pure function of the step index, the field
 * after N steps is a deterministic function of the settings. {@link #reset}
 * zero-fills both textures; {@link #replay} is reset plus N steps and therefore
 * reproduces the same field. {@link #setPaused} makes {@link #advance} record
 * nothing.</p>
 *
 * <p><b>Ownership and lifecycle.</b> The textures and programs are owned by
 * this instance and are never borrowed from another pass or render graph. The
 * {@link #densityTexture()} accessor is a read-only borrow for the volume
 * renderer and for tests; the caller must not close, resize or write it.
 * Construction allocates every resource before the instance becomes usable,
 * and a failed construction closes its candidates. {@link #reconfigure} builds
 * a complete candidate field pair before publishing it; a failed allocation
 * keeps the previous grid and settings active. {@link #close} is idempotent
 * and aggregates close failures.</p>
 *
 * <p><b>Separation from #38 and fog.</b> This owner is not the volumetric-fog
 * owner and not the volumetric-particle pass: it owns its own grid, compute
 * programs and frames; the fog and particle passes never read these textures
 * and this simulation never samples their resources. The volume consumer
 * ({@link SmokeVolumePass}) integrates this field exactly once.</p>
 *
 * <p>{@link SmokeSimulationSettings#disabled()} is rejected by the
 * constructor: while disabled no simulation or GPU resource exists.</p>
 */
public final class SmokeSimulation implements GlResource {
    /** Stable owner/pass name for render-graph integration. */
    public static final String PASS_NAME = "SmokeSimulation";
    /** Compute-program local size of the step and clear programs. */
    public static final int LOCAL_SIZE = 4;

    private static final String STEP_RESOURCE = "/shaders/render3d/volumetric/smoke-step.comp";
    private static final String CLEAR_RESOURCE = "/shaders/render3d/volumetric/smoke-clear.comp";
    private static final String[] DENSITY_RATE_UNIFORMS = {
            "uDensityRate0", "uDensityRate1", "uDensityRate2", "uDensityRate3"};
    private static final String[] TEMPERATURE_RATE_UNIFORMS = {
            "uTemperatureRate0", "uTemperatureRate1", "uTemperatureRate2", "uTemperatureRate3"};

    private final ShaderProgram stepShader;
    private final ShaderProgram clearShader;
    private final Vector3f gridSizeScratch = new Vector3f();
    private final Vector3f gridMinimumScratch = new Vector3f();
    private final Vector3f cellSizeScratch = new Vector3f();
    private final Vector3f windScratch = new Vector3f();
    private final Vector4f sourceScratch = new Vector4f();

    private SmokeSimulationSettings settings;
    private SmokeTimeline timeline;
    private Texture3D fieldA;
    private Texture3D fieldB;
    private boolean currentIsA = true;
    private boolean closed;

    /**
     * Allocates the complete active simulation.
     *
     * @param settings enabled bounded configuration
     * @throws IllegalArgumentException when disabled or invalid
     */
    public SmokeSimulation(SmokeSimulationSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            throw new IllegalArgumentException("smoke simulation is disabled");
        }
        ShaderProgram createdStep = null;
        ShaderProgram createdClear = null;
        Texture3D createdA = null;
        Texture3D createdB = null;
        try {
            createdStep = ShaderProgram.fromComputeSource(resource(STEP_RESOURCE));
            createdClear = ShaderProgram.fromComputeSource(resource(CLEAR_RESOURCE));
            createdA = createField(settings);
            createdB = createField(settings);
            this.stepShader = createdStep;
            this.clearShader = createdClear;
            this.fieldA = createdA;
            this.fieldB = createdB;
            this.settings = settings;
            this.timeline = new SmokeTimeline(settings);
        } catch (RuntimeException failure) {
            closeSuppressed(createdB, failure);
            closeSuppressed(createdA, failure);
            closeSuppressed(createdClear, failure);
            closeSuppressed(createdStep, failure);
            throw failure;
        }
    }

    /** @return the active immutable settings */
    public SmokeSimulationSettings settings() {
        return settings;
    }

    /** @return grid width in cells */
    public int gridWidth() {
        return settings.gridWidth();
    }

    /** @return grid height in cells */
    public int gridHeight() {
        return settings.gridHeight();
    }

    /** @return grid depth in cells */
    public int gridDepth() {
        return settings.gridDepth();
    }

    /** @return committed fixed steps since construction/reset/reconfigure */
    public long stepCount() {
        return timeline.stepCount();
    }

    /** @return true while {@link #advance} records no steps */
    public boolean paused() {
        return timeline.paused();
    }

    /** Pauses or resumes fixed-step advancement; resuming does not catch up. */
    public void setPaused(boolean value) {
        ensureOpen();
        timeline.setPaused(value);
    }

    /**
     * Zero-fills both ping-pong fields and restarts the deterministic step
     * sequence at step zero. The pause flag is preserved.
     */
    public void reset(CommandBuffer cmd) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        cmd.memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT)
                .bindShader(clearShader);
        gridSizeScratch.set(settings.gridWidth(), settings.gridHeight(), settings.gridDepth());
        cmd.setUniformVec3(clearShader, "uGridSize", gridSizeScratch);
        clearField(cmd, fieldA);
        clearField(cmd, fieldB);
        cmd.memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT
                | GL_TEXTURE_UPDATE_BARRIER_BIT);
        currentIsA = true;
        timeline.reset();
    }

    /**
     * Records {@code steps} fixed simulation steps.
     *
     * @param cmd   GPU-owner command buffer
     * @param steps non-negative number of fixed steps
     */
    public void step(CommandBuffer cmd, int steps) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        if (steps < 0) {
            throw new IllegalArgumentException("step count must be non-negative");
        }
        if (steps == 0) {
            return;
        }
        long baseStep = timeline.stepCount();
        // Make a previous frame's writes visible to the sampler before reading.
        cmd.memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT);
        cmd.bindShader(stepShader);
        gridSizeScratch.set(settings.gridWidth(), settings.gridHeight(), settings.gridDepth());
        gridMinimumScratch.set(settings.gridMinimum());
        cellSizeScratch.set(settings.cellSize());
        windScratch.set(settings.windVelocity());
        cmd.setUniformVec3(stepShader, "uGridSize", gridSizeScratch)
                .setUniformVec3(stepShader, "uGridMin", gridMinimumScratch)
                .setUniformVec3(stepShader, "uCellSize", cellSizeScratch)
                .setUniformVec3(stepShader, "uWind", windScratch)
                .setUniformFloat(stepShader, "uTimeStep", settings.timeStepSeconds())
                .setUniformFloat(stepShader, "uBuoyancy", settings.buoyancy())
                .setUniformFloat(stepShader, "uMaximumTemperature",
                        settings.maximumTemperature())
                .setUniformFloat(stepShader, "uDensityDissipation",
                        settings.densityDissipation())
                .setUniformFloat(stepShader, "uTemperatureDissipation",
                        settings.temperatureDissipation());
        bindSourceConstants(cmd);
        for (int index = 0; index < steps; index++) {
            long stepIndex = baseStep + index;
            bindSourceRates(cmd, stepIndex);
            Texture3D read = currentIsA ? fieldA : fieldB;
            Texture3D write = currentIsA ? fieldB : fieldA;
            cmd.bindTexture3D(0, read)
                    .bindImageTexture(0, write, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                    .dispatchCompute(groups(settings.gridWidth()), groups(settings.gridHeight()),
                            groups(settings.gridDepth()))
                    .memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                            | GL_TEXTURE_FETCH_BARRIER_BIT);
            currentIsA = !currentIsA;
        }
        timeline.commitSteps(steps);
    }

    /**
     * Plans and records whole fixed steps for one frame delta. A paused
     * simulation records nothing. Extra time beyond the per-frame step cap is
     * dropped so a stalled frame cannot create unbounded catch-up work.
     */
    public void advance(CommandBuffer cmd, float frameDeltaSeconds) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        int steps = timeline.planSteps(frameDeltaSeconds);
        if (steps > 0) {
            step(cmd, steps);
        }
    }

    /** Resets the field and replays exactly {@code steps} fixed steps. */
    public void replay(CommandBuffer cmd, int steps) {
        ensureOpen();
        if (steps < 0) {
            throw new IllegalArgumentException("step count must be non-negative");
        }
        reset(cmd);
        step(cmd, steps);
    }

    /**
     * Returns the current density/temperature field as a read-only borrow.
     * Ownership stays with this simulation.
     */
    public Texture3D densityTexture() {
        ensureOpen();
        return currentIsA ? fieldA : fieldB;
    }

    /**
     * Replaces the complete simulation configuration with a candidate-first
     * transaction. The candidate field pair is fully allocated before it is
     * published; a failed allocation leaves the old grid and settings active.
     * A successful reconfigure resets the field and the step sequence.
     */
    public void reconfigure(SmokeSimulationSettings next) {
        ensureOpen();
        Objects.requireNonNull(next, "next");
        if (!next.enabled()) {
            throw new IllegalArgumentException("reconfigure requires enabled settings");
        }
        if (next.equals(settings)) {
            return;
        }
        Texture3D candidateA = createField(next);
        Texture3D candidateB;
        try {
            candidateB = createField(next);
        } catch (RuntimeException failure) {
            closeSuppressed(candidateA, failure);
            throw failure;
        }
        Texture3D previousA = fieldA;
        Texture3D previousB = fieldB;
        fieldA = candidateA;
        fieldB = candidateB;
        currentIsA = true;
        settings = next;
        timeline = new SmokeTimeline(next);
        RuntimeException failure = null;
        failure = close(previousA, failure);
        failure = close(previousB, failure);
        if (failure != null) {
            throw new IllegalStateException(
                    "smoke field reconfigured but retiring the previous field failed", failure);
        }
    }

    /** @return the current ping-pong texture id, or 0 after close */
    @Override
    public int id() {
        return closed ? 0 : densityTexture().id();
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
        Texture3D previousA = fieldA;
        Texture3D previousB = fieldB;
        fieldA = null;
        fieldB = null;
        RuntimeException failure = null;
        failure = close(previousA, failure);
        failure = close(previousB, failure);
        failure = close(clearShader, failure);
        failure = close(stepShader, failure);
        if (failure != null) {
            throw failure;
        }
    }

    /** @return the packaged step compute stage */
    static String stepSource() {
        return resource(STEP_RESOURCE);
    }

    /** @return the packaged clear compute stage */
    static String clearSource() {
        return resource(CLEAR_RESOURCE);
    }

    static String resource(String path) {
        try (var stream = SmokeSimulation.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing smoke simulation shader " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read smoke simulation shader " + path, failure);
        }
    }

    private void bindSourceConstants(CommandBuffer cmd) {
        int count = settings.sources().size();
        for (int index = 0; index < SmokeSimulationSettings.MAXIMUM_SOURCES; index++) {
            if (index < count) {
                SmokeSimulationSettings.Source source = settings.sources().get(index);
                Vector3f center = source.centerInternal();
                sourceScratch.set(center.x, center.y, center.z, source.radius());
            } else {
                sourceScratch.set(0.0f, 0.0f, 0.0f, 0.0f);
            }
            cmd.setUniformVec4(stepShader, "uSource" + index, sourceScratch);
        }
    }

    private void bindSourceRates(CommandBuffer cmd, long stepIndex) {
        int count = settings.sources().size();
        for (int index = 0; index < SmokeSimulationSettings.MAXIMUM_SOURCES; index++) {
            float densityRate = index < count
                    ? timeline.densityRateForStep(index, stepIndex) : 0.0f;
            float temperatureRate = index < count
                    ? timeline.temperatureRateForStep(index, stepIndex) : 0.0f;
            cmd.setUniformFloat(stepShader, DENSITY_RATE_UNIFORMS[index], densityRate)
                    .setUniformFloat(stepShader, TEMPERATURE_RATE_UNIFORMS[index],
                            temperatureRate);
        }
    }

    private void clearField(CommandBuffer cmd, Texture3D field) {
        cmd.bindImageTexture(0, field, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .dispatchCompute(groups(settings.gridWidth()), groups(settings.gridHeight()),
                        groups(settings.gridDepth()));
    }

    private static Texture3D createField(SmokeSimulationSettings settings) {
        Texture3D texture = Texture3D.create(settings.gridWidth(), settings.gridHeight(),
                settings.gridDepth(), RenderFormat.RGBA16F);
        try {
            // Texture storage is undefined on allocation; a zero field is the
            // documented initial state, including reconfigure candidates.
            texture.clear();
            return texture;
        } catch (RuntimeException failure) {
            closeSuppressed(texture, failure);
            throw failure;
        }
    }

    private static int groups(int cells) {
        return (cells + LOCAL_SIZE - 1) / LOCAL_SIZE;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("smoke simulation is closed");
        }
    }

    private static RuntimeException close(GlResource resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void closeSuppressed(GlResource resource, RuntimeException primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
