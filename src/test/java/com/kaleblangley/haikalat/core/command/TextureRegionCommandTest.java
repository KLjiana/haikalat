package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL30.GL_R8;

class TextureRegionCommandTest {
    @Test
    void recordingCopiesSelectedPayloadAndPreservesObservableOrder() {
        Texture2D texture = texture(3, 1);
        ByteBuffer source = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 88});
        source.position(1).limit(4);

        CommandBuffer commands = new CommandBuffer()
                .enableBlend(true)
                .uploadTextureRegion(texture, 0, 0, 3, 1, source)
                .enableBlend(false)
                .drawArrays(GL_TRIANGLES, 0, 3);
        source.put(1, (byte) 41);
        source.put(2, (byte) 42);
        source.put(3, (byte) 43);

        assertEquals(1, source.position(), "recording must not consume caller payload");
        assertEquals(4, source.limit());
        assertEquals(4, commands.commandCount());
        assertEquals(CommandBuffer.APPLY_PIPELINE_STATE, commands.recordedOpcodeAt(0));
        assertEquals(CommandBuffer.UPLOAD_TEXTURE_REGION, commands.recordedOpcodeAt(1));
        assertEquals(CommandBuffer.APPLY_PIPELINE_STATE, commands.recordedOpcodeAt(2));
        assertEquals(CommandBuffer.DRAW_ARRAYS, commands.recordedOpcodeAt(3));
        assertEquals(2, commands.objectPayloadCount());
        assertSame(texture, commands.objectPayloadAt(0));

        ByteBuffer copied = (ByteBuffer) commands.objectPayloadAt(1);
        assertTrue(copied.isDirect());
        assertTrue(copied.isReadOnly());
        assertEquals(3, copied.remaining());
        assertEquals(1, Byte.toUnsignedInt(copied.get(0)));
        assertEquals(2, Byte.toUnsignedInt(copied.get(1)));
        assertEquals(3, Byte.toUnsignedInt(copied.get(2)));
    }

    @Test
    void invalidRecordingLeavesPendingStateUnflushed() {
        Texture2D texture = texture(4, 3);
        CommandBuffer commands = new CommandBuffer().enableBlend(true);

        assertThrows(IllegalArgumentException.class,
                () -> commands.uploadTextureRegion(texture, 3, 0, 2, 1,
                        ByteBuffer.allocate(2)));
        assertThrows(IllegalArgumentException.class,
                () -> commands.uploadTextureRegion(texture, 0, 0, 2, 2,
                        ByteBuffer.allocate(3)));

        assertEquals(1, commands.commandCount(),
                "invalid upload must leave the existing pending state packet untouched");
        assertEquals(0, commands.objectPayloadCount());
    }

    @Test
    void resetReleasesCommandOwnedPayloadReferences() {
        CommandBuffer commands = new CommandBuffer().uploadTextureRegion(
                texture(2, 2), 0, 0, 2, 2, ByteBuffer.allocate(4));

        commands.reset();

        assertEquals(0, commands.commandCount());
        assertEquals(0, commands.objectPayloadCount());
    }

    private static Texture2D texture(int width, int height) {
        try {
            Constructor<Texture2D> constructor = Texture2D.class.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(0, width, height, GL_R8);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
