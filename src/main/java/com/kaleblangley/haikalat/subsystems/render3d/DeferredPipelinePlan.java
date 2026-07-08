package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.device.RenderFormat;

import java.util.List;

public record DeferredPipelinePlan(
        List<String> passes,
        List<GBufferAttachment> gBufferAttachments,
        boolean implemented
) {
    public static DeferredPipelinePlan evaluatedBaseline() {
        return new DeferredPipelinePlan(
                List.of("GBufferPass", "LightingPass", "PostProcessPass", "PresentPass"),
                List.of(
                        new GBufferAttachment("GBufferAlbedo", RenderFormat.RGBA8),
                        new GBufferAttachment("GBufferNormal", RenderFormat.RGBA16F),
                        new GBufferAttachment("GBufferMaterial", RenderFormat.RGBA8)
                ),
                false
        );
    }

    public record GBufferAttachment(String name, RenderFormat format) {
    }
}
