package com.kaleblangley.haikalat.subsystems.postprocess.adaptive;

import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounds, allocation contract and exact no-op semantics of
 * {@link AdaptiveSamplingSettings}.
 *
 * <p>The settings are the host-facing budget policy of the SSR consumer: every
 * field is bounded, the disabled value allocates nothing and the allocation is
 * monotone in the measured error with a hard minimum-sample floor.  The
 * independent double reference is exercised separately.</p>
 */
class AdaptiveSamplingSettingsTest {
    private static final int MINIMUM = 8;
    private static final int MAXIMUM = 32;
    private static final float THRESHOLD = 0.10f;

    @Test
    void disabledValueIsAnExactNoOpWithBoundedDefaults() {
        AdaptiveSamplingSettings settings = AdaptiveSamplingSettings.disabled();
        assertFalse(settings.active());
        assertFalse(settings.enabled());
        assertEquals(8, settings.minimumSteps());
        assertEquals(32, settings.maximumSteps());
        assertEquals(8, settings.tileSizePixels());
        assertTrue(settings.maximumSteps() <= AdaptiveSamplingSettings.MAXIMUM_STEP_CEILING);
        assertTrue(settings.maximumSteps() <= SsrSettings.MAXIMUM_STEPS);
        assertTrue(settings.minimumSteps() >= AdaptiveSamplingSettings.MINIMUM_STEP_FLOOR);
        assertTrue(settings.budgetSpanSteps() == 24);
        assertTrue(settings.worstCaseSteps() == 40);
        assertFalse(settings.withEnabled(false).active());
        assertTrue(AdaptiveSamplingSettings.defaults().active());
        assertEquals(settings, AdaptiveSamplingSettings.defaults().withEnabled(false));
    }

    @Test
    void allocationIsMonotoneAndSpendsOnlyTheFloorAtOrBelowTheThreshold() {
        AdaptiveSamplingSettings settings = settings();
        assertEquals(MINIMUM, settings.budgetFor(0.0));
        assertEquals(MINIMUM, settings.budgetFor(0.05));
        assertEquals(MINIMUM, settings.budgetFor(THRESHOLD));
        assertEquals(MINIMUM, settings.budgetFor(-1.0), "negative errors clamp to the floor");
        assertEquals(MAXIMUM, settings.budgetFor(1.0));
        assertEquals(MAXIMUM, settings.budgetFor(4.0), "errors above one clamp to the ceiling");

        int previous = settings.budgetFor(0.0);
        for (int step = 0; step <= 100; step++) {
            int budget = settings.budgetFor(step / 100.0);
            assertTrue(budget >= previous,
                    "budget must not decrease as the measured error grows at " + step);
            assertTrue(budget >= MINIMUM && budget <= MAXIMUM,
                    "budget must stay inside [minimumSteps, maximumSteps]");
            previous = budget;
        }
        assertEquals(MAXIMUM, previous);
    }

    @Test
    void allocationMatchesTheDocumentedFixedInputTable() {
        AdaptiveSamplingSettings settings = settings();
        // Hand-derived from the documented formula (floor 8, span 24, threshold 0.10):
        // normalized = (error - 0.10) / 0.90; budget = 8 + floor(normalized * 24 + 0.5).
        assertEquals(8, settings.budgetFor(0.05));
        assertEquals(10, settings.budgetFor(0.19));
        assertEquals(13, settings.budgetFor(0.28));
        assertEquals(20, settings.budgetFor(0.55));
        assertEquals(27, settings.budgetFor(0.82));
        assertEquals(32, settings.budgetFor(1.0));
        // The independent double reference recomputes the same table.
        double[] inputs = {0.05, 0.19, 0.28, 0.55, 0.82, 1.0};
        int[] expected = {8, 10, 13, 20, 27, 32};
        for (int index = 0; index < inputs.length; index++) {
            assertEquals(expected[index], AdaptiveSamplingReference.budgetFor(inputs[index],
                    MINIMUM, MAXIMUM, settings.errorThreshold()));
            assertEquals(expected[index], settings.budgetFor(inputs[index]));
        }
    }

    @Test
    void nonFiniteErrorsFailClosed() {
        AdaptiveSamplingSettings settings = settings();
        assertThrows(IllegalArgumentException.class, () -> settings.budgetFor(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> settings.budgetFor(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> settings.budgetFor(Double.NEGATIVE_INFINITY));
    }

    @Test
    void everyFieldFailsClosedOutsideItsBound() {
        assertThrows(IllegalArgumentException.class,
                () -> settings().withMinimumSteps(0));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withMaximumSteps(MINIMUM - 1));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withMaximumSteps(65));
        assertEquals(AdaptiveSamplingSettings.MAXIMUM_STEP_CEILING,
                AdaptiveSamplingSettings.builder().minimumSteps(64).maximumSteps(64)
                        .build().maximumSteps());
        assertThrows(IllegalArgumentException.class,
                () -> settings().withTileSizePixels(0));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withTileSizePixels(65));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withErrorThreshold(-0.01f));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withErrorThreshold(0.95f));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withErrorThreshold(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withScreenStepScalePixels(0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withScreenStepScalePixels(65.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withDepthJumpScale(0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withDepthJumpScale(9.0f));
        assertThrows(IllegalArgumentException.class,
                () -> settings().withDepthJumpScale(Float.POSITIVE_INFINITY));
    }

    @Test
    void copyHelpersPreserveTheOtherBounds() {
        AdaptiveSamplingSettings tuned = settings().withMinimumSteps(4).withMaximumSteps(48)
                .withTileSizePixels(16).withErrorThreshold(0.2f)
                .withScreenStepScalePixels(8.0f).withDepthJumpScale(2.0f);
        assertEquals(4, tuned.minimumSteps());
        assertEquals(48, tuned.maximumSteps());
        assertEquals(16, tuned.tileSizePixels());
        assertEquals(0.2f, tuned.errorThreshold(), 0.0f);
        assertEquals(8.0f, tuned.screenStepScalePixels(), 0.0f);
        assertEquals(2.0f, tuned.depthJumpScale(), 0.0f);
        assertTrue(tuned.active());
        assertEquals(4L, tuned.tileCount(32, 17));
        assertEquals(3L, tuned.tileCount(33, 1));
        assertThrows(IllegalArgumentException.class, () -> tuned.tileCount(0, 1));
        assertThrows(IllegalArgumentException.class, () -> tuned.tileCount(1, -1));
    }

    @Test
    void postProcessSettingsCarriesAdaptiveSamplingWithoutDroppingOtherEffects() {
        AdaptiveSamplingSettings adaptive = AdaptiveSamplingSettings.defaults();
        SsrSettings ssr = SsrSettings.defaults();
        PostProcessSettings settings = PostProcessSettings.defaults()
                .withSsr(ssr)
                .withAdaptiveSampling(adaptive);
        assertEquals(adaptive, settings.adaptiveSampling());
        assertEquals(ssr, settings.ssr());
        assertFalse(PostProcessSettings.defaults().adaptiveSampling().active(),
                "the shared defaults keep the feature disabled");
        assertThrows(NullPointerException.class,
                () -> PostProcessSettings.defaults().withAdaptiveSampling(null));
        assertEquals(adaptive, PostProcessSettings.builder().ssr(ssr)
                .adaptiveSampling(adaptive).build().adaptiveSampling());
        assertNotEquals(PostProcessSettings.defaults(), settings);
        // The compatibility constructor that predates adaptive sampling keeps
        // the exact disabled value.
        PostProcessSettings legacy = new PostProcessSettings(
                PostProcessSettings.defaults().colorGrading(),
                PostProcessSettings.defaults().fog(), PostProcessSettings.defaults().gtao());
        assertFalse(legacy.adaptiveSampling().active());
    }

    private static AdaptiveSamplingSettings settings() {
        return AdaptiveSamplingSettings.builder()
                .enabled(true)
                .minimumSteps(MINIMUM)
                .maximumSteps(MAXIMUM)
                .tileSizePixels(8)
                .errorThreshold(THRESHOLD)
                .screenStepScalePixels(4.0f)
                .depthJumpScale(1.0f)
                .build();
    }
}
