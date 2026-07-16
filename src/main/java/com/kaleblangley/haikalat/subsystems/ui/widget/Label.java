package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureContext;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureResult;

import java.util.Objects;

/** 支持换行、截断和对齐合同的只读文本节点。 */
public class Label extends UiNode {
    public enum Wrap { NONE, WORD, GRAPHEME }
    public enum Alignment { START, CENTER, END }

    private String text = "";
    private Wrap wrap = Wrap.NONE;
    private Alignment alignment = Alignment.START;
    private int maximumLines = Integer.MAX_VALUE;
    private boolean ellipsis;

    public Label() {
        this("");
    }

    public Label(String text) {
        hitTestVisible(false);
        text(text);
    }

    public String text() { return text; }
    public Label text(String value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "text");
        if (!text.equals(value)) {
            text = value;
            semantics(UiSemanticRole.TEXT, value, value);
            markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT, UiDirtyFlag.PAINT,
                    UiDirtyFlag.SEMANTICS);
        }
        return this;
    }

    public Wrap wrap() { return wrap; }
    public Label wrap(Wrap value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "wrap");
        if (wrap != value) {
            wrap = value;
            markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT, UiDirtyFlag.PAINT);
        }
        return this;
    }

    public Alignment alignment() { return alignment; }
    public Label alignment(Alignment value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "alignment");
        if (alignment != value) {
            alignment = value;
            markDirty(UiDirtyFlag.PAINT);
        }
        return this;
    }

    public int maximumLines() { return maximumLines; }
    public Label maximumLines(int value) {
        ensureOpen();
        if (value <= 0) throw new IllegalArgumentException("maximumLines must be positive");
        if (maximumLines != value) {
            maximumLines = value;
            markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT, UiDirtyFlag.PAINT);
        }
        return this;
    }

    public boolean ellipsis() { return ellipsis; }
    public Label ellipsis(boolean value) {
        ensureOpen();
        if (ellipsis != value) {
            ellipsis = value;
            markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.PAINT);
        }
        return this;
    }

    @Override
    public MeasureResult measure(MeasureContext context) {
        Objects.requireNonNull(context, "context");
        float fontSize = computedStyle().fontSize();
        float unconstrained = text.codePointCount(0, text.length()) * fontSize * 0.6f;
        float lineHeight = fontSize * 1.2f;
        float available = context.availableWidth();
        if (wrap == Wrap.NONE || !Float.isFinite(available) || available <= 0.0f) {
            return new MeasureResult(unconstrained, lineHeight);
        }
        int lines = Math.min(maximumLines, Math.max(1, (int) Math.ceil(unconstrained / available)));
        return new MeasureResult(Math.min(unconstrained, available), lineHeight * lines);
    }
}
