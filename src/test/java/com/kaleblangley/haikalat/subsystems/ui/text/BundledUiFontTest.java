package com.kaleblangley.haikalat.subsystems.ui.text;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledUiFontTest {
    private static final String RESOURCE = "/ui/fonts/NotoSansSC-VF.ttf";
    private static final String SHA256 =
            "763146584cf0710223441356b4395e279021b0806c196614377a7a0174ae074a";
    private static final String MONO_RESOURCE = "/ui/fonts/JetBrainsMono-Regular.ttf";
    private static final String MONO_SHA256 =
            "a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f";

    @Test
    void bundledFontHasPinnedHashAndShapesLatinCjkWithoutSystemFonts() throws IOException {
        byte[] bytes;
        try (InputStream input = BundledUiFontTest.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing bundled font " + RESOURCE);
            bytes = input.readAllBytes();
        }
        assertEquals(SHA256, sha256(bytes));

        try (FontManager fonts = new FontManager()) {
            FontFamily family = fonts.registerFamily("Haikalat Bundled Noto Sans SC");
            FontFace face = fonts.registerFace(family, bytes, 0);
            assertTrue(face.supportsCodePoint('A'));
            assertTrue(face.supportsCodePoint('中'));

            FontFallbackChain fallback = fonts.fallbackChain(face);
            try (TextShaper shaper = new TextShaper(fonts)) {
                List<TextRun> runs = shaper.shapeWithFallback(fallback, 24,
                        "Haikalat 中文", TextDirection.LEFT_TO_RIGHT,
                        "zh-Hans", List.of());
                assertFalse(runs.isEmpty());
                assertTrue(runs.stream().flatMap(run -> run.glyphs().stream())
                        .allMatch(glyph -> glyph.glyphId() > 0));
            }
        }
    }

    @Test
    void bundledJetBrainsMonoHasPinnedHashAndUsesCjkFallback() throws IOException {
        byte[] mono = read(MONO_RESOURCE);
        assertEquals(MONO_SHA256, sha256(mono));

        try (FontManager fonts = new FontManager()) {
            FontFamily monoFamily = fonts.registerFamily("JetBrains Mono");
            FontFace monoFace = fonts.registerFace(monoFamily, mono, 0);
            assertTrue(monoFace.supportsCodePoint('A'));
            assertFalse(monoFace.supportsCodePoint('中'));
        }

        try (UiTextEngine text = UiTextEngine.createBundled(128, 128, 2)) {
            assertTrue(text.selectFontFamily(UiTextEngine.MONOSPACE_FONT_FAMILY));
            assertEquals(UiTextEngine.MONOSPACE_FONT_FAMILY, text.activeFontFamily());
            try (var label = new com.kaleblangley.haikalat.subsystems.ui.widget.Label(
                    "JetBrains Mono 中文")) {
                text.beginFrame(1.0, 1.0);
                assertTrue(text.measureLine(label, label.text()).width() > 0.0);
            }
        }
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream input = BundledUiFontTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled font " + resource);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
