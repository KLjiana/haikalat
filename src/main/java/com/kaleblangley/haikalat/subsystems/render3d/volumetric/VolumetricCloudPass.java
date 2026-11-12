package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Producer of a bounded volumetric cloud layer with low-resolution marching and
 * full-resolution spatial/temporal reconstruction.
 *
 * <p>The pass owns a low-resolution {@code RGBA16F} cloud target (premultiplied
 * single scatter + transmittance), a full-resolution {@code RGBA16F} output
 * target, two full-resolution history slots (cloud + depth/exposure state) and
 * three shader programs sharing one screen quad. It borrows the caller's
 * single-sample HDR colour source and one single-sample opaque depth texture;
 * it never owns, resizes, reads back or writes them.</p>
 *
 * <p><b>Frame sequence.</b> {@link #beginFrame()} marks the candidate; {@link
 * #record} records three draws: the low-resolution view march, the depth-aware
 * reconstruction with history rejection into the history write slot, and the
 * composite {@code S + T * scene} into {@link #colorTarget()}. {@link
 * #frameSucceeded()} publishes the history candidate and stores the camera and
 * exposure used for the next reprojection; {@link #frameFailed()} drops the
 * candidate and keeps the previous successful frame. A history is only usable
 * when a frame committed successfully, so a failed frame can never be observed
 * as a pixel rollback.</p>
 *
 * <p><b>Rejection.</b> The pass rejects the whole history for a camera cut
 * (translation, rotation or projection change beyond the configured bounds) and
 * for an exposure change beyond {@link VolumetricCloudSettings#exposureThreshold()}
 * or an unusable exposure. The shader additionally rejects per pixel when the
 * previous state is invalid, the reprojected coordinate leaves the raster, or
 * the expected previous anchor depth disagrees with the stored one
 * (disocclusion); surviving history is clamped to the current 3x3
 * low-resolution range before blending.</p>
 *
 * <p><b>Composition.</b> The pass is recorded after the scene/atmosphere
 * (#29) and volumetric-fog colour composite and composites its own medium
 * exactly once over that colour. It never samples fog density or integrates a
 * second fog medium. The horizontal coverage field is the existing #10
 * cloud-shadow interface ({@link CloudShadowSettings} and the shared mask
 * functions) rather than a second cloud field.</p>
 *
 * <p>{@link VolumetricCloudSettings#disabled()} is rejected by the constructor:
 * while disabled no pass, shader, framebuffer or draw exists.
 * {@link #resize(int, int)} allocates the complete candidate set before
 * publishing it, so a failure keeps the previous targets and history.
 * {@link #close()} is idempotent and aggregates close failures.</p>
 */
public final class VolumetricCloudPass implements GlResource {
    /** RenderGraph pass name reserved for integration. */
    public static final String PASS_NAME = "VolumetricCloudPass";
    /**
     * Graph colour attachment declared by the pipeline bridge. The pass-owned
     * framebuffer is copied into this target; downstream consumers read it
     * through the {@link #PASS_NAME} producer.
     */
    public static final String COLOR_TEXTURE = "volume.cloudColor";
    /** Fragment texture unit of the borrowed single-sample scene depth. */
    public static final int DEPTH_UNIT = 0;
    /** Fragment texture unit of the low-resolution cloud signal. */
    public static final int CLOUD_UNIT = 1;
    /** Fragment texture unit of the borrowed HDR colour source. */
    public static final int COLOR_UNIT = 2;
    /** Fragment texture unit of the read history cloud signal. */
    public static final int HISTORY_CLOUD_UNIT = 3;
    /** Fragment texture unit of the read history state. */
    public static final int HISTORY_STATE_UNIT = 4;
    /** Largest accepted target edge in pixels. */
    public static final int MAXIMUM_EXTENT = 16_384;

    private static final String VERTEX_RESOURCE = "/shaders/render3d/volumetric/cloud.vert";
    private static final String MARCH_RESOURCE = "/shaders/render3d/volumetric/cloud-march.frag";
    private static final String RECONSTRUCT_RESOURCE =
            "/shaders/render3d/volumetric/cloud-reconstruct.frag";
    private static final String COMPOSITE_RESOURCE =
            "/shaders/render3d/volumetric/cloud-composite.frag";
    private static final String FAIL_RESIZE_PROPERTY =
            "haikalat.test.failVolumetricCloudResizeAllocation";
    private static final float PROJECTION_RELATIVE_TOLERANCE = 1.0e-4f;
    private static final float DIRECTION_EPSILON = 1.0e-6f;

    private final VolumetricCloudSettings settings;
    private final ShaderProgram marchProgram;
    private final ShaderProgram reconstructProgram;
    private final ShaderProgram compositeProgram;
    private final ScreenQuad screen;
    private final CloudHistory history;
    private final Matrix4f viewScratch = new Matrix4f();
    private final Matrix4f projectionScratch = new Matrix4f();
    private final Matrix4f inverseViewScratch = new Matrix4f();
    private final Matrix4f inverseProjectionScratch = new Matrix4f();
    private final Matrix4f viewProjectionScratch = new Matrix4f();
    private final Matrix4f previousView = new Matrix4f();
    private final Matrix4f previousViewProjection = new Matrix4f();
    private ExternalCamera previousCamera;
    private ExternalCamera frameCamera;
    private float previousExposure;
    private float frameExposure;
    private boolean hasPreviousFrame;
    private Framebuffer lowTarget;
    private Framebuffer outputTarget;
    private int width;
    private int height;
    private int lowWidth;
    private int lowHeight;
    private boolean framePrepared;
    private boolean frameRecorded;
    private boolean historyAccepted;
    private boolean closed;

    /**
     * Creates an active cloud pass and allocates the complete target set.
     *
     * @param settings active bounded configuration
     * @param width    initial target width in pixels
     * @param height   initial target height in pixels
     * @throws IllegalArgumentException when disabled or the extent is invalid
     */
    public VolumetricCloudPass(VolumetricCloudSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            throw new IllegalArgumentException("volumetric clouds are disabled");
        }
        validateExtent(width, height);
        ShaderProgram createdMarch = null;
        ShaderProgram createdReconstruct = null;
        ShaderProgram createdComposite = null;
        ScreenQuad createdScreen = null;
        CloudHistory createdHistory = null;
        Framebuffer createdLow = null;
        Framebuffer createdOutput = null;
        try {
            createdMarch = ShaderProgram.fromSources(vertexSource(), marchSource());
            createdReconstruct = ShaderProgram.fromSources(vertexSource(), reconstructSource());
            createdComposite = ShaderProgram.fromSources(vertexSource(), compositeSource());
            createdScreen = new ScreenQuad();
            createdHistory = new CloudHistory(width, height);
            createdLow = createColorTarget(settings.lowResolutionExtent(width),
                    settings.lowResolutionExtent(height));
            createdOutput = createColorTarget(width, height);
            marchProgram = createdMarch;
            reconstructProgram = createdReconstruct;
            compositeProgram = createdComposite;
            screen = createdScreen;
            history = createdHistory;
            lowTarget = createdLow;
            outputTarget = createdOutput;
            this.width = width;
            this.height = height;
            lowWidth = settings.lowResolutionExtent(width);
            lowHeight = settings.lowResolutionExtent(height);
        } catch (RuntimeException failure) {
            closeSuppressed(createdOutput, failure);
            closeSuppressed(createdLow, failure);
            closeSuppressed(createdHistory, failure);
            closeSuppressed(createdScreen, failure);
            closeSuppressed(createdComposite, failure);
            closeSuppressed(createdReconstruct, failure);
            closeSuppressed(createdMarch, failure);
            throw failure;
        }
    }

    public VolumetricCloudSettings settings() {
        return settings;
    }

    /** @return current full-resolution target width, or 0 after close */
    public int width() {
        return closed ? 0 : width;
    }

    /** @return current full-resolution target height, or 0 after close */
    public int height() {
        return closed ? 0 : height;
    }

    /** @return current low-resolution march width, or 0 after close */
    public int lowResolutionWidth() {
        return closed ? 0 : lowWidth;
    }

    /** @return current low-resolution march height, or 0 after close */
    public int lowResolutionHeight() {
        return closed ? 0 : lowHeight;
    }

    /** @return sampled full-resolution RGBA16F colour texture id, or 0 after close */
    public int colorTexture() {
        return closed || outputTarget == null ? 0 : outputTarget.colorAttachment();
    }

    /**
     * Borrowed view of the pass-owned full-resolution composited target for the
     * next colour consumer. The caller must not close, resize or render into it
     * with an unrelated renderer; ownership stays with this pass.
     *
     * @throws GlException when the pass is closed
     */
    public Framebuffer colorTarget() {
        ensureOpen();
        return outputTarget;
    }

    /**
     * @return true when the most recently recorded frame was allowed to use the
     *         previous successful history (camera, exposure and commit checks)
     */
    public boolean historyAccepted() {
        return !closed && historyAccepted;
    }

    /**
     * Marks the history write slot as this frame's candidate. Must be called
     * before {@link #record}; the candidate is only published by
     * {@link #frameSucceeded()}.
     */
    public void beginFrame() {
        ensureOpen();
        if (framePrepared) {
            throw new IllegalStateException("volumetric cloud frame is already prepared");
        }
        history.prepareFrame();
        framePrepared = true;
        frameRecorded = false;
        historyAccepted = false;
    }

    /**
     * Copies/records the cloud layer over the borrowed colour source.
     *
     * @param cmd               GPU-owner command buffer
     * @param colorSource       already composited HDR scene colour (atmosphere/fog included)
     * @param sceneDepthTexture single-sample opaque scene depth matching the camera
     * @param camera            frozen frame camera used by the colour source
     * @param timeSeconds       cloud wind time in seconds, finite and non-negative
     * @param exposure          positive finite frame exposure scalar, used only for history rejection
     * @throws IllegalArgumentException on a mismatched extent, missing depth or unusable input
     */
    public void record(CommandBuffer cmd, Framebuffer colorSource, int sceneDepthTexture,
                       ExternalCamera camera, double timeSeconds, float exposure) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(colorSource, "colorSource");
        Objects.requireNonNull(camera, "camera");
        if (!framePrepared) {
            throw new IllegalStateException("volumetric clouds require beginFrame() before record()");
        }
        if (frameRecorded) {
            throw new IllegalStateException("volumetric clouds record once per frame");
        }
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("cloud time must be finite and non-negative");
        }
        if (!Float.isFinite(exposure) || exposure <= 0.0f) {
            throw new IllegalArgumentException("cloud exposure must be finite and positive");
        }
        colorSource.ensureOpen();
        if (colorSource == outputTarget || colorSource == lowTarget) {
            throw new IllegalArgumentException(
                    "volumetric clouds require a colour source other than a pass target");
        }
        if (colorSource.width() != width || colorSource.height() != height) {
            throw new IllegalArgumentException(
                    "volumetric cloud colour source extent must match the pass target");
        }
        if (colorSource.multisampled()) {
            throw new IllegalArgumentException(
                    "volumetric clouds require a resolved single-sample colour source");
        }
        if (!colorSource.colorAttachmentIsTexture(0)) {
            throw new IllegalArgumentException(
                    "volumetric clouds require a sampled colour attachment");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException(
                    "volumetric clouds require the single-sample opaque scene depth");
        }
        prepareMatrices(camera);
        boolean cameraCut = hasPreviousFrame && history.valid()
                && cameraCut(previousCamera, camera, settings);
        boolean exposureAccepted = hasPreviousFrame
                && exposureAccepted(previousExposure, exposure, settings.exposureThreshold());
        historyAccepted = history.valid() && hasPreviousFrame && !cameraCut && exposureAccepted;
        frameCamera = camera;
        frameExposure = exposure;

        CloudShadowSettings coverage = settings.coverageField();
        // Render the low-resolution march.
        cmd.enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindFramebuffer(lowTarget)
                .viewport(0, 0, lowWidth, lowHeight)
                .bindShader(marchProgram)
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .setUniformInt(marchProgram, "uSceneDepth", DEPTH_UNIT)
                .setUniformVec2(marchProgram, "uFullExtent", width, height)
                .setUniformVec2(marchProgram, "uLowExtent", lowWidth, lowHeight)
                .setUniformMat4(marchProgram, "uInverseProjection", inverseProjectionScratch)
                .setUniformMat4(marchProgram, "uInverseView", inverseViewScratch)
                .setUniformFloat(marchProgram, "uLayerBase", settings.baseAltitudeMetres())
                .setUniformFloat(marchProgram, "uLayerTop", settings.topAltitudeMetres())
                .setUniformVec3(marchProgram, "uLightDirection", settings.lightDirectionInternal())
                .setUniformVec3(marchProgram, "uLightColor", settings.lightColorInternal())
                .setUniformFloat(marchProgram, "uDensity", settings.density())
                .setUniformFloat(marchProgram, "uAbsorption", settings.absorption())
                .setUniformFloat(marchProgram, "uScattering", settings.scattering())
                .setUniformFloat(marchProgram, "uAnisotropy", settings.anisotropy())
                .setUniformFloat(marchProgram, "uStepSizeMetres", settings.stepSizeMetres())
                .setUniformInt(marchProgram, "uMaximumSteps", settings.maximumSteps())
                .setUniformInt(marchProgram, "uLightSteps", settings.lightSteps())
                .setUniformFloat(marchProgram, "uMaximumLightDistance",
                        settings.maximumLightDistanceMetres())
                .setUniformFloat(marchProgram, "uCloudShadowCoverage", coverage.coverage())
                .setUniformFloat(marchProgram, "uCloudShadowSoftness", coverage.softness())
                .setUniformFloat(marchProgram, "uCloudShadowScale", coverage.scale())
                .setUniformFloat(marchProgram, "uCloudShadowSpeed", coverage.speed())
                .setUniformFloat(marchProgram, "uCloudShadowTime", (float) timeSeconds)
                .bindVertexArray(screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6);

        // Reconstruct to the target extent and write the history candidate.
        cmd.bindFramebuffer(history.writeFramebuffer())
                .viewport(0, 0, width, height)
                .bindShader(reconstructProgram)
                .bindTexture(CLOUD_UNIT, lowTarget.colorAttachment())
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .bindTexture(HISTORY_CLOUD_UNIT, history.readCloudTexture())
                .bindTexture(HISTORY_STATE_UNIT, history.readStateTexture())
                .setUniformInt(reconstructProgram, "uCloud", CLOUD_UNIT)
                .setUniformInt(reconstructProgram, "uSceneDepth", DEPTH_UNIT)
                .setUniformInt(reconstructProgram, "uHistoryCloud", HISTORY_CLOUD_UNIT)
                .setUniformInt(reconstructProgram, "uHistoryState", HISTORY_STATE_UNIT)
                .setUniformMat4(reconstructProgram, "uView", viewScratch)
                .setUniformMat4(reconstructProgram, "uInverseView", inverseViewScratch)
                .setUniformMat4(reconstructProgram, "uInverseProjection", inverseProjectionScratch)
                .setUniformMat4(reconstructProgram, "uPreviousView", previousView)
                .setUniformMat4(reconstructProgram, "uPreviousViewProjection", previousViewProjection)
                .setUniformVec2(reconstructProgram, "uFullExtent", width, height)
                .setUniformVec2(reconstructProgram, "uLowExtent", lowWidth, lowHeight)
                .setUniformFloat(reconstructProgram, "uLayerBase", settings.baseAltitudeMetres())
                .setUniformFloat(reconstructProgram, "uLayerTop", settings.topAltitudeMetres())
                .setUniformFloat(reconstructProgram, "uDepthToleranceMetres",
                        settings.depthToleranceMetres())
                .setUniformFloat(reconstructProgram, "uHistoryBlend", settings.historyBlend())
                .setUniformFloat(reconstructProgram, "uExposure", exposure)
                .setUniformInt(reconstructProgram, "uHistoryValid", historyAccepted ? 1 : 0)
                .bindVertexArray(screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6);

        // Composite S + T * scene into the pass output.
        cmd.bindFramebuffer(outputTarget)
                .viewport(0, 0, width, height)
                .bindShader(compositeProgram)
                .bindTexture(CLOUD_UNIT, history.writeCloudTexture())
                .bindTexture(COLOR_UNIT, colorSource.colorAttachment())
                .setUniformInt(compositeProgram, "uCloud", CLOUD_UNIT)
                .setUniformInt(compositeProgram, "uColorSource", COLOR_UNIT)
                .bindVertexArray(screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true)
                .enableScissor(false);
        frameRecorded = true;
    }

    /**
     * Publishes the history candidate and stores the frame's camera/exposure for
     * the next reprojection. No-op when no frame was recorded since
     * {@link #beginFrame()}.
     */
    public void frameSucceeded() {
        ensureOpen();
        if (!framePrepared) {
            return;
        }
        if (!frameRecorded) {
            history.discardFrame();
            framePrepared = false;
            return;
        }
        history.commitSuccessfulFrame();
        previousView.set(viewScratch);
        previousViewProjection.set(viewProjectionScratch);
        previousCamera = frameCamera;
        previousExposure = frameExposure;
        hasPreviousFrame = true;
        framePrepared = false;
        frameRecorded = false;
        frameCamera = null;
    }

    /** Drops the history candidate and keeps the previous successful frame. */
    public void frameFailed() {
        ensureOpen();
        history.discardFrame();
        framePrepared = false;
        frameRecorded = false;
        historyAccepted = false;
    }

    /** Drops all history; the next recorded frame reconstructs from scratch. */
    public void invalidateHistory() {
        ensureOpen();
        history.invalidate();
        framePrepared = false;
        frameRecorded = false;
        hasPreviousFrame = false;
        previousCamera = null;
        historyAccepted = false;
    }

    /**
     * Explicitly resizes every pass-owned target. The complete candidate set is
     * allocated before the old one is published; a failure keeps the previous
     * targets and invalidated history is published with the new extent.
     */
    public void resize(int newWidth, int newHeight) {
        ensureOpen();
        validateExtent(newWidth, newHeight);
        if (newWidth == width && newHeight == height) {
            return;
        }
        if (Boolean.getBoolean(FAIL_RESIZE_PROPERTY)) {
            System.clearProperty(FAIL_RESIZE_PROPERTY);
            throw new IllegalStateException("injected volumetric cloud resize failure");
        }
        int newLowWidth = settings.lowResolutionExtent(newWidth);
        int newLowHeight = settings.lowResolutionExtent(newHeight);
        Framebuffer candidateOutput = createColorTarget(newWidth, newHeight);
        Framebuffer candidateLow = null;
        CloudHistory.ResizeCandidate candidateHistory = null;
        try {
            candidateLow = createColorTarget(newLowWidth, newLowHeight);
            candidateHistory = history.prepareResize(newWidth, newHeight);
        } catch (RuntimeException failure) {
            closeSuppressed(candidateLow, failure);
            closeSuppressed(candidateOutput, failure);
            throw failure;
        }
        try {
            history.commitResize(candidateHistory);
        } finally {
            candidateHistory.close();
        }
        Framebuffer previousOutput = outputTarget;
        Framebuffer previousLow = lowTarget;
        outputTarget = candidateOutput;
        lowTarget = candidateLow;
        width = newWidth;
        height = newHeight;
        lowWidth = newLowWidth;
        lowHeight = newLowHeight;
        framePrepared = false;
        frameRecorded = false;
        hasPreviousFrame = false;
        previousCamera = null;
        historyAccepted = false;
        RuntimeException failure = null;
        try {
            previousOutput.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            previousLow.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public int id() {
        return closed || outputTarget == null ? 0 : outputTarget.id();
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
        Framebuffer previousOutput = outputTarget;
        Framebuffer previousLow = lowTarget;
        outputTarget = null;
        lowTarget = null;
        width = 0;
        height = 0;
        lowWidth = 0;
        lowHeight = 0;
        framePrepared = false;
        frameRecorded = false;
        historyAccepted = false;
        RuntimeException failure = null;
        failure = close(previousOutput, failure);
        failure = close(previousLow, failure);
        failure = close(history, failure);
        failure = close(screen, failure);
        failure = close(compositeProgram, failure);
        failure = close(reconstructProgram, failure);
        failure = close(marchProgram, failure);
        if (failure != null) {
            throw failure;
        }
    }

    /** Package-private diagnostic accessor for the current resolved cloud texture. */
    int resolvedCloudTexture() {
        return closed ? 0 : history.writeCloudTexture();
    }

    /** Package-private diagnostic accessor for the read history cloud texture. */
    int historyCloudTexture() {
        return closed ? 0 : history.readCloudTexture();
    }

    /**
     * Camera-cut detection shared with the JVM contract test: a translation,
     * rotation or projection change beyond the configured bounds discards the
     * whole history instead of reprojecting it.
     */
    static boolean cameraCut(ExternalCamera previous, ExternalCamera current,
                             VolumetricCloudSettings settings) {
        if (previous == null || current == null) {
            return true;
        }
        Vector3f previousPosition = previous.position();
        Vector3f currentPosition = current.position();
        float translation = previousPosition.distance(currentPosition);
        if (!Float.isFinite(translation) || translation > settings.cameraCutTranslationMetres()) {
            return true;
        }
        Vector3f previousForward = forward(previous);
        Vector3f currentForward = forward(current);
        float cosine = Math.max(-1.0f, Math.min(1.0f, previousForward.dot(currentForward)));
        double angle = Math.toDegrees(Math.acos(cosine));
        if (!Double.isFinite(angle) || angle > settings.cameraCutAngleDegrees()) {
            return true;
        }
        return !projectionCompatible(previous.projection(), current.projection());
    }

    /**
     * Relative exposure-change test shared with the JVM contract test. Both
     * exposures must be positive and finite; a relative change above the
     * threshold is a rejection.
     */
    static boolean exposureAccepted(float previousExposure, float currentExposure,
                                    float threshold) {
        if (!Float.isFinite(previousExposure) || !Float.isFinite(currentExposure)
                || previousExposure <= 0.0f || currentExposure <= 0.0f) {
            return false;
        }
        float relative = Math.abs(currentExposure - previousExposure)
                / Math.max(previousExposure, 1.0e-6f);
        return relative <= threshold;
    }

    private static Vector3f forward(ExternalCamera camera) {
        Matrix4f inverse = new Matrix4f(camera.getViewMatrix(new Matrix4f())).invert();
        Vector3f forward = new Vector3f(-inverse.m20(), -inverse.m21(), -inverse.m22());
        float length = forward.length();
        if (!(length > DIRECTION_EPSILON) || !Float.isFinite(length)) {
            throw new IllegalArgumentException("cloud camera forward must be finite and non-zero");
        }
        return forward.mul(1.0f / length);
    }

    private static boolean projectionCompatible(Matrix4f previous, Matrix4f current) {
        return close(previous.m00(), current.m00()) && close(previous.m11(), current.m11())
                && close(previous.m22(), current.m22()) && close(previous.m23(), current.m23());
    }

    private static boolean close(float left, float right) {
        float scale = Math.max(1.0f, Math.max(Math.abs(left), Math.abs(right)));
        return Math.abs(left - right) <= PROJECTION_RELATIVE_TOLERANCE * scale;
    }

    private void prepareMatrices(ExternalCamera camera) {
        camera.getViewMatrix(viewScratch);
        camera.projection(projectionScratch);
        inverseProjectionScratch.set(projectionScratch).invert();
        inverseViewScratch.set(viewScratch).invert();
        viewProjectionScratch.set(projectionScratch).mul(viewScratch);
        if (!inverseProjectionScratch.isFinite() || !inverseViewScratch.isFinite()
                || !viewProjectionScratch.isFinite()
                || Math.abs(inverseProjectionScratch.determinant()) <= 1.0e-8f
                || Math.abs(inverseViewScratch.determinant()) <= 1.0e-8f) {
            throw new IllegalArgumentException(
                    "volumetric clouds require finite invertible camera matrices");
        }
    }

    private static Framebuffer createColorTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(GL_RGBA16F)
                .build());
    }

    private static void validateExtent(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAXIMUM_EXTENT || height > MAXIMUM_EXTENT) {
            throw new IllegalArgumentException("volumetric cloud target extent must be in [1, "
                    + MAXIMUM_EXTENT + "]");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("volumetric cloud pass is closed");
        }
    }

    /** @return the packaged vertex stage; no variant is generated for this pass */
    static String vertexSource() {
        return resource(VERTEX_RESOURCE);
    }

    /** @return the packaged low-resolution march stage */
    static String marchSource() {
        return resource(MARCH_RESOURCE);
    }

    /** @return the packaged reconstruction stage */
    static String reconstructSource() {
        return resource(RECONSTRUCT_RESOURCE);
    }

    /** @return the packaged composite stage */
    static String compositeSource() {
        return resource(COMPOSITE_RESOURCE);
    }

    static String resource(String path) {
        try (var stream = VolumetricCloudPass.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing volumetric cloud shader " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot read volumetric cloud shader " + path, failure);
        }
    }

    private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            if (failure == null) {
                return closeFailure instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(closeFailure);
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static void closeSuppressed(AutoCloseable resource, RuntimeException primary) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
