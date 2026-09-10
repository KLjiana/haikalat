package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.demo.pbr.ClusteredDemoSceneFactory.Request;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.environment;
import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.hiddenWindow;
import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.maxDifference;
import static com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingTestSupport.render;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Pixel-level comparison of the production clustered path against a test-only
 * full-scan reference shader that evaluates every local light in
 * frameLightIndex order.
 *
 * <p>The normal-list case uses K greater than the light count so the overflow
 * fallback cannot mask an incorrect inline list.  The overflow case keeps
 * K smaller on purpose.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glReadback", matches = "true")
class ClusterLightingGlTest {
    @Test
    void clusteredLightingMatchesFullScanReferenceForManyLights() {
        assertMatchesReference(18, 6, 128, 2, null);
    }

    @Test
    void clusteredOverflowFallbackStillMatchesFullScanReference() {
        assertMatchesReference(70, 6, 8, 2, null);
    }

    @Test
    void localLightsChangePixelsBeyondTheDirectionalBaseline() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram production = ShaderProgram.fromResource(ClusterLightingGlTest.class,
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 PbrEnvironment environment = environment(device)) {
                byte[] lit = render(window, device, production, fallbacks, environment,
                        Request.lab(18, 6, 7L), ClusteredLightingSettings.defaults(), scene -> { });
                byte[] baseline = render(window, device, production, fallbacks, environment,
                        Request.lab(0, 0, 7L), ClusteredLightingSettings.defaults(), scene -> { });
                assertTrue(maxDifference(lit, baseline) > 8,
                        "local lights must change final pixels beyond the directional baseline");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    static void assertMatchesReference(int localLights, int spots, int inlineCapacity,
                                       int tolerance, java.util.function.Consumer<Scene> setup) {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram production = ShaderProgram.fromResource(ClusterLightingGlTest.class,
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 ShaderProgram reference = ShaderProgram.fromResource(ClusterLightingGlTest.class,
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/clustered/full-scan-reference.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 PbrEnvironment environment = environment(device)) {
                ClusteredLightingSettings settings = ClusteredLightingSettings.builder()
                        .tileSize(64).zSlices(16)
                        .inlineIndicesPerCluster(inlineCapacity)
                        .maxLocalLights(128).build();
                Request request = Request.lab(localLights, spots, 11L);
                java.util.function.Consumer<Scene> configure = setup == null ? scene -> { } : setup;
                byte[] clustered = render(window, device, production, fallbacks, environment,
                        request, settings, configure);
                byte[] fullScan = render(window, device, reference, fallbacks, environment,
                        request, settings, configure);
                int maximum = maxDifference(clustered, fullScan);
                assertTrue(maximum <= tolerance,
                        "clustered and full-scan reference differ by " + maximum
                                + " (tolerance " + tolerance + ", K=" + inlineCapacity + ")");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }
}
