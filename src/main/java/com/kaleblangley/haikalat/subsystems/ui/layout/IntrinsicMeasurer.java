package com.kaleblangley.haikalat.subsystems.ui.layout;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;

/**
 * Yoga intrinsic measure callback 与具体控件测量服务之间的可注入边界。
 */
@FunctionalInterface
public interface IntrinsicMeasurer {
    /** 返回节点在给定逻辑像素约束下的固有尺寸。 */
    MeasureResult measure(UiNode node, MeasureContext context);

    /** 返回直接调用 {@link UiNode#measure(MeasureContext)} 的默认实现。 */
    static IntrinsicMeasurer nodeDefault() {
        return UiNode::measure;
    }
}
