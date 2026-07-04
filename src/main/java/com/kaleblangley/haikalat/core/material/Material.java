package com.kaleblangley.haikalat.core.material;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

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
    private final Map<String, UniformValue> staticUniforms;
    private final BlendMode blendMode;
    private final boolean depthTest;
    private final boolean ownResources;
    private boolean closed;

    private Material(Builder builder) {
        this.shader = builder.shader;
        this.textureBindings = List.copyOf(builder.textureBindings);
        this.staticUniforms = Map.copyOf(builder.staticUniforms);
        this.blendMode = builder.blendMode;
        this.depthTest = builder.depthTest;
        this.ownResources = builder.ownResources;
    }

    /**
     * 将材质绑定到命令缓冲区，依次设置着色器、混合状态、深度测试、纹理和静态 uniform。
     *
     * @param cmd 命令缓冲区
     * @return 传入的命令缓冲区，支持链式调用
     */
    public CommandBuffer bind(CommandBuffer cmd) {
        cmd.bindShader(shader);
        applyBlendState(cmd);
        cmd.enableDepthTest(depthTest);
        for (TextureBinding tb : textureBindings) {
            cmd.bindTexture(tb.unit, tb.texture);
        }
        for (Map.Entry<String, UniformValue> entry : staticUniforms.entrySet()) {
            applyUniform(cmd, entry.getKey(), entry.getValue());
        }
        return cmd;
    }

    void applyUniform(CommandBuffer cmd, String name, UniformValue value) {
        if (value instanceof UniformValue.FloatVal f) {
            cmd.setUniformFloat(shader, name, f.value());
        } else if (value instanceof UniformValue.IntVal i) {
            cmd.setUniformInt(shader, name, i.value());
        } else if (value instanceof UniformValue.Vec3Val v) {
            cmd.setUniformVec3(shader, name, v.value());
        } else if (value instanceof UniformValue.Mat4Val m) {
            cmd.setUniformMat4(shader, name, m.value());
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

    /** @return 此材质绑定的着色器程序 */
    public ShaderProgram shader() {
        return shader;
    }

    /** @return 此材质的混合模式 */
    public BlendMode blendMode() {
        return blendMode;
    }

    /** @return 此材质是否启用深度测试 */
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
        if (closed) {
            return;
        }
        if (ownResources) {
            shader.close();
            for (TextureBinding tb : textureBindings) {
                tb.texture.close();
            }
        }
        closed = true;
    }

    /**
     * 创建一个 Material 的 Builder。
     *
     * @param shader 此材质绑定的着色器程序
     * @return Builder 实例
     */
    public static Builder builder(ShaderProgram shader) {
        return new Builder(Objects.requireNonNull(shader, "shader"));
    }

    /** @return 通过此材质创建一个可覆盖参数的实例 */
    public MaterialInstance createInstance() {
        return new MaterialInstance(this);
    }

    private record TextureBinding(int unit, Texture2D texture) {
    }

    public static final class Builder {
        private final ShaderProgram shader;
        private final List<TextureBinding> textureBindings = new ArrayList<>();
        private final Map<String, UniformValue> staticUniforms = new LinkedHashMap<>();
        private BlendMode blendMode = BlendMode.OPAQUE;
        private boolean depthTest = true;
        private boolean ownResources;

        private Builder(ShaderProgram shader) {
            this.shader = shader;
        }

        /**
         * 绑定纹理到默认单元 0。
         *
         * @param name    uniform 名称，对应采样器
         * @param texture 纹理对象
         */
        public Builder texture(String name, Texture2D texture) {
            return texture(0, name, texture);
        }

        /**
         * 在指定单元上绑定纹理，并自动设置对应的 uniform int。
         *
         * @param unit    纹理单元
         * @param name    uniform 名称
         * @param texture 纹理对象
         */
        public Builder texture(int unit, String name, Texture2D texture) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(texture, "texture");
            textureBindings.add(new TextureBinding(unit, texture));
            staticUniforms.put(name, new UniformValue.IntVal(unit));
            return this;
        }

        /**
         * 设置 float 类型的静态 uniform。
         *
         * @param name  uniform 名称
         * @param value 浮点值
         */
        public Builder setFloat(String name, float value) {
            staticUniforms.put(Objects.requireNonNull(name, "name"), new UniformValue.FloatVal(value));
            return this;
        }

        /**
         * 设置 int 类型的静态 uniform。
         *
         * @param name  uniform 名称
         * @param value 整数值
         */
        public Builder setInt(String name, int value) {
            staticUniforms.put(Objects.requireNonNull(name, "name"), new UniformValue.IntVal(value));
            return this;
        }

        /**
         * 设置 vec3 类型的静态 uniform。
         *
         * @param name  uniform 名称
         * @param value 向量值
         */
        public Builder setVec3(String name, Vector3f value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            staticUniforms.put(name, new UniformValue.Vec3Val(value));
            return this;
        }

        /**
         * 设置 mat4 类型的静态 uniform。
         *
         * @param name  uniform 名称
         * @param value 矩阵值
         */
        public Builder setMat4(String name, Matrix4f value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            staticUniforms.put(name, new UniformValue.Mat4Val(value));
            return this;
        }

        /**
         * 设置材质的混合模式。
         *
         * @param mode 混合模式
         */
        public Builder blendMode(BlendMode mode) {
            this.blendMode = Objects.requireNonNull(mode, "blendMode");
            return this;
        }

        /**
         * 设置材质是否启用深度测试。
         *
         * @param enable true 启用
         */
        public Builder depthTest(boolean enable) {
            this.depthTest = enable;
            return this;
        }

        /** 标记此材质拥有其着色器及纹理资源的所有权，关闭时会一并释放。 */
        public Builder ownResources() {
            this.ownResources = true;
            return this;
        }

        /** @return 构建完成的 Material 实例 */
        public Material build() {
            return new Material(this);
        }
    }
}
