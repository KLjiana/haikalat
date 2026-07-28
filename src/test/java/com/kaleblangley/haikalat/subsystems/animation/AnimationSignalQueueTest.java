package com.kaleblangley.haikalat.subsystems.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationSignalQueueTest {
    @Test
    void highPrioritySignalEvictsOldestNormalWithoutStreamAllocation() {
        AnimationSignalQueue queue = new AnimationSignalQueue(2);
        queue.offer(signal(queue.nextSequence(), AnimationMarker.Priority.NORMAL, "normal-1"));
        queue.offer(signal(queue.nextSequence(), AnimationMarker.Priority.NORMAL, "normal-2"));

        assertTrue(queue.offer(signal(queue.nextSequence(), AnimationMarker.Priority.HIGH, "high")));
        assertEquals(0L, queue.droppedCount());
        assertEquals(3L, queue.emittedCount());
        assertEquals("normal-2", queue.pending().getFirst().name());
        assertEquals("high", queue.pending().getLast().name());
    }

    @Test
    void normalOverflowIsDroppedAndSequenceNumbersRemainMonotonic() {
        AnimationSignalQueue queue = new AnimationSignalQueue(1);
        AnimationSignal first = signal(queue.nextSequence(), AnimationMarker.Priority.NORMAL, "first");
        AnimationSignal second = signal(queue.nextSequence(), AnimationMarker.Priority.NORMAL, "second");
        assertTrue(queue.offer(first));
        assertFalse(queue.offer(second));
        assertEquals(1L, queue.droppedCount());
        assertEquals(0L, first.sequence());
        assertEquals(1L, second.sequence());
    }

    private static AnimationSignal signal(long sequence, AnimationMarker.Priority priority, String name) {
        return new AnimationSignal(
                sequence,
                AnimationSignal.Type.MARKER,
                new AnimationSignal.Source("test", -1),
                "state", "motion", 0.5f, name, "", 0L, priority);
    }

}
