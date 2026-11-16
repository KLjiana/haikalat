package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.PipelineFeaturePolicy;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterRefractionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTopologyTest {
    @Test
    void keyIncludesExtentSamplesPassFeaturesAndShadowKinds() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA)
                .msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.AUTO)
                .bloomSettings(BloomSettings.builder().enabled(true).maxLevels(4).build())
                .build();
        PostProcessSettings effects = PostProcessSettings.builder()
                .fog(FogSettings.builder().build())
                .build();
        PipelineTopology base = PipelineTopology.capture(
                scene, settings, effects, 1280, 720, false, false,
                DirectionalCascadeSettings.disabled());

        assertEquals(4, base.sampleCount());
        assertEquals(4, base.bloomLevels());
        assertNotEquals(base, base.withExtent(1920, 1080));
        assertNotEquals(base, PipelineTopology.capture(
                scene, settings, effects, 1280, 720, true, false,
                DirectionalCascadeSettings.disabled()));
        assertNotEquals(base, PipelineTopology.capture(
                scene, settings, effects, 1280, 720, false, false,
                new DirectionalCascadeSettings(4, 4096, 0.6f, 0.08f, Float.MAX_VALUE)));

        scene.addLight(SceneLight.shadowedPoint(new Vector3f(), new Vector3f(1.0f), 1.0f, 10.0f));
        PipelineTopology shadowed = PipelineTopology.capture(
                scene, settings, effects, 1280, 720, false, false,
                DirectionalCascadeSettings.disabled());
        assertNotEquals(base, shadowed);
        assertEquals(true, shadowed.pointShadow());
    }

    @Test
    void runtimeOnlyValuesDoNotChangeTopology() {
        Scene scene = new Scene(new Camera());
        RenderSettings first = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(1.0f)
                .bloomSettings(BloomSettings.builder().enabled(true)
                        .maxLevels(3).threshold(1.0f).intensity(0.1f).build())
                .build();
        RenderSettings second = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .exposure(2.0f)
                .bloomSettings(BloomSettings.builder().enabled(true)
                        .maxLevels(3).threshold(2.0f).intensity(0.4f).build())
                .build();

        assertEquals(PipelineTopology.capture(scene, first, PostProcessSettings.defaults(),
                        800, 600, false, false, DirectionalCascadeSettings.disabled()),
                PipelineTopology.capture(scene, second, PostProcessSettings.defaults(),
                        800, 600, false, false, DirectionalCascadeSettings.disabled()));
    }

    @Test
    void depthOfFieldRequestsSharedDepthWithoutUnrelatedSurfaceChannels() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES).build();
        PostProcessSettings effects = PostProcessSettings.builder()
                .depthOfField(DepthOfFieldSettings.of(4.0f, 2.8f, 8.0f)).build();
        PipelineTopology topology = PipelineTopology.capture(scene, settings, effects,
                64, 64, false, false, DirectionalCascadeSettings.disabled());

        assertTrue(topology.sceneSurface().requests()
                .requires(SceneBufferChannel.DEPTH));
        assertFalse(topology.sceneSurface().requests()
                .requires(SceneBufferChannel.NORMAL));
        assertFalse(topology.sceneSurface().hasResolve());
    }

    @Test
    void motionBlurRequestsValidatedVelocityWithoutUnrelatedSurfaceChannels() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES).build();
        PostProcessSettings effects = PostProcessSettings.builder()
                .motionBlur(MotionBlurSettings.of(0.5f, 16.0f)).build();
        PipelineTopology topology = PipelineTopology.capture(scene, settings, effects,
                64, 64, false, false, DirectionalCascadeSettings.disabled());

        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.VELOCITY));
        assertTrue(topology.sceneSurface().requests()
                .requires(SceneBufferChannel.PREVIOUS_SURFACE_DEPTH));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.VALIDITY));
        assertFalse(topology.sceneSurface().requests().requires(SceneBufferChannel.NORMAL));
        assertFalse(topology.sceneSurface().hasResolve());
    }

    @Test
    void msaaMotionBlurResolvesTheSingleSampleVelocityItConsumes() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES).build();
        PostProcessSettings effects = PostProcessSettings.builder()
                .motionBlur(MotionBlurSettings.of(0.5f, 16.0f)).build();
        PipelineTopology topology = PipelineTopology.capture(scene, settings, effects,
                64, 64, false, false, DirectionalCascadeSettings.disabled());

        assertTrue(topology.sceneSurface().hasResolve());
        assertTrue(topology.sceneSurface().resolve().stream()
                .anyMatch(attachment -> attachment.channel() == SceneBufferChannel.VELOCITY));
        assertFalse(topology.sceneSurface().requests().requires(SceneBufferChannel.NORMAL));
    }

    @Test
    void ssrRequestsResolvedDepthNormalAndRoughnessAndRequiresPbr() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES).build();
        PostProcessSettings effects = PostProcessSettings.builder()
                .ssr(SsrSettings.defaults()).build();
        PipelineTopology topology = PipelineTopology.capture(scene, settings, effects,
                64, 64, false, false, DirectionalCascadeSettings.disabled());

        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.DEPTH));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.NORMAL));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.ROUGHNESS));
        assertTrue(topology.sceneSurface().hasResolve(),
                "MSAA renders the roughness channel multisampled and resolves it");
        var roughness = topology.sceneSurface().single(SceneBufferChannel.ROUGHNESS);
        assertEquals(PostProcessTargets.SCENE_ROUGHNESS, roughness.name());
        assertEquals(RenderFormat.R8, roughness.format());
        assertEquals(1, roughness.samples());

        assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(topology, true).validate(),
                "screen-space reflections need a metallic-roughness PBR material");
    }

    @Test
    void subsurfaceScatteringRequestsDepthNormalAndTheResolvedMaterialMask() {
        Scene scene = new Scene(new Camera());
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES).build();
        PostProcessSettings disabled = PostProcessSettings.defaults();
        PipelineTopology without = PipelineTopology.capture(scene, settings, disabled,
                64, 64, false, false, DirectionalCascadeSettings.disabled());
        assertFalse(without.sceneSurface().requests()
                .requires(SceneBufferChannel.SUBSURFACE));
        assertFalse(without.sceneSurface().hasResolve());

        PostProcessSettings effects = PostProcessSettings.builder()
                .subsurfaceScattering(
                        com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings
                                .ofSkin())
                .build();
        PipelineTopology topology = PipelineTopology.capture(scene, settings, effects,
                64, 64, false, false, DirectionalCascadeSettings.disabled());

        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.DEPTH));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.NORMAL));
        assertTrue(topology.sceneSurface().requests().requires(SceneBufferChannel.SUBSURFACE));
        assertTrue(topology.sceneSurface().hasResolve(),
                "MSAA renders the mask multisampled and resolves it");
        var mask = topology.sceneSurface().single(SceneBufferChannel.SUBSURFACE);
        assertEquals(PostProcessTargets.SCENE_SUBSURFACE, mask.name());
        assertEquals(RenderFormat.RGBA16F, mask.format());
        assertEquals(1, mask.samples());
        assertNotEquals(without, topology);
    }

    @Test
    void gtaoTopologyUsesCeilHalfExtentAndRequiresPbr() {
        Scene scene = new Scene(new Camera());
        PostProcessSettings gtao = PostProcessSettings.builder()
                .gtao(GtaoSettings.defaults().withEnabled(true)).build();
        PipelineTopology topology = PipelineTopology.capture(scene,
                RenderSettings.builder().build(), gtao, 5, 3, false, false,
                DirectionalCascadeSettings.disabled());

        assertEquals(true, topology.gtaoEnabled());
        assertEquals(3, topology.gtaoHalfWidth());
        assertEquals(2, topology.gtaoHalfHeight());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(topology, false).validate());
    }

    @Test
    void waterSurfaceRequestsRasterDepthAndRejectsUnsupportedCombinations() {
        Scene scene = new Scene(new Camera());
        RenderSettings hdrMsaa = RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.ACES)
                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4).build();
        WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true).build();
        LocalShadowPipelineSettings localShadows = LocalShadowPipelineSettings.legacyDefaults();
        PipelineTopology base = PipelineTopology.capture(scene, hdrMsaa,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(), localShadows,
                VolumetricFogSettings.disabled(), true, true, true);
        PipelineTopology withWater = PipelineTopology.capture(scene, hdrMsaa,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(), localShadows,
                VolumetricFogSettings.disabled(), true, true, true, water);

        assertFalse(base.waterSurface());
        assertTrue(withWater.waterSurface());
        assertNotEquals(base, withWater);
        assertEquals(4, withWater.sceneSurface().raw(SceneBufferChannel.DEPTH).samples());
        assertFalse(withWater.sceneSurface().hasResolve(),
                "water reads the raster depth directly; no MSAA resolve copy is required");
        new PipelineFeaturePolicy(withWater, false).validate();

        PipelineTopology ldr = PipelineTopology.capture(scene, RenderSettings.builder().build(),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(), localShadows,
                VolumetricFogSettings.disabled(), true, true, true, water);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(ldr, false).validate());

        PipelineTopology volume = PipelineTopology.capture(scene, hdrMsaa,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(), localShadows,
                new VolumetricFogSettings(true, 16, VolumetricFogSettings.Quality.LOW,
                        FogMediumSettings.vacuum(), java.util.List.of(), 0.0f, false, 0.0f,
                        1337, new Vector3f()), true, true, true, water);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(volume, false).validate());
    }

    @Test
    void projectedDecalTopologyRestoresBaselineAndParticipatesInTheGenerationKey() {
        Scene scene = new Scene(new Camera());
        RenderSettings hdr = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES).build();
        RenderSettings hdrMsaa = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES).build();
        PostProcessSettings effects = PostProcessSettings.defaults();
        ProjectedDecalSettings active = new ProjectedDecalSettings(
                true, 0.6f, 0.02f, 0.5f, false, true, 8);

        PipelineTopology baseline = captureDecals(scene, hdr, effects,
                ProjectedDecalSettings.disabled(), false, true);
        assertFalse(baseline.projectedDecals());
        assertEquals(baseline, captureDecals(scene, hdr, effects,
                        ProjectedDecalSettings.disabled(), true, true),
                "a disabled batch must ignore the registered source and keep the baseline key");
        assertEquals(baseline, captureDecals(scene, hdr, effects, active, false, true),
                "an enabled batch without a registered renderer/source has no stage");
        assertEquals(baseline, captureDecals(scene, hdr, effects, active, true, false),
                "an enabled batch without a single-sample depth copy has no stage");
        PipelineTopology ldr = PipelineTopology.capture(scene,
                RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                        .toneMappingMode(ToneMappingMode.NONE).build(),
                effects, 64, 48, false, false,
                DirectionalCascadeSettings.disabled(), LocalShadowPipelineSettings.legacyDefaults(),
                VolumetricFogSettings.disabled(), true, true, true,
                WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled(),
                WeightedOitSettings.disabled(), false,
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(),
                HairStrandSettings.disabled(), ContactShadowSettings.disabled(),
                WaterRefractionSettings.disabled(), active, true, true);
        assertFalse(ldr.hdr());
        assertFalse(ldr.projectedDecals(),
                "projected decals require the HDR linear colour chain");

        PipelineTopology enabled = captureDecals(scene, hdr, effects, active, true, true);
        assertTrue(enabled.projectedDecals());
        assertNotEquals(baseline, enabled,
                "an eligible decal batch must change the generation key");
        assertTrue(enabled.withExtent(128, 96).projectedDecals(),
                "the extent candidate must retain the decal stage");
        assertEquals(enabled, captureDecals(scene, hdr, effects,
                        new ProjectedDecalSettings(true, 1.2f, 0.02f, 0.5f, false, true, 8),
                        true, true),
                "batch tuning values stay outside the topology key; only participation does");

        PipelineTopology msaa = captureDecals(scene, hdrMsaa, effects, active, true, true);
        assertTrue(msaa.projectedDecals(),
                "MSAA is supported through the resolved single-sample depth copy");
        assertEquals(4, msaa.sampleCount());
        assertFalse(msaa.sceneSurface().hasResolve(),
                "the decal stage owns its depth resolve; no extra shared surface resolve is required");
    }

    private static PipelineTopology captureDecals(Scene scene, RenderSettings settings,
                                                  PostProcessSettings effects,
                                                  ProjectedDecalSettings decals,
                                                  boolean sourcePresent,
                                                  boolean depthAvailable) {
        return PipelineTopology.capture(scene, settings, effects, 64, 48, false, false,
                DirectionalCascadeSettings.disabled(), LocalShadowPipelineSettings.legacyDefaults(),
                VolumetricFogSettings.disabled(), true, true, true,
                WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled(),
                WeightedOitSettings.disabled(), false,
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(),
                HairStrandSettings.disabled(), ContactShadowSettings.disabled(),
                WaterRefractionSettings.disabled(), decals, sourcePresent, depthAvailable);
    }

    @Test
    void emptyGenerationCloseIsIdempotentAndIdsAreDistinct() {
        PipelineTopology topology = PipelineTopology.capture(new Scene(new Camera()),
                RenderSettings.builder().build(), PostProcessSettings.defaults(),
                32, 32, false, false, DirectionalCascadeSettings.disabled());
        PipelineGeneration first = new PipelineGeneration(topology);
        PipelineGeneration second = new PipelineGeneration(topology);
        assertNotEquals(first.id(), second.id());

        first.close();
        first.close();
        assertEquals(true, first.isClosed());
    }

    // Merged from the former DirectionalCascadeSettingsTest: cascade layout is part of
    // the shadow topology contract this class already captures.
    @Test
    void cascadeSettingsDriveAtlasLayoutAndRejectUnsupportedCounts() {
        assertEquals(1024, new DirectionalCascadeSettings(
                2, 2048, 0.5f, 0.05f, Float.MAX_VALUE).tileSize());

        DirectionalCascadeSettings three = new DirectionalCascadeSettings(
                3, 4096, 0.5f, 0.05f, Float.MAX_VALUE);
        assertEquals(2, three.columns());
        assertEquals(2, three.rows());
        assertEquals(2048, three.tileSize());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DirectionalCascadeSettings(5, 4096, 0.5f, 0.05f, Float.MAX_VALUE));
    }
}
