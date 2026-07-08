package com.kaleblangley.haikalat.core.material;

import java.util.Objects;

public record UniformKey<T extends UniformValue>(String name, UniformType type) {
    public UniformKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("uniform name must not be blank");
        }
    }

    public static UniformKey<UniformValue.BoolVal> bool(String name) {
        return new UniformKey<>(name, UniformType.BOOL);
    }

    public static UniformKey<UniformValue.FloatVal> float1(String name) {
        return new UniformKey<>(name, UniformType.FLOAT);
    }

    public static UniformKey<UniformValue.IntVal> int1(String name) {
        return new UniformKey<>(name, UniformType.INT);
    }

    public static UniformKey<UniformValue.Vec2Val> vec2(String name) {
        return new UniformKey<>(name, UniformType.VEC2);
    }

    public static UniformKey<UniformValue.Vec3Val> vec3(String name) {
        return new UniformKey<>(name, UniformType.VEC3);
    }

    public static UniformKey<UniformValue.Mat4Val> mat4(String name) {
        return new UniformKey<>(name, UniformType.MAT4);
    }

    public void validate(UniformValue value) {
        Objects.requireNonNull(value, "value");
        if (value.type() != type) {
            throw new IllegalArgumentException(
                    "Uniform " + name + " expects " + type + " but got " + value.type());
        }
    }
}
