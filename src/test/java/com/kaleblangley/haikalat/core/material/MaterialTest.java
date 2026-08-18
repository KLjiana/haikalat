package com.kaleblangley.haikalat.core.material;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialTest {
    @Test
    void overrideAccessorsReuseSnapshotsWithoutMakingOldSnapshotsLive() {
        MaterialInstance instance = materialInstance();
        Map<UniformKey<?>, UniformValue> empty = instance.uniformOverrides();

        assertFalse(instance.hasOverrides());

        instance.setFloat("uValue", 1.0f);
        Map<UniformKey<?>, UniformValue> populated = instance.uniformOverrides();

        assertTrue(instance.hasOverrides());
        assertTrue(empty.isEmpty());
        assertEquals(1, populated.size());
        assertTrue(populated == instance.uniformOverrides(),
                "Unchanged overrides should reuse their immutable snapshot");

        instance.clearOverrides();
        assertFalse(instance.hasOverrides());
    }

    @Test
    void materialInstanceRevisionAdvancesOncePerEffectiveMutation() {
        MaterialInstance instance = materialInstance();
        long initialEpoch = MaterialInstance.mutationEpoch();
        assertEquals(0L, instance.revision());

        instance.setFloat("uValue", 1.0f);
        long uniformEpoch = MaterialInstance.mutationEpoch();
        assertEquals(1L, instance.revision());
        assertTrue(uniformEpoch > initialEpoch);
        instance.setFloat("uValue", 1.0f);
        assertEquals(1L, instance.revision(), "equal override must not invalidate caches");
        assertEquals(uniformEpoch, MaterialInstance.mutationEpoch());

        Texture2D texture = texture(13);
        instance.texture(3, "uTexture", texture);
        long textureEpoch = MaterialInstance.mutationEpoch();
        assertEquals(2L, instance.revision(),
                "one texture call changes binding and sampler uniform as one domain mutation");
        assertTrue(textureEpoch > uniformEpoch);
        instance.texture(3, "uTexture", texture);
        assertEquals(2L, instance.revision());
        assertEquals(textureEpoch, MaterialInstance.mutationEpoch());

        instance.clearOverrides();
        assertEquals(3L, instance.revision());
        assertTrue(MaterialInstance.mutationEpoch() > textureEpoch);
        long clearedEpoch = MaterialInstance.mutationEpoch();
        instance.clearOverrides();
        assertEquals(3L, instance.revision(), "clearing an empty instance is not a mutation");
        assertEquals(clearedEpoch, MaterialInstance.mutationEpoch());
    }

    @Test
    void materialDefaultsAreImmutableTemplateState() {
        UniformKey<UniformValue.FloatVal> roughness = UniformKey.float1("uRoughness");
        UniformKey<UniformValue.BoolVal> enabled = UniformKey.bool("uEnabled");
        Material material = Material.builder(shader(7))
                .texture(1, "uTexture", texture(11))
                .set(roughness, new UniformValue.FloatVal(0.4f))
                .set(enabled, new UniformValue.BoolVal(true))
                .resourceOwnership(ResourceOwnership.BORROWED)
                .build();

        assertEquals(ResourceOwnership.BORROWED, material.resourceOwnership());
        assertEquals(1, material.defaultTextures().size());
        assertEquals(UniformType.FLOAT, material.defaultUniforms().get(roughness).type());
        assertEquals(UniformType.BOOL, material.defaultUniforms().get(enabled).type());

        assertThrows(UnsupportedOperationException.class,
                () -> material.defaultUniforms().put(UniformKey.int1("x"), new UniformValue.IntVal(1)));
        assertThrows(UnsupportedOperationException.class,
                () -> material.defaultTextures().add(new Material.TextureBinding(0, "x", texture(12))));
    }

    @Test
    void materialInstanceOwnsMutableOverridesSeparatelyFromTemplate() {
        UniformKey<UniformValue.Vec3Val> tint = UniformKey.vec3("uTint");
        UniformKey<UniformValue.Vec2Val> uvScale = UniformKey.vec2("uUvScale");
        Material material = Material.builder(shader(7))
                .set(tint, new UniformValue.Vec3Val(new Vector3f(1, 1, 1)))
                .build();
        MaterialInstance instance = material.createInstance()
                .set(tint, new UniformValue.Vec3Val(new Vector3f(0, 1, 0)))
                .set(uvScale, new UniformValue.Vec2Val(2.0f, 3.0f))
                .texture(3, "uTexture", texture(13));

        assertEquals(UniformType.VEC3, material.defaultUniforms().get(tint).type());
        assertEquals(UniformType.VEC3, instance.uniformOverrides().get(tint).type());
        assertEquals(UniformType.VEC2, instance.uniformOverrides().get(uvScale).type());
        assertTrue(instance.textureOverrides().containsKey(3));
        assertEquals(UniformType.INT, instance.uniformOverrides().get(UniformKey.int1("uTexture")).type());

        instance.clearOverrides();

        assertTrue(instance.uniformOverrides().isEmpty());
        assertTrue(instance.textureOverrides().isEmpty());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void typedUniformKeysRejectWrongValueTypes() {
        UniformKey<UniformValue.FloatVal> roughness = UniformKey.float1("uRoughness");

        assertThrows(IllegalArgumentException.class,
                () -> Material.builder(shader(7)).set((UniformKey) roughness, new UniformValue.IntVal(1)));
        assertThrows(IllegalArgumentException.class,
                () -> materialInstance().set((UniformKey) roughness, new UniformValue.IntVal(1)));
    }

    @Test
    void textureBindingsCanCarryIndependentSamplerState() {
        Sampler sampler = sampler(21);
        Material material = Material.builder(shader(7))
                .texture(2, "uTexture", texture(11), sampler)
                .build();
        Material.TextureBinding binding = material.defaultTextures().get(0);

        assertEquals(2, binding.unit());
        assertEquals("uTexture", binding.samplerName());
        assertEquals(sampler, binding.sampler());
        assertEquals(UniformType.INT, material.defaultUniforms().get(binding.samplerKey()).type());

        MaterialInstance instance = material.createInstance()
                .texture(3, "uTexture", texture(12), sampler);

        assertEquals(sampler, instance.textureOverrides().get(3).sampler());
        assertEquals(UniformType.INT, instance.uniformOverrides().get(UniformKey.int1("uTexture")).type());
    }

    @Test
    void uniformValuesDefensivelyCopyMutableMathObjects() {
        Vector3f color = new Vector3f(1, 2, 3);
        Matrix4f matrix = new Matrix4f().translation(1, 2, 3);

        UniformValue.Vec3Val vec = new UniformValue.Vec3Val(color);
        UniformValue.Mat4Val mat = new UniformValue.Mat4Val(matrix);
        color.set(9, 9, 9);
        matrix.identity();

        assertEquals(new Vector3f(1, 2, 3), vec.value());
        assertEquals(1.0f, mat.value().m30(), 0.0f);
        assertEquals(2.0f, mat.value().m31(), 0.0f);
        assertEquals(3.0f, mat.value().m32(), 0.0f);
    }

    private static ShaderProgram shader(int id) {
        try {
            Constructor<ShaderProgram> ctor = ShaderProgram.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Texture2D texture(int id) {
        try {
            Constructor<Texture2D> ctor = Texture2D.class.getDeclaredConstructor(int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id, 1, 1, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Sampler sampler(int id) {
        try {
            Constructor<Sampler> ctor = Sampler.class.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static MaterialInstance materialInstance() {
        return Material.builder(shader(7)).build().createInstance();
    }
}
