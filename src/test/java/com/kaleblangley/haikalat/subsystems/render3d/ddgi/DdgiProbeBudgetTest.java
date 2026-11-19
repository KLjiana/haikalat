package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the deterministic per-frame DDGI update budget.
 *
 * <p>These are fixed-input cases: the schedule only advances the round-robin
 * cursor, so a regression in wrap-around, budget capping or duplicate emission
 * is visible without a GL context.</p>
 */
class DdgiProbeBudgetTest {
    @Test
    void scheduleAdvancesRoundRobinAndWraps() {
        int[] destination = new int[8];
        assertEquals(2, DdgiProbeBudget.writeSchedule(5, 2, -1, destination));
        assertArrayEquals(new int[]{0, 1}, copy(destination, 2));
        assertEquals(2, DdgiProbeBudget.writeSchedule(5, 2, 1, destination));
        assertArrayEquals(new int[]{2, 3}, copy(destination, 2));
        assertEquals(2, DdgiProbeBudget.writeSchedule(5, 2, 3, destination));
        assertArrayEquals(new int[]{4, 0}, copy(destination, 2));
        assertEquals(2, DdgiProbeBudget.writeSchedule(5, 2, 4, destination));
        assertArrayEquals(new int[]{0, 1}, copy(destination, 2));
    }

    @Test
    void budgetIsCappedByProbeCount() {
        int[] destination = new int[4];
        assertEquals(3, DdgiProbeBudget.writeSchedule(3, 8, -1, destination));
        assertArrayEquals(new int[]{0, 1, 2}, copy(destination, 3));
        assertEquals(3, DdgiProbeBudget.writeSchedule(3, 8, 2, destination));
        assertArrayEquals(new int[]{0, 1, 2}, copy(destination, 3));
    }

    @Test
    void everyProbeIsUpdatedWithinTheBoundedFrameCount() {
        int probeCount = 7;
        int budget = 3;
        boolean[] seen = new boolean[probeCount];
        int cursor = -1;
        int frames = 0;
        while (frames < 10) {
            int count = DdgiProbeBudget.writeSchedule(probeCount, budget, cursor, new int[probeCount]);
            int[] schedule = DdgiProbeReference.schedule(probeCount, budget, cursor);
            assertEquals(count, schedule.length);
            for (int probe : schedule) {
                seen[probe] = true;
            }
            cursor = schedule[schedule.length - 1];
            frames++;
            if (allSeen(seen)) {
                break;
            }
        }
        assertTrue(allSeen(seen),
                "every probe must be scheduled within ceil(count / budget) frames");
        assertEquals(3, frames, "7 probes at 3 per frame need three frames");
    }

    @Test
    void invalidInputsAreRejected() {
        int[] destination = new int[4];
        assertThrows(IllegalArgumentException.class,
                () -> DdgiProbeBudget.writeSchedule(0, 1, -1, destination));
        assertThrows(IllegalArgumentException.class,
                () -> DdgiProbeBudget.writeSchedule(4, 0, -1, destination));
        assertThrows(IllegalArgumentException.class,
                () -> DdgiProbeBudget.writeSchedule(4, 1, -2, destination));
        assertThrows(IllegalArgumentException.class,
                () -> DdgiProbeBudget.writeSchedule(4, 1, 4, destination));
        assertThrows(IllegalArgumentException.class,
                () -> DdgiProbeBudget.writeSchedule(4, 1, -1, new int[3]));
    }

    private static int[] copy(int[] source, int length) {
        int[] result = new int[length];
        System.arraycopy(source, 0, result, 0, length);
        return result;
    }

    private static boolean allSeen(boolean[] seen) {
        for (boolean value : seen) {
            if (!value) return false;
        }
        return true;
    }
}
