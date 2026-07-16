package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureCube;

import java.util.Objects;

/** 拥有启动期生成的全部 IBL 纹理；渲染管线只借用，不负责关闭。 */
public final class PbrEnvironment implements AutoCloseable {
    private final TextureCube radiance;
    private final TextureCube irradiance;
    private final TextureCube prefilteredSpecular;
    private final Texture2D brdfLut;
    private float intensity;
    private float rotationRadians;
    private boolean closed;

    PbrEnvironment(TextureCube radiance, TextureCube irradiance,
                   TextureCube prefilteredSpecular, Texture2D brdfLut,
                   float intensity, float rotationRadians) {
        this.radiance = Objects.requireNonNull(radiance, "radiance");
        this.irradiance = Objects.requireNonNull(irradiance, "irradiance");
        this.prefilteredSpecular = Objects.requireNonNull(prefilteredSpecular, "prefilteredSpecular");
        this.brdfLut = Objects.requireNonNull(brdfLut, "brdfLut");
        intensity(intensity);
        rotationRadians(rotationRadians);
    }

    public TextureCube radiance() { ensureOpen(); return radiance; }
    public TextureCube irradiance() { ensureOpen(); return irradiance; }
    public TextureCube prefilteredSpecular() { ensureOpen(); return prefilteredSpecular; }
    public Texture2D brdfLut() { ensureOpen(); return brdfLut; }
    public float intensity() { ensureOpen(); return intensity; }
    public float rotationRadians() { ensureOpen(); return rotationRadians; }

    public void intensity(float value) {
        ensureOpen();
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException("environment intensity must be finite and non-negative");
        }
        intensity = value;
    }

    public void rotationRadians(float value) {
        ensureOpen();
        if (!Float.isFinite(value)) throw new IllegalArgumentException("environment rotation must be finite");
        rotationRadians = value;
    }

    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        failure = close(brdfLut, failure);
        failure = close(prefilteredSpecular, failure);
        failure = close(irradiance, failure);
        failure = close(radiance, failure);
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("PBR environment is closed");
    }

    private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
        try {
            resource.close();
        } catch (Exception error) {
            RuntimeException next = error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Failed to close PBR environment resource", error);
            if (failure == null) return next;
            failure.addSuppressed(next);
        }
        return failure;
    }
}
