package com.kaleblangley.haikalat.subsystems.render3d.environment;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL45.glGetTextureImage;

/**
 * Real-GL contract for the production {@link AtmosphereSkyPass}.
 *
 * <p>Each case drives the pass directly into an RGBA16F FBO, reads the HDR
 * result or the LUTs back, and compares it with the independent double
 * reference.  These cases cover the sky colour relations, sun azimuth/elevation
 * response, exposure scaling, LUT resize and close/leak lifecycle; they are
 * guarded by {@code -Dhaikalat.glSmoke=true} and never run in the JVM suite.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class AtmosphereSkyPassGlTest {
    private static final int SIZE = 64;
    private static final float CAMERA_FOV_DEGREES = 45.0f;
    private static final float OBSERVER_ALTITUDE_METERS = 1_000.0f;

    @Test
    void zenithIsBluerAndDarkerThanHorizonAndMatchesTheIndependentReference() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            AtmosphereSettings settings = AtmosphereSettings.earth()
                    .withSunDirection(sunDirection(60.0f, 0.0f));
            try (Framebuffer target = colorTarget();
                 AtmosphereSkyPass pass = new AtmosphereSkyPass(settings)) {
                clearTarget(target);
                FloatBuffer zenithPixels = render(device, pass, target, camera(0.0f, 89.0f),
                        settings);
                FloatBuffer horizonPixels = render(device, pass, target, camera(90.0f, 0.0f),
                        settings);
                assertBounded(zenithPixels, "zenith");
                assertBounded(horizonPixels, "horizon");
                Vector3f zenith = color(zenithPixels, SIZE / 2, SIZE / 2);
                Vector3f horizon = color(horizonPixels, SIZE / 2, SIZE / 2);
                double zenithLuminance = luminance(zenith);
                double horizonLuminance = luminance(horizon);
                assertTrue(horizonLuminance > zenithLuminance * 2.0,
                        "the horizon must be brighter than the zenith");
                assertTrue(blueRed(zenith) > blueRed(horizon) * 1.5,
                        "the zenith must be substantially bluer than the horizon");

                double[] expectedZenith = AtmosphereReference.singleScattering(settings,
                        OBSERVER_ALTITUDE_METERS, 0.0, 0.0, 2048, 512);
                double[] expectedHorizon = AtmosphereReference.singleScattering(settings,
                        OBSERVER_ALTITUDE_METERS, Math.PI * 0.5, 0.0, 2048, 512);
                double exposure = settings.exposure();
                assertChannelClose(expectedZenith[0] * exposure, zenith.x, 0.15, "zenith red");
                assertChannelClose(expectedZenith[1] * exposure, zenith.y, 0.15, "zenith green");
                assertChannelClose(expectedZenith[2] * exposure, zenith.z, 0.15, "zenith blue");
                assertChannelClose(AtmosphereReference.luminance(expectedHorizon) * exposure,
                        horizonLuminance, 0.15, "horizon luminance");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void sunAzimuthMovesTheBrightSideAndElevationChangesTheSky() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Camera camera = camera(90.0f, 5.0f);
            try (Framebuffer target = colorTarget();
                 AtmosphereSkyPass pass = new AtmosphereSkyPass(AtmosphereSettings.earth())) {
                clearTarget(target);
                AtmosphereSettings east = AtmosphereSettings.earth()
                        .withSunDirection(sunDirection(20.0f, 40.0f));
                AtmosphereSettings west = AtmosphereSettings.earth()
                        .withSunDirection(sunDirection(20.0f, -40.0f));
                FloatBuffer eastPixels = render(device, pass, target, camera, east);
                FloatBuffer westPixels = render(device, pass, target, camera, west);
                int eastNear = nearerSunPixel(camera, east, 8, 56);
                int westNear = nearerSunPixel(camera, west, 8, 56);
                assertNotEquals(eastNear, westNear,
                        "the bright side must swap when the sun crosses the view direction");
                assertTrue(pixelLuminance(eastPixels, eastNear, 32)
                                > pixelLuminance(eastPixels, 8 + 56 - eastNear, 32) * 1.15,
                        "the sun-side sky must be brighter");
                assertTrue(pixelLuminance(westPixels, westNear, 32)
                                > pixelLuminance(westPixels, 8 + 56 - westNear, 32) * 1.15,
                        "the sun-side sky must stay brighter after the sun moves");

                AtmosphereSettings highSun = AtmosphereSettings.earth()
                        .withSunDirection(sunDirection(60.0f, 0.0f));
                AtmosphereSettings lowSun = AtmosphereSettings.earth()
                        .withSunDirection(sunDirection(20.0f, 0.0f));
                FloatBuffer highZenith = render(device, pass, target, camera(0.0f, 89.0f),
                        highSun);
                FloatBuffer lowZenith = render(device, pass, target, camera(0.0f, 89.0f),
                        lowSun);
                assertTrue(pixelLuminance(highZenith, SIZE / 2, SIZE / 2)
                                > pixelLuminance(lowZenith, SIZE / 2, SIZE / 2) * 1.3,
                        "lowering the sun must dim the zenith");
                FloatBuffer highHorizon = render(device, pass, target, camera(90.0f, 0.0f),
                        highSun);
                FloatBuffer lowHorizon = render(device, pass, target, camera(90.0f, 0.0f),
                        lowSun);
                assertTrue(pixelLuminance(lowHorizon, SIZE / 2, SIZE / 2)
                                > pixelLuminance(highHorizon, SIZE / 2, SIZE / 2) * 1.15,
                        "a lower sun must brighten the sun-side horizon");

                AtmosphereSettings disc = AtmosphereSettings.earth()
                        .withSunDirection(sunDirection(60.0f, 0.0f))
                        .withSunAngularRadius(0.05f)
                        .withSunDiscIntensity(1_000.0f);
                AtmosphereSettings noDisc = disc.withSunDiscIntensity(0.0f);
                FloatBuffer withDisc = render(device, pass, target, camera(90.0f, 60.0f), disc);
                FloatBuffer withoutDisc = render(device, pass, target, camera(90.0f, 60.0f),
                        noDisc);
                float discCenter = pixelLuminance(withDisc, SIZE / 2, SIZE / 2);
                float plainCenter = pixelLuminance(withoutDisc, SIZE / 2, SIZE / 2);
                assertTrue(discCenter > plainCenter + 5.0,
                        "the analytic solar disc must be visible at the configured sun direction");
                assertTrue(discCenter > pixelLuminance(withDisc, 2, 2) + 5.0,
                        "the disc must be local to the sun direction");
                assertBounded(withDisc, "sun disc");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void exposureScalesHdrRadianceLinearly() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            AtmosphereSettings base = AtmosphereSettings.earth()
                    .withSunDirection(sunDirection(30.0f, 0.0f));
            try (Framebuffer target = colorTarget();
                 AtmosphereSkyPass pass = new AtmosphereSkyPass(base)) {
                clearTarget(target);
                FloatBuffer low = render(device, pass, target, camera(90.0f, 0.0f),
                        base.withExposure(0.01f));
                FloatBuffer high = render(device, pass, target, camera(90.0f, 0.0f),
                        base.withExposure(0.02f));
                FloatBuffer black = render(device, pass, target, camera(90.0f, 0.0f),
                        base.withExposure(0.0f));
                assertBounded(low, "exposure 0.01");
                assertBounded(high, "exposure 0.02");
                int compared = 0;
                for (int index = 0; index < low.capacity(); index++) {
                    float lowValue = low.get(index);
                    float highValue = high.get(index);
                    if (lowValue > 1.0e-3f) {
                        assertEquals(2.0, highValue / lowValue, 0.02,
                                "linear exposure at channel " + index);
                        compared++;
                    }
                }
                assertTrue(compared > SIZE * SIZE, "exposure test must compare a substantial area");
                for (int index = 0; index < black.capacity(); index++) {
                    assertEquals(0.0f, black.get(index), 1.0e-6f,
                            "zero exposure must produce zero radiance");
                }
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void lutResizeRebuildAndCloseDoNotLeak() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            AtmosphereSettings settings = AtmosphereSettings.earth();
            try (Framebuffer target = colorTarget()) {
                try (GlDebug.ResourceTrackingLease tracking = GlDebug.acquireResourceTracking()) {
                    var baseline = GlDebug.resources();
                    AtmosphereSkyPass pass = new AtmosphereSkyPass(settings);
                    try {
                        clearTarget(target);
                        render(device, pass, target, camera(0.0f, 89.0f), settings);
                        assertEquals(256, pass.transmittanceLutWidth());
                        assertEquals(64, pass.transmittanceLutHeight());
                        assertEquals(32, pass.skyViewLutWidth());
                        assertEquals(32, pass.skyViewLutHeight());

                        FloatBuffer transmittance = readTexture(pass.transmittanceLutId(),
                                pass.transmittanceLutWidth(), pass.transmittanceLutHeight());
                        int width = pass.transmittanceLutWidth();
                        int height = pass.transmittanceLutHeight();
                        assertEquals(1.0f, texel(transmittance, width - 1, height - 1, width).x,
                                0.01f);
                        assertEquals(1.0f, texel(transmittance, width - 1, height - 1, width).y,
                                0.01f);
                        assertEquals(1.0f, texel(transmittance, width - 1, height - 1, width).z,
                                0.01f);

                        double texelRadius = settings.planetRadiusMeters()
                                + settings.atmosphereThicknessMeters()
                                * Math.pow(0.5 / width, 2.0);
                        double zenithMu = (height - 0.5) / height;
                        double[] expectedZenithTexel = AtmosphereReference.transmittance(settings,
                                texelRadius, zenithMu, 8_192);
                        Vector3f zenithTexel = texel(transmittance, 0, height - 1, width);
                        assertChannelClose(expectedZenithTexel[0], zenithTexel.x, 0.05,
                                "transmittance red");
                        assertChannelClose(expectedZenithTexel[1], zenithTexel.y, 0.05,
                                "transmittance green");
                        assertChannelClose(expectedZenithTexel[2], zenithTexel.z, 0.05,
                                "transmittance blue");
                        double[] expectedHorizonTexel = AtmosphereReference.transmittance(settings,
                                texelRadius, 0.5 / height, 8_192);
                        Vector3f horizonTexel = texel(transmittance, 0, 0, width);
                        assertChannelClose(expectedHorizonTexel[0], horizonTexel.x, 0.05,
                                "horizon transmittance red");
                        assertChannelClose(expectedHorizonTexel[1], horizonTexel.y, 0.05,
                                "horizon transmittance green");
                        assertChannelClose(expectedHorizonTexel[2], horizonTexel.z, 0.05,
                                "horizon transmittance blue");
                        assertTrue(horizonTexel.z < horizonTexel.x * 0.05,
                                "blue must be extinguished much more at the horizon");

                        FloatBuffer skyView = readTexture(pass.skyViewLutId(),
                                pass.skyViewLutWidth(), pass.skyViewLutHeight());
                        assertBounded(skyView, "sky-view LUT");
                        int skyWidth = pass.skyViewLutWidth();
                        int row = pass.skyViewLutHeight() / 2;
                        Vector3f skyZenith = texel(skyView, 0, row, skyWidth);
                        Vector3f skyHorizon = texel(skyView, skyWidth - 1, row, skyWidth);
                        assertTrue(luminance(skyHorizon) > luminance(skyZenith) * 2.0,
                                "the sky-view LUT must contain the horizon haze");

                        int transmittanceId = pass.transmittanceLutId();
                        int skyViewId = pass.skyViewLutId();
                        AtmosphereSettings low = AtmosphereSettings.earth(
                                AtmosphereSettings.Quality.LOW);
                        pass.rebuild(low);
                        assertEquals(128, pass.transmittanceLutWidth());
                        assertEquals(32, pass.transmittanceLutHeight());
                        assertEquals(16, pass.skyViewLutWidth());
                        assertEquals(16, pass.skyViewLutHeight());
                        assertNotEquals(transmittanceId, pass.transmittanceLutId());
                        assertNotEquals(skyViewId, pass.skyViewLutId());
                        render(device, pass, target, camera(90.0f, 0.0f), low);
                        FloatBuffer rebuilt = readTexture(pass.transmittanceLutId(),
                                pass.transmittanceLutWidth(), pass.transmittanceLutHeight());
                        assertEquals(1.0f, texel(rebuilt, pass.transmittanceLutWidth() - 1,
                                pass.transmittanceLutHeight() - 1,
                                pass.transmittanceLutWidth()).z, 0.01f);
                        int rebuiltTransmittance = pass.transmittanceLutId();
                        int rebuiltSkyView = pass.skyViewLutId();
                        pass.rebuild(low);
                        assertEquals(rebuiltTransmittance, pass.transmittanceLutId());
                        assertEquals(rebuiltSkyView, pass.skyViewLutId());

                        pass.close();
                        assertThrows(GlException.class, () -> pass.record(device.createCommandBuffer(),
                                new Matrix4f(), new Matrix4f(), OBSERVER_ALTITUDE_METERS, low));
                        pass.close();
                    } finally {
                        pass.close();
                    }
                    var after = GlDebug.resources();
                    assertEquals(baseline.liveResources(), after.liveResources(),
                            "all atmosphere pass resources must be released");
                    assertEquals(baseline.estimatedBytes(), after.estimatedBytes());
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    private static FloatBuffer render(GlRenderDevice device, AtmosphereSkyPass pass,
                                      Framebuffer target, Camera camera,
                                      AtmosphereSettings settings) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        var commands = device.createCommandBuffer();
        pass.record(commands, camera, SIZE, SIZE, OBSERVER_ALTITUDE_METERS, settings);
        device.execute(commands);
        target.bind();
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static void clearTarget(Framebuffer target) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static FloatBuffer readTexture(int texture, int width, int height) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
        glGetTextureImage(texture, 0, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static Framebuffer colorTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .build());
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Atmosphere sky pass test").visible(false).build();
    }

    private static Camera camera(float yaw, float pitch) {
        return new Camera(new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f), yaw, pitch);
    }

    private static Vector3f sunDirection(float elevationDegrees, float azimuthDegrees) {
        double elevation = Math.toRadians(elevationDegrees);
        double azimuth = Math.toRadians(azimuthDegrees);
        return new Vector3f((float) (-Math.cos(elevation) * Math.sin(azimuth)),
                (float) -Math.sin(elevation),
                (float) (-Math.cos(elevation) * Math.cos(azimuth)));
    }

    private static int nearerSunPixel(Camera camera, AtmosphereSettings settings,
                                      int left, int right) {
        Vector3f toSun = settings.sunDirection().negate();
        Vector3f leftDirection = directionForPixel(camera, left, SIZE / 2);
        Vector3f rightDirection = directionForPixel(camera, right, SIZE / 2);
        assertTrue(Math.abs(leftDirection.dot(toSun) - rightDirection.dot(toSun)) > 0.1,
                "the chosen pixels must straddle the sun azimuth");
        return leftDirection.dot(toSun) > rightDirection.dot(toSun) ? left : right;
    }

    private static Vector3f directionForPixel(Camera camera, int x, int y) {
        Vector3f front = camera.front();
        Vector3f right = new Vector3f(front).cross(0.0f, 1.0f, 0.0f).normalize();
        Vector3f up = new Vector3f(right).cross(front).normalize();
        float ndcX = (x + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / SIZE * 2.0f - 1.0f;
        float tangent = (float) Math.tan(Math.toRadians(CAMERA_FOV_DEGREES * 0.5));
        return new Vector3f(front).fma(ndcX * tangent, right).fma(ndcY * tangent, up)
                .normalize();
    }

    private static Vector3f color(FloatBuffer pixels, int x, int y) {
        int index = (y * SIZE + x) * 4;
        return new Vector3f(pixels.get(index), pixels.get(index + 1), pixels.get(index + 2));
    }

    private static Vector3f texel(FloatBuffer pixels, int x, int y, int width) {
        int index = (y * width + x) * 4;
        return new Vector3f(pixels.get(index), pixels.get(index + 1), pixels.get(index + 2));
    }

    private static float pixelLuminance(FloatBuffer pixels, int x, int y) {
        return (float) luminance(color(pixels, x, y));
    }

    private static double luminance(Vector3f color) {
        return 0.2126 * color.x + 0.7152 * color.y + 0.0722 * color.z;
    }

    private static double luminance(double[] color) {
        return 0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2];
    }

    private static double blueRed(Vector3f color) {
        return color.z / Math.max(color.x, 1.0e-12);
    }

    private static void assertChannelClose(double expected, double actual, double relativeTolerance,
                                           String message) {
        double tolerance = Math.max(Math.abs(expected) * relativeTolerance, 1.0e-4);
        assertEquals(expected, actual, tolerance, message);
    }

    private static void assertBounded(FloatBuffer pixels, String label) {
        for (int index = 0; index < pixels.capacity(); index++) {
            float value = pixels.get(index);
            assertTrue(Float.isFinite(value), label + " must be finite");
            assertTrue(value >= 0.0f && value < 1.0e4f,
                    label + " must be finite, non-negative and bounded");
        }
    }
}
