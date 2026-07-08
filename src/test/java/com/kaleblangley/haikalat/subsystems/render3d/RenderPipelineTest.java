package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderPipelineTest {
    @Test
    void passPlanUsesPresentPassForNoneAndMsaa() {
        List<String> expected = List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.PRESENT_PASS);

        assertEquals(expected, RenderPipeline.passNamesFor(AntiAliasingMode.NONE));
        assertEquals(expected, RenderPipeline.passNamesFor(AntiAliasingMode.MSAA));
    }

    @Test
    void passPlanUsesDedicatedPostprocessPassesForFxaaAndTaa() {
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.FXAA));
        assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS),
                RenderPipeline.passNamesFor(AntiAliasingMode.TAA));
    }
}
