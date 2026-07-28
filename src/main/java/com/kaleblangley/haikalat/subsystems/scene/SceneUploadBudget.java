package com.kaleblangley.haikalat.subsystems.scene;

import java.time.Duration;
import java.util.Objects;

/** Soft GL-frame budget for staged scene asset uploads. */
public record SceneUploadBudget(int maxSteps, long maxNanos, long maxBytes) {
    public SceneUploadBudget(int maxSteps, long maxNanos) {
        this(maxSteps, maxNanos, Long.MAX_VALUE);
    }

    public SceneUploadBudget {
        if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps must be positive");
        if (maxNanos <= 0L) throw new IllegalArgumentException("maxNanos must be positive");
        if (maxBytes <= 0L) throw new IllegalArgumentException("maxBytes must be positive");
    }

    public static SceneUploadBudget of(int maxSteps, Duration maxTime) {
        Objects.requireNonNull(maxTime, "maxTime");
        return new SceneUploadBudget(maxSteps, maxTime.toNanos(), Long.MAX_VALUE);
    }

    public static SceneUploadBudget frameDefault() {
        return new SceneUploadBudget(8, Duration.ofMillis(2).toNanos(),
                16L * 1024L * 1024L);
    }
}
