package com.kaleblangley.haikalat.subsystems.render3d.water;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * Real-GL contract for the water surface shader itself.
 *
 * <p>Renders the production {@link WaterSurfacePass} against a synthetic shared
 * depth buffer and compares float readback with an independent CPU
 * implementation of the documented shading model.  The pipeline-level enabled,
 * disabled, bounds and grazing cases live in the integration GL test; this case
 * proves the analytic normal, absorption and shoreline values.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class WaterSurfacePassGlTest {
    private static final int SIZE = 64;
    private static final float BACKGROUND_RED = 0.2f;
    private static final float BACKGROUND_GREEN = 0.4f;
    private static final float BACKGROUND_BLUE = 0.6f;
    private static final float LEVEL = 0.0f;

    @Test
    void absorptionShoreAndBoundsMatchTheReferenceModel() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Camera camera = new Camera(new Vector3f(0.0f, 6.0f, 10.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -40.0f);
            Matrix4f projection = CameraProjection.stable(camera, SIZE, SIZE, new Matrix4f());
            Matrix4f view = camera.getViewMatrix(new Matrix4f());

            WaterSurfaceSettings deep = WaterSurfaceSettings.builder()
                    .enabled(true).level(LEVEL).bounds(-6.0f, -6.0f, 6.0f, 6.0f)
                    .waveWavelength(8.0f).waveSpeed(0.6f).waveAmplitude(0.06f)
                    .waveDirectionRadians(0.0f)
                    .waterColor(0.02f, 0.12f, 0.16f)
                    .absorptionDensity(0.35f).shoreFadeDistance(0.75f)
                    .reflectionFallback(0.35f, 0.5f, 0.65f)
                    .build();

            try (Framebuffer source = sceneSource();
                 Framebuffer target = waterTarget();
                 WaterSurfacePass pass = new WaterSurfacePass(1)) {
                // A far synthetic surface makes the water body thick at the centre.
                renderSource(source, projection, 30.0f);
                Vector3f background = readFramebufferColor(source, 1, SIZE - 2);
                FloatBuffer deepPixels = render(device, pass, source, target,
                        camera, projection, view, deep, 0.0f, false);
                int center = pixel(SIZE / 2, SIZE / 2);
                Vector3f expectedDeep = referencePixel(deep, camera, projection, view,
                        SIZE / 2, SIZE / 2, 30.0f, background);
                FloatBuffer deepNormal = render(device, pass, source, target,
                        camera, projection, view, deep, 0.0f, true);
                assertNormal(referenceNormal(deep, camera, projection, view,
                        SIZE / 2, SIZE / 2, 0.0f), deepNormal, center, 4.0e-3f);
                assertPixel(expectedDeep, deepPixels, center, 2.0e-3f,
                        "deep water absorption/fresnel reference");

                // Pixels outside the finite rectangle keep the copied scene colour exactly.
                for (int[] outside : new int[][]{{1, SIZE - 2}, {SIZE / 2, SIZE - 2},
                        {SIZE - 2, SIZE - 2}}) {
                    Vector3f expected = referencePixel(deep, camera, projection, view,
                            outside[0], outside[1], 30.0f, background);
                    assertTrue(expected == null, "test pixel must fall outside the water rectangle");
                    assertPixel(background, deepPixels, pixel(outside[0], outside[1]), 1.0e-6f,
                            "no water outside the finite bounds");
                }

                // A shallow near-shore surface must stay closer to the background.
                renderSource(source, projection, 9.8f);
                FloatBuffer shorePixels = render(device, pass, source, target,
                        camera, projection, view, deep, 0.0f, false);
                Vector3f expectedShore = referencePixel(deep, camera, projection, view,
                        SIZE / 2, SIZE / 2, 9.8f, background);
                assertPixel(expectedShore, shorePixels, center, 2.0e-3f,
                        "shore transition reference");
                assertTrue(distanceToBackground(expectedShore, background)
                                < distanceToBackground(expectedDeep, background),
                        "a shallow water column must fade towards the background");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void analyticNormalsMatchIndependentReferenceAndVaryWithTime() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Camera camera = new Camera(new Vector3f(0.0f, 6.0f, 10.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -40.0f);
            Matrix4f projection = CameraProjection.stable(camera, SIZE, SIZE, new Matrix4f());
            Matrix4f view = camera.getViewMatrix(new Matrix4f());
            WaterSurfaceSettings waves = WaterSurfaceSettings.builder()
                    .enabled(true).level(LEVEL).bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                    .waveWavelength(3.0f).waveSpeed(1.2f).waveAmplitude(0.1f)
                    .waveDirectionRadians(0.7f)
                    .shoreFadeDistance(0.0f)
                    .build();

            try (Framebuffer source = sceneSource();
                 Framebuffer target = waterTarget();
                 WaterSurfacePass pass = new WaterSurfacePass(1)) {
                renderSource(source, projection, 30.0f);
                FloatBuffer first = render(device, pass, source, target,
                        camera, projection, view, waves, 1.4f, true);
                FloatBuffer later = render(device, pass, source, target,
                        camera, projection, view, waves, 2.1f, true);
                int center = pixel(SIZE / 2, SIZE / 2);
                Vector3f expected = referenceNormal(waves, camera, projection, view,
                        SIZE / 2, SIZE / 2, 1.4f);
                assertNormal(expected, first, center, 4.0e-3f);
                assertTrue(decodedNormal(later, center).distance(expected) > 1.0e-3f,
                        "moving waves must change the analytic normal");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void grazingViewKeepsTheBackgroundAndProducesNoNaN() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            Camera camera = new Camera(new Vector3f(0.0f, 0.002f, 10.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
            Matrix4f projection = CameraProjection.stable(camera, SIZE, SIZE, new Matrix4f());
            Matrix4f view = camera.getViewMatrix(new Matrix4f());
            WaterSurfaceSettings water = WaterSurfaceSettings.builder()
                    .enabled(true).level(LEVEL).bounds(-50.0f, -50.0f, 50.0f, 50.0f)
                    .shoreFadeDistance(0.0f).build();
            try (Framebuffer source = sceneSource();
                 Framebuffer target = waterTarget();
                 WaterSurfacePass pass = new WaterSurfacePass(1)) {
                renderSource(source, projection, 30.0f);
                FloatBuffer pixels = render(device, pass, source, target,
                        camera, projection, view, water, 0.0f, false);
                for (int index = 0; index < SIZE * SIZE; index++) {
                    for (int channel = 0; channel < 4; channel++) {
                        float value = pixels.get(index * 4 + channel);
                        assertTrue(Float.isFinite(value), "grazing output must be finite");
                        assertTrue(Math.abs(value) < 100.0f, "grazing output must stay bounded");
                    }
                }
                assertPixel(new Vector3f(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE),
                        pixels, pixel(SIZE / 2, SIZE / 2), 1.0e-3f,
                        "a view parallel to the plane must keep the background");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void aSubNearWaterlineKeepsCoverageWithoutDrawingOutsideThePoolOrOverForeground() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            var device = new GlRenderDevice();
            var camera = new Camera(new Vector3f(0, 0.01f, 0), new Vector3f(0, 1, 0), -90, 0);
            Matrix4f view = camera.getViewMatrix(new Matrix4f());
            Matrix4f wideNear = new Matrix4f().perspective((float) Math.toRadians(55), 1, 0.1f, 50);
            Matrix4f smallNear = new Matrix4f().perspective((float) Math.toRadians(55), 1, 0.005f, 50);
            var water = WaterSurfaceSettings.builder().enabled(true).level(LEVEL)
                    .bounds(-0.003f, -2, 0.05f, 1).waveAmplitude(0).shoreFadeDistance(0)
                    .reflectionFallback(1, 0, 0).build();
            try (Framebuffer source = sceneSource(); Framebuffer target = waterTarget();
                 WaterSurfacePass pass = new WaterSurfacePass(1)) {
                renderSource(source, smallNear, 3);
                Vector3f background = readFramebufferColor(source, 0, 0);
                var reference = render(device, pass, source, target,
                        camera, smallNear, view, water, 0, false);
                renderSource(source, wideNear, 3);
                var actual = render(device, pass, source, target,
                        camera, wideNear, view, water, 0, false);
                int wet = pixel(SIZE / 2, 8);
                Vector3f expectedWet = referencePixel(water, camera, smallNear, view,
                        SIZE / 2, 8, 3, background);
                assertTrue(expectedWet.distance(background) > 0.05f,
                        "the sub-near fixture must produce a visible change");
                assertPixel(expectedWet, reference, wet, 1.0e-3f,
                        "the small-near control must visibly draw water");
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(reference.get(wet * 4 + channel), actual.get(wet * 4 + channel),
                            1.0e-3f, "water under the 0.1 m near plane");
                }
                assertPixel(background, actual, pixel(2, 8), 0, "outside finite pool");
                int shallowAngle = pixel(SIZE / 2, 30);
                Vector3f expectedShallow = referencePixel(water, camera, wideNear, view,
                        SIZE / 2, 30, 3, background);
                assertTrue(expectedShallow.distance(background) > 0.05f,
                        "the blocker fixture must otherwise show water");
                assertPixel(expectedShallow, actual, shallowAngle, 1.0e-3f,
                        "the foreground test pixel must show water without the blocker");
                renderSource(source, wideNear, 0.15f);
                var blocked = render(device, pass, source, target,
                        camera, wideNear, view, water, 0, false);
                assertPixel(background, blocked, shallowAngle, 0, "opaque foreground occludes water");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static FloatBuffer render(GlRenderDevice device, WaterSurfacePass pass, Framebuffer source,
                                      Framebuffer target, Camera camera, Matrix4f projection,
                                      Matrix4f view, WaterSurfaceSettings settings,
                                      float timeSeconds, boolean debugNormals) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        var commands = device.createCommandBuffer();
        TemporalFrameState.FrameParameters frame = new TemporalFrameState.FrameParameters(
                0L, SIZE, SIZE, CameraProjection.NEAR_PLANE, CameraProjection.FAR_PLANE,
                camera.position().x, camera.position().y, camera.position().z,
                camera.front().x, camera.front().y, camera.front().z, 0.0f, 0.0f,
                projection, projection, new Matrix4f(projection).invert(), view,
                new Matrix4f(projection).mul(view),
                new Matrix4f(projection).mul(view).invert(), 0L);
        if (debugNormals) {
            pass.recordNormalDebug(commands, source, target, source.depthAttachment(), frame, settings, timeSeconds);
        } else {
            pass.record(commands, source, target, source.depthAttachment(), frame,
                    settings, null, 0.0f, 0.0f, timeSeconds);
        }
        device.execute(commands);
        target.bind();
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static void renderSource(Framebuffer source, Matrix4f projection,
                                     float viewDistance) {
        source.bind();
        glViewport(0, 0, SIZE, SIZE);
        org.lwjgl.opengl.GL11.glDepthMask(true);
        glClearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f);
        glClearDepth(deviceDepth(viewDistance, projection));
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static Framebuffer sceneSource() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .depthTexture()
                .build());
    }

    private static Framebuffer waterTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .build());
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Water surface shader test").visible(false).build();
    }

    /** Independent reference for one shaded water pixel, or null when the ray misses the plane. */
    private static Vector3f referencePixel(WaterSurfaceSettings settings, Camera camera,
                                           Matrix4f projection, Matrix4f view, int x, int y,
                                           float sceneViewDistance, Vector3f background) {
        Vector3f world = waterWorld(camera, projection, view, x, y, settings.level());
        if (world == null) return null;
        if (world.x < settings.minX() || world.x > settings.maxX()
                || world.z < settings.minZ() || world.z > settings.maxZ()) {
            return null;
        }
        Vector3f sceneWorld = unproject(camera, projection, view, x, y,
                deviceDepth(sceneViewDistance, projection) * 2.0f - 1.0f);
        float thickness = settings.level() - sceneWorld.y;
        if (!(thickness > 0.0f)) return null;
        Vector3f normal = referenceNormalVector(settings, world.x, world.z, 0.0f);
        Vector3f viewDirection = new Vector3f(world).sub(camera.position()).normalize();
        float nDotV = Math.max(0.0f, Math.min(1.0f, normal.dot(
                new Vector3f(viewDirection).negate())));
        float fresnel = 0.02f + 0.98f * (float) Math.pow(1.0f - nDotV, 5.0f);
        float absorb = 1.0f - (float) Math.exp(-settings.absorptionDensity() * thickness);
        float shore = settings.shoreFadeDistance() <= 0.0f ? 1.0f
                : smoothstep(0.0f, settings.shoreFadeDistance(), thickness);
        Vector3f color = new Vector3f(settings.waterColor()).lerp(
                new Vector3f(settings.reflectionFallback()), fresnel);
        float alpha = clamp(shore * (absorb + (1.0f - absorb) * fresnel));
        return new Vector3f(background).lerp(color, alpha);
    }

    private static Vector3f referenceNormal(WaterSurfaceSettings settings, Camera camera,
                                            Matrix4f projection, Matrix4f view, int x, int y,
                                            float time) {
        Vector3f world = waterWorld(camera, projection, view, x, y, settings.level());
        return world == null ? null : referenceNormalVector(settings, world.x, world.z, time);
    }

    private static Vector3f referenceNormalVector(WaterSurfaceSettings settings,
                                                  float x, float z, float time) {
        double twoPi = Math.PI * 2.0;
        double k1 = twoPi / settings.waveWavelength();
        double dx = Math.cos(settings.waveDirectionRadians());
        double dz = Math.sin(settings.waveDirectionRadians());
        double k2 = k1 * 1.7;
        double phase1 = k1 * (dx * x + dz * z) - k1 * settings.waveSpeed() * time;
        double phase2 = k2 * (-dz * x + dx * z) - k2 * settings.waveSpeed() * time + 1.3;
        double gradientX = settings.waveAmplitude() * (k1 * dx * Math.cos(phase1)
                + 0.5 * k2 * -dz * Math.cos(phase2));
        double gradientZ = settings.waveAmplitude() * (k1 * dz * Math.cos(phase1)
                + 0.5 * k2 * dx * Math.cos(phase2));
        return new Vector3f((float) -gradientX, 1.0f, (float) -gradientZ).normalize();
    }

    /** Independent ray/plane intersection for one pixel; null when the ray is parallel or behind. */
    private static Vector3f waterWorld(Camera camera, Matrix4f projection, Matrix4f view,
                                       int x, int y, float level) {
        Vector3f near = unproject(camera, projection, view, x, y, -1.0f);
        Vector3f far = unproject(camera, projection, view, x, y, 1.0f);
        Vector3f direction = new Vector3f(far).sub(near).normalize();
        if (Math.abs(direction.y) < 1.0e-6f) return null;
        float distance = (level - camera.position().y) / direction.y;
        if (!(distance > 0.0f)) return null;
        return new Vector3f(camera.position()).fma(distance, direction);
    }

    private static Vector3f unproject(Camera camera, Matrix4f projection, Matrix4f view,
                                      int x, int y, float ndcZ) {
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Matrix4f inverseView = new Matrix4f(view).invert();
        float ndcX = (x + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / SIZE * 2.0f - 1.0f;
        Vector4f clip = new Vector4f(ndcX, ndcY, ndcZ, 1.0f).mul(inverseProjection);
        clip.div(clip.w);
        Vector4f world = new Vector4f(clip.x, clip.y, clip.z, 1.0f).mul(inverseView);
        world.div(world.w);
        return new Vector3f(world.x, world.y, world.z);
    }

    private static float deviceDepth(float viewDistance, Matrix4f projection) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDistance, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static void assertPixel(Vector3f expected, FloatBuffer pixels, int index,
                                    float tolerance, String message) {
        assertEquals(expected.x, pixels.get(index * 4), tolerance, message + " red");
        assertEquals(expected.y, pixels.get(index * 4 + 1), tolerance, message + " green");
        assertEquals(expected.z, pixels.get(index * 4 + 2), tolerance, message + " blue");
    }

    private static void assertNormal(Vector3f expected, FloatBuffer pixels, int index,
                                     float tolerance) {
        Vector3f actual = decodedNormal(pixels, index);
        assertEquals(expected.x, actual.x, tolerance, "normal x");
        assertEquals(expected.y, actual.y, tolerance, "normal y");
        assertEquals(expected.z, actual.z, tolerance, "normal z");
        assertTrue(actual.y > 0.0f, "wave normal y must stay positive");
    }

    private static Vector3f decodedNormal(FloatBuffer pixels, int index) {
        return new Vector3f(pixels.get(index * 4) * 2.0f - 1.0f,
                pixels.get(index * 4 + 1) * 2.0f - 1.0f,
                pixels.get(index * 4 + 2) * 2.0f - 1.0f);
    }

    private static Vector3f readFramebufferColor(Framebuffer framebuffer, int x, int y) {
        framebuffer.bind();
        FloatBuffer pixel = BufferUtils.createFloatBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        return new Vector3f(pixel.get(0), pixel.get(1), pixel.get(2));
    }

    private static float distanceToBackground(Vector3f color, Vector3f background) {
        return color.distance(background);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = clamp((value - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int pixel(int x, int y) {
        return y * SIZE + x;
    }
}
