package com.kaleblangley.haikalat.core.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandBufferDebugGroupTest {
    @Test
    void typedDebugGroupsPreserveCommandOrder() {
        CommandBuffer commands = new CommandBuffer()
                .pushDebugGroup("RenderGraph/Geometry")
                .drawArrays(4, 0, 3)
                .popDebugGroup();

        assertEquals(CommandBuffer.PUSH_DEBUG_GROUP, commands.recordedOpcodeAt(0));
        assertEquals(CommandBuffer.DRAW_ARRAYS, commands.recordedOpcodeAt(1));
        assertEquals(CommandBuffer.POP_DEBUG_GROUP, commands.recordedOpcodeAt(2));
        assertEquals("RenderGraph/Geometry", commands.objectPayloadAt(0));
    }

    @Test
    void blankGroupNamesFailAtRecordTime() {
        assertThrows(IllegalArgumentException.class,
                () -> new CommandBuffer().pushDebugGroup("  "));
    }
}
