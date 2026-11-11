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
 * of silently discarding one of them.
 */
class PipelineFeaturePolicyTest {
    @Test
    void rejectsRefractionAndWaterSurfaceTogether() {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false).build();
        PipelineTopology topology = PipelineTopology.capture(
                new Scene(new Camera()), settings, PostProcessSettings.defaults(),
                64, 64, false, false, DirectionalCascadeSettings.defaults(),
                LocalShadowPipelineSettings.legacyDefaults(),
                VolumetricFogSettings.disabled(), false, false, false,
                WaterSurfaceSettings.disabled().withEnabled(true));
        PipelineFeaturePolicy policy = new PipelineFeaturePolicy(topology, true);
        assertDoesNotThrow(policy::validate);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> policy.validateRefractionWater(true));
        assertTrue(failure.getMessage().contains("mutually exclusive"));
        assertDoesNotThrow(() -> policy.validateRefractionWater(false));

        assertThrows(IllegalStateException.class,
                () -> policy.validateSsrComposition(true, false));
        assertThrows(IllegalStateException.class,
                () -> policy.validateSsrComposition(true, true));
        assertDoesNotThrow(() -> policy.validateSsrComposition(false, true));
    }
}
