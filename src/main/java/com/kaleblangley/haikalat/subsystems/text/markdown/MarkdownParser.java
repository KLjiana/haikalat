package com.kaleblangley.haikalat.subsystems.text.markdown;

import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument.Block;
import static com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument.BlockKind;
import static com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument.Inline;
import static com.kaleblangley.haikalat.subsystems.text.markdown.MarkdownDocument.InlineKind;

/** Converts CommonMark syntax into the renderer-neutral text document model. */
public final class MarkdownParser {
    private final Parser parser;

    public MarkdownParser() {
        parser = Parser.builder().build();
    }

    public MarkdownDocument parse(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        Node root = parser.parse(markdown);
        List<Block> blocks = new ArrayList<>();
        boolean firstGroup = true;
        for (Node node = root.getFirstChild(); node != null; node = node.getNext()) {
            List<Block> group = parseTopLevel(node);
            if (group.isEmpty()) continue;
            if (!firstGroup) blocks.add(new Block(BlockKind.SPACER, ""));
            blocks.addAll(group);
            firstGroup = false;
        }
        return new MarkdownDocument(blocks);
    }

    private static List<Block> parseTopLevel(Node node) {
        if (node instanceof Heading heading) {
            BlockKind kind = heading.getLevel() == 1 ? BlockKind.HEADING_1
                    : heading.getLevel() == 2 ? BlockKind.HEADING_2 : BlockKind.HEADING_3;
            return inlineBlocks(kind, heading);
        }
        if (node instanceof Paragraph paragraph) {
            return inlineBlocks(BlockKind.PARAGRAPH, paragraph);
        }
        if (node instanceof BlockQuote quote) {
            return inlineBlocks(BlockKind.QUOTE, quote);
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            List<Block> result = new ArrayList<>();
            for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                if (item instanceof ListItem) result.addAll(inlineBlocks(BlockKind.LIST, item));
            }
            return List.copyOf(result);
        }
        if (node instanceof FencedCodeBlock fenced) return codeBlocks(fenced.getLiteral());
        if (node instanceof IndentedCodeBlock indented) return codeBlocks(indented.getLiteral());
        return List.of();
    }

    private static List<Block> codeBlocks(String literal) {
        List<Block> result = new ArrayList<>();
        for (String line : literal.split("\\R", -1)) {
            if (!line.isEmpty()) {
                result.add(new Block(BlockKind.CODE, line,
                        List.of(new Inline(InlineKind.CODE, line))));
            }
        }
        return List.copyOf(result);
    }

    private static List<Block> inlineBlocks(BlockKind kind, Node container) {
        List<List<Inline>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        appendInline(container, InlineKind.TEXT, lines);
        List<Block> result = new ArrayList<>();
        for (List<Inline> line : lines) {
            if (line.isEmpty()) continue;
            String text = line.stream().map(Inline::text).reduce("", String::concat);
            result.add(new Block(kind, text, line));
        }
        return List.copyOf(result);
    }

    private static void appendInline(Node node, InlineKind inherited,
                                     List<List<Inline>> lines) {
        if (node instanceof Text text) {
            appendRun(lines.getLast(), inherited, text.getLiteral());
            return;
        }
        if (node instanceof Code code) {
            appendRun(lines.getLast(), InlineKind.CODE, code.getLiteral());
            return;
        }
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            lines.add(new ArrayList<>());
            return;
        }
        InlineKind style = node instanceof StrongEmphasis ? InlineKind.STRONG
                : node instanceof Emphasis ? InlineKind.EMPHASIS : inherited;
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            appendInline(child, style, lines);
        }
    }

    private static void appendRun(List<Inline> line, InlineKind kind, String text) {
        if (text.isEmpty()) return;
        int lastIndex = line.size() - 1;
        if (lastIndex >= 0 && line.get(lastIndex).kind() == kind) {
            Inline previous = line.get(lastIndex);
            line.set(lastIndex, new Inline(kind, previous.text() + text));
        } else {
            line.add(new Inline(kind, text));
        }
    }
}
