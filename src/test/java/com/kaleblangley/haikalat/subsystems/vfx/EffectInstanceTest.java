package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.curve.ColorGradient;
import com.kaleblangley.haikalat.core.curve.FloatTrack;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertThrows(IllegalArgumentException.class, () -> EffectAsset.builder("unused")
                .decals(new Decal(1, 1.0f, new Vector4f(1.0f), new Vector4f(1.0f)))
                .particleSizeOverLife(linearTrack(1.0f, 2.0f)).build());
        try (EffectAsset asset = fullAsset(); EffectInstance instance = asset.instantiate(3L)) {
            assertThrows(IllegalArgumentException.class, () -> instance.spawnDecal(
                    new Vector3f(), new Vector3f(), new Vector2f(1.0f), 0.0f));
        }
    }

    @Test
    void configuredTracksSampleParticleRibbonAndDecalProperties() {
        ParticleEmitter particle = new ParticleEmitter(4, 1.0f, 2.0f,
                new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, 0.0f, 0.0f,
                new Vector3f(), 0.0f, 1.0f, 1.0f,
                new Vector4f(1.0f), new Vector4f(1.0f));
        try (EffectAsset asset = EffectAsset.builder("curved")
                .particles(particle)
                .ribbon(new RibbonEmitter(4, 2.0f, 0.01f, 1.0f, 1.0f,
                        new Vector4f(1.0f), new Vector4f(1.0f)))
                .decals(new Decal(2, 2.0f, new Vector4f(1.0f), new Vector4f(1.0f)))
                .particleSizeOverLife(linearTrack(1.0f, 3.0f))
                .particleColorOverLife(testGradient())
                .particleRotationOverLife(linearTrack(0.0f, 2.0f))
                .ribbonWidthOverLife(linearTrack(0.5f, 1.5f))
                .ribbonColorOverLife(testGradient())
                .decalScaleOverLife(linearTrack(1.0f, 2.0f))
                .decalColorOverLife(testGradient())
                .build(); EffectInstance instance = asset.instantiate(5L)) {
            instance.update(1.0f, new Vector3f());
            instance.spawnDecal(new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                    new Vector2f(2.0f, 4.0f), 0.0f);
            instance.update(0.5f, new Vector3f(1.0f, 0.0f, 0.0f));
            EffectSnapshot snapshot = instance.snapshot(new Vector3f(0.0f, 0.0f, 5.0f));

            EffectSnapshot.ParticleSprite sprite = snapshot.particles().get(0);
            assertEquals(1.5f, sprite.size(), 1.0e-5f);
            assertEquals(new Vector4f(0.75f, 0.25f, 0.0f, 0.75f), sprite.color());
            assertTrue(sprite.rotationRadians() >= 0.5f);
            EffectSnapshot.RibbonSegment ribbon = snapshot.ribbonSegments().get(0);
            assertEquals(0.75f, ribbon.startWidth(), 1.0e-5f);
            assertEquals(0.5f, ribbon.endWidth(), 1.0e-5f);
            assertEquals(new Vector4f(0.875f, 0.125f, 0.0f, 0.875f), ribbon.color());
            EffectSnapshot.DecalInstance decal = snapshot.decals().get(0);
            assertEquals(new Vector2f(2.5f, 5.0f), decal.size());
            assertEquals(new Vector4f(0.75f, 0.25f, 0.0f, 0.75f), decal.color());
            assertTrue(instance.curveSamples() > 0L);
        }
    }

    @Test
    void legacyAssetsKeepLinearValuesAndInvalidCurveOutputsFailExplicitly() {
        ParticleEmitter particle = new ParticleEmitter(2, 1.0f, 2.0f,
                new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, 0.0f, 0.0f,
                new Vector3f(), 0.0f, 2.0f, 1.0f,
                new Vector4f(1.0f, 0.0f, 0.0f, 1.0f),
                new Vector4f(0.0f, 1.0f, 0.0f, 0.0f));
        try (EffectAsset legacy = EffectAsset.builder("legacy").particles(particle).build();
             EffectInstance instance = legacy.instantiate(2L)) {
            instance.update(1.0f, new Vector3f()).update(0.5f, new Vector3f());
            EffectSnapshot.ParticleSprite sprite = instance.snapshot(new Vector3f()).particles().get(0);
            assertEquals(1.75f, sprite.size(), 1.0e-5f);
            assertEquals(new Vector4f(0.75f, 0.25f, 0.0f, 0.75f), sprite.color());
            assertEquals(0L, instance.curveSamples());
        }

        FloatTrack invalid = new FloatTrack(
                new FloatTrack.Key(0.0f, 1.0f, 0.0f, -8.0f,
                        FloatTrack.Interpolation.CUBIC_HERMITE),
                new FloatTrack.Key(1.0f, 1.0f, 8.0f, 0.0f,
                        FloatTrack.Interpolation.LINEAR));
        try (EffectAsset asset = EffectAsset.builder("invalid")
                .particles(particle).particleSizeOverLife(invalid).build();
             EffectInstance instance = asset.instantiate(2L)) {
            instance.update(1.0f, new Vector3f()).emitting(false)
                    .update(2.0f, new Vector3f());
            assertTrue(instance.snapshot(new Vector3f()).particles().isEmpty(),
                    "expired primitives must not sample an invalid age");
            instance.emitting(true).update(1.0f, new Vector3f())
                    .update(0.5f, new Vector3f());
            assertThrows(IllegalStateException.class, () -> instance.snapshot(new Vector3f()));
        }
    }

    @Test
    void beamReusesRibbonAndMeshVfxIsBoundedCurvedAndExpires() {
        RibbonEmitter beam = new RibbonEmitter(4, 1.0f, 0.0f,
                0.3f, 0.1f, new Vector4f(1.0f), new Vector4f(1.0f),
                RibbonMode.BEAM);
        MeshVfx mesh = new MeshVfx(2, 1.0f,
                BuiltinMeshData.texturedQuad("mesh-vfx-test"),
                1.0f, 0.0f, new Vector4f(1.0f, 0.5f, 0.1f, 1.0f),
                new Vector4f(0.2f, 0.1f, 1.0f, 0.0f));
        try (EffectAsset asset = EffectAsset.builder("beam-and-mesh")
                .ribbon(beam)
                .meshes(mesh)
                .meshScaleOverLife(linearTrack(1.0f, 2.0f))
                .meshColorOverLife(testGradient())
                .meshRotationOverLife(linearTrack(0.0f, 1.0f))
                .build();
             EffectInstance instance = asset.instantiate(11L)) {
            instance.beam(List.of(new Vector3f(-1.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 0.2f, 0.0f),
                    new Vector3f(1.0f, 0.0f, 0.0f)));
            instance.spawnMesh(new Matrix4f().translation(0.0f, 0.0f, -1.0f))
                    .spawnMesh(new Matrix4f().translation(1.0f, 0.0f, -2.0f))
                    .spawnMesh(new Matrix4f().translation(2.0f, 0.0f, -3.0f))
                    .update(0.5f, new Vector3f(99.0f));

            EffectSnapshot snapshot = instance.snapshot(new Vector3f());
            assertEquals(2, snapshot.ribbonSegments().size());
            assertEquals(2, snapshot.meshes().size());
            assertEquals(2, instance.statistics().activeMeshes());
            assertEquals(1, instance.statistics().evictedMeshes());
            Vector3f meshScale = snapshot.meshes().get(0).model().getScale(new Vector3f());
            assertEquals(1.5f, meshScale.x, 1.0e-5f);
            assertEquals(1.5f, meshScale.y, 1.0e-5f);
            assertEquals(1.5f, meshScale.z, 1.0e-5f);
            assertEquals(EffectSnapshot.Kind.MESH, snapshot.meshes().get(0).kind());

            instance.emitting(false).update(0.5f, new Vector3f());
            assertTrue(instance.snapshot(new Vector3f()).meshes().isEmpty());
        }
    }

    @Test
    void particleFlipbookStartAndTimeAreDeterministic() {
        ParticleEmitter particle = new ParticleEmitter(4, 4.0f, 2.0f,
                new Vector3f(0.0f, 1.0f, 0.0f), 0.0f, 0.0f, 0.0f,
                new Vector3f(), 0.0f, 1.0f, 1.0f,
                new Vector4f(1.0f), new Vector4f(1.0f));
        VfxMaterial material = VfxMaterial.builder("flipbook")
                .flipbook(new FlipbookConfig(2, 2, 4, 8.0f, true, true))
                .build();
        try (EffectAsset firstAsset = EffectAsset.builder("first")
                .particles(particle).particleMaterial(material).build();
             EffectAsset secondAsset = EffectAsset.builder("second")
                     .particles(particle).particleMaterial(material).build();
             EffectInstance first = firstAsset.instantiate(77L);
             EffectInstance second = secondAsset.instantiate(77L)) {
            first.update(0.25f, new Vector3f()).update(0.125f, new Vector3f());
            second.update(0.25f, new Vector3f()).update(0.125f, new Vector3f());
            EffectSnapshot.ParticleSprite firstParticle =
                    first.snapshot(new Vector3f()).particles().get(0);
            EffectSnapshot.ParticleSprite secondParticle =
                    second.snapshot(new Vector3f()).particles().get(0);
            assertEquals(firstParticle, secondParticle);
            assertEquals(0.125f, firstParticle.ageSeconds(), 1.0e-6f);
            assertTrue(firstParticle.flipbookStartFrame() < 4);
        }
    }

    private static FloatTrack linearTrack(float start, float end) {
        return new FloatTrack(
                new FloatTrack.Key(0.0f, start, FloatTrack.Interpolation.LINEAR),
                new FloatTrack.Key(1.0f, end, FloatTrack.Interpolation.LINEAR));
    }

    private static ColorGradient testGradient() {
        return new ColorGradient(
                new ColorGradient.Stop(0.0f, 1.0f, 0.0f, 0.0f, 1.0f),
                new ColorGradient.Stop(1.0f, 0.0f, 1.0f, 0.0f, 0.0f));
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
