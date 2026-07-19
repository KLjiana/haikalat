package com.kaleblangley.haikalat.demo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 scalability Demo 的确定性容量矩阵和自动化路径边界。 */
class SceneScalabilityDemoTest {
    @Test
    void layoutsProduceDocumentedVisibleCounts() {
        assertEquals(70, SceneScalabilityDemo.Layout.SMALL.visibleCount(100));
        assertEquals(250, SceneScalabilityDemo.Layout.MEDIUM.visibleCount(1_000));
        assertEquals(1_000, SceneScalabilityDemo.Layout.LARGE.visibleCount(10_000));
        assertEquals(10_000, SceneScalabilityDemo.Layout.ALL_VISIBLE.visibleCount(10_000));
        assertEquals(1, SceneScalabilityDemo.Layout.ALL_HIDDEN.visibleCount(10_000));
    }

    @Test
    void parsesCompleteAutomationContractAndRestrictsCapacityChoices() {
        SceneScalabilityDemo.Options options = SceneScalabilityDemo.Options.parse(new String[]{
                "--objects=1000", "--visibility=disabled", "--layout=medium",
                "--frames=12", "--warmup=4", "--rounds=2", "--size=960x540",
                "--resize=3:1280x720", "--deterministic", "--verify"
        });

        assertAll(
                () -> assertEquals(1_000, options.objects()),
                () -> assertFalse(options.visibilityEnabled()),
                () -> assertEquals(SceneScalabilityDemo.Layout.MEDIUM, options.layout()),
                () -> assertEquals(960, options.width()),
                () -> assertEquals(540, options.height()),
                () -> assertEquals(3, options.resizeFrame()),
                () -> assertTrue(options.deterministic()),
                () -> assertTrue(options.verify()));
        assertThrows(IllegalArgumentException.class,
                () -> SceneScalabilityDemo.Options.parse(new String[]{"--objects=999"}));
    }

    @Test
    void diagnosticsExportMustRemainUnderBuildDiagnostics() {
        String allowed = Path.of("build", "diagnostics", "scene.json").toString();
        assertNotNull(SceneScalabilityDemo.Options.parse(
                new String[]{"--diagnostics-export=" + allowed}).diagnosticsExport());
        assertThrows(IllegalArgumentException.class,
                () -> SceneScalabilityDemo.Options.parse(
                        new String[]{"--diagnostics-export=scene.json"}));
    }
}
