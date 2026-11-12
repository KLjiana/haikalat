package com.kaleblangley.haikalat.subsystems.render3d.pipeline;

import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;

import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;

import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.postprocess.LateSurfaceProducer;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;

import java.util.List;
import java.util.Objects;

/**
 * Adapts the shared scene surface lease and the late reactive producer to the
 * post-process contracts.
 *
 * <p>This is the only Render3D type that the colour chain sees.  The adapter
 * preserves the real producer pass, the per-frame lease (so a stale frame or a
 * foreign attachment fails at record time) and the reactive pass declaration;
 * it never falls back to raw texture ids.</p>
 */
public final class SceneSurfacePostProcessAdapter implements SurfaceInputs, LateSurfaceProducer {
    private final SceneSurfaceInputs surface;
    private final RenderGraph.PassExecutor reactiveRecorder;

    public SceneSurfacePostProcessAdapter(SceneSurfaceInputs surface,
                                   RenderGraph.PassExecutor reactiveRecorder) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.reactiveRecorder = reactiveRecorder;
    }

    @Override
    public Input single(Channel channel) {
        var attachment = attachment(channel);
        return attachment == null ? null
                : new Input(channel, attachment.name(), attachment.producer());
    }

    @Override
    public void validateDependency(String consumerPass, Input input) {
        Objects.requireNonNull(consumerPass, "consumerPass");
        var attachment = requireAttachment(input);
        surface.validateDependency(consumerPass, attachment);
    }

    @Override
    public int texture(PassResources resources, Input input) {
        Objects.requireNonNull(resources, "resources");
        return surface.frame().require(resources, requireAttachment(input));
    }

    @Override
    public boolean enabled() {
        return surface.layout().reactive() != null;
    }

    @Override
    public void declare(RenderGraph graph, String colorProducerPass) {
        Objects.requireNonNull(graph, "graph");
        var layout = surface.layout();
        var output = layout.reactive();
        if (output == null) {
            throw new IllegalStateException("active scene surface topology has no reactive output");
        }
        var target = graph.addPass(output.producer()).dependsOn(colorProducerPass);
        if (layout.reactiveMode() == SceneSurfaceLayout.ReactiveMode.VOLUME_ALIAS) {
            target.shareColorAttachments(List.of(output.name()), List.of(output.format()),
                    VolumetricPassBuilder.COMPOSITE_PASS,
                    List.of(VolumetricPassBuilder.OPAQUE_REACTIVE_SLOT), 1).noClear();
        } else {
            target.createColor(output.name(), output.format()).clearColor(0, 0, 0, 0);
        }
        var recorder = Objects.requireNonNull(reactiveRecorder, "missing reactive surface recorder");
        target.execute((res, cmd) -> {
            recorder.execute(res, cmd);
            surface.frame().reactiveProduced(res);
        });
    }

    private SceneSurfaceLayout.Attachment attachment(Channel channel) {
        return surface.layout().single(sceneChannel(channel));
    }

    private SceneSurfaceLayout.Attachment requireAttachment(Input input) {
        var attachment = attachment(input.channel());
        if (attachment == null || !attachment.name().equals(input.name())
                || !attachment.producer().equals(input.producerPass())) {
            throw new IllegalArgumentException("foreign post-process surface input " + input);
        }
        return attachment;
    }

    private static SceneBufferChannel sceneChannel(Channel channel) {
        return switch (channel) {
            case DEPTH -> SceneBufferChannel.DEPTH;
            case NORMAL -> SceneBufferChannel.NORMAL;
            case ROUGHNESS -> SceneBufferChannel.ROUGHNESS;
            case SUBSURFACE -> SceneBufferChannel.SUBSURFACE;
            case VELOCITY -> SceneBufferChannel.VELOCITY;
            case PREVIOUS_SURFACE_DEPTH -> SceneBufferChannel.PREVIOUS_SURFACE_DEPTH;
            case VALIDITY -> SceneBufferChannel.VALIDITY;
            case REACTIVE -> SceneBufferChannel.REACTIVE;
        };
    }
}
