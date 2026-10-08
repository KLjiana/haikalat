package com.kaleblangley.haikalat.demo.pbr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VolumetricBaselineContractTest {
    @Test
    void samplerIndexAndActiveStageCountUseDifferentLimits() {
        var limits = Map.of("sampler.bindings", 16, "fragment.sampler.count", 2);
        var rows = List.of("pbr fragment sampler 14 prefix planned", "pbr fragment sampler 15 source planned");
        assertTrue(Render3dVolumetricBaseline.checkBindings(rows, limits).isEmpty());
        assertFalse(Render3dVolumetricBaseline.checkBindings(rows,
                Map.of("sampler.bindings", 15, "fragment.sampler.count", 32)).isEmpty());
        assertFalse(Render3dVolumetricBaseline.checkBindings(rows,
                Map.of("sampler.bindings", 32, "fragment.sampler.count", 1)).isEmpty());
    }

    @Test
    void namespacesCanReuseIndicesButOneProgramCannotAliasResources() {
        var limits = Map.of("sampler.bindings", 16, "fragment.sampler.count", 16,
                "image.bindings", 8, "fragment.image.count", 8);
        var rows = List.of("fog fragment sampler 0 source planned", "fog fragment image 0 destination planned");
        assertTrue(Render3dVolumetricBaseline.checkBindings(rows, limits).isEmpty());
        assertFalse(Render3dVolumetricBaseline.checkBindings(List.of(rows.getFirst(), rows.getFirst()), limits).isEmpty());
    }

    @Test
    void balanced4kIncludesAllVolumesExtraBoundaryParametersAndFullSizeOutputs() throws IOException {
        var row = Render3dVolumetricBaseline.budget(Render3dVolumetricBaseline.settings(), "balanced", 3840, 2160, 1);
        assertEquals(240, row.get("nx")); assertEquals(135, row.get("ny")); assertEquals(64, row.get("nz"));
        assertEquals(124_740_368L, row.get("owned3dAndParametersBytes"));
        assertEquals(199_389_968L, row.get("steadyBytes"));
        assertEquals(398_779_936L, row.get("transactionPeakBytes"));
        assertEquals(true, row.get("withinBudget"));
    }

    @Test
    void msaaIsBudgetedSeparatelyAndHigh4kIsExplicitlyRejected() throws IOException {
        var settings = Render3dVolumetricBaseline.settings();
        var msaa = Render3dVolumetricBaseline.budget(settings, "balanced", 3840, 2160, 4);
        assertEquals(398_455_568L, msaa.get("steadyBytes"));
        assertEquals(true, msaa.get("withinBudget"));
        assertEquals(false, Render3dVolumetricBaseline.budget(settings, "high", 3840, 2160, 1).get("withinBudget"));
        assertThrows(IllegalArgumentException.class,
                () -> Render3dVolumetricBaseline.budget(settings, "balanced", 0, 2160, 1));
        var tiny = Render3dVolumetricBaseline.budget(settings, "balanced", 1, 1, 1);
        assertEquals(1, tiny.get("nx")); assertEquals(1, tiny.get("ny"));
        var nonAligned = Render3dVolumetricBaseline.budget(settings, "balanced", 1919, 1079, 1);
        assertEquals(120, nonAligned.get("nx")); assertEquals(68, nonAligned.get("ny"));
    }
}
