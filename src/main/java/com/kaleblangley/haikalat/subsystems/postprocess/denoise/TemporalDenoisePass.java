package com.kaleblangley.haikalat.subsystems.postprocess.denoise;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalDenoiseSettings;
import org.joml.Matrix4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Temporal denoiser for one real noisy signal, bound first to the
 * screen-space reflection colour.
 *
 * <p>The pass reconstructs the current surface, reprojects history through the
 * previous successful stable view-projection (or the frozen velocity channel),
 * rejects disoccluded, depth/normal-inconsistent and luminance-shocked
 * samples, clamps the surviving history to the current depth-matched
 * neighbourhood and its luminance moments, and finally blends the current
 * sample into the history.  One MRT draw writes the resolved colour, the
 * current depth/normal state and the updated luminance moments; the pass
 * owns the double-buffered history and never writes the shared scene depth or
 * reads pixels back.</p>
 *
 * <p>The resolved colour is the bounded reflection <em>contribution</em>, not
 * scene colour: RGB is the contribution and alpha is the usable hit
 * confidence. A zero-confidence pixel has an exact zero contribution and
 * rejects all history. {@link #recordComposite(CommandBuffer, int, int, int,
 * int)} then applies the same {@code base + contribution} equation as the
 * spatial SSR pass, so base-colour regions stay byte-identical and the
 * denoiser never smears scene detail.</p>
 *
 * <p>History advances only through {@link #frameSucceeded()}.  A failed frame
 * keeps the previous read slot; resize allocates a complete candidate first.
 * No other effect shares this history: a second signal must create its own
 * pass.</p>
 */
public final class TemporalDenoisePass implements GlResource {
    private final TemporalDenoiseSettings settings;
    private final ShaderProgram program;
    private final ShaderProgram compositeProgram;
    private final ScreenQuad quad;
    private final TemporalDenoiseHistory history;
    private Matrix4f previousViewProjection;
    private Matrix4f previousInverseProjection;
    private float previousJitterUvX;
    private float previousJitterUvY;
    private Matrix4f pendingViewProjection;
    private Matrix4f pendingInverseProjection;
    private float pendingJitterUvX;
    private float pendingJitterUvY;
    private boolean closed;

    public TemporalDenoisePass(TemporalDenoiseSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException(
                    "TemporalDenoisePass requires active temporal denoising");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("temporal denoise extent must be positive");
        }
        ShaderProgram createdProgram = null;
        ShaderProgram createdCompositeProgram = null;
        ScreenQuad createdQuad = null;
        TemporalDenoiseHistory createdHistory = null;
        try {
            createdProgram = ShaderProgram.fromResource(TemporalDenoisePass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/temporal-denoise.frag");
            createdCompositeProgram = ShaderProgram.fromResource(TemporalDenoisePass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/temporal-denoise-composite.frag");
            createdQuad = new ScreenQuad();
            createdHistory = new TemporalDenoiseHistory(width, height);
        } catch (RuntimeException failure) {
            closeSuppressed(createdHistory, failure);
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdCompositeProgram, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        compositeProgram = createdCompositeProgram;
        quad = createdQuad;
        history = createdHistory;
    }

    public TemporalDenoiseSettings settings() {
        return settings;
    }

    /** Starts the temporal transaction for this frame. */
    public void beginFrame() {
        ensureOpen();
        if (history.prepared()) {
            throw new IllegalStateException("temporal denoise frame has already begun");
        }
        history.prepareFrame();
    }

    /**
     * Records the denoise resolve into the pass-owned history write slot.
     * The caller must execute the command buffer and then commit or fail the
     * frame.  The output colour is available through
     * {@link #writeColorTexture()} until the frame commits.
     */
    public CommandBuffer record(CommandBuffer cmd, TemporalDenoiseInputs inputs) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inputs, "inputs");
        if (!history.prepared()) {
            throw new IllegalStateException(
                    "temporal denoise requires beginFrame() before record()");
        }
        Framebuffer target = history.writeFramebuffer();
        boolean useVelocity = inputs.usesVelocity();
        boolean historyValid = historyValid();
        int fallbackTexture = inputs.depthTexture();
        Matrix4f previousViewProjectionForShader = historyValid
                ? previousViewProjection : inputs.stableViewProjection();
        Matrix4f previousInverseProjectionForShader = historyValid
                ? previousInverseProjection : inputs.inverseProjection();

        cmd.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, inputs.noisyColorTexture())
                .bindTexture(1, inputs.depthTexture())
                .bindTexture(2, inputs.normalTexture())
                .bindTexture(3, useVelocity ? inputs.velocityTexture() : fallbackTexture)
                .bindTexture(4, useVelocity ? inputs.previousDepthTexture() : fallbackTexture)
                .bindTexture(5, history.readColorTexture())
                .bindTexture(6, history.readStateTexture())
                .bindTexture(7, history.readMomentsTexture())
                .setUniformInt(program, "uNoisyColor", 0)
                .setUniformInt(program, "uDepth", 1)
                .setUniformInt(program, "uNormal", 2)
                .setUniformInt(program, "uVelocity", 3)
                .setUniformInt(program, "uPreviousDepth", 4)
                .setUniformInt(program, "uHistoryColor", 5)
                .setUniformInt(program, "uHistoryState", 6)
                .setUniformInt(program, "uHistoryMoments", 7)
                .setUniformVec2(program, "uExtent", target.width(), target.height())
                .setUniformMat4(program, "uInverseProjection", inputs.inverseProjection())
                .setUniformMat4(program, "uInverseView", inputs.inverseView())
                .setUniformMat4(program, "uPreviousViewProjection",
                        previousViewProjectionForShader)
                .setUniformMat4(program, "uPreviousInverseProjection",
                        previousInverseProjectionForShader)
                .setUniformInt(program, "uHistoryValid", historyValid ? 1 : 0)
                .setUniformInt(program, "uUseVelocity", useVelocity ? 1 : 0)
                .setUniformVec2(program, "uCurrentJitter", inputs.jitterUvX(),
                        inputs.jitterUvY())
                .setUniformVec2(program, "uPreviousJitter",
                        historyValid ? previousJitterUvX : 0.0f,
                        historyValid ? previousJitterUvY : 0.0f)
                .setUniformInt(program, "uNeighborhoodRadius",
                        settings.neighborhoodClampRadius())
                .setUniformFloat(program, "uHistoryBlend", settings.historyWeight())
                .setUniformFloat(program, "uDepthRejectionThreshold",
                        settings.depthRejectionThreshold())
                .setUniformFloat(program, "uNormalRejectionThreshold",
                        settings.normalRejectionThreshold())
                .setUniformFloat(program, "uReactiveThreshold",
                        settings.reactiveThreshold())
                .setUniformFloat(program, "uMaxReprojectionPixels",
                        settings.maxReprojectionPixels())
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);

        pendingViewProjection = new Matrix4f(inputs.stableViewProjection());
        pendingInverseProjection = new Matrix4f(inputs.inverseProjection());
        pendingJitterUvX = inputs.jitterUvX();
        pendingJitterUvY = inputs.jitterUvY();
        return cmd;
    }

    /**
     * Composites the resolved reflection contribution over the base colour with
     * the same equation as the spatial SSR pass: {@code base + contribution}.
     *
     * <p>Both inputs are point-sampled and a zero-confidence contribution is an
     * exact zero, so base-colour regions are reproduced byte-for-byte. The
     * output alpha is the opaque {@code 1.0} contract downstream effects
     * already expect from the legacy SSR output. The caller records this into
     * a graph colour target; the pass binds no framebuffer itself.</p>
     *
     * @param cmd               command buffer being recorded
     * @param baseColorTexture  colour produced before the reflection signal
     * @param contributionTexture resolved contribution/confidence texture
     * @param width             target width in pixels
     * @param height            target height in pixels
     */
    public CommandBuffer recordComposite(CommandBuffer cmd, int baseColorTexture,
                                         int contributionTexture, int width, int height) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        if (baseColorTexture <= 0 || contributionTexture <= 0) {
            throw new IllegalArgumentException(
                    "reflection composite requires base colour and contribution textures");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "reflection composite viewport must be positive");
        }
        return cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(compositeProgram)
                .bindTexture(0, baseColorTexture)
                .bindTexture(1, contributionTexture)
                .setUniformInt(compositeProgram, "uBaseColor", 0)
                .setUniformInt(compositeProgram, "uContribution", 1)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    /** Commits this frame's write slot and stores its camera pose for reprojection. */
    public void frameSucceeded() {
        history.commitSuccessfulFrame();
        if (pendingViewProjection != null && pendingInverseProjection != null) {
            previousViewProjection = pendingViewProjection;
            previousInverseProjection = pendingInverseProjection;
            previousJitterUvX = pendingJitterUvX;
            previousJitterUvY = pendingJitterUvY;
        }
        pendingViewProjection = null;
        pendingInverseProjection = null;
    }

    /** Discards the frame candidate; the previous read slot remains active. */
    public void frameFailed() {
        pendingViewProjection = null;
        pendingInverseProjection = null;
        history.discardFrame();
    }

    /** Stops trusting the previous history, for example after a camera cut. */
    public void invalidate() {
        previousViewProjection = null;
        previousInverseProjection = null;
        previousJitterUvX = 0.0f;
        previousJitterUvY = 0.0f;
        pendingViewProjection = null;
        pendingInverseProjection = null;
        history.invalidate();
    }

    public boolean historyValid() {
        return !closed && history.valid()
                && previousViewProjection != null && previousInverseProjection != null;
    }

    /** Package-private diagnostic: previous jitter stored for the next resolve. */
    float previousJitterUvXForTest() {
        return previousJitterUvX;
    }

    /** Package-private diagnostic: previous jitter stored for the next resolve. */
    float previousJitterUvYForTest() {
        return previousJitterUvY;
    }

    /** @return the committed colour produced by the last successful frame */
    public Framebuffer readFramebuffer() {
        ensureOpen();
        return history.readFramebuffer();
    }

    /** @return this frame's candidate target; valid until the next beginFrame */
    public Framebuffer writeFramebuffer() {
        ensureOpen();
        return history.writeFramebuffer();
    }

    /** @return committed denoise colour for post-commit consumers */
    public int readColorTexture() {
        ensureOpen();
        return history.readColorTexture();
    }

    /** @return this frame's candidate denoise colour for consumers in the same graph */
    public int writeColorTexture() {
        ensureOpen();
        return history.writeColorTexture();
    }

    /** @return this frame's candidate depth/normal state texture */
    public int writeStateTexture() {
        ensureOpen();
        return history.writeStateTexture();
    }

    /** @return this frame's candidate luminance moments texture */
    public int writeMomentsTexture() {
        ensureOpen();
        return history.writeMomentsTexture();
    }

    public ResizeCandidate prepareResize(int width, int height) {
        ensureOpen();
        return new ResizeCandidate(this, history.prepareResize(width, height));
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
        private final TemporalDenoisePass owner;
        private final TemporalDenoiseHistory.ResizeCandidate historyCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(TemporalDenoisePass owner,
                                TemporalDenoiseHistory.ResizeCandidate historyCandidate) {
            this.owner = owner;
            this.historyCandidate = historyCandidate;
        }

        private void commitInto(TemporalDenoisePass expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "temporal-denoise resize candidate belongs to another pass");
            }
            if (closed) {
                throw new IllegalStateException("temporal-denoise resize candidate is closed");
            }
            if (committed) {
                throw new IllegalStateException(
                        "temporal-denoise resize candidate already committed");
            }
            owner.history.commitResize(historyCandidate);
            // Any resize boundary invalidates the camera pose history even when
            // the extent is unchanged; this is conservative and keeps the
            // previous matrices from being paired with a different signal.
            owner.previousViewProjection = null;
            owner.previousInverseProjection = null;
            owner.previousJitterUvX = 0.0f;
            owner.previousJitterUvY = 0.0f;
            owner.pendingViewProjection = null;
            owner.pendingInverseProjection = null;
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            historyCandidate.close();
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
        try {
            history.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            quad.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            compositeProgram.close();
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

    private void ensureOpen() {
        if (closed) throw new GlException("TemporalDenoisePass is closed");
    }

    private static void closeSuppressed(AutoCloseable resource, RuntimeException primary) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
