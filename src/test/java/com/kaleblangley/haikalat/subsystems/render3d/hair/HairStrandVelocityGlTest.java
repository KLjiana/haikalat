package com.kaleblangley.haikalat.subsystems.render3d.hair;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL acceptance for the hair velocity channel (#46 v1).
 *
 * <p>The fixture owns a single-sample RG16F velocity attachment and a shared
 * depth attachment prefilled with an exact sentinel. Each case calls the
 * production {@link HairStrandPass#recordVelocity} once, reads the attachment
 * back with {@code GL_RGBA/GL_FLOAT} and checks the written values against an
 * independent double-precision reprojection:</p>
 *
 * <ul>
 *   <li>a static model and camera must write exactly zero and must not touch
 *       uncovered pixels (the sentinel survives);</li>
 *   <li>a model translated +X must write the centreline reprojection difference
 *       of the previous and current model matrices;</li>
 *   <li>a camera-only move must write the same static world point's stable
 *       reprojection difference;</li>
 *   <li>a CUTOUT forward pass must leave a shared depth the velocity pass can
 *       match with LEQUAL rather than requiring the pipeline default GL_LESS;</li>
 *   <li>an invalid previous frame must enqueue no command at all, leaving the
 *       attachment byte-identical instead of zeroing the receiver's motion;</li>
 *   <li>a disabled settings value must be an exact no-command no-op.</li>
 * </ul>
 *
 * <p>The guard matches {@code HairStrandGlTest}: plain {@code test} skips the
 * class; {@code glSmoke} runs it. No production helper computes the expected
 * values: the reference below evaluates the camera transform in {@code double}
 * from the same frozen camera/model inputs.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class HairStrandVelocityGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final float FOV_Y = (float) Math.toRadians(55.0);
    /** Exact half-float values, so an untouched pixel reads back bit-identically. */
    private static final float SENTINEL_U = 5.0f;
    private static final float SENTINEL_V = 7.0f;
    private static final float TOLERANCE = 1.0e-4f;
    private static final Matrix4f STATIC_MODEL = new Matrix4f().translate(-0.5f, 0.0f, 0.0f);
    private static final float MODEL_SHIFT_X = 0.25f;
    private static final float CAMERA_SHIFT_X = 0.2f;

    @Test
    void staticLockAndCameraWriteExactZeroAndKeepUncoveredPixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = straightLock();
            ExternalCamera camera = camera(0.5f);
            try (Framebuffer target = velocityTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                prefill(device, target);
                float[] pixels = record(device, target, pass, settings, camera, camera,
                        STATIC_MODEL, STATIC_MODEL, true);

                int covered = 0;
                int untouched = 0;
                for (int index = 0; index < WIDTH * HEIGHT; index++) {
                    float u = pixels[index * 4];
                    float v = pixels[index * 4 + 1];
                    if (isSentinel(u, v)) {
                        untouched++;
                        continue;
                    }
                    covered++;
                    assertEquals(0.0f, Math.abs(u), 0.0f,
                            "a static lock must write an exact zero u at pixel " + index);
                    assertEquals(0.0f, Math.abs(v), 0.0f,
                            "a static lock must write an exact zero v at pixel " + index);
                }
                assertTrue(covered > 100,
                        "the lock must rasterize a real ribbon, covered=" + covered);
                assertTrue(untouched > 1000,
                        "pixels outside the ribbon must keep the sentinel, untouched="
                                + untouched);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void modelTranslationWritesThePreviousToCurrentReprojectionDifference() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = straightLock();
            ExternalCamera camera = camera(0.5f);
            Matrix4f moved = new Matrix4f(STATIC_MODEL)
                    .translate(MODEL_SHIFT_X, 0.0f, 0.0f);
            double expectedU = reprojectionDifference(0.5, STATIC_MODEL.m30(),
                    STATIC_MODEL.m30() + MODEL_SHIFT_X);
            double expectedV = 0.0;
            assertTrue(Math.abs(expectedU) > 1.0e-3,
                    "the fixture translation must produce a measurable velocity");
            try (Framebuffer target = velocityTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                prefill(device, target);
                float[] pixels = record(device, target, pass, settings, camera, camera,
                        moved, STATIC_MODEL, true);
                assertEveryCoveredPixel(pixels, expectedU, expectedV,
                        "model translation");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void cameraOnlyMoveWritesTheAnalyticStableReprojection() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = straightLock();
            ExternalCamera previous = camera(0.5f);
            ExternalCamera current = camera(0.5f + CAMERA_SHIFT_X);
            double expectedU = cameraReprojectionDifference(0.5, 0.5 + CAMERA_SHIFT_X,
                    STATIC_MODEL.m30());
            assertTrue(Math.abs(expectedU) > 1.0e-3,
                    "the camera move must produce a measurable velocity");
            try (Framebuffer target = velocityTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                prefill(device, target);
                float[] pixels = record(device, target, pass, settings, current, previous,
                        STATIC_MODEL, STATIC_MODEL, true);
                assertEveryCoveredPixel(pixels, expectedU, 0.0, "camera-only move");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void invalidPreviousFrameSkipsTheDrawAndLeavesTheTargetUntouched() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = straightLock();
            ExternalCamera camera = camera(0.5f);
            Matrix4f moved = new Matrix4f(STATIC_MODEL)
                    .translate(MODEL_SHIFT_X, 0.0f, 0.0f);
            try (Framebuffer target = velocityTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                prefill(device, target);
                float[] before = read(target);

                CommandBuffer commands = device.createCommandBuffer();
                pass.recordVelocity(commands, camera, camera, moved, STATIC_MODEL, false,
                        settings);
                assertEquals(0, commands.commandCount(),
                        "an invalid previous frame must enqueue no command");
                device.execute(commands);
                device.invalidateState();

                assertArrayEqualsBits(before, read(target),
                        "an invalid previous frame must leave the velocity target untouched");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void velocityMatchesTheForwardHairDepthWithALequalComparison() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = straightLock();
            ExternalCamera camera = camera(0.5f);
            try (Framebuffer target = velocityTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                // CUTOUT forward writes the shared depth; the pipeline default
                // comparison is GL_LESS, so only an explicit LEQUAL in the
                // velocity pass can match the hair fragments' own depth.
                CommandBuffer forward = device.createCommandBuffer();
                forward.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT)
                        .clearColor(0.0f, 0.0f, 0.0f, 0.0f).clear(true, true);
                pass.record(forward, camera, STATIC_MODEL, settings, whiteAmbient(),
                        0, null, 0.0f);
                device.execute(forward);
                device.invalidateState();

                CommandBuffer reset = device.createCommandBuffer();
                reset.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT)
                        .clearColor(SENTINEL_U, SENTINEL_V, 0.0f, 1.0f).clear(true, false);
                device.execute(reset);
                device.invalidateState();

                float[] pixels = record(device, target, pass, settings, camera, camera,
                        STATIC_MODEL, STATIC_MODEL, true);
                int covered = 0;
                for (int index = 0; index < WIDTH * HEIGHT; index++) {
                    float u = pixels[index * 4];
                    float v = pixels[index * 4 + 1];
                    if (isSentinel(u, v)) {
                        continue;
                    }
                    covered++;
                    assertEquals(0.0f, Math.abs(u), 0.0f,
                            "LEQUAL depth match must write zero u at pixel " + index);
                    assertEquals(0.0f, Math.abs(v), 0.0f,
                            "LEQUAL depth match must write zero v at pixel " + index);
                }
                assertTrue(covered > 100,
                        "the velocity pass must match the forward hair depth instead of "
                                + "requiring GL_LESS, covered=" + covered);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void disabledSettingsAreAnExactNoCommandNoOp() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            HairStrandSettings settings = straightLock();
            ExternalCamera camera = camera(0.5f);
            try (Framebuffer target = velocityTarget();
                 HairStrandPass pass = new HairStrandPass(settings, WIDTH, HEIGHT)) {
                prefill(device, target);
                float[] before = read(target);

                CommandBuffer commands = device.createCommandBuffer();
                pass.recordVelocity(commands, camera, camera, STATIC_MODEL, STATIC_MODEL,
                        true, HairStrandSettings.disabled());
                assertEquals(0, commands.commandCount(),
                        "disabled hair settings must enqueue no command");
                device.execute(commands);
                device.invalidateState();

                assertArrayEqualsBits(before, read(target),
                        "disabled hair settings must not touch the velocity target");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    private static HairStrandSettings straightLock() {
        return HairStrandSettings.builder()
                .strandCount(1).segmentsPerStrand(8).strandLengthMetres(1.0f)
                .rootSpreadMetres(0.0f).curlMetres(0.0f)
                .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                .widthPixels(8.0f).minimumWidthPixels(2.0f).maximumWidthPixels(12.0f)
                .nearDistanceMetres(20.0f).farDistanceMetres(40.0f)
                .lodMinimumPixels(1.0f).lodWidthScale(1.0f)
                .alpha(1.0f).alphaCutoff(0.35f)
                .transparency(HairStrandSettings.Transparency.CUTOUT)
                .rootColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .tipColor(new Vector3f(1.0f, 1.0f, 1.0f))
                .diffuseStrength(1.0f).specularStrength(0.0f)
                .colorVariation(0.0f).castsShadow(false).seed(11L)
                .build();
    }

    private static HairStrandPass.HairStrandLight whiteAmbient() {
        return new HairStrandPass.HairStrandLight(new Vector3f(0.0f, -1.0f, 0.0f),
                new Vector3f(1.0f), 0.0f, new Vector3f(1.0f));
    }

    private static ExternalCamera camera(float eyeX) {
        Matrix4f view = new Matrix4f().lookAt(eyeX, 0.0f, 2.5f, eyeX, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f);
        Matrix4f projection = new Matrix4f().perspective(FOV_Y, WIDTH / (float) HEIGHT,
                0.1f, 100.0f);
        return ExternalCamera.of(view, projection, new Vector3f(eyeX, 0.0f, 2.5f), 0.0f);
    }

    /**
     * Independent double reference for a +X model translation with a static
     * camera. The straight lock lies on the world z=0 plane, so the reprojection
     * difference is the same for every covered pixel.
     *
     * @param eyeX          camera x
     * @param previousModelX previous translation x of the local origin
     * @param currentModelX  current translation x of the local origin
     * @return {@code previousUv - currentUv} on u; v is unchanged
     */
    private static double reprojectionDifference(double eyeX, double previousModelX,
                                                 double currentModelX) {
        double focal = 1.0 / Math.tan(FOV_Y * 0.5);
        double aspect = WIDTH / (double) HEIGHT;
        double depth = 2.5; // eye z minus the world z=0 strand plane
        double previousNdc = focal / aspect * (previousModelX - eyeX) / depth;
        double currentNdc = focal / aspect * (currentModelX - eyeX) / depth;
        return (previousNdc - currentNdc) * 0.5;
    }

    /**
     * Independent double reference for a static world point observed by two
     * cameras translated along X only. The view direction is unchanged, so the
     * pixel-space difference is a constant horizontal shift.
     */
    private static double cameraReprojectionDifference(double previousEyeX, double currentEyeX,
                                                       double worldX) {
        double focal = 1.0 / Math.tan(FOV_Y * 0.5);
        double aspect = WIDTH / (double) HEIGHT;
        double depth = 2.5;
        double previousNdc = focal / aspect * (worldX - previousEyeX) / depth;
        double currentNdc = focal / aspect * (worldX - currentEyeX) / depth;
        return (previousNdc - currentNdc) * 0.5;
    }

    private static float[] record(GlRenderDevice device, Framebuffer target,
                                  HairStrandPass pass, HairStrandSettings settings,
                                  ExternalCamera current, ExternalCamera previous,
                                  Matrix4f model, Matrix4f previousModel,
                                  boolean previousValid) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT);
        pass.recordVelocity(commands, current, previous, model, previousModel,
                previousValid, settings);
        device.execute(commands);
        device.invalidateState();
        return read(target);
    }

    private static void assertEveryCoveredPixel(float[] pixels, double expectedU,
                                                double expectedV, String label) {
        int covered = 0;
        for (int index = 0; index < WIDTH * HEIGHT; index++) {
            float u = pixels[index * 4];
            float v = pixels[index * 4 + 1];
            if (isSentinel(u, v)) {
                continue;
            }
            covered++;
            assertEquals(expectedU, u, TOLERANCE,
                    label + ": u at pixel " + index);
            assertEquals(expectedV, v, TOLERANCE,
                    label + ": v at pixel " + index);
        }
        assertTrue(covered > 100, label + ": the lock must rasterize real coverage, was "
                + covered);
    }

    private static boolean isSentinel(float u, float v) {
        return u == SENTINEL_U && v == SENTINEL_V;
    }

    private static Framebuffer velocityTarget() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(WIDTH, HEIGHT)
                .colorTexture(RenderFormat.RG16F)
                .depthTexture()
                .build());
    }

    private static void prefill(GlRenderDevice device, Framebuffer target) {
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(target).viewport(0, 0, WIDTH, HEIGHT)
                .clearColor(SENTINEL_U, SENTINEL_V, 0.0f, 1.0f).clear(true, true);
        device.execute(commands);
        device.invalidateState();
    }

    private static float[] read(Framebuffer target) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.id());
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(WIDTH * HEIGHT * 4);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_FLOAT, buffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        float[] pixels = new float[WIDTH * HEIGHT * 4];
        buffer.get(pixels);
        return pixels;
    }

    private static void assertArrayEqualsBits(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message + ": extent");
        for (int index = 0; index < expected.length; index++) {
            assertEquals(Float.floatToIntBits(expected[index]),
                    Float.floatToIntBits(actual[index]),
                    message + ": byte " + index);
        }
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT)
                .title("Hair Velocity GL Test").visible(false).build();
    }
}
