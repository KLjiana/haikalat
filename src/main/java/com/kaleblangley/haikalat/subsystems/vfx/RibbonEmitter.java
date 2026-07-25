package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector4f;

import java.util.Objects;

/** 随效果实例移动采样控制点的 CPU Ribbon 定义。 */
public record RibbonEmitter(
        int maxPoints,
        float pointLifetimeSeconds,
        float minimumPointDistance,
        float startWidth,
        float endWidth,
        Vector4f startColor,
        Vector4f endColor
) {
    public RibbonEmitter {
        if (maxPoints < 2) throw new IllegalArgumentException("maxPoints must be at least 2");
        ParticleEmitter.requireFinitePositive(pointLifetimeSeconds, "pointLifetimeSeconds");
        ParticleEmitter.requireFiniteNonNegative(minimumPointDistance, "minimumPointDistance");
        ParticleEmitter.requireFinitePositive(startWidth, "startWidth");
        ParticleEmitter.requireFiniteNonNegative(endWidth, "endWidth");
        startColor = new Vector4f(Objects.requireNonNull(startColor, "startColor"));
        endColor = new Vector4f(Objects.requireNonNull(endColor, "endColor"));
        ParticleEmitter.requireColor(startColor, "startColor");
        ParticleEmitter.requireColor(endColor, "endColor");
    }

    @Override
    public Vector4f startColor() {
        return new Vector4f(startColor);
    }

    @Override
    public Vector4f endColor() {
        return new Vector4f(endColor);
    }
}
