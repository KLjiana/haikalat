package com.kaleblangley.haikalat.backend.vertex;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

public final class VertexAttribute {
    private final int index;
    private final int size;
    private final int type;
    private final boolean normalized;
    private final long offsetBytes;
    private final int divisor;
    private final VertexSemantic semantic;

    private VertexAttribute(Builder builder) {
        if (builder.index < 0) throw new IllegalArgumentException("attribute index must be non-negative");
        if (builder.size < 1 || builder.size > 4) {
            throw new IllegalArgumentException("attribute size must be between 1 and 4");
        }
        if (builder.offsetBytes < 0L) throw new IllegalArgumentException("attribute offset must be non-negative");
        if (builder.divisor < 0) throw new IllegalArgumentException("attribute divisor must be non-negative");
        this.index = builder.index;
        this.size = builder.size;
        this.type = builder.type;
        this.normalized = builder.normalized;
        this.offsetBytes = builder.offsetBytes;
        this.divisor = builder.divisor;
        this.semantic = Objects.requireNonNull(builder.semantic, "semantic");
    }

    public static Builder builder() { return new Builder(); }
    public int index() { return index; }
    public int size() { return size; }
    public int type() { return type; }
    public boolean normalized() { return normalized; }
    public long offsetBytes() { return offsetBytes; }
    public int divisor() { return divisor; }
    public VertexSemantic semantic() { return semantic; }

    public static final class Builder {
        private int index;
        private int size;
        private int type = GL_FLOAT;
        private boolean normalized;
        private long offsetBytes;
        private int divisor;
        private VertexSemantic semantic = VertexSemantic.CUSTOM;

        private Builder() {}

        public Builder index(int value) { index = value; return this; }
        public Builder size(int value) { size = value; return this; }
        public Builder type(int value) { type = value; return this; }
        public Builder normalized(boolean value) { normalized = value; return this; }
        public Builder offsetBytes(long value) { offsetBytes = value; return this; }
        public Builder divisor(int value) { divisor = value; return this; }
        public Builder semantic(VertexSemantic value) { semantic = value; return this; }
        public VertexAttribute build() { return new VertexAttribute(this); }
    }
}
