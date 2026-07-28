package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.ui.render.UiAttachmentOptions;
import com.kaleblangley.haikalat.subsystems.ui.render.UiCompositor;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns the UI → RenderGraph attachment lifecycle.
 *
 * <p>Validation and topology mutation live in one place so the public
 * {@link UiSystem} facade only coordinates the update and render callbacks.</p>
 */
final class UiAttachmentController implements AutoCloseable {
    private final String overlayPassName;
    private RenderGraph attachedGraph;
    private UiCompositor compositor;

    UiAttachmentController(String overlayPassName) {
        this.overlayPassName = Objects.requireNonNull(overlayPassName, "overlayPassName");
    }

    void attach(RenderGraph graph, String dependencyPass,
                RenderGraph.PassExecutor beforeOverlay,
                Consumer<CommandBuffer> overlayRecorder) {
        validate(graph, dependencyPass);
        Objects.requireNonNull(beforeOverlay, "beforeOverlay");
        Objects.requireNonNull(overlayRecorder, "overlayRecorder");
        graph.addPass(overlayPassName)
                .writeToBackbuffer()
                .noClear()
                .dependsOn(dependencyPass)
                .execute((resources, commands) -> {
                    beforeOverlay.execute(resources, commands);
                    overlayRecorder.accept(commands);
                });
        graph.sealTopology();
        attachedGraph = graph;
    }

    void attach(RenderGraph graph, String dependencyPass,
                UiAttachmentOptions options,
                Consumer<CommandBuffer> overlayRecorder) {
        validate(graph, dependencyPass);
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(overlayRecorder, "overlayRecorder");
        compositor = new UiCompositor(options);
        String finalDependency = compositor.registerPasses(graph, dependencyPass);
        graph.addPass(overlayPassName)
                .writeToBackbuffer()
                .noClear()
                .dependsOn(finalDependency)
                .execute((resources, commands) -> overlayRecorder.accept(commands));
        graph.sealTopology();
        attachedGraph = graph;
    }

    boolean isAttached() {
        return attachedGraph != null;
    }

    UiCompositor.Diagnostics diagnostics() {
        return compositor == null ? UiCompositor.Diagnostics.EMPTY : compositor.diagnostics();
    }

    @Override
    public void close() {
        attachedGraph = null;
        compositor = null;
    }

    private void validate(RenderGraph graph, String dependencyPass) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(dependencyPass, "dependencyPass");
        if (attachedGraph != null) {
            throw new IllegalStateException("UiSystem is already attached to a RenderGraph");
        }
        if (graph.isTopologySealed()) {
            throw new IllegalStateException("RenderGraph topology is already sealed");
        }
        if (!graph.hasPass(dependencyPass)) {
            throw new IllegalArgumentException("UI dependency pass does not exist: " + dependencyPass);
        }
        if (!graph.passWritesToBackbuffer(dependencyPass)) {
            throw new IllegalArgumentException("UI dependency pass does not write to backbuffer: "
                    + dependencyPass);
        }
        if (graph.hasPass(overlayPassName)) {
            throw new IllegalStateException("RenderGraph already contains " + overlayPassName);
        }
    }
}
