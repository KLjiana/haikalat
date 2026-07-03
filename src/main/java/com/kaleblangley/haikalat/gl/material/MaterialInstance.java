package com.kaleblangley.haikalat.gl.material;

import com.kaleblangley.haikalat.gl.command.CommandBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MaterialInstance {
    private final Material material;
    private final Map<String, UniformValue> overrides;

    MaterialInstance(Material material) {
        this.material = Objects.requireNonNull(material, "material");
        this.overrides = new LinkedHashMap<>();
    }

    public MaterialInstance setFloat(String name, float value) {
        overrides.put(Objects.requireNonNull(name, "name"), new UniformValue.FloatVal(value));
        return this;
    }

    public MaterialInstance setInt(String name, int value) {
        overrides.put(Objects.requireNonNull(name, "name"), new UniformValue.IntVal(value));
        return this;
    }

    public MaterialInstance setVec3(String name, Vector3f value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        overrides.put(name, new UniformValue.Vec3Val(value));
        return this;
    }

    public MaterialInstance setMat4(String name, Matrix4f value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        overrides.put(name, new UniformValue.Mat4Val(value));
        return this;
    }

    public CommandBuffer bind(CommandBuffer cmd) {
        material.bind(cmd);
        for (Map.Entry<String, UniformValue> entry : overrides.entrySet()) {
            material.applyUniform(cmd, entry.getKey(), entry.getValue());
        }
        return cmd;
    }

    public Material material() {
        return material;
    }

    public void clearOverrides() {
        overrides.clear();
    }
}
