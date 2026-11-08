package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.RenderFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void preparedNoopCannotReplaceTheResizedGraph() {
        try (RenderGraph graph = new RenderGraph(64, 48, false);
             var noop = graph.prepareResize(64, 48)) {
            graph.resize(97, 65);
            var resized = graph.description();

            assertThrows(IllegalStateException.class, () -> graph.commitResize(noop));
            assertSame(resized, graph.description());
            assertEquals(97, graph.width());
            assertEquals(65, graph.height());
        }
    }

    @Test
    void changedPassesAndFixedTargetsInvalidatePreparedResize() {
        try (RenderGraph graph = new RenderGraph(64, 48, false)) {
            try (var candidate = graph.prepareResize(97, 65)) {
                graph.addPass("Fixed").createColor("fixed", RenderFormat.R8).fixedSize(8, 8)
                        .execute((resources, commands) -> {});
                assertThrows(IllegalStateException.class, () -> graph.commitResize(candidate));
            }
            graph.compile();
            try (var candidate = graph.prepareResize(97, 65)) {
                graph.resizeManagedTargets(Map.of("Fixed", new RenderGraph.TargetExtent(16, 12)));
                assertThrows(IllegalStateException.class, () -> graph.commitResize(candidate));
            }

            graph.resize(97, 65);
            var fixed = graph.description().passes().getFirst();
            assertEquals(16, fixed.width());
            assertEquals(12, fixed.height());
        }
    }

    @Test
    void replacementRejectsDonorThatResizedAwayAndBack() {
        try (RenderGraph donor = new RenderGraph(64, 48, false);
             RenderGraph stale = RenderGraph.replacementOf(donor)) {
            donor.resize(97, 65);
            donor.resize(64, 48);

            assertThrows(IllegalStateException.class, stale::commitReplacement);
            try (RenderGraph fresh = RenderGraph.replacementOf(donor)) {
                fresh.commitReplacement();
                assertEquals(64, fresh.width());
                assertEquals(48, fresh.height());
            }
        }
    }
}
