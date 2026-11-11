package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch plan contract of the bounded bitonic sort.
 *
 * <p>Expected step lists are hand-written from the standard network, not read
 * back from the plan: capacity 8 has the steps
 * {@code (2,1), (4,2), (4,1), (8,4), (8,2), (8,1)}. Non-power-of-two bounds are
 * padded with a larger power of two and must not add or drop steps for the
 * padded capacity.</p>
 */
class GpuParticleSortPlanTest {
    @Test
    void singleElementCapacityHasNoCompareExchangeSteps() {
        GpuParticleSortPlan plan = new GpuParticleSortPlan(1);

        assertEquals(1, plan.sortCapacity());
        assertEquals(0, plan.steps());
        assertEquals(0, plan.stepGroups());
        assertEquals(1, plan.prepareGroups());
    }

    @Test
    void nonPowerOfTwoBoundsPadToTheNextPowerOfTwoNetwork() {
        GpuParticleSortPlan padded = new GpuParticleSortPlan(5);

        assertEquals(8, padded.sortCapacity());
        assertEquals(6, padded.steps());
        assertEquals(List.of(2, 4, 4, 8, 8, 8), blockSizes(padded));
        assertEquals(List.of(1, 2, 1, 4, 2, 1), compareDistances(padded));
        assertEquals(1, padded.prepareGroups());
        assertEquals(1, padded.stepGroups());

        GpuParticleSortPlan exact = new GpuParticleSortPlan(8);
        assertEquals(8, exact.sortCapacity());
        assertEquals(padded.steps(), exact.steps());
        assertEquals(blockSizes(padded), blockSizes(exact));
        assertEquals(compareDistances(padded), compareDistances(exact));
    }

    @Test
    void everyStepIsACompareExchangeInsideItsBlock() {
        GpuParticleSortPlan plan = new GpuParticleSortPlan(130);

        assertEquals(256, plan.sortCapacity());
        assertEquals(36, plan.steps(), "2^8 capacity has 8 * 9 / 2 bitonic steps");
        assertEquals(4, plan.prepareGroups());
        assertEquals(2, plan.stepGroups());
        for (int step = 0; step < plan.steps(); step++) {
            int block = plan.blockSize(step);
            int distance = plan.compareDistance(step);
            assertEquals(1, Integer.bitCount(block), "block size must be a power of two: " + block);
            assertEquals(1, Integer.bitCount(distance),
                    "compare distance must be a power of two: " + distance);
            assertTrue(distance < block, "distance must stay inside its block");
            assertTrue(block <= plan.sortCapacity());
            if (step > 0 && plan.blockSize(step - 1) == block) {
                assertTrue(plan.compareDistance(step - 1) == distance * 2,
                        "distances must halve inside a block");
            }
        }
    }

    @Test
    void capacityBoundsMatchTheDocumentedPowerOfTwoRule() {
        assertEquals(1, GpuParticleSortPlan.nextPowerOfTwo(1));
        assertEquals(2, GpuParticleSortPlan.nextPowerOfTwo(2));
        assertEquals(4, GpuParticleSortPlan.nextPowerOfTwo(3));
        assertEquals(1024, GpuParticleSortPlan.nextPowerOfTwo(1024));
        assertEquals(2048, GpuParticleSortPlan.nextPowerOfTwo(1025));
        assertEquals(GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES,
                GpuParticleSortPlan.nextPowerOfTwo(
                        GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES));
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GpuParticleSortPlan(0));
        assertThrows(IllegalArgumentException.class,
                () -> new GpuParticleSortPlan(
                        GpuParticleSortingSettings.MAXIMUM_SORTED_PARTICLES + 1));
        assertThrows(IllegalArgumentException.class, () -> GpuParticleSortPlan.nextPowerOfTwo(0));
        assertThrows(IllegalArgumentException.class,
                () -> GpuParticleSortPlan.nextPowerOfTwo(-1));
    }

    /**
     * Executes the documented compare-exchange step semantics over the plan's
     * step list, exactly as the shader maps one invocation to one pair. This
     * guards the plan/network composition that no JVM production test can
     * observe without a GPU: sentinels must end at the tail, the valid prefix
     * must be sorted in both directions, and equal keys must keep their input
     * order under the stable tie key.
     */
    @Test
    void documentedNetworkSortsThePaddedArrayAndKeepsEqualKeysStable() {
        java.util.Random random = new java.util.Random(20261112L);
        for (int maximum : new int[]{1, 2, 3, 5, 8, 9, 16, 17, 31, 32}) {
            GpuParticleSortPlan plan = new GpuParticleSortPlan(maximum);
            int capacity = plan.sortCapacity();
            for (int count : new int[]{0, 1, 2, 3, 5, 7, Math.min(maximum, capacity)}) {
                if (count > maximum) {
                    continue;
                }
                for (boolean backToFront : new boolean[]{false, true}) {
                    float[] metrics = new float[Math.max(1, count)];
                    for (int index = 0; index < count; index++) {
                        metrics[index] = random.nextInt(5) * 1.0f;
                    }
                    assertNetworkOrder(plan, count, metrics, backToFront);
                }
            }
        }
    }

    private static void assertNetworkOrder(GpuParticleSortPlan plan, int count,
                                           float[] metrics, boolean backToFront) {
        int capacity = plan.sortCapacity();
        long[] keys = new long[capacity];
        int[] payload = new int[capacity];
        long sentinel = 0xFFFFFFFFL << 32;
        java.util.Arrays.fill(keys, sentinel);
        java.util.Arrays.fill(payload, 0xFFFFFFFF);
        for (int index = 0; index < count; index++) {
            long primary = orderedKey(metrics[index]);
            if (backToFront) {
                primary = ~primary & 0xFFFFFFFFL;
            }
            keys[index] = (primary << 32) | index;
            payload[index] = index;
        }
        if (count > 1) {
            for (int step = 0; step < plan.steps(); step++) {
                int block = plan.blockSize(step);
                int distance = plan.compareDistance(step);
                for (int invocation = 0; invocation < capacity / 2; invocation++) {
                    int low = (invocation / distance) * (distance * 2)
                            + (invocation % distance);
                    int high = low + distance;
                    boolean ascending = (low & block) == 0;
                    boolean exchange = ascending ? keyLess(keys[high], keys[low])
                            : keyLess(keys[low], keys[high]);
                    if (exchange) {
                        long key = keys[low];
                        keys[low] = keys[high];
                        keys[high] = key;
                        int value = payload[low];
                        payload[low] = payload[high];
                        payload[high] = value;
                    }
                }
            }
        }
        for (int index = 1; index < count; index++) {
            assertTrue(keys[index - 1] >>> 32 <= keys[index] >>> 32,
                    "the valid prefix must be sorted");
            if (keys[index - 1] >>> 32 == keys[index] >>> 32) {
                assertTrue(payload[index - 1] < payload[index],
                        "equal primary keys must keep the stable tie order");
            }
        }
        for (int index = count; index < capacity; index++) {
            assertEquals(0xFFFFFFFFL, keys[index] >>> 32,
                    "padding keys must end at the tail");
            assertEquals(0xFFFFFFFF, payload[index],
                    "padding payloads must end at the tail");
        }
    }

    private static boolean keyLess(long left, long right) {
        long leftPrimary = left >>> 32;
        long rightPrimary = right >>> 32;
        return leftPrimary < rightPrimary
                || (leftPrimary == rightPrimary
                && (left & 0xFFFFFFFFL) < (right & 0xFFFFFFFFL));
    }

    private static long orderedKey(float value) {
        int bits = Float.floatToRawIntBits(value);
        return (bits & 0x80000000) != 0 ? (long) (~bits & 0xFFFFFFFF)
                : (long) (bits | 0x80000000);
    }

    private static List<Integer> blockSizes(GpuParticleSortPlan plan) {
        List<Integer> result = new ArrayList<>();
        for (int step = 0; step < plan.steps(); step++) {
            result.add(plan.blockSize(step));
        }
        return result;
    }

    private static List<Integer> compareDistances(GpuParticleSortPlan plan) {
        List<Integer> result = new ArrayList<>();
        for (int step = 0; step < plan.steps(); step++) {
            result.add(plan.compareDistance(step));
        }
        return result;
    }
}
