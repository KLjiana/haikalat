package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.glIsTexture;

/** 验证 environment 预计算中途失败时的真实 GL 资源事务语义。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class EnvironmentPreprocessorFailureGlTest {
    @Test
    void everyInjectedStageClosesCreatedTexturesExactlyOnceInReverseOrder() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (GlDebug.ResourceTrackingLease ignored = GlDebug.acquireResourceTracking()) {
                for (EnvironmentPreprocessor.FailurePoint point : List.of(
                        EnvironmentPreprocessor.FailurePoint.AFTER_ENVIRONMENT_CUBE,
                        EnvironmentPreprocessor.FailurePoint.BEFORE_PREFILTER_MIP,
                        EnvironmentPreprocessor.FailurePoint.AFTER_BRDF_LUT)) {
                    Tracker tracker = new Tracker(false);
                    try (Texture2D source = Texture2D.fromHdrResource(getClass(),
                            "/pbr/studio-small.hdr", false)) {
                        IllegalStateException failure = assertThrows(IllegalStateException.class,
                                () -> EnvironmentPreprocessor.preprocess(new GlRenderDevice(), source,
                                        PbrEnvironmentSettings.testQuality(), point, tracker));
                        assertTrue(failure.getMessage().contains(point.name()));
                    }
                    List<String> expectedCloseOrder = new ArrayList<>(tracker.created.keySet());
                    Collections.reverse(expectedCloseOrder);
                    assertEquals(expectedCloseOrder, tracker.closed,
                            "partial environment resources must close in reverse creation order");
                    assertEquals(tracker.closed.size(), tracker.closed.stream().distinct().count(),
                            "each resource must close exactly once");
                    tracker.created.values().forEach(id -> assertFalse(glIsTexture(id),
                            "failed preprocessing leaked texture " + id));
                    assertTrue(GlDebug.resources().liveResources().isEmpty(),
                            "failed preprocessing left tracked resources at " + point);
                    GlDebug.checkError("PBR injected failure " + point);
                }
            }
        }
    }

    @Test
    void cleanupFailureIsSuppressedWithoutStoppingRemainingCleanup() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (GlDebug.ResourceTrackingLease ignored = GlDebug.acquireResourceTracking()) {
                Tracker tracker = new Tracker(true);
                try (Texture2D source = Texture2D.fromHdrResource(getClass(),
                        "/pbr/studio-small.hdr", false)) {
                    IllegalStateException failure = assertThrows(IllegalStateException.class,
                            () -> EnvironmentPreprocessor.preprocess(new GlRenderDevice(), source,
                                    PbrEnvironmentSettings.testQuality(),
                                    EnvironmentPreprocessor.FailurePoint.AFTER_BRDF_LUT, tracker));
                    assertTrue(failure.getMessage().contains("AFTER_BRDF_LUT"));
                    assertEquals(1, failure.getSuppressed().length);
                    assertTrue(failure.getSuppressed()[0].getMessage().contains("synthetic close observer"));
                }
                assertEquals(tracker.created.size(), tracker.closed.size(),
                        "one cleanup exception must not abort later resource closes");
                tracker.created.values().forEach(id -> assertFalse(glIsTexture(id)));
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(32, 32)
                .title("PBR failure cleanup GL test").visible(false).build();
    }

    private static final class Tracker implements EnvironmentPreprocessor.LifecycleObserver {
        private final Map<String, Integer> created = new LinkedHashMap<>();
        private final List<String> closed = new ArrayList<>();
        private final boolean failFirstClose;

        private Tracker(boolean failFirstClose) {
            this.failFirstClose = failFirstClose;
        }

        @Override
        public void created(String kind, int id) {
            assertTrue(created.putIfAbsent(kind, id) == null, "duplicate create event: " + kind);
        }

        @Override
        public void closed(String kind, int id) {
            assertEquals(created.get(kind), id);
            closed.add(kind);
            if (failFirstClose && closed.size() == 1) {
                throw new IllegalStateException("synthetic close observer failure");
            }
        }
    }
}
