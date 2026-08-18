package com.kaleblangley.haikalat.subsystems.render3d;

/** Validated contract for the supported DEPTH24_STENCIL8 MSAA-to-depth-texture blit. */
record DepthResolveDescriptor(String sourceFormat, String targetFormat,
                              int sourceSamples, int targetSamples,
                              int sourceWidth, int sourceHeight,
                              int targetWidth, int targetHeight) {
    DepthResolveDescriptor {
        if (!"DEPTH24_STENCIL8".equals(sourceFormat)
                || !"DEPTH_COMPONENT".equals(targetFormat)) {
            throw new IllegalArgumentException("unsupported depth resolve format pair");
        }
        if (sourceSamples <= 1 || targetSamples != 1) {
            throw new IllegalArgumentException("depth resolve requires multisample source and single-sample target");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0
                || sourceWidth != targetWidth || sourceHeight != targetHeight) {
            throw new IllegalArgumentException("depth resolve extents must be positive and equal");
        }
    }

    static DepthResolveDescriptor forTopology(PipelineTopology topology) {
        return new DepthResolveDescriptor("DEPTH24_STENCIL8", "DEPTH_COMPONENT",
                topology.sampleCount(), 1, topology.width(), topology.height(),
                topology.width(), topology.height());
    }
}
