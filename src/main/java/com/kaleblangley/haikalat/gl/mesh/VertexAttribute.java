package com.kaleblangley.haikalat.gl.mesh;

public final class VertexAttribute {
    private final int index;
    private final int size;
    private final int type;
    private final boolean normalized;
    private final long offsetBytes;
    private final int divisor;

    private VertexAttribute(Builder builder) {
        this.index = builder.index;
        this.size = builder.size;
        this.type = builder.type;
        this.normalized = builder.normalized;
        this.offsetBytes = builder.offsetBytes;
        this.divisor = builder.divisor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int index() {
        return index;
    }

    public int size() {
        return size;
    }

    public int type() {
        return type;
    }

    public boolean normalized() {
        return normalized;
    }

    public long offsetBytes() {
        return offsetBytes;
    }

    public int divisor() {
        return divisor;
    }

    public static final class Builder {
        private int index;
        private int size;
        private int type;
        private boolean normalized;
        private long offsetBytes;
        private int divisor;

        private Builder() {
        }

        public Builder index(int value) {
            this.index = value;
            return this;
        }

        public Builder size(int value) {
            this.size = value;
            return this;
        }

        public Builder type(int value) {
            this.type = value;
            return this;
        }

        public Builder normalized(boolean value) {
            this.normalized = value;
            return this;
        }

        public Builder offsetBytes(long value) {
            this.offsetBytes = value;
            return this;
        }

        public Builder divisor(int value) {
            this.divisor = value;
            return this;
        }

        public VertexAttribute build() {
            return new VertexAttribute(this);
        }
    }
}
