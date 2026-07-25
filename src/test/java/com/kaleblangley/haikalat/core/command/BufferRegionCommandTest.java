package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.buffer.BufferUploadTarget;
import com.kaleblangley.haikalat.backend.state.StateCache;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BufferRegionCommandTest {
    @Test
    void recordsImmutablePayloadAndExecutesTypedTargetUpdate() {
        RecordingTarget target = new RecordingTarget();
        ByteBuffer source = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(7).putInt(11).flip();
        CommandBuffer commands = new CommandBuffer().uploadBufferRegion(target, 16L, source);
        source.putInt(0, 99);

        assertEquals(CommandBuffer.UPLOAD_BUFFER_REGION, commands.recordedOpcodeAt(0));
        assertEquals(2, commands.objectPayloadCount());
        assertNotSame(source, commands.objectPayloadAt(1));

        commands.execute(new StateCache());

        assertEquals(16L, target.offset);
        assertEquals(7, target.data.getInt(0));
        assertEquals(11, target.data.getInt(4));
    }

    @Test
    void rejectsNegativeOffsetAndEmptyPayloadWithoutPublishingCommand() {
        RecordingTarget target = new RecordingTarget();
        CommandBuffer commands = new CommandBuffer();

        assertThrows(IllegalArgumentException.class,
                () -> commands.uploadBufferRegion(target, -1L, ByteBuffer.allocate(1)));
        assertThrows(IllegalArgumentException.class,
                () -> commands.uploadBufferRegion(target, 0L, ByteBuffer.allocate(0)));
        assertEquals(0, commands.commandCount());
    }

    private static final class RecordingTarget implements BufferUploadTarget {
        private long offset = -1L;
        private ByteBuffer data;

        @Override
        public int id() {
            return 1;
        }

        @Override
        public BufferUploadTarget update(long offsetBytes, ByteBuffer source) {
            offset = offsetBytes;
            data = ByteBuffer.allocate(source.remaining()).order(source.order());
            data.put(source.duplicate()).flip();
            return this;
        }
    }
}
