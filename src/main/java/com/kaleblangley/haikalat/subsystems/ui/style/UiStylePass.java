package com.kaleblangley.haikalat.subsystems.ui.style;

import com.kaleblangley.haikalat.subsystems.ui.UiDirtyFlag;
import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.ui.widget.Toggle;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** 按控件状态解析 typed theme，并只让实际字段差异触发节点失效。 */
public final class UiStylePass {
    private final StyleResolver resolver;

    public UiStylePass(StyleResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public int resolve(UiDocument document) {
        Objects.requireNonNull(document, "document").ensureOpen();
        int changed = resolveNode(document, document.root(), null, true);
        return changed + resolveNode(document, document.overlayRoot(), null, true);
    }

    private int resolveNode(UiDocument document, UiNode node, ComputedStyle inherited,
                            boolean root) {
        EnumSet<StyleResolver.PseudoState> states = EnumSet.noneOf(StyleResolver.PseudoState.class);
        if (!node.enabled()) states.add(StyleResolver.PseudoState.DISABLED);
        if (document.focusManager().focused() == node) states.add(StyleResolver.PseudoState.FOCUSED);
        if (node instanceof Button button) {
            if (button.hovered()) states.add(StyleResolver.PseudoState.HOVER);
            if (button.pressed()) states.add(StyleResolver.PseudoState.PRESSED);
        }
        if (node instanceof Toggle toggle && toggle.value()) states.add(StyleResolver.PseudoState.CHECKED);
        if (node instanceof Slider slider && slider.dragging()) states.add(StyleResolver.PseudoState.PRESSED);

        ComputedStyle resolved = resolver.resolve(node.widgetType(), Set.of(), states, inherited);
        if (root) {
            resolved = new ComputedStyle(UiColor.TRANSPARENT, resolved.foreground(),
                    UiColor.TRANSPARENT, 0.0f, 0.0f, resolved.opacity(),
                    resolved.fontSize(), resolved.fontFamily());
        }
        boolean changed = !resolved.equals(node.computedStyle());
        node.computedStyle(resolved);
        node.clearDirty(UiDirtyFlag.STYLE);
        int count = changed ? 1 : 0;
        for (UiNode child : node.children()) count += resolveNode(document, child, resolved, false);
        return count;
    }
}
