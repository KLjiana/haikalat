package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.util.DirectBuffers;

import static org.lwjgl.opengl.GL11.GL_REPEAT;

/**
 * Renderer-owned deterministic linear noise texture shared by UV distortion and
 * dissolve/erosion.
 *
 * <p>The pattern is generated on the CPU, uploaded once as R8 linear data and
 * never re-uploaded for scalar author edits.  Material seeds offset the sampled
 * coordinates; they do not create one texture per material, which keeps the
 * resource set bounded.</p>
 */
final class VfxNoiseTexture implements AutoCloseable {
    static final int SIZE = 128;
    private static final long PATTERN_SEED = 0x4841494b414c4154L;

    private final Texture2D texture;

    VfxNoiseTexture() {
        texture = Texture2D.fromR8(SIZE, SIZE, DirectBuffers.copyOf(generate()))
                .setWrap(GL_REPEAT, GL_REPEAT);
    }

    Texture2D texture() {
        return texture;
    }

    @Override
    public void close() {
        texture.close();
    }

    /** Value-noise with four octaves, quantized to the full R8 range. */
    static byte[] generate() {
        byte[] pixels = new byte[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float value = 0.0f;
                float amplitude = 0.5f;
                float frequency = 4.0f;
                for (int octave = 0; octave < 4; octave++) {
                    value += amplitude * valueNoise(x * frequency / SIZE,
                            y * frequency / SIZE, PATTERN_SEED + octave * 0x9e3779b9L);
                    amplitude *= 0.5f;
                    frequency *= 2.0f;
                }
                int quantized = Math.max(0, Math.min(255, Math.round(value * 255.0f)));
                pixels[y * SIZE + x] = (byte) quantized;
            }
        }
        return pixels;
    }

    private static float valueNoise(float x, float y, long seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float sx = fx * fx * (3.0f - 2.0f * fx);
        float sy = fy * fy * (3.0f - 2.0f * fy);
        float top = lerp(hash(x0, y0, seed), hash(x0 + 1, y0, seed), sx);
        float bottom = lerp(hash(x0, y0 + 1, seed), hash(x0 + 1, y0 + 1, seed), sx);
        return lerp(top, bottom, sy);
    }

    private static float hash(int x, int y, long seed) {
        long value = seed;
        value ^= (long) x * 0x9e3779b97f4a7c15L;
        value ^= (long) y * 0xc2b2ae3d27d4eb4fL;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 40) / (float) (1 << 24);
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }
}
