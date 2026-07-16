package com.kaleblangley.haikalat.subsystems.ui.render;

/** UI primitive 的混合语义。 */
public enum UiBlendMode {
    /** 预乘 alpha：ONE / ONE_MINUS_SRC_ALPHA。 */
    PREMULTIPLIED_ALPHA,
    /** 不透明覆盖。 */
    OPAQUE,
    /** 加法混合。 */
    ADDITIVE
}
