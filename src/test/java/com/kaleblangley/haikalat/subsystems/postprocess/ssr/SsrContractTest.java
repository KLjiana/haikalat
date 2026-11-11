package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-module contract for the bounded screen-space reflection path.
 *
 * <p>The pass is a post-process owner but its inputs come from the shared scene
 * surface protocol.  These assertions pin the requested channels, the added
 * roughness attachment (R8, single-sample and resolved), the pass/target names
 * and the bounded shader shape; pixel correctness is proved by the real-GL
 * test because shader compilation needs a GL context.</p>
 */
class SsrContractTest {
    @Test
    void surfaceRequestAddsSingleSampleDepthNormalAndRoughness() {
        SceneBufferRequirements requirements = SceneBufferRequirements.ssr();
        assertTrue(requirements.requires(SceneBufferChannel.DEPTH));
        assertTrue(requirements.requires(SceneBufferChannel.NORMAL));
        assertTrue(requirements.requires(SceneBufferChannel.ROUGHNESS));
        assertFalse(requirements.requires(SceneBufferChannel.VELOCITY),
                "ssr owns no temporal history and needs no motion vectors");
        assertEquals(1, requirements.samples());
    }

    @Test
    void roughnessIsAnR8AttachmentWithNearestDepthResolve() {
        SceneSurfaceLayout single = SceneSurfaceLayout.plan(1, SceneBufferRequirements.none(),
                SceneBufferRequirements.ssr(), SceneSurfaceLayout.ReactiveMode.NONE);
        var roughness = single.single(SceneBufferChannel.ROUGHNESS);
        assertNotNull(roughness);
        assertEquals(PostProcessTargets.SCENE_ROUGHNESS, roughness.name());
        assertEquals(RenderFormat.R8, roughness.format());
        assertEquals(1, roughness.samples());
        assertEquals(PostProcessTargets.SCENE_SURFACE_PASS, roughness.producer());
        assertEquals(PostProcessTargets.SCENE_NORMAL, single.single(SceneBufferChannel.NORMAL).name());

        SceneSurfaceLayout multisampled = SceneSurfaceLayout.plan(4,
                SceneBufferRequirements.none(), SceneBufferRequirements.ssr(),
                SceneSurfaceLayout.ReactiveMode.NONE);
        var resolved = multisampled.single(SceneBufferChannel.ROUGHNESS);
        assertEquals(PostProcessTargets.SCENE_ROUGHNESS, resolved.name());
        assertEquals(PostProcessTargets.SCENE_SURFACE_RESOLVE_PASS, resolved.producer());
        assertEquals(PostProcessTargets.SCENE_ROUGHNESS_MS,
                multisampled.raw(SceneBufferChannel.ROUGHNESS).name());
        assertEquals(4, multisampled.raw(SceneBufferChannel.ROUGHNESS).samples());
    }

    @Test
    void passAndTargetNamesStayStable() {
        assertEquals("SsrPass", PostProcessTargets.SSR_PASS);
        assertEquals("ssrColor", PostProcessTargets.SSR_COLOR);
        assertEquals("sceneRoughness", PostProcessTargets.SCENE_ROUGHNESS);
        assertEquals("sceneRoughnessMS", PostProcessTargets.SCENE_ROUGHNESS_MS);
    }

    @Test
    void productionShaderKeepsTheBoundedMarchAndExactFallbackShape() throws IOException {
        String source;
        try (var stream = SsrContractTest.class.getResourceAsStream(
                "/shaders/postprocess/ssr.frag")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(source.contains("#version 460 core"));
        assertTrue(source.contains("#define SSR_MAX_STEPS 64"),
                "the shader loop bound must match SsrSettings.MAXIMUM_STEPS");
        assertTrue(source.contains("#define SSR_REFINE_STEPS 4"),
                "the bisection refinement must stay bounded");
        assertFalse(source.contains("while ("),
                "an unbounded loop would break the cost contract");
        assertTrue(source.contains("step <= SSR_MAX_STEPS"),
                "every march must be clamped by the compile-time budget");
        assertTrue(source.contains("uThickness"), "the hit window must be a uniform");
        assertTrue(source.contains("uRoughnessCutoff"), "rough surfaces must be rejected");
        assertTrue(source.contains("uMinimumConfidence"), "the confidence floor must be applied");
        assertTrue(source.contains("base + weight * (hitRadiance - environmentRadiance)"),
                "zero weight must leave the forward environment colour untouched");
        assertTrue(source.contains("fetchPixel(uRoughness, uv)"),
                "roughness must be read from the shared surface channel");
        assertTrue(source.contains("texelFetch(source, pixel, 0)"),
                "depth and geometric normals must be point-sampled, never filtered");
        assertTrue(source.contains("textureLod(uPrefilteredMap"),
                "the fallback must be the existing environment/IBL radiance");
        assertFalse(source.contains("uJitter"),
                "the march is projected with the stable view-projection");
    }

    @Test
    void stepBudgetMatchesTheDocumentedBounds() {
        assertEquals(64, SsrSettings.MAXIMUM_STEPS);
        assertTrue(SsrSettings.builder().maxSteps(64).build().maxSteps() <= 64);
    }
}
