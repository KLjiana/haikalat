package com.kaleblangley.haikalat.subsystems.text.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParserTest {
    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void preservesStrongAndInlineCodeRuns() {
        MarkdownDocument document = parser.parse("Inline **strong** and `code` remain visible.");

        MarkdownDocument.Block paragraph = document.blocks().getFirst();
        assertEquals(MarkdownDocument.BlockKind.PARAGRAPH, paragraph.kind());
        assertEquals("Inline strong and code remain visible.", paragraph.text());
        assertTrue(paragraph.inlines().stream().anyMatch(run ->
                run.kind() == MarkdownDocument.InlineKind.STRONG && run.text().equals("strong")));
        assertTrue(paragraph.inlines().stream().anyMatch(run ->
                run.kind() == MarkdownDocument.InlineKind.CODE && run.text().equals("code")));
        assertFalse(paragraph.text().contains("**"));
        assertFalse(paragraph.text().contains("`"));
    }

    @Test
    void keepsSoftBreaksAndFencedCodeAsSeparateDisplayLines() {
        MarkdownDocument document = parser.parse("""
                first line
                second line

                ```java
                int first = 1;
                int second = 2;
                ```
                """);

        assertEquals(List.of("first line", "second line"), document.blocks().stream()
                .filter(block -> block.kind() == MarkdownDocument.BlockKind.PARAGRAPH)
                .map(MarkdownDocument.Block::text).toList());
        assertEquals(List.of("int first = 1;", "int second = 2;"), document.blocks().stream()
                .filter(block -> block.kind() == MarkdownDocument.BlockKind.CODE)
                .map(MarkdownDocument.Block::text).toList());
    }
}
