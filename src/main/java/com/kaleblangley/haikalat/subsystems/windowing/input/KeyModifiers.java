package com.kaleblangley.haikalat.subsystems.windowing.input;

/** 一次输入事件之后稳定发布的键盘修饰状态。 */
public record KeyModifiers(boolean shift, boolean control, boolean alt, boolean superKey,
                           boolean capsLock, boolean numLock) {
    public static final KeyModifiers NONE = new KeyModifiers(false, false, false,
            false, false, false);
}
