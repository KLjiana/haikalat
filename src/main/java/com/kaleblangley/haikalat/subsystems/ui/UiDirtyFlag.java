package com.kaleblangley.haikalat.subsystems.ui;

/** retained tree 使用的细粒度失效类型。 */
public enum UiDirtyFlag {
    STYLE,
    MEASURE,
    LAYOUT,
    PAINT,
    HIT_TEST,
    COMPOSITOR,
    SEMANTICS
}
