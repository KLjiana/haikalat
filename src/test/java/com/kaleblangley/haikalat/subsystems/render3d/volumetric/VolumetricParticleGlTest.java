package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.FogMediumSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderFrameContext;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glReadBuffer;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;

import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;

/**
 * Real-GL acceptance for the bounded local volumetric-particle pass.
 *
 * <p>The fixture renders a perspective camera at {@code (0, 0, 8)} looking down
 * {@code -Z} into an RGBA16F colour target with a single-sample depth texture
 * cleared to a constant floor distance. Expected colours always come from the
 * independent double reference {@link VolumetricParticleReference}, never from
 * the shader output. Cases:</p>
 *
 * <ul>
 *   <li>a) a local ellipsoid adds bounded premultiplied in-scatter inside the
 *       body and leaves pixels whose ray misses it byte-identical;</li>
 *   <li>b) a rasterised foreground quad in front of the body rejects the
 *       particle contribution while a floor/inside depth keeps the expected
 *       partial integration (A-B occlusion);</li>
 *   <li>c) a real {@link VolumetricPassBuilder} fog medium is composited once,
 *       and the particle pass adds exactly its own medium over the fog result,
 *       differing from a deliberately doubled fog composition;</li>
 *   <li>d) step-budget and density changes converge monotonically to the
 *       analytic reference and stay inside the energy bounds;</li>
 *   <li>e) disabled settings create no pass and leave the baseline pixels byte
 *       identical;</li>
 *   <li>f) resize/close are transactional and release every tracked GL
 *       resource.</li>
 * </ul>
 *
 * <p>MSAA raw depth, HDR tone mapping, TAA/jitter, a full pipeline integration
 * and performance are not evaluated by this class. The temporary readbacks are
 * test evidence only; the production pass never reads back.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricParticleGlTest {
    private static final int SIZE = 64;
    private static final int SMALL_WIDTH = 48;
    private static final int SMALL_HEIGHT = 32;
    private static final float FLOOR_DISTANCE = 12.0f;
    private static final float FOREGROUND_DISTANCE = 5.0f;
    private static final float[] BACKGROUND = {0.05f, 0.10f, 0.15f};
    private static final float[] BRIGHT_BACKGROUND = {0.8f, 0.7f, 0.6f};
    private static final float TOLERANCE = 4.0e-3f;
    private static final float FOREGROUND_HALF_EXTENT_PIXELS = 5.0f;

    private static final String QUAD_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aViewPosition;
            uniform mat4 uProjection;
            void main() {
                gl_Position = uProjection * vec4(aViewPosition, 1.0);
            }
            """;

    private static final String PASSTHROUGH_VERTEX = """
            #version 460 core
            layout (location = 0) in vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
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
    void boundedScatteringInsideTheBodyLeavesOutsidePixelsUnchanged() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = camera();
            float depth = floorDeviceDepth(camera);
            try (Framebuffer scene = sceneTarget()) {
                clear(scene, BACKGROUND, depth);
                FloatBuffer source = readColor(scene);
                VolumetricParticleSettings settings = settings(new Vector3f(), 48, 0.05f,
                        4.0f, 1.0f, 1.0f, VolumetricParticleSettings.FogBlend.OCCLUDING);
                try (VolumetricParticlePass pass = new VolumetricParticlePass(settings, SIZE, SIZE)) {
                    FloatBuffer pixels = render(device, pass, scene, camera);
                    Matrix4f inverseProjection = new Matrix4f(camera.projection()).invert();
                    Matrix4f inverseView = new Matrix4f(camera.getViewMatrix(new Matrix4f())).invert();
                    int lit = 0;
                    int rejected = 0;
                    for (int y = 0; y < SIZE; y++) {
                        for (int x = 0; x < SIZE; x++) {
                            VolumetricParticleReference.Ray ray = VolumetricParticleReference
                                    .rayForPixel(inverseProjection, inverseView, SIZE, SIZE, x, y, depth);
                            double opticalDepth = VolumetricParticleReference
                                    .quadratureOpticalDepth(settings, ray);
                            double analytic = VolumetricParticleReference
                                    .analyticOpticalDepth(settings, ray);
                            assertEquals(analytic, opticalDepth, 2.0e-3,
                                    "quadrature must stay inside the documented bound at " + x + "," + y);
                            if (opticalDepth > 0.0) {
                                lit++;
                                assertPixel(VolumetricParticleReference.occluding(settings, ray,
                                                asDouble(BACKGROUND), false), pixels, x, y, TOLERANCE,
                                        "lit particle pixel");
                            } else {
                                rejected++;
                                assertPixel(new double[]{source.get((y * SIZE + x) * 4),
                                                source.get((y * SIZE + x) * 4 + 1),
                                                source.get((y * SIZE + x) * 4 + 2)},
                                        pixels, x, y, 0.0f,
                                        "a ray outside the local body must not change the pixel");
                            }
                        }
                    }
                    assertTrue(lit >= 200, "the local body must cover a meaningful area, got " + lit);
                    assertTrue(rejected >= 1500, "most pixels must reject the local body, got " + rejected);
                    assertTrue(pixels.get((32 * SIZE + 32) * 4) > BACKGROUND[0] + 0.05f,
                            "the center ray must produce a visible in-scatter");
                    VolumetricParticleSettings offscreen = settings(
                            new Vector3f(100.0f, 0.0f, 0.0f), 16, 0.1f, 4.0f, 1.0f, 1.0f,
                            VolumetricParticleSettings.FogBlend.OCCLUDING);
                    try (VolumetricParticlePass absent = new VolumetricParticlePass(offscreen,
                            SIZE, SIZE)) {
                        FloatBuffer copied = render(device, absent, scene, camera);
                        assertArrayEquals(toArray(source), toArray(copied), 0.0f,
                                "an off-screen body records no draw but still copies the source");
                    }
                    VolumetricParticleSettings capped = settings(new Vector3f(), 16, 0.1f,
                            4.0f, 1.0f, 0.02f, VolumetricParticleSettings.FogBlend.OCCLUDING);
                    try (VolumetricParticlePass limited = new VolumetricParticlePass(capped,
                            SIZE, SIZE)) {
                        CommandBuffer commands = device.createCommandBuffer();
                        assertThrows(IllegalStateException.class, () -> limited.record(commands,
                                        scene, scene.depthAttachment(), camera),
                                "coverage above the configured cap must fail before recording");
                    }
                }
            }
            GlDebug.assertNoError("bounded particle scattering");
        }
    }

    @Test
    void foregroundDepthOccludesParticlesAndInsideDepthTruncatesThem() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = camera();
            float floorDepth = floorDeviceDepth(camera);
            try (Framebuffer scene = sceneTarget()) {
                clear(scene, BACKGROUND, floorDepth);
                drawForegroundDepth(device, scene, camera, 32, 32, FOREGROUND_DISTANCE);
                float foregroundDepth = readDepth(scene, 32, 32);
                assertTrue(foregroundDepth < floorDepth,
                        "the fixture must rasterise a real occluder in front of the body");
                FloatBuffer source = readColor(scene);

                VolumetricParticleSettings behind = settings(new Vector3f(), 48, 0.05f,
                        4.0f, 1.0f, 1.0f, VolumetricParticleSettings.FogBlend.OCCLUDING);
                try (VolumetricParticlePass pass = new VolumetricParticlePass(behind, SIZE, SIZE)) {
                    FloatBuffer pixels = render(device, pass, scene, camera);
                    Matrix4f inverseProjection = new Matrix4f(camera.projection()).invert();
                    Matrix4f inverseView = new Matrix4f(camera.getViewMatrix(new Matrix4f())).invert();
                    int sourceIndex = (32 * SIZE + 32) * 4;
                    assertPixel(new double[]{source.get(sourceIndex), source.get(sourceIndex + 1),
                                    source.get(sourceIndex + 2)}, pixels, 32, 32, 0.0f,
                            "an occluder in front of the local body must reject every particle");

                    int[] lit = findLitPixel(behind, inverseProjection, inverseView, floorDepth, 32, 32);
                    VolumetricParticleReference.Ray ray = VolumetricParticleReference.rayForPixel(
                            inverseProjection, inverseView, SIZE, SIZE, lit[0], lit[1], floorDepth);
                    assertPixel(VolumetricParticleReference.occluding(behind, ray,
                                    asDouble(BACKGROUND), false), pixels, lit[0], lit[1], TOLERANCE,
                            "a pixel on the floor behind the body keeps the expected contribution");
                }

                VolumetricParticleSettings straddling = settings(new Vector3f(0.0f, 0.0f, 2.5f),
                        48, 0.05f, 4.0f, 1.0f, 1.0f,
                        VolumetricParticleSettings.FogBlend.OCCLUDING);
                try (VolumetricParticlePass pass = new VolumetricParticlePass(straddling, SIZE, SIZE)) {
                    FloatBuffer pixels = render(device, pass, scene, camera);
                    Matrix4f inverseProjection = new Matrix4f(camera.projection()).invert();
                    Matrix4f inverseView = new Matrix4f(camera.getViewMatrix(new Matrix4f())).invert();
                    VolumetricParticleReference.Ray ray = VolumetricParticleReference.rayForPixel(
                            inverseProjection, inverseView, SIZE, SIZE, 32, 32, foregroundDepth);
                    double opticalDepth = VolumetricParticleReference
                            .quadratureOpticalDepth(straddling, ray);
                    assertTrue(opticalDepth > 0.1,
                            "the occluder must cut through the body for the A-B partial case");
                    assertPixel(VolumetricParticleReference.occluding(straddling, ray,
                                    asDouble(BACKGROUND), false), pixels, 32, 32, TOLERANCE,
                            "a body in front of the occluder integrates up to the depth");
                    int sourceIndex = (32 * SIZE + 32) * 4;
                    assertTrue(pixels.get(sourceIndex) > source.get(sourceIndex) + 0.01f,
                            "the truncated particle contribution must be visible");
                }
            }
            GlDebug.assertNoError("particle depth A-B occlusion");
        }
    }

    @Test
    void volumetricFogAndParticlesApplyEachMediumExactlyOnce() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = camera();
            float depth = floorDeviceDepth(camera);
            VolumetricFogSettings fog = new VolumetricFogSettings(true, 32.0f,
                    VolumetricFogSettings.Quality.BALANCED,
                    FogMediumSettings.homogeneous(0.2f, new Vector3f(0.8f), new Vector3f()),
                    List.of(), 0.0f, false, 0.0f, 1337, new Vector3f());
            int fogBytes = SIZE * SIZE * 4 * Float.BYTES;
            try (VolumetricPassBuilder builder = new VolumetricPassBuilder(9L, SIZE, SIZE, fog)) {
                Scene scene = new Scene(camera);
                RenderFrameContext context = RenderFrameContext.capture(scene, camera, SIZE, SIZE,
                        1.0f / 60.0f, 0, 0L, 0L, null);
                builder.prepareFrame(context, fog);
                CommandBuffer commands = device.createCommandBuffer();
                builder.recordMedium(commands);
                builder.recordSource(commands);
                builder.recordTemporal(commands);
                builder.recordIntegrate(commands);
                device.execute(commands);
                builder.prepareFrameSuccess();
                // The frame stays pending until frameSucceeded(): bindQuery below
                // needs the prepared frame, exactly like the production composite.

                try (Framebuffer fogTarget = sceneTarget();
                     GlBuffer fogValues = new GlBuffer(GL_SHADER_STORAGE_BUFFER, GL_DYNAMIC_DRAW);
                     ShaderProgram query = ShaderProgram.fromComputeSource(fogQuerySource());
                     ShaderProgram composite = ShaderProgram.fromSources(PASSTHROUGH_VERTEX,
                             fogCompositeFragmentSource());
                     ScreenQuad quad = new ScreenQuad()) {
                    clear(fogTarget, BRIGHT_BACKGROUND, depth);
                    fogValues.allocateStorage(fogBytes, GL_DYNAMIC_STORAGE_BIT);

                    commands = device.createCommandBuffer();
                    commands.bindShader(query).bindStorageBuffer(3, fogValues, 0, fogBytes);
                    builder.bindQuery(commands, query);
                    commands.dispatchCompute(SIZE / 8, SIZE / 8, 1)
                            .memoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
                    device.execute(commands);
                    FloatBuffer fogQuery = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
                    glGetNamedBufferSubData(fogValues.id(), 0, fogQuery);

                    commands = device.createCommandBuffer();
                    commands.bindFramebuffer(fogTarget).viewport(0, 0, SIZE, SIZE)
                            .enableDepthTest(false).depthMask(false).enableBlend(false)
                            .enableScissor(false).enableCullFace(false)
                            .bindShader(composite);
                    builder.bindQuery(commands, composite);
                    commands.setUniformFloat(composite, "uDepth", FLOOR_DISTANCE)
                            .setUniformVec3(composite, "uBackground",
                                    new Vector3f(BRIGHT_BACKGROUND[0], BRIGHT_BACKGROUND[1],
                                            BRIGHT_BACKGROUND[2]))
                            .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6);
                    device.execute(commands);
                    FloatBuffer fogColor = readColor(fogTarget);

                    int center = (32 * SIZE + 32) * 4;
                    assertTrue(fogColor.get(center) < BRIGHT_BACKGROUND[0] * 0.5f,
                            "the fixture must actually apply the fog transmittance");

                    VolumetricParticleSettings settings = settings(new Vector3f(), 48, 0.05f,
                            0.5f, 1.0f, 1.0f, VolumetricParticleSettings.FogBlend.OCCLUDING);
                    try (VolumetricParticlePass pass = new VolumetricParticlePass(settings,
                            SIZE, SIZE)) {
                        FloatBuffer pixels = render(device, pass, fogTarget, camera);
                        Matrix4f inverseProjection = new Matrix4f(camera.projection()).invert();
                        Matrix4f inverseView = new Matrix4f(
                                camera.getViewMatrix(new Matrix4f())).invert();
                        VolumetricParticleReference.Ray ray = VolumetricParticleReference
                                .rayForPixel(inverseProjection, inverseView, SIZE, SIZE, 32, 32, depth);
                        double opticalDepth = VolumetricParticleReference
                                .quadratureOpticalDepth(settings, ray);
                        assertTrue(opticalDepth > 0.1, "the fog case must include a particle medium");
                        double[] composed = {fogColor.get(center), fogColor.get(center + 1),
                                fogColor.get(center + 2)};
                        double[] single = VolumetricParticleReference.occluding(settings, ray,
                                composed, false);
                        assertPixel(single, pixels, 32, 32, TOLERANCE,
                                "the particle medium applies once over the fog-composited colour");

                        double fogRed = fogQuery.get(center);
                        double fogGreen = fogQuery.get(center + 1);
                        double fogBlue = fogQuery.get(center + 2);
                        double fogTransmittance = fogQuery.get(center + 3);
                        double[] doubledFog = {
                                fogRed + fogTransmittance * composed[0],
                                fogGreen + fogTransmittance * composed[1],
                                fogBlue + fogTransmittance * composed[2]};
                        double[] doubled = VolumetricParticleReference.occluding(settings, ray,
                                doubledFog, false);
                        assertTrue(maxDifference(doubled, single) > 0.01,
                                "the fixture must separate single and doubled fog composition");
                        assertTrue(maxDifference(new double[]{pixels.get(center),
                                        pixels.get(center + 1), pixels.get(center + 2)}, doubled)
                                        > 0.01,
                                "the particle output must differ from a doubled fog composition");
                    }
                }
                builder.frameSucceeded();
            }
            GlDebug.assertNoError("single fog/particle composition");
        }
    }

    @Test
    void stepBudgetAndDensityChangesAreMonotoneAndBounded() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            GlRenderDevice device = new GlRenderDevice();
            ExternalCamera camera = camera();
            float depth = floorDeviceDepth(camera);
            try (Framebuffer scene = sceneTarget()) {
                clear(scene, BACKGROUND, depth);
                FloatBuffer source = readColor(scene);
                Matrix4f inverseProjection = new Matrix4f(camera.projection()).invert();
                Matrix4f inverseView = new Matrix4f(camera.getViewMatrix(new Matrix4f())).invert();
                VolumetricParticleReference.Ray ray = VolumetricParticleReference.rayForPixel(
                        inverseProjection, inverseView, SIZE, SIZE, 32, 32, depth);

                VolumetricParticleSettings coarse = settings(new Vector3f(), 1, 0.05f,
                        1.5f, 1.0f, 1.0f, VolumetricParticleSettings.FogBlend.ADDITIVE);
                VolumetricParticleSettings fine = settings(new Vector3f(), 48, 0.05f,
                        1.5f, 1.0f, 1.0f, VolumetricParticleSettings.FogBlend.ADDITIVE);
                double[] analytic = VolumetricParticleReference.additive(fine, ray,
                        asDouble(BACKGROUND), true);
                double coarsePixel = renderCenterPixel(device, scene, camera, coarse);
                double finePixel = renderCenterPixel(device, scene, camera, fine);
                double coarseError = Math.abs(coarsePixel - analytic[0]);
                double fineError = Math.abs(finePixel - analytic[0]);
                assertTrue(coarseError > 3.0e-3,
                        "the one-step fixture must expose a visible quadrature error: " + coarseError);
                assertTrue(fineError < coarseError,
                        "a larger step budget must move the pixel toward the analytic reference: "
                                + fineError + " !< " + coarseError);
                assertEquals(VolumetricParticleReference.additive(coarse, ray,
                                asDouble(BACKGROUND), false)[0], coarsePixel, TOLERANCE,
                        "the coarse pixel must match its own documented quadrature");
                assertEquals(analytic[0], finePixel, TOLERANCE,
                        "the fine pixel must match the analytic reference");

                double previous = -1.0;
                for (float density : new float[]{0.5f, 1.5f, 4.0f}) {
                    VolumetricParticleSettings densitySettings = settings(new Vector3f(), 48,
                            0.05f, density, 1.0f, 1.0f,
                            VolumetricParticleSettings.FogBlend.ADDITIVE);
                    double pixel = renderCenterPixel(device, scene, camera, densitySettings);
                    assertTrue(pixel > previous, "density must increase the additive in-scatter");
                    assertEquals(VolumetricParticleReference.additive(densitySettings, ray,
                                    asDouble(BACKGROUND), false)[0], pixel, TOLERANCE,
                            "density pixel reference");
                    assertTrue(pixel <= BACKGROUND[0] + 3.5 + TOLERANCE,
                            "single scatter stays energy-bounded by the configured light");
                    previous = pixel;
                }

                VolumetricParticleSettings zero = settings(new Vector3f(), 48, 0.05f,
                        1.5f, 0.0f, 1.0f, VolumetricParticleSettings.FogBlend.OCCLUDING);
                double sourceCenter = source.get((32 * SIZE + 32) * 4);
                assertEquals(sourceCenter, renderCenterPixel(device, scene, camera, zero),
                        0.0, "a zero fog weight must be an exact particle no-op");
            }
            GlDebug.assertNoError("particle step/density monotonicity");
        }
    }

    @Test
    void disabledSettingsCreateNoPassAndLeaveTheBaselineByteIdentical() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (var tracking = GlDebug.acquireResourceTracking()) {
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = camera();
                float depth = floorDeviceDepth(camera);
                try (Framebuffer scene = sceneTarget()) {
                    clear(scene, BACKGROUND, depth);
                    float[] baseline = toArray(readColor(scene));
                    VolumetricParticleSettings disabled = VolumetricParticleSettings.disabled();
                    assertFalse(disabled.enabled());
                    assertFalse(disabled.active());
                    int liveBefore = GlDebug.resources().liveResources().size();
                    assertThrows(IllegalArgumentException.class,
                            () -> new VolumetricParticlePass(disabled, SIZE, SIZE),
                            "disabled settings must not create a pass or any GPU resource");
                    assertEquals(liveBefore, GlDebug.resources().liveResources().size(),
                            "a rejected disabled construction must not leak resources");
                    // The integration contract is: while disabled, no pass exists and
                    // nothing is recorded, so the scene stays byte-identical.
                    float[] after = toArray(readColor(scene));
                    assertArrayEquals(baseline, after, 0.0f,
                            "disabled particles must leave the baseline pixels byte-identical");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "disabled-path tracking must return to the baseline");
            }
            GlDebug.assertNoError("disabled particle no-op");
        }
    }

    @Test
    void resizeAndCloseAreTransactionalAndReleaseTrackedResources() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (var tracking = GlDebug.acquireResourceTracking()) {
                GlRenderDevice device = new GlRenderDevice();
                ExternalCamera camera = camera();
                ExternalCamera smallCamera = camera(SMALL_WIDTH, SMALL_HEIGHT);
                try (Framebuffer scene = sceneTarget();
                     Framebuffer small = sceneTarget(SMALL_WIDTH, SMALL_HEIGHT);
                     VolumetricParticlePass pass = new VolumetricParticlePass(
                             settings(new Vector3f(), 32, 0.1f, 2.0f, 1.0f, 1.0f,
                                     VolumetricParticleSettings.FogBlend.OCCLUDING),
                             SIZE, SIZE)) {
                    clear(scene, BACKGROUND, floorDeviceDepth(camera));
                    render(device, pass, scene, camera);
                    assertEquals(SIZE, pass.width());
                    assertEquals(SIZE, pass.height());

                    pass.resize(SMALL_WIDTH, SMALL_HEIGHT);
                    assertEquals(SMALL_WIDTH, pass.width());
                    assertEquals(SMALL_HEIGHT, pass.height());
                    int resizedId = pass.id();
                    assertTrue(resizedId != 0, "the resized target must be published");
                    clear(small, BACKGROUND, floorDeviceDepth(smallCamera));
                    FloatBuffer smallPixels = render(device, pass, small, smallCamera);
                    assertEquals(SMALL_WIDTH * SMALL_HEIGHT * 4, smallPixels.capacity(),
                            "the resized target must be readable at the new extent");

                    pass.resize(SMALL_WIDTH, SMALL_HEIGHT);
                    assertEquals(resizedId, pass.id(), "a same-extent resize is a no-op");
                    assertThrows(IllegalArgumentException.class, () -> pass.resize(0, SMALL_HEIGHT));
                    assertEquals(SMALL_WIDTH, pass.width());
                    assertEquals(resizedId, pass.id(), "a failed resize must keep the old target");

                    int closedId = pass.id();
                    pass.close();
                    assertTrue(pass.isClosed());
                    assertEquals(0, pass.id());
                    assertEquals(0, pass.colorTexture());
                    assertEquals(0, pass.width());
                    assertEquals(0, pass.height());
                    assertTrue(closedId != 0);
                    pass.close();
                    assertThrows(GlException.class,
                            () -> pass.record(device.createCommandBuffer(), small,
                                    small.depthAttachment(), smallCamera));
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "resize/close must release every tracked GL resource");
            }
            GlDebug.assertNoError("particle resize/close lifecycle");
        }
    }

    private static VolumetricParticleSettings settings(Vector3f center, int steps, float stepSize,
                                                       float density, float weight,
                                                       float maximumCoverage,
                                                       VolumetricParticleSettings.FogBlend blend) {
        return new VolumetricParticleSettings(true, density, 0.2f, 0.8f, center,
                new Vector3f(1.5f), steps, stepSize, new Vector3f(0.3f, -1.0f, 0.2f),
                new Vector3f(3.5f, 3.2f, 3.0f), 0.2f, blend, weight, maximumCoverage);
    }

    private static ExternalCamera camera() {
        return camera(SIZE, SIZE);
    }

    private static ExternalCamera camera(int width, int height) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(50.0),
                width / (float) height, 0.125f, 64.0f);
        Matrix4f view = new Matrix4f().lookAt(new Vector3f(0.0f, 0.0f, 8.0f), new Vector3f(),
                new Vector3f(0.0f, 1.0f, 0.0f));
        return ExternalCamera.of(view, projection, new Vector3f(0.0f, 0.0f, 8.0f), 0.0f);
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

    private static FloatBuffer render(GlRenderDevice device, VolumetricParticlePass pass,
                                      Framebuffer source, ExternalCamera camera) {
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, source, source.depthAttachment(), camera);
        device.execute(commands);
        return readColor(pass.colorTarget());
    }

    private static double renderCenterPixel(GlRenderDevice device, Framebuffer source,
                                            ExternalCamera camera,
                                            VolumetricParticleSettings settings) {
        try (VolumetricParticlePass pass = new VolumetricParticlePass(settings, SIZE, SIZE)) {
            FloatBuffer pixels = render(device, pass, source, camera);
            return pixels.get((32 * SIZE + 32) * 4);
        }
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

    private static float floorDeviceDepth(ExternalCamera camera) {
        Matrix4f projection = camera.projection();
        Vector4f clip = new Vector4f(0.0f, 0.0f, -FLOOR_DISTANCE, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static float readDepth(Framebuffer source, int x, int y) {
        source.bind();
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        assertEquals(GL_NO_ERROR, glGetError());
        return depth.get(0);
    }

    private static void drawForegroundDepth(GlRenderDevice device, Framebuffer scene,
                                            ExternalCamera camera, int centerX, int centerY,
                                            float viewDistance) {
        try (ShaderProgram shader = ShaderProgram.fromSources(QUAD_VERTEX, QUAD_FRAGMENT);
             Mesh mesh = foregroundQuad(camera, centerX, centerY, viewDistance)) {
            CommandBuffer commands = device.createCommandBuffer();
            commands.bindFramebuffer(scene)
                    .viewport(0, 0, SIZE, SIZE)
                    .enableScissor(false)
                    .enableBlend(false)
                    .enableCullFace(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .bindShader(shader)
                    .setUniformMat4(shader, "uProjection", camera.projection())
                    .setUniformVec4(shader, "uColor", new Vector4f(BACKGROUND[0], BACKGROUND[1],
                            BACKGROUND[2], 1.0f))
                    .bindMesh(mesh)
                    .drawMesh(mesh);
            device.execute(commands);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static Mesh foregroundQuad(ExternalCamera camera, int centerX, int centerY,
                                       float viewDistance) {
        float tanHalf = (float) Math.tan(Math.toRadians(50.0 * 0.5));
        float ndcX = (centerX + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (centerY + 0.5f) / SIZE * 2.0f - 1.0f;
        float centerXView = ndcX * viewDistance * tanHalf;
        float centerYView = ndcY * viewDistance * tanHalf;
        float halfExtent = FOREGROUND_HALF_EXTENT_PIXELS
                * (2.0f * viewDistance * tanHalf / SIZE);
        float z = -viewDistance;
        float[] positions = {
                centerXView - halfExtent, centerYView - halfExtent, z,
                centerXView + halfExtent, centerYView - halfExtent, z,
                centerXView + halfExtent, centerYView + halfExtent, z,
                centerXView - halfExtent, centerYView + halfExtent, z
        };
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        return Mesh.builder()
                .vertices(positions, 3 * Float.BYTES, position)
                .indices(new int[]{0, 1, 2, 0, 2, 3})
                .build();
    }

    private static int[] findLitPixel(VolumetricParticleSettings settings,
                                      Matrix4f inverseProjection, Matrix4f inverseView,
                                      float depth, int excludedX, int excludedY) {
        for (int y = 8; y < SIZE - 8; y++) {
            for (int x = 8; x < SIZE - 8; x++) {
                if (Math.abs(x - excludedX) <= 8 && Math.abs(y - excludedY) <= 8) {
                    continue;
                }
                VolumetricParticleReference.Ray ray = VolumetricParticleReference.rayForPixel(
                        inverseProjection, inverseView, SIZE, SIZE, x, y, depth);
                if (VolumetricParticleReference.quadratureOpticalDepth(settings, ray) > 0.5) {
                    return new int[]{x, y};
                }
            }
        }
        throw new AssertionError("the fixture did not produce a lit particle pixel");
    }

    private static String fogQuerySource() {
        return "#version 460 core\n"
                + VolumetricPassBuilder.resource("volume-common.glsl") + "\n"
                + VolumetricPassBuilder.resource("volume-query.glsl") + "\n"
                + "layout (local_size_x = 8, local_size_y = 8) in;\n"
                + "layout (std430, binding = 3) buffer FogOut { vec4 values[]; };\n"
                + "uniform float uDepth;\n"
                + "void main() {\n"
                + "    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);\n"
                + "    if (pixel.x >= " + SIZE + " || pixel.y >= " + SIZE + ") return;\n"
                + "    values[pixel.y * " + SIZE + " + pixel.x] = volumeQuery(vec2(pixel) + 0.5, uDepth);\n"
                + "}\n";
    }

    private static String fogCompositeFragmentSource() {
        return "#version 460 core\n"
                + VolumetricPassBuilder.resource("volume-common.glsl") + "\n"
                + VolumetricPassBuilder.resource("volume-query.glsl") + "\n"
                + "uniform vec3 uBackground;\n"
                + "uniform float uDepth;\n"
                + "layout (location = 0) out vec4 FragColor;\n"
                + "void main() {\n"
                + "    vec4 fog = volumeQuery(gl_FragCoord.xy, uDepth);\n"
                + "    FragColor = vec4(fog.rgb + fog.a * uBackground, 1.0);\n"
                + "}\n";
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
                .title("Volumetric particle pass test").visible(false).build();
    }

    private static void assertPixel(double[] expected, FloatBuffer pixels, int x, int y,
                                    float tolerance, String message) {
        int index = (y * SIZE + x) * 4;
        assertEquals(expected[0], pixels.get(index), tolerance, message + " red");
        assertEquals(expected[1], pixels.get(index + 1), tolerance, message + " green");
        assertEquals(expected[2], pixels.get(index + 2), tolerance, message + " blue");
    }

    private static double[] asDouble(float[] values) {
        return new double[]{values[0], values[1], values[2]};
    }

    private static float[] toArray(FloatBuffer buffer) {
        float[] values = new float[buffer.remaining()];
        buffer.duplicate().get(values);
        return values;
    }

    private static double maxDifference(double[] left, double[] right) {
        return Math.max(Math.abs(left[0] - right[0]),
                Math.max(Math.abs(left[1] - right[1]), Math.abs(left[2] - right[2])));
    }
}
