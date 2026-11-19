package com.kaleblangley.haikalat.subsystems.render3d.pipeline;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;

import java.util.Objects;

/**
 * Adapts the active PBR irradiance environment to the screen-space
 * indirect-diffuse ambient baseline.
 *
 * <p>The binder stays owned by the pipeline generation and is only borrowed for
 * the duration of a recorded pass; the adapter therefore never closes it and
 * always reads the live intensity/rotation overrides.</p>
 */
final class PbrSsgiEnvironment implements SsgiEnvironmentSource {
    private final PbrMaterialBinder binder;

    PbrSsgiEnvironment(PbrMaterialBinder binder) {
        this.binder = Objects.requireNonNull(binder, "binder");
    }

    @Override
    public boolean bindIrradiance(CommandBuffer cmd, int unit) {
        Objects.requireNonNull(cmd, "cmd");
        return binder.bindIrradiance(cmd, unit);
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
