package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.Objects;

/**
 * Logical scene-color contract for backdrop effects. It deliberately exposes no native
 * texture or framebuffer handle.
 */
public record UiBackdropSource(String logicalTextureName, Availability availability) {
    public UiBackdropSource {
        logicalTextureName = Objects.requireNonNullElse(logicalTextureName, "");
        Objects.requireNonNull(availability, "availability");
        if (availability == Availability.AVAILABLE && logicalTextureName.isBlank()) {
            throw new IllegalArgumentException("an available backdrop needs a logical texture name");
        }
    }

    public static UiBackdropSource available(String logicalTextureName) {
        return new UiBackdropSource(logicalTextureName, Availability.AVAILABLE);
    }

    public static UiBackdropSource unavailable() {
        return new UiBackdropSource("", Availability.UNAVAILABLE);
    }

    public boolean isAvailable() { return availability == Availability.AVAILABLE; }

    public enum Availability { AVAILABLE, UNAVAILABLE }
}
