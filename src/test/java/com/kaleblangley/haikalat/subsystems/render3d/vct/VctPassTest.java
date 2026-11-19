package com.kaleblangley.haikalat.subsystems.render3d.vct;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Disabled-path contracts that do not require an OpenGL context: a disabled
 * pass must allocate and record nothing, remain closeable/idempotent, and
 * accept settings transitions without touching the command buffer.
 */
class VctPassTest {
    @Test
    void disabledPassRecordsNothingAndClosesIdempotently() {
        CommandBuffer command = new CommandBuffer();
        VctSceneInput scene = VctSceneInput.builder(1L)
                .addBox(new Vector3f(-1.0f, -1.0f, -1.0f), new Vector3f(1.0f, 1.0f, 1.0f),
                        1.0f, 0.5f, 0.25f)
                .build();
        VctProbeBatch probes = VctProbeBatch.builder()
                .addProbe(new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(0.0f, 0.0f, 1.0f), 0.4f)
                .build();
        VctPass pass = new VctPass(VctSettings.disabled());
        try {
            assertFalse(pass.active());
            assertNull(pass.volumeTexture());
            assertEquals(0L, pass.volumeBytes());
            assertFalse(pass.volumeValid());
            assertEquals(VctSettings.disabled(), pass.settings());
            assertEquals(0L, pass.revision());
            assertEquals(VctPass.UpdateStats.NONE, pass.recordVoxelize(command, scene,
                    List.of(VctBox.of(-2.0f, -2.0f, -2.0f, 2.0f, 2.0f, 2.0f))));
            assertEquals(0, command.commandCount());
            assertEquals(0, pass.recordProbeTrace(command, probes));
            assertEquals(0, command.commandCount());
            pass.recordComposite(command, null, 0, 0, null, 0.5f, new Vector3f(1.0f));
            assertEquals(0, command.commandCount());
            assertThrows(IllegalStateException.class, pass::probeResults);

            pass.reconfigure(VctSettings.disabled());
            assertEquals(0L, pass.revision());
            assertThrows(NullPointerException.class, () -> pass.reconfigure(null));

            pass.close();
            pass.close();
            assertFalse(pass.active());
        } finally {
            pass.close();
        }
    }

    @Test
    void sceneInputPacksRevisionTrianglesAndBoxGeometry() {
        VctSceneInput input = VctSceneInput.builder(7L)
                .addBox(new Vector3f(-2.0f, -1.0f, -3.0f), new Vector3f(2.0f, 1.0f, 3.0f),
                        1.0f, 0.5f, 0.25f)
                .build();
        assertEquals(7L, input.revision());
        assertEquals(12, input.triangleCount());
        float[] packed = input.packedCopy();
        assertEquals(12 * VctSceneInput.FLOATS_PER_TRIANGLE, packed.length);
        // The first triangle is the -Z face: p0=(-2,-1,-3), p1=(-2,-1,3), p2=(-2,1,3).
        assertEquals(-2.0f, packed[0], 0.0f);
        assertEquals(-1.0f, packed[1], 0.0f);
        assertEquals(-3.0f, packed[2], 0.0f);
        assertEquals(-2.0f, packed[4], 0.0f);
        assertEquals(3.0f, packed[6], 0.0f);
        assertEquals(-2.0f, packed[8], 0.0f);
        assertEquals(1.0f, packed[9], 0.0f);
        assertEquals(-1.0f, packed[12], 0.0f);
        assertEquals(0.0f, packed[13], 0.0f);
        assertEquals(0.0f, packed[14], 0.0f);
        assertEquals(1.0f, packed[15], 0.0f);
        assertEquals(1.0f, packed[16], 0.0f);
        assertEquals(0.5f, packed[17], 0.0f);
        assertEquals(0.25f, packed[18], 0.0f);

        assertThrows(IllegalArgumentException.class,
                () -> VctSceneInput.builder(-1L));
        assertThrows(IllegalArgumentException.class, () -> VctSceneInput.builder()
                .addTriangle(new Vector3f(), new Vector3f(1.0f, 0.0f, 0.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, 0.0f, 0.0f),
                        1.0f, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> VctSceneInput.builder()
                .addTriangle(new Vector3f(), new Vector3f(1.0f, 0.0f, 0.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f),
                        1.0f, 1.0f, 1.0f, 0.0f));
        assertThrows(IllegalArgumentException.class, () -> VctSceneInput.builder()
                .addBox(new Vector3f(1.0f), new Vector3f(-1.0f), 1.0f, 1.0f, 1.0f));
    }

    @Test
    void sceneInputRejectsMoreThanTheBoundedTriangleCount() {
        VctSceneInput.Builder builder = VctSceneInput.builder();
        for (int index = 0; index < VctSceneInput.MAX_TRIANGLES; index++) {
            builder.addTriangle(new Vector3f(), new Vector3f(1.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f),
                    1.0f, 1.0f, 1.0f);
        }
        assertEquals(VctSceneInput.MAX_TRIANGLES, builder.build().triangleCount());
        assertThrows(IllegalArgumentException.class, () -> builder.addTriangle(
                new Vector3f(), new Vector3f(1.0f, 0.0f, 0.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f),
                1.0f, 1.0f, 1.0f));
    }

    @Test
    void probeBatchPacksNormalizedProbeRowsAndValidatesItsBounds() {
        VctProbeBatch batch = VctProbeBatch.builder()
                .addProbe(new Vector3f(1.0f, 2.0f, 3.0f), new Vector3f(0.0f, 0.0f, 5.0f),
                        new Vector3f(0.0f, 0.0f, 2.0f), 0.35f)
                .build();
        assertEquals(1, batch.count());
        float[] packed = batch.packedCopy();
        assertEquals(VctProbeBatch.FLOATS_PER_PROBE, packed.length);
        assertEquals(1.0f, packed[0], 0.0f);
        assertEquals(2.0f, packed[1], 0.0f);
        assertEquals(3.0f, packed[2], 0.0f);
        assertEquals(0.0f, packed[4], 0.0f);
        assertEquals(0.0f, packed[5], 0.0f);
        assertEquals(1.0f, packed[6], 0.0f);
        assertEquals(0.35f, packed[7], 0.0f);
        assertEquals(0.0f, packed[8], 0.0f);
        assertEquals(0.0f, packed[9], 0.0f);
        assertEquals(1.0f, packed[10], 0.0f);

        assertThrows(IllegalArgumentException.class, () -> VctProbeBatch.builder()
                .addProbe(new Vector3f(), new Vector3f(0.0f, 0.0f, 0.0f),
                        new Vector3f(0.0f, 0.0f, 1.0f), 0.5f));
        assertThrows(IllegalArgumentException.class, () -> VctProbeBatch.builder()
                .addProbe(new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(0.0f, 0.0f, 1.0f), 1.5f));
    }

    @Test
    void addBoxPacksPlanarFacesWithOutwardNormals() {
        Vector3f minimum = new Vector3f(-2.0f, -1.0f, -3.0f);
        Vector3f maximum = new Vector3f(2.0f, 1.0f, 3.0f);
        float[] packed = VctSceneInput.builder()
                .addBox(minimum, maximum, 1.0f, 1.0f, 1.0f)
                .build()
                .packedCopy();
        assertEquals(12 * VctSceneInput.FLOATS_PER_TRIANGLE, packed.length);
        float[] axes = {minimum.x, minimum.y, minimum.z, maximum.x, maximum.y, maximum.z};
        for (int triangle = 0; triangle < 12; triangle++) {
            int base = triangle * VctSceneInput.FLOATS_PER_TRIANGLE;
            int constantAxis = -1;
            float constantValue = 0.0f;
            for (int axis = 0; axis < 3; axis++) {
                float first = packed[base + axis];
                float second = packed[base + 4 + axis];
                float third = packed[base + 8 + axis];
                if (Math.abs(first - second) < 1.0e-5f && Math.abs(first - third) < 1.0e-5f) {
                    assertEquals(-1, constantAxis, "a box face must const on exactly one axis");
                    constantAxis = axis;
                    constantValue = first;
                }
            }
            assertTrue(constantAxis >= 0, "triangle " + triangle + " is not planar on a box face");
            float normalComponent = packed[base + 12 + constantAxis];
            assertEquals(0.0f, Math.abs(packed[base + 12 + (constantAxis + 1) % 3]), 0.0f,
                    "box face normal must be axis aligned");
            assertEquals(0.0f, Math.abs(packed[base + 12 + (constantAxis + 2) % 3]), 0.0f,
                    "box face normal must be axis aligned");
            boolean atMaximum = Math.abs(constantValue - axes[constantAxis + 3]) < 1.0e-5f;
            float expectedSign = atMaximum ? 1.0f : -1.0f;
            assertEquals(expectedSign, normalComponent, 0.0f,
                    "box face normal must point outward on triangle " + triangle);
        }
    }

    @Test
    void dirtyBoxesAreValidatedAndUnionIsBounded() {
        VctBox first = VctBox.of(-1.0f, -2.0f, -3.0f, 1.0f, 2.0f, 3.0f);
        VctBox second = VctBox.of(0.0f, 0.0f, 0.0f, 4.0f, 5.0f, 6.0f);
        VctBox union = first.union(second);
        assertEquals(-1.0f, union.minimum().x, 0.0f);
        assertEquals(-3.0f, union.minimum().z, 0.0f);
        assertEquals(4.0f, union.maximum().x, 0.0f);
        assertEquals(6.0f, union.maximum().z, 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> VctBox.of(1.0f, 0.0f, 0.0f, -1.0f, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> VctBox.of(Float.NaN, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f));
        assertTrue(first.union(second).maximum().y >= first.maximum().y);
    }
}
