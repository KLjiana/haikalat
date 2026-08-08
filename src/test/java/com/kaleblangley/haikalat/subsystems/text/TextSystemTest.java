package com.kaleblangley.haikalat.subsystems.text;

import com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSystemTest {
    @Test
    void ownsReusableFontLayoutAtlasAndMarkdownServices() {
        try (TextSystem text = TextSystem.createBundled(128, 128, 8, 2)) {
            assertEquals(BundledFonts.NOTO_SANS_SC_FAMILY, text.activeFontFamily());
            assertTrue(text.selectFontFamily(BundledFonts.JETBRAINS_MONO_FAMILY));
            assertFalse(text.selectFontFamily(BundledFonts.JETBRAINS_MONO_FAMILY));

            TextLayout first = text.layoutSingleLine(20, "Text 中文", 300.0f,
                    TextAlignment.START, false);
            TextSystem.LayoutStatistics afterFirst = text.layoutStatistics();
            TextLayout second = text.layoutSingleLine(20, "Text 中文", 300.0f,
                    TextAlignment.START, false);
            TextSystem.LayoutStatistics afterSecond = text.layoutStatistics();

            assertEquals(first, second);
            assertEquals(afterFirst.cacheMisses(), afterSecond.cacheMisses());
            assertEquals(afterFirst.cacheHits() + 1L, afterSecond.cacheHits());
            assertTrue(first.glyphCount() > 0);

            TextLayout explicitFamily = text.layoutSingleLine(
                    BundledFonts.NOTO_SANS_SC_FAMILY, 20, "Text 中文", 300.0f,
                    TextAlignment.START, false);
            assertTrue(explicitFamily.glyphCount() > 0);

            long hitsBeforeSwitch = text.layoutStatistics().cacheHits();
            assertTrue(text.selectFontFamily(BundledFonts.NOTO_SANS_SC_FAMILY));
            TextLayout retained = text.layoutSingleLine(20, "Text 中文", 300.0f,
                    TextAlignment.START, false);
            assertEquals(explicitFamily, retained);
            assertEquals(hitsBeforeSwitch + 1L, text.layoutStatistics().cacheHits());

            var document = text.parseMarkdown("**strong** and `code`");
            assertTrue(document.blocks().getFirst().inlines().stream().anyMatch(inline ->
                    inline.kind() == MarkdownDocument.InlineKind.STRONG));
            assertTrue(document.blocks().getFirst().inlines().stream().anyMatch(inline ->
                    inline.kind() == MarkdownDocument.InlineKind.CODE));
        }
    }

    @Test
    void rejectsUseAfterClose() {
        TextSystem text = TextSystem.createBundled(64, 64, 2, 1);
        text.close();

        assertThrows(IllegalStateException.class, text::fontFamilies);
    }

    @Test
    void retriesCloseAfterPublishedGenerationLeaseIsReleased() {
        TextSystem text = TextSystem.createBundled(64, 64, 2, 1);
        var lease = text.acquireAtlasGeneration();

        assertThrows(IllegalStateException.class, text::close);
        assertTrue(text.fontFamilies().contains(BundledFonts.NOTO_SANS_SC_FAMILY));

        lease.close();
        text.close();
        assertThrows(IllegalStateException.class, text::fontFamilies);
    }
}
