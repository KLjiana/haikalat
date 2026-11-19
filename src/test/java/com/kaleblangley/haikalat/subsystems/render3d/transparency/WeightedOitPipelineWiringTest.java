package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM-only production wiring contract for weighted blended OIT.
 *
 * <p>No GL context is created: the cases pin the topology key, the
 * caller-selected-set staging contract and the exact-no-op default.
 */
class WeightedOitPipelineWiringTest {
    private static final OitSurface SURFACE = OitSurface.of(
            new Vector3f(0.0f, 0.0f, -2.0f), new Vector3f(0.5f, 0.0f, 0.0f),
            new Vector3f(0.0f, 0.5f, 0.0f), new Vector3f(1.0f, 0.2f, 0.1f), 1.0f);

    @Test
    void disabledDefaultsLeaveTheTopologyAndTheStagedSetUndeclared() {
        Scene scene = new Scene(new Camera());
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology disabled = PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(), StochasticTransparencySettings.disabled(),
                WeightedOitSettings.disabled(), false,
                com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings
                        .disabled(),
                com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings
                        .disabled());

        assertEquals(baseline, disabled);
        assertFalse(disabled.weightedOit());

        RenderPipeline pipeline = new RenderPipeline(window(), scene, null,
                hdr(AntiAliasingMode.NONE));
        try {
            assertEquals(WeightedOitSettings.disabled(), pipeline.weightedOit());
            assertEquals(List.of(), pipeline.weightedOitSurfaces());
            assertFalse(pipeline.weightedOitSetDeclared());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void thePassKeyExistsOnlyForADeclaredSetOnASingleSampleHdrTarget() {
        Scene scene = new Scene(new Camera());
        assertTrue(capture(scene, hdr(AntiAliasingMode.NONE),
                WeightedOitSettings.defaults()).weightedOit());
        assertFalse(capture(scene, hdr(AntiAliasingMode.NONE),
                        WeightedOitSettings.defaults(), false).weightedOit(),
                "an enabled OIT request without a declared set stays unavailable");
        assertFalse(capture(scene, hdr(AntiAliasingMode.TAA),
                WeightedOitSettings.defaults()).weightedOit(),
                "TAA has no stable non-jittered target for the OIT resolve");
        assertFalse(capture(scene, hdr(AntiAliasingMode.MSAA),
                WeightedOitSettings.defaults()).weightedOit(),
                "MSAA is not a single-sample accumulation target");
        assertFalse(capture(scene, RenderSettings.builder().build(),
                WeightedOitSettings.defaults()).weightedOit(),
                "OIT linear compositing requires HDR");
        assertEquals(capture(scene, hdr(AntiAliasingMode.NONE),
                        WeightedOitSettings.disabled()),
                PipelineTopology.capture(scene, hdr(AntiAliasingMode.NONE),
                        PostProcessSettings.defaults(), 64, 48, false, false,
                        DirectionalCascadeSettings.disabled()));
    }

    @Test
    void stagingCopiesTheSetDeclaresTheStrategyAndRespectsCapacity() {
        RenderPipeline pipeline = new RenderPipeline(window(), new Scene(new Camera()), null,
                hdr(AntiAliasingMode.NONE))
                .weightedOit(WeightedOitSettings.defaults().withMaximumSurfaces(1));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.weightedOitSurfaces(Arrays.asList(SURFACE, SURFACE)),
                    "the staged set must respect the configured maximumSurfaces");
            assertThrows(NullPointerException.class,
                    () -> pipeline.weightedOitSurfaces(Arrays.asList(SURFACE, null)));

            // Even an explicitly empty set selects the OIT strategy: it is a
            // declared no-op collection until surfaces are staged.
            pipeline.weightedOitSurfaces(List.of());
            assertTrue(pipeline.weightedOitSetDeclared());
            pipeline.weightedOitSurfaces(List.of(SURFACE));
            assertEquals(List.of(SURFACE), pipeline.weightedOitSurfaces());
            assertThrows(UnsupportedOperationException.class,
                    () -> pipeline.weightedOitSurfaces().add(SURFACE),
                    "the staged set must be immutable");
        } finally {
            pipeline.close();
        }
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings,
                                            WeightedOitSettings oit) {
        return capture(scene, settings, oit, oit.active());
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings,
                                            WeightedOitSettings oit, boolean setDeclared) {
        return PipelineTopology.capture(scene, settings, PostProcessSettings.defaults(),
                64, 48, false, false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(), StochasticTransparencySettings.disabled(),
                oit, setDeclared,
                com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings
                        .disabled(),
                com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings
                        .disabled());
    }

    private static RenderSettings hdr(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
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
