package com.kaleblangley.haikalat.util;

import com.kaleblangley.haikalat.gl.GlException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class DirectBuffers {
    private DirectBuffers() {
    }

    public static ByteBuffer copyOf(byte[] data) {
        Objects.requireNonNull(data, "data");
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    public static FloatBuffer copyOf(float[] data) {
        Objects.requireNonNull(data, "data");
        ByteBuffer bytes = ByteBuffer.allocateDirect(data.length * Float.BYTES).order(ByteOrder.nativeOrder());
        FloatBuffer buffer = bytes.asFloatBuffer();
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    public static IntBuffer copyOf(int[] data) {
        Objects.requireNonNull(data, "data");
        ByteBuffer bytes = ByteBuffer.allocateDirect(data.length * Integer.BYTES).order(ByteOrder.nativeOrder());
        IntBuffer buffer = bytes.asIntBuffer();
        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    public static byte[] readResourceBytes(Class<?> anchor, String resourcePath) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream inputStream = openResource(anchor, resourcePath)) {
            if (inputStream == null) {
                throw new GlException("Resource not found: " + resourcePath);
            }
            return inputStream.readAllBytes();
        } catch (IOException e) {
            throw new GlException("Failed to read resource: " + resourcePath, e);
        }
    }

    public static String readResourceString(Class<?> anchor, String resourcePath) {
        return new String(readResourceBytes(anchor, resourcePath), StandardCharsets.UTF_8);
    }

    private static InputStream openResource(Class<?> anchor, String resourcePath) {
        if (resourcePath.startsWith("/")) {
            return anchor.getResourceAsStream(resourcePath);
        }
        return anchor.getResourceAsStream(resourcePath);
    }
}
