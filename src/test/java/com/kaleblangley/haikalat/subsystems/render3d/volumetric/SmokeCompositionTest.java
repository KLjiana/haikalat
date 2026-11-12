package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL-free composition contract shared by the simulation schedule, the volume
 * consumer projection and the render settings.
 *
 * <p>These cases cover the deterministic fixed-step accumulator, half-open
 * injection windows, candidate projection/coverage used for the scissor and
 * fail-closed coverage cap, and the renderer's documented energy terms. Real
 * shader execution and pixels stay in the guarded {@code SmokeSimulationGlTest}.</p>
 */
class SmokeCompositionTest {
    private static SmokeSimulationSettings scheduleSettings(List<SmokeSimulationSettings.Source> sources) {
        return new SmokeSimulationSettings(true, 8, 8, 8, new Vector3f(),
                new Vector3f(4.0f), 0.1f, new Vector3f(), 1.0f, 4.0f, 0.0f, 0.0f, 3, sources);
    }

    private static SmokeSimulationSettings.Source burst() {
        return new SmokeSimulationSettings.Source(new Vector3f(0.0f, 0.0f, 0.0f), 1.0f,
                2.0f, 1.0f, 0.2f, 0.5f);
    }

    @Test
    void fixedStepAccumulatorIsDeterministicAndBounded() {
        SmokeTimeline timeline = new SmokeTimeline(scheduleSettings(List.of(burst())));
        assertEquals(0, timeline.stepCount());
        assertEquals(0, timeline.planSteps(0.05));
        assertEquals(0, timeline.stepCount(), "planning does not commit a step");
        assertEquals(1, timeline.planSteps(0.06));
        timeline.commitSteps(1);
        assertEquals(1, timeline.stepCount());
        assertEquals(2, timeline.planSteps(10.0),
                "the per-frame cap limits catch-up work");
        timeline.commitSteps(2);
        assertEquals(3, timeline.stepCount());
        assertEquals(0, timeline.planSteps(0.0));
        assertEquals(0, timeline.planSteps(1.0) - 3,
                "a zero-length frame delta must not plan more than the retained remainder");
        assertThrows(IllegalArgumentException.class, () -> timeline.planSteps(-1.0));
        assertThrows(IllegalArgumentException.class, () -> timeline.planSteps(Double.NaN));
        timeline.reset();
        assertEquals(0, timeline.stepCount());
        assertEquals(0, timeline.planSteps(0.05));
    }

    @Test
    void pauseSkipsAccumulationAndResumeDoesNotCatchUp() {
        SmokeTimeline timeline = new SmokeTimeline(scheduleSettings(List.of(burst())));
        timeline.setPaused(true);
        assertEquals(0, timeline.planSteps(10.0));
        timeline.commitSteps(0);
        assertEquals(0, timeline.stepCount());
        timeline.setPaused(false);
        assertEquals(0, timeline.planSteps(0.05), "paused wall time is not accumulated");
        assertEquals(1, timeline.planSteps(0.06));
        timeline.setPaused(true);
        assertEquals(0, timeline.planSteps(0.06));
        timeline.reset();
        assertTrue(timeline.paused(), "reset keeps the pause flag");
    }

    @Test
    void sourceRatesFollowTheHalfOpenSimulationTimeWindow() {
        SmokeTimeline timeline = new SmokeTimeline(scheduleSettings(List.of(burst())));
        assertEquals(0.0f, timeline.densityRateForStep(0, 0));
        assertEquals(0.0f, timeline.densityRateForStep(0, 1));
        assertEquals(2.0f, timeline.densityRateForStep(0, 2));
        assertEquals(2.0f, timeline.densityRateForStep(0, 4));
        assertEquals(0.0f, timeline.densityRateForStep(0, 5));
        assertEquals(0.0f, timeline.temperatureRateForStep(0, 0));
        assertEquals(1.0f, timeline.temperatureRateForStep(0, 2));
        assertEquals(0.0f, timeline.temperatureRateForStep(0, 5));
        assertThrows(IllegalArgumentException.class, () -> timeline.densityRateForStep(1, 0));
        assertEquals(0.2, timeline.timeAtStep(2), 1.0e-6);
    }

    @Test
    void sourceWindowsCompareDoubleStepTimeWithoutAHiddenTolerance() {
        SmokeSimulationSettings boundary = new SmokeSimulationSettings(true, 8, 8, 8,
                new Vector3f(), new Vector3f(4.0f), 0.02f, new Vector3f(), 1.0f, 4.0f,
                0.0f, 0.0f, 4, List.of(new SmokeSimulationSettings.Source(
                        new Vector3f(), 1.0f, 2.0f, 0.0f, 0.0f, 0.1f)));
        SmokeTimeline timeline = new SmokeTimeline(boundary);
        assertEquals(2.0f, timeline.densityRateForStep(0, 4));
        assertEquals(2.0f, timeline.densityRateForStep(0, 5),
                "5 * 0.02f is slightly below 0.1f in double, so the half-open window"
                        + " still contains step 5; the rule is not silently rounded");
        assertEquals(0.0f, timeline.densityRateForStep(0, 6));

        SmokeSimulationSettings separated = new SmokeSimulationSettings(true, 8, 8, 8,
                new Vector3f(), new Vector3f(4.0f), 0.02f, new Vector3f(), 1.0f, 4.0f,
                0.0f, 0.0f, 4, List.of(new SmokeSimulationSettings.Source(
                        new Vector3f(), 1.0f, 2.0f, 0.0f, 0.0f, 0.09f)));
        SmokeTimeline separatedTimeline = new SmokeTimeline(separated);
        assertEquals(2.0f, separatedTimeline.densityRateForStep(0, 4));
        assertEquals(0.0f, separatedTimeline.densityRateForStep(0, 5),
                "a distinguishable endpoint must stop injection at the intended step");
    }

    @Test
    void volumeProjectionCoversTheBoxAndRejectsOffscreenGrids() {
        int width = 64;
        int height = 64;
        Matrix4f viewProjection = new Matrix4f()
                .perspective((float) Math.toRadians(50.0), 1.0f, 0.125f, 64.0f)
                .mul(new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 8.0f), new Vector3f(),
                        new Vector3f(0.0f, 1.0f, 0.0f)));

        SmokeVolumePass.Projection centered = SmokeVolumePass.project(new Vector3f(),
                new Vector3f(2.0f), viewProjection, width, height);
        assertTrue(centered.visible());
        assertTrue(centered.coverage() > 0.0f && centered.coverage() <= 1.0f);
        assertEquals((float) centered.width() * centered.height() / (width * height),
                centered.coverage(), 1.0e-6);
        assertTrue(centered.x() <= 32 && 32 < centered.x() + centered.width(),
                "the scissor must contain the center pixel");
        assertTrue(centered.y() <= 32 && 32 < centered.y() + centered.height());

        SmokeVolumePass.Projection offscreen = SmokeVolumePass.project(
                new Vector3f(100.0f, 0.0f, 0.0f), new Vector3f(2.0f), viewProjection,
                width, height);
        assertFalse(offscreen.visible(), "a grid outside the frustum records no draw");

        SmokeVolumePass.Projection behind = SmokeVolumePass.project(
                new Vector3f(0.0f, 0.0f, 20.0f), new Vector3f(2.0f), viewProjection,
                width, height);
        assertFalse(behind.visible(), "a grid behind the eye records no draw");

        SmokeVolumePass.Projection straddling = SmokeVolumePass.project(
                new Vector3f(0.0f, 0.0f, 8.0f), new Vector3f(2.0f), viewProjection,
                width, height);
        assertTrue(straddling.visible());
        assertEquals(0, straddling.x());
        assertEquals(0, straddling.y());
        assertEquals(width, straddling.width());
        assertEquals(height, straddling.height());
        assertEquals(1.0f, straddling.coverage(), 0.0f,
                "a box straddling the eye plane is conservatively full-target");

        assertThrows(IllegalArgumentException.class,
                () -> SmokeVolumePass.project(new Vector3f(), new Vector3f(2.0f),
                        viewProjection, 0, height));
    }

    @Test
    void renderSettingsDocumentTheEnergyTermsAndRejectInvalidBounds() {
        SmokeRenderSettings settings = new SmokeRenderSettings(true, 0.25f, 0.75f, 2.0f,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.0f, 2.0f, 1.0f), 0.2f,
                48, 0.05f, SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 0.9f);
        assertEquals(2.0f, settings.densityExtinction(), 1.0e-6f);
        assertEquals(0.75f, settings.scatteringAlbedo(), 1.0e-6f);
        assertEquals(1.0f, new Vector3f(settings.lightDirection()).length(), 1.0e-6f);
        Vector3f light = settings.lightColor();
        light.x = 99.0f;
        assertEquals(3.0f, settings.lightColor().x, 0.0f);

        SmokeRenderSettings disabled = SmokeRenderSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertTrue(disabled.withEnabled(true).active());
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeVolumePass(disabled, 64, 64),
                "disabled render settings must not create a pass");

        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, -1.0f, 0.0f, 1.0f, new Vector3f(0, -1, 0),
                        new Vector3f(1.0f), 0.0f, 8, 0.1f,
                        SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, 0.0f, 0.0f, 1.0f, new Vector3f(0, 0, 0),
                        new Vector3f(1.0f), 0.0f, 8, 0.1f,
                        SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, 0.0f, 0.0f, 1.0f, new Vector3f(0, -1, 0),
                        new Vector3f(1.0f), 1.0f, 8, 0.1f,
                        SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, 0.0f, 0.0f, 1.0f, new Vector3f(0, -1, 0),
                        new Vector3f(1.0f), 0.0f, 0, 0.1f,
                        SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, 0.0f, 0.0f, 1.0f, new Vector3f(0, -1, 0),
                        new Vector3f(1.0f), 0.0f, 8, 0.0f,
                        SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, 0.0f, 0.0f, 1.0f, new Vector3f(0, -1, 0),
                        new Vector3f(1.0f), 0.0f, 8, 0.1f, null, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, 0.0f, 0.0f, 1.0f, new Vector3f(0, -1, 0),
                        new Vector3f(1.0f), 0.0f, 8, 0.1f,
                        SmokeRenderSettings.Blend.OCCLUDING, 1.1f, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new SmokeRenderSettings(true, 0.0f, 0.0f, 1.0f, new Vector3f(0, -1, 0),
                        new Vector3f(1.0f), 0.0f, 8, 0.1f,
                        SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 0.0f));
    }

    @Test
    void volumeReferenceMatchesTheClosedFormOverAConstantField() {
        int grid = 8;
        double[] density = new double[grid * grid * grid];
        java.util.Arrays.fill(density, 1.0);
        SmokeRenderSettings settings = new SmokeRenderSettings(true, 0.0f, 1.0f, 1.0f,
                new Vector3f(0.0f, 0.0f, -1.0f), new Vector3f(1.0f), 0.0f, 64, 0.05f,
                SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f);
        SmokeVolumeReference.Ray unbounded = new SmokeVolumeReference.Ray(
                0.0, 0.0, 8.0, 0.0, 0.0, -1.0, 1.0e30);
        SmokeVolumeReference.Contribution full = SmokeVolumeReference.contribution(settings,
                new Vector3f(), new Vector3f(2.0f), density, grid, grid, grid, unbounded);
        double phase = 1.0 / (4.0 * Math.PI);
        assertEquals(Math.exp(-4.0), full.transmittance(), 1.0e-9,
                "the constant 1/m medium over the 4 m box has T = exp(-4)");
        assertEquals(phase * (1.0 - Math.exp(-4.0)), full.red(), 1.0e-9);

        SmokeVolumeReference.Ray truncated = new SmokeVolumeReference.Ray(
                0.0, 0.0, 8.0, 0.0, 0.0, -1.0, 7.5);
        SmokeVolumeReference.Contribution clipped = SmokeVolumeReference.contribution(settings,
                new Vector3f(), new Vector3f(2.0f), density, grid, grid, grid, truncated);
        assertEquals(Math.exp(-1.5), clipped.transmittance(), 1.0e-9,
                "an opaque surface inside the box truncates the march at 1.5 m");
        assertTrue(clipped.red() < full.red());
        assertEquals(0.0, SmokeVolumeReference.contribution(settings,
                new Vector3f(), new Vector3f(2.0f), density, grid, grid, grid,
                new SmokeVolumeReference.Ray(0.0, 0.0, 8.0, 0.0, 0.0, -1.0, 5.0)).red(), 0.0,
                "a surface in front of the box contributes nothing");
    }

    @Test
    void zeroWeightIsTheExactRenderNoOpAndZeroAlbedoHasNoScatter() {
        SmokeRenderSettings zeroWeight = new SmokeRenderSettings(true, 0.3f, 0.7f, 4.0f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.0f, 16, 0.1f,
                SmokeRenderSettings.Blend.OCCLUDING, 0.0f, 1.0f);
        assertEquals(0.0f, zeroWeight.contributionWeight(), 0.0f);
        assertEquals(4.0f, zeroWeight.densityExtinction(), 1.0e-6f);
        SmokeRenderSettings absorbing = new SmokeRenderSettings(true, 1.0f, 0.0f, 4.0f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.0f, 16, 0.1f,
                SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f);
        assertEquals(0.0f, absorbing.scatteringAlbedo(), 0.0f,
                "a purely absorbing medium cannot add single scatter");
        SmokeRenderSettings transparent = new SmokeRenderSettings(true, 0.0f, 1.0f, 0.0f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.0f, 16, 0.1f,
                SmokeRenderSettings.Blend.ADDITIVE, 1.0f, 1.0f);
        assertEquals(0.0f, transparent.densityExtinction(), 0.0f);
        assertEquals(1.0f, transparent.scatteringAlbedo(), 0.0f);
    }
}
