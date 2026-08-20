package com.kaleblangley.haikalat.core.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** JVM-level coverage for the graph half of the resize candidate contract. */
class RenderGraphResizeCandidateTest {
    @Test
    void injectedGraphAllocationFailureLeavesActiveExtentUntouched() {
        try (RenderGraph graph = new RenderGraph(64, 48, false)) {
            System.setProperty("haikalat.test.failGraphResizeAllocation", "true");
            try {
                assertThrows(IllegalStateException.class, () -> graph.prepareResize(96, 72));
            } finally {
                System.clearProperty("haikalat.test.failGraphResizeAllocation");
            }
            assertEquals(64, graph.width());
            assertEquals(48, graph.height());

            RenderGraph.ResizeCandidate candidate = graph.prepareResize(96, 72);
            try {
                assertEquals(64, graph.width());
                assertEquals(48, graph.height());
                graph.commitResize(candidate);
            } finally {
                candidate.close();
            }
            assertEquals(96, graph.width());
            assertEquals(72, graph.height());
        }
    }
}
