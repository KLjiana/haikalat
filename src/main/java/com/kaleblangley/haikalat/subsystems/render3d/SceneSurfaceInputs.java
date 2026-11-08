package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.util.List;
import java.util.Objects;

import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.*;
import static com.kaleblangley.haikalat.subsystems.render3d.SceneBufferChannel.DEPTH;

/** One graph's borrowed surface inputs. The graph owns all attachment storage. */
final class SceneSurfaceInputs {
    private final long generationId;
    private final RenderGraph graph;
    private final SceneSurfaceLayout layout;
    private Frame current;
    private boolean retired;

    SceneSurfaceInputs(long generationId, RenderGraph graph, SceneSurfaceLayout layout) {
        if (generationId <= 0) throw new IllegalArgumentException("invalid surface generation");
        this.generationId = generationId;
        this.graph = Objects.requireNonNull(graph);
        this.layout = Objects.requireNonNull(layout);
    }

    long generationId() { return generationId; }
    RenderGraph graph() { return graph; }
    SceneSurfaceLayout layout() { return layout; }

    SceneSurfaceLayout.Attachment single(SceneBufferChannel channel) {
        var attachment = layout.single(channel);
        if (attachment == null) throw new IllegalArgumentException("no single-sample surface input for " + channel);
        return attachment;
    }

    void validate() {
        var description = graph.description();
        if (layout.hasSurface()) validatePass(description, SCENE_SURFACE_PASS, layout.raster());
        if (layout.hasResolve()) {
            validatePass(description, SCENE_SURFACE_RESOLVE_PASS, layout.resolve());
            validateDependency(SCENE_SURFACE_RESOLVE_PASS, layout.raw(DEPTH));
        }
        if (layout.reactive() != null) validatePass(description, SCENE_REACTIVE_PASS, List.of(layout.reactive()));
    }

    private static void validatePass(RenderGraph.Description graph, String name,
                                     List<SceneSurfaceLayout.Attachment> attachments) {
        var pass = pass(graph, name);
        if (pass.width() != graph.width() || pass.height() != graph.height()
                || pass.samples() != attachments.getFirst().samples()) {
            throw new IllegalArgumentException("invalid surface extent or samples in " + name);
        }
        long colors = attachments.stream().filter(a -> !a.depthTexture()).count();
        if (pass.colorAttachments().size() != colors) throw new IllegalArgumentException("invalid surface slots in " + name);
        for (var attachment : attachments) validateAttachment(pass, attachment);
    }

    static void validateAttachment(RenderGraph.PassDescription pass, SceneSurfaceLayout.Attachment attachment) {
        var actual = attachment.depthTexture() ? pass.depthAttachment()
                : attachment.colorSlot() < pass.colorAttachments().size() ? pass.colorAttachments().get(attachment.colorSlot()) : null;
        String format = attachment.depthTexture() ? "DEPTH_COMPONENT" : attachment.format().name();
        if (actual == null || !actual.logicalName().equals(attachment.name()) || !actual.format().equals(format)
                || actual.storageKind() != RenderGraph.StorageKind.TEXTURE) {
            throw new IllegalArgumentException("invalid surface attachment " + attachment.name() + " in " + pass.name());
        }
    }

    void validateDependency(String consumer, SceneSurfaceLayout.Attachment input) {
        var description = graph.description();
        if (!pass(description, consumer).directDependencies().contains(input.producer())
                || description.executionOrder().indexOf(input.producer()) < 0
                || description.executionOrder().indexOf(input.producer()) >= description.executionOrder().indexOf(consumer)) {
            throw new IllegalArgumentException(consumer + " must depend on surface producer " + input.producer());
        }
    }

    static RenderGraph.PassDescription pass(RenderGraph.Description description, String name) {
        return description.passes().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing surface producer or consumer " + name));
    }

    void beginFrame(RenderFrameContext context) {
        if (retired || current != null || graph.width() != context.width() || graph.height() != context.height()) {
            throw new IllegalStateException("cannot borrow surface inputs for generation " + generationId);
        }
        current = new Frame(context);
    }

    Frame frame() {
        if (current == null) throw new IllegalStateException("surface inputs require an active frame");
        return current;
    }

    void endFrame() { current = null; }

    void retire() {
        retired = true;
        endFrame();
    }

    final class Frame {
        private final RenderFrameContext context;
        private boolean rasterReady;
        private boolean resolveReady;
        private boolean reactiveReady;

        private Frame(RenderFrameContext context) { this.context = context; }

        private void ensureCurrent() {
            if (current != this || graph.width() != context.width() || graph.height() != context.height()) {
                throw new IllegalStateException("expired scene surface frame " + context.frameSequence());
            }
        }

        private String recordingPass(PassResources resources) {
            ensureCurrent();
            String pass = resources == null ? null : resources.recordingPass(graph);
            if (pass == null) throw new IllegalStateException("surface input requires an active callback on its graph");
            return pass;
        }

        private void requireProducer(PassResources resources, String producer) {
            if (!producer.equals(recordingPass(resources))) {
                throw new IllegalStateException("surface output must be recorded by " + producer);
            }
        }

        void rasterProduced(PassResources resources) {
            requireProducer(resources, SCENE_SURFACE_PASS);
            if (!layout.hasSurface() || rasterReady) throw new IllegalStateException("unexpected raster surface production");
            rasterReady = true;
        }

        void resolveProduced(PassResources resources) {
            requireProducer(resources, SCENE_SURFACE_RESOLVE_PASS);
            if (!rasterReady || !layout.hasResolve() || resolveReady) throw new IllegalStateException("unexpected surface resolve production");
            resolveReady = true;
        }

        void reactiveProduced(PassResources resources) {
            requireProducer(resources, SCENE_REACTIVE_PASS);
            if (layout.reactive() == null || reactiveReady || layout.hasSurface() && !rasterReady) {
                throw new IllegalStateException("unexpected reactive surface production");
            }
            reactiveReady = true;
        }

        int require(PassResources resources, SceneSurfaceLayout.Attachment attachment) {
            recordingPass(resources);
            Objects.requireNonNull(attachment, "surface attachment");
            if (attachment != layout.raw(attachment.channel()) && attachment != layout.single(attachment.channel())) {
                throw new IllegalStateException("foreign scene surface input");
            }
            boolean ready = switch (attachment.producer()) {
                case SCENE_SURFACE_PASS -> rasterReady;
                case SCENE_SURFACE_RESOLVE_PASS -> resolveReady;
                case SCENE_REACTIVE_PASS -> reactiveReady;
                default -> false;
            };
            if (!ready) throw new IllegalStateException("surface input read before " + attachment.producer());
            var target = resources.framebufferOfPass(attachment.producer());
            if (target == null || target.isClosed() || target.width() != context.width() || target.height() != context.height()
                    || target.descriptor().samples() != attachment.samples()) {
                throw new IllegalStateException("stale framebuffer for surface input " + attachment.name());
            }
            int texture = attachment.depthTexture() ? target.depthAttachment() : target.colorAttachment(attachment.colorSlot());
            if (texture <= 0) throw new IllegalStateException("missing surface texture " + attachment.name());
            return texture;
        }
    }
}
