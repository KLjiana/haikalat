package com.kaleblangley.haikalat.subsystems.postprocess.tonemapping;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.postprocess.ColorGradingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Owner of the ACES tone-mapping program and its colour-grading LUT.
 *
 * <p>Exposure and bloom are inputs here: exposure is a borrowed 1x1 texture
 * produced earlier in the frame, bloom is composited by the tone-mapping
 * shader.  The chain never allocates the scene colour target.</p>
 */
public final class ToneMappingChain implements AutoCloseable {
    private final ToneMappingPass pass;

    public ToneMappingChain(ColorGradingSettings colorGrading) {
        this.pass = new ToneMappingPass(Objects.requireNonNull(colorGrading, "colorGrading"));
    }

    /**
     * Declares the tone-mapping pass.
     *
     * @param bloomTexture      bloom colour, or {@code null} when disabled
     * @param bloomProducer     bloom producer pass, or {@code null} when disabled
     * @param exposureTexture   1x1 exposure supplier, or {@code null} when manual
     * @param exposureProducer  exposure producer pass, or {@code null} when manual
     */
    public void addPass(RenderGraph graph, String hdrTexture, String hdrProducer,
                        String bloomTexture, String bloomProducer,
                        float manualExposure, IntSupplier exposureTexture,
                        String exposureProducer, float bloomIntensity) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(hdrTexture, "hdrTexture");
        Objects.requireNonNull(hdrProducer, "hdrProducer");
        String toneDependency = bloomProducer != null ? bloomProducer : hdrProducer;
        RenderGraph.PassBuilder tonePass = graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                .createColor(PostProcessTargets.TONE_MAPPED_COLOR, RenderFormat.RGBA8)
                .noClear()
                .dependsOn(toneDependency);
        if (exposureProducer != null) {
            tonePass.dependsOn(exposureProducer);
        }
        tonePass.execute((res, cmd) -> {
            int bloom = bloomTexture == null ? 0 : res.colorAttachment(bloomTexture);
            int exposure = exposureTexture == null ? 0 : exposureTexture.getAsInt();
            pass.recordIntoCurrentTarget(cmd, res.colorAttachment(hdrTexture), bloom,
                    manualExposure, exposure, bloomIntensity);
        });
    }

    @Override
    public void close() {
        pass.close();
    }
}
