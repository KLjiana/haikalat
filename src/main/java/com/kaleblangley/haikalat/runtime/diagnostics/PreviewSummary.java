package com.kaleblangley.haikalat.runtime.diagnostics;

import java.util.Objects;

/**
 * 可冻结、可导出的预览状态值摘要；不包含像素、native id、resolver 或 GPU owner。
 */
public record PreviewSummary(String selectedLogicalKey, String mode, String channel,
                             float exposureEv, float rangeMin, float rangeMax,
                             boolean falseColor, String depthInterpretation,
                             float nearPlane, float farPlane, String cubeFace,
                             int mipLevel, String filtering, int updateInterval,
                             boolean paused, boolean frozenMetadata,
                             String status, int outputWidth, int outputHeight,
                             long lastSuccessfulFrameSequence, String errorCode,
                             String errorMessage, String path, int frameDraws,
                             int frameBlits, long estimatedBytes) {
    public PreviewSummary {
        selectedLogicalKey = Objects.requireNonNullElse(selectedLogicalKey, "");
        mode = text(mode, "mode");
        channel = text(channel, "channel");
        depthInterpretation = text(depthInterpretation, "depthInterpretation");
        cubeFace = text(cubeFace, "cubeFace");
        filtering = text(filtering, "filtering");
        status = text(status, "status");
        errorCode = Objects.requireNonNullElse(errorCode, "");
        errorMessage = Objects.requireNonNullElse(errorMessage, "");
        path = Objects.requireNonNullElse(path, "");
        float[] finite = {exposureEv, rangeMin, rangeMax, nearPlane, farPlane};
        for (float value : finite) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("preview summary contains non-finite value");
            }
        }
        if (rangeMin >= rangeMax || nearPlane <= 0.0f || farPlane <= nearPlane) {
            throw new IllegalArgumentException("preview summary ranges are invalid");
        }
        if (mipLevel < 0 || updateInterval <= 0 || outputWidth < 0 || outputHeight < 0
                || lastSuccessfulFrameSequence < -1L || frameDraws < 0 || frameBlits < 0
                || estimatedBytes < 0L) {
            throw new IllegalArgumentException("preview summary counters are invalid");
        }
    }

    public static PreviewSummary idle() {
        return new PreviewSummary("", "AUTO", "RGBA", 0.0f, 0.0f, 1.0f,
                false, "RAW", 0.1f, 100.0f, "POSITIVE_X", 0,
                "LINEAR", 1, false, false, "IDLE", 0, 0,
                -1L, "", "", "", 0, 0, 0L);
    }

    private static String text(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
