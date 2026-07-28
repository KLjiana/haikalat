package com.kaleblangley.haikalat.core.assets.gltf;

import java.util.Objects;

/** Immutable CPU-decoded RGBA8 image payload ready for a later GL upload. */
public record GltfImageData(int width, int height, byte[] rgba8) {
    public GltfImageData {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        int expected;
        try {
            expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("image dimensions overflow RGBA8 payload", overflow);
        }
        Objects.requireNonNull(rgba8, "rgba8");
        if (rgba8.length != expected) {
            throw new IllegalArgumentException("RGBA8 payload length must be " + expected
                    + ", got " + rgba8.length);
        }
        rgba8 = rgba8.clone();
    }

    @Override
    public byte[] rgba8() {
        return rgba8.clone();
    }
}
