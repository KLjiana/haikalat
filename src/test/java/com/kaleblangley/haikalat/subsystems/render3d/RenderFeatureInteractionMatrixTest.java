package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6 representative interaction matrix at the production topology/policy level.
 *
 * <p>The 46-feature matrix does not enumerate a cartesian product. These cases
 * pin the high-risk combinations from the delivery plan: water/glass, cloud and
 * smoke media, particle transparency strategies, hair with the AA modes, and
 * the exact no-op default. The per-feature pixel contracts stay in their own GL
 * fixtures.</p>
 */
class RenderFeatureInteractionMatrixTest {
    @Test
    void disabledDefaultsLeaveTheBaselineTopologyUnchanged() {
        Scene scene = scene();
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology explicit = capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), WaterSurfaceSettings.disabled(),
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), WeightedOitSettings.disabled(), false,
                StochasticTransparencySettings.disabled(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.disabled());
        assertEquals(baseline, explicit,
                "all-disabled requests must keep the topology key byte-identical");
    }

    @Test
    void waterGlassCombinationEnablesPlanarAndCausticsTogether() {
        Scene scene = scene();
        PipelineTopology topology = capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(),
                WaterSurfaceSettings.builder().enabled(true).level(0.0f)
                        .bounds(-20.0f, -20.0f, 20.0f, 20.0f).build(),
                PlanarReflectionSettings.builder().enabled(true).planeY(0.0f).build(),
                caustics(), VolumetricCloudSettings.disabled(),
                SmokeSimulationSettings.disabled(), SmokeRenderSettings.disabled(),
                WeightedOitSettings.disabled(), false,
                StochasticTransparencySettings.disabled(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.disabled());
        assertTrue(topology.waterSurface());
        assertTrue(topology.planarReflections());
        assertTrue(topology.caustics());
        new PipelineFeaturePolicy(topology, true).validate();
    }

    @Test
    void cloudAndSmokeMediaStaySingleSampleHdrAndRejectMsaa() {
        Scene scene = scene();
        PipelineTopology active = capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), WaterSurfaceSettings.disabled(),
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                clouds(), smokeSimulation(), smokeRender(),
                WeightedOitSettings.disabled(), false,
                StochasticTransparencySettings.disabled(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.disabled());
        assertTrue(active.volumetricClouds());
        assertTrue(active.volumetricSmoke());
        new PipelineFeaturePolicy(active, true).validate();

        PipelineTopology msaa = capture(scene, hdr(AntiAliasingMode.MSAA),
                PostProcessSettings.defaults(), WaterSurfaceSettings.disabled(),
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                clouds(), smokeSimulation(), smokeRender(),
                WeightedOitSettings.disabled(), false,
                StochasticTransparencySettings.disabled(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.disabled());
        assertFalse(msaa.volumetricClouds());
        assertFalse(msaa.volumetricSmoke());
    }

    @Test
    void hairAcceptsTheSingleSampleRasterAndRejectsMsaa() {
        Scene scene = scene();
        PipelineTopology none = capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), WaterSurfaceSettings.disabled(),
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), WeightedOitSettings.disabled(), false,
                StochasticTransparencySettings.disabled(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.builder().build());
        assertTrue(none.hairStrands());
        new PipelineFeaturePolicy(none, true).validate();

        PipelineTopology msaa = capture(scene, hdr(AntiAliasingMode.MSAA),
                PostProcessSettings.defaults(), WaterSurfaceSettings.disabled(),
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), WeightedOitSettings.disabled(), false,
                StochasticTransparencySettings.disabled(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.builder().build());
        assertFalse(msaa.hairStrands(),
                "MSAA hair is reported unavailable instead of failing shared-colour allocation");
    }

    @Test
    void oitAndStochasticDeclareIndependentStrategies() {
        Scene scene = scene();
        PipelineTopology topology = capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), WaterSurfaceSettings.disabled(),
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), WeightedOitSettings.defaults(), true,
                StochasticTransparencySettings.defaults(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.disabled());
        assertTrue(topology.weightedOit());
        assertTrue(topology.stochasticTransparency());
        new PipelineFeaturePolicy(topology, true).validate();
    }

    @Test
    void ldrRequestsKeepCloudsAndSmokeUnavailableWithoutChangingTheKey() {
        Scene scene = scene();
        RenderSettings ldrSettings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.NONE).vsync(false).build();
        PipelineTopology baseline = PipelineTopology.capture(scene, ldrSettings,
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology ldr = capture(scene, ldrSettings, PostProcessSettings.defaults(),
                WaterSurfaceSettings.disabled(), PlanarReflectionSettings.disabled(),
                CausticsSettings.disabled(), clouds(), smokeSimulation(), smokeRender(),
                WeightedOitSettings.disabled(), false,
                StochasticTransparencySettings.disabled(), DdgiSettings.disabled(),
                VctSettings.disabled(), HairStrandSettings.disabled());
        assertEquals(baseline, ldr);
        assertFalse(ldr.volumetricClouds());
        assertFalse(ldr.volumetricSmoke());
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings,
                                            PostProcessSettings effects,
                                            WaterSurfaceSettings water,
                                            PlanarReflectionSettings planar,
                                            CausticsSettings caustics,
                                            VolumetricCloudSettings clouds,
                                            SmokeSimulationSettings smokeSimulation,
                                            SmokeRenderSettings smokeRender,
                                            WeightedOitSettings oit, boolean oitDeclared,
                                            StochasticTransparencySettings stochastic,
                                            DdgiSettings ddgi, VctSettings vct,
                                            HairStrandSettings hair) {
        return PipelineTopology.capture(scene, settings, effects, 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, water, VolumetricParticleSettings.disabled(),
                stochastic, clouds, smokeSimulation, smokeRender,
                CloudShadowSettings.disabled(), oit, oitDeclared, planar, caustics, ddgi, vct,
                hair);
    }

    private static Scene scene() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.directional(new Vector3f(0.1f, -1.0f, 0.2f),
                new Vector3f(1.0f), 1.0f));
        return scene;
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder().antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES).vsync(false).build();
    }

    private static CausticsSettings caustics() {
        return CausticsSettings.builder().enabled(true).waterPlane(0.0f)
                .waterRegion(-20.0f, -20.0f, 20.0f, 20.0f)
                .receiverHeight(-8.0f, -0.5f).intensity(3.0f).build();
    }

    private static VolumetricCloudSettings clouds() {
        return VolumetricCloudSettings.disabled().withEnabled(true)
                .withCoverageField(new CloudShadowSettings(true, 0.85f, 0.3f, 0.12f, 1.2f, 0.65f));
    }

    private static SmokeSimulationSettings smokeSimulation() {
        return new SmokeSimulationSettings(true, 16, 16, 16, new Vector3f(),
                new Vector3f(1.5f), 0.02f, new Vector3f(), 1.0f, 4.0f, 1.0f, 1.0f, 4,
                List.of(new SmokeSimulationSettings.Source(new Vector3f(), 1.0f, 4.0f, 2.0f,
                        0.0f, 0.4f)));
    }

    private static SmokeRenderSettings smokeRender() {
        return new SmokeRenderSettings(true, 0.3f, 0.7f, 2.0f,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.0f, 2.5f, 2.0f), 0.2f,
                32, 0.05f, SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f);
    }
}
