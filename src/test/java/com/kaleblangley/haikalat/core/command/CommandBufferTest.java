package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.TextureCube;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;

class CommandBufferTest {
    @Test
    void indexedInstancedDrawIsAFormalValidatedCommand() {
        CommandBuffer cmd = new CommandBuffer()
                .drawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_BYTE, 0L, 100_000);

        assertEquals(1, cmd.commandCount());
        assertThrows(IllegalArgumentException.class,
                () -> cmd.drawElementsInstanced(GL_TRIANGLES, 36, GL_UNSIGNED_BYTE, -1L, 1));
    }

    @Test
    void instancedBatchCommandExecutesInRecordedOrder() {
        List<String> events = new ArrayList<>();
        CommandBuffer cmd = new CommandBuffer();

        cmd.custom(() -> events.add("before"));
        cmd.recordInstancedBatch(new FakeInstancedBatch(events, 3),
                List.of(new Matrix4f(), new Matrix4f()));
        cmd.custom(() -> events.add("after"));

        assertEquals(3, cmd.commandCount());

        cmd.execute(new StateCache());

        assertEquals(List.of("before", "begin", "submit:2", "flush", "drawn:3", "after"), events);
    }

    @Test
    void instancedBatchCommandCopiesTransformsWhenRecorded() {
        List<String> events = new ArrayList<>();
        List<Float> submittedX = new ArrayList<>();
        Matrix4f transform = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
        CommandBuffer cmd = new CommandBuffer();

        cmd.recordInstancedBatch(new FakeInstancedBatch(events, submittedX, 1), List.of(transform));
        transform.translation(9.0f, 9.0f, 9.0f);

        cmd.execute(new StateCache());

        assertEquals(List.of(1.0f), submittedX);
    }

    @Test
    void typedCubeCommandsRetainResourcesAndResetDropsReferences() throws Exception {
        TextureCube cube = fakeCube();
        CommandBuffer cmd = new CommandBuffer()
                .bindTextureCube(3, cube)
                .bindImage(cube, 1, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .generateMipmaps(cube);

        assertEquals(4, cmd.commandCount());
        assertEquals(3, cmd.objectPayloadCount());
        cmd.reset();
        assertEquals(0, cmd.commandCount());
        assertEquals(0, cmd.objectPayloadCount());
    }

    @Test
    void cubeUseAfterRecordIsCheckedAtExecutionBoundary() throws Exception {
        TextureCube cube = fakeCube();
        CommandBuffer cmd = new CommandBuffer().bindTextureCube(0, cube);
        java.lang.reflect.Field closed = TextureCube.class.getDeclaredField("closed");
        closed.setAccessible(true);
        closed.setBoolean(cube, true);
        assertThrows(GlException.class, () -> cmd.execute(new StateCache()));
    }

    private static TextureCube fakeCube() throws Exception {
        var constructor = TextureCube.class.getDeclaredConstructor(
                int.class, int.class, int.class, RenderFormat.class);
        constructor.setAccessible(true);
        return constructor.newInstance(99, 4, 3, RenderFormat.RGBA16F);
    }

    private static final class FakeInstancedBatch implements InstancedBatchSubmission {
        private final List<String> events;
        private final List<Float> submittedX;
        private final int drawnCount;

        FakeInstancedBatch(List<String> events, int drawnCount) {
            this(events, new ArrayList<>(), drawnCount);
        }

        FakeInstancedBatch(List<String> events, List<Float> submittedX, int drawnCount) {
            this.events = events;
            this.submittedX = submittedX;
            this.drawnCount = drawnCount;
        }

        @Override
        public void beginFrame() {
            events.add("begin");
        }

        @Override
        public void submitAll(Iterable<Matrix4f> transforms) {
            int count = 0;
            for (Matrix4f transform : transforms) {
                submittedX.add(transform.m30());
                count++;
            }
            events.add("submit:" + count);
        }

        @Override
        public int flush() {
            events.add("flush");
            return drawnCount;
        }

        @Override
        public void drawn(int count) {
            events.add("drawn:" + count);
        }
    }
}
