package com.kaleblangley.haikalat.core.device;

import java.util.Objects;

public record BufferDescriptor(BufferType type, BufferUsage usage, long sizeBytes) {
    public BufferDescriptor {
        type = Objects.requireNonNull(type, "type");
        usage = Objects.requireNonNull(usage, "usage");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
    }

    public static BufferDescriptor vertex(long sizeBytes, BufferUsage usage) {
        return new BufferDescriptor(BufferType.VERTEX, usage, sizeBytes);
    }

    public static BufferDescriptor index(long sizeBytes, BufferUsage usage) {
        return new BufferDescriptor(BufferType.INDEX, usage, sizeBytes);
    }

    public static BufferDescriptor uniform(long sizeBytes, BufferUsage usage) {
        return new BufferDescriptor(BufferType.UNIFORM, usage, sizeBytes);
    }
}
