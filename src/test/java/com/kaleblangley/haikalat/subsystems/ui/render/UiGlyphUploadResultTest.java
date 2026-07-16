package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.subsystems.ui.text.FontFaceId;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphAtlas;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphBitmap;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphKey;
import com.kaleblangley.haikalat.subsystems.ui.text.GlyphUploadRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiGlyphUploadResultTest {
    @Test
    void successDefensivelyPublishesWholeBatchAndPageTextures() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphUploadRequest request = request(atlas, 1);
            ArrayList<GlyphUploadRequest> requests = new ArrayList<>(List.of(request));
            LinkedHashMap<Integer, Integer> textures = new LinkedHashMap<>();
            textures.put(0, 73);

            UiGlyphUploadResult result = UiGlyphUploadResult.succeeded(5, requests, textures);
            requests.clear();
            textures.clear();

            assertEquals(UiGlyphUploadResult.Status.SUCCEEDED, result.status());
            assertTrue(result.succeeded());
            assertEquals(List.of(request), result.requests());
            assertEquals(73, result.pageTextureId(0));
            assertEquals(java.util.Map.of(0, 73), result.pageTextureIds());
            assertTrue(result.failure().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> result.pageTextureIds().put(1, 74));
        }
    }

    @Test
    void failureNeverPublishesTextureAndRetainsCause() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphUploadRequest request = request(atlas, 1);
            IllegalStateException cause = new IllegalStateException("synthetic upload failure");

            UiGlyphUploadResult result = UiGlyphUploadResult.failed(
                    9, List.of(request), cause);

            assertEquals(UiGlyphUploadResult.Status.FAILED, result.status());
            assertFalse(result.succeeded());
            assertSame(cause, result.failure().orElseThrow());
            assertTrue(result.pageTextureIds().isEmpty());
            assertThrows(IllegalStateException.class, () -> result.pageTextureId(0));
        }
    }

    @Test
    void gpuAtlasRejectsInvalidBatchBeforeRequiringGlContextAndClosesIdempotently() {
        try (GlyphAtlas atlas = new GlyphAtlas(8, 8, 1, 1)) {
            GlyphUploadRequest request = request(atlas, 1);
            UiGlyphAtlasGpu matching = new UiGlyphAtlasGpu(8, 8, 1);
            UiGlyphAtlasGpu mismatched = new UiGlyphAtlasGpu(16, 8, 1);

            assertThrows(IllegalArgumentException.class,
                    () -> matching.recordUploads(List.of(), new com.kaleblangley.haikalat.core.command.CommandBuffer()));
            assertThrows(IllegalArgumentException.class,
                    () -> matching.recordUploads(List.of(request, request),
                            new com.kaleblangley.haikalat.core.command.CommandBuffer()));
            assertThrows(IllegalArgumentException.class,
                    () -> mismatched.recordUploads(List.of(request),
                            new com.kaleblangley.haikalat.core.command.CommandBuffer()));

            matching.close();
            matching.close();
            mismatched.close();
            mismatched.close();
            assertTrue(matching.isClosed());
            assertTrue(mismatched.isClosed());
        }
    }

    private static GlyphUploadRequest request(GlyphAtlas atlas, int glyphId) {
        GlyphKey key = new GlyphKey(new FontFaceId(1), glyphId, 16);
        return atlas.lookup(key, ignored -> new GlyphBitmap(
                key, 2, 2, 0, 2, 2.0f, 0.0f,
                new byte[]{1, 2, 3, 4})).uploadRequest().orElseThrow();
    }
}
