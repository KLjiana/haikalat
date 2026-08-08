package com.kaleblangley.haikalat.subsystems.text;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlyphAtlasFontIntegrationTest {
    @Test
    void conditionalFontRasterizesIntoCpuAtlasAndPublishesOnlyAfterSuccess() throws Exception {
        Path font = FontNativeLifecycleTest.conditionalFont().orElse(null);
        Assumptions.assumeTrue(font != null,
                "No system/JDK font is available; deterministic project font asset is not installed yet");
        try (FontManager manager = new FontManager()) {
            FontFace face = manager.registerFace(manager.registerFamily("atlas-native-smoke"), font, 0);
            try (GlyphAtlas atlas = new GlyphAtlas(128, 128, 1, 2)) {
                GlyphKey key = new GlyphKey(face.id(), face.glyphIndex('A'), 24);
                GlyphAtlasLookup miss = atlas.lookup(face, key);
                GlyphUploadRequest upload = miss.uploadRequest().orElseThrow();

                assertFalse(miss.ready());
                assertTrue(upload.payload().isDirect());
                assertTrue(upload.uploadByteCount() > 0);
                GlyphAtlasGlyph published = atlas.publishUpload(upload);
                GlyphAtlasLookup hit = atlas.lookup(face, key);

                assertSame(published, hit.glyph().orElseThrow());
                assertEquals(upload.uploadByteCount(), atlas.statistics().uploadBytes());
                assertEquals(1, atlas.statistics().pages());
            }
        }
    }
}
