package com.kaleblangley.haikalat.subsystems.postprocess.smaa;

/**
 * CPU reference for the SMAA 1x luma edge-detection pass.
 *
 * <p>Independent Java transcription of {@code SMAALumaEdgeDetectionPS}
 * (SMAA.hlsl, commit 71c806a, HIGH preset threshold 0.1, local contrast
 * factor 2.0).  It is test support: the real-GL test compares the shader's
 * edge mask against this map, and the JVM test pins the reference itself on
 * hand-derived fixtures so a broken oracle cannot accept wrong GPU output.</p>
 */
final class SmaaEdgeReference {
    static final float THRESHOLD = 0.1f;
    static final float LOCAL_CONTRAST_ADAPTATION_FACTOR = 2.0f;

    private SmaaEdgeReference() {
    }

    /**
     * @param rgba tightly packed RGBA8 source pixels, row-major bottom-up
     * @return RGBA8 edge mask with R/G set to 0 or 255
     */
    static byte[] detect(byte[] rgba, int width, int height) {
        if (rgba.length != width * height * 4) {
            throw new IllegalArgumentException("RGBA payload does not match extent");
        }
        byte[] edges = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float center = luma(rgba, width, height, x, y);
                float left = luma(rgba, width, height, x - 1, y);
                float top = luma(rgba, width, height, x, y - 1);
                float right = luma(rgba, width, height, x + 1, y);
                float bottom = luma(rgba, width, height, x, y + 1);

                float deltaX = Math.abs(center - left);
                float deltaY = Math.abs(center - top);
                float edgeX = deltaX >= THRESHOLD ? 1.0f : 0.0f;
                float edgeY = deltaY >= THRESHOLD ? 1.0f : 0.0f;
                if (edgeX == 0.0f && edgeY == 0.0f) {
                    continue;
                }

                float maxDeltaX = Math.max(deltaX, Math.abs(center - right));
                float maxDeltaY = Math.max(deltaY, Math.abs(center - bottom));
                float leftLeft = luma(rgba, width, height, x - 2, y);
                float topTop = luma(rgba, width, height, x, y - 2);
                maxDeltaX = Math.max(maxDeltaX, Math.abs(left - leftLeft));
                maxDeltaY = Math.max(maxDeltaY, Math.abs(top - topTop));
                float finalDelta = Math.max(maxDeltaX, maxDeltaY);

                if (edgeX != 0.0f
                        && LOCAL_CONTRAST_ADAPTATION_FACTOR * deltaX < finalDelta) {
                    edgeX = 0.0f;
                }
                if (edgeY != 0.0f
                        && LOCAL_CONTRAST_ADAPTATION_FACTOR * deltaY < finalDelta) {
                    edgeY = 0.0f;
                }
                int offset = (y * width + x) * 4;
                edges[offset] = (byte) (edgeX == 0.0f ? 0 : 255);
                edges[offset + 1] = (byte) (edgeY == 0.0f ? 0 : 255);
                edges[offset + 3] = (byte) 255;
            }
        }
        return edges;
    }

    private static float luma(byte[] rgba, int width, int height, int x, int y) {
        int clampedX = Math.max(0, Math.min(width - 1, x));
        int clampedY = Math.max(0, Math.min(height - 1, y));
        int offset = (clampedY * width + clampedX) * 4;
        float red = Byte.toUnsignedInt(rgba[offset]) / 255.0f;
        float green = Byte.toUnsignedInt(rgba[offset + 1]) / 255.0f;
        float blue = Byte.toUnsignedInt(rgba[offset + 2]) / 255.0f;
        return 0.2126f * red + 0.7152f * green + 0.0722f * blue;
    }
}
