package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Internal GTAO graph and shader owner for one pipeline generation. */
final class GtaoPasses implements AutoCloseable {
    private final GtaoSettings settings;
    private final ShaderProgram estimateProgram;
    private final ShaderProgram temporalProgram;
    private final ShaderProgram denoiseProgram;
    private final ShaderProgram upsampleProgram;
    private final ScreenQuad quad;
    private final GtaoHistory history;
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f previousInverseProjection = new Matrix4f();
    private final Matrix4f pendingInverseProjection = new Matrix4f();
    private final Matrix4f currentViewProjection = new Matrix4f();
    private final Matrix4f previousViewProjection = new Matrix4f();
    private final Matrix4f pendingViewProjection = new Matrix4f();
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameView = new Matrix4f();
    private final float[] matrixDeltaLeft = new float[16];
    private final float[] matrixDeltaRight = new float[16];
    private final Matrix4f uploadedInverseProjection = new Matrix4f();
    private final Matrix4f uploadedInverseViewProjection = new Matrix4f();
    private final Matrix4f uploadedPreviousInverseProjection = new Matrix4f();
    private final Matrix4f uploadedPreviousViewProjection = new Matrix4f();
    private boolean previousCameraValid;
    private boolean pendingFrame;
    private boolean uniformsInitialized;
    private boolean projectionUniformDirty;
    private boolean viewUniformDirty;
    private boolean previousProjectionUniformDirty;
    private boolean previousViewUniformDirty;
    private boolean extentUniformDirty;
    private boolean phaseUniformDirty;
    private boolean nearFarUniformDirty;
    private boolean historyUniformDirty;
    private float uploadedProjectionScaleY;
    private float uploadedNearPlane;
    private float uploadedFarPlane;
    private float uploadedSamplePhase;
    private float uploadedHistoryWeight;
    private int uploadedHistoryValid = -1;
    private int uploadedWidth = -1;
    private int uploadedHeight = -1;
    private float frameHistoryWeight;
    private int frameHistoryValid;
    private boolean closed;
    private int depthPrepassDraws;
    private float nearPlane = CameraProjection.NEAR_PLANE;
    private float farPlane = CameraProjection.FAR_PLANE;
    private float projectionScaleY = 1.0f;
    private int width;
    private int height;
    private float samplePhase;

    GtaoPasses(GtaoSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.width = positive(width, "width");
        this.height = positive(height, "height");
        ShaderProgram estimate = null;
        ShaderProgram temporal = null;
        ShaderProgram denoise = null;
        ShaderProgram upsample = null;
        ScreenQuad screenQuad = null;
        GtaoHistory temporalHistory = null;
        try {
            estimate = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-estimate.frag");
            temporal = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-temporal.frag");
            denoise = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-denoise.frag");
            upsample = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-upsample.frag");
            screenQuad = new ScreenQuad();
            temporalHistory = settings.temporal()
                    ? new GtaoHistory(half(this.width), half(this.height)) : null;
            estimateProgram = estimate;
            temporalProgram = temporal;
            denoiseProgram = denoise;
            upsampleProgram = upsample;
            quad = screenQuad;
            history = temporalHistory;
            configureStaticUniforms();
        } catch (RuntimeException failure) {
            closeCollecting(temporalHistory, failure);
            closeCollecting(screenQuad, failure);
            closeCollecting(upsample, failure);
            closeCollecting(denoise, failure);
            closeCollecting(temporal, failure);
            closeCollecting(estimate, failure);
            throw failure;
        }
    }

    void addPreGeometryPasses(RenderGraph graph, RenderGraph.PassExecutor depthExecutor) {
        ensureOpen();
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(depthExecutor, "depthExecutor");
        graph.addPass(PostProcessTargets.GTAO_DEPTH_PREPASS)
                .createDepthTexture(PostProcessTargets.GTAO_DEPTH)
                .clearDepthOnly()
                .execute(depthExecutor);

        graph.addPass(PostProcessTargets.GTAO_ESTIMATE_PASS)
                .createColor(PostProcessTargets.GTAO_RAW, RenderFormat.R8)
                .relativeSizeCeil(0.5f)
                .noClear()
                .dependsOn(PostProcessTargets.GTAO_DEPTH_PREPASS)
                .execute(this::recordEstimate);

        graph.addPass(PostProcessTargets.GTAO_TEMPORAL_PASS)
                .createColor(PostProcessTargets.GTAO_TEMPORAL, RenderFormat.RG16F)
                .relativeSizeCeil(0.5f)
                .noClear()
                .dependsOn(PostProcessTargets.GTAO_ESTIMATE_PASS)
                .execute(this::recordTemporal);

        graph.addPass(PostProcessTargets.GTAO_DENOISE_HORIZONTAL_PASS)
                // The original implementation used two separable passes.  A
                // single 3x3 bilateral pass has the same bounded neighborhood
                // contract while removing one fullscreen draw/FBO/timer from
                // the stable frame path.
                .createColor(PostProcessTargets.GTAO_DENOISE_B, RenderFormat.R8)
                .relativeSizeCeil(0.5f)
                .noClear()
                .dependsOn(PostProcessTargets.GTAO_TEMPORAL_PASS)
                .execute(this::recordDenoise);

        graph.addPass(PostProcessTargets.GTAO_UPSAMPLE_PASS)
                .createColor(PostProcessTargets.GTAO_FINAL, RenderFormat.R8)
                .noClear()
                .dependsOn(PostProcessTargets.GTAO_DENOISE_HORIZONTAL_PASS)
                .execute(this::recordUpsample);
    }

    void beginFrame(Camera camera, int width, int height, int frameIndex,
                    com.kaleblangley.haikalat.core.AntiAliasingMode antiAliasingMode) {
        ensureOpen();
        Objects.requireNonNull(camera, "camera");
        this.width = positive(width, "width");
        this.height = positive(height, "height");
        CameraProjection.stable(camera, this.width, this.height, frameProjection);
        CameraUniforms.applyTemporalJitter(frameProjection, this.width, this.height,
                Objects.requireNonNull(antiAliasingMode, "antiAliasingMode"), frameIndex);
        camera.getViewMatrix(frameView);
        currentViewProjection.set(frameProjection).mul(frameView);
        if (!currentViewProjection.isFinite()
                || Math.abs(currentViewProjection.determinant()) <= 1.0e-8f) {
            throw new IllegalArgumentException("GTAO camera view-projection must be invertible");
        }
        inverseViewProjection.set(currentViewProjection).invert();
        if (!inverseViewProjection.isFinite()) {
            throw new IllegalArgumentException("GTAO inverse camera matrix must be finite");
        }
        inverseProjection.set(frameProjection).invert();
        if (!inverseProjection.isFinite()) {
            throw new IllegalArgumentException("GTAO inverse projection matrix must be finite");
        }
        projectionScaleY = frameProjection.m11();
        if (!Float.isFinite(projectionScaleY) || projectionScaleY <= 0.0f) {
            throw new IllegalArgumentException("GTAO projection scale must be finite and positive");
        }
        depthPrepassDraws = 0;
        // Rotate the deterministic interleaved pattern only when temporal
        // accumulation can converge the additional samples.  Non-temporal
        // GTAO keeps a stable phase for reproducible single-frame output.
        samplePhase = samplePhase(frameIndex, settings.temporal());
        if (camera instanceof ExternalCamera external) {
            nearPlane = external.nearPlane();
            farPlane = external.farPlane();
        } else {
            nearPlane = CameraProjection.NEAR_PLANE;
            farPlane = CameraProjection.FAR_PLANE;
        }
        projectionUniformDirty = !uniformsInitialized
                || !uploadedInverseProjection.equals(inverseProjection)
                || Float.floatToIntBits(uploadedProjectionScaleY)
                != Float.floatToIntBits(projectionScaleY);
        viewUniformDirty = !uniformsInitialized
                || !uploadedInverseViewProjection.equals(inverseViewProjection);
        previousProjectionUniformDirty = !uniformsInitialized
                || !uploadedPreviousInverseProjection.equals(previousInverseProjection);
        previousViewUniformDirty = !uniformsInitialized
                || !uploadedPreviousViewProjection.equals(previousViewProjection);
        extentUniformDirty = !uniformsInitialized
                || uploadedWidth != this.width || uploadedHeight != this.height;
        phaseUniformDirty = !uniformsInitialized
                || Float.floatToIntBits(uploadedSamplePhase)
                != Float.floatToIntBits(samplePhase);
        nearFarUniformDirty = !uniformsInitialized
                || Float.floatToIntBits(uploadedNearPlane) != Float.floatToIntBits(nearPlane)
                || Float.floatToIntBits(uploadedFarPlane) != Float.floatToIntBits(farPlane);
        // GTAO is screen-space data.  A history that is valid for a large matrix
        // displacement can reproject an old contact shadow across a nearby surface,
        // which reads as a disappearing or sliding shadow while the camera moves.
        // Reject the history before that happens; small sub-pixel motion still benefits
        // from temporal accumulation.
        if (previousCameraValid && matrixDelta(previousViewProjection, currentViewProjection) > 0.18f) {
            if (history != null) history.invalidate();
        }
        frameHistoryWeight = history != null && history.valid() ? settings.historyWeight() : 0.0f;
        frameHistoryValid = history != null && history.valid() ? 1 : 0;
        historyUniformDirty = !uniformsInitialized
                || Float.floatToIntBits(uploadedHistoryWeight)
                != Float.floatToIntBits(frameHistoryWeight)
                || uploadedHistoryValid != frameHistoryValid;
        pendingViewProjection.set(currentViewProjection);
        pendingInverseProjection.set(inverseProjection);
        pendingFrame = true;
    }

    void frameSucceeded() {
        ensureOpen();
        if (history != null) history.commit();
        if (pendingFrame) {
            uploadedInverseProjection.set(inverseProjection);
            uploadedInverseViewProjection.set(inverseViewProjection);
            uploadedPreviousInverseProjection.set(previousInverseProjection);
            uploadedPreviousViewProjection.set(previousViewProjection);
            uploadedProjectionScaleY = projectionScaleY;
            uploadedNearPlane = nearPlane;
            uploadedFarPlane = farPlane;
            uploadedSamplePhase = samplePhase;
            uploadedHistoryWeight = frameHistoryWeight;
            uploadedHistoryValid = frameHistoryValid;
            uploadedWidth = width;
            uploadedHeight = height;
            uniformsInitialized = true;
            previousViewProjection.set(pendingViewProjection);
            previousInverseProjection.set(pendingInverseProjection);
            previousCameraValid = true;
            pendingFrame = false;
        }
    }

    void frameFailed() {
        if (history != null) history.discard();
        pendingFrame = false;
    }

    /**
     * Returns the deterministic interleaved-gradient phase for one frame.
     * Temporal accumulation advances through eight sub-phases; a non-temporal
     * render keeps phase zero so a single frame remains reproducible.
     */
    static float samplePhase(int frameIndex, boolean temporal) {
        return temporal ? (frameIndex & 7) / 8.0f : 0.0f;
    }

    ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0 || this.width == width && this.height == height) {
            return new ResizeCandidate(this, width, height, null);
        }
        GtaoHistory.ResizeCandidate historyCandidate = history == null ? null
                : history.prepareResize(half(width), half(height));
        return new ResizeCandidate(this, width, height, historyCandidate);
    }

    void commitResize(ResizeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    void resize(int width, int height) {
        ResizeCandidate candidate = prepareResize(width, height);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    static final class ResizeCandidate implements AutoCloseable {
        private final GtaoPasses owner;
        private final int width;
        private final int height;
        private GtaoHistory.ResizeCandidate historyCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(GtaoPasses owner, int width, int height,
                                GtaoHistory.ResizeCandidate historyCandidate) {
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.historyCandidate = historyCandidate;
        }

        private void commitInto(GtaoPasses expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("GTAO resize candidate belongs to another pass set");
            }
            if (closed) throw new IllegalStateException("GTAO resize candidate is closed");
            if (committed) throw new IllegalStateException("GTAO resize candidate already committed");
            if (historyCandidate != null) {
                owner.history.commitResize(historyCandidate);
            }
            owner.width = width;
            owner.height = height;
            owner.previousCameraValid = false;
            owner.pendingFrame = false;
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (historyCandidate != null) {
                historyCandidate.close();
                historyCandidate = null;
            }
        }
    }

    boolean historyValid() {
        return history != null && history.valid();
    }

    Render3dDiagnostics.AmbientOcclusionSummary diagnostics(int fullWidth, int fullHeight) {
        long fullPixels = (long) fullWidth * fullHeight;
        long halfPixels = (long) half(fullWidth) * half(fullHeight);
        // D24 depth is conservatively reported as four bytes/texel; the transient
        // color targets are R8, RG16F, R8, R8 and R8 respectively.
        long transientBytes = fullPixels * 5L + halfPixels * 7L;
        long historyBytes = history == null ? 0L
                : halfPixels * 8L;
        return new Render3dDiagnostics.AmbientOcclusionSummary(true, "", settings.quality().name(),
                settings.radius(), settings.strength(), settings.thickness(), fullWidth, fullHeight,
                half(fullWidth), half(fullHeight), settings.temporal(), historyValid(), depthPrepassDraws,
                transientBytes + historyBytes);
    }

    void recordDepthPrepassDraw() {
        if (depthPrepassDraws < Integer.MAX_VALUE) depthPrepassDraws++;
    }

    void invalidateHistory() {
        if (history != null) history.invalidate();
        previousCameraValid = false;
        pendingFrame = false;
    }

    private void recordEstimate(PassResources resources, CommandBuffer commands) {
        Framebuffer target = resources.currentTarget();
        if (target == null) return;
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(estimateProgram)
                .bindTexture(0, resources.depthAttachment(PostProcessTargets.GTAO_DEPTH));
        if (projectionUniformDirty) command.setUniformMat4(estimateProgram, "uInverseProjection", inverseProjection)
                .setUniformFloat(estimateProgram, "uProjectionScaleY", projectionScaleY);
        if (phaseUniformDirty) command.setUniformFloat(estimateProgram, "uFramePhase", samplePhase);
        if (extentUniformDirty) command.setUniformVec2(estimateProgram, "uFullExtent", width, height);
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    private void recordTemporal(PassResources resources, CommandBuffer commands) {
        Framebuffer target = resources.currentTarget();
        if (target == null) return;
        int historyTexture = history == null ? 0 : history.readFramebuffer().colorAttachment();
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(temporalProgram)
                .bindTexture(0, resources.colorAttachment(PostProcessTargets.GTAO_RAW))
                .bindTexture(1, resources.depthAttachment(PostProcessTargets.GTAO_DEPTH))
                .bindTexture(2, historyTexture);
        if (viewUniformDirty) command.setUniformMat4(temporalProgram,
                "uInverseViewProjection", inverseViewProjection);
        if (projectionUniformDirty) command.setUniformMat4(temporalProgram,
                "uInverseProjection", inverseProjection);
        if (previousViewUniformDirty) command.setUniformMat4(temporalProgram,
                "uPreviousViewProjection", previousViewProjection);
        if (previousProjectionUniformDirty) command.setUniformMat4(temporalProgram,
                "uPreviousInverseProjection", previousInverseProjection);
        if (historyUniformDirty) command.setUniformFloat(temporalProgram, "uHistoryWeight",
                frameHistoryWeight).setUniformInt(temporalProgram, "uHistoryValid", frameHistoryValid);
        if (nearFarUniformDirty) command.setUniformFloat(temporalProgram, "uNearPlane", nearPlane)
                .setUniformFloat(temporalProgram, "uFarPlane", farPlane);
        if (extentUniformDirty) command.setUniformVec2(temporalProgram,
                "uHalfExtent", target.width(), target.height());
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
        if (history != null) history.stage(commands, target);
    }

    private void recordDenoise(PassResources resources, CommandBuffer commands) {
        Framebuffer target = resources.currentTarget();
        if (target == null) return;
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(denoiseProgram)
                .bindTexture(0, resources.colorAttachment(PostProcessTargets.GTAO_TEMPORAL))
                .bindTexture(1, resources.depthAttachment(PostProcessTargets.GTAO_DEPTH));
        if (extentUniformDirty) command.setUniformVec2(denoiseProgram,
                "uHalfExtent", target.width(), target.height())
                .setUniformVec2(denoiseProgram, "uFullExtent", width, height);
        if (projectionUniformDirty) command.setUniformMat4(denoiseProgram,
                "uInverseProjection", inverseProjection);
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    private void recordUpsample(PassResources resources, CommandBuffer commands) {
        Framebuffer target = resources.currentTarget();
        if (target == null) return;
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(upsampleProgram)
                .bindTexture(0, resources.colorAttachment(PostProcessTargets.GTAO_DENOISE_B))
                .bindTexture(1, resources.depthAttachment(PostProcessTargets.GTAO_DEPTH));
        if (extentUniformDirty) command.setUniformVec2(upsampleProgram,
                "uHalfExtent", half(width), half(height))
                .setUniformVec2(upsampleProgram, "uFullExtent", width, height);
        if (projectionUniformDirty) command.setUniformMat4(upsampleProgram,
                "uInverseProjection", inverseProjection);
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    private void ensureOpen() {
        if (closed) throw new GlException("GTAO passes are closed");
    }

    /**
     * Sampler bindings and immutable quality/tuning values are set once with
     * DSA after link.  Re-emitting them in every command stream needlessly
     * spends CPU time and obscures the genuinely frame-varying uniforms.
     */
    private void configureStaticUniforms() {
        estimateProgram.setSampler("uDepth", 0)
                .setFloat("uRadius", settings.radius())
                .setFloat("uStrength", settings.strength())
                .setFloat("uThickness", settings.thickness())
                .setInt("uDirections", settings.quality().directions())
                .setInt("uSteps", settings.quality().stepsPerDirection());
        temporalProgram.setSampler("uRaw", 0)
                .setSampler("uDepth", 1)
                .setSampler("uHistory", 2)
                .setFloat("uDepthReject", settings.depthRejectionThreshold());
        denoiseProgram.setSampler("uInput", 0)
                .setSampler("uDepth", 1)
                .setFloat("uDepthReject", settings.depthRejectionThreshold());
        upsampleProgram.setSampler("uInput", 0)
                .setSampler("uDepth", 1)
                .setFloat("uDepthReject", settings.depthRejectionThreshold());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(history, failure);
        failure = closeCollecting(quad, failure);
        failure = closeCollecting(upsampleProgram, failure);
        failure = closeCollecting(denoiseProgram, failure);
        failure = closeCollecting(temporalProgram, failure);
        failure = closeCollecting(estimateProgram, failure);
        if (failure != null) throw failure;
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int half(int value) {
        return Math.max(1, (value + 1) / 2);
    }

    private float matrixDelta(Matrix4f left, Matrix4f right) {
        float maximum = 0.0f;
        // Reuse the owner buffers: this method runs once per frame and must not
        // create garbage after the GTAO pipeline has warmed up.
        left.get(matrixDeltaLeft);
        right.get(matrixDeltaRight);
        for (int index = 0; index < 16; index++) {
            maximum = Math.max(maximum, Math.abs(matrixDeltaLeft[index] - matrixDeltaRight[index]));
        }
        return maximum;
    }

    private static RuntimeException closeCollecting(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return failure;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            RuntimeException runtime = closeFailure instanceof RuntimeException existing
                    ? existing : new IllegalStateException("Failed to close GTAO resource", closeFailure);
            if (failure == null) return runtime;
            failure.addSuppressed(runtime);
        }
        return failure;
    }
}
