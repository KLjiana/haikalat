package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Structured inputs for one TAA resolve.  Texture ids are borrowed for the
 * duration of the recorded frame; zero means the channel is unavailable and
 * the resolve must take its documented fallback.
 */
public record TaaResolveInputs(
        int currentColorTexture,
        int historyColorTexture,
        int historyDepthTexture,
        int sceneDepthTexture,
        int velocityTexture,
        int previousSurfaceDepthTexture,
        int validityTexture,
        int reactiveTexture,
        boolean historyValid,
        int width,
        int height,
        float currentJitterUvX,
        float currentJitterUvY,
        float previousJitterUvX,
        float previousJitterUvY,
        Matrix4f inverseProjection,
        Matrix4f previousStableViewProjection,
        Matrix4f inverseView,
        TaaSettings settings) {
    public TaaResolveInputs {
        if (currentColorTexture <= 0) {
            throw new IllegalArgumentException("TAA resolve requires a current color texture");
        }
        if (sceneDepthTexture <= 0) {
            throw new IllegalArgumentException("TAA resolve requires the shared scene depth");
        }
        if (velocityTexture <= 0 || previousSurfaceDepthTexture <= 0 || validityTexture <= 0) {
            throw new IllegalArgumentException(
                    "TAA resolve requires velocity, previous surface depth and validity");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("TAA resolve extent must be positive");
        }
        inverseProjection = new Matrix4f(Objects.requireNonNull(inverseProjection, "inverseProjection"));
        previousStableViewProjection = new Matrix4f(
                Objects.requireNonNull(previousStableViewProjection, "previousStableViewProjection"));
        inverseView = new Matrix4f(Objects.requireNonNull(inverseView, "inverseView"));
        settings = Objects.requireNonNull(settings, "settings");
    }

    public boolean hasHistory() {
        return historyValid && historyColorTexture > 0 && historyDepthTexture > 0;
    }

    public boolean reactiveAvailable() {
        return reactiveTexture > 0;
    }
}
