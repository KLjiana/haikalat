package com.kaleblangley.haikalat.subsystems.ui.layout;

/** UI 布局同步、测量或 native 计算失败。 */
public final class UiLayoutException extends RuntimeException {
    public UiLayoutException(String message) {
        super(message);
    }

    public UiLayoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
