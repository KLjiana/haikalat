package com.kaleblangley.haikalat.subsystems.text.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void retainsLinkTextButNotDestinationAndDoesNotRenderImages() {
        MarkdownDocument document = parser.parse(
                "Read [the guide](https://example.invalid/secret) ![hidden alt](image.png) now.");

        assertEquals("Read the guide  now.", document.blocks().getFirst().text());
        assertFalse(document.blocks().getFirst().text().contains("https://"));
        assertFalse(document.blocks().getFirst().text().contains("hidden alt"));
    }

    @Test
    void flattensNestedListsAndDropsOrderedNumbers() {
        MarkdownDocument document = parser.parse("""
                1. first
                   - nested
                     1. deep
                2. second
                """);

        assertEquals(List.of("first", "nested", "deep", "second"), document.blocks().stream()
                .filter(block -> block.kind() == MarkdownDocument.BlockKind.LIST)
                .map(MarkdownDocument.Block::text).toList());
    }

    @Test
    void mapsHeadingLevelsFourThroughSixToHeadingThree() {
        MarkdownDocument document = parser.parse("""
                #### four
                ##### five
                ###### six
                """);

        assertEquals(List.of(MarkdownDocument.BlockKind.HEADING_3,
                        MarkdownDocument.BlockKind.SPACER,
                        MarkdownDocument.BlockKind.HEADING_3,
                        MarkdownDocument.BlockKind.SPACER,
                        MarkdownDocument.BlockKind.HEADING_3),
                document.blocks().stream().map(MarkdownDocument.Block::kind).toList());
        assertEquals(List.of("four", "five", "six"), document.blocks().stream()
                .filter(block -> block.kind() == MarkdownDocument.BlockKind.HEADING_3)
                .map(MarkdownDocument.Block::text).toList());
    }

    @Test
    void ignoresFenceLanguageAndSupportsIndentedCode() {
        MarkdownDocument document = parser.parse("""
                ```java
                int value = 1;
                ```

                    indented();
                """);

        assertEquals(List.of("int value = 1;", "indented();"), document.blocks().stream()
                .filter(block -> block.kind() == MarkdownDocument.BlockKind.CODE)
                .map(MarkdownDocument.Block::text).toList());
        assertFalse(document.blocks().stream().anyMatch(block -> block.text().contains("java")));
    }

    @Test
    void emptyAndUnsupportedTopLevelDocumentsProduceNoDisplayBlocks() {
        assertTrue(parser.parse("").blocks().isEmpty());
        assertTrue(parser.parse("---\n\n<div>unsupported</div>").blocks().isEmpty());
    }

    @Test
    void rejectsMarkdownBeyondConfiguredCapacity() {
        MarkdownParser bounded = new MarkdownParser(8);

        assertEquals("12345678", bounded.parse("12345678").blocks().getFirst().text());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> bounded.parse("123456789"));
        assertTrue(failure.getMessage().contains("8 character limit"));
    }
}
