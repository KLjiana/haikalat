package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.TextureCube;

import java.util.Objects;

/**
 * Owned GPU resource of one active local reflection probe.
 *
 * <p>The probe borrows nothing: the {@link PbrEnvironment} was created for it
 * by {@link PbrEnvironmentLoader} (or the equivalent preprocessing path) and
 * is closed exactly once by {@link #close()}. The environment's prefiltered
 * specular cubemap is the only texture the PBR shader samples; the irradiance
 * cube and BRDF LUT are kept for a future diffuse/local-BRDF extension and to
 * reuse the existing preprocessing result.</p>
 */
final class ReflectionProbe implements AutoCloseable {
    private final ReflectionProbeSettings settings;
    private final PbrEnvironment environment;
    private boolean closed;

    ReflectionProbe(ReflectionProbeSettings settings, PbrEnvironment environment) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.environment = Objects.requireNonNull(environment, "environment");
        if (!settings.active()) {
            throw new IllegalArgumentException("inactive reflection probes own no GPU resources");
        }
    }

    ReflectionProbeSettings settings() {
        return settings;
    }

    private PbrEnvironment environment() {
        ensureOpen();
        return environment;
    }

    TextureCube prefilteredSpecular() {
        return environment().prefilteredSpecular();
    }

    /** Highest valid prefiltered mip; the shader scales roughness by this value. */
    float maximumPrefilteredLod() {
        return prefilteredSpecular().mipLevels() - 1.0f;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        environment.close();
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Reflection probe '" + settings.name() + "' is closed");
    }
}
