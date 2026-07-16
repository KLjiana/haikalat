package com.kaleblangley.haikalat.subsystems.ui.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextBoundaryServiceTest {
    private final TextBoundaryService boundaries = new TextBoundaryService();

    @Test
    void supplementaryCodePointUsesOneCaretStep() {
        String text = "A\uD83D\uDE00B";

        assertEquals(3, boundaries.codePointCount(text));
        assertEquals(List.of(0, 1, 3, 4), boundaries.codePointBoundaries(text));
        assertFalse(boundaries.isCodePointBoundary(text, 2));
        assertEquals(1, boundaries.previousCaretOffset(text, 3));
        assertEquals(3, boundaries.nextCaretOffset(text, 1));
        assertEquals(new TextRange(1, 3), boundaries.backwardDeletionRange(text, 3));
        assertEquals(new TextRange(1, 3), boundaries.forwardDeletionRange(text, 1));
    }

    @Test
    void combiningSequenceIsNeverSplitByCaretOrDeletion() {
        String text = "e\u0301x";

        assertEquals(List.of(0, 2, 3), boundaries.graphemeBoundaries(text));
        assertFalse(boundaries.isGraphemeBoundary(text, 1));
        assertEquals(0, boundaries.previousCaretOffset(text, 2));
        assertEquals(2, boundaries.nextCaretOffset(text, 0));
        assertEquals(new TextRange(0, 2), boundaries.backwardDeletionRange(text, 2));
        assertEquals(new TextRange(0, 2), boundaries.forwardDeletionRange(text, 1));
    }

    @Test
    void emojiJoinerModifierAndRegionalPairStayAsClusters() {
        String technologist = "\uD83D\uDC69\u200D\uD83D\uDCBB";
        String tonedThumb = "\uD83D\uDC4D\uD83C\uDFFD";
        String flag = "\uD83C\uDDE8\uD83C\uDDF3";

        assertEquals(List.of(0, technologist.length()), boundaries.graphemeBoundaries(technologist));
        assertEquals(List.of(0, tonedThumb.length()), boundaries.graphemeBoundaries(tonedThumb));
        assertEquals(List.of(0, flag.length()), boundaries.graphemeBoundaries(flag));
    }

    @Test
    void cjkLineIteratorExposesPerIdeographBreaks() {
        String text = "中文测试";

        assertEquals(List.of(0, 1, 2, 3, 4), boundaries.lineBreakBoundaries(text));
        for (int boundary : boundaries.lineBreakBoundaries(text)) {
            assertTrue(boundaries.isGraphemeBoundary(text, boundary));
        }
    }

    @Test
    void returnedBoundaryListsAreImmutableAndOffsetsAreChecked() {
        List<Integer> result = boundaries.graphemeBoundaries("abc");

        assertThrows(UnsupportedOperationException.class, () -> result.add(4));
        assertThrows(IndexOutOfBoundsException.class, () -> boundaries.nextCaretOffset("abc", 4));
    }
}
