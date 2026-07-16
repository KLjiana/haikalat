package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;

import java.util.Objects;

/** 由可键盘聚焦按钮项组成的基础 popup menu。 */
public final class Menu extends Popup {
    public Menu() { semantics(UiSemanticRole.MENU, "", ""); }

    public Button item(String label, Runnable action) {
        Button item = new Button(Objects.requireNonNull(label, "label"));
        item.semantics(UiSemanticRole.MENU_ITEM, label, "");
        item.onClick(() -> {
            action.run();
            dismiss();
        });
        add(item);
        return item;
    }
}
