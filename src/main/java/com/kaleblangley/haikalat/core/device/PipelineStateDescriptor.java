package com.kaleblangley.haikalat.core.device;

import com.kaleblangley.haikalat.core.BlendMode;

import java.util.Objects;

public record PipelineStateDescriptor(
        boolean depthTest,
        boolean depthWrite,
        BlendMode blendMode,
        CullMode cullMode
) {
    public PipelineStateDescriptor {
        blendMode = Objects.requireNonNull(blendMode, "blendMode");
        cullMode = Objects.requireNonNull(cullMode, "cullMode");
    }

    public static PipelineStateDescriptor opaque() {
        return new PipelineStateDescriptor(true, true, BlendMode.OPAQUE, CullMode.BACK);
    }

    public static PipelineStateDescriptor transparent() {
        return new PipelineStateDescriptor(true, false, BlendMode.ALPHA, CullMode.BACK);
    }
}
