package com.kaleblangley.haikalat.core.assets.gltf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.util.Objects;

/** CPU-only STB decoder used before a serialized scene reaches the GL thread. */
public final class GltfImageDecoder {
    private static final long MAX_DECODED_BYTES = 256L * 1024L * 1024L;

    private GltfImageDecoder() {}

    public static GltfImageData decodeRgba8(byte[] encoded, boolean flipVertically) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0) {
            throw new IllegalArgumentException("encoded image must not be empty");
        }
        final BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(encoded));
        } catch (IOException failure) {
            throw new IllegalArgumentException("failed to decode image", failure);
        }
        if (image == null) {
            throw new IllegalArgumentException("unsupported or corrupt image");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        long decodedBytes = (long) width * height * 4L;
        if (decodedBytes > MAX_DECODED_BYTES) {
            throw new IllegalArgumentException("decoded image exceeds "
                    + MAX_DECODED_BYTES + " bytes");
        }
        int length;
        try {
            length = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("image dimensions overflow RGBA8 payload", overflow);
        }
        byte[] rgba = new byte[length];
        for (int y = 0; y < height; y++) {
            int destinationY = flipVertically ? height - 1 - y : y;
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int offset = (destinationY * width + x) * 4;
                rgba[offset] = (byte) ((argb >>> 16) & 0xff);
                rgba[offset + 1] = (byte) ((argb >>> 8) & 0xff);
                rgba[offset + 2] = (byte) (argb & 0xff);
                rgba[offset + 3] = (byte) ((argb >>> 24) & 0xff);
            }
        }
        return new GltfImageData(width, height, rgba);
    }
}
