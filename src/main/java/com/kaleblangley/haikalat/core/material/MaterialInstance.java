package com.kaleblangley.haikalat.core.material;

import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class MaterialInstance {
    private static final AtomicLong MUTATION_EPOCH = new AtomicLong();

    private final Material material;
    private final Map<UniformKey<?>, UniformValue> uniformOverrides = new LinkedHashMap<>();
    private final Map<Integer, Material.TextureBinding> textureOverrides = new LinkedHashMap<>();
    private Map<UniformKey<?>, UniformValue> uniformOverridesSnapshot = Map.of();
    private Map<Integer, Material.TextureBinding> textureOverridesSnapshot = Map.of();
    private boolean uniformOverridesDirty;
    private boolean textureOverridesDirty;
    private boolean hasOverrides;
    private long revision;

    MaterialInstance(Material material) {
        this.material = Objects.requireNonNull(material, "material");
    }

    public MaterialInstance setBool(String name, boolean value) {
        return set(UniformKey.bool(name), new UniformValue.BoolVal(value));
    }

    public MaterialInstance set(UniformKey<UniformValue.BoolVal> key, UniformValue.BoolVal value) {
        put(key, value);
        return this;
    }

    public MaterialInstance setFloat(String name, float value) {
        return set(UniformKey.float1(name), new UniformValue.FloatVal(value));
    }

    public MaterialInstance set(UniformKey<UniformValue.FloatVal> key, UniformValue.FloatVal value) {
        put(key, value);
        return this;
    }

    public MaterialInstance setInt(String name, int value) {
        return set(UniformKey.int1(name), new UniformValue.IntVal(value));
    }

    public MaterialInstance set(UniformKey<UniformValue.IntVal> key, UniformValue.IntVal value) {
        put(key, value);
        return this;
    }

    public MaterialInstance setVec2(String name, float x, float y) {
        return set(UniformKey.vec2(name), new UniformValue.Vec2Val(x, y));
    }

    public MaterialInstance set(UniformKey<UniformValue.Vec2Val> key, UniformValue.Vec2Val value) {
        put(key, value);
        return this;
    }

    public MaterialInstance setVec3(String name, Vector3f value) {
        return set(UniformKey.vec3(name), new UniformValue.Vec3Val(value));
    }

    public MaterialInstance set(UniformKey<UniformValue.Vec3Val> key, UniformValue.Vec3Val value) {
        put(key, value);
        return this;
    }

    public MaterialInstance setVec4(String name, Vector4f value) {
        return set(UniformKey.vec4(name), new UniformValue.Vec4Val(value));
    }

    public MaterialInstance set(UniformKey<UniformValue.Vec4Val> key, UniformValue.Vec4Val value) {
        put(key, value);
        return this;
    }

    public MaterialInstance setMat4(String name, Matrix4f value) {
        return set(UniformKey.mat4(name), new UniformValue.Mat4Val(value));
    }

    public MaterialInstance set(UniformKey<UniformValue.Mat4Val> key, UniformValue.Mat4Val value) {
        put(key, value);
        return this;
    }

    public MaterialInstance texture(String samplerName, Texture2D texture) {
        return texture(0, samplerName, texture);
    }

    public MaterialInstance texture(int unit, String samplerName, Texture2D texture) {
        return texture(unit, samplerName, texture, null);
    }

    public MaterialInstance texture(String samplerName, Texture2D texture, Sampler sampler) {
        return texture(0, samplerName, texture, sampler);
    }

    public MaterialInstance texture(int unit, String samplerName, Texture2D texture, Sampler sampler) {
        Material.TextureBinding binding = new Material.TextureBinding(unit, samplerName, texture, sampler);
        Material.TextureBinding previous = textureOverrides.put(unit, binding);
        boolean changed = !binding.equals(previous);
        if (changed) textureOverridesDirty = true;
        changed |= putOverride(binding.samplerKey(), new UniformValue.IntVal(unit));
        if (changed) advanceRevision();
        return this;
    }

    public CommandBuffer bind(CommandBuffer cmd) {
        material.bind(cmd);
        for (Material.TextureBinding binding : textureOverrides.values()) {
            cmd.bindTexture(binding.unit(), binding.texture(), binding.sampler());
        }
        for (Map.Entry<UniformKey<?>, UniformValue> entry : uniformOverrides.entrySet()) {
            material.applyUniform(cmd, entry.getKey(), entry.getValue());
        }
        return cmd;
    }

    public Material material() {
        return material;
    }

    /** @return 当前实例是否包含任何逐实例 uniform 或纹理覆盖。 */
    public boolean hasOverrides() {
        return hasOverrides;
    }

    /**
     * Change token for this instance's effective uniform/texture overrides.
     * Equality is only meaningful for the same {@code MaterialInstance} lifecycle.
     */
    public long revision() {
        return revision;
    }

    /**
     * Process-local conservative epoch for render-cache invalidation. A mutation in another
     * scene may cause one harmless cache miss, but stable scenes can test this value in O(1).
     */
    public static long mutationEpoch() {
        return MUTATION_EPOCH.get();
    }

    public Map<UniformKey<?>, UniformValue> uniformOverrides() {
        if (uniformOverridesDirty) {
            uniformOverridesSnapshot = Map.copyOf(uniformOverrides);
            uniformOverridesDirty = false;
        }
        return uniformOverridesSnapshot;
    }

    public Map<Integer, Material.TextureBinding> textureOverrides() {
        if (textureOverridesDirty) {
            textureOverridesSnapshot = Map.copyOf(textureOverrides);
            textureOverridesDirty = false;
        }
        return textureOverridesSnapshot;
    }

    public void clearOverrides() {
        if (!hasOverrides) return;
        uniformOverrides.clear();
        textureOverrides.clear();
        uniformOverridesSnapshot = Map.of();
        textureOverridesSnapshot = Map.of();
        uniformOverridesDirty = false;
        textureOverridesDirty = false;
        hasOverrides = false;
        advanceRevision();
    }

    private void put(UniformKey<?> key, UniformValue value) {
        if (putOverride(key, value)) advanceRevision();
    }

    private boolean putOverride(UniformKey<?> key, UniformValue value) {
        key.validate(value);
        UniformValue previous = uniformOverrides.put(key, value);
        boolean changed = !value.equals(previous);
        if (changed) uniformOverridesDirty = true;
        hasOverrides = true;
        return changed;
    }

    private void advanceRevision() {
        revision = Math.incrementExact(revision);
        MUTATION_EPOCH.incrementAndGet();
    }
}
