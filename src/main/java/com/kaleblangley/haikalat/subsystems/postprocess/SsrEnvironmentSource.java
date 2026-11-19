package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.core.command.CommandBuffer;

/**
 * Borrowed environment/IBL colour used by the screen-space reflection fallback.
 *
 * <p>Render3D adapts the active PBR reflection environment to this contract; the
 * colour chain never imports Render3D or {@code PbrEnvironment}.  The source
 * binds the prefiltered specular cubemap on demand, inside the recording pass,
 * so the pass owns no environment resource and a generation swap simply
 * replaces the adapter.</p>
 *
 * <p>The pass has no meaningful reflection baseline without this contract: it
 * replaces the environment specular term only where a screen-space hit exists,
 * so the environment is also the fallback for offscreen, occluded, backfacing,
 * rough and grazing pixels.</p>
 */
public interface SsrEnvironmentSource {
    /**
     * Binds the borrowed prefiltered specular environment cubemap into
     * {@code unit}.
     *
     * @return the highest valid mip level, or a negative value when no
     * environment is currently bound
     */
    float bindPrefilteredSpecular(CommandBuffer cmd, int unit);

    /** @return the environment rotation in radians, matching the PBR forward pass */
    float rotationRadians();

    /** @return the environment intensity multiplier, matching the PBR forward pass */
    float intensity();
}
