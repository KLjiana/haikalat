package com.kaleblangley.haikalat.backend.vertex;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_BYTE;
import static org.lwjgl.opengl.GL11.GL_DOUBLE;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_INT;
import static org.lwjgl.opengl.GL11.GL_SHORT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL30.GL_HALF_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/** 描述 OpenGL 顶点输入布局及其应用逻辑。 */
public final class VertexLayout {
    private final int strideBytes;
    private final List<VertexAttribute> attributes;

    private VertexLayout(int strideBytes, List<VertexAttribute> attributes) {
        if (strideBytes <= 0) throw new IllegalArgumentException("strideBytes must be positive");
        this.strideBytes = strideBytes;
        this.attributes = List.copyOf(attributes);
        validate();
    }

    public static VertexLayout interleaved(int strideBytes, VertexAttribute... attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return new VertexLayout(strideBytes, Arrays.asList(attributes));
    }

    public static VertexLayout instanceMatrix(int baseLocation) {
        return interleaved(16 * Float.BYTES,
                VertexAttribute.builder().index(baseLocation).size(4).type(GL_FLOAT).offsetBytes(0L).divisor(1).build(),
                VertexAttribute.builder().index(baseLocation + 1).size(4).type(GL_FLOAT).offsetBytes(4L * Float.BYTES).divisor(1).build(),
                VertexAttribute.builder().index(baseLocation + 2).size(4).type(GL_FLOAT).offsetBytes(8L * Float.BYTES).divisor(1).build(),
                VertexAttribute.builder().index(baseLocation + 3).size(4).type(GL_FLOAT).offsetBytes(12L * Float.BYTES).divisor(1).build());
    }

    public int strideBytes() { return strideBytes; }
    public List<VertexAttribute> attributes() { return attributes; }

    public Optional<VertexAttribute> attribute(VertexSemantic semantic) {
        Objects.requireNonNull(semantic, "semantic");
        if (semantic == VertexSemantic.CUSTOM) {
            throw new IllegalArgumentException("CUSTOM semantic is not unique; inspect attributes() instead");
        }
        return attributes.stream().filter(attribute -> attribute.semantic() == semantic).findFirst();
    }

    public Set<Integer> locations() {
        HashSet<Integer> result = new HashSet<>();
        for (VertexAttribute attribute : attributes) result.add(attribute.index());
        return Set.copyOf(result);
    }

    public void validateNoLocationOverlap(VertexLayout other, String usage) {
        Objects.requireNonNull(other, "other");
        HashSet<Integer> overlap = new HashSet<>(locations());
        overlap.retainAll(other.locations());
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(usage + " attribute locations overlap: " + overlap);
        }
    }

    public void apply() {
        for (VertexAttribute attribute : attributes) {
            glVertexAttribPointer(attribute.index(), attribute.size(), attribute.type(),
                    attribute.normalized(), strideBytes, attribute.offsetBytes());
            glEnableVertexAttribArray(attribute.index());
            if (attribute.divisor() != 0) glVertexAttribDivisor(attribute.index(), attribute.divisor());
        }
    }

    private void validate() {
        HashSet<Integer> locations = new HashSet<>();
        HashSet<VertexSemantic> semantics = new HashSet<>();
        for (VertexAttribute attribute : attributes) {
            Objects.requireNonNull(attribute, "attribute");
            if (!locations.add(attribute.index())) {
                throw new IllegalArgumentException("duplicate vertex attribute location " + attribute.index());
            }
            if (attribute.semantic() != VertexSemantic.CUSTOM && !semantics.add(attribute.semantic())) {
                throw new IllegalArgumentException("duplicate vertex semantic " + attribute.semantic());
            }
            long end = Math.addExact(attribute.offsetBytes(),
                    Math.multiplyExact((long) attribute.size(), componentBytes(attribute.type())));
            if (end > strideBytes) {
                throw new IllegalArgumentException("attribute " + attribute.index()
                        + " exceeds vertex stride " + strideBytes);
            }
        }
    }

    private static int componentBytes(int type) {
        return switch (type) {
            case GL_BYTE, GL_UNSIGNED_BYTE -> Byte.BYTES;
            case GL_SHORT, GL_UNSIGNED_SHORT, GL_HALF_FLOAT -> Short.BYTES;
            case GL_INT, GL_UNSIGNED_INT, GL_FLOAT -> Integer.BYTES;
            case GL_DOUBLE -> Double.BYTES;
            default -> throw new IllegalArgumentException("unsupported vertex attribute type " + type);
        };
    }
}
