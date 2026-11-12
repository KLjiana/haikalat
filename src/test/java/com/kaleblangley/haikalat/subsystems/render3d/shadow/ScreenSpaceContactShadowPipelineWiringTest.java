package com.kaleblangley.haikalat.subsystems.render3d.shadow;

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
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM-only production gating contract for the #9 screen-space contact-shadow
 * pass. The pass consumes the camera scene depth, so the topology key requires
 * HDR, a single-sample raster and a directional light; MSAA and LDR requests
 * stay unavailable without changing the graph.
 */
class ScreenSpaceContactShadowPipelineWiringTest {
    private static final ContactShadowSettings ACTIVE =
            new ContactShadowSettings(true, 2.0f, 0.5f, 16, 0.8f);

    @Test
    void disabledDefaultsLeaveTheTopologyAndSurfaceRequestsUnchanged() {
        Scene scene = sceneWithDirectionalLight();
        PipelineTopology disabled = capture(scene, ContactShadowSettings.disabled(), hdr());
        assertFalse(disabled.screenSpaceContactShadows());
        assertEquals(disabled, capture(scene, ContactShadowSettings.disabled(), hdr()),
                "a disabled contact-shadow request must not change the topology key");
        assertFalse(disabled.sceneBuffers().requires(SceneBufferChannel.DEPTH),
                "a disabled request must not force the shared single-sample depth");
    }

    @Test
    void activeRequestRequiresHdrSingleSampleAndADirectionalLight() {
        Scene lit = sceneWithDirectionalLight();
        PipelineTopology active = capture(lit, ACTIVE, hdr());
        assertTrue(active.screenSpaceContactShadows(),
                "HDR + single-sample + directional light must compose the pass");
        assertNotNull(active.sceneSurface().single(SceneBufferChannel.DEPTH),
                "the active pass must own a structural single-sample depth dependency");

        assertFalse(capture(new Scene(new Camera()), ACTIVE, hdr())
                        .screenSpaceContactShadows(),
                "contact shadows need a directional light to march toward");
        assertFalse(capture(lit, ACTIVE, msaaHdr()).screenSpaceContactShadows(),
                "the first version cannot write a multisampled scene colour target");
        assertFalse(capture(lit, ACTIVE, ldr()).screenSpaceContactShadows(),
                "the multiplicative composite requires HDR");
        assertFalse(capture(lit, ACTIVE.withEnabled(false), hdr())
                        .screenSpaceContactShadows(),
                "an explicitly disabled request stays absent");
    }

    @Test
    void inconsistentTopologyFailsClosedAtAssembly() {
        PipelineTopology valid = capture(sceneWithDirectionalLight(), ACTIVE, hdr());
        assertTrue(valid.screenSpaceContactShadows());
        new PipelineFeaturePolicy(valid, false).validate();

        PipelineTopology withoutHdr = copy(valid, false, valid.sampleCount(),
                valid.sceneSurface());
        IllegalStateException hdrFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(withoutHdr, false).validate());
        assertTrue(hdrFailure.getMessage().contains("HDR tone mapping"));

        PipelineTopology multisampled = copy(valid, valid.hdr(), 4,
                valid.sceneSurface());
        IllegalStateException sampleFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(multisampled, false).validate());
        assertTrue(sampleFailure.getMessage().contains("single-sample scene colour"));

        SceneSurfaceLayout noDepth = SceneSurfaceLayout.plan(1,
                SceneBufferRequirements.none(), SceneBufferRequirements.none(),
                SceneSurfaceLayout.ReactiveMode.NONE);
        PipelineTopology withoutDepth = copy(valid, valid.hdr(), 1, noDepth);
        IllegalStateException depthFailure = assertThrows(IllegalStateException.class,
                () -> new PipelineFeaturePolicy(withoutDepth, false).validate());
        assertTrue(depthFailure.getMessage().contains("single-sample scene depth"));
    }

    private static PipelineTopology capture(Scene scene, ContactShadowSettings contact,
                                            RenderSettings render) {
        return PipelineTopology.capture(scene, render, PostProcessSettings.defaults(),
                64, 48, false, false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(), StochasticTransparencySettings.disabled(),
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled(),
                WeightedOitSettings.disabled(), false, PlanarReflectionSettings.disabled(),
                CausticsSettings.disabled(), DdgiSettings.disabled(), VctSettings.disabled(),
                HairStrandSettings.disabled(), contact);
    }

    private static PipelineTopology copy(PipelineTopology base, boolean hdr, int sampleCount,
                                         SceneSurfaceLayout surface) {
        return new PipelineTopology(base.width(), base.height(), base.antiAliasingMode(),
                sampleCount, hdr, base.bloom(), base.bloomLevels(),
                base.automaticExposure(), base.colorGrading(), base.fog(),
                base.directionalShadow(), base.pointShadow(), base.spotShadow(),
                base.directionalCascadeCount(), base.directionalShadowAtlasSize(),
                base.pointShadowCapacity(), base.pointShadowResolution(),
                base.spotShadowCapacity(), base.spotShadowResolution(),
                base.pbrMaterials(), base.hdrVfx(), base.embedded(), base.gtaoEnabled(),
                base.gtaoTemporal(), base.gtaoHalfWidth(), base.gtaoHalfHeight(), surface,
                base.volumetricFog(), base.volumetricQuality(), base.volumetricEmission(),
                base.waterSurface(), base.volumetricParticles(),
                base.stochasticTransparency(), base.volumetricClouds(), base.volumetricSmoke(),
                base.weightedOit(), base.planarReflections(), base.caustics(),
                base.ddgi(), base.voxelConeTracing(), base.hairStrands(),
                base.screenSpaceContactShadows());
    }

    private static Scene sceneWithDirectionalLight() {
        Scene scene = new Scene(new Camera());
        scene.addLight(SceneLight.directional(
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(1.0f), 1.0f));
        return scene;
    }

    private static RenderSettings hdr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static RenderSettings msaaHdr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.MSAA)
                .msaaSamples(4)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static RenderSettings ldr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.NONE)
                .vsync(false)
                .build();
    }
}
