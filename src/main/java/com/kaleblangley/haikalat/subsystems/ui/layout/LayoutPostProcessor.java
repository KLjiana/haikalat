package com.kaleblangley.haikalat.subsystems.ui.layout;

/**
 * 在整棵 UI 树完成布局后校正依赖最终尺寸的控件状态。
 *
 * <p>返回 {@code true} 表示校正改变了布局树，布局引擎需要再执行一次稳定化布局。</p>
 */
public interface LayoutPostProcessor {
    boolean afterLayout();
}
