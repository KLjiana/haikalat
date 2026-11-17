package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
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
import com.kaleblangley.haikalat.subsystems.render3d.water.UnderwaterVolumeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterRefractionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-version water surface and single-layer refraction both own the
 * post-process scene-colour producer; the combination must fail loudly instead
 * of silently discarding one of them.  SSR is opaque-only, so it must also
 * reject both producers independently rather than reflect through them.
 *
 * <p>The second half is the assembly-time {@code renderScale} matrix: every
 * topology that claims a full-resolution scene composition must fail closed
 * below scale one and stay valid at the {@code 1.0} no-op.</p>
 */
class PipelineFeaturePolicyTest {
    @Test
    void rejectsRefractionAndWaterSurfaceTogether() {
        PipelineFeaturePolicy policy = new PipelineFeaturePolicy(
                topology(WaterSurfaceSettings.disabled().withEnabled(true)), true);
        assertDoesNotThrow(policy::validate);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> policy.validateRefractionWater(true));
        assertTrue(failure.getMessage().contains("mutually exclusive"));
        assertDoesNotThrow(() -> policy.validateRefractionWater(false));

        IllegalStateException waterSsr = assertThrows(IllegalStateException.class,
                () -> policy.validateSsrComposition(true, false));
        assertTrue(waterSsr.getMessage().contains("finite water surface"));
        assertThrows(IllegalStateException.class,
                () -> policy.validateSsrComposition(true, true));
        assertDoesNotThrow(() -> policy.validateSsrComposition(false, true));
    }

    @Test
    void rejectsSsrWithRefractionWhenWaterIsDisabled() {
        PipelineFeaturePolicy policy = new PipelineFeaturePolicy(
                topology(WaterSurfaceSettings.disabled()), true);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> policy.validateSsrComposition(true, true));
        assertTrue(failure.getMessage().contains("single-layer refraction"));
        assertDoesNotThrow(() -> policy.validateSsrComposition(true, false));
        assertDoesNotThrow(() -> policy.validateSsrComposition(false, true));
    }

    private static PipelineTopology topology(WaterSurfaceSettings water) {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false).build();
        return PipelineTopology.capture(
                new Scene(new Camera()), settings, PostProcessSettings.defaults(),
                64, 64, false, false, DirectionalCascadeSettings.defaults(),
                LocalShadowPipelineSettings.legacyDefaults(),
                VolumetricFogSettings.disabled(), false, false, false, water);
    }

    // ---- v0.28.1 low-resolution foundation: topology renderScale matrix ----

    /**
     * One assembly-time composition: the factory captures the topology at the
     * requested scale so the same case exercises the {@code 1.0} control and the
     * fail-closed reduced scale.
     */
    private record TopologyScaleCase(RenderScalePolicy.Feature feature,
                                     Function<Float, PipelineTopology> capture) {
        @Override
        public String toString() {
            return feature.code();
        }
    }

    private static Stream<TopologyScaleCase> topologyScaleCases() {
        return Stream.of(
                new TopologyScaleCase(RenderScalePolicy.Feature.MSAA,
                        scale -> scaleTopology(hdr(scale)
                                .antiAliasingMode(AntiAliasingMode.MSAA).msaaSamples(4).build(),
                                false, WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.TAA,
                        scale -> scaleTopology(hdr(scale)
                                .antiAliasingMode(AntiAliasingMode.TAA).build(),
                                false, WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.BLOOM,
                        scale -> scaleTopology(hdr(scale)
                                .bloomSettings(BloomSettings.builder().enabled(true)
                                        .maxLevels(4).build()).build(),
                                false, WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.AUTO_EXPOSURE,
                        scale -> scaleTopology(hdr(scale)
                                .exposureMode(ExposureMode.AUTO).build(),
                                false, WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.WATER_SURFACE,
                        scale -> scaleTopology(hdr(scale).build(), false,
                                activeWater(), VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.VOLUMETRIC_CLOUDS,
                        scale -> scaleTopology(hdr(scale).build(), false,
                                WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled().withEnabled(true),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.WEIGHTED_OIT,
                        scale -> scaleTopology(hdr(scale).build(), false,
                                WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.defaults(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.DDGI,
                        scale -> scaleTopology(hdr(scale).build(), false,
                                WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(),
                                DdgiSettings.disabled().withEnabled(true),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.CAUSTICS,
                        scale -> scaleTopology(hdr(scale).build(), false,
                                activeWater(), VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled().withEnabled(true),
                                ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.PROJECTED_DECALS,
                        scale -> scaleTopology(hdr(scale).build(), false,
                                WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(),
                                new ProjectedDecalSettings(true, 0.6f, 0.02f, 0.5f, false, true, 8),
                                UnderwaterVolumeSettings.disabled())),
                new TopologyScaleCase(RenderScalePolicy.Feature.UNDERWATER_VOLUME,
                        scale -> scaleTopology(hdr(scale).build(), false,
                                activeWater(), VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled().withEnabled(true))),
                new TopologyScaleCase(RenderScalePolicy.Feature.EMBEDDED_TARGET,
                        scale -> scaleTopology(hdr(scale).build(), true,
                                WaterSurfaceSettings.disabled(),
                                VolumetricCloudSettings.disabled(),
                                WeightedOitSettings.disabled(), DdgiSettings.disabled(),
                                CausticsSettings.disabled(), ProjectedDecalSettings.disabled(),
                                UnderwaterVolumeSettings.disabled())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("topologyScaleCases")
    void reducedRenderScaleRejectsTopologyCompositionsAndScaleOneDoesNot(
            TopologyScaleCase testCase) {
        PipelineTopology full = testCase.capture().apply(1.0f);
        assertEquals(1.0f, full.renderScale());
        assertEquals(full.width(), full.sceneWidth());
        assertEquals(full.height(), full.sceneHeight());
        assertDoesNotThrow(() -> new PipelineFeaturePolicy(full, true).validate(),
                "renderScale 1.0 must keep today's assembly policy for "
                        + testCase.feature().code());

        PipelineTopology reduced = testCase.capture().apply(0.5f);
        assertNotEquals(full, reduced);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(reduced, true).validate(),
                testCase.feature().code());
        assertTrue(failure.getMessage().contains(testCase.feature().code()),
                () -> "expected reason '" + testCase.feature().code() + "' but got: "
                        + failure.getMessage());
    }

    private static RenderSettings.Builder hdr(float renderScale) {
        return RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES).vsync(false).renderScale(renderScale);
    }

    private static WaterSurfaceSettings activeWater() {
        return WaterSurfaceSettings.builder().enabled(true).build();
    }

    /**
     * Captures a topology with one directional light (DDGI/caustics need an
     * eligible light) and every unset composition disabled.
     */
    private static PipelineTopology scaleTopology(RenderSettings render, boolean embedded,
                                                  WaterSurfaceSettings water,
                                                  VolumetricCloudSettings clouds,
                                                  WeightedOitSettings oit,
                                                  DdgiSettings ddgi,
                                                  CausticsSettings caustics,
                                                  ProjectedDecalSettings decals,
                                                  UnderwaterVolumeSettings underwater) {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.directional(new Vector3f(-1.0f, -1.0f, -1.0f),
                new Vector3f(1.0f), 1.0f));
        boolean decalSourcePresent = decals.active();
        return PipelineTopology.capture(scene, render, PostProcessSettings.defaults(),
                96, 64, false, embedded, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, water,
                VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(), clouds,
                SmokeSimulationSettings.disabled(), SmokeRenderSettings.disabled(),
                CloudShadowSettings.disabled(), oit, true,
                PlanarReflectionSettings.disabled(), caustics, ddgi, VctSettings.disabled(),
                HairStrandSettings.disabled(), ContactShadowSettings.disabled(),
                WaterRefractionSettings.disabled(), decals, decalSourcePresent, true, underwater);
    }
}
