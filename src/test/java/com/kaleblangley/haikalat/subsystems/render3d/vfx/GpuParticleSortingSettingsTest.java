package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.EqualKeyPolicy;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortDirection;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings.SortKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded sorting configuration contract.
 *
 * <p>An enabled value must carry a positive sort bound; a disabled value must
 * not imply any scratch allocation. The owner derives its allocations and its
 * dispatch plan from this value, so an invalid bound is rejected at
 * construction instead of turning into an unbounded GPU allocation later.</p>
 */
class GpuParticleSortingSettingsTest {
    @Test
    void disabledIsTheExactIndirectPathAndCarriesNoScratchBound() {
        GpuParticleSortingSettings disabled = GpuParticleSortingSettings.disabled();

        assertFalse(disabled.enabled());
        assertEquals(SortKey.VIEW_DEPTH, disabled.key());
        assertEquals(SortDirection.FRONT_TO_BACK, disabled.direction());
        assertEquals(0, disabled.maximumSortedParticles());
        assertEquals(EqualKeyPolicy.STABLE, disabled.equalKeys());
        assertThrows(IllegalArgumentException.class,
                () -> new GpuParticleSortingSettings(false, SortKey.VIEW_DEPTH,
                        SortDirection.FRONT_TO_BACK, 8, EqualKeyPolicy.STABLE));
    }

    @Test
    void enabledBoundIsValidatedAtConstruction() {
        assertEquals(8, GpuParticleSortingSettings.enabled(SortKey.DISTANCE,
                SortDirection.BACK_TO_FRONT, 8, EqualKeyPolicy.STABLE).maximumSortedParticles());

        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleSortingSettings.enabled(SortKey.VIEW_DEPTH,
                        SortDirection.FRONT_TO_BACK, 0, EqualKeyPolicy.STABLE));
        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleSortingSettings.enabled(SortKey.VIEW_DEPTH,
                        SortDirection.FRONT_TO_BACK, -3, EqualKeyPolicy.STABLE));
        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleSortingSettings.enabled(SortKey.VIEW_DEPTH,
                        SortDirection.FRONT_TO_BACK,
                        GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES + 1,
                        EqualKeyPolicy.STABLE));
        assertEquals(GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES,
                GpuParticleSortingSettings.enabled(SortKey.VIEW_DEPTH,
                        SortDirection.FRONT_TO_BACK,
                        GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES,
                        EqualKeyPolicy.UNSTABLE).maximumSortedParticles());
    }

    @Test
    void nullEnumsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new GpuParticleSortingSettings(true, null,
                        SortDirection.FRONT_TO_BACK, 8, EqualKeyPolicy.STABLE));
        assertThrows(NullPointerException.class,
                () -> new GpuParticleSortingSettings(true, SortKey.VIEW_DEPTH,
                        null, 8, EqualKeyPolicy.STABLE));
        assertThrows(NullPointerException.class,
                () -> new GpuParticleSortingSettings(true, SortKey.VIEW_DEPTH,
                        SortDirection.FRONT_TO_BACK, 8, null));
    }

    @Test
    void boundedCopiesKeepTheOtherFieldsAndRevalidate() {
        GpuParticleSortingSettings settings = GpuParticleSortingSettings.enabled(
                SortKey.DISTANCE, SortDirection.BACK_TO_FRONT, 16, EqualKeyPolicy.UNSTABLE);

        GpuParticleSortingSettings bounded = settings.withMaximumSortedParticles(4);
        assertEquals(4, bounded.maximumSortedParticles());
        assertEquals(SortKey.DISTANCE, bounded.key());
        assertEquals(SortDirection.BACK_TO_FRONT, bounded.direction());
        assertEquals(EqualKeyPolicy.UNSTABLE, bounded.equalKeys());
        assertTrue(bounded.enabled());

        assertThrows(IllegalArgumentException.class,
                () -> settings.withMaximumSortedParticles(0));
        GpuParticleSortingSettings disabled = GpuParticleSortingSettings.disabled();
        assertThrows(IllegalArgumentException.class,
                () -> disabled.withMaximumSortedParticles(4),
                "re-enabling sorting requires the enabled factory, not a bound copy");
    }
}
