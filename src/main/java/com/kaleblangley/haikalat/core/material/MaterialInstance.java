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

public final class MaterialInstance {
    private final Material material;
    private final Map<UniformKey<?>, UniformValue> uniformOverrides = new LinkedHashMap<>();
    private final Map<Integer, Material.TextureBinding> textureOverrides = new LinkedHashMap<>();
    private Map<UniformKey<?>, UniformValue> uniformOverridesSnapshot = Map.of();
    private Map<Integer, Material.TextureBinding> textureOverridesSnapshot = Map.of();
    private boolean uniformOverridesDirty;
    private boolean textureOverridesDirty;
    private boolean hasOverrides;

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
        textureOverrides.put(unit, binding);
        textureOverridesDirty = true;
        hasOverrides = true;
        put(binding.samplerKey(), new UniformValue.IntVal(unit));
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
        uniformOverrides.clear();
        textureOverrides.clear();
        uniformOverridesSnapshot = Map.of();
        textureOverridesSnapshot = Map.of();
        uniformOverridesDirty = false;
        textureOverridesDirty = false;
        hasOverrides = false;
    }

    private void put(UniformKey<?> key, UniformValue value) {
        key.validate(value);
        uniformOverrides.put(key, value);
        uniformOverridesDirty = true;
        hasOverrides = true;
    }
}
