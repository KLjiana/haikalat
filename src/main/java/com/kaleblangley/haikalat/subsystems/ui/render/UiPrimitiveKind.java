package com.kaleblangley.haikalat.subsystems.ui.render;

/** UI display list 中的结构化指令种类。 */
public enum UiPrimitiveKind {
    /** 单色四边形。 */
    SOLID_QUAD,
    /** 图片或通用纹理四边形。 */
    TEXTURED_QUAD,
    /** 共用同一 atlas 页和状态的定位 glyph 序列。 */
    GLYPH_RUN,
    /** 压入逻辑裁剪；batcher 在逻辑空间与父裁剪求交。 */
    PUSH_CLIP,
    /** 弹出最近压入的逻辑裁剪。 */
    POP_CLIP,
    /** 诊断边框。 */
    DEBUG_OUTLINE,
    /** 用户 paint 或 layer 的显式顺序屏障。 */
    PAINT_BOUNDARY
}
