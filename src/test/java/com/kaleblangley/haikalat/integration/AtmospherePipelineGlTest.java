package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.environment.AtmosphereSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Pipeline-level real-GL contract for the atmosphere background wiring.
 *
 * <p>Proves that {@link RenderPipeline#atmosphere(AtmosphereSettings)} replaces
 * the background (a pipeline with no environment renders a non-black sky), that
 * exposure changes at a frame boundary scale the captured HDR radiance, that a
 * LUT extent change rebuilds the generation, and that the atmosphere and the
 * stylized outdoor sky are mutually exclusive.  The numeric sky reference lives
 * in {@code render3d.environment.AtmosphereSkyPassGlTest}; this case only owns
 * the production wiring.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class AtmospherePipelineGlTest {
    private static final int SIZE = 96;

    @Test
    void atmosphereReplacesTheBackgroundAndPublishesExposureChanges() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Atmosphere pipeline test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            int width = window.width();
            int height = window.height();
            Camera camera = new Camera(new Vector3f(0.0f, 1_000.0f, 0.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
            Scene scene = new Scene(camera);
            scene.addLight(SceneLight.shadowedDirectional(
                    new Vector3f(0.2f, -1.0f, 0.1f).normalize(),
                    new Vector3f(1.0f, 1.0f, 1.0f), 3.0f));
            AtmosphereSettings base = AtmosphereSettings.earth(AtmosphereSettings.Quality.LOW)
                    .withSunDirection(new Vector3f(-0.35f, -0.55f, -1.0f).normalize())
                    .withExposure(1.0f);

            RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings(), null)
                    .atmosphere(base);
            try {
                pipeline.build();
                Vector3f lightDirection = scene.lights().get(0).direction();
                assertEquals(base.sunDirection().x, lightDirection.x, 1.0e-6f,
                        "the atmosphere sun direction must drive the directional light");
                assertEquals(base.sunDirection().y, lightDirection.y, 1.0e-6f);
                assertEquals(base.sunDirection().z, lightDirection.z, 1.0e-6f);
                pipeline.execute(device);
                float[] first = pipeline.captureLinearHdrRgbaFloat();
                assertEquals(width * height * 4, first.length);
                float centre = luminance(first, width, height, width / 2, height / 2);
                assertTrue(centre > 1.0e-3f,
                        "the atmosphere background must render non-black radiance, got " + centre);
                assertTrue(Float.isFinite(centre));

                pipeline.applyAtmosphere(base.withExposure(2.0f));
                pipeline.execute(device);
                float[] brighter = pipeline.captureLinearHdrRgbaFloat();
                float centreBrighter = luminance(brighter, width, height, width / 2, height / 2);
                assertEquals(2.0, centreBrighter / centre, 0.05,
                        "a frame-boundary exposure change must scale the HDR sky");

                // A LUT extent change needs a replacement generation, not just a
                // scalar update; the new generation must still render the sky.
                pipeline.applyAtmosphere(AtmosphereSettings.earth(AtmosphereSettings.Quality.MEDIUM)
                        .withSunDirection(new Vector3f(-0.35f, -0.55f, -1.0f).normalize())
                        .withExposure(1.0f));
                pipeline.execute(device);
                float[] rebuilt = pipeline.captureLinearHdrRgbaFloat();
                float centreRebuilt = luminance(rebuilt, width, height, width / 2, height / 2);
                assertTrue(centreRebuilt > 1.0e-3f,
                        "the rebuilt LUT generation must keep rendering the sky");
                assertEquals(GL_NO_ERROR, glGetError());
            } finally {
                pipeline.close();
            }

            RenderPipeline stylized = new RenderPipeline(window, scene, null, renderSettings(), null)
                    .outdoorEnvironment(OutdoorEnvironmentSettings.clearDay());
            try {
                assertThrows(IllegalStateException.class, () -> stylized.atmosphere(base),
                        "atmosphere and stylized sky must be rejected before build");
            } finally {
                stylized.close();
            }

            RenderPipeline afterBuild = new RenderPipeline(window, scene, null, renderSettings(), null);
            try {
                afterBuild.build();
                assertThrows(IllegalStateException.class,
                        () -> afterBuild.atmosphere(base),
                        "the atmosphere must be configured before build or via applyAtmosphere");
            } finally {
                afterBuild.close();
            }
        }
    }

    private static float luminance(float[] image, int width, int height, int x, int y) {
        int offset = (y * width + x) * 4;
        return 0.2126f * image[offset] + 0.7152f * image[offset + 1]
                + 0.0722f * image[offset + 2];
    }

    private static RenderSettings renderSettings() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }
}
