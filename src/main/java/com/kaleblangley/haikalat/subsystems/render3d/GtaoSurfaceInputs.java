package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.util.Objects;

import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.*;
import static com.kaleblangley.haikalat.subsystems.render3d.SceneBufferChannel.*;

/** GTAO's shared surface request or its compatibility depth-only path. */
record GtaoSurfaceInputs(long generationId, RenderGraph graph, SceneSurfaceInputs surface,
                         AntiAliasingMode aa, boolean temporal) {
    GtaoSurfaceInputs {
        if (generationId <= 0) throw new IllegalArgumentException("invalid GTAO input generation");
        Objects.requireNonNull(graph);
        Objects.requireNonNull(aa);
        if (surface != null && (surface.graph() != graph || surface.generationId() != generationId)) {
            throw new IllegalArgumentException("foreign GTAO surface generation");
        }
    }

    static GtaoSurfaceInputs shared(SceneSurfaceInputs surface, AntiAliasingMode aa, boolean temporal) {
        return new GtaoSurfaceInputs(surface.generationId(), surface.graph(), surface, aa, temporal);
    }

    static GtaoSurfaceInputs dedicated(long generation, RenderGraph graph, AntiAliasingMode aa, boolean temporal) {
        return new GtaoSurfaceInputs(generation, graph, null, aa, temporal);
    }

    boolean shared() { return surface != null; }

    String producer() { return shared() ? surface.single(DEPTH).producer() : GTAO_DEPTH_PREPASS; }

    int depth(PassResources resources) {
        if (shared()) return texture(resources, DEPTH);
        if (resources.recordingPass(graph) == null) {
            throw new IllegalStateException("dedicated GTAO depth requires an active callback on its graph");
        }
        int depth = resources.depthAttachment(GTAO_DEPTH);
        if (depth <= 0) throw new IllegalStateException("missing dedicated GTAO depth texture");
        return depth;
    }

    int texture(PassResources resources, SceneBufferChannel channel) {
        return surface.frame().require(resources, surface.single(channel));
    }

    void validate() {
        if (shared()) {
            if ((aa == AntiAliasingMode.MSAA) != (surface.layout().samples() > 1)) {
                throw new IllegalArgumentException("GTAO AA differs from its scene surface layout");
            }
            for (var channel : SceneBufferRequirements.gtao(temporal).channels()) surface.single(channel);
            surface.validateDependency(GTAO_ESTIMATE_PASS, surface.single(DEPTH));
            return;
        }
        var description = graph.description();
        var source = SceneSurfaceInputs.pass(description, GTAO_DEPTH_PREPASS);
        if (source.width() != graph.width() || source.height() != graph.height() || source.samples() != 1) {
            throw new IllegalArgumentException("GTAO depth requires full-size single-sample storage");
        }
        SceneSurfaceInputs.validateAttachment(source, new SceneSurfaceLayout.Attachment(DEPTH, GTAO_DEPTH,
                GTAO_DEPTH_PREPASS, RenderFormat.DEPTH_COMPONENT24, 1, -1));
        if (!SceneSurfaceInputs.pass(description, GTAO_ESTIMATE_PASS).directDependencies().contains(GTAO_DEPTH_PREPASS)) {
            throw new IllegalArgumentException("GTAO estimate is missing its dedicated depth dependency");
        }
    }

    void validateFrame(int width, int height) {
        if (graph.width() != width || graph.height() != height) {
            throw new IllegalStateException("GTAO frame extent does not match its input generation");
        }
    }
}
