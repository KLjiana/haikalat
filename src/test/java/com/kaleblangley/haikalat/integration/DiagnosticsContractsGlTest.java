package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL43.*;

/** v0.15 context、ring、资源身份与多 session 诊断合同。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class DiagnosticsContractsGlTest {
    @Test
    void detailedResourceTrackingUsesIndependentLeases() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (FrameDriver first = driver(DiagnosticsLevel.DETAILED);
                 FrameDriver second = driver(DiagnosticsLevel.DETAILED);
                 FrameDriver basic = driver(DiagnosticsLevel.BASIC);
                 GlBuffer buffer = GlBuffer.arrayBuffer(GL_DYNAMIC_DRAW).allocate(64)) {
                assertEquals(1, GlDebug.resources().liveResources().stream()
                        .filter(item -> item.kind().equals("BUFFER")).count());
                basic.close();
                assertFalse(GlDebug.resources().liveResources().isEmpty());
                first.close();
                assertFalse(GlDebug.resources().liveResources().isEmpty());
            }
            assertTrue(GlDebug.resources().liveResources().isEmpty());
        }
    }

    @Test
    void messageRingEvictsNotificationsBeforeHighSeverityMessages() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            assertTrue(GlDebug.enableDebugCallback());
            for (int index = 0; index < 270; index++) {
                glDebugMessageInsert(GL_DEBUG_SOURCE_APPLICATION, GL_DEBUG_TYPE_OTHER,
                        10_000 + index, GL_DEBUG_SEVERITY_NOTIFICATION,
                        "notification-" + index);
            }
            glDebugMessageInsert(GL_DEBUG_SOURCE_APPLICATION, GL_DEBUG_TYPE_ERROR,
                    99_999, GL_DEBUG_SEVERITY_HIGH, "must-survive");

            GlDebug.MessageSnapshot snapshot = GlDebug.messages();
            assertEquals(256, snapshot.messages().size());
            assertTrue(snapshot.droppedCount() >= 15);
            assertTrue(snapshot.messages().stream().anyMatch(message ->
                    message.severity().equals("HIGH") && message.message().equals("must-survive")));
        }
    }

    @Test
    void resourceIdentitySurvivesNativeIdReuseAndContextsStayIsolated() {
        try (GlfwWindow firstWindow = hiddenWindow()) {
            firstWindow.bindContext();
            GLCapabilities firstCapabilities = GL.createCapabilities();
            long secondWindow = glfwCreateWindow(32, 32, "Second diagnostics context", 0L, 0L);
            assertNotEquals(0L, secondWindow);
            try (GlDebug.ResourceTrackingLease firstLease = GlDebug.acquireResourceTracking()) {
                long firstSequence = GlDebug.trackResource("BUFFER", 77, "first", 4L);
                long reusedSequence = GlDebug.trackResource("BUFFER", 77, "reused", 8L);
                assertNotEquals(firstSequence, reusedSequence);
                assertEquals(2, GlDebug.resources().liveResources().size());

                glfwMakeContextCurrent(secondWindow);
                GLCapabilities secondCapabilities = GL.createCapabilities();
                assertNotSame(firstCapabilities, secondCapabilities);
                try (GlDebug.ResourceTrackingLease secondLease = GlDebug.acquireResourceTracking()) {
                    long secondSequence = GlDebug.trackResource("TEXTURE", 77, "other-context", 16L);
                    GlDebug.ResourceSnapshot secondSnapshot = GlDebug.resources();
                    assertEquals(1, secondSnapshot.liveResources().size());
                    assertEquals(secondSequence,
                            secondSnapshot.liveResources().getFirst().resourceSequence());
                    GlDebug.closeResource(secondSequence);
                }
                GlDebug.releaseCurrentContext();
                GL.setCapabilities(null);
                glfwDestroyWindow(secondWindow);
                secondWindow = 0L;

                firstWindow.bindContext();
                GL.setCapabilities(firstCapabilities);
                assertEquals(2, GlDebug.resources().liveResources().size());
                GlDebug.closeResource(firstSequence);
                GlDebug.closeResource(reusedSequence);
            } finally {
                if (secondWindow != 0L) {
                    glfwMakeContextCurrent(secondWindow);
                    GlDebug.releaseCurrentContext();
                    GL.setCapabilities(null);
                    glfwDestroyWindow(secondWindow);
                    firstWindow.bindContext();
                    GL.setCapabilities(firstCapabilities);
                }
            }
        }
    }

    private static FrameDriver driver(DiagnosticsLevel level) {
        return new FrameDriver(RenderSettings.builder().vsync(false).build(), level);
    }
}
