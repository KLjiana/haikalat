package com.kaleblangley.haikalat.backend.texture;

import com.kaleblangley.haikalat.backend.GlCapabilityContract;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlFormats;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;

import java.util.Objects;

import static org.lwjgl.opengl.GL46.*;

/** Single-level immutable 3D storage. Scene policy and allocation budgets belong to its owner. */
public final class Texture3D implements GlResource {
    private final int id;
    private final int width;
    private final int height;
    private final int depth;
    private final RenderFormat format;
    private final long estimatedBytes;
    private final long resourceSequence;
    private boolean closed;

    private Texture3D(int id, int width, int height, int depth, RenderFormat format, long bytes) {
        this.id = id; this.width = width; this.height = height; this.depth = depth;
        this.format = format; this.estimatedBytes = bytes;
        String label = "Texture3D " + width + "x" + height + "x" + depth + " " + format;
        GlDebug.labelObject(GL_TEXTURE, id, label);
        resourceSequence = GlDebug.trackResource("TEXTURE", id, label, bytes);
    }

    public static Texture3D create(int width, int height, int depth, RenderFormat format) {
        long bytes = estimatedByteSize(width, height, depth, format);
        GlCapabilityContract.requireCurrent();
        int maximum = glGetInteger(GL_MAX_3D_TEXTURE_SIZE);
        if (width > maximum || height > maximum || depth > maximum) {
            throw new IllegalArgumentException("3D texture dimensions exceed device limit " + maximum);
        }
        int texture = glCreateTextures(GL_TEXTURE_3D);
        try {
            glTextureStorage3D(texture, 1, GlFormats.toGl(format), width, height, depth);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
            glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTextureParameteri(texture, GL_TEXTURE_BASE_LEVEL, 0);
            glTextureParameteri(texture, GL_TEXTURE_MAX_LEVEL, 0);
            GlDebug.assertNoError("Texture3D allocation");
            if (glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_WIDTH) != width
                    || glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_HEIGHT) != height
                    || glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_DEPTH) != depth
                    || glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_INTERNAL_FORMAT) != GlFormats.toGl(format)) {
                throw new GlException("Texture3D storage allocation was not completed");
            }
            Texture3D result = new Texture3D(texture, width, height, depth, format, bytes);
            texture = 0;
            return result;
        } finally {
            if (texture != 0) {
                glDeleteTextures(texture);
                GlDebug.closeResource(-1L);
            }
        }
    }

    /** Validates format/dimensions without requiring an OpenGL context. */
    public static long estimatedByteSize(int width, int height, int depth, RenderFormat format) {
        Objects.requireNonNull(format, "format");
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("3D texture dimensions must be positive");
        }
        int texelBytes = switch (format) {
            case RGBA16F -> 8;
            case RGBA32F -> 16;
            case R8 -> 1;
            default -> throw new IllegalArgumentException("3D image storage supports RGBA16F, RGBA32F or R8, got " + format);
        };
        try {
            return Math.multiplyExact(Math.multiplyExact(Math.multiplyExact((long) width, height), depth), texelBytes);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("3D texture byte size overflows", failure);
        }
    }

    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int mipLevels() { return 1; }
    public RenderFormat format() { return format; }
    public long estimatedBytes() { return estimatedBytes; }
    /** Explicit synchronous 2D slice export; preserves host pack state and never exposes a 3D ID to a previewer. */
    public float[] readLayerRgbaFloat(int layer) {
        ensureOpen();
        if (layer < 0 || layer >= depth) throw new IllegalArgumentException("3D layer outside storage");
        float[] pixels=new float[Math.multiplyExact(Math.multiplyExact(width,height),4)];
        int pbo=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] names={GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_IMAGE_HEIGHT,
                GL_PACK_SKIP_PIXELS,GL_PACK_SKIP_ROWS,GL_PACK_SKIP_IMAGES,GL_PACK_SWAP_BYTES,GL_PACK_LSB_FIRST};
        int[] previous=new int[names.length];for(int i=0;i<names.length;i++) previous[i]=glGetInteger(names[i]);
        try {
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            for(int i=0;i<names.length;i++) glPixelStorei(names[i],i==0?1:0);
            glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
            glGetTextureSubImage(id,0,0,0,layer,width,height,1,GL_RGBA,GL_FLOAT,pixels);
            GlDebug.assertNoError("Texture3D slice readback");
            return pixels;
        } finally {
            for(int i=0;i<names.length;i++) glPixelStorei(names[i],previous[i]);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,pbo);
        }
    }
    public void ensureOpen() { if (closed) throw new GlException("Texture3D is closed"); }
    @Override public int id() { return id; }
    @Override public boolean isClosed() { return closed; }

    @Override public void close() {
        if (closed) return;
        glDeleteTextures(id);
        GlDebug.closeResource(resourceSequence);
        closed = true;
    }
}
