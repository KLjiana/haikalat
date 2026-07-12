package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.graph.FrameProfile;
import com.kaleblangley.haikalat.core.graph.PassProfile;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderErrorCategory;
import com.kaleblangley.haikalat.backend.RenderErrors;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityTest {
    @Test
    void frameProfileAggregatesGpuTime() {
        FrameProfile profile = new FrameProfile(5_000_000L, List.of(
                new PassProfile("Geometry", 1_000_000L, 2_000_000L),
                new PassProfile("Present", 500_000L, 1_000_000L)));

        assertEquals(3_000_000L, profile.totalGpuNanos());
        assertEquals(5.0, profile.cpuFrameMillis(), 0.0);
        assertEquals(3.0, profile.totalGpuMillis(), 0.0);
    }

    @Test
    void renderStatisticsKeepsGraphProfileAcrossEndFrame() {
        RenderStatistics stats = new RenderStatistics();
        stats.beginFrame();
        stats.recordGraphProfile(new FrameProfile(0L, List.of(new PassProfile("Geometry", 1L, 2L))));
        stats.endFrame();

        assertEquals(1, stats.frameCount());
        assertEquals(1, stats.lastFrameProfile().passes().size());
        assertEquals("Geometry", stats.lastFrameProfile().passes().get(0).passName());
        assertTrue(stats.lastFrameDurationNanos() >= 0L);
    }

    @Test
    void debugOverlaySnapshotUsesStatisticsAndAaMode() {
        RenderStatistics stats = new RenderStatistics();
        stats.beginFrame();
        stats.recordGraphProfile(new FrameProfile(0L, List.of(new PassProfile("Geometry", 1L, 2_500_000L))));
        stats.endFrame();

        DebugOverlaySnapshot overlay = DebugOverlaySnapshot.from(stats, 7, 42, AntiAliasingMode.FXAA);

        assertEquals(7, overlay.drawCalls());
        assertEquals(42, overlay.instanceCount());
        assertEquals(2.5, overlay.gpuMillis(), 0.0);
        assertEquals(AntiAliasingMode.FXAA, overlay.activeAntiAliasingMode());
    }

    @Test
    void presentFpsUsesCompletedSwapsInsteadOfCpuSubmissionRate() {
        AtomicLong nanos = new AtomicLong();
        RenderStatistics stats = new RenderStatistics(nanos::get);

        stats.beginFrame();
        nanos.addAndGet(2_000_000L);
        stats.endFrame();
        stats.recordPresent();
        for (int i = 0; i < 100; i++) {
            nanos.addAndGet(10_000_000L);
            stats.recordPresent();
        }

        RenderStatistics.Snapshot snapshot = stats.snapshot();
        assertEquals(100.0, snapshot.presentFps(), 0.000_001);
        assertEquals(2.0, snapshot.cpuSubmitMillis(), 0.000_001);
        assertEquals(101L, snapshot.presentedFrames());
        assertEquals(1L, snapshot.submittedFrames());
    }

    @Test
    void frameDriverRecordsPresentOnlyAfterSwapCompletes() {
        FrameDriver driver = new FrameDriver(RenderSettings.builder().build());
        try {
            driver.present(() -> assertEquals(0L,
                    driver.statistics().presentedFrameCount()));
            assertEquals(1L, driver.statistics().presentedFrameCount());
        } finally {
            driver.close();
        }
    }

    @Test
    void renderErrorsClassifyCommonFailures() {
        assertEquals(RenderErrorCategory.SHADER, RenderErrors.categoryFor("Shader compile failed"));
        assertEquals(RenderErrorCategory.FRAMEBUFFER, RenderErrors.categoryFor("Framebuffer incomplete"));
        assertEquals(RenderErrorCategory.RESOURCE, RenderErrors.categoryFor("Texture resource missing"));
        assertEquals(RenderErrorCategory.STATE, RenderErrors.categoryFor("Depth state invalid"));
        assertEquals(RenderErrorCategory.UNKNOWN, RenderErrors.categoryFor("Something else"));

        GlException exception = new GlException("Framebuffer incomplete");
        assertEquals(RenderErrorCategory.FRAMEBUFFER, exception.category());
    }
}
