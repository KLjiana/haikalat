package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Internal GTAO graph and shader owner for one pipeline generation. */
final class GtaoPasses implements AutoCloseable {
    /**
     * View-space movement required before the temporal sample pattern rotates.
     * This intentionally ignores TAA's jittered projection matrix.
     */
    private static final float SAMPLE_PHASE_MOTION_EPSILON = 5.0e-4f;
    private GtaoSettings settings;
    private GtaoSettings uploadedSettings;
    enum InvalidationReason { NONE, FIRST_FRAME, SETTINGS, REJECTION_POLICY, RESIZE, CAMERA_CUT, PROJECTION, MOTION_PHASE, FRAME_FAILURE, EXPLICIT_RESET, WORLD }
    private InvalidationReason pendingInvalidation = InvalidationReason.FIRST_FRAME;
    private InvalidationReason completedInvalidation = InvalidationReason.NONE;
    private final ShaderProgram estimateProgram;
    private final ShaderProgram temporalProgram;
    private final ShaderProgram denoiseProgram;
    private final ShaderProgram upsampleProgram;
    private final ScreenQuad quad;
    private final GtaoHistory history;
    private final GtaoDepthPass dedicatedDepth;
    private GtaoSurfaceInputs preparedInputs;
    private SceneFrame preparedScene;
    private CameraUniforms preparedUniforms;
    private SceneDeformationDefaults preparedDeformationDefaults;
    private InstancedRenderer preparedInstances;
    private Camera preparedCamera;
    private int preparedFrameIndex;
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f previousInverseProjection = new Matrix4f();
    private final Matrix4f pendingInverseProjection = new Matrix4f();
    private final Matrix4f currentViewProjection = new Matrix4f();
    private final Matrix4f previousViewProjection = new Matrix4f();
    private final Matrix4f pendingViewProjection = new Matrix4f();
    private final Matrix4f previousCameraView = new Matrix4f();
    private final Matrix4f pendingCameraView = new Matrix4f();
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameView = new Matrix4f();
    private final float[] matrixDeltaLeft = new float[16];
    private final float[] matrixDeltaRight = new float[16];
    private final Matrix4f uploadedInverseProjection = new Matrix4f();
    private final Matrix4f uploadedInverseViewProjection = new Matrix4f();
    private final Matrix4f uploadedPreviousInverseProjection = new Matrix4f();
    private final Matrix4f uploadedPreviousViewProjection = new Matrix4f();
    private final org.joml.Vector2f jitterScratch = new org.joml.Vector2f();
    private boolean previousCameraValid;
    private final boolean sharedSurface;
    private float pendingJitterX;
    private float pendingJitterY;
    private float previousJitterX;
    private float previousJitterY;
    private boolean previousPhaseMotionActive;
    private boolean pendingPhaseMotionActive;
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
    private long resizeRevision;
    private float samplePhase;

    GtaoPasses(GtaoSettings settings, int width, int height) {
        this(settings, width, height, true, false);
    }

    GtaoPasses(GtaoSettings settings, int width, int height, boolean sharedSurface, boolean instances) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) throw new IllegalArgumentException("disabled GTAO does not allocate an owner");
        GtaoAllocationPlan.validateDeviceExtent(width, height,
                org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE));
        GtaoAllocationPlan.prepare(width, height, settings, sharedSurface, 0, false);
        this.sharedSurface = sharedSurface;
        this.width = positive(width, "width");
        this.height = positive(height, "height");
        ShaderProgram estimate = null;
        ShaderProgram temporal = null;
        ShaderProgram denoise = null;
        ShaderProgram upsample = null;
        ScreenQuad screenQuad = null;
        GtaoHistory temporalHistory = null;
        GtaoDepthPass depthRenderer = null;
        try {
            compilationPoint(0);
            estimate = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-estimate.frag");
            compilationPoint(1);
            temporal = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-temporal.frag");
            compilationPoint(2);
            denoise = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-denoise.frag");
            compilationPoint(3);
            upsample = ShaderProgram.fromResource(GtaoPasses.class,
                    "/shaders/postprocess/gtao-quad.vert", "/shaders/postprocess/gtao-upsample.frag");
            if (Integer.getInteger("haikalat.test.failGtaoAllocation", -1) == 2)
                throw new IllegalStateException("injected GTAO quad allocation failure");
            screenQuad = new ScreenQuad();
            temporalHistory = settings.temporal()
                    ? new GtaoHistory(half(this.width), half(this.height)) : null;
            depthRenderer = sharedSurface ? null : new GtaoDepthPass(instances);
            estimateProgram = estimate;
            temporalProgram = temporal;
            denoiseProgram = denoise;
            upsampleProgram = upsample;
            quad = screenQuad;
            history = temporalHistory;
            dedicatedDepth = depthRenderer;
            configureStaticUniforms();
        } catch (RuntimeException failure) {
            closeCollecting(depthRenderer, failure);
            closeCollecting(temporalHistory, failure);
            closeCollecting(screenQuad, failure);
            closeCollecting(upsample, failure);
            closeCollecting(denoise, failure);
            closeCollecting(temporal, failure);
            closeCollecting(estimate, failure);
            throw failure;
        }
    }

    void addPreGeometryPasses(RenderGraph graph, GtaoSurfaceInputs inputs) {
        ensureOpen();
        Objects.requireNonNull(graph, "graph");
        if (inputs.graph() != graph || inputs.shared() != sharedSurface || inputs.temporal() != settings.temporal())
            throw new IllegalArgumentException("GTAO graph input lease does not match its resource owner");
        if (!sharedSurface) {
            graph.addPass(PostProcessTargets.GTAO_DEPTH_PREPASS)
                    .createDepthTexture(PostProcessTargets.GTAO_DEPTH)
                    .clearDepthOnly()
                    .execute((res, cmd) -> {
                        requireFrame(inputs);
                        preparedDeformationDefaults.record(cmd);
                        depthPrepassDraws += dedicatedDepth.record(cmd, preparedScene, preparedCamera,
                                preparedUniforms, preparedInstances, inputs.aa(), preparedFrameIndex, width, height);
                    });
        }

        graph.addPass(PostProcessTargets.GTAO_ESTIMATE_PASS)
                .createColors(java.util.List.of(PostProcessTargets.GTAO_RAW,
                                PostProcessTargets.GTAO_NORMAL),
                        java.util.List.of(RenderFormat.R8, RenderFormat.RG16F))
                .relativeSizeCeil(0.5f)
                .noClear()
                .dependsOn(inputs.producer())
                .execute((res, cmd) -> { requireFrame(inputs); recordEstimate(res, cmd); });

        RenderGraph.PassBuilder temporal = graph.addPass(PostProcessTargets.GTAO_TEMPORAL_PASS);
        if (history == null) {
            temporal.createColor(PostProcessTargets.GTAO_TEMPORAL, RenderFormat.RG16F)
                    .relativeSizeCeil(0.5f);
        } else {
            // The candidate history color is also this frame's temporal output.
            // Render directly into it instead of allocating a second RG16F
            // target and copying the entire half-resolution image afterward.
            temporal.writeToExternalTarget();
        }
        temporal.noClear().dependsOn(PostProcessTargets.GTAO_ESTIMATE_PASS)
                .execute((res, cmd) -> { requireFrame(inputs); recordTemporal(res, cmd); });

        graph.addPass(PostProcessTargets.GTAO_DENOISE_HORIZONTAL_PASS)
                // The original implementation used two separable passes.  A
                // single 3x3 bilateral pass has the same bounded neighborhood
                // contract while removing one fullscreen draw/FBO/timer from
                // the stable frame path.
                .createColor(PostProcessTargets.GTAO_DENOISE_B, RenderFormat.R8)
                .relativeSizeCeil(0.5f)
                .noClear()
                .dependsOn(PostProcessTargets.GTAO_TEMPORAL_PASS)
                .execute((res, cmd) -> { requireFrame(inputs); recordDenoise(res, cmd); });

        graph.addPass(PostProcessTargets.GTAO_UPSAMPLE_PASS)
                .createColor(PostProcessTargets.GTAO_FINAL, RenderFormat.R8)
                .noClear()
                .dependsOn(PostProcessTargets.GTAO_DENOISE_HORIZONTAL_PASS)
                .execute((res, cmd) -> { requireFrame(inputs); recordUpsample(res, cmd); });
    }

    void prepareFrame(RenderFrameContext context, SceneFrame scene, CameraUniforms uniforms,
                      InstancedRenderer instances, GtaoSurfaceInputs inputs, SceneDeformationDefaults deformationDefaults) {
        ensureOpen(); inputs.validateFrame(context.width(), context.height());
        preparedInputs = inputs; preparedScene = Objects.requireNonNull(scene);
        preparedUniforms = Objects.requireNonNull(uniforms); preparedInstances = instances;
        preparedDeformationDefaults = Objects.requireNonNull(deformationDefaults);
        preparedCamera = context.camera(); preparedFrameIndex = context.frameIndex();
        beginFrame(context.camera(), context.width(), context.height(), context.frameIndex(), inputs.aa());
    }

    private void requireFrame(GtaoSurfaceInputs inputs) {
        ensureOpen();
        if (preparedInputs != inputs || !pendingFrame)
            throw new IllegalStateException("stale or unprepared GTAO graph generation");
        inputs.validateFrame(width, height);
    }

    boolean compatible(GtaoSettings replacement, boolean shared) {
        return !closed && settings.enabled() == replacement.enabled()
                && settings.temporal() == replacement.temporal() && sharedSurface == shared;
    }

    GtaoSettings settings() { return settings; }
    GtaoHistory historyForTest() { return history; }
    float samplePhase() { return samplePhase; }

    void applySettings(GtaoSettings replacement) {
        ensureOpen(); Objects.requireNonNull(replacement);
        if (!compatible(replacement, sharedSurface) || pendingFrame)
            throw new IllegalStateException("GTAO resource-shape updates require a generation candidate");
        if (replacement.equals(settings)) return;
        if (replacement.radius() != settings.radius() || replacement.strength() != settings.strength()
                || replacement.thickness() != settings.thickness() || replacement.quality() != settings.quality())
            rejectHistory(InvalidationReason.SETTINGS);
        else if (replacement.depthRejectionThreshold() != settings.depthRejectionThreshold())
            rejectHistory(InvalidationReason.REJECTION_POLICY);
        settings = replacement;
    }

    private void rejectHistory(InvalidationReason reason) {
        if (history != null) history.invalidate();
        if (pendingInvalidation == InvalidationReason.NONE || pendingInvalidation == InvalidationReason.FIRST_FRAME)
            pendingInvalidation = reason;
    }

    String completedInvalidationReason() {
        return completedInvalidation == InvalidationReason.NONE ? "" : "GTAO_" + completedInvalidation.name();
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
        TemporalJitter.uvOffset(antiAliasingMode, frameIndex, this.width, this.height,
                jitterScratch);
        pendingJitterX = jitterScratch.x;
        pendingJitterY = jitterScratch.y;
        camera.getViewMatrix(frameView);
        float cameraViewDelta = previousCameraValid
                ? matrixDelta(previousCameraView, frameView) : 0.0f;
        boolean phaseMotionActive = previousCameraValid
                && cameraViewDelta > SAMPLE_PHASE_MOTION_EPSILON;
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
        // A static view must keep a stable sample phase. Rotating one global
        // angle on every frame makes all contact edges change together, which
        // remains visible even after temporal accumulation. Real camera motion
        // still advances the deterministic sequence so moving views can gather
        // additional samples. The view matrix is used instead of the jittered
        // view-projection matrix, so TAA jitter cannot reintroduce shimmer.
        samplePhase = samplePhaseForMotion(frameIndex, settings.temporal(), phaseMotionActive);
        if (camera instanceof ExternalCamera external) {
            nearPlane = external.nearPlane();
            farPlane = external.farPlane();
        } else {
            nearPlane = CameraProjection.NEAR_PLANE;
            farPlane = CameraProjection.FAR_PLANE;
        }
        if (previousCameraValid
                && (Float.floatToIntBits(nearPlane) != Float.floatToIntBits(uploadedNearPlane)
                || Float.floatToIntBits(farPlane) != Float.floatToIntBits(uploadedFarPlane))) {
            // History depth is stored in positive view-space units.  A clip
            // range change changes the interpretation/precision of both the
            // current and previous projection, so retaining the old history
            // would make a matching surface look like a disocclusion (or vice
            // versa).
            rejectHistory(InvalidationReason.PROJECTION);
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
            rejectHistory(InvalidationReason.CAMERA_CUT);
        }
        if (previousCameraValid && phaseMotionActive != previousPhaseMotionActive
                && history != null) {
            // Do not blend history generated with a rotating pattern into a
            // stable phase (or vice versa); that transition would look like a
            // one-frame contact-shadow jump when the camera starts or stops.
            rejectHistory(InvalidationReason.MOTION_PHASE);
        }
        frameHistoryWeight = history != null && history.valid() ? settings.historyWeight() : 0.0f;
        frameHistoryValid = history != null && history.valid() ? 1 : 0;
        historyUniformDirty = !uniformsInitialized
                || Float.floatToIntBits(uploadedHistoryWeight)
                != Float.floatToIntBits(frameHistoryWeight)
                || uploadedHistoryValid != frameHistoryValid;
        pendingViewProjection.set(currentViewProjection);
        pendingCameraView.set(frameView);
        pendingPhaseMotionActive = phaseMotionActive;
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
            uploadedSettings = settings;
            completedInvalidation = pendingInvalidation;
            pendingInvalidation = InvalidationReason.NONE;
            previousViewProjection.set(pendingViewProjection);
            previousCameraView.set(pendingCameraView);
            previousPhaseMotionActive = pendingPhaseMotionActive;
            previousInverseProjection.set(pendingInverseProjection);
            previousJitterX = pendingJitterX;
            previousJitterY = pendingJitterY;
            previousCameraValid = true;
            pendingFrame = false;
        }
    }

    void frameFailed() {
        if (history != null) history.invalidate();
        pendingInvalidation = InvalidationReason.FRAME_FAILURE;
        uniformsInitialized = false;
        pendingFrame = false;
    }

    void synchronizeTemporalImport(RenderGraph graph) {
        if (history == null) return;
        if (preparedInputs == null || preparedInputs.graph() != graph)
            throw new IllegalStateException("GTAO history import belongs to another graph generation");
        Framebuffer candidate = history.writeFramebuffer();
        if (candidate.width() != half(width) || candidate.height() != half(height))
            throw new IllegalStateException("GTAO history target has a stale extent");
        graph.importExternalColor(PostProcessTargets.GTAO_TEMPORAL,
                ExternalAttachment.borrowedColor(candidate.colorAttachment(),
                        RenderFormat.RG16F, candidate.width(), candidate.height()));
    }

    /**
     * Returns the deterministic interleaved-gradient phase for one frame.
     * Temporal accumulation advances through eight sub-phases; a non-temporal
     * render keeps phase zero so a single frame remains reproducible.
     */
    static float samplePhase(int frameIndex, boolean temporal) {
        return temporal ? (frameIndex & 7) / 8.0f : 0.0f;
    }

    static float samplePhaseForMotion(int frameIndex, boolean temporal, boolean cameraMoving) {
        return samplePhase(frameIndex, temporal && cameraMoving);
    }

    ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("GTAO extent must be positive");
        if (this.width == width && this.height == height) {
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
        private final long originalRevision;
        private GtaoHistory.ResizeCandidate historyCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(GtaoPasses owner, int width, int height,
                                GtaoHistory.ResizeCandidate historyCandidate) {
            this.owner = owner;
            this.originalRevision = owner.resizeRevision;
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
            owner.ensureOpen();
            if (owner.resizeRevision != originalRevision) throw new IllegalStateException("stale GTAO pass resize candidate");
            if (historyCandidate != null) {
                owner.history.commitResize(historyCandidate);
            }
            owner.width = width;
            owner.height = height;
            owner.previousCameraValid = false;
            owner.previousPhaseMotionActive = false;
            owner.pendingPhaseMotionActive = false;
            owner.pendingFrame = false;
            owner.previousJitterX = 0.0f;
            owner.previousJitterY = 0.0f;
            owner.pendingInvalidation = InvalidationReason.RESIZE;
            owner.resizeRevision++;
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

    static Render3dDiagnostics.AmbientOcclusionSummary disabledDiagnostics(int width, int height) {
        return new Render3dDiagnostics.AmbientOcclusionSummary(false, "disabled", "disabled",
                0, 0, 0, width, height, half(width), half(height), false, false, 0, 0L);
    }

    Render3dDiagnostics.AmbientOcclusionSummary diagnostics(int fullWidth, int fullHeight) {
        long fullPixels = (long) fullWidth * fullHeight;
        long halfPixels = (long) half(fullWidth) * half(fullHeight);
        // D24 depth is conservatively reported as four bytes/texel when GTAO
        // owns its legacy prepass; in shared-surface mode the depth belongs to
        // the SceneSurfacePass budget instead.  The transient color targets
        // include the RG16F octahedral normal cache emitted beside raw AO.
        long transientBytes = (sharedSurface ? fullPixels * 1L : fullPixels * 5L)
                + halfPixels * (history == null ? 10L : 6L);
        long historyBytes = history == null ? 0L
                : halfPixels * 8L;
        return new Render3dDiagnostics.AmbientOcclusionSummary(true, "", settings.quality().name(),
                settings.radius(), settings.strength(), settings.thickness(), fullWidth, fullHeight,
                half(fullWidth), half(fullHeight), settings.temporal(), historyValid(), depthPrepassDraws,
                transientBytes + historyBytes + 96);
    }

    void recordDepthPrepassDraw() {
        if (depthPrepassDraws < Integer.MAX_VALUE) depthPrepassDraws++;
    }

    void invalidateHistory() {
        invalidateHistory(InvalidationReason.EXPLICIT_RESET);
    }

    void invalidateHistory(InvalidationReason reason) {
        if (history != null) history.invalidate();
        pendingInvalidation = Objects.requireNonNull(reason);
        previousCameraValid = false;
        previousPhaseMotionActive = false;
        pendingPhaseMotionActive = false;
        pendingFrame = false;
        previousJitterX = 0.0f;
        previousJitterY = 0.0f;
    }

    private void recordEstimate(PassResources resources, CommandBuffer commands) {
        Framebuffer target = resources.currentTarget();
        if (target == null) return;
        int depthTexture = preparedInputs.depth(resources);
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(estimateProgram)
                .bindSampler(0, null).bindSampler(1, null)
                .bindTexture(0, depthTexture);
        if (sharedSurface) {
            command.bindTexture(1, preparedInputs.texture(resources, SceneBufferChannel.NORMAL))
                    .setUniformInt(estimateProgram, "uSharedSurface", 1)
                    .setUniformMat4(estimateProgram, "uView", frameView);
        } else {
            command.setUniformInt(estimateProgram, "uSharedSurface", 0);
        }
        if (!settings.equals(uploadedSettings)) command
                .setUniformFloat(estimateProgram, "uRadius", settings.radius())
                .setUniformFloat(estimateProgram, "uStrength", settings.strength())
                .setUniformFloat(estimateProgram, "uThickness", settings.thickness())
                .setUniformInt(estimateProgram, "uDirections", settings.quality().directions())
                .setUniformInt(estimateProgram, "uSteps", settings.quality().stepsPerDirection());
        if (projectionUniformDirty) command.setUniformMat4(estimateProgram, "uInverseProjection", inverseProjection)
                .setUniformFloat(estimateProgram, "uProjectionScaleY", projectionScaleY);
        if (phaseUniformDirty) command.setUniformFloat(estimateProgram, "uFramePhase", samplePhase);
        if (extentUniformDirty) command.setUniformVec2(estimateProgram, "uFullExtent", width, height);
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    private void recordTemporal(PassResources resources, CommandBuffer commands) {
        Framebuffer target = history == null ? resources.currentTarget()
                : history.writeFramebuffer();
        if (target == null) return;
        int historyTexture = history == null ? 0 : history.readFramebuffer().colorAttachment();
        int depthTexture = preparedInputs.depth(resources);
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindFramebuffer(target).viewport(0, 0, target.width(), target.height())
                .bindShader(temporalProgram)
                .bindSampler(0, null).bindSampler(1, null).bindSampler(2, null)
                .bindSampler(3, null).bindSampler(4, null).bindSampler(5, null)
                .bindTexture(0, resources.colorAttachment(PostProcessTargets.GTAO_RAW))
                .bindTexture(1, depthTexture)
                .bindTexture(2, historyTexture);
        if (sharedSurface && history != null) {
            command.bindTexture(3, preparedInputs.texture(resources, SceneBufferChannel.VELOCITY))
                    .bindTexture(4, preparedInputs.texture(resources, SceneBufferChannel.PREVIOUS_SURFACE_DEPTH))
                    .bindTexture(5, preparedInputs.texture(resources, SceneBufferChannel.VALIDITY))
                    .setUniformInt(temporalProgram, "uSharedSurface", 1)
                    .setUniformVec2(temporalProgram, "uJitter", pendingJitterX, pendingJitterY)
                    .setUniformVec2(temporalProgram, "uPreviousJitter",
                            previousJitterX, previousJitterY);
        } else {
            command.setUniformInt(temporalProgram, "uSharedSurface", 0);
        }
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
        if (!settings.equals(uploadedSettings)) command.setUniformFloat(temporalProgram, "uDepthReject", settings.depthRejectionThreshold());
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
        if (history != null) history.stageRendered(target);
    }

    private void recordDenoise(PassResources resources, CommandBuffer commands) {
        Framebuffer target = resources.currentTarget();
        if (target == null) return;
        int depthTexture = preparedInputs.depth(resources);
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(denoiseProgram)
                .bindSampler(0, null).bindSampler(1, null).bindSampler(2, null)
                .bindTexture(0, resources.colorAttachment(PostProcessTargets.GTAO_TEMPORAL))
                .bindTexture(1, depthTexture)
                .bindTexture(2, resources.colorAttachment(PostProcessTargets.GTAO_NORMAL));
        if (extentUniformDirty) command.setUniformVec2(denoiseProgram,
                "uHalfExtent", target.width(), target.height());
        if (projectionUniformDirty) command.setUniformMat4(denoiseProgram,
                "uInverseProjection", inverseProjection);
        if (!settings.equals(uploadedSettings)) command.setUniformFloat(denoiseProgram, "uDepthReject", settings.depthRejectionThreshold());
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    private void recordUpsample(PassResources resources, CommandBuffer commands) {
        Framebuffer target = resources.currentTarget();
        if (target == null) return;
        int depthTexture = preparedInputs.depth(resources);
        CommandBuffer command = commands.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(upsampleProgram)
                .bindSampler(0, null).bindSampler(1, null).bindSampler(2, null)
                .bindTexture(0, resources.colorAttachment(PostProcessTargets.GTAO_DENOISE_B))
                .bindTexture(1, depthTexture)
                .bindTexture(2, resources.colorAttachment(PostProcessTargets.GTAO_NORMAL));
        if (extentUniformDirty) command.setUniformVec2(upsampleProgram,
                "uHalfExtent", half(width), half(height))
                .setUniformVec2(upsampleProgram, "uFullExtent", width, height);
        if (projectionUniformDirty) command.setUniformMat4(upsampleProgram,
                "uInverseProjection", inverseProjection);
        if (!settings.equals(uploadedSettings)) command.setUniformFloat(upsampleProgram, "uDepthReject", settings.depthRejectionThreshold());
        command.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    private void ensureOpen() {
        if (closed) throw new GlException("GTAO passes are closed");
    }

    private static void compilationPoint(int ordinal) {
        if (Integer.getInteger("haikalat.test.failGtaoCompilation", -1) == ordinal)
            throw new IllegalStateException("injected GTAO shader compilation failure " + ordinal);
    }

    /**
     * Sampler bindings and immutable quality/tuning values are set once with
     * DSA after link.  Re-emitting them in every command stream needlessly
     * spends CPU time and obscures the genuinely frame-varying uniforms.
     */
    private void configureStaticUniforms() {
        estimateProgram.setSampler("uDepth", 0)
                .setSampler("uSharedNormal", 1)
                .setInt("uSharedSurface", 0)
                .setFloat("uRadius", settings.radius())
                .setFloat("uStrength", settings.strength())
                .setFloat("uThickness", settings.thickness())
                .setInt("uDirections", settings.quality().directions())
                .setInt("uSteps", settings.quality().stepsPerDirection());
        temporalProgram.setSampler("uRaw", 0)
                .setSampler("uDepth", 1)
                .setSampler("uHistory", 2)
                .setSampler("uVelocity", 3)
                .setSampler("uPreviousDepth", 4)
                .setSampler("uValidity", 5)
                .setInt("uSharedSurface", 0)
                .setFloat("uDepthReject", settings.depthRejectionThreshold());
        denoiseProgram.setSampler("uInput", 0)
                .setSampler("uDepth", 1)
                .setSampler("uNormal", 2)
                .setFloat("uDepthReject", settings.depthRejectionThreshold());
        upsampleProgram.setSampler("uInput", 0)
                .setSampler("uDepth", 1)
                .setSampler("uNormal", 2)
                .setFloat("uDepthReject", settings.depthRejectionThreshold());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        failure = closeCollecting(dedicatedDepth, failure);
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
