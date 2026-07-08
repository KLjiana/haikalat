package com.kaleblangley.haikalat.core.mesh;

import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

public final class InstanceDataLayout {
    private static final int MAT4_BYTES = 16 * Float.BYTES;

    private final String name;
    private final VertexLayout vertexLayout;
    private final boolean matrixTransform;

    private InstanceDataLayout(String name, VertexLayout vertexLayout, boolean matrixTransform) {
        this.name = Objects.requireNonNull(name, "name");
        this.vertexLayout = Objects.requireNonNull(vertexLayout, "vertexLayout");
        this.matrixTransform = matrixTransform;
    }

    public static InstanceDataLayout mat4Transform(int baseLocation) {
        return new InstanceDataLayout("mat4-transform", VertexLayout.instanceMatrix(baseLocation), true);
    }

    public static InstanceDataLayout packedTransform(int baseLocation) {
        return new InstanceDataLayout(
                "packed-transform",
                VertexLayout.interleaved(
                        8 * Float.BYTES,
                        VertexAttribute.builder().index(baseLocation).size(4).type(GL_FLOAT)
                                .offsetBytes(0L).divisor(1).build(),
                        VertexAttribute.builder().index(baseLocation + 1).size(4).type(GL_FLOAT)
                                .offsetBytes(4L * Float.BYTES).divisor(1).build()
                ),
                false
        );
    }

    public static InstanceDataLayout mat4TransformWithColor(int transformBaseLocation, int colorLocation) {
        return new InstanceDataLayout(
                "mat4-transform-color",
                VertexLayout.interleaved(
                        MAT4_BYTES + 4 * Float.BYTES,
                        VertexAttribute.builder().index(transformBaseLocation).size(4).type(GL_FLOAT)
                                .offsetBytes(0L).divisor(1).build(),
                        VertexAttribute.builder().index(transformBaseLocation + 1).size(4).type(GL_FLOAT)
                                .offsetBytes(4L * Float.BYTES).divisor(1).build(),
                        VertexAttribute.builder().index(transformBaseLocation + 2).size(4).type(GL_FLOAT)
                                .offsetBytes(8L * Float.BYTES).divisor(1).build(),
                        VertexAttribute.builder().index(transformBaseLocation + 3).size(4).type(GL_FLOAT)
                                .offsetBytes(12L * Float.BYTES).divisor(1).build(),
                        VertexAttribute.builder().index(colorLocation).size(4).type(GL_FLOAT)
                                .offsetBytes(MAT4_BYTES).divisor(1).build()
                ),
                true
        );
    }

    public static InstanceDataLayout custom(String name, int strideBytes, VertexAttribute... attributes) {
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.length == 0) {
            throw new IllegalArgumentException("attributes must not be empty");
        }
        return new InstanceDataLayout(name, VertexLayout.interleaved(strideBytes, attributes), false);
    }

    public String name() {
        return name;
    }

    public int strideBytes() {
        return vertexLayout.strideBytes();
    }

    public List<VertexAttribute> attributes() {
        return vertexLayout.attributes();
    }

    public VertexLayout vertexLayout() {
        return vertexLayout;
    }

    public boolean supportsMatrixTransforms() {
        return matrixTransform;
    }
}
