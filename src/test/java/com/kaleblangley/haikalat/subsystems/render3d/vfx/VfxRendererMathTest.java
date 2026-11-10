package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void normalMatrixUsesInverseTransposeForNonUniformAndMirroredScales() {
        Matrix4f model = new Matrix4f().scale(2.0f, 4.0f, 8.0f);
        Matrix4f normal = new Matrix4f();
        Matrix3f scratch = new Matrix3f();
        assertTrue(VfxRenderer.normalMatrix(model, normal, scratch));
        Vector3f transformed = normal.transformDirection(
                new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f()).normalize();
        assertEquals(0.0f, transformed.x, 1.0e-6f);
        assertEquals(0.0f, transformed.y, 1.0e-6f);
        assertEquals(1.0f, transformed.z, 1.0e-6f);

        Matrix4f mirrored = new Matrix4f().scale(-1.0f, 2.0f, 3.0f);
        assertTrue(VfxRenderer.normalMatrix(mirrored, normal, scratch));
        Vector3f mirroredNormal = normal.transformDirection(
                new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f()).normalize();
        assertEquals(-1.0f, mirroredNormal.x, 1.0e-6f);
    }

    @Test
    void singularTransformSkipsRimInsteadOfProducingNaN() {
        Matrix4f singular = new Matrix4f().scale(0.0f, 1.0f, 1.0f);
        Matrix4f normal = new Matrix4f().translation(9.0f, 9.0f, 9.0f);
        assertFalse(VfxRenderer.normalMatrix(singular, normal, new Matrix3f()));
        assertEquals(new Matrix4f(), normal);
        float[] values = normal.get(new float[16]);
        for (float value : values) assertTrue(Float.isFinite(value));
    }

    @Test
    void shaderSeedOffsetsStayBoundedAndExactlyRepresentable() {
        assertEquals(0.0f, VfxRenderer.seedValue(65_536L));
        assertEquals(1.0f, VfxRenderer.seedValue(65_537L));
        assertEquals(65_535.0f, VfxRenderer.seedValue(-1L));
        assertEquals(123.0f, VfxRenderer.seedValue(123L));
    }

    @Test
    void deterministicNoiseTextureCoversTheLinearUnitRange() {
        byte[] noise = VfxNoiseTexture.generate();
        assertEquals(VfxNoiseTexture.SIZE * VfxNoiseTexture.SIZE, noise.length);
        int minimum = 255;
        int maximum = 0;
        long sum = 0L;
        for (byte value : noise) {
            int unsigned = Byte.toUnsignedInt(value);
            minimum = Math.min(minimum, unsigned);
            maximum = Math.max(maximum, unsigned);
            sum += unsigned;
        }
        assertTrue(maximum - minimum > 96,
                "dissolve thresholds need a wide noise range, got " + minimum + ".." + maximum);
        double mean = sum / (double) noise.length;
        assertTrue(mean > 32.0 && mean < 224.0, "noise mean must stay centred, got " + mean);
        assertArrayEquals(noise, VfxNoiseTexture.generate(),
                "noise generation must be deterministic");    }
}
