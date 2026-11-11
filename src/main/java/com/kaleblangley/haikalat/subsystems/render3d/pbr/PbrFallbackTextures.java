package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL11.GL_RGBA8;

/** 一组共享的 1×1 PBR 缺省纹理及 sampler，生命周期由应用显式拥有。 */
public final class PbrFallbackTextures implements AutoCloseable {
    /**
     * Red channel of the shared height fallback. Kept at zero so a missing
     * height map is a flat surface.
     */
    static final int HEIGHT_FALLBACK_RED = 0;
    /** Green channel of the shared height fallback; zero like {@link #HEIGHT_FALLBACK_RED}. */
    static final int HEIGHT_FALLBACK_GREEN = 0;
    /** Blue channel of the shared height fallback; zero like {@link #HEIGHT_FALLBACK_RED}. */
    static final int HEIGHT_FALLBACK_BLUE = 0;
    /**
     * Alpha channel of the shared height fallback. It must be zero as well:
     * {@code HeightChannel.ALPHA} samples {@code .a}, so a 255 alpha fallback
     * would report the maximum height and move UVs when no map is supplied.
     * A non-zero height scale with this zero fallback and no
     * {@code flipHeight} therefore keeps the original UV exactly.
     */
    static final int HEIGHT_FALLBACK_ALPHA = 0;

    private final Map<PbrTextureRole, Texture2D> textures = new EnumMap<>(PbrTextureRole.class);
    private final Sampler sampler;
    private boolean closed;

    public PbrFallbackTextures() {
        sampler = Sampler.create(new Sampler.Descriptor(GL_LINEAR, GL_LINEAR,
                GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
        try {
            textures.put(PbrTextureRole.BASE_COLOR, pixel(GL_SRGB8_ALPHA8, 255, 255, 255, 255));
            textures.put(PbrTextureRole.NORMAL, pixel(GL_RGBA8, 128, 128, 255, 255));
            textures.put(PbrTextureRole.METALLIC_ROUGHNESS, pixel(GL_RGBA8, 0, 255, 0, 255));
            textures.put(PbrTextureRole.OCCLUSION, pixel(GL_RGBA8, 255, 255, 255, 255));
            // glTF semantics: emissive = emissiveFactor * emissiveTexture.  A
            // white fallback keeps emissiveFactor as the only authoring input,
            // so materials without an emissive map can still glow.
            textures.put(PbrTextureRole.EMISSIVE, pixel(GL_SRGB8_ALPHA8, 255, 255, 255, 255));
            // Height zero in every channel (including alpha) is flat: the
            // parallax march returns the interpolated UV for a missing map.
            // A flipped zero fallback is intentionally not flat; see
            // ParallaxOcclusionSettings.flipHeight.
            textures.put(PbrTextureRole.HEIGHT, pixel(GL_RGBA8, HEIGHT_FALLBACK_RED,
                    HEIGHT_FALLBACK_GREEN, HEIGHT_FALLBACK_BLUE, HEIGHT_FALLBACK_ALPHA));
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public Texture2D texture(PbrTextureRole role) {
        if (closed) throw new IllegalStateException("PBR fallback textures are closed");
        return textures.get(java.util.Objects.requireNonNull(role, "role"));
    }

    public Sampler sampler() {
        if (closed) throw new IllegalStateException("PBR fallback textures are closed");
        return sampler;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Texture2D texture : textures.values()) {
            try { texture.close(); } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        textures.clear();
        try { sampler.close(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private static Texture2D pixel(int format, int r, int g, int b, int a) {
        Texture2D texture = Texture2D.createEmpty(1, 1, format);
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(4)
                    .put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
            texture.uploadRegion(0, 0, 1, 1, data);
            return texture;
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
    }
}
