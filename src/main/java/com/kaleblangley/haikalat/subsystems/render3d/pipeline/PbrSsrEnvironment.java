package com.kaleblangley.haikalat.subsystems.render3d.pipeline;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;

import java.util.Objects;

/**
 * Adapts the active PBR reflection environment to the screen-space reflection
 * fallback contract.
 *
 * <p>The binder stays owned by the pipeline generation and is only borrowed
 * for the duration of a recorded pass; the adapter therefore never closes it
 * and always reads the live intensity/rotation overrides.</p>
 */
final class PbrSsrEnvironment implements SsrEnvironmentSource {
    private final PbrMaterialBinder binder;

    PbrSsrEnvironment(PbrMaterialBinder binder) {
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    @Override
    public float bindPrefilteredSpecular(CommandBuffer cmd, int unit) {
        Objects.requireNonNull(cmd, "cmd");
        return binder.bindPrefilteredSpecular(cmd, unit);
    }

    @Override
    public float rotationRadians() {
        return binder.environmentRotationRadians();
    }

    @Override
    public float intensity() {
        return binder.environmentIntensity();
    }
}
