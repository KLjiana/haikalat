package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Request;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;
import java.util.function.Consumer;

import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.environment;
import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.hiddenWindow;
import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.maxDifference;
import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Rotated-view audit for the cluster Z lookup.
 *
 * <p>A view-space depth derived from the wrong matrix component produces the
 * wrong logarithmic slice whenever the camera is not axis aligned, so these
 * cases deliberately move and rotate the camera and keep K above the light
 * count to exercise the normal inline list rather than the overflow fallback.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glReadback", matches = "true")
class ClusterRotatedViewAuditGlTest {
    private static final float TOLERANCE = 2.0f;

    @Test
    void rotatedNormalListMatchesFullScanReference() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram production = ShaderProgram.fromResource(
                    ClusterRotatedViewAuditGlTest.class,
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 ShaderProgram reference = ShaderProgram.fromResource(
                         ClusterRotatedViewAuditGlTest.class,
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/clustered/full-scan-reference.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 PbrEnvironment environment = environment(device)) {
                assertMatches(window, device, production, reference, fallbacks, environment,
                        18, 6, 128, camera(12.0f, 4.5f, 12.0f, -135.0f, -15.0f));
                assertMatches(window, device, production, reference, fallbacks, environment,
                        70, 6, 8, camera(12.0f, 4.5f, 12.0f, -135.0f, -15.0f));
            }
        }
    }

    @Test
    void yawAndPitchSweepMatchesFullScanReference() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            List<Float> yaws = List.of(0.0f, -45.0f, -90.0f, -135.0f, 180.0f);
            List<Float> pitches = List.of(-20.0f, 0.0f, 20.0f);
            try (ShaderProgram production = ShaderProgram.fromResource(
                    ClusterRotatedViewAuditGlTest.class,
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 ShaderProgram reference = ShaderProgram.fromResource(
                         ClusterRotatedViewAuditGlTest.class,
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/clustered/full-scan-reference.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 PbrEnvironment environment = environment(device)) {
                for (float yaw : yaws) {
                    for (float pitch : pitches) {
                        assertMatches(window, device, production, reference, fallbacks,
                                environment, 18, 6, 128,
                                camera(12.0f, 4.5f, 12.0f, yaw, pitch));
                    }
                }
            }
        }
    }

    @Test
    void crossSliceDepthRangeMatchesFullScanReference() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram production = ShaderProgram.fromResource(
                    ClusterRotatedViewAuditGlTest.class,
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 ShaderProgram reference = ShaderProgram.fromResource(
                         ClusterRotatedViewAuditGlTest.class,
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/clustered/full-scan-reference.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 PbrEnvironment environment = environment(device)) {
                assertMatches(window, device, production, reference, fallbacks, environment,
                        18, 6, 128, camera(0.0f, 2.0f, 7.0f, -90.0f, -20.0f));
                assertMatches(window, device, production, reference, fallbacks, environment,
                        18, 6, 128, camera(0.0f, 10.0f, 30.0f, -75.0f, -25.0f));
            }
        }
    }

    private static void assertMatches(GlfwWindow window, GlRenderDevice device,
                                      ShaderProgram production, ShaderProgram reference,
                                      PbrFallbackTextures fallbacks, PbrEnvironment environment,
                                      int localLights, int spots, int inlineCapacity,
                                      Consumer<Scene> camera) {
        ClusteredLightingSettings settings = ClusteredLightingSettings.builder()
                .tileSize(64).zSlices(16)
                .inlineIndicesPerCluster(inlineCapacity)
                .maxLocalLights(128).build();
        Request request = Request.lab(localLights, spots, 11L);
        byte[] clustered = render(window, device, production, fallbacks, environment,
                request, settings, camera);
        byte[] fullScan = render(window, device, reference, fallbacks, environment,
                request, settings, camera);
        float maximum = maxDifference(clustered, fullScan);
        assertTrue(maximum <= TOLERANCE,
                "rotated clustered view differs from full scan by " + maximum
                        + " (K=" + inlineCapacity + ")");
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static Consumer<Scene> camera(float x, float y, float z, float yaw, float pitch) {
        return scene -> {
            Camera camera = scene.camera();
            camera.setPosition(new Vector3f(x, y, z));
            camera.setYaw(yaw);
            camera.setPitch(pitch);
        };
    }
}
