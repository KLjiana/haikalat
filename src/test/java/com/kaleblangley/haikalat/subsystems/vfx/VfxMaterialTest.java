package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VfxMaterialTest {
    @Test
    void legacyDefaultsAreWhiteAlphaPureValues() {
        VfxMaterial material = VfxMaterial.legacyAlpha();
        assertEquals(VfxMaskMode.WHITE, material.maskMode());
        assertEquals(BlendMode.ALPHA, material.blendMode());
        assertEquals(1.0f, material.emissiveIntensity());
        assertEquals(VfxUvRegion.FULL, material.uvRegion());
        assertSame(material, VfxVisualSet.legacy().particle());
    }

    @Test
    void validatesTextureBlendAndBoundedFields() {
        assertThrows(IllegalArgumentException.class, () -> VfxMaterial.builder("mask")
                .maskMode(VfxMaskMode.ALPHA).build());
        assertThrows(IllegalArgumentException.class, () -> VfxMaterial.builder("opaque")
                .blendMode(BlendMode.OPAQUE));
        assertThrows(IllegalArgumentException.class, () -> VfxMaterial.builder("cutoff")
                .alphaCutoff(1.1f));
        assertThrows(IllegalArgumentException.class, () -> VfxMaterial.builder("stretch")
                .maximumStretch(0.9f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxUvRegion(0.5f, 0.0f, 0.5f, 1.0f));

        VfxMaterial material = VfxMaterial.builder("fire")
                .texture(AssetRef.of("/vfx/particles/kenney/particle_pack/fire_01.png"))
                .maskMode(VfxMaskMode.LUMINANCE)
                .blendMode(BlendMode.ADDITIVE)
                .emissiveIntensity(4.0f)
                .softParticleDistance(0.1f)
                .velocityStretch(0.25f)
                .maximumStretch(2.0f)
                .fogInfluence(0.5f)
                .build();
        assertEquals("/vfx/particles/kenney/particle_pack/fire_01.png", material.texture().orElseThrow().path());
        VfxMaterial copy = VfxMaterial.builder("fire")
                .texture(AssetRef.of("/vfx/particles/kenney/particle_pack/fire_01.png"))
                .maskMode(VfxMaskMode.LUMINANCE)
                .blendMode(BlendMode.ADDITIVE)
                .emissiveIntensity(4.0f)
                .softParticleDistance(0.1f)
                .velocityStretch(0.25f)
                .maximumStretch(2.0f)
                .fogInfluence(0.5f)
                .build();
        assertEquals(material, copy);
        assertEquals(material.hashCode(), copy.hashCode());
    }

    @Test
    void legacySnapshotConstructorAndParticleConstructorRemainCompatible() {
        EffectSnapshot.ParticleSprite particle = new EffectSnapshot.ParticleSprite(
                1L, new Vector3f(), 1.0f, 0.0f, new Vector4f(1.0f), 0.0f);
        EffectSnapshot snapshot = new EffectSnapshot(
                List.of(particle), List.of(), List.of(), List.of(particle));

        assertEquals(new Vector3f(), particle.velocity());
        assertEquals(0.0f, particle.frameProgress());
        assertEquals(VfxVisualSet.legacy(), snapshot.visuals());
    }

    @Test
    void flipbookValidatesAtlasAndSamplesFramesWithOptionalInterpolation() {
        FlipbookConfig flipbook = new FlipbookConfig(2, 2, 3, 4.0f, true, true);
        FlipbookConfig.FrameSample sample = flipbook.sample(0.375f, 1);

        assertEquals(2, sample.currentFrame());
        assertEquals(0, sample.nextFrame());
        assertEquals(0.5f, sample.blend(), 1.0e-6f);
        assertEquals(new VfxUvRegion(0.0f, 0.5f, 0.5f, 1.0f),
                flipbook.frameRegion(2));

        VfxMaterial material = VfxMaterial.builder("animated")
                .flipbook(flipbook)
                .build();
        assertEquals(flipbook, material.flipbook().orElseThrow());
        assertTrue(VfxMaterial.legacyAlpha().flipbook().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new FlipbookConfig(2, 2, 5, 1.0f, false, false));
    }
}
