package com.kaleblangley.haikalat.subsystems.vfx;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void newEffectTermsAreOptionalAndLegacyDefaultsStayOff() {
        VfxMaterial legacy = VfxMaterial.legacyAlpha();
        assertTrue(legacy.rimLight().isEmpty());
        assertTrue(legacy.uvFlow().isEmpty());
        assertTrue(legacy.dissolve().isEmpty());
        assertFalse(legacy.requiresNormals());

        VfxRimLight rim = new VfxRimLight(new Vector3f(0.2f, 0.4f, 1.0f), 3.0f, 2.0f);
        VfxUvFlow flow = VfxUvFlow.of(0.2f, -0.1f, 4.0f, 0.05f, 7L);
        VfxDissolve dissolve = VfxDissolve.explicit(0.1f,
                new Vector3f(1.0f, 0.5f, 0.2f), 4.0f, 6.0f, 9L);
        VfxMaterial material = VfxMaterial.builder("combined")
                .rimLight(rim).uvFlow(flow).dissolve(dissolve).build();
        VfxMaterial copy = VfxMaterial.builder("combined")
                .rimLight(rim).uvFlow(flow).dissolve(dissolve).build();
        assertEquals(rim, material.rimLight().orElseThrow());
        assertEquals(flow, material.uvFlow().orElseThrow());
        assertEquals(dissolve, material.dissolve().orElseThrow());
        assertTrue(material.requiresNormals());
        assertEquals(material, copy);
        assertEquals(material.hashCode(), copy.hashCode());
    }

    @Test
    void newEffectTermsRejectNonFiniteAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new VfxRimLight(new Vector3f(1.0f), -1.0f, 2.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxRimLight(new Vector3f(1.0f), 1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new VfxRimLight(new Vector3f(Float.NaN, 0.0f, 0.0f), 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> VfxUvFlow.of(Float.POSITIVE_INFINITY, 0.0f, 1.0f, 0.0f, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> VfxUvFlow.of(0.0f, 0.0f, 0.0f, 0.0f, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> VfxUvFlow.of(0.0f, 0.0f, 1.0f, 1.5f, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> VfxDissolve.lifecycle(-0.1f, new Vector3f(1.0f), 1.0f, 1.0f, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> VfxDissolve.lifecycle(0.1f, new Vector3f(1.0f), -1.0f, 1.0f, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> VfxDissolve.lifecycle(0.1f, new Vector3f(1.0f), 1.0f, 0.0f, 1L));
    }

    @Test
    void rimWeightAndDissolveEndpointsMatchTheDocumentedAnalyticReference() {
        assertEquals(0.0f, VfxRimLight.weight(1.0f, 2.0f), 1.0e-6f);
        assertEquals(0.25f, VfxRimLight.weight(0.5f, 2.0f), 1.0e-6f);
        assertEquals(1.0f, VfxRimLight.weight(0.0f, 2.0f), 1.0e-6f);
        assertEquals(1.0f, VfxRimLight.weight(-1.0f, 2.0f), 1.0e-6f);

        assertTrue(VfxDissolve.visible(0.0f, 0.0f));
        assertFalse(VfxDissolve.visible(1.0f, 1.0f));
        assertTrue(VfxDissolve.visible(0.6f, 0.5f));
        assertFalse(VfxDissolve.visible(0.4f, 0.5f));
        assertEquals(0.0f, VfxDissolve.edgeGlow(0.6f, 0.5f, 0.0f), 1.0e-6f);
        assertEquals(0.5f, VfxDissolve.edgeGlow(0.55f, 0.5f, 0.1f), 1.0e-6f);
        assertEquals(0.0f, VfxDissolve.edgeGlow(0.7f, 0.5f, 0.1f), 1.0e-6f);
    }

    @Test
    void uvFlowAppliesSpeedThenDistortionThenClamp() {
        VfxUvFlow flow = VfxUvFlow.of(0.5f, -0.25f, 4.0f, 0.1f, 3L);
        Vector2f displaced = flow.sample(0.2f, 0.6f, 2.0f, 1.0f);
        assertEquals(1.0f, displaced.x, 1.0e-6f);
        assertEquals(0.2f, displaced.y, 1.0e-6f);
        Vector2f baseline = flow.sample(0.2f, 0.6f, 0.0f, 0.5f);
        assertEquals(0.2f, baseline.x, 1.0e-6f);
        assertEquals(0.6f, baseline.y, 1.0e-6f);
    }

    @Test
    void rimMaterialRejectsMeshWithoutNormalStream() {
        VfxMaterial rim = VfxMaterial.builder("rim").rimLight(
                new VfxRimLight(new Vector3f(1.0f), 1.0f, 2.0f)).build();
        MeshVfx withoutNormals = new MeshVfx(1, 1.0f, positionUvOnlyQuad(),
                1.0f, 1.0f, new Vector4f(1.0f), new Vector4f(1.0f));
        assertThrows(IllegalArgumentException.class, () -> EffectAsset.builder("no-normals")
                .meshes(withoutNormals).meshMaterial(rim).build());

        MeshVfx withNormals = new MeshVfx(1, 1.0f,
                BuiltinMeshData.texturedQuad("with-normals"),
                1.0f, 1.0f, new Vector4f(1.0f), new Vector4f(1.0f));
        try (EffectAsset asset = EffectAsset.builder("with-normals")
                .meshes(withNormals).meshMaterial(rim).build()) {
            assertTrue(asset.meshVfx().orElseThrow().hasNormals());
        }
    }

    private static MeshData positionUvOnlyQuad() {
        VertexLayout layout = VertexLayout.interleaved(5 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).offsetBytes(0)
                        .semantic(VertexSemantic.POSITION).build(),
                VertexAttribute.builder().index(1).size(2).offsetBytes(3L * Float.BYTES)
                        .semantic(VertexSemantic.TEXCOORD_0).build());
        return MeshData.of("position-uv-only", new float[]{
                -0.5f, -0.5f, 0.0f, 0.0f, 0.0f,
                0.5f, -0.5f, 0.0f, 1.0f, 0.0f,
                0.5f, 0.5f, 0.0f, 1.0f, 1.0f
        }, layout);
    }
}
