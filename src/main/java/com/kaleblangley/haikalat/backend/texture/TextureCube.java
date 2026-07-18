package com.kaleblangley.haikalat.backend.texture;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlFormats;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL13.GL_TEXTURE_CUBE_MAP;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;

/** 具有不可变 mip storage 的真实 OpenGL cubemap 资源。 */
public final class TextureCube implements GlResource {
    private final int id;
    private final int size;
    private final int mipLevels;
    private final RenderFormat format;
    private final long resourceSequence;
    private boolean closed;

    private TextureCube(int id, int size, int mipLevels, RenderFormat format) {
        this.id = id;
        this.size = size;
        this.mipLevels = mipLevels;
        this.format = format;
        this.resourceSequence = GlDebug.trackResource("TEXTURE", id,
                "TextureCube " + size + "x" + size + " mips=" + mipLevels,
                estimateBytes(size, mipLevels));
        GlDebug.labelObject(org.lwjgl.opengl.GL43.GL_TEXTURE, id,
                "TextureCube " + size + "x" + size + " mips=" + mipLevels);
    }

    public static TextureCube create(int size, int mipLevels, RenderFormat format) {
        Objects.requireNonNull(format, "format");
        if (size <= 0) throw new IllegalArgumentException("cubemap size must be positive");
        int maximumMips = maximumMipLevels(size);
        if (mipLevels <= 0 || mipLevels > maximumMips) {
            throw new IllegalArgumentException("cubemap mip levels must be within [1, "
                    + maximumMips + "]");
        }
        if (format != RenderFormat.RGBA16F) {
            throw new IllegalArgumentException("v0.12 cubemap storage requires RGBA16F");
        }
        int texture = glCreateTextures(GL_TEXTURE_CUBE_MAP);
        try {
            glTextureStorage2D(texture, mipLevels, GlFormats.toGl(format), size, size);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER,
                    mipLevels > 1 ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
            glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            TextureCube result = new TextureCube(texture, size, mipLevels, format);
            texture = 0;
            return result;
        } finally {
            if (texture != 0) glDeleteTextures(texture);
        }
    }

    public static int maximumMipLevels(int size) {
        if (size <= 0) throw new IllegalArgumentException("cubemap size must be positive");
        return Integer.SIZE - Integer.numberOfLeadingZeros(size);
    }

    public int size() { return size; }
    public int mipLevels() { return mipLevels; }
    public RenderFormat format() { return format; }

    public void ensureOpen() {
        if (closed) throw new GlException("TextureCube is closed");
    }

    @Override public int id() { return id; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        glDeleteTextures(id);
        GlDebug.closeResource(resourceSequence);
        closed = true;
    }

    private static long estimateBytes(int size, int mipLevels) {
        long pixels = 0L;
        int dimension = size;
        for (int mip = 0; mip < mipLevels; mip++) {
            pixels += (long) dimension * dimension * 6L;
            dimension = Math.max(1, dimension / 2);
        }
        return pixels * 8L;
    }
}
