package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-version water surface and single-layer refraction both own the
 * post-process scene-colour producer; the combination must fail loudly instead
 * of silently discarding one of them.  SSR is opaque-only, so it must also
 * reject both producers independently rather than reflect through them.
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
}
