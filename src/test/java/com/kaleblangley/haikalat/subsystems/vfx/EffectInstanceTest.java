package com.kaleblangley.haikalat.subsystems.vfx;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectInstanceTest {
    @Test
    void fixedSeedAndInputsProduceIdenticalSnapshots() {
        try (EffectAsset firstAsset = fullAsset(); EffectAsset secondAsset = fullAsset();
             EffectInstance first = firstAsset.instantiate(42L);
             EffectInstance second = secondAsset.instantiate(42L)) {
            for (int frame = 0; frame < 12; frame++) {
                Vector3f origin = new Vector3f(frame * 0.08f, (float) Math.sin(frame * 0.2f), 0.0f);
                first.update(1.0f / 30.0f, origin);
                second.update(1.0f / 30.0f, origin);
            }
            first.spawnDecal(new Vector3f(0.0f, -0.9f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f),
                    new Vector2f(0.8f, 0.4f), 0.25f);
            second.spawnDecal(new Vector3f(0.0f, -0.9f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f),
                    new Vector2f(0.8f, 0.4f), 0.25f);

            assertEquals(first.snapshot(new Vector3f(0.0f, 0.0f, 5.0f)),
                    second.snapshot(new Vector3f(0.0f, 0.0f, 5.0f)));
            assertEquals(first.statistics(), second.statistics());
        }
    }

    @Test
    void particlesAreBoundedExpireAndStopSpawning() {
        ParticleEmitter particles = new ParticleEmitter(2, 10.0f, 0.5f,
                new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, 1.0f, 1.0f,
                new Vector3f(), 0.0f, 0.2f, 0.0f,
                new Vector4f(1.0f), new Vector4f(1.0f, 1.0f, 1.0f, 0.0f));
        try (EffectAsset asset = EffectAsset.builder("bounded").particles(particles).build();
             EffectInstance instance = asset.instantiate(7L)) {
            instance.update(1.0f, new Vector3f());
            assertEquals(2, instance.statistics().activeParticles());
            assertEquals(8, instance.statistics().droppedParticles());

            instance.emitting(false).update(0.5f, new Vector3f());
            assertEquals(0, instance.statistics().activeParticles());
            assertFalse(instance.isAlive());
        }
    }

    @Test
    void ribbonAndDecalsStayBoundedAndTransparentOrderIsFarToNear() {
        try (EffectAsset asset = fullAsset(); EffectInstance instance = asset.instantiate(1L)) {
            for (int index = 0; index < 12; index++) {
                instance.update(0.05f, new Vector3f(index * 0.2f, 0.0f, index * -0.1f));
            }
            for (int index = 0; index < 6; index++) {
                instance.spawnDecal(new Vector3f(0.0f, -1.0f, -index),
                        new Vector3f(0.0f, 0.0f, 1.0f), new Vector2f(0.5f), 0.0f);
            }
            EffectInstance.Statistics stats = instance.statistics();
            assertTrue(stats.ribbonSegments() <= 7);
            assertEquals(4, stats.activeDecals());
            assertEquals(2, stats.evictedDecals());

            EffectSnapshot snapshot = instance.snapshot(new Vector3f());
            float previous = Float.POSITIVE_INFINITY;
            long previousSequenceAtSameDistance = -1L;
            for (EffectSnapshot.Primitive primitive : snapshot.transparentDrawOrder()) {
                assertTrue(primitive.distanceSquared() <= previous + 1.0e-6f);
                if (Math.abs(primitive.distanceSquared() - previous) < 1.0e-6f) {
                    assertTrue(primitive.sequence() >= previousSequenceAtSameDistance);
                }
                previous = primitive.distanceSquared();
                previousSequenceAtSameDistance = primitive.sequence();
            }
        }
    }

    @Test
    void assetCannotCloseBeforeInstancesAndRejectsUseAfterClose() {
        EffectAsset asset = fullAsset();
        EffectInstance instance = asset.instantiate(9L);
        assertEquals(1, asset.activeInstances());
        assertThrows(IllegalStateException.class, asset::close);
        instance.close();
        assertEquals(0, asset.activeInstances());
        asset.close();
        assertTrue(asset.isClosed());
        assertThrows(IllegalStateException.class, () -> asset.instantiate(10L));
        assertThrows(IllegalStateException.class,
                () -> instance.update(0.1f, new Vector3f()));
    }

    @Test
    void invalidDefinitionsAndDecalInputsFailEarly() {
        assertThrows(IllegalArgumentException.class, () -> new ParticleEmitter(0, 1.0f, 1.0f,
                new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, 0.0f, 1.0f,
                new Vector3f(), 0.0f, 1.0f, 1.0f,
                new Vector4f(1.0f), new Vector4f(1.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> EffectAsset.builder("empty").build());
        try (EffectAsset asset = fullAsset(); EffectInstance instance = asset.instantiate(3L)) {
            assertThrows(IllegalArgumentException.class, () -> instance.spawnDecal(
                    new Vector3f(), new Vector3f(), new Vector2f(1.0f), 0.0f));
        }
    }

    private static EffectAsset fullAsset() {
        return EffectAsset.builder("test-effect")
                .particles(new ParticleEmitter(32, 30.0f, 1.5f,
                        new Vector3f(0.0f, 1.0f, 0.0f), 0.35f, 0.8f, 1.8f,
                        new Vector3f(0.0f, -0.5f, 0.0f), 0.1f, 0.18f, 0.02f,
                        new Vector4f(1.0f, 0.7f, 0.2f, 0.9f),
                        new Vector4f(0.8f, 0.1f, 0.05f, 0.0f)))
                .ribbon(new RibbonEmitter(8, 1.0f, 0.05f, 0.14f, 0.01f,
                        new Vector4f(0.2f, 0.8f, 1.0f, 0.75f),
                        new Vector4f(0.1f, 0.2f, 0.8f, 0.0f)))
                .decals(new Decal(4, 2.0f,
                        new Vector4f(0.7f, 0.3f, 1.0f, 0.65f),
                        new Vector4f(0.3f, 0.1f, 0.5f, 0.0f)))
                .build();
    }
}
