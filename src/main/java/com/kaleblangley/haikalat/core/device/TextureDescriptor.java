package com.kaleblangley.haikalat.core.device;

import java.util.Objects;

public record TextureDescriptor(int width, int height, RenderFormat format, boolean mipmapped) {
    public TextureDescriptor {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        format = Objects.requireNonNull(format, "format");
    }

    public static TextureDescriptor color(int width, int height, RenderFormat format) {
        return new TextureDescriptor(width, height, format, false);
    }
}
