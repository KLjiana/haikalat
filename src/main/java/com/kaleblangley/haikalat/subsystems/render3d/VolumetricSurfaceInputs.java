package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.graph.RenderGraph;
import java.util.Objects;

/** Graph-owned, device-depth inputs. All names and producer edges come from assembly. */
record VolumetricSurfaceInputs(String geometryPass, String surfacePass, String resolvePass,
                               String depthTexture, String multisampleDepthTexture) {
    VolumetricSurfaceInputs {
        Objects.requireNonNull(geometryPass); Objects.requireNonNull(surfacePass);
        Objects.requireNonNull(resolvePass); Objects.requireNonNull(depthTexture);
        Objects.requireNonNull(multisampleDepthTexture);
    }

    void validate(RenderGraph graph, PipelineTopology topology) {
        var description = graph.description();
        var geometry = pass(description, geometryPass);
        var surface = pass(description, surfacePass);
        for (var input : java.util.List.of(geometry, surface)) {
            if (input.width() != topology.width() || input.height() != topology.height()
                    || input.samples() != topology.sampleCount() || input.depthAttachment() == null)
                throw new IllegalArgumentException("invalid volumetric surface extent/samples/depth: " + input.name());
        }
        if (geometry.colorAttachments().stream().noneMatch(a -> a.format().equals("RGBA16F")))
            throw new IllegalArgumentException("volumetric color input must be linear RGBA16F");
        String expectedDepth = topology.sampleCount() > 1 ? multisampleDepthTexture : depthTexture;
        if (!surface.depthAttachment().logicalName().equals(expectedDepth))
            throw new IllegalArgumentException("undeclared volumetric depth input: " + expectedDepth);
        if (topology.sampleCount() > 1) {
            var resolve = pass(description, resolvePass);
            if (resolve.samples() != 1 || resolve.width() != topology.width() || resolve.height() != topology.height()
                    || resolve.colorAttachments().stream().noneMatch(a -> a.logicalName().equals(depthTexture) && a.format().equals("R32F")))
                throw new IllegalArgumentException("volumetric MSAA requires a matching single-sample depth resolve");
        }
    }

    private static RenderGraph.PassDescription pass(RenderGraph.Description graph, String name) {
        return graph.passes().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing volumetric producer: " + name));
    }
}
