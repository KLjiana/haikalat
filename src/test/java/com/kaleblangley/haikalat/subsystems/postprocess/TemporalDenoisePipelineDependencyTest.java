package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the colour-chain dependency assembler used by SSR and the
 * temporal denoiser.
 *
 * <p>Depth, normal and roughness normally share one surface producer.  The
 * RenderGraph compiler intentionally rejects duplicate dependency declarations,
 * so adding the same producer once per channel broke the real denoise pass in
 * the first GPU run.  This JVM case pins the dedupe without requiring a GL
 * context.</p>
 */
class TemporalDenoisePipelineDependencyTest {
    @Test
    void temporalDenoiseRequestsTheSharedVelocityAndPreviousDepthPair() {
        SceneBufferRequirements requirements = SceneBufferRequirements.temporalDenoise();
        assertTrue(requirements.requires(SceneBufferChannel.VELOCITY),
                "the denoiser's rigid-motion reprojection needs the shared velocity");
        assertTrue(requirements.requires(SceneBufferChannel.PREVIOUS_SURFACE_DEPTH),
                "velocity without previous depth would skip depth rejection");
        assertEquals(1, requirements.samples(),
                "the motion pair is consumed at the single-sample resolve");
        assertFalse(SceneBufferRequirements.ssr().requires(SceneBufferChannel.VELOCITY),
                "the spatial SSR pass itself still owns no history");
        assertTrue(SceneBufferRequirements.temporalDenoise()
                        .merge(SceneBufferRequirements.ssr())
                        .requires(SceneBufferChannel.ROUGHNESS),
                "merging the two requests must keep both consumers' channels");
    }

    @Test
    void surfaceProducersAreUniqueOrderedAndExcludeTheColourProducer() {
        List<SurfaceInputs.Input> geometryOnly = List.of(
                input(SurfaceInputs.Channel.DEPTH, "sceneDepth", PostProcessTargets.GEOMETRY_PASS),
                input(SurfaceInputs.Channel.NORMAL, "sceneNormal", PostProcessTargets.GEOMETRY_PASS),
                input(SurfaceInputs.Channel.ROUGHNESS, "sceneRoughness",
                        PostProcessTargets.GEOMETRY_PASS));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS),
                PostProcessPipeline.uniqueSurfaceProducers(PostProcessTargets.SSR_PASS,
                        geometryOnly),
                "three channels from one producer must add one dependency");

        List<SurfaceInputs.Input> mixed = List.of(
                input(SurfaceInputs.Channel.DEPTH, "sceneDepth",
                        PostProcessTargets.GEOMETRY_PASS),
                input(SurfaceInputs.Channel.NORMAL, "resolvedNormal",
                        PostProcessTargets.TAA_PASS),
                input(SurfaceInputs.Channel.ROUGHNESS, "sceneRoughness",
                        PostProcessTargets.GEOMETRY_PASS));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.TAA_PASS),
                PostProcessPipeline.uniqueSurfaceProducers(
                        PostProcessTargets.TEMPORAL_DENOISE_PASS, mixed),
                "declaration order must stay stable while duplicates are removed");

        assertEquals(List.of(),
                PostProcessPipeline.uniqueSurfaceProducers(
                        PostProcessTargets.GEOMETRY_PASS, geometryOnly),
                "the colour producer must not depend on itself");
        assertEquals(List.of(),
                PostProcessPipeline.uniqueSurfaceProducers(
                        PostProcessTargets.SSR_PASS, List.of()));
    }

    @Test
    void nullInputsFailClosed() {
        assertThrows(NullPointerException.class,
                () -> PostProcessPipeline.uniqueSurfaceProducers(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> PostProcessPipeline.uniqueSurfaceProducers(
                        PostProcessTargets.SSR_PASS, null));
    }

    private static SurfaceInputs.Input input(SurfaceInputs.Channel channel,
                                             String name, String producer) {
        return new SurfaceInputs.Input(channel, name, producer);
    }
}
