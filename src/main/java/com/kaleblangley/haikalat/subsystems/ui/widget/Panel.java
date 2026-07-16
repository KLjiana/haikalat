package com.kaleblangley.haikalat.subsystems.ui.widget;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiSemanticRole;

/** 带背景、边框、padding 和可选裁剪的通用容器。 */
public class Panel extends UiNode {
    public Panel() {
        semantics(UiSemanticRole.GROUP, "", "");
    }
}
