package com.kaleblangley.haikalat.gl.material;

import com.kaleblangley.haikalat.gl.BlendMode;
import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.*;

public final class Material implements GlResource {
    private final ShaderProgram shader;
    private final List<TextureBinding> textureBindings;
    private final Map<String, Object> staticUniforms;
    private final BlendMode blendMode;
    private final boolean depthTest;
    private boolean closed;

    private Material(Builder builder) {
        this.shader = builder.shader;
        this.textureBindings = List.copyOf(builder.textureBindings);
        this.staticUniforms = Map.copyOf(builder.staticUniforms);
        this.blendMode = builder.blendMode;
        this.depthTest = builder.depthTest;
    }

    public CommandBuffer bind(CommandBuffer cmd) {
        cmd.bindShader(shader);
        applyBlendState(cmd);
        cmd.enableDepthTest(depthTest);
        for (TextureBinding tb : textureBindings) {
            cmd.bindTexture(tb.unit, tb.texture);
        }
        for (Map.Entry<String, Object> entry : staticUniforms.entrySet()) {
            applyUniform(cmd, entry.getKey(), entry.getValue());
        }
        return cmd;
    }

    void applyUniform(CommandBuffer cmd, String name, Object value) {
        if (value instanceof Float f) {
            cmd.setUniformFloat(shader, name, f);
        } else if (value instanceof Integer i) {
            cmd.setUniformInt(shader, name, i);
        } else if (value instanceof Vector3f v) {
            cmd.setUniformVec3(shader, name, v);
        } else if (value instanceof Matrix4f m) {
            cmd.setUniformMat4(shader, name, m);
        } else {
            throw new GlException("Unsupported uniform type: " + value.getClass().getSimpleName());
        }
    }

    private void applyBlendState(CommandBuffer cmd) {
        switch (blendMode) {
            case OPAQUE -> {
                cmd.enableBlend(false);
                cmd.depthMask(true);
            }
            case ALPHA -> {
                cmd.enableBlend(true);
                cmd.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                cmd.depthMask(false);
            }
            case ADDITIVE -> {
                cmd.enableBlend(true);
                cmd.blendFunc(GL_ONE, GL_ONE);
                cmd.depthMask(false);
            }
        }
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
        closed = true;
    }

    public static Builder builder(ShaderProgram shader) {
        return new Builder(Objects.requireNonNull(shader, "shader"));
    }

    public MaterialInstance createInstance() {
        return new MaterialInstance(this);
    }

    private record TextureBinding(int unit, Texture2D texture) {
    }

    public static final class Builder {
        private final ShaderProgram shader;
        private final List<TextureBinding> textureBindings = new ArrayList<>();
        private final Map<String, Object> staticUniforms = new LinkedHashMap<>();
        private BlendMode blendMode = BlendMode.OPAQUE;
        private boolean depthTest = true;

        private Builder(ShaderProgram shader) {
            this.shader = shader;
        }

        public Builder texture(String name, Texture2D texture) {
            return texture(0, name, texture);
        }

        public Builder texture(int unit, String name, Texture2D texture) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(texture, "texture");
            textureBindings.add(new TextureBinding(unit, texture));
            staticUniforms.put(name, unit);
            return this;
        }

        public Builder setFloat(String name, float value) {
            staticUniforms.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        public Builder setInt(String name, int value) {
            staticUniforms.put(Objects.requireNonNull(name, "name"), value);
            return this;
        }

        public Builder setVec3(String name, Vector3f value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            staticUniforms.put(name, value);
            return this;
        }

        public Builder setMat4(String name, Matrix4f value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            staticUniforms.put(name, value);
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

        public Material build() {
            return new Material(this);
        }
    }
}
