package com.kaleblangley.haikalat.subsystems.ui.vfx;

import com.kaleblangley.haikalat.subsystems.ui.UiId;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

import java.util.List;
import java.util.Objects;

/** Frozen screen-space effect state consumed by the UI renderer. */
public record UiEffectSnapshot(long instanceId, UiId target,
                               UiEffectDefinition definition,
                               UiScreenRect anchorBounds, float normalizedTime,
                               List<Particle> particles) {
    public UiEffectSnapshot {
        if (instanceId <= 0L) throw new IllegalArgumentException("instanceId must be positive");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(anchorBounds, "anchorBounds");
        if (!Float.isFinite(normalizedTime)
                || normalizedTime < 0.0f || normalizedTime > 1.0f) {
            throw new IllegalArgumentException("normalizedTime must be within [0, 1]");
        }
        particles = List.copyOf(Objects.requireNonNull(particles, "particles"));
        if (particles.size() > definition.maximumParticles()) {
            throw new IllegalArgumentException("snapshot exceeds effect capacity");
        }
    }

    public record Particle(float x, float y, float size, float rotationRadians,
                           UiColor color) {
        public Particle {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(size)
                    || size < 0.0f || !Float.isFinite(rotationRadians)) {
                throw new IllegalArgumentException("particle values must be finite");
            }
            Objects.requireNonNull(color, "color");
        }
    }
}
