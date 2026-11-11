package com.kaleblangley.haikalat.subsystems.postprocess.smaa;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineFeaturePolicy;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure configuration/topology contract for SMAA 1x: HDR-only, mutually
 * exclusive with the other anti-aliasing modes, exact disabled path and a
 * pass plan that never mixes SMAA with FXAA/TAA/MSAA passes.
 */
class SmaaContractTest {
    @Test
    void smaaRequiresHdrToneMappingAndRejectsTheLdrChain() {
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> RenderSettings.builder()
                        .antiAliasingMode(AntiAliasingMode.SMAA)
                        .build());
        assertEquals("SMAA requires HDR tone mapping", rejected.getMessage());

        RenderSettings hdr = hdrSettings(AntiAliasingMode.SMAA);
        assertEquals(ToneMappingMode.ACES, hdr.toneMappingMode());
        assertEquals(AntiAliasingMode.SMAA, hdr.antiAliasingMode());

        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.passNamesFor(AntiAliasingMode.SMAA,
                        ToneMappingMode.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> PostProcessPipeline.finalPassNameFor(AntiAliasingMode.SMAA,
                        ToneMappingMode.NONE));

        RenderSettings ldr = RenderSettings.builder().build();
        assertThrows(IllegalStateException.class, () -> ldr.withVisualSettings(1.0f,
                BloomSettings.defaults(), AntiAliasingMode.SMAA),
                "a visual-profile switch to SMAA must be rejected for an LDR chain");
    }

    @Test
    void smaaIsASingleMutuallyExclusiveAntiAliasingSelection() {
        Scene scene = new Scene(new Camera());
        PipelineTopology smaa = capture(scene, hdrSettings(AntiAliasingMode.SMAA));
        PipelineTopology fxaa = capture(scene, hdrSettings(AntiAliasingMode.FXAA));
        PipelineTopology taa = capture(scene, hdrSettings(AntiAliasingMode.TAA));
        PipelineTopology msaa = capture(scene, hdrSettings(AntiAliasingMode.MSAA));
        PipelineTopology none = capture(scene, hdrSettings(AntiAliasingMode.NONE));

        assertEquals(AntiAliasingMode.SMAA, smaa.antiAliasingMode());
        assertNotEquals(smaa, fxaa);
        assertNotEquals(smaa, taa);
        assertNotEquals(smaa, msaa);
        assertNotEquals(smaa, none);
        assertEquals(1, smaa.sampleCount(), "SMAA is spatial 1x, not MSAA");
        assertFalse(smaa.volumetricFog());

        List<String> passes = PostProcessPipeline.passNamesFor(
                AntiAliasingMode.SMAA, ToneMappingMode.ACES);
        assertFalse(passes.contains(PostProcessTargets.FXAA_PASS));
        assertFalse(passes.contains(PostProcessTargets.TAA_PASS));
        assertFalse(passes.contains(PostProcessTargets.HDR_RESOLVE_PASS));
        assertFalse(passes.contains(PostProcessTargets.PRESENT_PASS));
        assertEquals(1L, passes.stream()
                .filter(PostProcessTargets.SMAA_PASS::equals).count());
    }

    @Test
    void smaaPassPlanRunsAfterToneMappingAndBeforePresent() {
        List<String> passes = PostProcessPipeline.passNamesFor(
                AntiAliasingMode.SMAA, ToneMappingMode.ACES);

        assertEquals(List.of(
                PostProcessTargets.GEOMETRY_PASS,
                PostProcessTargets.TONE_MAPPING_PASS,
                PostProcessTargets.SMAA_EDGE_DETECTION_PASS,
                PostProcessTargets.SMAA_BLENDING_WEIGHT_PASS,
                PostProcessTargets.SMAA_PASS), passes);
        assertTrue(passes.indexOf(PostProcessTargets.TONE_MAPPING_PASS)
                < passes.indexOf(PostProcessTargets.SMAA_EDGE_DETECTION_PASS),
                "SMAA consumes the display-referred tone-mapped image");
        assertEquals(PostProcessTargets.SMAA_PASS, PostProcessPipeline.finalPassNameFor(
                AntiAliasingMode.SMAA, ToneMappingMode.ACES));
    }

    @Test
    void disabledPathIsTheExactBaselineAndNotAnSmaaNoOp() {
        RenderSettings defaults = RenderSettings.builder().build();
        assertEquals(AntiAliasingMode.NONE, defaults.antiAliasingMode(),
                "the default anti-aliasing option stays NONE");
        assertEquals(PostProcessTargets.PRESENT_PASS,
                PostProcessPipeline.finalPassNameFor(AntiAliasingMode.NONE,
                        ToneMappingMode.ACES));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                        PostProcessTargets.TONE_MAPPING_PASS,
                        PostProcessTargets.PRESENT_PASS),
                PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                        ToneMappingMode.ACES),
                "disabled/none keeps the exact pre-feature colour chain");
    }

    @Test
    void topologyPassesFeaturePolicyAndPublicFingerprintsMatchTheGeneratedTables() {
        PipelineTopology topology = capture(new Scene(new Camera()),
                hdrSettings(AntiAliasingMode.SMAA));
        new PipelineFeaturePolicy(topology, false).validate();

        assertEquals(SmaaLookupTables.AREA_WIDTH, SmaaPass.AREA_TEXTURE_WIDTH);
        assertEquals(SmaaLookupTables.AREA_HEIGHT, SmaaPass.AREA_TEXTURE_HEIGHT);
        assertEquals(SmaaLookupTables.SEARCH_WIDTH, SmaaPass.SEARCH_TEXTURE_WIDTH);
        assertEquals(SmaaLookupTables.SEARCH_HEIGHT, SmaaPass.SEARCH_TEXTURE_HEIGHT);
        assertEquals(SmaaLookupTables.AREA_SHA256, SmaaPass.AREA_TEXTURE_SHA256);
        assertEquals(SmaaLookupTables.SEARCH_SHA256, SmaaPass.SEARCH_TEXTURE_SHA256);
    }

    private static RenderSettings hdrSettings(AntiAliasingMode mode) {
        return RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static PipelineTopology capture(Scene scene, RenderSettings settings) {
        return PipelineTopology.capture(scene, settings, PostProcessSettings.defaults(),
                128, 72, false, false, DirectionalCascadeSettings.disabled());
    }
}
