package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.Objects;

/**
 * UI 图片稳定标识解析后的渲染区域。
 *
 * <p>texture/sampler 是跨线程快照可保存的稳定整数资源标识；资源所有权仍由注册表持有。</p>
 */
public record UiImageRegion(int textureId, int samplerId,
                            double intrinsicWidth, double intrinsicHeight,
                            UiUvRect uv) {
    public UiImageRegion {
        if (textureId < 0 || samplerId < 0) {
            throw new IllegalArgumentException("UI image texture and sampler ids must be non-negative");
        }
        if (!Double.isFinite(intrinsicWidth) || !Double.isFinite(intrinsicHeight)
                || intrinsicWidth <= 0.0 || intrinsicHeight <= 0.0) {
            throw new IllegalArgumentException("UI image intrinsic dimensions must be finite and positive");
        }
        Objects.requireNonNull(uv, "uv");
    }
}
