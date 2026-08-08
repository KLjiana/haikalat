package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;
import com.kaleblangley.haikalat.subsystems.ui.event.KeyEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.TextEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureContext;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureResult;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.render.UiTextLineMetrics;
import com.kaleblangley.haikalat.subsystems.text.TextBoundaryService;
import com.kaleblangley.haikalat.subsystems.text.TextRange;
import com.kaleblangley.haikalat.subsystems.windowing.input.ClipboardService;
import com.kaleblangley.haikalat.subsystems.windowing.input.ImeComposition;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * grapheme-safe 的单行文本编辑控件。
 *
 * <p>所有 selection/caret 索引均为 UTF-16 offset，但只会停在
 * {@link TextBoundaryService} 允许的 grapheme 边界。</p>
 */
public final class TextField extends UiNode {
    private static final int DEFAULT_UNDO_LIMIT = 100;

    private final TextBoundaryService boundaries;
    private final List<Consumer<String>> valueListeners = new ArrayList<>();
    private final ArrayDeque<EditState> undo = new ArrayDeque<>();
    private final ArrayDeque<EditState> redo = new ArrayDeque<>();
    private String value = "";
    private String placeholder = "";
    private int anchor;
    private int caret;
    private int maximumCodePoints = Integer.MAX_VALUE;
    private int undoLimit = DEFAULT_UNDO_LIMIT;
    private Predicate<String> validator = ignored -> true;
    private ClipboardService clipboard;
    private ImeComposition composition;
    private boolean password;
    private boolean notifying;
    private boolean draggingSelection;
    private UiTextLineMetrics textMetrics = UiTextLineMetrics.approximate("", 0.0);
    private double horizontalScroll;

    public TextField() { this(new TextBoundaryService()); }

    public TextField(TextBoundaryService boundaries) {
        this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
        focusable(true);
        semantics(UiSemanticRole.TEXT_FIELD, "", value);
    }

    public String value() { return value; }
    public String placeholder() { return placeholder; }
    public int selectionStart() { return Math.min(anchor, caret); }
    public int selectionEnd() { return Math.max(anchor, caret); }
    public int caretOffset() { return caret; }
    public boolean hasSelection() { return anchor != caret; }
    public boolean password() { return password; }
    public ImeComposition composition() { return composition; }
    public double horizontalScroll() { return horizontalScroll; }

    /** 返回当前 caret 在内容框中的可见 X，供 painter 与 IME 共用。 */
    public double visibleCaretX() {
        if (!textMetrics.text().equals(value)) return 0.0;
        return Math.max(0.0, textMetrics.xAt(caret) - horizontalScroll);
    }

    /** 接收字体引擎的单行几何并保证 caret 始终位于可见内容区域。 */
    public void updateTextMetrics(UiTextLineMetrics metrics, double viewportWidth) {
        metrics = Objects.requireNonNull(metrics, "metrics");
        if (!metrics.text().equals(value)) {
            throw new IllegalArgumentException("text metrics must describe the committed field value");
        }
        if (!Double.isFinite(viewportWidth) || viewportWidth < 0.0) {
            throw new IllegalArgumentException("viewport width must be finite and non-negative");
        }
        textMetrics = metrics;
        double caretX = metrics.xAt(caret);
        double margin = Math.min(2.0, viewportWidth * 0.25);
        if (caretX - horizontalScroll > viewportWidth - margin) {
            horizontalScroll = caretX - viewportWidth + margin;
        } else if (caretX - horizontalScroll < margin) {
            horizontalScroll = caretX - margin;
        }
        double maximum = Math.max(0.0, metrics.width() - viewportWidth + margin);
        horizontalScroll = Math.max(0.0, Math.min(horizontalScroll, maximum));
    }

    /** 返回扣除 border 与 padding 后用于文字、caret 和命中的逻辑内容框。 */
    public LayoutBox textContentBox() {
        LayoutBox box = layoutBox();
        double border = computedStyle().borderWidth();
        double left = border + resolveInset(style().padding().left(), box.width());
        double top = border + resolveInset(style().padding().top(), box.width());
        double right = border + resolveInset(style().padding().right(), box.width());
        double bottom = border + resolveInset(style().padding().bottom(), box.width());
        float width = (float) Math.max(0.0, box.width() - left - right);
        float height = (float) Math.max(0.0, box.height() - top - bottom);
        return new LayoutBox((float) (box.x() + left), (float) (box.y() + top), width, height);
    }

    public TextField value(String newValue) {
        replaceWhole(normalizeSingleLine(Objects.requireNonNull(newValue, "value")), false);
        return this;
    }

    public TextField placeholder(String text) {
        text = Objects.requireNonNull(text, "placeholder");
        if (!placeholder.equals(text)) {
            placeholder = text;
            markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.PAINT);
        }
        return this;
    }

    public TextField password(boolean enabled) {
        if (password != enabled) {
            password = enabled;
            markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.SEMANTICS);
        }
        return this;
    }

    public TextField maximumCodePoints(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("maximumCodePoints must be non-negative");
        maximumCodePoints = maximum;
        if (boundaries.codePointCount(value) > maximum) replaceWhole(truncate(value, maximum), true);
        return this;
    }

    public TextField validator(Predicate<String> value) {
        validator = Objects.requireNonNull(value, "validator");
        return this;
    }

    public TextField clipboard(ClipboardService value) {
        clipboard = value;
        return this;
    }

    public TextField undoLimit(int value) {
        if (value <= 0) throw new IllegalArgumentException("undo limit must be positive");
        undoLimit = value;
        trimUndo();
        return this;
    }

    public TextField select(int anchorOffset, int caretOffset) {
        anchor = normalizedBoundary(anchorOffset);
        caret = normalizedBoundary(caretOffset);
        markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.SEMANTICS);
        return this;
    }

    public TextField selectAll() { return select(0, value.length()); }

    public AutoCloseable onValueChanged(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        valueListeners.add(listener);
        return () -> valueListeners.remove(listener);
    }

    /** 设置临时 preedit；不会立即写入 committed value。 */
    public void updateComposition(ImeComposition value) {
        value = Objects.requireNonNull(value, "composition");
        if (!value.equals(composition)) {
            composition = value;
            markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.PAINT);
        }
    }

    /** 提交 preedit 并按当前 selection 替换。 */
    public boolean commitComposition(String committedText) {
        composition = null;
        return replaceSelection(committedText, true);
    }

    /** 只取消临时 preedit，不回滚其他已提交编辑。 */
    public void cancelComposition() {
        if (composition != null) {
            composition = null;
            markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.PAINT);
        }
    }

    @Override
    public MeasureResult measure(MeasureContext context) {
        float size = computedStyle().fontSize();
        int count = Math.max(1, boundaries.codePointCount(value.isEmpty() ? placeholder : value));
        float preferred = count * size * 0.6f + size;
        return new MeasureResult(Math.min(preferred, context.availableWidth()), size * 1.5f);
    }

    @Override
    protected void handleDefaultEvent(UiEvent event) {
        switch (event.type()) {
            case TEXT_INPUT -> {
                if (enabled() && event instanceof TextEvent text && composition == null) {
                    if (replaceSelection(text.text(), true)) event.preventDefault();
                }
            }
            case KEY_DOWN -> {
                if (enabled() && event instanceof KeyEvent key) handleKey(key);
            }
            case POINTER_DOWN -> {
                if (enabled() && event instanceof PointerEvent pointer) {
                    event.requestFocus();
                    event.capturePointer(pointer.pointerId());
                    draggingSelection = true;
                    int offset = caretFromX(pointer.x());
                    if (event.modifiers().shift()) select(anchor, offset);
                    else select(offset, offset);
                }
            }
            case POINTER_MOVE -> {
                if (draggingSelection && event instanceof PointerEvent pointer) select(anchor, caretFromX(pointer.x()));
            }
            case POINTER_UP, POINTER_CANCEL -> {
                draggingSelection = false;
                if (event instanceof PointerEvent pointer) event.releasePointer(pointer.pointerId());
            }
            case FOCUS_LOST -> {
                draggingSelection = false;
                cancelComposition();
            }
            default -> { }
        }
    }

    private void handleKey(KeyEvent event) {
        Key key = event.key();
        boolean control = event.modifiers().control();
        boolean shift = event.modifiers().shift();
        if (control && key == Key.A) {
            selectAll();
        } else if (control && key == Key.C) {
            copySelection();
        } else if (control && key == Key.X) {
            if (copySelection() && !password) deleteSelection(true);
        } else if (control && key == Key.V) {
            paste();
        } else if (control && key == Key.Z) {
            undo();
        } else if (control && key == Key.Y) {
            redo();
        } else if (key == Key.LEFT) {
            moveCaret(control ? previousWord(caret) : boundaries.previousCaretOffset(value, caret), shift);
        } else if (key == Key.RIGHT) {
            moveCaret(control ? nextWord(caret) : boundaries.nextCaretOffset(value, caret), shift);
        } else if (key == Key.HOME) {
            moveCaret(0, shift);
        } else if (key == Key.END) {
            moveCaret(value.length(), shift);
        } else if (key == Key.BACKSPACE && composition == null) {
            if (!deleteSelection(true)) deleteRange(boundaries.backwardDeletionRange(value, caret), true);
        } else if (key == Key.DELETE && composition == null) {
            if (!deleteSelection(true)) deleteRange(boundaries.forwardDeletionRange(value, caret), true);
        } else {
            return;
        }
        event.preventDefault();
    }

    private boolean replaceSelection(String inserted, boolean recordUndo) {
        inserted = normalizeSingleLine(Objects.requireNonNull(inserted, "inserted"));
        int start = selectionStart();
        int end = selectionEnd();
        String candidate = value.substring(0, start) + inserted + value.substring(end);
        candidate = truncate(candidate, maximumCodePoints);
        if (!validator.test(candidate)) return false;
        if (candidate.equals(value) && start == end && inserted.isEmpty()) return false;
        if (recordUndo) pushUndo();
        value = candidate;
        int proposedCaret = Math.min(candidate.length(), start + inserted.length());
        caret = normalizedBoundary(proposedCaret);
        anchor = caret;
        redo.clear();
        changed();
        return true;
    }

    private boolean deleteSelection(boolean recordUndo) {
        if (!hasSelection()) return false;
        return deleteRange(new TextRange(selectionStart(), selectionEnd()), recordUndo);
    }

    private boolean deleteRange(TextRange range, boolean recordUndo) {
        if (range.isEmpty()) return false;
        select(range.startUtf16(), range.endUtf16());
        return replaceSelection("", recordUndo);
    }

    private void replaceWhole(String candidate, boolean recordUndo) {
        candidate = truncate(candidate, maximumCodePoints);
        if (!validator.test(candidate) || candidate.equals(value)) return;
        if (recordUndo) pushUndo();
        value = candidate;
        anchor = caret = value.length();
        redo.clear();
        changed();
    }

    private boolean copySelection() {
        if (clipboard == null || !hasSelection()) return false;
        String selected = password ? "" : value.substring(selectionStart(), selectionEnd());
        try {
            clipboard.writeText(selected);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void paste() {
        if (clipboard == null) return;
        try {
            clipboard.readText().ifPresent(text -> replaceSelection(text, true));
        } catch (RuntimeException ignored) {
            // 剪贴板失败不得破坏当前 value/selection。
        }
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(new EditState(value, anchor, caret));
        restore(undo.pop());
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(new EditState(value, anchor, caret));
        restore(redo.pop());
    }

    private void restore(EditState state) {
        value = state.value;
        anchor = state.anchor;
        caret = state.caret;
        composition = null;
        changed();
    }

    private void pushUndo() {
        undo.push(new EditState(value, anchor, caret));
        trimUndo();
    }

    private void trimUndo() {
        while (undo.size() > undoLimit) undo.removeLast();
    }

    private void changed() {
        semantics(UiSemanticRole.TEXT_FIELD, "", password ? "" : value);
        markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT, UiDirtyFlag.PAINT,
                UiDirtyFlag.SEMANTICS);
        if (notifying) return;
        notifying = true;
        try {
            int guard = 0;
            String notified = null;
            while (!Objects.equals(notified, value)) {
                if (++guard > 100) throw new IllegalStateException("TextField listener mutation did not converge");
                notified = value;
                for (Consumer<String> listener : List.copyOf(valueListeners)) listener.accept(notified);
            }
        } finally {
            notifying = false;
        }
    }

    private void moveCaret(int offset, boolean extend) {
        int normalized = normalizedBoundary(offset);
        if (extend) caret = normalized;
        else anchor = caret = normalized;
        markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.SEMANTICS);
    }

    private int previousWord(int offset) {
        List<Integer> points = boundaries.wordBoundaries(value);
        int index = Collections.binarySearch(points, offset);
        int candidate = index >= 0 ? index - 1 : -index - 2;
        return points.get(Math.max(0, candidate));
    }

    private int nextWord(int offset) {
        List<Integer> points = boundaries.wordBoundaries(value);
        int index = Collections.binarySearch(points, offset);
        int candidate = index >= 0 ? index + 1 : -index - 1;
        return points.get(Math.min(points.size() - 1, candidate));
    }

    private int normalizedBoundary(int offset) {
        if (offset < 0 || offset > value.length()) {
            throw new IndexOutOfBoundsException("selection offset outside value: " + offset);
        }
        List<Integer> points = boundaries.graphemeBoundaries(value);
        int index = Collections.binarySearch(points, offset);
        return index >= 0 ? offset : points.get(Math.max(0, -index - 2));
    }

    private int caretFromX(double x) {
        LayoutBox contentBox = textContentBox();
        if (value.isEmpty() || contentBox.width() <= 0.0f) return 0;
        if (textMetrics.text().equals(value)) {
            return normalizedBoundary(textMetrics.offsetAt(
                    x - contentBox.x() + horizontalScroll));
        }
        double fraction = (x - contentBox.x()) / contentBox.width();
        int graphemeIndex = (int) Math.round(Math.max(0.0, Math.min(1.0, fraction))
                * (boundaries.graphemeBoundaries(value).size() - 1));
        return boundaries.graphemeBoundaries(value).get(graphemeIndex);
    }

    private static double resolveInset(UiLength length, double referenceWidth) {
        return switch (length.unit()) {
            case AUTO -> 0.0;
            case POINTS -> length.value();
            case PERCENT -> referenceWidth * length.value() / 100.0;
        };
    }

    private String truncate(String text, int maximum) {
        int count = boundaries.codePointCount(text);
        if (count <= maximum) return text;
        int offset = boundaries.utf16Offset(text, maximum);
        while (offset > 0 && !boundaries.isGraphemeBoundary(text, offset)) {
            offset = boundaries.previousCaretOffset(text, offset);
        }
        return text.substring(0, offset);
    }

    private static String normalizeSingleLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private record EditState(String value, int anchor, int caret) {
    }
}
