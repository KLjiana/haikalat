package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import org.joml.Matrix4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Temporal upscaler for an independently rendered low-resolution colour signal.
 *
 * <p>One frame records a resolve draw and, when sharpening is enabled, one
 * bounded sharpen draw:</p>
 * <ol>
 *   <li>the resolve reconstructs the current low-resolution surface from the
 *       jittered inverse projection, reprojects it with the previous successful
 *       stable view-projection (or the frozen velocity/previous-depth pair),
 *       rejects background, disoccluded, depth/exposure-inconsistent and
 *       luminance-shocked history, clamps the surviving history to the current
 *       depth-matched input neighbourhood, blends and writes the resolved
 *       colour plus its depth/exposure state into the double-buffered history
 *       write slot;</li>
 *   <li>the sharpen draw applies a local-contrast-limited unsharp filter to the
 *       resolved colour at the output extent, bypassing already composited UI
 *       pixels.</li>
 * </ol>
 *
 * <p>The pass owns its programs, the {@link ScreenQuad}, both history slots and
 * the optional sharpen target.  It never writes the shared scene depth, never
 * reads pixels back and never samples its own write target.  History advances
 * only through {@link #frameSucceeded()}; a failed frame or a candidate-first
 * resize keeps the previous read slot.  A resize boundary also forgets the
 * previous camera pose so stale matrices cannot be paired with a new extent.</p>
 */
public final class TemporalUpscalingPass implements GlResource {
    private final TemporalUpscalingSettings settings;
    private final ShaderProgram program;
    private final ShaderProgram sharpenProgram;
    private final ScreenQuad quad;
    private final TemporalUpscalingHistory history;
    private Matrix4f previousViewProjection;
    private Matrix4f previousInverseProjection;
    private Matrix4f pendingViewProjection;
    private Matrix4f pendingInverseProjection;
    private boolean frameRecorded;
    private boolean closed;

    public TemporalUpscalingPass(TemporalUpscalingSettings settings, int width, int height) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException(
                    "TemporalUpscalingPass requires active temporal upscaling");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("temporal upscaling extent must be positive");
        }
        ShaderProgram createdProgram = null;
        ShaderProgram createdSharpenProgram = null;
        ScreenQuad createdQuad = null;
        TemporalUpscalingHistory createdHistory = null;
        try {
            createdProgram = ShaderProgram.fromResource(TemporalUpscalingPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/temporal-upscale.frag");
            if (settings.sharpness() > 0.0f) {
                createdSharpenProgram = ShaderProgram.fromResource(TemporalUpscalingPass.class,
                        "/shaders/postprocess/screen-quad.vert",
                        "/shaders/postprocess/temporal-upscale-sharpen.frag");
            }
            createdQuad = new ScreenQuad();
            createdHistory = new TemporalUpscalingHistory(width, height,
                    settings.sharpness() > 0.0f);
        } catch (RuntimeException failure) {
            closeSuppressed(createdHistory, failure);
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdSharpenProgram, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        sharpenProgram = createdSharpenProgram;
        quad = createdQuad;
        history = createdHistory;
    }

    public TemporalUpscalingSettings settings() {
        return settings;
    }

    /** @return true when the sharpen draw is part of this pass */
    public boolean sharpening() {
        return sharpenProgram != null;
    }

    /** Starts the temporal transaction for this frame. */
    public void beginFrame() {
        ensureOpen();
        if (history.prepared()) {
            throw new IllegalStateException("temporal upscaling frame has already begun");
        }
        frameRecorded = false;
        history.prepareFrame();
    }

    /**
     * Records the upscale resolve into the pass-owned history write slot and,
     * when sharpening is enabled, the sharpen draw into the pass-owned output
     * target.  The caller must execute the command buffer and then commit or
     * fail the frame.
     */
    public CommandBuffer record(CommandBuffer cmd, TemporalUpscalingInputs inputs) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inputs, "inputs");
        if (!history.prepared()) {
            throw new IllegalStateException(
                    "temporal upscaling requires beginFrame() before record()");
        }
        if (frameRecorded) {
            throw new IllegalStateException(
                    "temporal upscaling has already recorded this frame");
        }
        Framebuffer target = history.writeFramebuffer();
        boolean useVelocity = inputs.usesVelocity();
        boolean historyValid = historyValid();
        int fallback = inputs.depthTexture();
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
                .bindTexture(0, inputs.colorTexture())
                .bindTexture(1, inputs.depthTexture())
                .bindTexture(2, useVelocity ? inputs.velocityTexture() : fallback)
                .bindTexture(3, useVelocity ? inputs.previousDepthTexture() : fallback)
                .bindTexture(4, inputs.hasReactiveMask() ? inputs.reactiveTexture() : fallback)
                .bindTexture(5, inputs.hasUiMask() ? inputs.uiMaskTexture() : fallback)
                .bindTexture(6, history.readColorTexture())
                .bindTexture(7, history.readStateTexture())
                .setUniformInt(program, "uColor", 0)
                .setUniformInt(program, "uDepth", 1)
                .setUniformInt(program, "uVelocity", 2)
                .setUniformInt(program, "uPreviousDepth", 3)
                .setUniformInt(program, "uReactive", 4)
                .setUniformInt(program, "uUiMask", 5)
                .setUniformInt(program, "uHistoryColor", 6)
                .setUniformInt(program, "uHistoryState", 7)
                .setUniformVec2(program, "uInputExtent",
                        inputs.inputWidth(), inputs.inputHeight())
                .setUniformVec2(program, "uOutputExtent", target.width(), target.height())
                .setUniformMat4(program, "uInverseProjection", inputs.inverseProjection())
                .setUniformMat4(program, "uInverseView", inputs.inverseView())
                .setUniformMat4(program, "uPreviousViewProjection",
                        previousViewProjectionForShader)
                .setUniformMat4(program, "uPreviousInverseProjection",
                        previousInverseProjectionForShader)
                .setUniformInt(program, "uHistoryValid", historyValid ? 1 : 0)
                .setUniformInt(program, "uUseVelocity", useVelocity ? 1 : 0)
                .setUniformInt(program, "uHasReactive", inputs.hasReactiveMask() ? 1 : 0)
                .setUniformInt(program, "uHasUiMask", inputs.hasUiMask() ? 1 : 0)
                .setUniformVec2(program, "uCurrentJitter",
                        inputs.jitterUvX(), inputs.jitterUvY())
                .setUniformInt(program, "uNeighborhoodRadius",
                        settings.quality().neighborhoodRadius())
                .setUniformFloat(program, "uHistoryBlend", settings.historyWeight())
                .setUniformFloat(program, "uReactiveThreshold", settings.reactiveThreshold())
                .setUniformFloat(program, "uExposureThreshold", settings.exposureThreshold())
                .setUniformFloat(program, "uExposure", inputs.exposure())
                .setUniformFloat(program, "uMaxReprojectionPixels",
                        settings.maxReprojectionPixels())
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);

        if (sharpenProgram != null) {
            Framebuffer sharpenTarget = history.sharpenFramebuffer();
            cmd.bindFramebuffer(sharpenTarget)
                    .viewport(0, 0, sharpenTarget.width(), sharpenTarget.height())
                    .enableBlend(false)
                    .enableDepthTest(false)
                    .enableCullFace(false)
                    .enableFramebufferSrgb(false)
                    .bindShader(sharpenProgram)
                    .bindTexture(0, history.writeColorTexture())
                    .bindTexture(1, inputs.hasUiMask() ? inputs.uiMaskTexture() : fallback)
                    .setUniformInt(sharpenProgram, "uResolved", 0)
                    .setUniformInt(sharpenProgram, "uUiMask", 1)
                    .setUniformVec2(sharpenProgram, "uOutputExtent",
                            sharpenTarget.width(), sharpenTarget.height())
                    .setUniformVec2(sharpenProgram, "uInputExtent",
                            inputs.inputWidth(), inputs.inputHeight())
                    .setUniformInt(sharpenProgram, "uHasUiMask",
                            inputs.hasUiMask() ? 1 : 0)
                    .setUniformFloat(sharpenProgram, "uSharpness", settings.sharpness())
                    .bindVertexArray(quad.id())
                    .drawArrays(GL_TRIANGLES, 0, 6)
                    .enableDepthTest(true);
        }

        pendingViewProjection = new Matrix4f(inputs.stableViewProjection());
        // The stored inverse projection is the current frame's (possibly
        // jittered) inverse.  Next frame the expected previous view depth is
        // recovered by applying it to a stable previous clip position; a
        // clip-space XY jitter translation does not change clip z/w, so the
        // recovered depth is exact even though the matrix carries the jitter.
        pendingInverseProjection = new Matrix4f(inputs.inverseProjection());
        frameRecorded = true;
        return cmd;
    }

    /** Commits this frame's write slot and stores its camera pose for reprojection. */
    public void frameSucceeded() {
        if (!frameRecorded) {
            pendingViewProjection = null;
            pendingInverseProjection = null;
            history.discardFrame();
            return;
        }
        history.commitSuccessfulFrame();
        previousViewProjection = pendingViewProjection;
        previousInverseProjection = pendingInverseProjection;
        pendingViewProjection = null;
        pendingInverseProjection = null;
        frameRecorded = false;
    }

    /** Discards the frame candidate; the previous read slot remains active. */
    public void frameFailed() {
        pendingViewProjection = null;
        pendingInverseProjection = null;
        frameRecorded = false;
        history.discardFrame();
    }

    /** Stops trusting the previous history, for example after a camera cut. */
    public void invalidate() {
        previousViewProjection = null;
        previousInverseProjection = null;
        pendingViewProjection = null;
        pendingInverseProjection = null;
        frameRecorded = false;
        history.invalidate();
    }

    public boolean historyValid() {
        return !closed && history.valid()
                && previousViewProjection != null && previousInverseProjection != null;
    }

    /** @return the committed resolved colour produced by the last successful frame */
    public Framebuffer readFramebuffer() {
        ensureOpen();
        return history.readFramebuffer();
    }

    /** @return this frame's candidate resolve target; valid until the next beginFrame */
    public Framebuffer writeFramebuffer() {
        ensureOpen();
        return history.writeFramebuffer();
    }

    /** @return the committed resolved colour before sharpening */
    public int readColorTexture() {
        ensureOpen();
        return history.readColorTexture();
    }

    /** @return this frame's candidate resolved colour before sharpening */
    public int writeColorTexture() {
        ensureOpen();
        return history.writeColorTexture();
    }

    /** @return this frame's candidate depth/exposure state texture */
    public int writeStateTexture() {
        ensureOpen();
        return history.writeStateTexture();
    }

    /**
     * @return the texture downstream consumers must sample after this frame:
     *         the sharpen target when sharpening is enabled, otherwise the
     *         resolved history write colour
     */
    public int outputColorTexture() {
        ensureOpen();
        return history.outputColorTexture();
    }

    /**
     * @return the framebuffer holding {@link #outputColorTexture()} for this
     *         candidate frame, valid until the next {@code beginFrame()}
     */
    public Framebuffer outputFramebuffer() {
        ensureOpen();
        return history.sharpenFramebuffer() != null
                ? history.sharpenFramebuffer() : history.writeFramebuffer();
    }

    /**
     * @return the final output record for the last recorded frame.  The
     *         resolved history colour is rolled back on {@link #frameFailed()},
     *         but the optional single-buffered sharpen target is written during
     *         recording and cannot be rolled back; read this only after
     *         {@link #frameSucceeded()}.
     */
    public Framebuffer readOutputFramebuffer() {
        ensureOpen();
        return history.readOutputFramebuffer();
    }

    /**
     * @return the final output texture recorded for the last frame; see
     *         {@link #readOutputFramebuffer()} for the failure semantics
     */
    public int readOutputColorTexture() {
        ensureOpen();
        return history.readOutputColorTexture();
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
        private final TemporalUpscalingPass owner;
        private final TemporalUpscalingHistory.ResizeCandidate historyCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(TemporalUpscalingPass owner,
                                TemporalUpscalingHistory.ResizeCandidate historyCandidate) {
            this.owner = owner;
            this.historyCandidate = historyCandidate;
        }

        private void commitInto(TemporalUpscalingPass expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException(
                        "temporal-upscaling resize candidate belongs to another pass");
            }
            if (closed) {
                throw new IllegalStateException("temporal-upscaling resize candidate is closed");
            }
            if (committed) {
                throw new IllegalStateException(
                        "temporal-upscaling resize candidate already committed");
            }
            owner.history.commitResize(historyCandidate);
            // Any resize boundary invalidates the camera pose history even when
            // the extent is unchanged; this keeps the previous matrices from
            // being paired with a different signal.
            owner.previousViewProjection = null;
            owner.previousInverseProjection = null;
            owner.pendingViewProjection = null;
            owner.pendingInverseProjection = null;
            owner.frameRecorded = false;
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
        if (sharpenProgram != null) {
            try {
                sharpenProgram.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
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
        if (closed) throw new GlException("TemporalUpscalingPass is closed");
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
