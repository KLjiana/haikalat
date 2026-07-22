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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;

class CommandBufferTest {
    @Test
    void repeatedProgramBindingIsFoldedUntilCustomBarrier() {
        CommandBuffer commands = new CommandBuffer()
                .useProgram(7)
                .drawArrays(GL_TRIANGLES, 0, 3)
                .useProgram(7)
                .drawArrays(GL_TRIANGLES, 0, 3);

        assertEquals(3, commands.commandCount());

        commands.custom(() -> { }).useProgram(7);
        assertEquals(5, commands.commandCount(),
                "custom may replace the active program, so the binding must be recorded again");

        commands.reset();
        commands.useProgram(7);
        assertEquals(1, commands.commandCount(), "reset must forget recorder-local bindings");
    }

    @Test
    void typedFramebufferBlitRejectsInvalidDepthFilteringAndMasks() {
        CommandBuffer commands = new CommandBuffer()
                .blitFramebuffer(1, 2, 16, 16, 8, 8,
                        GL_COLOR_BUFFER_BIT, GL_LINEAR)
                .blitFramebuffer(1, 2, 16, 16, 16, 16,
                        GL_DEPTH_BUFFER_BIT, GL_NEAREST);

        assertEquals(2, commands.commandCount());
        assertThrows(IllegalArgumentException.class, () -> commands.blitFramebuffer(
                1, 2, 16, 16, 16, 16, GL_DEPTH_BUFFER_BIT, GL_LINEAR));
        assertThrows(IllegalArgumentException.class, () -> commands.blitFramebuffer(
                1, 2, 16, 16, 16, 16,
                GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST));
        assertThrows(IllegalArgumentException.class, () -> commands.blitFramebuffer(
                1, 2, 0, 16, 16, 16, GL_COLOR_BUFFER_BIT, GL_NEAREST));
    }

    @Test
    void publicDiagnosticsCountsCoverOnlyTheirDocumentedArenas() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isPublic(CommandBuffer.class
                .getMethod("recordedMatrixSnapshotCount").getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(CommandBuffer.class
                .getMethod("recordedObjectPayloadCount").getModifiers()));

        CommandBuffer commands = new CommandBuffer().custom(() -> { });

        assertEquals(0, commands.recordedMatrixSnapshotCount());
        assertEquals(1, commands.recordedObjectPayloadCount());
        commands.reset();
        assertEquals(0, commands.recordedMatrixSnapshotCount());
        assertEquals(0, commands.recordedObjectPayloadCount());
    }

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
