package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JVM composition and dependency contract for #39.
 *
 * <p>The colour-chain pass itself needs a GL context to compile, so the live
 * insertion and resize/close paths are covered by {@code SsgiPassGlTest}. This
 * class pins the pure parts: the requested surface channels, the albedo
 * attachment and MSAA resolve, the topology merge, the baseline pass order when
 * disabled and the fail-closed LDR rejection that happens before any shader is
 * created.</p>
 */
class SsgiPipelineCompositionTest {
    @Test
    void ssgiRequiresDepthNormalRoughnessAndAlbedoWithoutTemporalChannels() {
        SceneBufferRequirements requirements = SceneBufferRequirements.ssgi();
        assertTrue(requirements.requires(SceneBufferChannel.DEPTH));
        assertTrue(requirements.requires(SceneBufferChannel.NORMAL));
        assertTrue(requirements.requires(SceneBufferChannel.ROUGHNESS));
        assertTrue(requirements.requires(SceneBufferChannel.ALBEDO));
        assertFalse(requirements.requires(SceneBufferChannel.VELOCITY),
                "the first SSGI version is spatial and owns no history");
        assertFalse(requirements.requires(SceneBufferChannel.SUBSURFACE),
                "SSGI must not silently depend on the SSS mask");
        assertEquals(1, requirements.samples());
    }

    @Test
    void albedoIsAnRgba8AttachmentResolvedWithTheNearestDepthSample() {
        SceneSurfaceLayout single = SceneSurfaceLayout.plan(1, SceneBufferRequirements.none(),
                SceneBufferRequirements.ssgi(), SceneSurfaceLayout.ReactiveMode.NONE);
        var albedo = single.single(SceneBufferChannel.ALBEDO);
        assertNotNull(albedo);
        assertEquals(PostProcessTargets.SCENE_ALBEDO, albedo.name());
        assertEquals(RenderFormat.RGBA8, albedo.format());
        assertEquals(1, albedo.samples());
        assertEquals(PostProcessTargets.SCENE_SURFACE_PASS, albedo.producer());

        SceneSurfaceLayout multisampled = SceneSurfaceLayout.plan(4,
                SceneBufferRequirements.none(), SceneBufferRequirements.ssgi(),
                SceneSurfaceLayout.ReactiveMode.NONE);
        var resolved = multisampled.single(SceneBufferChannel.ALBEDO);
        assertEquals(PostProcessTargets.SCENE_ALBEDO, resolved.name());
        assertEquals(PostProcessTargets.SCENE_SURFACE_RESOLVE_PASS, resolved.producer());
        assertEquals(PostProcessTargets.SCENE_ALBEDO_MS,
                multisampled.raw(SceneBufferChannel.ALBEDO).name());
        assertEquals(4, multisampled.raw(SceneBufferChannel.ALBEDO).samples());
        assertTrue(multisampled.hasResolve());
    }

    @Test
    void topologyRequestsTheSharedInputsOnlyWhenSsgiIsActive() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES).build();
        PipelineTopology without = PipelineTopology.capture(scene, settings,
                PostProcessSettings.defaults(), 64, 64, false, false,
                DirectionalCascadeSettings.disabled());
        assertFalse(without.sceneSurface().requests().requires(SceneBufferChannel.ALBEDO));
        assertFalse(without.sceneSurface().hasResolve());
        assertEquals(0, without.sceneSurface().raster().size());

        PostProcessSettings effects = PostProcessSettings.defaults()
                .withSsgi(SsgiSettings.defaults());
        PipelineTopology topology = PipelineTopology.capture(scene, settings, effects,
                64, 64, false, false, DirectionalCascadeSettings.disabled());
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.DEPTH));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.NORMAL));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.ROUGHNESS));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.ALBEDO));
        assertTrue(topology.sceneSurface().hasResolve(),
                "MSAA renders the albedo attachment multisampled and resolves it");
        assertTrue(topology.sceneSurface().resolve().stream()
                .anyMatch(attachment -> attachment.channel() == SceneBufferChannel.ALBEDO));
    }

    @Test
    void disabledRequestKeepsTheBaselineColourChainOrder() {
        assertFalse(PostProcessSettings.defaults().ssgi().active());
        for (AntiAliasingMode mode : new AntiAliasingMode[]{
                AntiAliasingMode.NONE, AntiAliasingMode.FXAA, AntiAliasingMode.TAA,
                AntiAliasingMode.MSAA}) {
            List<String> passes = PostProcessPipeline.passNamesFor(mode, ToneMappingMode.ACES);
            assertFalse(passes.contains(PostProcessTargets.SSGI_PASS),
                    "the baseline " + mode + " chain must not contain SSGI");
        }
        assertEquals("SsgiPass", PostProcessTargets.SSGI_PASS);
        assertEquals("ssgiColor", PostProcessTargets.SSGI_COLOR);
    }

    @Test
    void ssgiFailsClosedWithoutHdrBeforeAnyPassIsCreated() {
        RenderSettings ldr = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.NONE)
                .vsync(false)
                .build();
        PostProcessSettings effects = PostProcessSettings.defaults()
                .withSsgi(SsgiSettings.defaults());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.create(ldr, effects, 64, 64, null,
                        NO_SURFACE, null, null, null, null));
        assertTrue(failure.getMessage().contains("HDR"),
                "SSGI must reject a non-HDR chain with an explicit reason");
    }

    @Test
    void settingsCarryTheRequestAndTheBuilderRoundTripsIt() {
        SsgiSettings tuned = SsgiSettings.builder().enabled(true).sampleCount(4)
                .stepCount(4).maxDistanceMetres(6.0f).thicknessMetres(0.4f)
                .strength(0.6f).maximumOffscreenFraction(0.25f)
                .ambientFallback(0.1f, 0.1f, 0.1f).build();
        PostProcessSettings effects = PostProcessSettings.builder()
                .ssgi(tuned)
                .build();
        assertEquals(tuned, effects.ssgi());
        assertEquals(tuned, effects.withSsgi(tuned).ssgi());
        assertEquals(PostProcessSettings.defaults().fog(), effects.fog(),
                "adding SSGI must not disturb the other effects");
    }

    @Test
    void ssgiFailsClosedWithTemporalUpscalingBeforeAnyPassIsCreated() {
        RenderSettings hdr = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
        PostProcessSettings effects = PostProcessSettings.defaults()
                .withSsgi(SsgiSettings.defaults())
                .withTemporalUpscaling(
                        com.kaleblangley.haikalat.subsystems.postprocess
                                .TemporalUpscalingSettings.defaults());
        com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSource source =
                new com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSource(
                        1, 2, 64, 64);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.create(hdr, effects, 64, 64, null, NO_SURFACE,
                        null, null, source, null));
        assertTrue(failure.getMessage().contains("indirect diffuse"),
                "the full-resolution SSGI surface inputs are mutually exclusive with the "
                        + "low-resolution temporal upscaler");
    }

    private static final SurfaceInputs NO_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return null;
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                           Input input) {
            throw new UnsupportedOperationException();
        }
    };
}
