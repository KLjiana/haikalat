package com.kaleblangley.haikalat.core.material;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public sealed interface UniformValue {

    record FloatVal(float value) implements UniformValue {}

    record IntVal(int value) implements UniformValue {}

    record Vec3Val(Vector3f value) implements UniformValue {}

    record Mat4Val(Matrix4f value) implements UniformValue {}
}
