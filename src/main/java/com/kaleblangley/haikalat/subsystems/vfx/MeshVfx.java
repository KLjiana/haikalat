package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.joml.Vector4f;

import java.util.Objects;

/**
 * 有界、定时失效的任意低模 VFX 定义。
 *
 * <p>该类型只持有 CPU {@link MeshData}；上传与 GPU 生命周期由 render3d VFX adapter 独占。</p>
 */
public record MeshVfx(
        int maxInstances,
        float lifetimeSeconds,
        MeshData meshData,
        float startScale,
        float endScale,
        Vector4f startColor,
        Vector4f endColor
) {
    public MeshVfx {
        if (maxInstances <= 0) throw new IllegalArgumentException("maxInstances must be positive");
        ParticleEmitter.requireFinitePositive(lifetimeSeconds, "lifetimeSeconds");
        meshData = Objects.requireNonNull(meshData, "meshData");
        ParticleEmitter.requireFinitePositive(startScale, "startScale");
        ParticleEmitter.requireFiniteNonNegative(endScale, "endScale");
        startColor = new Vector4f(Objects.requireNonNull(startColor, "startColor"));
        endColor = new Vector4f(Objects.requireNonNull(endColor, "endColor"));
        ParticleEmitter.requireColor(startColor, "startColor");
        ParticleEmitter.requireColor(endColor, "endColor");
        if (!meshData.hasPositionAttribute(0, 3)) {
            throw new IllegalArgumentException(
                    "MeshVfx POSITION must provide at least three components at location 0");
        }
    }

    @Override
    public Vector4f startColor() {
        return new Vector4f(startColor);
    }

    @Override
    public Vector4f endColor() {
        return new Vector4f(endColor);
    }

    boolean hasTextureCoordinates() {
        return meshData.hasTexCoord0Attribute(1, 2);
    }
}
