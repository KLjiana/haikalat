package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.state.StateCache;
import com.kaleblangley.haikalat.backend.sync.GpuFenceTarget;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GpuFenceCommandTest {
    @Test
    void typedFenceRunsOnlyAtItsRecordedExecutionBoundary() {
        AtomicInteger calls = new AtomicInteger();
        CommandBuffer commands = new CommandBuffer().insertGpuFence(calls::incrementAndGet);

        assertEquals(0, calls.get());
        commands.execute(new StateCache());
        assertEquals(1, calls.get());
    }

    @Test
    void commandFailureNotifiesFenceTargetsThatWereNotReached() {
        RuntimeException expected = new RuntimeException("command failed");
        FailureAwareTarget target = new FailureAwareTarget();
        CommandBuffer commands = new CommandBuffer()
                .custom(() -> { throw expected; })
                .insertGpuFence(target);

        assertSame(expected, assertThrows(RuntimeException.class,
                () -> commands.execute(new StateCache())));
        assertEquals(0, target.insertions);
        assertSame(expected, target.failure);
    }

    private static final class FailureAwareTarget implements GpuFenceTarget {
        private int insertions;
        private Throwable failure;

        @Override
        public void insertGpuFence() {
            insertions++;
        }

        @Override
        public void executionFailed(Throwable failure) {
            this.failure = failure;
        }
    }
}
