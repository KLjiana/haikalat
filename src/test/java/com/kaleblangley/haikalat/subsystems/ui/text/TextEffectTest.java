package com.kaleblangley.haikalat.subsystems.ui.text;

import com.kaleblangley.haikalat.subsystems.ui.render.UiBlendMode;
import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiPrimitiveKind;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.render.UiUvRect;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBatcher;
import com.kaleblangley.haikalat.subsystems.ui.render.UiGlyphPainter;
import com.kaleblangley.haikalat.subsystems.ui.render.UiImageResolver;
import com.kaleblangley.haikalat.subsystems.ui.render.UiPainter;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiDebugOptions;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEffectTest {
    @Test
    void offsetIsDefensiveAndGradientAngleIsInDegrees() {
        TextEffect effect = TextEffect.dropShadow(UiColor.WHITE, 2.0f, 3.0f, 4.0f);
        Vector2f offset = (Vector2f) effect.offset();
        offset.set(99.0f, 100.0f);
        assertEquals(2.0f, effect.offsetX());
        assertEquals(3.0f, effect.offsetY());
        assertNotSame(offset, effect.offset());
        assertEquals(45.0f, TextEffect.gradient(UiColor.WHITE, UiColor.BLACK, 45.0f).angleDegrees());
    }

    @Test
    void externalEffectsRejectExtentsBeyondAtlasPadding() {
        assertThrows(IllegalArgumentException.class,
                () -> TextEffect.outline(UiColor.WHITE, TextEffect.MAXIMUM_EXTENT + 0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> TextEffect.glow(UiColor.WHITE, TextEffect.MAXIMUM_EXTENT + 0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> TextEffect.dropShadow(UiColor.WHITE, 5.0f, 0.0f, 4.0f));
    }

    @Test
    void pendingEffectIsCopiedIntoGlyphRunsAndSeparatesBatches() {
        TextEffect effect = TextEffect.gradient(UiColor.WHITE, UiColor.BLACK, 45.0f);
        UiDisplayList list = new UiDisplayList();
        list.setTextEffect(effect);
        list.beginGlyphRun(1, 1, UiBlendMode.PREMULTIPLIED_ALPHA);
        list.addGlyph(new UiScreenRect(0, 0, 4, 6), UiUvRect.FULL, 0xffffffff);
        list.endGlyphRun();
        list.setTextEffect(TextEffect.none());
        list.beginGlyphRun(1, 1, UiBlendMode.PREMULTIPLIED_ALPHA);
        list.addGlyph(new UiScreenRect(4, 0, 4, 6), UiUvRect.FULL, 0xffffffff);
        list.endGlyphRun();

        assertEquals(UiPrimitiveKind.GLYPH_RUN, list.primitiveKind(0));
        assertEquals(5, list.textEffectType(0));
        assertEquals(45.0f, list.textEffectAngle(0));
        assertEquals(0, list.textEffectType(1));
        assertEquals(2, new UiBatcher().batch(list).size());
    }

    @Test
    void painterCarriesLabelEffectIntoGlyphRun() {
        UiGlyphPainter glyphPainter = (list, node, text, bounds, color) -> {
            list.beginGlyphRun(1, 1, UiBlendMode.PREMULTIPLIED_ALPHA);
            list.addGlyph(bounds, UiUvRect.FULL, color);
            list.endGlyphRun();
            return true;
        };
        try (UiDocument document = new UiDocument()) {
            Label label = new Label("A").textEffect(TextEffect.outline(UiColor.BLACK, 2.0f));
            label.applyLayout(new LayoutBox(0, 0, 40, 20));
            document.root().add(label);
            UiDisplayList list = new UiPainter(UiImageResolver.empty(), glyphPainter,
                    UiDebugOptions.NONE).paint(document);
            int glyphPrimitive = -1;
            for (int index = 0; index < list.primitiveCount(); index++) {
                if (list.primitiveKind(index) == UiPrimitiveKind.GLYPH_RUN) {
                    glyphPrimitive = index;
                    break;
                }
            }
            assertTrue(glyphPrimitive >= 0);
            assertEquals(TextEffectType.OUTLINE.ordinal(), list.textEffectType(glyphPrimitive));
            assertEquals(2.0f, list.textEffectThickness(glyphPrimitive));
        }
    }
}
