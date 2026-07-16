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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
