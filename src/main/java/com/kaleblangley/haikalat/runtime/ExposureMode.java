package com.kaleblangley.haikalat.runtime;

/** HDR tone mapping 使用的曝光来源。 */
public enum ExposureMode {
    /** 使用 {@link RenderSettings#exposure()} 提供的固定曝光。 */
    MANUAL,
    /** 在 GPU 上计算对数平均亮度并进行跨帧适应。 */
    AUTO
}
