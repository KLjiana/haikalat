package com.kaleblangley.haikalat.demo.pbr;

import java.util.List;
import java.util.Objects;

/** Scene/camera evidence inputs intentionally kept outside color grading profiles. */
public record SceneCaptureSpec(String sceneRevision, String cameraPath, int seed,
                               List<Integer> captureFrames) {
    public SceneCaptureSpec {
        if (sceneRevision == null || sceneRevision.isBlank()) {
            throw new IllegalArgumentException("sceneRevision must not be blank");
        }
        if (cameraPath == null || cameraPath.isBlank()) {
            throw new IllegalArgumentException("cameraPath must not be blank");
        }
        captureFrames = List.copyOf(Objects.requireNonNull(captureFrames, "captureFrames"));
        if (captureFrames.isEmpty() || captureFrames.stream().anyMatch(frame -> frame == null || frame < 0)) {
            throw new IllegalArgumentException("captureFrames must contain non-negative frames");
        }
    }
}
