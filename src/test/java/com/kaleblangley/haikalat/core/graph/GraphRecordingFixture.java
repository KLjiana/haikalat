package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.ExecutionModel;
import com.kaleblangley.haikalat.core.device.RenderBackendKind;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.device.ResourceBarrier;

/** Exercises real graph compilation and callbacks without a context or command submission. */
public final class GraphRecordingFixture {
    private GraphRecordingFixture() {}

    public static RenderGraph graph(int width, int height) {
        return new RenderGraph(width, height, false);
    }

    public static void record(RenderGraph graph) {
        graph.execute(new RenderDevice() {
            @Override public RenderBackendKind backendKind() { return RenderBackendKind.OPENGL; }
            @Override public ExecutionModel executionModel() { return ExecutionModel.EXPLICIT; }
            @Override public CommandBuffer createCommandBuffer() { return new CommandBuffer(); }
            @Override public void execute(CommandBuffer buffer) {}
            @Override public void invalidateState() {}
            @Override public void transition(ResourceBarrier... barriers) {}
        });
    }
}
