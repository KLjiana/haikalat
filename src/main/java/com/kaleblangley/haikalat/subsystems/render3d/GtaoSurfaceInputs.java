package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import java.util.Objects;

/** Immutable per-graph lease of graph-owned, single-sample device depth and world normals. */
record GtaoSurfaceInputs(long generationId, RenderGraph graph, String producer,
                         String depthTexture, String normalTexture,
                         AntiAliasingMode aa, boolean temporal, boolean shared) {
    GtaoSurfaceInputs {
        if (generationId <= 0) throw new IllegalArgumentException("GTAO input generation must be positive");
        Objects.requireNonNull(graph); Objects.requireNonNull(producer); Objects.requireNonNull(depthTexture);
        Objects.requireNonNull(aa);
        if (shared) Objects.requireNonNull(normalTexture, "normalTexture");
    }

    static GtaoSurfaceInputs shared(long generation, RenderGraph graph, PipelineTopology topology, boolean temporal) {
        return new GtaoSurfaceInputs(generation, graph, topology.sampleCount() > 1
                ? PostProcessTargets.SCENE_SURFACE_RESOLVE_PASS : PostProcessTargets.SCENE_SURFACE_PASS,
                PostProcessTargets.SCENE_DEPTH, PostProcessTargets.SCENE_NORMAL,
                topology.antiAliasingMode(), temporal, true);
    }

    static GtaoSurfaceInputs dedicated(long generation, RenderGraph graph, AntiAliasingMode aa, boolean temporal) {
        return new GtaoSurfaceInputs(generation, graph, PostProcessTargets.GTAO_DEPTH_PREPASS,
                PostProcessTargets.GTAO_DEPTH, null, aa, temporal, false);
    }

    void validate() {
        var description = graph.description();
        var source = description.passes().stream().filter(p -> p.name().equals(producer)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing GTAO surface producer: " + producer));
        if (source.width() != graph.width() || source.height() != graph.height() || source.samples() != 1)
            throw new IllegalArgumentException("GTAO inputs require matching full-size single-sample storage");
        boolean depth = source.depthAttachment() != null
                && source.depthAttachment().logicalName().equals(depthTexture)
                && source.depthAttachment().format().equals("DEPTH_COMPONENT")
                && source.depthAttachment().storageKind() == RenderGraph.StorageKind.TEXTURE;
        depth |= source.colorAttachments().stream().anyMatch(a -> a.logicalName().equals(depthTexture)
                && a.format().equals("R32F"));
        if (!depth) throw new IllegalArgumentException("GTAO input must be declared device depth: " + depthTexture);
        if (shared && aa == AntiAliasingMode.MSAA && source.colorAttachments().stream()
                .noneMatch(a -> a.logicalName().equals(depthTexture) && a.format().equals("R32F")))
            throw new IllegalArgumentException("MSAA GTAO must consume a resolved R32F device-depth producer");
        if (shared && source.colorAttachments().stream().noneMatch(a -> a.logicalName().equals(normalTexture)
                && a.format().equals("RG16F")))
            throw new IllegalArgumentException("GTAO shared normals must be signed octahedral RG16F");
        var estimate = description.passes().stream().filter(p -> p.name().equals(PostProcessTargets.GTAO_ESTIMATE_PASS))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("missing GTAO estimate consumer"));
        if (!estimate.directDependencies().contains(producer)
                || description.executionOrder().indexOf(producer) >= description.executionOrder().indexOf(estimate.name()))
            throw new IllegalArgumentException("GTAO estimate must depend on its actual input producer");
        if (temporal && shared) for (String channel : java.util.List.of(PostProcessTargets.SCENE_VELOCITY,
                PostProcessTargets.SCENE_PREVIOUS_DEPTH, PostProcessTargets.SCENE_VALIDITY)) {
            String format = channel.equals(PostProcessTargets.SCENE_VELOCITY) ? "RG16F"
                    : channel.equals(PostProcessTargets.SCENE_PREVIOUS_DEPTH) ? "R32F" : "R8";
            if (source.colorAttachments().stream().noneMatch(a -> a.logicalName().equals(channel) && a.format().equals(format)))
                throw new IllegalArgumentException("missing GTAO temporal surface channel: " + channel);
        }
    }

    void validateFrame(int width, int height) {
        if (graph.width() != width || graph.height() != height)
            throw new IllegalStateException("GTAO frame extent does not match its input generation");
    }
}
