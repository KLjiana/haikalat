package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;

import java.util.Objects;

/** 位于 overlay root、保留逻辑 owner 和原焦点的 popup。 */
public class Popup extends Panel {
    private UiNode owner;
    private UiNode previousFocus;
    private boolean open;

    public Popup() { semantics(UiSemanticRole.DIALOG, "", ""); }

    public boolean isOpen() { return open; }
    public UiNode owner() { return owner; }

    public void open(UiDocument document, UiNode logicalOwner) {
        Objects.requireNonNull(document, "document").ensureOpen();
        Objects.requireNonNull(logicalOwner, "logicalOwner");
        if (open) throw new IllegalStateException("popup is already open");
        if (logicalOwner.document() != document || logicalOwner.isClosed()) {
            throw new IllegalArgumentException("popup owner must be an open member of the document");
        }
        owner = logicalOwner;
        previousFocus = document.focusManager().focused();
        anchorToOwner(document, logicalOwner);
        document.overlayRoot().add(this);
        open = true;
        UiNode firstFocusable = firstFocusable(this);
        if (firstFocusable != null) document.focusManager().requestFocus(firstFocusable);
    }

    private void anchorToOwner(UiDocument document, UiNode logicalOwner) {
        LayoutBox anchor = document.visualLayoutBox(logicalOwner);
        LayoutBox viewport = document.root().layoutBox();
        float popupWidth = pointSize(style().width(), layoutBox().width());
        float popupHeight = pointSize(style().height(), layoutBox().height());
        float x = clamp(anchor.x(), viewport.x(),
                Math.max(viewport.x(), viewport.right() - popupWidth));
        float below = anchor.bottom();
        float above = anchor.y() - popupHeight;
        float y = below + popupHeight <= viewport.bottom() ? below : Math.max(viewport.y(), above);
        style(style().withMargin(new UiInsets(
                UiLength.points(x), UiLength.points(y), UiLength.points(0.0f), UiLength.points(0.0f))));
    }

    private static float pointSize(UiLength length, float fallback) {
        return length.unit() == UiLength.Unit.POINTS ? length.value() : Math.max(0.0f, fallback);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public void dismiss() {
        if (!open) return;
        UiDocument ownerDocument = document();
        ownerDocument.overlayRoot().remove(this);
        open = false;
        owner = null;
        if (previousFocus != null && !previousFocus.isClosed()
                && previousFocus.document() == ownerDocument && previousFocus.focusable()) {
            ownerDocument.focusManager().requestFocus(previousFocus);
        }
        previousFocus = null;
    }

    private static UiNode firstFocusable(UiNode root) {
        if (root.focusable() && root.enabled()) return root;
        for (UiNode child : root.children()) {
            UiNode result = firstFocusable(child);
            if (result != null) return result;
        }
        return null;
    }
}
