package com.kaleblangley.haikalat.backend;

import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL11.*;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GpuTimerGlTest {
    @Test
    void ringReuseWithoutPerFramePollingRetainsEverySubmissionIdentity() {
        try (var window = new GlfwWindow.Builder().dimensions(16,16)
                .title("GPU query ring reuse").visible(false).build()) {
            window.bindContext();GL.createCapabilities();
            try (var timer = new GpuTimer(true)) {
                for (int sequence=0;sequence<512;sequence++) {
                    // Only the test guarantees readiness before reusing a full ring.
                    if (sequence==256) glFinish();
                    assertTrue(timer.begin(sequence));
                    glClear(GL_COLOR_BUFFER_BIT);timer.end();
                }
                glFinish();
                var samples=timer.drainCompletedSamples();
                assertEquals(512,samples.size());
                for (int sequence=0;sequence<512;sequence++)
                    assertEquals(sequence,samples.get(sequence).resultSequence());
                assertTrue(timer.drainCompletedSamples().isEmpty());
                assertEquals(0,timer.sample(512).skippedSubmissions());
            }
        }
    }

    @Test
    void retainedQueriesSurviveLatestSamplePollingAndDrainExactlyOnce() {
        try (var window = new GlfwWindow.Builder().dimensions(16, 16)
                .title("GPU query retention").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            try (var timer = new GpuTimer(true)) {
                for (int sequence = 0; sequence < 32; sequence++) {
                    assertTrue(timer.begin(sequence));
                    glClear(GL_COLOR_BUFFER_BIT);
                    timer.end();
                    timer.sample(sequence);
                }
                // Only the test waits; the production collector remains nonblocking.
                glFinish();
                var samples = timer.drainCompletedSamples();
                assertEquals(32, samples.size());
                for (int sequence = 0; sequence < samples.size(); sequence++) {
                    assertEquals(sequence, samples.get(sequence).resultSequence());
                    assertEquals(GpuTimer.Status.AVAILABLE, samples.get(sequence).status());
                }
                assertTrue(timer.drainCompletedSamples().isEmpty());
                assertEquals(0, timer.sample(32).skippedSubmissions());
            }
            try (var timer = new GpuTimer()) {
                assertThrows(GlException.class, timer::drainCompletedSamples);
            }
        }
    }
}
