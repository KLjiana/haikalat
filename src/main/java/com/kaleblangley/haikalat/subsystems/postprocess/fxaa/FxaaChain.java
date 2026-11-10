package com.kaleblangley.haikalat.subsystems.postprocess.fxaa;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;

import java.util.Objects;

/** Owner of the FXAA program and the LDR/HDR presentation topology. */
public final class FxaaChain implements AutoCloseable {
    private final FxaaPostProcessor pass = new FxaaPostProcessor();

    /** LDR path: FXAA renders into its own colour target and the caller presents it. */
    public void addLdrPass(RenderGraph graph, String sourcePass, String sourceTexture) {
        Objects.requireNonNull(graph, "graph");
        graph.addPass(PostProcessTargets.FXAA_PASS)
                .createColor(PostProcessTargets.FXAA_COLOR, RenderFormat.SRGB8_ALPHA8)
                .noClear()
                .dependsOn(sourcePass)
                .execute((res, cmd) -> {
                    Framebuffer source = res.framebufferOfPass(sourcePass);
                    if (source != null) {
                        pass.recordIntoCurrentTarget(cmd,
                                res.colorAttachment(sourceTexture), source.width(), source.height());
                    }
                });
    }

    /** HDR path: FXAA writes the final backbuffer image after tone mapping. */
    public void addHdrPass(RenderGraph graph, String sourcePass, String sourceTexture) {
        Objects.requireNonNull(graph, "graph");
        graph.addPass(PostProcessTargets.FXAA_PASS)
                .writeToBackbuffer()
                .noClear()
                .dependsOn(sourcePass)
                .execute((res, cmd) -> {
                    Framebuffer source = res.framebufferOfPass(sourcePass);
                    if (source != null) {
                        pass.recordIntoCurrentTarget(cmd,
                                res.colorAttachment(sourceTexture), source.width(), source.height());
                    }
                });
    }

    @Override
    public void close() {
        pass.close();
    }
}
