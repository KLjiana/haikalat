package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaskMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.util.DirectBuffers;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/** Renderer-owned synchronous cache for VFX masks and color textures. */
final class VfxTextureCache implements AutoCloseable {
    private static final int MAXIMUM_DIMENSION = 4096;

    private final Map<Key, Texture2D> textures = new LinkedHashMap<>();
    private boolean closed;

    Texture2D get(VfxMaterial material) {
        ensureOpen();
        Objects.requireNonNull(material, "material");
        Key key = new Key(material.texture().orElse(null), material.maskMode());
        return textures.computeIfAbsent(key, this::load);
    }

    int size() {
        return textures.size();
    }

    private Texture2D load(Key key) {
        if (key.mode == VfxMaskMode.WHITE) {
            ByteBuffer white = DirectBuffers.copyOf(new byte[]{(byte) 255});
            return Texture2D.fromR8(1, 1, white);
        }
        if (key.ref == null) throw new IllegalArgumentException("Textured VFX material has no asset ref");
        byte[] encoded = DirectBuffers.readResourceBytes(VfxTextureCache.class, key.ref.path());
        if (key.mode == VfxMaskMode.RGBA_COLOR) {
            return Texture2D.fromEncoded(encoded, true, TextureColorSpace.SRGB)
                    .setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);
        }
        return decodeMask(encoded, key);
    }

    private static Texture2D decodeMask(byte[] encoded, Key key) {
        ByteBuffer input = DirectBuffers.copyOf(encoded);
        STBImage.stbi_set_flip_vertically_on_load_thread(1);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuffer = stack.mallocInt(1);
            IntBuffer heightBuffer = stack.mallocInt(1);
            IntBuffer channelsBuffer = stack.mallocInt(1);
            ByteBuffer rgba = STBImage.stbi_load_from_memory(
                    input, widthBuffer, heightBuffer, channelsBuffer, 4);
            if (rgba == null) {
                throw new GlException("Failed to decode VFX texture " + key.ref.path() + ": "
                        + STBImage.stbi_failure_reason());
            }
            try {
                int width = widthBuffer.get(0);
                int height = heightBuffer.get(0);
                if (width <= 0 || height <= 0 || width > MAXIMUM_DIMENSION
                        || height > MAXIMUM_DIMENSION) {
                    throw new GlException("Invalid VFX texture dimensions " + width + "x" + height
                            + " for " + key.ref.path());
                }
                ByteBuffer mask = ByteBuffer.allocateDirect(Math.multiplyExact(width, height));
                for (int pixel = 0; pixel < width * height; pixel++) {
                    int offset = pixel * 4;
                    int red = Byte.toUnsignedInt(rgba.get(offset));
                    int green = Byte.toUnsignedInt(rgba.get(offset + 1));
                    int blue = Byte.toUnsignedInt(rgba.get(offset + 2));
                    int alpha = Byte.toUnsignedInt(rgba.get(offset + 3));
                    int value = switch (key.mode) {
                        case ALPHA -> alpha;
                        case RED -> red;
                        case LUMINANCE -> (54 * red + 183 * green + 19 * blue + 128) >> 8;
                        case WHITE, RGBA_COLOR -> throw new IllegalStateException(
                                "Mask decode does not support " + key.mode);
                    };
                    mask.put((byte) value);
                }
                mask.flip();
                return Texture2D.fromR8(width, height, mask);
            } finally {
                STBImage.stbi_image_free(rgba);
            }
        } finally {
            STBImage.stbi_set_flip_vertically_on_load_thread(0);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        for (Texture2D texture : textures.values()) {
            try {
                texture.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        textures.clear();
        closed = true;
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("VfxTextureCache is closed");
    }

    private record Key(AssetRef ref, VfxMaskMode mode) {
        private Key {
            mode = Objects.requireNonNull(mode, "mode");
            if (mode != VfxMaskMode.WHITE) ref = Objects.requireNonNull(ref, "ref");
        }
    }
}
