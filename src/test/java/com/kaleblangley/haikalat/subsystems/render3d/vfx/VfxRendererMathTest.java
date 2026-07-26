package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VfxRendererMathTest {
    @Test
    void ribbonJoinOffsetCreatesOneSharedMiterForBothSegments() {
        Vector3f offset = VfxRenderer.ribbonJoinOffset(
                new Vector3f(0.0f, 1.0f, 0.0f),
                new Vector3f(1.0f, 0.0f, 0.0f), 0.2f, new Vector3f());
        assertEquals(0.1f, offset.x, 1.0e-6f);
        assertEquals(0.1f, offset.y, 1.0e-6f);
        assertEquals(0.0f, offset.z, 1.0e-6f);
    }

    @Test
    void ribbonProgressUsesWholeTrailLengthAndTextureProgressRunsTowardTheTail() {
        assertEquals(0.0f, VfxRenderer.ribbonProgress(-0.1f, 4.0f), 1.0e-6f);
        assertEquals(0.25f, VfxRenderer.ribbonProgress(1.0f, 4.0f), 1.0e-6f);
        assertEquals(1.0f, VfxRenderer.ribbonProgress(4.1f, 4.0f), 1.0e-6f);
        assertEquals(0.0f, VfxRenderer.ribbonProgress(0.0f, 0.0f), 1.0e-6f);
        assertEquals(1.0f, VfxRenderer.ribbonTextureProgress(0.0f, 4.0f), 1.0e-6f);
        assertEquals(0.75f, VfxRenderer.ribbonTextureProgress(1.0f, 4.0f), 1.0e-6f);
        assertEquals(0.0f, VfxRenderer.ribbonTextureProgress(4.0f, 4.0f), 1.0e-6f);
    }
}
