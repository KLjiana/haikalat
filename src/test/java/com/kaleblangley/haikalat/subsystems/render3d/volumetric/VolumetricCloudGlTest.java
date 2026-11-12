package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL acceptance for the bounded volumetric-cloud layer and its
 * low-resolution reconstruction.
 *
 * <p>The fixture renders a fixed perspective top-down sky view (for the
 * reference comparison) or a perspective horizon view (for the rejection
 * cases) into an RGBA16F target with a single-sample depth texture. Expected
 * values always come from the independent double reference
 * {@link VolumetricCloudReference}. Cases:</p>
 *
 * <ul>
 *   <li>a) the per-pixel transmittance and single scatter match the independent
 *       reference and are monotonic in density;</li>
 *   <li>b) rays below the cloud layer, foreground depth and cleared-depth
 *       pixels stay byte-identical to the source;</li>
 *   <li>c) half-resolution reconstruction tracks the full-resolution march
 *       within a documented bound;</li>
 *   <li>d) accepted history changes the image while camera cuts and exposure
 *       changes fall back to the fresh frame;</li>
 *   <li>e) disocclusion rejects history and a failed frame keeps the previous
 *       successful history;</li>
 *   <li>f) disabled settings create no pass and leave the baseline byte
 *       identical;</li>
 *   <li>g) resize/close are transactional and release every tracked
 *       resource.</li>
 * </ul>
 *
 * <p>Temporal reprojection accuracy under continuous motion, MSAA raw depth,
 * TAA/jitter, a full RenderPipeline integration, visual acceptance and
 * performance are not evaluated by this class. The temporary readbacks are test
 * evidence only; the production pass never reads back.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricCloudGlTest {
    private static final int SIZE = 64;
    private static final int SMALL_WIDTH = 48;
    private static final int SMALL_HEIGHT = 32;
    private static final float BACKGROUND = 0.0f;
    private static final float[] DARK = {0.02f, 0.03f, 0.05f};
    private static final float REFERENCE_TOLERANCE = 3.0e-3f;

    private static final String QUAD_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aViewPosition;
            uniform mat4 uProjection;
            void main() {
                gl_Position = uProjection * vec4(aViewPosition, 1.0);
            }
            """;

    private static final String QUAD_FRAGMENT = """
            #version 460 core
            uniform vec4 uColor;
            layout (location = 0) out vec4 FragColor;
            void main() {
                FragColor = uColor;
            }
            """;

    @Test
    void densityOpticalDepthAndScatterMatchTheIndependentReference() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = CloudTestCameras.topDown();
            try (Framebuffer scene = sceneTarget()) {
                VolumetricCloudSettings settings = settings(1.0f, 0.004f, 0.0f);
                float[][] outputs = renderAgainstBackgrounds(device, settings, camera, scene, 0.0);
                float[] scatter = outputs[0];
                float[] transmittance = outputs[1];

                Matrix4f inverseProjection = new Matrix4f(camera.projection()).invert();
                Matrix4f inverseView = new Matrix4f(camera.getViewMatrix(new Matrix4f()))
                        .invert();
                int cloudy = 0;
                int clear = 0;
                float maximumTransmittanceError = 0.0f;
                float maximumScatterError = 0.0f;
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        VolumetricCloudReference.Ray ray = VolumetricCloudReference.rayForPixel(
                                inverseProjection, inverseView, SIZE, SIZE, x, y, 1.0f);
                        VolumetricCloudReference.Contribution expected =
                                VolumetricCloudReference.march(settings, ray, 0.0);
                        int index = (y * SIZE + x) * 4;
                        maximumTransmittanceError = Math.max(maximumTransmittanceError,
                                (float) Math.abs(expected.transmittance()
                                        - transmittance[index]));
                        maximumScatterError = Math.max(maximumScatterError,
                                (float) Math.abs(expected.red() - scatter[index]));
                        if (expected.transmittance() < 0.99) {
                            cloudy++;
                        }
                        if (expected.transmittance() > 0.999) {
                            clear++;
                        }
                    }
                }
                assertTrue(cloudy >= 800, "the fixture must contain cloud pixels, got " + cloudy);
                assertTrue(clear >= 500, "the fixture must contain clear pixels, got " + clear);
                assertTrue(maximumTransmittanceError < REFERENCE_TOLERANCE,
                        "transmittance must match the independent reference, max error "
                                + maximumTransmittanceError);
                assertTrue(maximumScatterError < REFERENCE_TOLERANCE,
                        "single scatter must match the independent reference, max error "
                                + maximumScatterError);
                int center = (32 * SIZE + 32) * 4;
                assertTrue(transmittance[center] < 0.9f,
                        "the center pixel must be cloudy for the monotonicity check");
                assertTrue(scatter[center] > 0.001f,
                        "the center pixel must receive a measurable in-scatter");

                double energyBound = 1.0 * VolumetricCloudReference.phase(
                        settings.anisotropy(), -1.0) * settings.scatteringAlbedo();
                assertTrue(scatter[center] <= energyBound + REFERENCE_TOLERANCE,
                        "single scatter must stay below light * phase * albedo");

                float previousTransmittance = Float.POSITIVE_INFINITY;
                float previousScatter = -1.0f;
                for (float density : new float[]{0.002f, 0.004f, 0.008f}) {
                    VolumetricCloudSettings dense = settings(1.0f, density, 0.0f);
                    float[][] denseOutput = renderAgainstBackgrounds(device, dense, camera, scene,
                            0.0);
                    VolumetricCloudReference.Ray ray = VolumetricCloudReference.rayForPixel(
                            inverseProjection, inverseView, SIZE, SIZE, 32, 32, 1.0f);
                    VolumetricCloudReference.Contribution expected =
                            VolumetricCloudReference.march(dense, ray, 0.0);
                    float pixelTransmittance = denseOutput[1][center];
                    float pixelScatter = denseOutput[0][center];
                    assertEquals(expected.transmittance(), pixelTransmittance,
                            REFERENCE_TOLERANCE, "density=" + density + " transmittance");
                    assertEquals(expected.red(), pixelScatter, REFERENCE_TOLERANCE,
                            "density=" + density + " scatter");
                    assertTrue(pixelTransmittance < previousTransmittance,
                            "denser clouds must transmit less");
                    assertTrue(pixelScatter > previousScatter,
                            "denser clouds must scatter more");
                    previousTransmittance = pixelTransmittance;
                    previousScatter = pixelScatter;
                }
            }
            GlDebug.assertNoError("cloud reference march");
        }
    }

    @Test
    void horizonAndDepthRejectionLeaveTheBackgroundByteIdentical() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = CloudTestCameras.horizon();
            try (Framebuffer scene = sceneTarget()) {
                clear(scene, DARK, 1.0f);
                FloatBuffer source = readColor(scene);
                VolumetricCloudSettings settings = settings(1.0f, 0.004f, 20.0f);
                try (VolumetricCloudPass pass = new VolumetricCloudPass(settings, SIZE, SIZE)) {
                    FloatBuffer pixels = render(device, pass, scene, camera, 0.0, 1.0f);
                    int matchedHorizonRejected = 0;
                    int cloudyUpper = 0;
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            int index = (y * SIZE + x) * 4;
                            if (y < SIZE / 2) {
                                // Splitting the frame at mid-height is the horizon:
                                // every lower-half ray points away from the layer.
                                matchedHorizonRejected++;
                                assertPixel(source, pixels, index, 0.0f,
                                        "a ray below the cloud layer must not change the pixel");
                            } else if (pixels.get(index) > source.get(index) + 1.0e-4f
                                    || pixels.get(index) < source.get(index) - 1.0e-4f) {
                                cloudyUpper++;
                            }
                        }
                    }
                    assertTrue(matchedHorizonRejected >= 1_500,
                            "the lower half must be a real no-hit region");
                    assertTrue(cloudyUpper >= 100,
                            "the upper half must contain visible cloud pixels, got " + cloudyUpper);

                    // A rasterised foreground surface in front of the layer: the
                    // pixel must fall back to the unmodified source.
                    drawDepthQuad(device, scene, camera, 0.0f, 0.0f, 0.5f, 0.5f, 5.0f);
                    FloatBuffer occludedSource = readColor(scene);
                    FloatBuffer occluded = render(device, pass, scene, camera, 0.0, 1.0f);
                    for (int y = 30; y <= 34; y++) {
                        for (int x = 30; x <= 34; x++) {
                            int index = (y * SIZE + x) * 4;
                            assertPixel(occludedSource, occluded, index, 0.0f,
                                    "foreground depth must reject the cloud layer");
                        }
                    }
                    assertEquals(source.get((60 * SIZE + 32) * 4),
                            occludedSource.get((60 * SIZE + 32) * 4), 0.0f,
                            "the depth draw must not modify the sky colour outside the quad");
                }
            }
            GlDebug.assertNoError("cloud horizon/depth rejection");
        }
    }

    @Test
    void lowResolutionReconstructionTracksTheFullResolutionMarch() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = CloudTestCameras.topDown();
            try (Framebuffer scene = sceneTarget()) {
                clear(scene, DARK, 1.0f);
                VolumetricCloudSettings nativeSettings = settings(1.0f, 0.004f, 20.0f);
                VolumetricCloudSettings lowSettings = settings(0.5f, 0.004f, 20.0f);
                try (VolumetricCloudPass nativePass = new VolumetricCloudPass(nativeSettings,
                        SIZE, SIZE);
                     VolumetricCloudPass lowPass = new VolumetricCloudPass(lowSettings,
                             SIZE, SIZE)) {
                    assertEquals(32, lowPass.lowResolutionWidth());
                    assertEquals(32, lowPass.lowResolutionHeight());
                    FloatBuffer nativePixels = render(device, nativePass, scene, camera, 0.0, 1.0f);
                    FloatBuffer lowPixels = render(device, lowPass, scene, camera, 0.0, 1.0f);
                    double totalError = 0.0;
                    double maximumError = 0.0;
                    int compared = 0;
                    for (int y = 2; y < SIZE - 2; y++) {
                        for (int x = 2; x < SIZE - 2; x++) {
                            int index = (y * SIZE + x) * 4;
                            for (int channel = 0; channel < 3; channel++) {
                                double difference = Math.abs(nativePixels.get(index + channel)
                                        - lowPixels.get(index + channel));
                                totalError += difference;
                                maximumError = Math.max(maximumError, difference);
                                compared++;
                            }
                        }
                    }
                    double meanError = totalError / compared;
                    assertTrue(meanError < 1.0e-2,
                            "half-resolution reconstruction mean error must stay small, got "
                                    + meanError);
                    assertTrue(maximumError < 1.0e-1,
                            "half-resolution reconstruction max error must stay bounded, got "
                                    + maximumError);
                    assertTrue(maximumError > 0.0,
                            "the low-resolution path must differ somewhere from the native path");

                    // A high-resolution pass must reproduce the reference even when
                    // the no-op history path is active (historyBlend = 0).
                    VolumetricCloudSettings temporalSettings = settings(1.0f, 0.004f, 20.0f,
                            0.9f);
                    try (VolumetricCloudPass temporalPass = new VolumetricCloudPass(
                            temporalSettings, SIZE, SIZE)) {
                        FloatBuffer first = render(device, temporalPass, scene, camera, 0.0, 1.0f);
                        assertFalse(temporalPass.historyAccepted(),
                                "the first frame has no history to accept");
                        FloatBuffer second = render(device, temporalPass, scene, camera, 0.0, 1.0f);
                        assertArrayEquals(toArray(nativePixels), toArray(second), 1.0e-6f,
                                "a static frame with accepted history must stay stable");
                        assertArrayEquals(toArray(first), toArray(second), 1.0e-6f);
                    }
                }
            }
            GlDebug.assertNoError("cloud low-resolution reconstruction");
        }
    }

    @Test
    void historyRejectsCameraCutsAndExposureChanges() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = CloudTestCameras.topDown();
            ExternalCamera moved = CloudTestCameras.topDown(CloudTestCameras.CLOUD_CENTER_X + 20.0f, CloudTestCameras.CLOUD_CENTER_Z);
            ExternalCamera nearby = CloudTestCameras.topDown(CloudTestCameras.CLOUD_CENTER_X + 2.0f, CloudTestCameras.CLOUD_CENTER_Z);
            VolumetricCloudSettings settings = settings(0.5f, 0.004f, 20.0f, 0.9f);
            try (Framebuffer scene = sceneTarget();
                 VolumetricCloudPass pass = new VolumetricCloudPass(settings, SIZE, SIZE)) {
                clear(scene, DARK, 1.0f);
                render(device, pass, scene, camera, 0.0, 1.0f);
                FloatBuffer blended = render(device, pass, scene, camera, 40.0, 1.0f);
                assertTrue(pass.historyAccepted(),
                        "a static camera with stable exposure must accept the history");

                FloatBuffer fresh = freshRender(device, settings, scene, camera, 40.0, 1.0f);
                assertTrue(maximumDifference(blended, fresh) > 1.0e-3f,
                        "accepted history must change the image for moving clouds");

                FloatBuffer exposure = render(device, pass, scene, camera, 40.0, 3.0f);
                assertFalse(pass.historyAccepted(),
                        "a relative exposure change rejects the history");
                assertArrayEquals(toArray(fresh), toArray(exposure), 1.0e-6f,
                        "an exposure change must fall back to the fresh frame");

                FloatBuffer cut = render(device, pass, scene, moved, 40.0, 3.0f);
                assertFalse(pass.historyAccepted(), "a camera translation cut rejects the history");
                FloatBuffer freshCut = freshRender(device, settings, scene, moved, 40.0, 3.0f);
                assertArrayEquals(toArray(freshCut), toArray(cut), 1.0e-6f,
                        "a camera cut must fall back to the fresh frame");

                render(device, pass, scene, nearby, 40.0, 3.0f);
                assertTrue(pass.historyAccepted(),
                        "a small camera move must keep reprojecting the history");
            }
            GlDebug.assertNoError("cloud history rejection");
        }
    }

    @Test
    void disocclusionRejectsHistoryAndFailedFramesKeepThePreviousHistory() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = CloudTestCameras.horizon();
            VolumetricCloudSettings settings = settings(0.5f, 0.004f, 0.0f, 0.9f);
            try (Framebuffer scene = sceneTarget();
                 VolumetricCloudPass pass = new VolumetricCloudPass(settings, SIZE, SIZE)) {
                clear(scene, DARK, 1.0f);
                render(device, pass, scene, camera, 0.0, 1.0f);
                int readHistoryTexture = pass.historyCloudTexture();
                assertTrue(readHistoryTexture != 0);

                // A recorded but failed frame must not publish its history slot.
                pass.beginFrame();
                CommandBuffer commands = device.createCommandBuffer();
                pass.record(commands, scene, scene.depthAttachment(), camera, 1.0, 1.0f);
                device.execute(commands);
                pass.frameFailed();
                assertEquals(readHistoryTexture, pass.historyCloudTexture(),
                        "a failed frame must keep the previous successful history slot");
                assertFalse(pass.historyAccepted(), "a failed frame is not an accepted history");

                // A prepared but unrecorded frame must not publish either.
                pass.beginFrame();
                pass.frameSucceeded();
                assertEquals(readHistoryTexture, pass.historyCloudTexture(),
                        "an unrecorded frame must not commit a history candidate");
                assertFalse(pass.historyAccepted());

                // The next successful frame swaps the read slot.
                render(device, pass, scene, camera, 2.0, 1.0f);
                assertTrue(pass.historyCloudTexture() != readHistoryTexture,
                        "a successful frame publishes the candidate history");

                // A newly near surface at a previously cloudy pixel is a
                // disocclusion: the history must not smear clouds over it.
                drawDepthQuad(device, scene, camera, 0.0f, 0.0f, 0.5f, 0.5f, 5.0f);
                FloatBuffer source = readColor(scene);
                FloatBuffer pixels = render(device, pass, scene, camera, 3.0, 1.0f);
                for (int y = 30; y <= 34; y++) {
                    for (int x = 30; x <= 34; x++) {
                        int index = (y * SIZE + x) * 4;
                        assertPixel(source, pixels, index, 0.0f,
                                "disoccluded foreground must reject the cloud history");
                    }
                }
                int changedSky = 0;
                for (int y = SIZE / 2; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int index = (y * SIZE + x) * 4;
                        if (Math.abs(pixels.get(index) - source.get(index)) > 1.0e-4f) {
                            changedSky++;
                        }
                    }
                }
                assertTrue(changedSky >= 100,
                        "the sky above the horizon must keep its cloud signal, got " + changedSky);

                pass.invalidateHistory();
                assertTrue(pass.historyCloudTexture() != 0, "the slots stay allocated");
                FloatBuffer afterInvalidate = render(device, pass, scene, camera, 4.0, 1.0f);
                assertFalse(pass.historyAccepted(),
                        "an invalidated history must be rebuilt from the current frame");
                assertEquals(SIZE * SIZE * 4, afterInvalidate.capacity());
            }
            GlDebug.assertNoError("cloud disocclusion/history transaction");
        }
    }

    @Test
    void disabledSettingsCreateNoPassAndLeaveTheBaselineByteIdentical() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (var tracking = GlDebug.acquireResourceTracking()) {
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = CloudTestCameras.topDown();
                try (Framebuffer scene = sceneTarget()) {
                    clear(scene, DARK, 1.0f);
                    float[] baseline = toArray(readColor(scene));
                    VolumetricCloudSettings disabled = VolumetricCloudSettings.disabled();
                    assertFalse(disabled.enabled());
                    assertFalse(disabled.active());
                    int liveBefore = GlDebug.resources().liveResources().size();
                    assertThrows(IllegalArgumentException.class,
                            () -> new VolumetricCloudPass(disabled, SIZE, SIZE),
                            "disabled settings must not create a pass or any GPU resource");
                    assertEquals(liveBefore, GlDebug.resources().liveResources().size(),
                            "a rejected disabled construction must not leak resources");
                    assertArrayEquals(baseline, toArray(readColor(scene)), 0.0f,
                            "disabled clouds must leave the baseline pixels byte-identical");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "the disabled path must not leave tracked resources behind");
            }
            GlDebug.assertNoError("disabled cloud no-op");
        }
    }

    @Test
    void resizeAndCloseAreTransactionalAndReleaseTrackedResources() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (var tracking = GlDebug.acquireResourceTracking()) {
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = CloudTestCameras.topDown();
                ExternalCamera smallCamera = CloudTestCameras.topDown();
                VolumetricCloudPass pass = null;
                try (Framebuffer scene = sceneTarget();
                     Framebuffer small = sceneTarget(SMALL_WIDTH, SMALL_HEIGHT)) {
                    VolumetricCloudPass active =
                            new VolumetricCloudPass(settings(0.5f, 0.004f, 20.0f), SIZE, SIZE);
                    pass = active;
                    clear(scene, DARK, 1.0f);
                    render(device, active, scene, camera, 0.0, 1.0f);
                    assertEquals(SIZE, active.width());
                    assertEquals(32, active.lowResolutionWidth());

                    active.resize(SMALL_WIDTH, SMALL_HEIGHT);
                    assertEquals(SMALL_WIDTH, active.width());
                    assertEquals(SMALL_HEIGHT, active.height());
                    assertEquals(24, active.lowResolutionWidth());
                    assertEquals(16, active.lowResolutionHeight());
                    int resizedId = active.id();
                    assertTrue(resizedId != 0);
                    clear(small, DARK, 1.0f);
                    FloatBuffer smallPixels = render(device, active, small, smallCamera, 0.0, 1.0f);
                    assertEquals(SMALL_WIDTH * SMALL_HEIGHT * 4, smallPixels.capacity(),
                            "the resized target must be readable at the new extent");

                    active.resize(SMALL_WIDTH, SMALL_HEIGHT);
                    assertEquals(resizedId, active.id(), "a same-extent resize is a no-op");
                    assertThrows(IllegalArgumentException.class,
                            () -> active.resize(0, SMALL_HEIGHT));
                    assertEquals(resizedId, active.id(), "an invalid resize keeps the old targets");

                    System.setProperty("haikalat.test.failVolumetricCloudResizeAllocation", "true");
                    try {
                        assertThrows(IllegalStateException.class, () -> resizeTarget(active));
                    } finally {
                        System.clearProperty("haikalat.test.failVolumetricCloudResizeAllocation");
                    }
                    assertEquals(SMALL_WIDTH, active.width(),
                            "an injected allocation failure keeps the old targets");
                    assertEquals(resizedId, active.id());

                    int closedId = active.id();
                    active.close();
                    assertTrue(active.isClosed());
                    assertEquals(0, active.id());
                    assertEquals(0, active.colorTexture());
                    assertEquals(0, active.width());
                    assertEquals(0, active.lowResolutionWidth());
                    assertTrue(closedId != 0);
                    active.close();
                    assertThrows(GlException.class,
                            () -> active.record(device.createCommandBuffer(), small,
                                    small.depthAttachment(), smallCamera, 0.0, 1.0f));
                } finally {
                    if (pass != null) {
                        pass.close();
                    }
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "resize/close must release every tracked GL resource");
            }
            GlDebug.assertNoError("cloud resize/close lifecycle");
        }
    }

    private static void resizeTarget(VolumetricCloudPass pass) {
        pass.resize(SIZE, SIZE);
    }

    private static VolumetricCloudSettings settings(float renderScale, float density,
                                                    float cloudSpeed) {
        return settings(renderScale, density, cloudSpeed, 0.0f);
    }

    private static VolumetricCloudSettings settings(float renderScale, float density,
                                                    float cloudSpeed, float historyBlend) {
        return new VolumetricCloudSettings(true,
                new CloudShadowSettings(true, 0.5f, 0.4f, 0.0004f, cloudSpeed, 0.65f),
                1_000.0f, 1_400.0f, density, 0.05f, 0.95f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.3f,
                32, 40.0f, 4, 2_000.0f, renderScale, historyBlend, 0.1f, 2.0f, 5.0f, 30.0f);
    }

    private static Framebuffer sceneTarget() {
        return sceneTarget(SIZE, SIZE);
    }

    private static Framebuffer sceneTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.RGBA16F)
                .depthTexture()
                .build());
    }

    /** Renders once with a black and once with a white background to split S and T. */
    private static float[][] renderAgainstBackgrounds(GlRenderDevice device,
                                                      VolumetricCloudSettings settings,
                                                      ExternalCamera camera, Framebuffer scene,
                                                      double time) {
        try (VolumetricCloudPass pass = new VolumetricCloudPass(settings, SIZE, SIZE)) {
            clear(scene, new float[]{BACKGROUND, BACKGROUND, BACKGROUND}, 1.0f);
            FloatBuffer dark = render(device, pass, scene, camera, time, 1.0f);
            clear(scene, new float[]{1.0f, 1.0f, 1.0f}, 1.0f);
            FloatBuffer bright = render(device, pass, scene, camera, time, 1.0f);
            float[] scatter = new float[SIZE * SIZE * 4];
            float[] transmittance = new float[SIZE * SIZE * 4];
            for (int index = 0; index < scatter.length; index++) {
                scatter[index] = dark.get(index);
                transmittance[index] = bright.get(index) - dark.get(index);
            }
            return new float[][]{scatter, transmittance};
        }
    }

    private static FloatBuffer freshRender(GlRenderDevice device,
                                           VolumetricCloudSettings settings,
                                           Framebuffer scene, ExternalCamera camera,
                                           double time, float exposure) {
        try (VolumetricCloudPass fresh = new VolumetricCloudPass(settings, SIZE, SIZE)) {
            return render(device, fresh, scene, camera, time, exposure);
        }
    }

    private static FloatBuffer render(GlRenderDevice device, VolumetricCloudPass pass,
                                      Framebuffer scene, ExternalCamera camera,
                                      double time, float exposure) {
        pass.beginFrame();
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, scene, scene.depthAttachment(), camera, time, exposure);
        device.execute(commands);
        pass.frameSucceeded();
        return readColor(pass.colorTarget());
    }

    private static FloatBuffer readColor(Framebuffer framebuffer) {
        framebuffer.bind();
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(
                framebuffer.width() * framebuffer.height() * 4);
        glReadPixels(0, 0, framebuffer.width(), framebuffer.height(), GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static void clear(Framebuffer target, float[] color, float depth) {
        target.bind();
        glViewport(0, 0, target.width(), target.height());
        glDisable(GL_SCISSOR_TEST);
        glDepthMask(true);
        glClearColor(color[0], color[1], color[2], 1.0f);
        glClearDepth(depth);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static void drawDepthQuad(GlRenderDevice device, Framebuffer scene,
                                      ExternalCamera camera, float centerX, float centerY,
                                      float halfExtentX, float halfExtentY, float viewDepth) {
        float z = -viewDepth;
        float[] positions = {
                centerX - halfExtentX, centerY - halfExtentY, z,
                centerX + halfExtentX, centerY - halfExtentY, z,
                centerX + halfExtentX, centerY + halfExtentY, z,
                centerX - halfExtentX, centerY + halfExtentY, z
        };
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        try (ShaderProgram shader = ShaderProgram.fromSources(QUAD_VERTEX, QUAD_FRAGMENT);
             Mesh mesh = Mesh.builder()
                     .vertices(positions, 3 * Float.BYTES, position)
                     .indices(new int[]{0, 1, 2, 0, 2, 3})
                     .build()) {
            CommandBuffer commands = device.createCommandBuffer();
            commands.bindFramebuffer(scene)
                    .viewport(0, 0, scene.width(), scene.height())
                    .enableScissor(false)
                    .enableBlend(false)
                    .enableCullFace(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .bindShader(shader)
                    .setUniformMat4(shader, "uProjection", camera.projection())
                    .setUniformVec4(shader, "uColor", new Vector4f(DARK[0], DARK[1],
                            DARK[2], 1.0f))
                    .bindMesh(mesh)
                    .drawMesh(mesh);
            device.execute(commands);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static void bind(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        while (glGetError() != GL_NO_ERROR) {
            // drain stale errors from context creation before asserting.
        }
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Volumetric cloud pass test").visible(false).build();
    }

    private static void assertPixel(FloatBuffer expected, FloatBuffer actual, int index,
                                    float tolerance, String message) {
        assertEquals(expected.get(index), actual.get(index), tolerance, message + " red");
        assertEquals(expected.get(index + 1), actual.get(index + 1), tolerance,
                message + " green");
        assertEquals(expected.get(index + 2), actual.get(index + 2), tolerance,
                message + " blue");
    }

    private static float[] toArray(FloatBuffer buffer) {
        float[] values = new float[buffer.remaining()];
        buffer.duplicate().get(values);
        return values;
    }

    private static float maximumDifference(FloatBuffer left, FloatBuffer right) {
        float maximum = 0.0f;
        for (int index = 0; index < left.capacity(); index++) {
            maximum = Math.max(maximum, Math.abs(left.get(index) - right.get(index)));
        }
        return maximum;
    }
}
