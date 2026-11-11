package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded settings contract for the production particle owner.
 *
 * <p>Capacity, spawn volume, lifetime, gravity, speed, size and colour are
 * validated at construction instead of being clamped, because an invalid value
 * would otherwise surface as an unbounded GPU allocation or a non-finite
 * particle position long after the author error.</p>
 */
class GpuParticleSettingsTest {
    @Test
    void defaultsAreBoundedAndCapacityIsValidated() {
        GpuParticleSettings settings = GpuParticleSettings.defaults(64);

        assertEquals(64, settings.capacity());
        assertTrue(settings.lifetimeSeconds() > 0.0f);
        assertTrue(settings.minimumSpeed() <= settings.maximumSpeed());
        assertTrue(settings.particleSize() > 0.0f);

        assertThrows(IllegalArgumentException.class, () -> GpuParticleSettings.defaults(0));
        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleSettings.defaults(GpuParticleSettings.MAXIMUM_CAPACITY + 1));
    }

    @Test
    void maximumCapacityIsAccepted() {
        assertEquals(GpuParticleSettings.MAXIMUM_CAPACITY,
                GpuParticleSettings.defaults(GpuParticleSettings.MAXIMUM_CAPACITY).capacity());
    }

    @Test
    void invalidSpawnVolumeAndScalarsAreRejected() {
        GpuParticleSettings valid = GpuParticleSettings.defaults(8);

        assertThrows(NullPointerException.class, () -> new GpuParticleSettings(8, null,
                new Vector3f(1, 1, 1), 1.0f, 0.0f, 0.0f, 0.0f, 0.1f,
                new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), 1.0f, 0.0f, 0.0f, 0.0f,
                0.1f, new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(Float.NaN, 0, 0), new Vector3f(1, 1, 1), 1.0f, 0.0f, 0.0f,
                0.0f, 0.1f, new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 0.0f, 0.0f, 0.0f, 0.0f,
                0.1f, new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 1),
                GpuParticleSettings.MAXIMUM_LIFETIME_SECONDS + 1.0f, 0.0f, 0.0f, 0.0f,
                0.1f, new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 1.0f,
                GpuParticleSettings.MAXIMUM_GRAVITY + 1.0f, 0.0f, 0.0f, 0.1f,
                new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 1.0f, 0.0f, 2.0f, 1.0f,
                0.1f, new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, new Vector3f(1, 1, 1), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 1.0f, 0.0f, 0.0f, 0.0f,
                0.1f, new Vector3f(-1, 0, 0), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSettings(8,
                new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 1.0f, 0.0f, 0.0f, 0.0f,
                0.1f, new Vector3f(1, 1, 1), -1.0f));
        assertEquals(8, valid.capacity());
    }

    @Test
    void vectorsAreDefensivelyCopiedInBothDirections() {
        Vector3f minimum = new Vector3f(-1.0f, -2.0f, -3.0f);
        Vector3f maximum = new Vector3f(1.0f, 2.0f, 3.0f);
        Vector3f color = new Vector3f(0.2f, 0.4f, 0.6f);
        GpuParticleSettings settings = new GpuParticleSettings(16, minimum, maximum,
                1.0f, 0.5f, 0.1f, 0.2f, 0.05f, color, 0.7f);

        minimum.set(9.0f, 9.0f, 9.0f);
        maximum.set(9.0f, 9.0f, 9.0f);
        color.set(9.0f, 9.0f, 9.0f);

        assertEquals(new Vector3f(-1.0f, -2.0f, -3.0f), settings.spawnMinimum());
        assertEquals(new Vector3f(1.0f, 2.0f, 3.0f), settings.spawnMaximum());
        assertEquals(new Vector3f(0.2f, 0.4f, 0.6f), settings.color());

        Vector3f exported = settings.spawnMinimum();
        assertNotSame(settings.spawnMinimum(), exported);
        exported.set(5.0f, 5.0f, 5.0f);
        assertEquals(new Vector3f(-1.0f, -2.0f, -3.0f), settings.spawnMinimum());
    }

    @Test
    void boundedCopiesKeepTheOtherFieldsAndRevalidate() {
        GpuParticleSettings settings = GpuParticleSettings.defaults(32);
        GpuParticleSettings resized = settings.withCapacity(128);
        GpuParticleSettings longer = settings.withLifetimeSeconds(2.5f);

        assertEquals(128, resized.capacity());
        assertEquals(settings.lifetimeSeconds(), resized.lifetimeSeconds());
        assertEquals(settings.spawnMaximum(), resized.spawnMaximum());
        assertEquals(2.5f, longer.lifetimeSeconds());
        assertEquals(settings.capacity(), longer.capacity());

        assertThrows(IllegalArgumentException.class, () -> settings.withCapacity(0));
        assertThrows(IllegalArgumentException.class,
                () -> settings.withCapacity(GpuParticleSettings.MAXIMUM_CAPACITY + 1));
        assertThrows(IllegalArgumentException.class, () -> settings.withLifetimeSeconds(0.0f));
    }
}
