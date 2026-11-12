package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Bounded screen-space indirect diffuse for the resolved linear HDR colour.
 *
 * <p>This is the #39 production consumer of the shared surface depth, world
 * normal, roughness and albedo channels. One fixed cosine-weighted hemisphere is
 * traced per pixel with a bounded sample and march budget; hits inside the
 * raster add the neighbouring surface radiance, offscreen misses add the
 * receiver's ambient radiance approximation and the result is composited as one
 * extra albedo-weighted diffuse bounce. No existing ambient is subtracted and no
 * AO is reapplied, so the pass cannot double count the forward direct, IBL
 * ambient or AO terms.</p>
 *
 * <p>The spatial path is stateless: it owns the shader and the screen quad only,
 * and the caller/graph owns the full-size RGBA16F target. There is no history,
 * no private framebuffer and no pixel read-back, so resize is a graph-owned
 * target reallocation and a failed frame cannot leave a partially advanced
 * private state. {@link SsgiSettings#temporalActive()} additionally enables a
 * GI-specific temporal stage: a private staging target receives the current
 * added bounce, a double-buffered history is reprojected through the shared
 * single-sample velocity and rejected per pixel on validity, previous-depth,
 * normal and reprojection mismatch, and the surviving history is clamped to the
 * depth-matched current 3x3 neighbourhood before the exponential blend. The
 * temporal stage is off by default; a zero blend never allocates or samples
 * history and produces the exact pre-existing spatial output.</p>
 *
 * <p>The borrowed environment source is bound per draw and never closed by this
 * pass; a null source falls back to the settings' uniform ambient
 * approximation. {@link #close()} is idempotent and releases exactly the owned
 * objects.</p>
 */
public final class SsgiPass implements GlResource {
    /** Resolved linear HDR radiance texture unit. */
    public static final int COLOR_UNIT = 0;
    /** Single-sample shared device depth texture unit. */
    public static final int DEPTH_UNIT = 1;
    /** Single-sample octahedral world normal texture unit. */
    public static final int NORMAL_UNIT = 2;
    /** Single-sample material roughness texture unit. */
    public static final int ROUGHNESS_UNIT = 3;
    /** Single-sample material albedo texture unit. */
    public static final int ALBEDO_UNIT = 4;
    /** Borrowed irradiance environment cubemap unit. */
    public static final int IRRADIANCE_UNIT = 5;

    private static final int TEMPORAL_SPATIAL_GI_UNIT = 0;
    private static final int TEMPORAL_COLOR_UNIT = 1;
    private static final int TEMPORAL_DEPTH_UNIT = 2;
    private static final int TEMPORAL_NORMAL_UNIT = 3;
    private static final int TEMPORAL_VELOCITY_UNIT = 4;
    private static final int TEMPORAL_PREVIOUS_DEPTH_UNIT = 5;
    private static final int TEMPORAL_VALIDITY_UNIT = 6;
    private static final int TEMPORAL_HISTORY_COLOR_UNIT = 7;
    private static final int TEMPORAL_HISTORY_STATE_UNIT = 8;

    static final float CAMERA_CUT_POSITION_DISTANCE_SQUARED = 16.0f;
    static final float CAMERA_CUT_FORWARD_DOT = 0.8f;
    static final float CAMERA_CUT_DEPTH_RELATIVE_TOLERANCE = 1.0e-3f;
    private static final int ROTATION_SEQUENCE_LENGTH = 64;

    private final ShaderProgram program;
    private final ShaderProgram temporalProgram;
    private final ScreenQuad quad;
    private final SsgiSettings settings;
    private final SsgiTemporalStorage temporalStorage;
    private Matrix4f previousInverseView;
    private Matrix4f previousInverseProjection;
    private Matrix4f pendingInverseView;
    private boolean temporalRecorded;
    private Matrix4f pendingInverseProjection;
    private int rotationIndex;
    private boolean closed;

    /**
     * Creates a spatial-only pass. A request with a positive
     * {@link SsgiSettings#temporalBlend()} needs the extent overload so the
     * private staging target and history can be allocated.
     */
    public SsgiPass(SsgiSettings settings) {
        this(settings, 0, 0);
    }

    /**
     * Creates the pass and, when temporal accumulation is requested, its
     * private staging target and double-buffered history.
     */
    public SsgiPass(SsgiSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("SsgiPass requires an active SSGI request");
        }
        boolean temporal = settings.temporalActive();
        if (temporal && (width <= 0 || height <= 0)) {
            throw new IllegalArgumentException(
                    "temporal SSGI requires an explicit positive viewport extent");
        }
        ShaderProgram createdProgram = null;
        ShaderProgram createdTemporalProgram = null;
        ScreenQuad createdQuad = null;
        SsgiTemporalStorage createdStorage = null;
        try {
            createdProgram = ShaderProgram.fromResource(SsgiPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/ssgi.frag");
            createdQuad = new ScreenQuad();
            if (temporal) {
                createdTemporalProgram = ShaderProgram.fromResource(SsgiPass.class,
                        "/shaders/postprocess/screen-quad.vert",
                        "/shaders/postprocess/ssgi-temporal.frag");
                createdStorage = new SsgiTemporalStorage(width, height);
            }
        } catch (RuntimeException | Error failure) {
            closeSuppressed(createdStorage, failure);
            closeSuppressed(createdTemporalProgram, failure);
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        temporalProgram = createdTemporalProgram;
        quad = createdQuad;
        temporalStorage = createdStorage;
    }

    public SsgiSettings settings() {
        return settings;
    }

    /** @return true when this pass owns and commits a GI temporal history */
    public boolean temporalActive() {
        return temporalStorage != null;
    }

    /** @return true when a previous successful frame can be reprojected */
    public boolean historyValid() {
        return temporalStorage != null && temporalStorage.valid()
                && previousInverseView != null && previousInverseProjection != null;
    }

    /** @return the number of retained samples, saturated at the settings cap */
    public int historyAcceptedFrames() {
        return temporalStorage == null ? 0 : temporalStorage.acceptedFrames();
    }

    /** @return true when a resize to the extent can retain this pass/history */
    public boolean compatibleExtent(int width, int height) {
        return temporalStorage == null
                || temporalStorage.width() == width && temporalStorage.height() == height;
    }

    /**
     * Records the pure spatial pass into the currently bound target.
     *
     * <p>This is the pre-existing stateless entry point. A temporal request must
     * use {@link #recordTemporal(CommandBuffer, TemporalInputs)} so the shared
     * velocity, previous-depth and validity channels are never silently
     * omitted.</p>
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int depthTexture, int normalTexture,
                                                 int roughnessTexture, int albedoTexture,
                                                 Matrix4f inverseProjection,
                                                 Matrix4f projection, Matrix4f view,
                                                 SsgiEnvironmentSource environment,
                                                 int width, int height) {
        ensureOpen();
        if (temporalActive()) {
            throw new IllegalStateException(
                    "temporal SSGI requires the shared velocity, previous-depth and validity "
                            + "inputs");
        }
        return recordSpatial(cmd, colorTexture, depthTexture, normalTexture, roughnessTexture,
                albedoTexture, inverseProjection, projection, view, environment, width, height);
    }

    /**
     * Starts the temporal transaction for this frame.
     */
    public void beginFrame() {
        ensureOpen();
        if (temporalStorage == null) return;
        temporalStorage.prepareFrame();
        temporalRecorded = false;
        pendingInverseView = null;
        pendingInverseProjection = null;
    }

    /**
     * Records the temporal stage into the pass-owned history write slot.
     *
     * <p>The caller must execute the command buffer and then commit
     * ({@link #frameSucceeded()}) or fail ({@link #frameFailed()}) the frame. The
     * output colour is available through {@link #writeColorTexture()} until the
     * frame commits.</p>
     */
    public CommandBuffer recordTemporal(CommandBuffer cmd, TemporalInputs inputs) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inputs, "inputs");
        if (temporalStorage == null) {
            throw new IllegalStateException("temporal SSGI is not active");
        }
        if (!temporalStorage.prepared()) {
            throw new IllegalStateException(
                    "temporal SSGI requires beginFrame() before recordTemporal()");
        }
        if (inputs.width() != temporalStorage.width()
                || inputs.height() != temporalStorage.height()) {
            throw new IllegalArgumentException(
                    "temporal SSGI extent differs from the allocated history");
        }
        Matrix4f inverseView = new Matrix4f(inputs.view()).invert();
        if (temporalStorage.valid() && previousInverseView != null
                && previousInverseProjection != null
                && looksLikeCameraCut(previousInverseView, previousInverseProjection,
                        inverseView, inputs.inverseProjection())) {
            temporalStorage.invalidateForCut();
            rotationIndex = 0;
            previousInverseView = null;
            previousInverseProjection = null;
        }

        recordSpatial(cmd, inputs.colorTexture(), inputs.depthTexture(),
                inputs.normalTexture(), inputs.roughnessTexture(), inputs.albedoTexture(),
                inputs.inverseProjection(), inputs.projection(), inputs.view(),
                inputs.environment(), inputs.width(), inputs.height(),
                temporalStorage.spatialFramebuffer(), frameRotation(), true);

        int historyValid = temporalStorage.valid() ? 1 : 0;
        float historyWeight = settings.historyWeightFor(
                temporalStorage.acceptedFrames() + 1);
        var target = temporalStorage.writeFramebuffer();
        cmd.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(temporalProgram)
                .bindTexture(TEMPORAL_SPATIAL_GI_UNIT,
                        temporalStorage.spatialFramebuffer().colorAttachment(0))
                .bindTexture(TEMPORAL_COLOR_UNIT, inputs.colorTexture())
                .bindTexture(TEMPORAL_DEPTH_UNIT, inputs.depthTexture())
                .bindTexture(TEMPORAL_NORMAL_UNIT, inputs.normalTexture())
                .bindTexture(TEMPORAL_VELOCITY_UNIT, inputs.velocityTexture())
                .bindTexture(TEMPORAL_PREVIOUS_DEPTH_UNIT, inputs.previousDepthTexture())
                .bindTexture(TEMPORAL_VALIDITY_UNIT, inputs.validityTexture())
                .bindTexture(TEMPORAL_HISTORY_COLOR_UNIT,
                        temporalStorage.readHistoryColorTexture())
                .bindTexture(TEMPORAL_HISTORY_STATE_UNIT, temporalStorage.readStateTexture())
                .setUniformInt(temporalProgram, "uSpatialGi", TEMPORAL_SPATIAL_GI_UNIT)
                .setUniformInt(temporalProgram, "uColor", TEMPORAL_COLOR_UNIT)
                .setUniformInt(temporalProgram, "uDepth", TEMPORAL_DEPTH_UNIT)
                .setUniformInt(temporalProgram, "uNormal", TEMPORAL_NORMAL_UNIT)
                .setUniformInt(temporalProgram, "uVelocity", TEMPORAL_VELOCITY_UNIT)
                .setUniformInt(temporalProgram, "uPreviousDepth", TEMPORAL_PREVIOUS_DEPTH_UNIT)
                .setUniformInt(temporalProgram, "uValidity", TEMPORAL_VALIDITY_UNIT)
                .setUniformInt(temporalProgram, "uHistoryColor", TEMPORAL_HISTORY_COLOR_UNIT)
                .setUniformInt(temporalProgram, "uHistoryState", TEMPORAL_HISTORY_STATE_UNIT)
                .setUniformVec2(temporalProgram, "uViewportSize", target.width(), target.height())
                .setUniformMat4(temporalProgram, "uInverseProjection",
                        inputs.inverseProjection())
                .setUniformInt(temporalProgram, "uHistoryValid", historyValid)
                .setUniformFloat(temporalProgram, "uHistoryWeight", historyWeight)
                .setUniformFloat(temporalProgram, "uDepthRejection",
                        settings.temporalDepthRejection())
                .setUniformFloat(temporalProgram, "uNormalRejection",
                        settings.temporalNormalRejection())
                .setUniformFloat(temporalProgram, "uVelocityRejection",
                        settings.temporalVelocityRejection())
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);

        pendingInverseView = inverseView;
        pendingInverseProjection = new Matrix4f(inputs.inverseProjection());
        temporalRecorded = true;
        return cmd;
    }

    /** Commits this frame's history write slot and stores its camera pose. */
    public void frameSucceeded() {
        if (temporalStorage == null) return;
        if (!temporalRecorded) {
            // beginFrame() without recordTemporal() must not publish an
            // unwritten write slot as valid history.
            pendingInverseView = null;
            pendingInverseProjection = null;
            temporalStorage.discardFrame();
            return;
        }
        temporalRecorded = false;
        temporalStorage.commitSuccessfulFrame();
        if (pendingInverseView != null && pendingInverseProjection != null) {
            previousInverseView = pendingInverseView;
            previousInverseProjection = pendingInverseProjection;
        }
        pendingInverseView = null;
        pendingInverseProjection = null;
        rotationIndex = (rotationIndex + 1) % ROTATION_SEQUENCE_LENGTH;
    }

    /** Discards the frame candidate; the previous read slot remains active. */
    public void frameFailed() {
        if (temporalStorage == null) return;
        temporalRecorded = false;
        pendingInverseView = null;
        pendingInverseProjection = null;
        temporalStorage.discardFrame();
    }

    /**
     * Stops trusting the previous history, for example after an explicit
     * invalidation, a camera cut or a resize.
     */
    public void invalidate() {
        ensureOpen();
        if (temporalStorage == null) return;
        temporalStorage.invalidate();
        previousInverseView = null;
        previousInverseProjection = null;
        pendingInverseView = null;
        pendingInverseProjection = null;
        rotationIndex = 0;
    }

    /** @return this frame's candidate target; valid until the next beginFrame */
    public Framebuffer writeFramebuffer() {
        ensureOpen();
        if (temporalStorage == null) {
            throw new IllegalStateException("temporal SSGI is not active");
        }
        return temporalStorage.writeFramebuffer();
    }

    /** @return this frame's candidate colour for consumers in the same graph */
    public int writeColorTexture() {
        return writeFramebuffer().colorAttachment(0);
    }

    /** @return the committed colour produced by the last successful frame */
    public Framebuffer readFramebuffer() {
        ensureOpen();
        if (temporalStorage == null) {
            throw new IllegalStateException("temporal SSGI is not active");
        }
        return temporalStorage.readFramebuffer();
    }

    public ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        return new ResizeCandidate(this, temporalStorage == null
                ? null : temporalStorage.prepareResize(width, height));
    }

    public void commitResize(ResizeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    public void resize(int width, int height) {
        ResizeCandidate candidate = prepareResize(width, height);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    public static final class ResizeCandidate implements AutoCloseable {
        private final SsgiPass owner;
        private final SsgiTemporalStorage.ResizeCandidate storageCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(SsgiPass owner,
                                SsgiTemporalStorage.ResizeCandidate storageCandidate) {
            this.owner = owner;
            this.storageCandidate = storageCandidate;
        }

        private void commitInto(SsgiPass expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "temporal SSGI resize candidate belongs to another pass");
            }
            if (closed) {
                throw new IllegalStateException("temporal SSGI resize candidate is closed");
            }
            if (committed) {
                throw new IllegalStateException(
                        "temporal SSGI resize candidate already committed");
            }
            if (storageCandidate != null) {
                owner.temporalStorage.commitResize(storageCandidate);
            }
            if (owner.temporalStorage != null) {
                // Any resize boundary invalidates the retained GI history,
                // including a same-extent candidate that only refreshed the
                // camera pose state.
                owner.temporalStorage.invalidate();
            }
            owner.previousInverseView = null;
            owner.previousInverseProjection = null;
            owner.pendingInverseView = null;
            owner.pendingInverseProjection = null;
            owner.rotationIndex = 0;
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (storageCandidate != null) storageCandidate.close();
        }
    }

    @Override
    public int id() {
        return program.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        if (temporalStorage != null) {
            try {
                temporalStorage.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
        if (temporalProgram != null) {
            try {
                temporalProgram.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        try {
            quad.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            program.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private CommandBuffer recordSpatial(CommandBuffer cmd, int colorTexture, int depthTexture,
                                        int normalTexture, int roughnessTexture,
                                        int albedoTexture, Matrix4f inverseProjection,
                                        Matrix4f projection, Matrix4f view,
                                        SsgiEnvironmentSource environment,
                                        int width, int height) {
        return recordSpatial(cmd, colorTexture, depthTexture, normalTexture, roughnessTexture,
                albedoTexture, inverseProjection, projection, view, environment, width, height,
                null, 0.0f, false);
    }

    /**
     * Records the spatial draw either into the currently bound graph target or
     * into the pass-owned staging target used by the temporal stage.
     */
    private CommandBuffer recordSpatial(CommandBuffer cmd, int colorTexture, int depthTexture,
                                        int normalTexture, int roughnessTexture,
                                        int albedoTexture, Matrix4f inverseProjection,
                                        Matrix4f projection, Matrix4f view,
                                        SsgiEnvironmentSource environment,
                                        int width, int height,
                                        Framebuffer target,
                                        float frameRotation, boolean temporalMode) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inverseProjection, "inverseProjection");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(view, "view");
        if (colorTexture <= 0 || depthTexture <= 0 || normalTexture <= 0
                || roughnessTexture <= 0 || albedoTexture <= 0) {
            throw new IllegalArgumentException(
                    "screen-space indirect diffuse requires colour, depth, normal, roughness and "
                            + "albedo textures");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "screen-space indirect-diffuse viewport must be positive");
        }
        if (target != null) {
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height());
        }
        cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(COLOR_UNIT, colorTexture)
                .bindTexture(DEPTH_UNIT, depthTexture)
                .bindTexture(NORMAL_UNIT, normalTexture)
                .bindTexture(ROUGHNESS_UNIT, roughnessTexture)
                .bindTexture(ALBEDO_UNIT, albedoTexture)
                .setUniformInt(program, "uColor", COLOR_UNIT)
                .setUniformInt(program, "uDepth", DEPTH_UNIT)
                .setUniformInt(program, "uNormal", NORMAL_UNIT)
                .setUniformInt(program, "uRoughness", ROUGHNESS_UNIT)
                .setUniformInt(program, "uAlbedo", ALBEDO_UNIT)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformMat4(program, "uInverseProjection", inverseProjection)
                .setUniformMat4(program, "uProjection", projection)
                .setUniformMat4(program, "uView", view)
                .setUniformInt(program, "uSampleCount", settings.sampleCount())
                .setUniformInt(program, "uStepCount", settings.stepCount())
                .setUniformFloat(program, "uMaxDistance", settings.maxDistanceMetres())
                .setUniformFloat(program, "uThickness", settings.thicknessMetres())
                .setUniformFloat(program, "uStrength", settings.strength())
                .setUniformFloat(program, "uMinimumSampleRoughness",
                        settings.minimumSampleRoughness())
                .setUniformFloat(program, "uMaximumOffscreenFraction",
                        settings.maximumOffscreenFraction())
                .setUniformFloat(program, "uEdgeFadePixels", settings.edgeFadePixels())
                .setUniformFloat(program, "uFrameRotation", frameRotation)
                .setUniformInt(program, "uTemporalMode", temporalMode ? 1 : 0)
                .setUniformVec3(program, "uAmbientFallback",
                        new Vector3f(settings.ambientFallbackRed(),
                                settings.ambientFallbackGreen(),
                                settings.ambientFallbackBlue()));
        boolean environmentAvailable = environment != null
                && environment.bindIrradiance(cmd, IRRADIANCE_UNIT);
        cmd.setUniformInt(program, "uIrradiance", IRRADIANCE_UNIT)
                .setUniformInt(program, "uEnvironmentAvailable",
                        environmentAvailable ? 1 : 0)
                .setUniformFloat(program, "uEnvironmentIntensity",
                        environmentAvailable ? environment.intensity() : 0.0f)
                .setUniformFloat(program, "uEnvironmentRotation",
                        environmentAvailable ? environment.rotationRadians() : 0.0f);
        return cmd.bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    private float frameRotation() {
        return (float) ((rotationIndex * (double) 0.61803398875) % 1.0);
    }

    /**
     * Conservative camera-cut predicate for the temporal history.
     *
     * <p>A cut is a camera translation beyond four metres, a forward-vector
     * turn whose dot drops below 0.8, or a projection whose reconstructed
     * view-depth mapping changed. Jitter only translates clip x/y, so the depth
     * mapping comparison is insensitive to the per-frame TAA jitter.</p>
     */
    static boolean looksLikeCameraCut(Matrix4fc previousInverseView,
                                      Matrix4fc previousInverseProjection,
                                      Matrix4fc currentInverseView,
                                      Matrix4fc currentInverseProjection) {
        if (!finite(previousInverseView) || !finite(previousInverseProjection)
                || !finite(currentInverseView) || !finite(currentInverseProjection)) {
            return true;
        }
        float dx = previousInverseView.m30() - currentInverseView.m30();
        float dy = previousInverseView.m31() - currentInverseView.m31();
        float dz = previousInverseView.m32() - currentInverseView.m32();
        if (dx * dx + dy * dy + dz * dz > CAMERA_CUT_POSITION_DISTANCE_SQUARED) {
            return true;
        }
        Vector3f previousForward = new Vector3f(-previousInverseView.m20(),
                -previousInverseView.m21(), -previousInverseView.m22()).normalize();
        Vector3f currentForward = new Vector3f(-currentInverseView.m20(),
                -currentInverseView.m21(), -currentInverseView.m22()).normalize();
        if (previousForward.dot(currentForward) < CAMERA_CUT_FORWARD_DOT) {
            return true;
        }
        for (float deviceDepth : new float[]{0.2f, 0.5f, 0.8f}) {
            float previousDepth = reconstructedDepth(previousInverseProjection, deviceDepth);
            float currentDepth = reconstructedDepth(currentInverseProjection, deviceDepth);
            if (!Float.isFinite(previousDepth) || !Float.isFinite(currentDepth)) {
                return true;
            }
            float scale = Math.max(1.0f,
                    Math.max(Math.abs(previousDepth), Math.abs(currentDepth)));
            if (Math.abs(previousDepth - currentDepth)
                    > CAMERA_CUT_DEPTH_RELATIVE_TOLERANCE * scale) {
                return true;
            }
        }
        // The FOV/aspect linear part is invariant to the clip-space x/y jitter,
        // which only changes the inverse matrix's translation column.
        return differs(previousInverseProjection.m00(), currentInverseProjection.m00())
                || differs(previousInverseProjection.m01(), currentInverseProjection.m01())
                || differs(previousInverseProjection.m10(), currentInverseProjection.m10())
                || differs(previousInverseProjection.m11(), currentInverseProjection.m11());
    }

    private static boolean differs(float previous, float current) {
        if (!Float.isFinite(previous) || !Float.isFinite(current)) {
            return true;
        }
        float scale = Math.max(1.0f, Math.max(Math.abs(previous), Math.abs(current)));
        return Math.abs(previous - current) > CAMERA_CUT_DEPTH_RELATIVE_TOLERANCE * scale;
    }

    private static float reconstructedDepth(Matrix4fc inverseProjection, float deviceDepth) {
        float ndc = deviceDepth * 2.0f - 1.0f;
        float z = inverseProjection.m22() * ndc + inverseProjection.m32();
        float w = inverseProjection.m23() * ndc + inverseProjection.m33();
        if (Math.abs(w) <= 1.0e-6f) return Float.NaN;
        return -z / w;
    }

    private static boolean finite(Matrix4fc matrix) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                if (!Float.isFinite(matrix.get(column, row))) return false;
            }
        }
        return true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("SsgiPass is closed");
    }

    private static void closeSuppressed(AutoCloseable resource, Throwable primary) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    /**
     * Structured inputs for one temporal SSGI resolve.
     *
     * <p>All textures are borrowed for the duration of the recorded frame. The
     * velocity, previous-depth and validity channels are the shared
     * single-sample surface outputs used by TAA/motion blur; they are required
     * because the temporal stage must never guess a disocclusion without them.
     * The matrices must describe the same raster projection the depth was
     * produced with; the pass stores the inverse view/inverse projection of the
     * last successful frame for its own camera-cut check.</p>
     */
    public record TemporalInputs(
            int colorTexture,
            int depthTexture,
            int normalTexture,
            int roughnessTexture,
            int albedoTexture,
            int velocityTexture,
            int previousDepthTexture,
            int validityTexture,
            Matrix4f inverseProjection,
            Matrix4f projection,
            Matrix4f view,
            SsgiEnvironmentSource environment,
            int width,
            int height
    ) {
        public TemporalInputs {
            if (colorTexture <= 0 || depthTexture <= 0 || normalTexture <= 0
                    || roughnessTexture <= 0 || albedoTexture <= 0) {
                throw new IllegalArgumentException(
                        "temporal SSGI requires colour, depth, normal, roughness and albedo "
                                + "textures");
            }
            if (velocityTexture <= 0 || previousDepthTexture <= 0 || validityTexture <= 0) {
                throw new IllegalArgumentException(
                        "temporal SSGI requires the shared velocity, previous-depth and "
                                + "validity textures");
            }
            inverseProjection = new Matrix4f(
                    Objects.requireNonNull(inverseProjection, "inverseProjection"));
            projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
            view = new Matrix4f(Objects.requireNonNull(view, "view"));
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("temporal SSGI extent must be positive");
            }
        }
    }
}
