package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.buffer.BufferUploadTarget;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_POINTS;

/**
 * Locks the typed indirect-draw command encoding.
 *
 * <p>The draw count lives only in the GPU parameter buffer, so this test asserts
 * the recorded opcode plus its integer/long/object cursor order instead of any
 * CPU count. A cursor mismatch here would make the executor read the byte
 * offset or the buffer from the wrong payload and is exactly the regression the
 * GPU particle owner must not reintroduce.</p>
 */
class IndirectDrawCommandTest {
    @Test
    void indirectDrawKeepsModeOffsetAndBufferInExecutorOrder() {
        StubBuffer buffer = new StubBuffer(7);

        CommandBuffer commands = new CommandBuffer()
                .enableBlend(true)
                .drawArraysIndirect(GL_POINTS, buffer, 32L);

        assertEquals(2, commands.commandCount());
        assertEquals(CommandBuffer.APPLY_PIPELINE_STATE, commands.recordedOpcodeAt(0));
        assertEquals(CommandBuffer.DRAW_ARRAYS_INDIRECT, commands.recordedOpcodeAt(1));

        assertEquals(1, commands.objectPayloadCount());
        assertSame(buffer, commands.objectPayloadAt(0));

        assertEquals(2, commands.recordedIntegerAt(0),
                "the pending packet must encode the BLEND_ENABLE dirty bit");
        assertEquals(1, commands.recordedIntegerAt(1), "blend is enabled");
        assertEquals(GL_POINTS, commands.recordedIntegerAt(2),
                "the draw mode is the first integer payload of the indirect command");
        assertEquals(32L, commands.recordedLongAt(0),
                "the indirect command offset follows the mode in the long cursor");
    }

    @Test
    void invalidIndirectDrawLeavesPendingStateAndPayloadsUntouched() {
        CommandBuffer commands = new CommandBuffer().enableBlend(true);

        assertThrows(NullPointerException.class,
                () -> commands.drawArraysIndirect(GL_POINTS, null, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> commands.drawArraysIndirect(GL_POINTS, new StubBuffer(1), -1L));
        assertThrows(GlException.class,
                () -> commands.drawArraysIndirect(GL_POINTS, new ClosedBuffer(), 0L));

        assertEquals(1, commands.commandCount(),
                "a rejected indirect draw must not flush the existing pending packet");
        assertEquals(0, commands.objectPayloadCount());
    }

    @Test
    void resetDropsRecordedIndirectPayload() {
        CommandBuffer commands = new CommandBuffer()
                .drawArraysIndirect(GL_POINTS, new StubBuffer(3), 0L);

        assertEquals(1, commands.commandCount());
        assertEquals(1, commands.objectPayloadCount());

        commands.reset();

        assertEquals(0, commands.commandCount());
        assertEquals(0, commands.objectPayloadCount());
    }

    private static final class StubBuffer implements BufferUploadTarget {
        private final int id;

        private StubBuffer(int id) {
            this.id = id;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public BufferUploadTarget update(long offsetBytes, ByteBuffer data) {
            throw new AssertionError("indirect draw must not upload from the CPU");
        }
    }

    private static final class ClosedBuffer implements BufferUploadTarget, GlResource {
        @Override
        public int id() {
            return 9;
        }

        @Override
        public boolean isClosed() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public BufferUploadTarget update(long offsetBytes, ByteBuffer data) {
            throw new AssertionError("closed buffer must not upload from the CPU");
        }
    }
}
