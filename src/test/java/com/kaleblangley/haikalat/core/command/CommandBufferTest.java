package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.state.StateCache;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandBufferTest {
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
