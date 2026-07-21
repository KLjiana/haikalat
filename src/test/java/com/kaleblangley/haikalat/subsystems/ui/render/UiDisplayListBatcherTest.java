package com.kaleblangley.haikalat.subsystems.ui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiDisplayListBatcherTest {
    private static final UiScreenRect QUAD = new UiScreenRect(1.0, 2.0, 10.0, 8.0);

    @Test
    void mergesOnlyAdjacentPrimitivesWithCompleteMatchingKey() {
        UiDisplayList list = new UiDisplayList(0, 0)
                .addSolidQuad(QUAD, 0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA)
                .addSolidQuad(QUAD, 0xff00ffff, UiBlendMode.PREMULTIPLIED_ALPHA)
                .addTexturedQuad(QUAD, UiUvRect.FULL, 7, 3, 0xffffffff,
                        UiBlendMode.PREMULTIPLIED_ALPHA)
                .addTexturedQuad(QUAD, UiUvRect.FULL, 7, 3, 0xffffffff,
                        UiBlendMode.PREMULTIPLIED_ALPHA)
                .addTexturedQuad(QUAD, UiUvRect.FULL, 8, 3, 0xffffffff,
                        UiBlendMode.PREMULTIPLIED_ALPHA)
                .addSolidQuad(QUAD, 0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA);

        UiBatcher.Result batches = new UiBatcher().batch(list);

        assertEquals(4, batches.size());
        assertEquals(0, batches.firstPrimitive(0));
        assertEquals(2, batches.primitiveCount(0));
        assertEquals(2, batches.quadCount(0));
        assertEquals(UiShaderVariant.SOLID, batches.shader(0));
        assertEquals(2, batches.firstPrimitive(1));
        assertEquals(2, batches.primitiveCount(1));
        assertEquals(7, batches.texture(1));
        assertEquals(4, batches.firstPrimitive(2));
        assertEquals(8, batches.texture(2));
        assertEquals(5, batches.firstPrimitive(3));
        assertEquals(UiShaderVariant.SOLID, batches.shader(3));
        assertEquals(3, batches.breakStatistics().total());
        assertEquals(2, batches.breakStatistics().shaderChanges());
        assertEquals(1, batches.breakStatistics().textureChanges());
    }

    @Test
    void nestedClipAndExplicitBoundaryPreservePaintOrder() {
        UiScreenRect outer = new UiScreenRect(0.0, 0.0, 10.0, 10.0);
        UiScreenRect inner = new UiScreenRect(5.0, 4.0, 10.0, 3.0);
        UiDisplayList list = new UiDisplayList()
                .addSolidQuad(QUAD, 1, UiBlendMode.PREMULTIPLIED_ALPHA)
                .pushClip(outer)
                .addSolidQuad(QUAD, 2, UiBlendMode.PREMULTIPLIED_ALPHA)
                .pushClip(inner)
                .addSolidQuad(QUAD, 3, UiBlendMode.PREMULTIPLIED_ALPHA)
                .addSolidQuad(QUAD, 4, UiBlendMode.PREMULTIPLIED_ALPHA)
                .popClip()
                .addSolidQuad(QUAD, 5, UiBlendMode.PREMULTIPLIED_ALPHA)
                .popClip()
                .addSolidQuad(QUAD, 6, UiBlendMode.PREMULTIPLIED_ALPHA)
                .paintBoundary()
                .addSolidQuad(QUAD, 7, UiBlendMode.PREMULTIPLIED_ALPHA);

        UiBatcher.Result batches = new UiBatcher().batch(list);

        assertEquals(6, batches.size());
        assertFalse(batches.hasClip(0));
        assertEquals(outer, batches.clip(1));
        assertEquals(new UiScreenRect(5.0, 4.0, 5.0, 3.0), batches.clip(2));
        assertEquals(2, batches.primitiveCount(2));
        assertEquals(outer, batches.clip(3));
        assertFalse(batches.hasClip(4));
        assertFalse(batches.hasClip(5));
        assertEquals(9, batches.firstPrimitive(4));
        assertEquals(11, batches.firstPrimitive(5));
        assertEquals(5, batches.breakStatistics().orderBarriers());
        assertEquals(5, batches.breakStatistics().total());
        assertThrows(IllegalStateException.class, () -> batches.clip(0));
    }

    @Test
    void glyphRunUsesOnePrimitiveAndContiguousQuadRange() {
        UiDisplayList list = new UiDisplayList();
        list.beginGlyphRun(4, 2, UiBlendMode.PREMULTIPLIED_ALPHA);
        list.addGlyph(new UiScreenRect(0.0, 0.0, 4.0, 6.0), UiUvRect.FULL, 0xffffffff);
        list.addGlyph(new UiScreenRect(4.0, 0.0, 5.0, 6.0), UiUvRect.FULL, 0xffffffff);
        list.endGlyphRun();
        list.beginGlyphRun(4, 2, UiBlendMode.PREMULTIPLIED_ALPHA);
        list.addGlyph(new UiScreenRect(9.0, 0.0, 4.0, 6.0), UiUvRect.FULL, 0xffffffff);
        list.endGlyphRun();

        UiBatcher.Result batches = new UiBatcher().batch(list);

        assertEquals(2, list.primitiveCount());
        assertEquals(3, list.quadCount());
        assertEquals(UiPrimitiveKind.GLYPH_RUN, list.primitiveKind(0));
        assertEquals(2, list.primitiveQuadCount(0));
        assertEquals(1, batches.size());
        assertEquals(2, batches.primitiveCount(0));
        assertEquals(3, batches.quadCount(0));
        assertEquals(UiShaderVariant.GLYPH, batches.shader(0));
    }

    @Test
    void renderSnapshotFreezesArenaBeforeBuilderReuse() {
        UiDisplayList builder = new UiDisplayList()
                .addSolidQuad(QUAD, 0x01020304, UiBlendMode.PREMULTIPLIED_ALPHA);

        UiRenderSnapshot snapshot = UiRenderSnapshot.capture(7, 800, 600,
                1200, 900, 1.5, 1.5, builder, new UiBatcher());
        builder.clear();
        builder.addSolidQuad(new UiScreenRect(20.0, 20.0, 1.0, 1.0),
                0xffffffff, UiBlendMode.OPAQUE);

        assertEquals(7, snapshot.sequence());
        assertTrue(snapshot.displayList().isFrozen());
        assertEquals(1, snapshot.displayList().primitiveCount());
        assertEquals(0x01020304, snapshot.displayList().quadColor(0));
        assertEquals(UiBlendMode.PREMULTIPLIED_ALPHA, snapshot.batches().blend(0));
        assertThrows(IllegalStateException.class, snapshot.displayList()::clear);
    }

    @Test
    void logicalImagesSurviveSnapshotsAndNeverMergeAcrossIds() {
        UiDisplayList builder = new UiDisplayList()
                .addLogicalImageQuad(QUAD, UiUvRect.FULL, 41L, 7, 3,
                        0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA)
                .addLogicalImageQuad(QUAD, UiUvRect.FULL, 42L, 7, 3,
                        0xffffffff, UiBlendMode.PREMULTIPLIED_ALPHA);

        UiRenderSnapshot snapshot = UiRenderSnapshot.capture(1L, builder);

        assertEquals(2, snapshot.batches().size());
        assertEquals(41L, snapshot.batches().imageId(0));
        assertEquals(42L, snapshot.batches().imageId(1));
        assertEquals(1L, snapshot.batches().breakStatistics().textureChanges());
    }

    @Test
    void incompleteClipOrGlyphRecordingCannotBePublished() {
        UiDisplayList clip = new UiDisplayList().pushClip(QUAD);
        UiDisplayList glyph = new UiDisplayList();
        glyph.beginGlyphRun(1, 1, UiBlendMode.PREMULTIPLIED_ALPHA);

        assertThrows(IllegalStateException.class, clip::freeze);
        assertThrows(IllegalStateException.class, glyph::freeze);
        glyph.abortGlyphRun();
        assertEquals(0, glyph.freeze().quadCount());
        assertThrows(IllegalStateException.class, new UiDisplayList()::popClip);
    }
}
