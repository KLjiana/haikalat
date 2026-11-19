package com.kaleblangley.haikalat.subsystems.postprocess.adaptive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent double reference for the adaptive error/budget allocation.
 *
 * <p>The fixture is deliberately sparse: 56 easy cells converge quickly and 8
 * hard cells need a large budget.  Adaptive sampling spends the measured
 * residual of the hard cells and the floor on the easy cells, while the
 * cost-matched fixed control spreads the same total sample count uniformly.
 * Because the error-vs-budget curve is convex, the adaptive allocation has a
 * lower total remaining error at exactly the same cost.  This is a model of
 * the sampler, not a measured GPU result; the real-GL test covers the actual
 * shader path.</p>
 */
class AdaptiveSamplingReferenceTest {
    private static final int CELLS = 64;
    private static final int HARD_CELLS = 8;
    private static final int MINIMUM_STEPS = 8;
    private static final int MAXIMUM_STEPS = 64;
    /** Ceiling used by the real-GL normal-step fixture. */
    private static final int GL_MAXIMUM_STEPS = 32;
    private static final double THRESHOLD = 0.10;

    /** Residual of the hard cells chosen so the allocation is exactly 56 steps. */
    private static final double HARD_RESIDUAL = 0.8714;
    private static final double HARD_ERROR_AT_FLOOR = 0.80;
    private static final double HARD_DECAY_PER_STEP = 0.02;
    private static final double EASY_RESIDUAL = 0.05;
    private static final double EASY_ERROR_AT_FLOOR = 0.05;
    private static final double EASY_DECAY_PER_STEP = 0.30;

    @Test
    void fixedInputsRecomputeTheDocumentedAllocation() {
        // Hand-derived from the formula: budget = 8 + floor(((e - 0.1) / 0.9) * 56 + 0.5).
        assertEquals(8, AdaptiveSamplingReference.budgetFor(0.0, MINIMUM_STEPS,
                MAXIMUM_STEPS, THRESHOLD));
        assertEquals(8, AdaptiveSamplingReference.budgetFor(0.10, MINIMUM_STEPS,
                MAXIMUM_STEPS, THRESHOLD));
        assertEquals(8, AdaptiveSamplingReference.budgetFor(0.05, MINIMUM_STEPS,
                MAXIMUM_STEPS, THRESHOLD));
        assertEquals(64, AdaptiveSamplingReference.budgetFor(1.0, MINIMUM_STEPS,
                MAXIMUM_STEPS, THRESHOLD));
        assertEquals(56, AdaptiveSamplingReference.budgetFor(HARD_RESIDUAL, MINIMUM_STEPS,
                MAXIMUM_STEPS, THRESHOLD));
        assertEquals(30, AdaptiveSamplingReference.budgetFor(0.45, MINIMUM_STEPS,
                MAXIMUM_STEPS, THRESHOLD),
                "normalized (0.45 - 0.10) / 0.90 = 0.3889, span 56 -> 21.78 rounds to 22");
    }

    @Test
    void perPixelBudgetIsTheLargerOfTheTileAndResidualBudgets() {
        double[] tileErrors = {0.0, 0.90, 0.0, 0.90, 0.05};
        double[] residuals = {0.0, 0.0, 0.8714, 0.8714, 0.05};
        int[] budgets = AdaptiveSamplingReference.allocate(tileErrors, residuals,
                MINIMUM_STEPS, MAXIMUM_STEPS, THRESHOLD);
        assertEquals(8, budgets[0], "both signals below the threshold spend the floor");
        assertEquals(58, budgets[1], "a high tile variance alone raises the budget");
        assertEquals(56, budgets[2], "a high measured residual alone raises the budget");
        assertEquals(58, budgets[3], "both signals keep the larger budget");
        assertEquals(8, budgets[4]);
        for (int budget : budgets) {
            assertTrue(budget >= MINIMUM_STEPS && budget <= MAXIMUM_STEPS);
        }
    }

    @Test
    void adaptiveAllocationBeatsTheCostMatchedFixedControl() {
        double[] tileErrors = new double[CELLS];
        double[] residuals = new double[CELLS];
        double[] errorAtFloor = new double[CELLS];
        double[] decay = new double[CELLS];
        for (int index = 0; index < CELLS; index++) {
            boolean hard = index >= CELLS - HARD_CELLS;
            tileErrors[index] = EASY_RESIDUAL;
            residuals[index] = hard ? HARD_RESIDUAL : EASY_RESIDUAL;
            errorAtFloor[index] = hard ? HARD_ERROR_AT_FLOOR : EASY_ERROR_AT_FLOOR;
            decay[index] = hard ? HARD_DECAY_PER_STEP : EASY_DECAY_PER_STEP;
        }

        int[] budgets = AdaptiveSamplingReference.allocate(tileErrors, residuals,
                MINIMUM_STEPS, MAXIMUM_STEPS, THRESHOLD);
        assertEquals(MINIMUM_STEPS, budgets[0]);
        assertEquals(56, budgets[CELLS - 1]);

        int fixedBudget = AdaptiveSamplingReference.fixedBudgetForSameCost(budgets,
                MINIMUM_STEPS);
        assertEquals(15, fixedBudget,
                "the exact two-phase spend is 960 samples, so the cost-matched fixed "
                        + "control spends 15 steps");
        assertEquals(AdaptiveSamplingReference.totalCost(budgets, MINIMUM_STEPS),
                (long) CELLS * fixedBudget,
                "the oracle comparison must be cost-matched");

        double adaptiveError = AdaptiveSamplingReference.totalRemainingError(errorAtFloor,
                budgets, MINIMUM_STEPS, decay);
        int[] fixedBudgets = new int[CELLS];
        java.util.Arrays.fill(fixedBudgets, fixedBudget);
        double fixedError = AdaptiveSamplingReference.totalRemainingError(errorAtFloor,
                fixedBudgets, MINIMUM_STEPS, decay);

        assertTrue(adaptiveError < fixedError,
                "adaptive " + adaptiveError + " must beat fixed " + fixedError);
        assertTrue(adaptiveError < fixedError * 0.95,
                "the sparse-hard-cell fixture must show a measurable gain, adaptive "
                        + adaptiveError + " vs fixed " + fixedError);
    }

    @Test
    void tileNormalSpreadRecomputesTheGlFixtureBudgets() {
        // The real-GL normal-step fixture uses the production settings
        // (min 8, max 32, threshold 0.10) and a 35/60 degree tile spread.
        assertEquals(0.0, AdaptiveSamplingReference.tileNormalError(0.0), 1.0e-12);
        assertEquals(0.361696, AdaptiveSamplingReference.tileNormalError(35.0), 1.0e-5);
        assertEquals(1.0, AdaptiveSamplingReference.tileNormalError(60.0), 1.0e-12);

        int flat = AdaptiveSamplingReference.budgetFor(
                AdaptiveSamplingReference.tileNormalError(0.0),
                MINIMUM_STEPS, GL_MAXIMUM_STEPS, THRESHOLD);
        int small = AdaptiveSamplingReference.budgetFor(
                AdaptiveSamplingReference.tileNormalError(35.0),
                MINIMUM_STEPS, GL_MAXIMUM_STEPS, THRESHOLD);
        int large = AdaptiveSamplingReference.budgetFor(
                AdaptiveSamplingReference.tileNormalError(60.0),
                MINIMUM_STEPS, GL_MAXIMUM_STEPS, THRESHOLD);
        assertEquals(8, flat);
        assertEquals(15, small);
        assertEquals(32, large);
        assertTrue(flat < small && small < large);
    }

    @Test
    void uniformFixturesAllocateTheSameBudgetAsTheFixedControl() {
        double[] tileErrors = new double[CELLS];
        double[] residuals = new double[CELLS];
        java.util.Arrays.fill(tileErrors, 0.55);
        java.util.Arrays.fill(residuals, 0.55);
        int[] budgets = AdaptiveSamplingReference.allocate(tileErrors, residuals,
                MINIMUM_STEPS, MAXIMUM_STEPS, THRESHOLD);
        for (int budget : budgets) {
            assertEquals(budgets[0], budget,
                    "a uniform measured error must allocate one uniform budget");
        }
        assertEquals(AdaptiveSamplingReference.budgetFor(0.55, MINIMUM_STEPS,
                MAXIMUM_STEPS, THRESHOLD), budgets[0]);
    }

    @Test
    void floorOnlyAllocationPaysTheFloorOnce() {
        int[] budgets = new int[CELLS];
        java.util.Arrays.fill(budgets, MINIMUM_STEPS);
        assertEquals((long) CELLS * MINIMUM_STEPS,
                AdaptiveSamplingReference.totalCost(budgets, MINIMUM_STEPS),
                "a floor-only allocation must pay the mandatory floor once, not twice");
        assertEquals(MINIMUM_STEPS,
                AdaptiveSamplingReference.fixedBudgetForSameCost(budgets, MINIMUM_STEPS),
                "the cost-matched fixed control for a floor-only allocation is the floor");
    }
}
