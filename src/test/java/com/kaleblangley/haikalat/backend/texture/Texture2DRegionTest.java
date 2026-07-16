package com.kaleblangley.haikalat.backend.texture;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RG8;

class Texture2DRegionTest {
    @Test
    void computesTightlyPackedByteCountsForSupportedFormats() {
        Texture2D r8 = texture(8, 6, GL_R8);
        Texture2D rg8 = texture(8, 6, GL_RG8);
        Texture2D rgba8 = texture(8, 6, GL_RGBA8);

        assertEquals(12, r8.requiredRegionBytes(1, 1, 4, 3));
        assertEquals(24, rg8.requiredRegionBytes(1, 1, 4, 3));
        assertEquals(48, rgba8.requiredRegionBytes(1, 1, 4, 3));
        assertEquals(0, r8.requiredRegionBytes(8, 6, 0, 0));
    }

    @Test
    void validatesRegionBoundsBeforeAnyGlCall() {
        Texture2D texture = texture(8, 6, GL_R8);

        assertThrows(IllegalArgumentException.class,
                () -> texture.requiredRegionBytes(-1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> texture.requiredRegionBytes(0, -1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> texture.requiredRegionBytes(7, 0, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> texture.requiredRegionBytes(0, 5, 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> texture.requiredRegionBytes(0, 0, -1, 1));
    }

    @Test
    void rejectsUnsupportedDynamicFormat() {
        Texture2D unsupported = texture(4, 4, 0x12345678);

        assertThrows(IllegalArgumentException.class,
                () -> unsupported.requiredRegionBytes(0, 0, 1, 1));
    }

    @Test
    void emptyStorageRejectsInvalidDimensionsAndUnsizedFormatsBeforeGlCall() {
        assertThrows(IllegalArgumentException.class,
                () -> Texture2D.createEmpty(0, 1, GL_R8));
        assertThrows(IllegalArgumentException.class,
                () -> Texture2D.createEmpty(1, 1, GL_RED));
    }

    private static Texture2D texture(int width, int height, int format) {
        try {
            Constructor<Texture2D> constructor = Texture2D.class.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(0, width, height, format);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
