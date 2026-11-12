package com.kaleblangley.haikalat.subsystems.render3d.vct;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounds, defaults and derivation contracts of the VCT settings record. */
class VctSettingsTest {
    @Test
    void disabledDefaultsAreInertAndDeriveBoundedVolumeValues() {
        VctSettings disabled = VctSettings.disabled();
        assertFalse(disabled.enabled());
        assertEquals(64, disabled.resolution());
        assertEquals(7, disabled.mipLevels());
        assertEquals(0.5f, disabled.voxelSize(), 0.0f);
        assertEquals(-16.0f, disabled.boundsMinimum().x, 0.0f);
        assertEquals(-16.0f, disabled.boundsMinimum().y, 0.0f);
        assertEquals(-16.0f, disabled.boundsMinimum().z, 0.0f);
        assertTrue(disabled.opacityThreshold() >= 0.0f && disabled.opacityThreshold() <= 0.95f);
        assertTrue(disabled.maxVoxelsPerUpdate() > 0L);
    }

    @Test
    void enabledFactoriesUseTheDocumentedDefaultsAndExplicitCube() {
        VctSettings base = VctSettings.enabled(64, 8.0f);
        assertTrue(base.enabled());
        assertEquals(64, base.resolution());
        assertEquals(8.0f, base.halfExtent(), 0.0f);
        assertEquals(0.25f, base.voxelSize(), 0.0f);
        assertEquals(8, base.diffuseCones());
        assertEquals(4, base.specularCones());

        VctSettings offset = VctSettings.enabled(128, new Vector3f(4.0f, -8.0f, 2.0f), 32.0f);
        assertEquals(4.0f, offset.center().x, 0.0f);
        assertEquals(-8.0f, offset.center().y, 0.0f);
        assertEquals(2.0f, offset.center().z, 0.0f);
        assertEquals(0.5f, offset.voxelSize(), 0.0f);
        assertEquals(-28.0f, offset.boundsMinimum().x, 0.0f);
        assertEquals(-40.0f, offset.boundsMinimum().y, 0.0f);
        assertEquals(-30.0f, offset.boundsMinimum().z, 0.0f);
        assertEquals(8, offset.mipLevels());
    }

    @Test
    void rejectsOutOfRangeResolutionBoundsAndTraceParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(48, 16.0f));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, new Vector3f(Float.POSITIVE_INFINITY, 0.0f, 0.0f), 16.0f));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 16.0f).withConeCounts(0, 4));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 16.0f).withConeCounts(8, 17));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 16.0f).withThinWallDilation(3));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 16.0f).withTrace(4, 0.2f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 16.0f).withTrace(32, 1.2f, 0.5f));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 16.0f).withUpdateBudget(0, 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> VctSettings.enabled(64, 16.0f).withUpdateBudget(2, 0L));
    }

    @Test
    void memoryBudgetMustHoldTheRequestedMipChain() {
        // 64³ chain: 262144+32768+4096+512+64+8+1 = 299593 texels * 8 = 2396744 bytes.
        assertEquals(2_396_744L, VctVoxelMath.estimatedVolumeBytes(64));
        assertEquals(19_173_960L, VctVoxelMath.estimatedVolumeBytes(128));
        // Exact 64³ chain plus fixed buffers is under the default budget but an
        // explicit 1 MiB budget cannot hold it.
        assertThrows(IllegalArgumentException.class, () -> new VctSettings(true, 64,
                new Vector3f(), 16.0f, 1.0f, 1.0f, 8, 4, 0.55f, 1.0f, 0.25f, 0.75f,
                1, 8, 4_000_000L, 1024L * 1024L, 48));
    }

    @Test
    void accessorsAndConfigurationCopiesDoNotExposeMutableState() {
        VctSettings base = VctSettings.enabled(64, new Vector3f(1.0f, 2.0f, 3.0f), 16.0f);
        Vector3f center = base.center();
        center.x = 100.0f;
        assertEquals(1.0f, base.center().x, 0.0f);

        VctSettings resized = base.withResolution(128);
        assertEquals(128, resized.resolution());
        assertEquals(base.center(), resized.center());
        assertNotEquals(base, resized);

        VctSettings moved = base.withBounds(new Vector3f(-2.0f, 0.0f, 4.0f), 20.0f);
        assertEquals(-2.0f, moved.center().x, 0.0f);
        assertEquals(20.0f, moved.halfExtent(), 0.0f);

        VctSettings cones = base.withConeCounts(16, 8);
        assertEquals(16, cones.diffuseCones());
        assertEquals(8, cones.specularCones());

        VctSettings budgeted = base.withUpdateBudget(2, 500_000L);
        assertEquals(2, budgeted.maxDirtyRegions());
        assertEquals(500_000L, budgeted.maxVoxelsPerUpdate());

        assertEquals(base, base.withResolution(64));
        assertEquals(base.hashCode(), base.withResolution(64).hashCode());
    }
}
