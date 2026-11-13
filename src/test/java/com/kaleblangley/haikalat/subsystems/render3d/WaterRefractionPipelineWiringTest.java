package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.graph.GraphRecordingFixture;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterRefractionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring contract for #11 screen-space water refraction.
 *
 * <p>These cases prove the topology key, the unified-feature policy and the
 * frame-boundary apply transaction without a GL context.  The actual companion
 * copy pass and shader variant are exercised by
 * {@code WaterRefractionPipelineGlTest}.</p>
 */
class WaterRefractionPipelineWiringTest {
    private static final RenderFeaturePolicy.Facts NO_PBR = new RenderFeaturePolicy.Facts(
            false, true, true, true, false, false, false, false, false, true);

    @Test
    void topologyOnlyCapturesRefractionWhenWaterIsActive() {
        Scene scene = new Scene(new Camera());
        PipelineTopology baseline = topology(scene, WaterSurfaceSettings.disabled(),
                WaterRefractionSettings.disabled());
        PipelineTopology waterOnly = topology(scene, water(), WaterRefractionSettings.disabled());
        PipelineTopology refraction = topology(scene, water(), refraction());
        PipelineTopology refractionWithoutWater = topology(scene,
                WaterSurfaceSettings.disabled(), refraction());

        assertFalse(baseline.waterRefraction());
        assertFalse(waterOnly.waterRefraction(),
                "disabled refraction must leave the pre-#11 topology unchanged");
        assertTrue(refraction.waterRefraction());
        assertFalse(refractionWithoutWater.waterRefraction(),
                "an invalid request must not change the graph key");
        assertEquals(waterOnly, topology(scene, water(), WaterRefractionSettings.disabled()),
                "the disabled value must be a deterministic topology no-op");
    }

    @Test
    void featurePolicyReportsActiveRefractionAndRejectsMissingWater() {
        RenderFeatureSettings active = hdrBuilder()
                .waterSurface(water())
                .waterRefraction(refraction())
                .build();
        RenderFeaturePolicy.Resolved resolved = RenderFeaturePolicy.resolve(active, NO_PBR);
        assertTrue(resolved.waterRefraction());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(resolved, RenderFeature.WATER_REFRACTION).reason());

        RenderFeatureSettings missingWater = hdrBuilder()
                .waterRefraction(refraction())
                .build();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(missingWater, NO_PBR));
        assertTrue(failure.getMessage().contains("active finite water surface"),
                failure.getMessage());

        RenderFeatureSettings paused = active.toBuilder().enabled(false).build();
        RenderFeaturePolicy.Resolved pausedResolved =
                RenderFeaturePolicy.resolve(paused, NO_PBR);
        assertFalse(pausedResolved.waterRefraction());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(pausedResolved, RenderFeature.WATER_REFRACTION).reason());
    }

    @Test
    void builderTimeRefractionWithoutWaterFailsBeforeGeneration() {
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE));
        try {
            pipeline.waterRefraction(refraction());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    pipeline::build);
            assertTrue(failure.getMessage().contains("active finite water surface"),
                    failure.getMessage());
            assertEquals(null, pipeline.graph(),
                    "a failed dependency check must not publish a candidate graph");
        } finally {
            pipeline.close();
        }
    }

    @Test
    void disablingWaterWhileRefractionIsActiveFailsClosed() {
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE))
                .waterSurface(water())
                .waterRefraction(refraction());
        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> pipeline.applyWaterSurface(water().withEnabled(false)));
            assertTrue(failure.getMessage().contains("water refraction"),
                    failure.getMessage());
            assertTrue(pipeline.waterSurface().active());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void scalarRefractionChangesDoNotRebuildAndToggleRollsBack() throws Exception {
        Scene scene = new Scene(new Camera());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE))
                .waterSurface(water())
                .waterRefraction(refraction());
        PipelineTopology topology = topology(scene, water(), refraction());
        PipelineGeneration injected = new PipelineGeneration(topology);
        RenderGraph graph = GraphRecordingFixture.graph(64, 48);
        try {
            setField(injected, "graph", graph);
            setField(pipeline, "activeGeneration", injected);
            WaterRefractionSettings scalar = WaterRefractionSettings.builder()
                    .enabled(true).ior(1.5f).strength(1.5f).maximumScreenOffset(0.05f)
                    .thicknessScale(1.5f).foregroundRejectionBias(0.02f)
                    .offscreenFallback(WaterRefractionSettings.OffscreenFallback.TINT)
                    .offscreenFallbackColor(0.9f, 0.1f, 0.7f).build();
            pipeline.applyWaterRefraction(scalar);
            assertEquals(scalar, pipeline.waterRefraction());
            assertSame(injected, pipeline.activeGenerationForTest(),
                    "scalar refraction changes are read by the executor and must not rebuild");

            assertThrows(IllegalStateException.class,
                    () -> pipeline.applyWaterRefraction(scalar.withEnabled(false)),
                    "disabling refraction changes the graph and must rebuild a candidate");
            assertEquals(scalar, pipeline.waterRefraction(),
                    "a failed candidate must keep the previous refraction settings");
            assertSame(injected, pipeline.activeGenerationForTest());
        } finally {
            setField(pipeline, "activeGeneration", null);
            pipeline.close();
            graph.close();
            injected.close();
        }
    }

    private static PipelineTopology topology(Scene scene, WaterSurfaceSettings water,
                                             WaterRefractionSettings refraction) {
        return PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, water, VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled(), VolumetricCloudSettings.disabled(),
                SmokeSimulationSettings.disabled(), SmokeRenderSettings.disabled(),
                com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings.disabled(),
                WeightedOitSettings.disabled(), false,
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(),
                HairStrandSettings.disabled(),
                com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings.disabled(),
                refraction);
    }

    private static WaterSurfaceSettings water() {
        return WaterSurfaceSettings.builder()
                .enabled(true).level(0.0f).bounds(-6.0f, -6.0f, 6.0f, 6.0f)
                .waveWavelength(8.0f).waveSpeed(0.6f).waveAmplitude(0.06f)
                .waveDirectionRadians(0.0f).waterColor(0.02f, 0.12f, 0.16f)
                .absorptionDensity(0.35f).shoreFadeDistance(0.0f)
                .reflectionFallback(0.35f, 0.5f, 0.65f).build();
    }

    private static WaterRefractionSettings refraction() {
        return WaterRefractionSettings.builder()
                .enabled(true).ior(1.333f).strength(1.0f).maximumScreenOffset(0.2f)
                .thicknessScale(1.0f).foregroundRejectionBias(0.05f)
                .offscreenFallback(WaterRefractionSettings.OffscreenFallback.UNREFRACTED)
                .build();
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static RenderFeatureSettings.Builder hdrBuilder() {
        return RenderFeatureSettings.builder().render(hdr(AntiAliasingMode.NONE));
    }

    private static RenderFeatureActivity activity(RenderFeaturePolicy.Resolved resolved,
                                                  RenderFeature feature) {
        return resolved.activities().stream()
                .filter(activity -> activity.feature() == feature)
                .findFirst().orElseThrow();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static RenderWindow window() {
        return new RenderWindow() {
            @Override
            public int width() {
                return 64;
            }

            @Override
            public int height() {
                return 48;
            }
        };
    }
}
