package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

/**
 * Deterministic round-robin probe update budget for DDGI.
 *
 * <p>{@code cursor} is the last probe index written by the previous frame
 * ({@code -1} before the first update). Each frame refills at most
 * {@code min(budget, probeCount)} probes starting at {@code cursor + 1}, so
 * every probe is refreshed within {@code ceil(probeCount / budget)} frames and
 * no probe is refreshed twice before the others were. There is no dirty-probe
 * priority in this first bounded version; moving geometry is picked up when its
 * probes come around in the rotation.</p>
 */
final class DdgiProbeBudget {
    private DdgiProbeBudget() {
    }

    /**
     * Writes the next probe indices into {@code destination}.
     *
     * @param probeCount  total probe count, positive
     * @param budget      requested probes per frame, positive
     * @param cursor      last probe index written, in {@code [-1, probeCount)}
     * @param destination output array at least {@code probeCount} long
     * @return number of indices written ({@code min(budget, probeCount)})
     */
    static int writeSchedule(int probeCount, int budget, int cursor, int[] destination) {
        if (probeCount <= 0) {
            throw new IllegalArgumentException("probeCount must be positive");
        }
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive");
        }
        if (cursor < -1 || cursor >= probeCount) {
            throw new IllegalArgumentException("cursor must be in [-1, " + probeCount + ")");
        }
        if (destination == null || destination.length < probeCount) {
            throw new IllegalArgumentException("destination must hold at least " + probeCount
                    + " indices");
        }
        int count = Math.min(budget, probeCount);
        for (int index = 0; index < count; index++) {
            destination[index] = (cursor + 1 + index) % probeCount;
        }
        return count;
    }
}
