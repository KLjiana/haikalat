package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.command.CommandBuffer;

/**
 * Borrowed irradiance environment used by the screen-space indirect-diffuse
 * ambient baseline.
 *
 * <p>Render3D adapts the active PBR environment to this contract; the colour
 * chain never imports Render3D or {@code PbrEnvironment}. The source binds the
 * irradiance cubemap on demand, inside the recording pass, so the pass owns no
 * environment resource and a generation swap simply replaces the adapter.</p>
 *
 * <p>The pass derives the receiver's ambient irradiance from this cube and
 * compares the bounded offscreen miss budget against it. The environment is
 * never added as a second ambient term: it only defines the baseline that the
 * screen-space gather must exceed, and the fallback used when no environment is
 * currently bound.</p>
 */
public interface SsgiEnvironmentSource {
    /**
     * Binds the borrowed irradiance environment cubemap into {@code unit}.
     *
     * @return true when a finite environment is bound for this draw
     */
    boolean bindIrradiance(CommandBuffer cmd, int unit);

    /** @return the environment rotation in radians, matching the PBR forward pass */
    float rotationRadians();

    /** @return the environment intensity multiplier, matching the PBR forward pass */
    float intensity();
}
