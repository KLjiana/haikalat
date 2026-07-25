package com.kaleblangley.haikalat.subsystems.resources;

/** Monotonic version of one logical resource identity. */
public record ResourceGeneration(long value) implements Comparable<ResourceGeneration> {
    public static final ResourceGeneration INITIAL = new ResourceGeneration(0L);

    public ResourceGeneration {
        if (value < 0L) {
            throw new IllegalArgumentException("value must be non-negative");
        }
    }

    public ResourceGeneration next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("resource generation exhausted");
        }
        return new ResourceGeneration(value + 1L);
    }

    @Override
    public int compareTo(ResourceGeneration other) {
        return Long.compare(value, other.value);
    }
}
