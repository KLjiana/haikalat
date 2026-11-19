package com.kaleblangley.haikalat.subsystems.render3d.surface;

import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderFrameContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the explicit scene-vs-chain extent split in
 * {@link SceneSurfaceInputs}: surface producers are validated against the scene
 * raster extent while the graph (chain) extent stays at the target.
 */
class SceneSurfaceInputsTest {
    private static final int CHAIN_WIDTH = 20;
    private static final int CHAIN_HEIGHT = 10;
    private static final int SCENE_WIDTH = 10;
    private static final int SCENE_HEIGHT = 5;

    @Test
    void sceneExtentIsExplicitAndIndependentFromTheGraphExtent() {
        try (RenderGraph graph = new RenderGraph(CHAIN_WIDTH, CHAIN_HEIGHT)) {
            SceneSurfaceInputs reduced = new SceneSurfaceInputs(1L, graph, emptySurface(),
                    SCENE_WIDTH, SCENE_HEIGHT);
            assertEquals(SCENE_WIDTH, reduced.sceneWidth());
            assertEquals(SCENE_HEIGHT, reduced.sceneHeight());
            assertTrue(reduced.hasReducedSceneExtent(),
                    "a scene raster smaller than the chain must be observable");

            SceneSurfaceInputs full = new SceneSurfaceInputs(2L, graph, emptySurface());
            assertEquals(CHAIN_WIDTH, full.sceneWidth());
            assertEquals(CHAIN_HEIGHT, full.sceneHeight());
            assertFalse(full.hasReducedSceneExtent());
        }
    }

    @Test
    void beginFrameRequiresTheSceneExtentNotTheChainExtent() {
        try (RenderGraph graph = new RenderGraph(CHAIN_WIDTH, CHAIN_HEIGHT)) {
            SceneSurfaceInputs inputs = new SceneSurfaceInputs(1L, graph, emptySurface(),
                    SCENE_WIDTH, SCENE_HEIGHT);
            RenderFrameContext matching = context(CHAIN_WIDTH, CHAIN_HEIGHT,
                    SCENE_WIDTH, SCENE_HEIGHT);
            inputs.beginFrame(matching);
            assertTrue(inputs.frame() != null);
            inputs.endFrame();

            assertThrows(IllegalStateException.class,
                    () -> inputs.beginFrame(context(CHAIN_WIDTH, CHAIN_HEIGHT,
                            CHAIN_WIDTH, CHAIN_HEIGHT)),
                    "the chain extent must not satisfy the surface producer extent");
            assertThrows(IllegalStateException.class,
                    () -> inputs.beginFrame(context(CHAIN_WIDTH, CHAIN_HEIGHT,
                            SCENE_WIDTH, SCENE_HEIGHT - 1)),
                    "a mismatched scene height must be rejected");
        }
    }

    @Test
    void invalidSceneExtentsAreRejectedAtConstruction() {
        try (RenderGraph graph = new RenderGraph(CHAIN_WIDTH, CHAIN_HEIGHT)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SceneSurfaceInputs(1L, graph, emptySurface(), 0, SCENE_HEIGHT));
            assertThrows(IllegalArgumentException.class,
                    () -> new SceneSurfaceInputs(1L, graph, emptySurface(),
                            SCENE_WIDTH, CHAIN_HEIGHT + 1),
                    "a scene raster taller than the chain target is invalid");
            assertThrows(IllegalArgumentException.class,
                    () -> new SceneSurfaceInputs(0L, graph, emptySurface(),
                            SCENE_WIDTH, SCENE_HEIGHT));
        }
    }

    private static SceneSurfaceLayout emptySurface() {
        return SceneSurfaceLayout.plan(1, SceneBufferRequirements.none(),
                SceneBufferRequirements.none(), SceneSurfaceLayout.ReactiveMode.NONE);
    }

    private static RenderFrameContext context(int width, int height,
                                              int sceneWidth, int sceneHeight) {
        Scene scene = new Scene(new Camera());
        return RenderFrameContext.capture(scene, scene.camera(), width, height,
                sceneWidth, sceneHeight, 0.0f, 0, 0L, 1L, null);
    }
}
