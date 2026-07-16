package com.kaleblangley.haikalat.subsystems.ui.layout;

/** intrinsic measure 的逻辑像素结果。 */
public record MeasureResult(float width, float height) {
    public MeasureResult {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width < 0.0f || height < 0.0f) {
            throw new IllegalArgumentException("measure result must be finite and non-negative");
        }
    }
}
