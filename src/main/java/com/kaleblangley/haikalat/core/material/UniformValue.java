package com.kaleblangley.haikalat.core.material;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

public sealed interface UniformValue {
    UniformType type();

    void apply(CommandBuffer cmd, ShaderProgram shader, String name);

    record BoolVal(boolean value) implements UniformValue {
        @Override
        public UniformType type() {
            return UniformType.BOOL;
        }

        @Override
        public void apply(CommandBuffer cmd, ShaderProgram shader, String name) {
            cmd.setUniformInt(shader, name, value ? 1 : 0);
        }
    }

    record FloatVal(float value) implements UniformValue {
        @Override
        public UniformType type() {
            return UniformType.FLOAT;
        }

        @Override
        public void apply(CommandBuffer cmd, ShaderProgram shader, String name) {
            cmd.setUniformFloat(shader, name, value);
        }
    }

    record IntVal(int value) implements UniformValue {
        @Override
        public UniformType type() {
            return UniformType.INT;
        }

        @Override
        public void apply(CommandBuffer cmd, ShaderProgram shader, String name) {
            cmd.setUniformInt(shader, name, value);
        }
    }

    record Vec2Val(float x, float y) implements UniformValue {
        @Override
        public UniformType type() {
            return UniformType.VEC2;
        }

        @Override
        public void apply(CommandBuffer cmd, ShaderProgram shader, String name) {
            cmd.setUniformVec2(shader, name, x, y);
        }
    }

    record Vec3Val(Vector3f value) implements UniformValue {
        public Vec3Val {
            value = new Vector3f(Objects.requireNonNull(value, "value"));
        }

        @Override
        public UniformType type() {
            return UniformType.VEC3;
        }

        @Override
        public void apply(CommandBuffer cmd, ShaderProgram shader, String name) {
            cmd.setUniformVec3(shader, name, value);
        }
    }

    record Mat4Val(Matrix4f value) implements UniformValue {
        public Mat4Val {
            value = new Matrix4f(Objects.requireNonNull(value, "value"));
        }

        @Override
        public UniformType type() {
            return UniformType.MAT4;
        }

        @Override
        public void apply(CommandBuffer cmd, ShaderProgram shader, String name) {
            cmd.setUniformMat4(shader, name, value);
        }
    }
}
