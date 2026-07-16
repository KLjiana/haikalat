package com.kaleblangley.haikalat.subsystems.ui.render;

/** UI renderer 的轻量 shader 变体标识，不携带 OpenGL 对象。 */
public enum UiShaderVariant {
    /** 纯色四边形。 */
    SOLID,
    /** 普通 RGBA 纹理。 */
    TEXTURED,
    /** 单通道 glyph atlas。 */
    GLYPH,
    /** 诊断线框。 */
    DEBUG_OUTLINE
}
