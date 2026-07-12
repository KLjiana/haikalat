package com.kaleblangley.haikalat.core.material;

import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * OpenGL runtime material template.
 *
 * <p>This class directly references {@link ShaderProgram}, {@link Texture2D}, and optional {@link Sampler}
 * objects, so it belongs to the GL runtime layer rather than the asset/configuration layer. Use a
 * non-GL definition object such as {@code MaterialDef} while parsing manifests or preparing assets, then
 * build a {@code Material} after the required GL resources have been loaded.</p>
 *
 * <p>Per-object mutable values belong in {@link MaterialInstance}. A Material closes referenced GPU
 * resources only when explicitly configured with {@link ResourceOwnership#OWNED}.</p>
 */
public final class Material implements GlResource {
    private final ShaderProgram shader;
    private final List<TextureBinding> defaultTextures;
    private final Map<UniformKey<?>, UniformValue> defaultUniforms;
    private final BlendMode blendMode;
    private final boolean depthTest;
    private final ResourceOwnership resourceOwnership;
    private boolean closed;

    private Material(Builder builder) {
        this.shader = builder.shader;
        this.defaultTextures = List.copyOf(builder.defaultTextures);
        this.defaultUniforms = Collections.unmodifiableMap(new LinkedHashMap<>(builder.defaultUniforms));
        this.blendMode = builder.blendMode;
        this.depthTest = builder.depthTest;
        this.resourceOwnership = builder.resourceOwnership;
    }

    public CommandBuffer bind(CommandBuffer cmd) {
        bindState(cmd);
        bindDefaultTextures(cmd);
        bindDefaultUniforms(cmd);
        return cmd;
    }

    void bindState(CommandBuffer cmd) {
        cmd.bindShader(shader);
        cmd.materialState(blendMode, depthTest);
    }

    void bindDefaultTextures(CommandBuffer cmd) {
        for (TextureBinding binding : defaultTextures) {
            cmd.bindTexture(binding.unit(), binding.texture(), binding.sampler());
        }
    }

    void bindDefaultUniforms(CommandBuffer cmd) {
        for (Map.Entry<UniformKey<?>, UniformValue> entry : defaultUniforms.entrySet()) {
            applyUniform(cmd, entry.getKey(), entry.getValue());
        }
    }

    void applyUniform(CommandBuffer cmd, String name, UniformValue value) {
        applyUniform(cmd, new UniformKey<>(name, value.type()), value);
    }

    void applyUniform(CommandBuffer cmd, UniformKey<?> key, UniformValue value) {
        key.validate(value);
        Objects.requireNonNull(value, "value").apply(cmd, shader, key.name());
    }

    public ShaderProgram shader() {
        return shader;
    }

    public BlendMode blendMode() {
        return blendMode;
    }

    public boolean depthTest() {
        return depthTest;
    }

    public ResourceOwnership resourceOwnership() {
        return resourceOwnership;
    }

    public Map<UniformKey<?>, UniformValue> defaultUniforms() {
        return defaultUniforms;
    }

    public List<TextureBinding> defaultTextures() {
        return defaultTextures;
    }

    @Override
    public int id() {
        return shader.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (resourceOwnership == ResourceOwnership.OWNED) {
            shader.close();
            Set<Integer> closedSamplers = new HashSet<>();
            for (TextureBinding binding : defaultTextures) {
                binding.texture().close();
                if (binding.sampler() != null && closedSamplers.add(binding.sampler().id())) {
                    binding.sampler().close();
                }
            }
        }
        closed = true;
    }

    public static Builder builder(ShaderProgram shader) {
        return new Builder(Objects.requireNonNull(shader, "shader"));
    }

    public MaterialInstance createInstance() {
        return new MaterialInstance(this);
    }

    public record TextureBinding(int unit, String samplerName, Texture2D texture, Sampler sampler) {
        public TextureBinding {
            Objects.requireNonNull(samplerName, "samplerName");
            Objects.requireNonNull(texture, "texture");
        }

        public TextureBinding(int unit, String samplerName, Texture2D texture) {
            this(unit, samplerName, texture, null);
        }

        public UniformKey<UniformValue.IntVal> samplerKey() {
            return UniformKey.int1(samplerName);
        }
    }

    public static final class Builder {
        private final ShaderProgram shader;
        private final List<TextureBinding> defaultTextures = new ArrayList<>();
        private final Map<UniformKey<?>, UniformValue> defaultUniforms = new LinkedHashMap<>();
        private BlendMode blendMode = BlendMode.OPAQUE;
        private boolean depthTest = true;
        private ResourceOwnership resourceOwnership = ResourceOwnership.BORROWED;

        private Builder(ShaderProgram shader) {
            this.shader = shader;
        }

        public Builder texture(String name, Texture2D texture) {
            return texture(0, name, texture);
        }

        public Builder texture(int unit, String name, Texture2D texture) {
            return texture(unit, name, texture, null);
        }

        public Builder texture(String name, Texture2D texture, Sampler sampler) {
            return texture(0, name, texture, sampler);
        }

        public Builder texture(int unit, String name, Texture2D texture, Sampler sampler) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(texture, "texture");
            defaultTextures.add(new TextureBinding(unit, name, texture, sampler));
            put(UniformKey.int1(name), new UniformValue.IntVal(unit));
            return this;
        }

        public Builder setBool(String name, boolean value) {
            return set(UniformKey.bool(name), new UniformValue.BoolVal(value));
        }

        public Builder set(UniformKey<UniformValue.BoolVal> key, UniformValue.BoolVal value) {
            put(key, value);
            return this;
        }

        public Builder setFloat(String name, float value) {
            return set(UniformKey.float1(name), new UniformValue.FloatVal(value));
        }

        public Builder set(UniformKey<UniformValue.FloatVal> key, UniformValue.FloatVal value) {
            put(key, value);
            return this;
        }

        public Builder setInt(String name, int value) {
            return set(UniformKey.int1(name), new UniformValue.IntVal(value));
        }

        public Builder set(UniformKey<UniformValue.IntVal> key, UniformValue.IntVal value) {
            put(key, value);
            return this;
        }

        public Builder setVec2(String name, float x, float y) {
            return set(UniformKey.vec2(name), new UniformValue.Vec2Val(x, y));
        }

        public Builder set(UniformKey<UniformValue.Vec2Val> key, UniformValue.Vec2Val value) {
            put(key, value);
            return this;
        }

        public Builder setVec3(String name, Vector3f value) {
            return set(UniformKey.vec3(name), new UniformValue.Vec3Val(value));
        }

        public Builder set(UniformKey<UniformValue.Vec3Val> key, UniformValue.Vec3Val value) {
            put(key, value);
            return this;
        }

        public Builder setMat4(String name, Matrix4f value) {
            return set(UniformKey.mat4(name), new UniformValue.Mat4Val(value));
        }

        public Builder set(UniformKey<UniformValue.Mat4Val> key, UniformValue.Mat4Val value) {
            put(key, value);
            return this;
        }

        public Builder blendMode(BlendMode mode) {
            this.blendMode = Objects.requireNonNull(mode, "blendMode");
            return this;
        }

        public Builder depthTest(boolean enable) {
            this.depthTest = enable;
            return this;
        }

        public Builder resourceOwnership(ResourceOwnership ownership) {
            this.resourceOwnership = Objects.requireNonNull(ownership, "ownership");
            return this;
        }

        public Builder ownResources() {
            return resourceOwnership(ResourceOwnership.OWNED);
        }

        public Material build() {
            return new Material(this);
        }

        private void put(UniformKey<?> key, UniformValue value) {
            key.validate(value);
            defaultUniforms.put(key, value);
        }
    }
}
