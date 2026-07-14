package com.kaleblangley.haikalat.runtime;

/** 控制场景是否使用 HDR 渲染以及最终采用的色调映射算法。 */
public enum ToneMappingMode {
    /** 保持兼容的 RGBA8 LDR 渲染路径，不增加 tone-mapping pass。 */
    NONE,
    /** 在线性 RGBA16F 空间渲染，并使用 ACES fitted curve 输出到显示空间。 */
    ACES
}
