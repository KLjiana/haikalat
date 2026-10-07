package com.kaleblangley.haikalat.backend.texture;

import com.kaleblangley.haikalat.backend.RenderFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Texture3DTest {
    @Test
    void invalidAndOverflowingStorageIsRejectedBeforeAnyGlAllocation() {
        assertThrows(IllegalArgumentException.class, () -> Texture3D.create(0, 2, 3, RenderFormat.RGBA16F));
        assertThrows(IllegalArgumentException.class, () -> Texture3D.create(2, -1, 3, RenderFormat.R8));
        assertThrows(IllegalArgumentException.class, () -> Texture3D.create(2, 3, 0, RenderFormat.R8));
        assertThrows(IllegalArgumentException.class, () -> Texture3D.create(2, 3, 4, RenderFormat.DEPTH_COMPONENT24));
        assertThrows(IllegalArgumentException.class, () -> Texture3D.create(2, 3, 4, RenderFormat.SRGB8_ALPHA8));
        assertThrows(IllegalArgumentException.class,
                () -> Texture3D.create(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, RenderFormat.RGBA16F));
        assertEquals(840L, Texture3D.estimatedByteSize(5, 3, 7, RenderFormat.RGBA16F));
        assertEquals(1680L, Texture3D.estimatedByteSize(5, 3, 7, RenderFormat.RGBA32F));
        assertThrows(IllegalArgumentException.class,
                () -> Texture3D.create(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, RenderFormat.RGBA32F));
        assertEquals(105L, Texture3D.estimatedByteSize(5, 3, 7, RenderFormat.R8));
    }
}
