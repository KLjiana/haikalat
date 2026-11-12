package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineFeaturePolicy;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JVM-only production wiring contract for bounded water caustics. */
class CausticsPipelineWiringTest {
    @Test
    void disabledDefaultsLeaveTheTopologyUnchanged() {
        Scene scene = sceneWithLight(true);
        PipelineTopology withWaterOnly = capture(scene, water(),
                CausticsSettings.disabled());
        assertFalse(withWaterOnly.caustics());
        assertEquals(capture(scene, water(), CausticsSettings.disabled()), withWaterOnly,
                "a disabled caustics request must not change the water topology");
    }

    @Test
    void thePassKeyRequiresWaterADownwardDirectionalLightAndHdr() {
        CausticsSettings active = CausticsSettings.builder().enabled(true)
                .waterPlane(0.0f).build();
        assertTrue(capture(sceneWithLight(true), water(), active).caustics(),
                "water + downward directional light + HDR compose the pass");
        assertFalse(capture(new Scene(new Camera()), water(), active).caustics(),
                "caustics need a directional light");
        assertFalse(capture(sceneWithLight(false), water(), active).caustics(),
                "an upward light has no water-entry projection");
        assertFalse(capture(sceneWithLight(true), WaterSurfaceSettings.disabled(), active)
                        .caustics(),
                "caustics require the finite water surface they are composed under");
        assertFalse(capture(sceneWithLight(true), water(), active,
                        RenderSettings.builder().build()).caustics(),
                "caustics require HDR tone mapping");
    }

    @Test
    void inconsistentTopologyFailsClosedAtAssembly() {
        Scene scene = sceneWithLight(true);
        PipelineTopology valid = capture(scene, water(),
                CausticsSettings.builder().enabled(true).waterPlane(0.0f).build());
        assertTrue(valid.caustics());
        new PipelineFeaturePolicy(valid, false).validate();

        PipelineTopology withoutWater = copy(valid, false, true);
        IllegalStateException waterFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(withoutWater, false).validate());
        assertTrue(waterFailure.getMessage().contains("active finite water surface"));

        PipelineTopology withoutHdr = copy(valid, true, true, false);
        IllegalStateException hdrFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(withoutHdr, false).validate());
        assertTrue(hdrFailure.getMessage().contains("HDR tone mapping"));

        PipelineTopology multisampled = copy(valid, true, true, valid.hdr(),
                valid.antiAliasingMode(), 4);
        IllegalStateException msaaFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(multisampled, false).validate());
        assertTrue(msaaFailure.getMessage().contains("single-sample scene colour"));
    }

    private static PipelineTopology capture(Scene scene, WaterSurfaceSettings water,
                                            CausticsSettings caustics) {
        return capture(scene, water, caustics, hdr());
    }

    private static PipelineTopology capture(Scene scene, WaterSurfaceSettings water,
                                            CausticsSettings caustics, RenderSettings settings) {
        return PipelineTopology.capture(scene, settings, PostProcessSettings.defaults(),
                64, 48, false, false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, water, VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(), WeightedOitSettings.disabled(),
                false, PlanarReflectionSettings.disabled(), caustics);
    }

    private static PipelineTopology copy(PipelineTopology base, boolean water, boolean caustics) {
        return copy(base, water, caustics, base.hdr());
    }

    private static PipelineTopology copy(PipelineTopology base, boolean water, boolean caustics,
                                         boolean hdr) {
        return copy(base, water, caustics, hdr, base.antiAliasingMode(), base.sampleCount());
    }

    private static PipelineTopology copy(PipelineTopology base, boolean water, boolean caustics,
                                         boolean hdr, AntiAliasingMode mode, int sampleCount) {
        return new PipelineTopology(base.width(), base.height(), mode,
                sampleCount, hdr, base.bloom(), base.bloomLevels(),
                base.automaticExposure(), base.colorGrading(), base.fog(),
                base.directionalShadow(), base.pointShadow(), base.spotShadow(),
                base.directionalCascadeCount(), base.directionalShadowAtlasSize(),
                base.pointShadowCapacity(), base.pointShadowResolution(),
                base.spotShadowCapacity(), base.spotShadowResolution(), base.pbrMaterials(),
                base.hdrVfx(), base.embedded(), base.gtaoEnabled(), base.gtaoTemporal(),
                base.gtaoHalfWidth(), base.gtaoHalfHeight(), base.sceneSurface(),
                base.volumetricFog(), base.volumetricQuality(), base.volumetricEmission(),
                water, base.volumetricParticles(), base.stochasticTransparency(),
                base.volumetricClouds(), base.volumetricSmoke(),
                base.weightedOit(), base.planarReflections(), caustics);
    }

    private static Scene sceneWithLight(boolean downward) {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.directional(
                downward ? new Vector3f(0.0f, -1.0f, -0.3f) : new Vector3f(0.0f, 1.0f, 0.3f),
                new Vector3f(1.0f), 1.0f));
        return scene;
    }

    private static WaterSurfaceSettings water() {
        return WaterSurfaceSettings.builder().enabled(true).build();
    }

    private static RenderSettings hdr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }
}
