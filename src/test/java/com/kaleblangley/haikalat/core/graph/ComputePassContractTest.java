package com.kaleblangley.haikalat.core.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract checks for framebuffer-less compute passes. */
class ComputePassContractTest {
    @Test
    void computeOnlyPassHasNoFramebufferAndReportsComputeTarget() {
        RenderGraph graph = new RenderGraph(64, 64, false);
        graph.addPass("ClusterCompute").computeOnly().execute((resources, cmd) -> { });
        graph.addPass("Geometry").createColor("Color").dependsOn("ClusterCompute")
                .execute((resources, cmd) -> { });
        graph.compile();

        RenderGraph.Description description = graph.description();
        assertEquals(List.of("ClusterCompute", "Geometry"), description.executionOrder());
        RenderGraph.PassDescription compute = description.passes().stream()
                .filter(pass -> pass.name().equals("ClusterCompute")).findFirst().orElseThrow();
        assertEquals(RenderGraph.TargetKind.COMPUTE, compute.targetKind());
        assertEquals(0, compute.width());
        assertEquals(0, compute.height());
        assertTrue(compute.colorAttachments().isEmpty());
        assertNull(compute.depthAttachment());
        assertNull(graph.passFramebufferDescriptor("ClusterCompute"));
        assertTrue(graph.passFramebufferDescriptor("Geometry") != null);
    }

    @Test
    void computeDeclarationsAreMutuallyExclusiveWithTargetsAndClears() {
        RenderGraph graph = new RenderGraph(32, 32, false);
        assertThrows(IllegalStateException.class, () -> graph.addPass("A")
                .createColor("Color").computeOnly());
        assertThrows(IllegalStateException.class, () -> graph.addPass("B")
                .computeOnly().createColor("Color"));
        assertThrows(IllegalStateException.class, () -> graph.addPass("C")
                .computeOnly().createDepth());
        assertThrows(IllegalStateException.class, () -> graph.addPass("D")
                .computeOnly().createDepthTexture("Depth"));
        assertThrows(IllegalStateException.class, () -> graph.addPass("E")
                .computeOnly().writeToBackbuffer());
        assertThrows(IllegalStateException.class, () -> graph.addPass("F")
                .computeOnly().writeToExternalTarget());
        assertThrows(IllegalStateException.class, () -> graph.addPass("G")
                .computeOnly().writeToPresentationTarget("Host"));
    }

    @Test
    void computePassStillParticipatesInDependencyOrderingAndProfiling() {
        RenderGraph graph = new RenderGraph(16, 16, false);
        graph.addPass("First").computeOnly().execute((resources, cmd) -> { });
        graph.addPass("Second").computeOnly().dependsOn("First")
                .execute((resources, cmd) -> { });
        graph.compile();

        assertEquals(List.of("First", "Second"), graph.description().executionOrder());
        RenderGraph.PassDescription second = graph.description().passes().stream()
                .filter(pass -> pass.name().equals("Second")).findFirst().orElseThrow();
        assertEquals(List.of("First"), second.directDependencies());
    }
}
