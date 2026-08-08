package com.kaleblangley.haikalat.subsystems.ui.text;

import com.kaleblangley.haikalat.subsystems.text.GlyphAtlasPlacement;
import com.kaleblangley.haikalat.subsystems.text.GlyphUploadRequest;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.render.UiShaderVariant;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiTextEngineTest {
    @Test
    void lineMetricsExposeRealCjkCaretStops() {
        try (UiTextEngine text = UiTextEngine.createBundled(128, 128, 2);
             Label label = new Label("A中B")) {
            text.beginFrame(1.0, 1.0);

            var metrics = text.measureLine(label, label.text());

            assertEquals(4, metrics.stops().size());
            assertTrue(metrics.xAt(1) > 0.0);
            assertTrue(metrics.xAt(2) > metrics.xAt(1));
            assertTrue(metrics.xAt(3) > metrics.xAt(2));
            assertEquals(2, metrics.offsetAt(metrics.xAt(2) + 0.01));
        }
    }

    @Test
    void atlasMissUsesPlaceholderBoundaryThenPublishesRealGlyphRuns() {
        try (UiTextEngine text = UiTextEngine.createBundled(128, 128, 2);
             Label label = new Label("Haikalat 中文")) {
            text.beginFrame(1.25, 1.5);
            UiDisplayList displayList = new UiDisplayList();

            assertFalse(text.paint(displayList, label, label.text(),
                    new UiScreenRect(0.0, 0.0, 240.0, 48.0), 0xffffffff));
            assertFalse(text.pendingUploads().isEmpty());

            for (GlyphUploadRequest request : text.pendingUploads()) {
                text.publishUpload(request);
            }
            displayList.clear();
            assertTrue(text.paint(displayList, label, label.text(),
                    new UiScreenRect(0.0, 0.0, 240.0, 48.0), 0xffffffff));
            assertTrue(displayList.quadCount() > 0);
            assertTrue(java.util.stream.IntStream.range(0, displayList.primitiveCount())
                    .anyMatch(index -> displayList.primitiveShader(index) == UiShaderVariant.GLYPH));
            assertTrue(text.frameGlyphs() > 0);
        }
    }

    @Test
    void uppercaseIRasterKeepsAVisibleStem() {
        try (UiTextEngine text = UiTextEngine.createBundled(128, 128, 2);
             Label label = new Label("I")) {
            text.beginFrame(1.0, 1.0);
            text.paint(new UiDisplayList(), label, label.text(),
                    new UiScreenRect(0.0, 0.0, 32.0, 32.0), 0xffffffff);
            GlyphUploadRequest request = text.pendingUploads().get(0);
            java.nio.ByteBuffer payload = request.payload();
            int sum = 0;
            int max = 0;
            int nonZero = 0;
            while (payload.hasRemaining()) {
                int value = Byte.toUnsignedInt(payload.get());
                sum += value;
                max = Math.max(max, value);
                nonZero += value == 0 ? 0 : 1;
            }
            assertTrue(request.glyphPlacement().width() >= 1);
            assertTrue(request.glyphPlacement().height() >= 8);
            assertTrue(max >= 128, "uppercase I coverage is too faint: " + max);
            assertTrue(nonZero >= 8);
            assertTrue(sum >= 1024);
        }
    }

    @Test
    void paintedGlyphUsesItsZeroFilledAtlasPadding() {
        try (UiTextEngine text = UiTextEngine.createBundled(128, 128, 2);
             Label label = new Label("I").textEffect(
                     TextEffect.glow(com.kaleblangley.haikalat.subsystems.ui.style.UiColor.WHITE,
                             4.0f))) {
            text.beginFrame(1.0, 1.0);
            UiDisplayList displayList = new UiDisplayList();
            assertFalse(text.paint(displayList, label, label.text(),
                    new UiScreenRect(0.0, 0.0, 32.0, 32.0), 0xffffffff));
            GlyphUploadRequest request = text.pendingUploads().get(0);
            GlyphAtlasPlacement placement = request.glyphPlacement();
            text.publishUpload(request);

            displayList.clear();
            assertTrue(text.paint(displayList, label, label.text(),
                    new UiScreenRect(0.0, 0.0, 32.0, 32.0), 0xffffffff));

            assertEquals(placement.allocatedWidth(), displayList.quadWidth(0), 0.001);
            assertEquals(placement.allocatedHeight(), displayList.quadHeight(0), 0.001);
            assertEquals(placement.allocatedU0(), displayList.quadU0(0), 0.000001f);
            assertEquals(placement.allocatedV0(), displayList.quadV0(0), 0.000001f);
            assertEquals(placement.allocatedU1(), displayList.quadU1(0), 0.000001f);
            assertEquals(placement.allocatedV1(), displayList.quadV1(0), 0.000001f);
        }
    }

    @Test
    void runtimeFontCatalogRegistersSelectsAndRebuildsFallback() throws Exception {
        byte[] fontData;
        try (var input = UiTextEngineTest.class.getResourceAsStream(
                UiTextEngine.BUNDLED_FONT_RESOURCE)) {
            fontData = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        try (UiTextEngine text = UiTextEngine.createBundled(128, 128, 2);
             Label label = new Label("Font 字体")) {
            assertEquals(List.of(UiTextEngine.DEFAULT_FONT_FAMILY,
                    UiTextEngine.UNIFONT_FONT_FAMILY,
                    UiTextEngine.MONOSPACE_FONT_FAMILY), text.fontFamilies());
            assertEquals(UiTextEngine.DEFAULT_FONT_FAMILY, text.activeFontFamily());

            text.beginFrame(1.0, 1.0);
            double defaultWidth = text.measureLine(label, label.text()).width();
            assertTrue(text.selectFontFamily(UiTextEngine.MONOSPACE_FONT_FAMILY));
            double monospaceWidth = text.measureLine(label, label.text()).width();
            assertNotEquals(defaultWidth, monospaceWidth, 0.01,
                    "default-family labels must follow the selected UI font");

            text.registerFont("Alternate", fontData);
            assertEquals(List.of(UiTextEngine.DEFAULT_FONT_FAMILY,
                    UiTextEngine.UNIFONT_FONT_FAMILY,
                    UiTextEngine.MONOSPACE_FONT_FAMILY, "Alternate"),
                    text.fontFamilies());
            assertTrue(text.selectFontFamily("Alternate"));
            assertFalse(text.selectFontFamily("Alternate"));
            assertEquals("Alternate", text.activeFontFamily());

            var alternateLayout = text.measureLine(label, label.text());
            assertTrue(alternateLayout.width() > 0.0);
            assertThrows(IllegalArgumentException.class,
                    () -> text.registerFont("Alternate", fontData));
            assertThrows(IllegalArgumentException.class,
                    () -> text.selectFontFamily("Missing"));
        }
    }
}
