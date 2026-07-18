package com.kaleblangley.haikalat.core.graph;

import com.kaleblangley.haikalat.backend.RenderFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenderGraphDescriptionTest {
    @Test
    void descriptionUsesCompiledOrderAndTracksResizeWithoutChangingTopology() {
        try (RenderGraph graph = new RenderGraph(800, 600, false)) {
            graph.addPass("Geometry").createColor("scene", RenderFormat.RGBA16F)
                    .relativeSize(0.5f).createDepth().execute((resources, commands) -> { });
            graph.addPass("Shadow").createDepthTexture("shadow").fixedSize(1024, 1024)
                    .execute((resources, commands) -> { });
            graph.addPass("Present").dependsOn("Geometry").dependsOn("Shadow")
                    .writeToBackbuffer().execute((resources, commands) -> { });
            graph.sealTopology();

            RenderGraph.Description before = graph.description();
            assertEquals(List.of("Geometry", "Shadow", "Present"), before.executionOrder());
            assertEquals(400, before.passes().getFirst().width());
            assertEquals(1024, before.passes().get(1).width());
            assertSame(before, graph.description());

            graph.resize(1200, 700);
            RenderGraph.Description after = graph.description();
            assertEquals(600, after.passes().getFirst().width());
            assertEquals(350, after.passes().getFirst().height());
            assertEquals(1024, after.passes().get(1).width());
            assertEquals(before.topologyRevision(), after.topologyRevision());
            assertNotSame(before, after);
        }
    }

    @Test
    void descriptionsAreDeeplyImmutable() {
        try (RenderGraph graph = new RenderGraph(16, 16, false)) {
            graph.addPass("Present").writeToBackbuffer().execute((resources, commands) -> { });
            RenderGraph.Description description = graph.description();
            assertThrows(UnsupportedOperationException.class,
                    () -> description.executionOrder().add("Injected"));
            assertThrows(UnsupportedOperationException.class,
                    () -> description.passes().getFirst().directDependencies().add("Injected"));
        }
    }
}
