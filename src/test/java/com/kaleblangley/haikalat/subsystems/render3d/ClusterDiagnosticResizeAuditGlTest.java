package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.opengl.GL11.glFinish;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ClusterDiagnosticResizeAuditGlTest {
    @Test
    void oldStorageFenceCannotValidateNewStorageCounters() {
        try (var window = new GlfwWindow.Builder().dimensions(64, 64)
                .title("Cluster diagnostic resize audit").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            var scene = new Scene(new Camera(new Vector3f(0, 0, 5)));
            scene.addLight(SceneLight.point(new Vector3f(), new Vector3f(1), 1, 4));
            var pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder().vsync(false).build());
            try {
                pipeline.build();
                pipeline.execute(new GlRenderDevice());
                // Test-only wait establishes a completed OLD allocation's fence.
                glFinish();
                pipeline.resize(128, 64);
                assertNull(pipeline.activeGenerationForTest().clusteredLightingBinder.tryCounterSnapshot(),
                        "resize must invalidate fences before reading replacement counter storage");
            } finally {
                pipeline.close();
            }
        }
    }
}
