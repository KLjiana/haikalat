package com.kaleblangley.haikalat.subsystems.ui.layout;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;

import java.util.List;
import java.util.Objects;

/**
 * UI 树的逻辑像素布局边界。
 *
 * <p>公开接口只使用引擎自身的 UI 类型，底层布局库的句柄和枚举不会泄漏给控件。</p>
 */
public interface LayoutEngine extends AutoCloseable {
    /**
     * 在相同视口中布局一组互相独立的根节点。
     *
     * @param roots 根节点列表，通常为普通 root 与 overlay root
     * @param logicalWidth 窗口内容区逻辑宽度
     * @param logicalHeight 窗口内容区逻辑高度
     */
    void layout(List<? extends UiNode> roots, float logicalWidth, float logicalHeight);

    /** 布局单个根节点。 */
    default void layout(UiNode root, float logicalWidth, float logicalHeight) {
        layout(List.of(Objects.requireNonNull(root, "root")), logicalWidth, logicalHeight);
    }

    /** 同时布局文档的普通 root 和 overlay root。 */
    default void layout(UiDocument document, float logicalWidth, float logicalHeight) {
        Objects.requireNonNull(document, "document").ensureOpen();
        layout(List.of(document.root(), document.overlayRoot()), logicalWidth, logicalHeight);
    }

    /** 释放布局引擎持有的全部资源；重复调用没有副作用。 */
    @Override
    void close();
}
