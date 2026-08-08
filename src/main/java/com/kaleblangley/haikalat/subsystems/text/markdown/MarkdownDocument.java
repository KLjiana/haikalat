package com.kaleblangley.haikalat.subsystems.text.markdown;

import java.util.List;
import java.util.Objects;

/** Immutable, renderer-neutral Markdown document produced by {@link MarkdownParser}. */
public record MarkdownDocument(List<Block> blocks) {
    public MarkdownDocument {
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    }

    public enum BlockKind {
        HEADING_1, HEADING_2, HEADING_3, PARAGRAPH, QUOTE, LIST, CODE, SPACER
    }

    public enum InlineKind { TEXT, STRONG, EMPHASIS, CODE }

    public record Inline(InlineKind kind, String text) {
        public Inline {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            if (text.isEmpty()) throw new IllegalArgumentException("inline text must not be empty");
        }
    }

    public record Block(BlockKind kind, String text, List<Inline> inlines) {
        public Block {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            inlines = List.copyOf(Objects.requireNonNull(inlines, "inlines"));
        }

        public Block(BlockKind kind, String text) {
            this(kind, text, text.isEmpty()
                    ? List.of() : List.of(new Inline(InlineKind.TEXT, text)));
        }
    }
}
