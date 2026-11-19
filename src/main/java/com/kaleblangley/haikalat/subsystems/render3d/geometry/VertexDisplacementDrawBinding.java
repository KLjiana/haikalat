package com.kaleblangley.haikalat.subsystems.render3d.geometry;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Per-object adapter that supplies the local sinusoidal displacement uniforms
 * to every scene vertex shader and snapshots the previous frame's time for
 * velocity/previous-depth consistency.
 *
 * <p>This binding composes with an existing skin/morph draw binding by
 * delegation.  The shader evaluation order is fixed as morph, then vertex
 * displacement, then skinning and the model transform.  The displacement model
 * itself is evaluated in local/rest space, so a skinned character's wave moves
 * with its rest pose rather than with the animated world position.</p>
 *
 * <p>Time is supplied explicitly by the application, normally immediately
 * before {@code RenderPipeline.execute}.  Changing any model parameter other
 * than time invalidates the committed previous deformation, so the first frame
 * after an edit reports velocity/previous depth as invalid instead of mixing
 * two different deformation models.</p>
 */
public final class VertexDisplacementDrawBinding implements SceneDrawBinding, TemporalDrawBinding {
    private final SceneDrawBinding base;
    private VertexDisplacementSettings settings;
    private final Vector3f axis = new Vector3f();
    private final Vector3f waveDirection = new Vector3f();
    private float timeSeconds;
    private float previousTimeSeconds;
    private float pendingTimeSeconds;
    private long settingsRevision;
    private boolean previousValid;
    private boolean pendingValid;

    public VertexDisplacementDrawBinding(VertexDisplacementSettings settings) {
        this(settings, SceneDrawBinding.NONE);
    }

    /**
     * @param settings bounded local displacement model
     * @param base optional existing skin/morph binding to preserve
     */
    public VertexDisplacementDrawBinding(VertexDisplacementSettings settings, SceneDrawBinding base) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (Objects.requireNonNull(base, "base") instanceof VertexDisplacementDrawBinding) {
            throw new IllegalArgumentException("vertex displacement bindings must not be nested");
        }
        this.base = base;
        pack(settings);
    }

    /** Updates the model and the caller-supplied time in seconds. */
    public void update(VertexDisplacementSettings nextSettings, float timeSeconds) {
        if (!Float.isFinite(timeSeconds)) {
            throw new IllegalArgumentException("timeSeconds must be finite");
        }
        VertexDisplacementSettings required = Objects.requireNonNull(nextSettings, "nextSettings");
        if (!required.equals(settings)) {
            settings = required;
            pack(required);
            settingsRevision = Math.incrementExact(settingsRevision);
            // A changed amplitude/frequency/phase/axis/direction describes a
            // different deformation; never blend it with the old previous pose.
            previousValid = false;
            pendingValid = false;
        }
        this.timeSeconds = timeSeconds;
    }

    public VertexDisplacementSettings settings() {
        return settings;
    }

    public float timeSeconds() {
        return timeSeconds;
    }

    public float previousTimeSeconds() {
        return previousTimeSeconds;
    }

    public boolean hasPreviousTime() {
        return previousValid;
    }

    @Override
    public void record(CommandBuffer commands, ShaderProgram shader, int frameIndex, Pass pass) {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(pass, "pass");
        base.record(commands, shader, frameIndex, pass);
    }

    @Override
    public void recordVertexDisplacement(CommandBuffer commands, ShaderProgram shader, Pass pass) {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(pass, "pass");
        if (!settings.active()) {
            commands.trySetUniformInt(shader, "uDisplacementEnabled", 0);
            return;
        }
        commands.trySetUniformInt(shader, "uDisplacementEnabled", 1)
                .trySetUniformFloat(shader, "uDisplacementAmplitude", settings.amplitude())
                .trySetUniformFloat(shader, "uDisplacementFrequency", settings.frequency())
                .trySetUniformFloat(shader, "uDisplacementSpeed", settings.speed())
                .trySetUniformFloat(shader, "uDisplacementPhase", settings.phase())
                .trySetUniformVec3(shader, "uDisplacementAxis", axis)
                .trySetUniformVec3(shader, "uDisplacementWaveDirection", waveDirection)
                .trySetUniformFloat(shader, "uDisplacementTime", timeSeconds)
                // When no previous time was committed the surface pass keeps
                // uPreviousValid at zero, so the value below is ignored.
                .trySetUniformFloat(shader, "uDisplacementPreviousTime",
                        previousValid ? previousTimeSeconds : timeSeconds);
    }

    @Override
    public boolean skinningEnabled() {
        return base.skinningEnabled();
    }

    @Override
    public int morphTargetCount() {
        return base.morphTargetCount();
    }

    @Override
    public boolean deformsVertices() {
        return settings.active() || base.deformsVertices();
    }

    @Override
    public long boundsRevision() {
        return 31L * base.boundsRevision() + settingsRevision;
    }

    @Override
    public float boundsPadding() {
        return Math.max(base.boundsPadding(), settings.maxOffset());
    }

    @Override
    public TemporalDrawBinding temporalBinding() {
        return this;
    }

    @Override
    public boolean previousDeformationAvailable() {
        if (!previousValid) return false;
        TemporalDrawBinding baseTemporal = base.temporalBinding();
        return baseTemporal == null || baseTemporal.previousDeformationAvailable();
    }

    @Override
    public void prepareTemporalFrame() {
        // Commit the current time even when disabled: a later enable then has
        // one valid previous static time, but model parameter changes still
        // invalidate it explicitly.
        pendingTimeSeconds = timeSeconds;
        pendingValid = true;
        TemporalDrawBinding baseTemporal = base.temporalBinding();
        if (baseTemporal != null) baseTemporal.prepareTemporalFrame();
    }

    @Override
    public void prepareTemporalCommit() {
        TemporalDrawBinding baseTemporal = base.temporalBinding();
        if (baseTemporal != null) baseTemporal.prepareTemporalCommit();
    }

    @Override
    public void recordPrevious(CommandBuffer commands, ShaderProgram shader) {
        TemporalDrawBinding baseTemporal = base.temporalBinding();
        if (baseTemporal != null && baseTemporal.previousDeformationAvailable()) {
            baseTemporal.recordPrevious(commands, shader);
        }
    }

    @Override
    public void commitTemporalFrame() {
        if (pendingValid) {
            previousTimeSeconds = pendingTimeSeconds;
            previousValid = true;
            pendingValid = false;
        }
        TemporalDrawBinding baseTemporal = base.temporalBinding();
        if (baseTemporal != null) baseTemporal.commitTemporalFrame();
    }

    @Override
    public void discardTemporalFrame() {
        pendingValid = false;
        TemporalDrawBinding baseTemporal = base.temporalBinding();
        if (baseTemporal != null) baseTemporal.discardTemporalFrame();
    }

    @Override
    public void invalidatePrevious() {
        previousValid = false;
        pendingValid = false;
        TemporalDrawBinding baseTemporal = base.temporalBinding();
        if (baseTemporal != null) baseTemporal.invalidatePrevious();
    }

    private void pack(VertexDisplacementSettings value) {
        value.axisTo(axis);
        value.waveDirectionTo(waveDirection);
    }
}
