package com.kaleblangley.haikalat.subsystems.render3d.planar;

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
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JVM-only production wiring contract for planar reflections. */
class PlanarReflectionPipelineWiringTest {
    @Test
    void disabledDefaultsLeaveTheTopologyUnchanged() {
        Scene scene = new Scene(new Camera());
        PipelineTopology baseline = PipelineTopology.capture(scene, hdr(),
                PostProcessSettings.defaults(), 64, 48, false, false,
                DirectionalCascadeSettings.disabled());
        PipelineTopology disabled = capture(scene, hdr(),
                PlanarReflectionSettings.disabled());

        assertEquals(baseline, disabled);
        assertFalse(disabled.planarReflections());
        RenderPipeline pipeline = new RenderPipeline(window(), scene, null, hdr());
        try {
            assertEquals(PlanarReflectionSettings.disabled(),
                    pipeline.planarReflections());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void thePassKeyRequiresHdrAndAUsableSinglePlane() {
        Scene scene = new Scene(new Camera());
        PlanarReflectionSettings active = PlanarReflectionSettings.builder()
                .enabled(true).planeY(0.0f).build();
        assertTrue(capture(scene, hdr(), active).planarReflections());
        assertFalse(capture(scene, RenderSettings.builder().build(), active)
                        .planarReflections(),
                "planar reflections require HDR tone mapping");
        assertFalse(capture(scene, hdr(),
                        PlanarReflectionSettings.disabled()).planarReflections());
    }

    @Test
    void anEnabledRequestWithoutAValidatedPlaneCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().enabled(true)
                        .plane(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f).build(),
                "a degenerate plane must fail closed before wiring");
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionSettings.builder().enabled(true)
                        .plane(Float.NaN, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f).build());
        PlanarReflectionSettings active = PlanarReflectionSettings.builder()
                .enabled(true).planeY(1.5f).build();
        assertTrue(active.hasPlane());
        assertEquals(-1.5f, active.planeEquation(new org.joml.Vector4f()).w, 1.0e-6f);
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings,
                                            PlanarReflectionSettings planar) {
        return PipelineTopology.capture(scene, settings, PostProcessSettings.defaults(),
                64, 48, false, false, DirectionalCascadeSettings.disabled(),
                LocalShadowPipelineSettings.legacyDefaults(), VolumetricFogSettings.disabled(),
                true, true, true, WaterSurfaceSettings.disabled(),
                VolumetricParticleSettings.disabled(), StochasticTransparencySettings.disabled(),
                WeightedOitSettings.disabled(), false, planar,
                com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings
                        .disabled());
    }

    private static RenderSettings hdr() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
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
