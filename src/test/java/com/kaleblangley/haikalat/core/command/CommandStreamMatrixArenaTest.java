package com.kaleblangley.haikalat.core.command;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandStreamMatrixArenaTest {
    @Test
    void matrixCommandKeepsAStablePrimitiveSnapshot() {
        CommandStream stream = new CommandStream();
        Matrix4f source = new Matrix4f().translationRotateScale(
                1.0f, 2.0f, 3.0f, 0.0f, 0.0f, 0.0f, 1.0f, 2.0f, 3.0f, 4.0f);
        Matrix4f expected = new Matrix4f(source);

        stream.matrixCommand((byte) 41, 7, "shader", source);
        source.zero();

        assertEquals(1, stream.commandCount());
        assertEquals(1, stream.matrixCount());
        assertEquals(7, stream.integerAt(0));
        assertEquals(0, stream.integerAt(1));
        assertEquals("shader", stream.objectAt(0));
        assertTrue(expected.equals(stream.loadMatrix(0, new Matrix4f()), 0.0f));
    }

    @Test
    void arenaGrowsWithoutChangingEarlierMatrices() {
        CommandStream stream = new CommandStream();
        for (int index = 0; index < 257; index++) {
            stream.matrixCommand((byte) 42, index, this,
                    new Matrix4f().translation(index, -index, index * 0.5f));
        }

        assertEquals(257, stream.matrixCount());
        assertEquals(0.0f, stream.loadMatrix(0, new Matrix4f()).m30());
        assertEquals(256.0f, stream.loadMatrix(256 * 16, new Matrix4f()).m30());
    }

    @Test
    void resetDropsPublishedCountsAndRejectsOldOffsets() {
        CommandStream stream = new CommandStream();
        stream.matrixCommand((byte) 43, 1, this, new Matrix4f().translation(2.0f, 0.0f, 0.0f));
        stream.reset();

        assertEquals(0, stream.commandCount());
        assertEquals(0, stream.matrixCount());
        assertEquals(0, stream.objectCount());
        assertThrows(IllegalStateException.class,
                () -> stream.loadMatrix(0, new Matrix4f()));
    }

    @Test
    void unalignedAndOutOfRangeOffsetsFailFast() {
        CommandStream stream = new CommandStream();
        stream.matrixCommand((byte) 44, 1, this, new Matrix4f());

        assertThrows(IllegalStateException.class,
                () -> stream.loadMatrix(1, new Matrix4f()));
        assertThrows(IllegalStateException.class,
                () -> stream.loadMatrix(16, new Matrix4f()));
    }
}
