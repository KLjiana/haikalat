package com.kaleblangley.haikalat.subsystems.postprocess.adaptive;

/**
 * Independent double-precision reference for the adaptive-sampling budget
 * allocation of the screen-space reflection march.
 *
 * <p>This class deliberately does not call production code.  It restates the
 * documented contract in double precision so the fixed inputs below can be
 * recomputed by hand:</p>
 *
 * <ul>
 *   <li>{@code budgetFor}: errors at or below the threshold spend only the
 *       floor; the ramp is linear over {@code (threshold, 1]} and the result is
 *       rounded half up.</li>
 *   <li>{@code allocate}: the final per-pixel budget is the larger of the
 *       per-tile budget and the per-pixel residual budget, clamped to
 *       {@code [minimumSteps, maximumSteps]}.</li>
 *   <li>{@code fixedBudgetForSameCost}: the fixed-budget control spends the
 *       same total sample count as the adaptive allocation, so the oracle
 *       comparison is cost-matched.</li>
 *   <li>{@code remainingError}: a deterministic convergence model used only by
 *       the fixture test; it is a model of the sampler, not a measured GPU
 *       result.</li>
 * </ul>
 */
final class AdaptiveSamplingReference {
    private AdaptiveSamplingReference() {
    }

    /**
     * Documented allocation formula in double precision.
     *
     * @param error          measured normalized error
     * @param minimumSteps   sample floor
     * @param maximumSteps   sample ceiling
     * @param errorThreshold error below which only the floor is spent
     * @return step budget in {@code [minimumSteps, maximumSteps]}
     */
    static int budgetFor(double error, int minimumSteps, int maximumSteps,
                         double errorThreshold) {
        if (minimumSteps < 1 || maximumSteps < minimumSteps) {
            throw new IllegalArgumentException("invalid step range");
        }
        double clampedError = clamp(error, 0.0, 1.0);
        double threshold = clamp(errorThreshold, 0.0, 0.999);
        double denominator = Math.max(1.0 - threshold, 1.0e-4);
        double normalized = clamp((clampedError - threshold) / denominator, 0.0, 1.0);
        return minimumSteps
                + (int) Math.floor(normalized * (maximumSteps - minimumSteps) + 0.5);
    }

    /**
     * Per-pixel allocation from the two measured signals: the larger of the
     * tile budget and the pixel residual budget.
     */
    static int[] allocate(double[] tileErrors, double[] pixelResiduals,
                          int minimumSteps, int maximumSteps, double errorThreshold) {
        if (tileErrors.length != pixelResiduals.length) {
            throw new IllegalArgumentException("fixture lengths differ");
        }
        int[] budgets = new int[tileErrors.length];
        for (int index = 0; index < budgets.length; index++) {
            budgets[index] = Math.max(
                    budgetFor(tileErrors[index], minimumSteps, maximumSteps, errorThreshold),
                    budgetFor(pixelResiduals[index], minimumSteps, maximumSteps,
                            errorThreshold));
        }
        return budgets;
    }

    /**
     * Total per-pixel cost of the two-phase adaptive march: the mandatory floor
     * plus the allocated final budget.
     */
    static long totalCost(int[] budgets, int minimumSteps) {
        long total = 0;
        for (int budget : budgets) {
            total += minimumSteps + budget;
        }
        return total;
    }

    /**
     * Cost-matched fixed control: every pixel spends the rounded mean of the
     * adaptive two-phase cost.  The returned value is the fixed final budget
     * (the control's single march), so {@code minimumSteps + fixedBudget}
     * equals the adaptive mean cost per pixel.
     */
    static int fixedBudgetForSameCost(int[] budgets, int minimumSteps) {
        if (budgets.length == 0) {
            throw new IllegalArgumentException("fixture must not be empty");
        }
        double meanCost = (double) totalCost(budgets, minimumSteps) / budgets.length;
        return (int) Math.round(meanCost) - minimumSteps;
    }

    /** Deterministic convergence model: remaining error after a final budget. */
    static double remainingError(double errorAtFloor, int budget, int minimumSteps,
                                 double decayPerStep) {
        return errorAtFloor * Math.exp(-(budget - minimumSteps) * decayPerStep);
    }

    /**
     * Tile normal-spread term shared with {@code ssr-adaptive.frag}:
     * {@code clamp((1 - minDot) / 0.5, 0, 1)}.  Used to recompute the expected
     * budgets of the real-GL normal-step fixture independently.
     *
     * @param degrees angle between the tile probe normals
     * @return normalized tile error in {@code [0, 1]}
     */
    static double tileNormalError(double degrees) {
        double minimumDot = Math.cos(Math.toRadians(degrees));
        return clamp((1.0 - minimumDot) / 0.5, 0.0, 1.0);
    }

    /** Total remaining error of a fixture under the given final budgets. */
    static double totalRemainingError(double[] errorAtFloor, int[] budgets,
                                      int minimumSteps, double[] decayPerStep) {
        double total = 0.0;
        for (int index = 0; index < budgets.length; index++) {
            total += remainingError(errorAtFloor[index], budgets[index], minimumSteps,
                    decayPerStep[index]);
        }
        return total;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
