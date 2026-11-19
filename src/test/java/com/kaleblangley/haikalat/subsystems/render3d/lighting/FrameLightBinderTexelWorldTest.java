package com.kaleblangley.haikalat.subsystems.render3d.lighting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks the PCSS world-texel contract.  The legacy single-map plan stores a
 * culling placeholder (1.0) rather than its real footprint, so the binder must
 * derive {@code 2 * sceneRadius / resolution} there; shipping the placeholder
 * would collapse the penumbra by roughly two orders of magnitude.
 */
class FrameLightBinderTexelWorldTest {
    @Test
    void legacySingleMapDerivesItsSymmetricOrthographicFootprint() {
        float legacy = 2.0f * 20.0f / 256.0f;
        assertArrayEquals(new float[]{legacy, legacy, legacy, legacy},
                FrameLightBinder.cascadeTexelWorldSizes(legacy, false,
                        new float[]{1.0f}),
                0.0f, "the legacy plan placeholder must not reach the shader");
    }

    @Test
    void cascadedPlanValuesAreUsedAndTheLastOneRepeatsForUnusedSlots() {
        assertArrayEquals(new float[]{0.03f, 0.05f, 0.05f, 0.05f},
                FrameLightBinder.cascadeTexelWorldSizes(99.0f, true,
                        new float[]{0.03f, 0.05f}),
                0.0f);
        assertArrayEquals(new float[]{7.0f, 7.0f, 7.0f, 7.0f},
                FrameLightBinder.cascadeTexelWorldSizes(7.0f, true, new float[0]),
                0.0f, "an empty plan falls back to the legacy footprint");
    }

    @Test
    void rejectsInvalidLegacyFootprints() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameLightBinder.cascadeTexelWorldSizes(0.0f, true, new float[]{0.1f}));
        assertThrows(IllegalArgumentException.class,
                () -> FrameLightBinder.cascadeTexelWorldSizes(Float.NaN, true,
                        new float[]{0.1f}));
        assertThrows(IllegalArgumentException.class,
                () -> FrameLightBinder.cascadeTexelWorldSizes(1.0f, true, null));
    }
}
