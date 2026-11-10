package com.kaleblangley.haikalat.subsystems.postprocess.temporal;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Owner of the native temporal resolve: TAA program, double-buffered history and
 * the last successfully published camera pose.
 *
 * <p>The borrowed surface textures are supplied by the assembler through
 * {@link SurfaceInputs}; this chain owns the resolve program and history
 * storage only.  History advances exclusively through
 * {@link #frameSucceeded()}, so a failed frame keeps the previous read slot.</p>
 */
public final class TemporalChain implements AutoCloseable {
    private final TemporalAccumulationPass pass;
    private final TaaHistory history;
    private final RenderFormat colorFormat;
    private final TaaSettings settings = TaaSettings.defaults();
    private TemporalFramePose pendingPose;
    private TemporalFramePose lastSuccessfulPose;
    private TaaInvalidationReason pendingInvalidation = TaaInvalidationReason.FIRST_FRAME;
    private TaaInvalidationReason completedInvalidation = TaaInvalidationReason.NONE;

    public TemporalChain(int width, int height, RenderFormat colorFormat) {
        this.colorFormat = Objects.requireNonNull(colorFormat, "colorFormat");
        this.history = new TaaHistory(width, height, colorFormat);
        TemporalAccumulationPass created = null;
        try {
            created = new TemporalAccumulationPass();
        } catch (RuntimeException failure) {
            history.close();
            throw failure;
        }
        this.pass = created;
    }

    public static RenderFormat historyFormat(boolean hdrEnabled) {
        return hdrEnabled ? RenderFormat.RGBA16F : RenderFormat.SRGB8_ALPHA8;
    }

    public boolean compatibleStorage(int width, int height, RenderFormat format) {
        var read = history.readFramebuffer();
        return colorFormat == format && read.width() == width && read.height() == height;
    }

    public static List<SurfaceInputs.Channel> channels(boolean reactive) {
        List<SurfaceInputs.Channel> channels = new ArrayList<>(List.of(
                SurfaceInputs.Channel.DEPTH,
                SurfaceInputs.Channel.VELOCITY,
                SurfaceInputs.Channel.PREVIOUS_SURFACE_DEPTH,
                SurfaceInputs.Channel.VALIDITY));
        if (reactive) {
            channels.add(SurfaceInputs.Channel.REACTIVE);
        }
        return List.copyOf(channels);
    }

    /** Validates the dependency edges before the whole graph is committed. */
    public void validateDependencies(SurfaceInputs surface, boolean reactive) {
        Objects.requireNonNull(surface, "surface");
        for (SurfaceInputs.Channel channel : channels(reactive)) {
            surface.validateDependency(PostProcessTargets.TAA_PASS, require(surface, channel));
        }
    }

    public void beginFrame(TemporalFramePose pose) {
        if (pendingPose != null) {
            throw new IllegalStateException("temporal chain frame has already begun");
        }
        pendingPose = Objects.requireNonNull(pose, "pose");
        history.prepareFrame();
    }

    public void addPass(RenderGraph graph, String colorProducer, String colorTexture,
                        SurfaceInputs surface, boolean reactive) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(surface, "surface");
        var taaPass = graph.addPass(PostProcessTargets.TAA_PASS)
                .writeToExternalTarget().noClear().dependsOn(colorProducer);
        var producers = new LinkedHashSet<String>();
        producers.add(colorProducer);
        for (SurfaceInputs.Channel channel : channels(reactive)) {
            var input = require(surface, channel);
            if (producers.add(input.producerPass())) {
                taaPass.dependsOn(input.producerPass());
            }
        }
        taaPass.execute((res, cmd) -> recordResolve(res, cmd, colorTexture, surface, reactive));
    }

    private void recordResolve(PassResources res, CommandBuffer cmd, String colorTexture,
                               SurfaceInputs surface, boolean reactive) {
        TemporalFramePose current = requirePendingPose();
        Framebuffer candidate = history.writeFramebuffer();
        boolean historyValid = history.valid() && lastSuccessfulPose != null;
        float previousJitterUvX = historyValid ? lastSuccessfulPose.jitterUvX() : 0.0f;
        float previousJitterUvY = historyValid ? lastSuccessfulPose.jitterUvY() : 0.0f;
        TaaResolveInputs inputs = new TaaResolveInputs(
                res.colorAttachment(colorTexture),
                historyValid ? history.readColorTexture() : 0,
                historyValid ? history.readDepthTexture() : 0,
                surface.texture(res, require(surface, SurfaceInputs.Channel.DEPTH)),
                surface.texture(res, require(surface, SurfaceInputs.Channel.VELOCITY)),
                surface.texture(res, require(surface, SurfaceInputs.Channel.PREVIOUS_SURFACE_DEPTH)),
                surface.texture(res, require(surface, SurfaceInputs.Channel.VALIDITY)),
                reactive ? surface.texture(res, require(surface, SurfaceInputs.Channel.REACTIVE)) : 0,
                historyValid,
                candidate.width(), candidate.height(),
                current.jitterUvX(), current.jitterUvY(),
                previousJitterUvX, previousJitterUvY,
                current.inverseJitteredProjection(),
                historyValid ? lastSuccessfulPose.stableViewProjection() : current.stableViewProjection(),
                current.inverseView(),
                settings);
        pass.recordResolve(cmd, candidate, inputs);
    }

    /**
     * Publishes this frame's history write colour as a borrowed logical texture
     * for passes that run after the TAA resolve.
     */
    public void synchronizeImports(RenderGraph graph) {
        int texture = history.writeColorTexture();
        Framebuffer write = history.writeFramebuffer();
        graph.importExternalColor(PostProcessTargets.TAA_RESOLVED_COLOR,
                ExternalAttachment.borrowedColor(texture, colorFormat, write.width(), write.height()));
    }

    public Framebuffer writeFramebuffer() {
        return history.writeFramebuffer();
    }

    public void frameSucceeded() {
        TemporalFramePose pose = pendingPose;
        pendingPose = null;
        history.commitSuccessfulFrame();
        if (pose != null) {
            lastSuccessfulPose = pose;
        }
        completedInvalidation = pendingInvalidation;
        pendingInvalidation = TaaInvalidationReason.NONE;
    }

    public void frameFailed() {
        pendingPose = null;
        history.discardFrame();
    }

    public void invalidate(TaaInvalidationReason reason) {
        history.invalidate();
        pendingInvalidation = Objects.requireNonNull(reason, "reason");
        lastSuccessfulPose = null;
    }

    public String completedInvalidationReason() {
        return completedInvalidation == TaaInvalidationReason.NONE
                ? "" : "TAA_" + completedInvalidation.name();
    }

    /** Test/diagnostic access to the history owner. */
    public TaaHistory historyForTest() {
        return history;
    }

    public TaaHistory.ResizeCandidate prepareResize(int width, int height) {
        return history.prepareResize(width, height);
    }

    public void commitResize(TaaHistory.ResizeCandidate candidate) {
        history.commitResize(candidate);
        pendingInvalidation = TaaInvalidationReason.RESIZE;
    }

    public void resize(int width, int height) {
        TaaHistory.ResizeCandidate candidate = prepareResize(width, height);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            history.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            pass.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private TemporalFramePose requirePendingPose() {
        if (pendingPose == null) {
            throw new IllegalStateException("temporal resolve requires a started frame pose");
        }
        return pendingPose;
    }

    private static SurfaceInputs.Input require(SurfaceInputs surface, SurfaceInputs.Channel channel) {
        SurfaceInputs.Input input = surface.single(channel);
        if (input == null) {
            throw new IllegalStateException("temporal chain requires a " + channel
                    + " surface input for " + PostProcessTargets.TAA_PASS);
        }
        return input;
    }
}
