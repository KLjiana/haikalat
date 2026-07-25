package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector4f;

import java.util.Objects;

/** 有界、定时失效的平面 Decal 定义；投影与绘制由渲染适配层负责。 */
public record Decal(int maxDecals, float lifetimeSeconds,
                    Vector4f startColor, Vector4f endColor) {
    public Decal {
        if (maxDecals <= 0) throw new IllegalArgumentException("maxDecals must be positive");
        ParticleEmitter.requireFinitePositive(lifetimeSeconds, "lifetimeSeconds");
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
