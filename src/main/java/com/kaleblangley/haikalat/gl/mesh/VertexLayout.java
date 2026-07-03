package com.kaleblangley.haikalat.gl.mesh;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

public final class VertexLayout {
    private final int strideBytes;
    private final List<VertexAttribute> attributes;

    private VertexLayout(int strideBytes, List<VertexAttribute> attributes) {
        if (strideBytes <= 0) {
            throw new IllegalArgumentException("strideBytes must be positive");
        }
        this.strideBytes = strideBytes;
        this.attributes = List.copyOf(attributes);
    }

    public static VertexLayout interleaved(int strideBytes, VertexAttribute... attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return new VertexLayout(strideBytes, Arrays.asList(attributes));
    }

    public static VertexLayout instanceMatrix(int baseLocation) {
        return interleaved(
                16 * Float.BYTES,
                VertexAttribute.builder().index(baseLocation).size(4).type(GL_FLOAT).offsetBytes(0L).divisor(1).build(),
                VertexAttribute.builder().index(baseLocation + 1).size(4).type(GL_FLOAT).offsetBytes(4L * Float.BYTES).divisor(1).build(),
                VertexAttribute.builder().index(baseLocation + 2).size(4).type(GL_FLOAT).offsetBytes(8L * Float.BYTES).divisor(1).build(),
                VertexAttribute.builder().index(baseLocation + 3).size(4).type(GL_FLOAT).offsetBytes(12L * Float.BYTES).divisor(1).build()
        );
    }

    public int strideBytes() {
        return strideBytes;
    }

    public List<VertexAttribute> attributes() {
        return attributes;
    }

    public void apply() {
        for (VertexAttribute attribute : attributes) {
            glVertexAttribPointer(
                    attribute.index(),
                    attribute.size(),
                    attribute.type(),
                    attribute.normalized(),
                    strideBytes,
                    attribute.offsetBytes()
            );
            glEnableVertexAttribArray(attribute.index());
            if (attribute.divisor() != 0) {
                glVertexAttribDivisor(attribute.index(), attribute.divisor());
            }
        }
    }
}
