package com.kaleblangley.haikalat.demo.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UiDemo 的隐藏窗口、resize、内容缩放和最终像素闭环。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class UiDemoGlTest {
    @Test
    void hiddenDeterministicRunProducesUiPixelsAndFiniteStatistics() {
        UiDemo.RunSummary summary = UiDemo.run(UiDemoOptions.parse(
                "--deterministic", "--frames=8", "--resize=3:720x480",
                "--content-scale=1.25x1.5", "--verify-pixels"));

        assertEquals(8, summary.renderedFrames());
        assertTrue(summary.nonClearSamples() > 0);
        assertTrue(summary.statistics().visibleNodes() > 20);
        assertTrue(summary.statistics().quads() > 0);
        assertTrue(summary.statistics().glyphs() > 0,
                "bundled FreeType/HarfBuzz glyphs must replace placeholders");
        assertTrue(summary.statistics().glyphAtlasPages() > 0);
        assertTrue(summary.statistics().atlasUploadBytes() > 0);
        assertTrue(summary.statistics().drawCalls() > 0);
        assertTrue(summary.framebufferWidth() > 0 && summary.framebufferHeight() > 0);
    }
}
