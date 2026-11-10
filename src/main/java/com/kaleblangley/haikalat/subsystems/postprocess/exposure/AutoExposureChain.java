package com.kaleblangley.haikalat.subsystems.postprocess.exposure;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.AutoExposureSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;

import java.util.Objects;

/**
 * Owner of the GPU auto-exposure reduction chain and its 1x1 adaptation
 * history.  The frame is published only through {@link #frameSucceeded()};
 * {@link #frameFailed()} keeps the previous valid exposure.
 */
public final class AutoExposureChain implements AutoCloseable {
    public static final int RELATIVE_PASS_COUNT = 13;
    public static final int REDUCTION_PASS_COUNT = RELATIVE_PASS_COUNT + 1;

    private final AutoExposurePass pass = new AutoExposurePass();

    /** Declares the luminance, reduction and adaptation passes. */
    public Output addPasses(RenderGraph graph, String sourceTexture, String sourcePass,
                            float manualExposure, AutoExposureSettings settings, float deltaSeconds) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(settings, "settings");
        graph.addPass(PostProcessTargets.AUTO_EXPOSURE_LUMINANCE_PASS)
                .createColor(PostProcessTargets.AUTO_EXPOSURE_LUMINANCE, RenderFormat.R16F)
                .noClear()
                .dependsOn(sourcePass)
                .execute((res, cmd) -> {
                    Framebuffer source = res.framebufferOfPass(sourcePass);
                    if (source != null) {
                        pass.recordLuminance(cmd, res.colorAttachment(sourceTexture),
                                source.width(), source.height());
                    }
                });

        String previousTexture = PostProcessTargets.AUTO_EXPOSURE_LUMINANCE;
        String previousPass = PostProcessTargets.AUTO_EXPOSURE_LUMINANCE_PASS;
        for (int level = 0; level < REDUCTION_PASS_COUNT; level++) {
            final String inputTexture = previousTexture;
            final String inputPass = previousPass;
            final boolean inputHasWeights = level > 0;
            String passName = PostProcessTargets.autoExposureReducePass(level);
            String outputTexture = PostProcessTargets.autoExposureReduceColor(level);
            RenderGraph.PassBuilder reduction = graph.addPass(passName)
                    .createColor(outputTexture, RenderFormat.RG32F)
                    .noClear()
                    .dependsOn(inputPass);
            if (level < RELATIVE_PASS_COUNT) {
                reduction.relativeSize(relativeScale(level));
            } else {
                reduction.fixedSize(1, 1);
            }
            reduction.execute((res, cmd) -> {
                Framebuffer input = res.framebufferOfPass(inputPass);
                Framebuffer output = res.currentTarget();
                if (input != null && output != null) {
                    pass.recordReduction(cmd, res.colorAttachment(inputTexture),
                            input.width(), input.height(), output.width(), output.height(),
                            inputHasWeights);
                }
            });
            previousTexture = outputTexture;
            previousPass = passName;
        }

        final String averageTexture = previousTexture;
        final String averagePass = previousPass;
        graph.addPass(PostProcessTargets.AUTO_EXPOSURE_ADAPT_PASS)
                .writeToExternalTarget()
                .noClear()
                .dependsOn(averagePass)
                .execute((res, cmd) -> pass.recordAdaptation(cmd,
                        res.colorAttachment(averageTexture), manualExposure, settings, deltaSeconds));
        return new Output(PostProcessTargets.AUTO_EXPOSURE_ADAPT_PASS);
    }

    /** The exposure texture produced by this frame's adaptation; valid inside later pass callbacks. */
    public int frameExposureTexture() {
        return pass.frameExposureTexture();
    }

    public void frameSucceeded() {
        pass.commitFrame();
    }

    public void frameFailed() {
        pass.discardFrame();
    }

    public boolean historyValid() {
        return pass.historyValid();
    }

    public static float relativeScale(int level) {
        if (level < 0 || level >= RELATIVE_PASS_COUNT) {
            throw new IllegalArgumentException("relative reduction level must be in [0, 12]");
        }
        return (float) Math.scalb(1.0, -(level + 1));
    }

    @Override
    public void close() {
        pass.close();
    }

    /** Producer pass of the 1x1 exposure texture. */
    public record Output(String producerPass) {
        public Output {
            Objects.requireNonNull(producerPass, "producerPass");
        }
    }
}
