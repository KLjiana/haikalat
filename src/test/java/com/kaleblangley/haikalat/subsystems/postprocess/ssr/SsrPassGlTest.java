package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderFrameContext;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceResolvePass;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;

/**
 * Real-GL acceptance for the bounded screen-space reflection pass.
 *
 * <p>Case 1 renders a deterministic analytic fixture: a floor plane reflecting
 * a narrow emissive wall.  Depth, world normal, roughness and linear HDR colour
 * are generated from the same closed-form geometry, so the expected reflection
 * direction can be derived independently of the shader: only floor pixels whose
 * mirrored ray reaches the front face of the wall may brighten, while wall and
 * sky pixels (roughness above the cutoff) must keep their input colour exactly.
 * A short ray bound and an all-rough variant must reproduce the input image
 * bit-exactly, which is the environment/IBL fallback.</p>
 *
 * <p>Case 2 checks the colour-chain contract: the disabled request keeps the
 * exact baseline pass order, the enabled request inserts one RGBA16F SSR pass
 * after geometry/surface production and before tone mapping, and disabling the
 * request again restores the baseline.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SsrPassGlTest {
    private static final int SIZE = 96;
    private static final Vector3f EYE = new Vector3f(0.0f, 1.6f, 4.0f);
    private static final Vector3f CENTRE = new Vector3f(0.0f, 1.0f, -3.0f);
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(55.0);
    private static final float WALL_Z = -3.0f;
    private static final float WALL_HALF_WIDTH = 1.2f;
    private static final float WALL_HEIGHT = 2.0f;
    private static final float FLOOR_ROUGHNESS = 0.05f;
    private static final float WALL_ROUGHNESS = 0.9f;
    private static final float SKY_ROUGHNESS = 1.0f;
    private static final Vector3f FLOOR_COLOR = new Vector3f(0.05f, 0.06f, 0.07f);
    private static final Vector3f WALL_COLOR = new Vector3f(32.0f, 0.2f, 0.1f);
    private static final Vector3f SKY_COLOR = new Vector3f(0.01f, 0.02f, 0.03f);
    private static final float WALL_SURFACE_RED = 32.0f;

    private static final Matrix4f VIEW = new Matrix4f().lookAt(EYE, CENTRE,
            new Vector3f(0.0f, 1.0f, 0.0f));
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(FIELD_OF_VIEW,
            1.0f, 0.1f, 100.0f);
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(PROJECTION).mul(VIEW);
    private static final Matrix4f INVERSE_VIEW = new Matrix4f(VIEW).invert();
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();

    private static final SsrSettings SETTINGS = SsrSettings.builder()
            .enabled(true)
            .maxDistanceMetres(12.0f)
            .maxSteps(48)
            .thicknessMetres(1.0f)
            .roughnessCutoff(0.4f)
            .minimumConfidence(0.15f)
            .strength(0.9f)
            .edgeFadePixels(8.0f)
            .build();

    /**
     * MSAA G-buffer producer used by the resolve-path case. Sample 1 is the
     * nearest covered sample at every pixel (a small device-depth bias), and it
     * writes roughness 0.05 while the other samples write 0.95. The production
     * {@link SceneSurfaceResolvePass} must therefore resolve roughness to 0.05;
     * an averaged or wrong-sample resolve would exceed the SSR roughness cutoff
     * and fall back exactly.
     */
    private static final String MSAA_SURFACE_PATTERN = """
            #version 460 core
            uniform sampler2D uDepth;
            uniform sampler2D uNormal;
            uniform vec2 uViewportSize;
            layout(location = 0) out vec2 outNormal;
            layout(location = 1) out float outRoughness;
            void main() {
                vec2 uv = gl_FragCoord.xy / uViewportSize;
                float depth = texture(uDepth, uv).r;
                if (depth >= 1.0) discard;
                int sampleId = gl_SampleID;
                gl_FragDepth = depth + (sampleId == 1 ? -1.0e-4 : 1.0e-4);
                outNormal = texture(uNormal, uv).rg;
                float sampleRoughness[4] = float[4](0.95, 0.05, 0.95, 0.95);
                outRoughness = sampleRoughness[sampleId];
            }
            """;

    @Test
    void reflectionFollowsTheMirroredRayAndFallsBackAtRoughnessAndDistance() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SSR Pass GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            recordDevice();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr",
                    PbrEnvironmentSettings.testQuality());
                 Sampler cubeSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                         GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE))) {
                SsrEnvironmentSource source = environment(environment, cubeSampler);
                Fixture fixture = fixture();
                float[] baseline = render(device, fixture.colour(), fixture.depth(),
                        fixture.normal(), fixture.roughness(), SETTINGS, source);
                assertReflectionFollowsTheMirrorGeometry(fixture, baseline);
                assertRoughAndBackgroundPixelsAreExact(fixture, baseline);

                // The fixture's nearest analytic reflection distance is about
                // 0.199 m, so a 0.15 m bound cannot reach the wall anywhere.
                SsrSettings bounded = SETTINGS.withMaxDistanceMetres(0.15f)
                        .withThicknessMetres(0.1f);
                Fixture boundedFixture = fixture();
                float[] boundedOutput = render(device, boundedFixture.colour(),
                        boundedFixture.depth(), boundedFixture.normal(),
                        boundedFixture.roughness(), bounded, source);
                assertExactBaseline(boundedFixture, boundedOutput,
                        "a ray bound shorter than the wall distance must fall back exactly");

                Fixture roughFixture = fixture().withUniformRoughness(1.0f);
                float[] roughOutput = render(device, roughFixture.colour(),
                        roughFixture.depth(), roughFixture.normal(),
                        roughFixture.roughness(), SETTINGS, source);
                assertExactBaseline(roughFixture, roughOutput,
                        "roughness above the cutoff must fall back to the environment exactly");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void reflectionSignalIsTheExactLegacyDeltaAndZeroOnFallback() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SSR Reflection Signal GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr",
                    PbrEnvironmentSettings.testQuality());
                 Sampler cubeSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                         GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE))) {
                SsrEnvironmentSource source = environment(environment, cubeSampler);
                Fixture fixture = fixture();
                float[] legacy = render(device, fixture.colour(), fixture.depth(),
                        fixture.normal(), fixture.roughness(), SETTINGS, source, false);
                float[] signal = render(device, fixture.colour(), fixture.depth(),
                        fixture.normal(), fixture.roughness(), SETTINGS, source, true);

                int exposed = 0;
                int fallback = 0;
                for (int index = 0; index < SIZE * SIZE; index++) {
                    int offset = index * 4;
                    float confidence = signal[offset + 3];
                    assertTrue(confidence >= 0.0f && confidence <= 1.0f,
                            "the confidence must stay bounded at pixel " + index);
                    if (confidence == 0.0f) {
                        fallback++;
                        // Exact legacy fallback and the exact zero contribution.
                        assertEquals(0.0f, signal[offset], 0.0f,
                                "a zero-confidence pixel must write zero contribution");
                        assertEquals(0.0f, signal[offset + 1], 0.0f);
                        assertEquals(0.0f, signal[offset + 2], 0.0f);
                        assertEquals(fixture.colour()[offset], legacy[offset], 0.0f,
                                "the legacy fallback must be byte-identical at pixel "
                                        + index);
                        assertEquals(fixture.colour()[offset + 1], legacy[offset + 1], 0.0f);
                        assertEquals(fixture.colour()[offset + 2], legacy[offset + 2], 0.0f);
                    } else {
                        exposed++;
                        for (int channel = 0; channel < 3; channel++) {
                            float expected = legacy[offset + channel]
                                    - fixture.colour()[offset + channel];
                            assertEquals(expected, signal[offset + channel], 2.0e-5f,
                                    "the signal must be exactly the legacy delta at pixel "
                                            + index + " channel " + channel);
                        }
                    }
                    for (int channel = 0; channel < 3; channel++) {
                        assertEquals(legacy[offset + channel],
                                fixture.colour()[offset + channel]
                                        + signal[offset + channel], 2.0e-5f,
                                "base + contribution must reproduce the legacy colour at pixel "
                                        + index);
                    }
                }
                assertTrue(exposed > 200,
                        "the fixture must expose a meaningful confident reflection region: "
                                + exposed);
                assertTrue(fallback > 1000,
                        "the fixture must contain a meaningful zero-confidence region: "
                                + fallback);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }


    @Test
    void adaptiveReflectionSignalVariantCompilesAndKeepsBoundedConfidence() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SSR Adaptive Signal GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr",
                    PbrEnvironmentSettings.testQuality());
                 Sampler cubeSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                         GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE))) {
                SsrEnvironmentSource source = environment(environment, cubeSampler);
                Fixture fixture = fixture();
                float[] signal = render(device, fixture.colour(), fixture.depth(),
                        fixture.normal(), fixture.roughness(), SETTINGS, source, true,
                        com.kaleblangley.haikalat.subsystems.postprocess
                                .AdaptiveSamplingSettings.defaults());
                int exposed = 0;
                int fallback = 0;
                for (int index = 0; index < SIZE * SIZE; index++) {
                    int offset = index * 4;
                    for (int channel = 0; channel < 4; channel++) {
                        assertTrue(Float.isFinite(signal[offset + channel]),
                                "the adaptive signal variant must stay finite at pixel "
                                        + index);
                    }
                    assertTrue(signal[offset + 3] >= 0.0f && signal[offset + 3] <= 1.0f,
                            "the adaptive confidence must stay bounded at pixel " + index);
                    if (signal[offset + 3] == 0.0f) fallback++;
                    else exposed++;
                }
                assertTrue(exposed > 200,
                        "the adaptive variant must expose confident reflections: " + exposed);
                assertTrue(fallback > 1000,
                        "the adaptive variant must keep zero-confidence fallbacks: "
                                + fallback);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }


    @Test
    void msaaRoughnessResolveDrivesTheFixedAndAdaptiveSsrPaths() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SSR MSAA Roughness Resolve GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                    "/environments/pbr/studio-small.hdr",
                    PbrEnvironmentSettings.testQuality());
                 Sampler cubeSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR,
                         GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE))) {
                SsrEnvironmentSource source = environment(environment, cubeSampler);
                Fixture fixture = fixture();
                SceneSurfaceLayout layout = SceneSurfaceLayout.plan(4,
                        SceneBufferRequirements.none(), SceneBufferRequirements.ssr(),
                        SceneSurfaceLayout.ReactiveMode.NONE);
                assertEquals(4, layout.samples());
                assertTrue(layout.hasResolve(),
                        "a 4x MSAA SSR request must create a single-sample surface resolve");

                try (Texture2D colourTexture = floatTexture(4, fixture.colour());
                     Texture2D depthSource = floatTexture(1, fixture.depth());
                     Texture2D normalSource = floatTexture(4, fixture.normal());
                     Texture2D roughnessSource = byteTexture(fixture.roughness());
                     ShaderProgram producer = ShaderProgram.builder()
                             .resource(SsrPassGlTest.class, ShaderStage.VERTEX,
                                     "/shaders/postprocess/screen-quad.vert")
                             .stage(ShaderStage.FRAGMENT, MSAA_SURFACE_PATTERN,
                                     "ssr-msaa-surface.frag")
                             .link();
                     ScreenQuad quad = new ScreenQuad();
                     SceneSurfaceResolvePass resolve = new SceneSurfaceResolvePass(layout);
                     RenderGraph graph = new RenderGraph(SIZE, SIZE);
                     SsrPass resolvedFixed = new SsrPass(SETTINGS);
                     SsrPass resolvedAdaptive = new SsrPass(SETTINGS,
                             AdaptiveSamplingSettings.defaults())) {
                    SceneSurfaceInputs inputs = new SceneSurfaceInputs(1, graph, layout);
                    var raw = graph.addPass(layout.raw(SceneBufferChannel.DEPTH).producer());
                    layout.configureRaster(raw);
                    raw.execute((res, cmd) -> {
                        cmd.enableBlend(false).enableDepthTest(true).depthMask(true)
                                .enableCullFace(false)
                                .bindShader(producer)
                                .bindTexture(0, depthSource.id())
                                .bindTexture(1, normalSource.id())
                                .setUniformInt(producer, "uDepth", 0)
                                .setUniformInt(producer, "uNormal", 1)
                                .setUniformVec2(producer, "uViewportSize", SIZE, SIZE)
                                .sampleShading(true, 1.0f)
                                .bindVertexArray(quad.id())
                                .drawArrays(GL_TRIANGLES, 0, 6)
                                .sampleShading(false, 0.0f);
                        inputs.frame().rasterProduced(res);
                    });
                    var resolvePass = graph.addPass(
                            layout.single(SceneBufferChannel.DEPTH).producer());
                    layout.configureResolve(resolvePass);
                    resolvePass.execute((res, cmd) -> {
                        resolve.record(cmd, res, inputs);
                        inputs.frame().resolveProduced(res);
                    });
                    graph.addPass("SsrMsaaResolvedFixed")
                            .createColor("ssrMsaaResolvedFixed", RenderFormat.RGBA32F)
                            .noClear()
                            .dependsOn(layout.single(SceneBufferChannel.DEPTH).producer())
                            .execute((res, cmd) -> recordResolvedSsr(cmd, resolvedFixed,
                                    colourTexture, inputs, layout, res, source));
                    graph.addPass("SsrMsaaResolvedAdaptive")
                            .createColor("ssrMsaaResolvedAdaptive", RenderFormat.RGBA32F)
                            .noClear()
                            .dependsOn(layout.single(SceneBufferChannel.DEPTH).producer())
                            .execute((res, cmd) -> recordResolvedSsr(cmd, resolvedAdaptive,
                                    colourTexture, inputs, layout, res, source));
                    graph.compile();
                    inputs.validate();
                    Scene scene = new Scene(new Camera());
                    inputs.beginFrame(RenderFrameContext.capture(scene, scene.camera(),
                            SIZE, SIZE, 0, 0, 0, 0, null));
                    try {
                        graph.execute(device);
                    } finally {
                        inputs.endFrame();
                    }

                    float[] resolvedRoughness = graph.readColorAttachmentRgbaFloat(
                            layout.single(SceneBufferChannel.ROUGHNESS).name());
                    float[] resolvedDepth = graph.readColorAttachmentRgbaFloat(
                            layout.single(SceneBufferChannel.DEPTH).name());
                    float[] resolvedNormal = graph.readColorAttachmentRgbaFloat(
                            layout.single(SceneBufferChannel.NORMAL).name());
                    int covered = 0;
                    for (int index = 0; index < SIZE * SIZE; index++) {
                        if (fixture.depth[index] < 1.0f) {
                            covered++;
                            assertEquals(13.0f / 255.0f, resolvedRoughness[index * 4],
                                    1.0f / 255.0f,
                                    "the resolve must carry sample 1's roughness at pixel "
                                            + index);
                        } else {
                            assertEquals(1.0f, resolvedRoughness[index * 4], 1.0f / 255.0f,
                                    "uncovered samples keep the rough clear value at pixel "
                                            + index);
                        }
                    }
                    assertTrue(covered > 2000,
                            "the fixture must cover a meaningful MSAA surface region: "
                                    + covered);

                    float[] fixedResolved = graph.readColorAttachmentRgbaFloat(
                            "ssrMsaaResolvedFixed");
                    float[] adaptiveResolved = graph.readColorAttachmentRgbaFloat(
                            "ssrMsaaResolvedAdaptive");

                    float[] controlDepth = new float[SIZE * SIZE];
                    float[] controlNormal = new float[SIZE * SIZE * 4];
                    byte[] controlRoughness = new byte[SIZE * SIZE];
                    byte[] wrongRoughness = new byte[SIZE * SIZE];
                    for (int index = 0; index < SIZE * SIZE; index++) {
                        controlDepth[index] = resolvedDepth[index * 4];
                        controlNormal[index * 4] = resolvedNormal[index * 4];
                        controlNormal[index * 4 + 1] = resolvedNormal[index * 4 + 1];
                        controlNormal[index * 4 + 3] = 1.0f;
                        controlRoughness[index] = (byte) Math.round(
                                resolvedRoughness[index * 4] * 255.0f);
                        wrongRoughness[index] = (byte) Math.round(0.95f * 255.0f);
                    }
                    try (Texture2D controlDepthTexture = floatTexture(1, controlDepth);
                         Texture2D controlNormalTexture = floatTexture(4, controlNormal);
                         Texture2D controlRoughnessTexture = byteTexture(controlRoughness);
                         Texture2D wrongRoughnessTexture = byteTexture(wrongRoughness)) {
                        float[] fixedControl = renderWithTextures(device, colourTexture,
                                controlDepthTexture, controlNormalTexture,
                                controlRoughnessTexture, SETTINGS,
                                AdaptiveSamplingSettings.disabled(), source);
                        float[] adaptiveControl = renderWithTextures(device, colourTexture,
                                controlDepthTexture, controlNormalTexture,
                                controlRoughnessTexture, SETTINGS,
                                AdaptiveSamplingSettings.defaults(), source);
                        assertExact(fixedResolved, fixedControl,
                                "the fixed SSR path must consume exactly the resolved "
                                        + "single-sample surface");
                        assertExact(adaptiveResolved, adaptiveControl,
                                "the adaptive SSR path must consume exactly the resolved "
                                        + "single-sample surface");
                        assertTrue(countChanged(fixture.colour(), fixedResolved) > 200,
                                "the resolved roughness must enable a real fixed reflection "
                                        + "region");
                        assertTrue(countChanged(fixture.colour(), adaptiveResolved) > 200,
                                "the resolved roughness must enable a real adaptive "
                                        + "reflection region");

                        float[] fixedWrong = renderWithTextures(device, colourTexture,
                                controlDepthTexture, controlNormalTexture,
                                wrongRoughnessTexture, SETTINGS,
                                AdaptiveSamplingSettings.disabled(), source);
                        float[] adaptiveWrong = renderWithTextures(device, colourTexture,
                                controlDepthTexture, controlNormalTexture,
                                wrongRoughnessTexture, SETTINGS,
                                AdaptiveSamplingSettings.defaults(), source);
                        assertExactBaseline(fixture, fixedWrong,
                                "a wrong resolved roughness sample must fall back exactly in "
                                        + "the fixed path");
                        assertExactBaseline(fixture, adaptiveWrong,
                                "a wrong resolved roughness sample must fall back exactly in "
                                        + "the adaptive path");
                    }
                }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void pipelineAddsBoundedSsrInTheHdrChainAndRestoresTheDisabledBaseline() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SSR Pipeline GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            assertDisabledBaseline();
            assertEnabledChain();
            assertDisabledBaseline();
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    /** Records the actual device/driver next to the JUnit XML evidence. */
    private static void recordDevice() {
        String vendor = glGetString(GL_VENDOR);
        String renderer = glGetString(GL_RENDERER);
        String version = glGetString(GL_VERSION);
        var snapshot = GlDebug.messages();
        StringBuilder text = new StringBuilder();
        text.append("vendor=").append(vendor).append('\n')
                .append("renderer=").append(renderer).append('\n')
                .append("version=").append(version).append('\n')
                .append("debugMessages=").append(snapshot.messages().size())
                .append(" dropped=").append(snapshot.droppedCount()).append('\n');
        for (var message : snapshot.messages()) {
            text.append("  ").append(message.severity()).append(' ')
                    .append(message.source()).append(' ').append(message.type())
                    .append(" id=").append(message.driverId()).append(' ')
                    .append(message.message()).append('\n');
        }
        try {
            Path directory = Path.of("build", "reports", "gl");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("ssr-pass-device.txt"), text.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot record the SSR GL device identity", failure);
        }
    }

    private static void assertReflectionFollowsTheMirrorGeometry(Fixture fixture,
                                                                 float[] output) {
        assertTrue(fixture.coreHits().size() >= 200,
                "the fixture must contain a meaningful reflected region, got "
                        + fixture.coreHits().size());
        for (int index : fixture.coreHits()) {
            int offset = index * 4;
            assertTrue(output[offset] > fixture.colour()[offset] + 0.5f,
                    "the mirrored ray must brighten the reflection at pixel " + index
                            + " (red " + output[offset] + " vs base "
                            + fixture.colour()[offset] + ")");
        }
        int changed = 0;
        for (int index = 0; index < SIZE * SIZE; index++) {
            int offset = index * 4;
            if (Math.abs(output[offset] - fixture.colour()[offset]) > 1.0e-3f
                    || Math.abs(output[offset + 1] - fixture.colour()[offset + 1]) > 1.0e-3f
                    || Math.abs(output[offset + 2] - fixture.colour()[offset + 2]) > 1.0e-3f) {
                changed++;
                assertTrue(fixture.nearAnyAnalyticHit(index, 4),
                        "a changed pixel must lie next to an analytic reflection, pixel "
                                + index);
            }
        }
        assertTrue(changed <= fixture.analyticHits() + 96,
                "the reflected region must stay bounded by the analytic hit set: "
                        + changed + " changed vs " + fixture.analyticHits() + " hits");
        assertTrue(changed >= fixture.coreHits().size(),
                "every confident analytic hit must be visible");
    }

    private static void assertRoughAndBackgroundPixelsAreExact(Fixture fixture,
                                                               float[] output) {
        for (int index : fixture.roughOrBackground()) {
            int offset = index * 4;
            assertEquals(fixture.colour()[offset], output[offset], 0.0f,
                    "rough/background pixel must keep the environment fallback exactly at "
                            + index);
            assertEquals(fixture.colour()[offset + 1], output[offset + 1], 0.0f);
            assertEquals(fixture.colour()[offset + 2], output[offset + 2], 0.0f);
        }
    }

    private static void assertExactBaseline(Fixture fixture, float[] output, String message) {
        for (int index = 0; index < SIZE * SIZE; index++) {
            int offset = index * 4;
            assertEquals(fixture.colour()[offset], output[offset], 0.0f,
                    message + " (red at " + index + ")");
            assertEquals(fixture.colour()[offset + 1], output[offset + 1], 0.0f,
                    message + " (green at " + index + ")");
            assertEquals(fixture.colour()[offset + 2], output[offset + 2], 0.0f,
                    message + " (blue at " + index + ")");
        }
    }

    private static float[] render(GlRenderDevice device, float[] colour, float[] depth,
                                  float[] normal, byte[] roughness, SsrSettings settings,
                                  SsrEnvironmentSource source) {
        return render(device, colour, depth, normal, roughness, settings, source, false);
    }

    private static float[] render(GlRenderDevice device, float[] colour, float[] depth,
                                  float[] normal, byte[] roughness, SsrSettings settings,
                                  SsrEnvironmentSource source, boolean reflectionSignal) {
        return render(device, colour, depth, normal, roughness, settings, source,
                reflectionSignal,
                com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings
                        .disabled());
    }

    private static float[] render(GlRenderDevice device, float[] colour, float[] depth,
                                  float[] normal, byte[] roughness, SsrSettings settings,
                                  SsrEnvironmentSource source, boolean reflectionSignal,
                                  com.kaleblangley.haikalat.subsystems.postprocess
                                          .AdaptiveSamplingSettings adaptiveSettings) {
        try (Texture2D colourTexture = floatTexture(4, colour);
             Texture2D depthTexture = floatTexture(1, depth);
             Texture2D normalTexture = floatTexture(4, normal);
             Texture2D roughnessTexture = byteTexture(roughness);
             Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F);
             SsrPass pass = new SsrPass(settings, adaptiveSettings, reflectionSignal)) {
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            CommandBuffer commands = device.createCommandBuffer();
            pass.recordIntoCurrentTarget(commands, colourTexture.id(), depthTexture.id(),
                    normalTexture.id(), roughnessTexture.id(), INVERSE_PROJECTION,
                    INVERSE_VIEW, VIEW, VIEW_PROJECTION, source, SIZE, SIZE);
            device.execute(commands);
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[SIZE * SIZE * 4];
            pixels.get(result);
            return result;
        }
    }

    private static SsrEnvironmentSource environment(PbrEnvironment environment,
                                                    Sampler sampler) {
        return new SsrEnvironmentSource() {
            @Override
            public float bindPrefilteredSpecular(CommandBuffer cmd, int unit) {
                cmd.bindTextureCube(unit, environment.prefilteredSpecular(), sampler);
                return environment.prefilteredSpecular().mipLevels() - 1.0f;
            }

            @Override
            public float rotationRadians() {
                return environment.rotationRadians();
            }

            @Override
            public float intensity() {
                return environment.intensity();
            }
        };
    }

    private static Texture2D floatTexture(int channels, float[] data) {
        int format = channels == 1 ? GL_R32F : GL_RGBA32F;
        Texture2D texture = Texture2D.createEmpty(SIZE, SIZE, format);
        ByteBuffer upload = BufferUtils.createByteBuffer(
                SIZE * SIZE * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, SIZE, SIZE, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static Texture2D byteTexture(byte[] roughness) {
        Texture2D texture = Texture2D.createEmpty(SIZE, SIZE, GL_R8);
        ByteBuffer upload = BufferUtils.createByteBuffer(SIZE * SIZE);
        upload.put(roughness);
        upload.flip();
        texture.uploadRegion(0, 0, SIZE, SIZE, upload);
        texture.setFiltering(GL_NEAREST, GL_NEAREST);
        return texture;
    }

    private static void recordResolvedSsr(CommandBuffer cmd, SsrPass pass,
                                          Texture2D colour, SceneSurfaceInputs inputs,
                                          SceneSurfaceLayout layout, PassResources resources,
                                          SsrEnvironmentSource source) {
        pass.recordIntoCurrentTarget(cmd, colour.id(),
                inputs.frame().require(resources,
                        layout.single(SceneBufferChannel.DEPTH)),
                inputs.frame().require(resources,
                        layout.single(SceneBufferChannel.NORMAL)),
                inputs.frame().require(resources,
                        layout.single(SceneBufferChannel.ROUGHNESS)),
                INVERSE_PROJECTION, INVERSE_VIEW, VIEW, VIEW_PROJECTION, source,
                SIZE, SIZE);
    }

    private static float[] renderWithTextures(GlRenderDevice device, Texture2D colour,
                                              Texture2D depth, Texture2D normal,
                                              Texture2D roughness, SsrSettings settings,
                                              AdaptiveSamplingSettings adaptive,
                                              SsrEnvironmentSource source) {
        try (Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F);
             SsrPass pass = new SsrPass(settings, adaptive)) {
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            CommandBuffer commands = device.createCommandBuffer();
            pass.recordIntoCurrentTarget(commands, colour.id(), depth.id(), normal.id(),
                    roughness.id(), INVERSE_PROJECTION, INVERSE_VIEW, VIEW,
                    VIEW_PROJECTION, source, SIZE, SIZE);
            device.execute(commands);
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[SIZE * SIZE * 4];
            pixels.get(result);
            return result;
        }
    }

    private static void assertExact(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], 0.0f,
                    message + " at index " + index);
        }
    }

    private static int countChanged(float[] base, float[] output) {
        int changed = 0;
        for (int index = 0; index < SIZE * SIZE; index++) {
            int offset = index * 4;
            if (Math.abs(base[offset] - output[offset]) > 1.0e-3f
                    || Math.abs(base[offset + 1] - output[offset + 1]) > 1.0e-3f
                    || Math.abs(base[offset + 2] - output[offset + 2]) > 1.0e-3f) {
                changed++;
            }
        }
        return changed;
    }

    private static void assertDisabledBaseline() {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(settings,
                PostProcessSettings.defaults(), SIZE, SIZE, null, NO_SURFACE, null, null);
             RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .execute((resources, commands) -> {
                    });
            pipeline.addFinalPass(graph);

            assertFalse(graph.hasPass(PostProcessTargets.SSR_PASS));
            assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                            ToneMappingMode.ACES),
                    graph.description().executionOrder(),
                    "a disabled SSR request must keep the exact baseline colour chain");
            assertEquals(PostProcessTargets.PRESENT_PASS, pipeline.finalPassName());
        }
    }

    private static void assertEnabledChain() {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
        PostProcessSettings effects = PostProcessSettings.defaults()
                .withSsr(SsrSettings.defaults());
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(settings, effects,
                SIZE, SIZE, null, SSR_SURFACE, null, NO_ENVIRONMENT);
             RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .execute((resources, commands) -> {
                    });
            pipeline.addFinalPass(graph);
            pipeline.validateSurfaceInputs();

            List<String> order = graph.description().executionOrder();
            assertEquals(List.of(PostProcessTargets.GEOMETRY_PASS,
                            PostProcessTargets.SSR_PASS,
                            PostProcessTargets.TONE_MAPPING_PASS,
                            PostProcessTargets.PRESENT_PASS),
                    order,
                    "SSR must run on the linear HDR colour before tone mapping");
            assertTrue(graph.hasPass(PostProcessTargets.SSR_PASS));
            assertTrue(graph.description().passes().stream()
                            .filter(pass -> pass.name().equals(PostProcessTargets.SSR_PASS))
                            .findFirst().orElseThrow()
                            .directDependencies().contains(PostProcessTargets.GEOMETRY_PASS),
                    "the SSR pass must depend on its surface producer");
            assertEquals(PostProcessTargets.SSR_COLOR, pipeline.linearHdrTextureName(),
                    "the HDR diagnostic capture must see the composited reflection");
        }
    }

    private static final SurfaceInputs NO_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return null;
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                           Input input) {
            throw new UnsupportedOperationException();
        }
    };

    private static final SurfaceInputs SSR_SURFACE = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return switch (channel) {
                case DEPTH -> new Input(channel, PostProcessTargets.SCENE_DEPTH,
                        PostProcessTargets.GEOMETRY_PASS);
                case NORMAL -> new Input(channel, PostProcessTargets.SCENE_NORMAL,
                        PostProcessTargets.GEOMETRY_PASS);
                case ROUGHNESS -> new Input(channel, PostProcessTargets.SCENE_ROUGHNESS,
                        PostProcessTargets.GEOMETRY_PASS);
                default -> null;
            };
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                           Input input) {
            throw new UnsupportedOperationException();
        }
    };

    private static final SsrEnvironmentSource NO_ENVIRONMENT = new SsrEnvironmentSource() {
        @Override
        public float bindPrefilteredSpecular(CommandBuffer cmd, int unit) {
            return -1.0f;
        }

        @Override
        public float rotationRadians() {
            return 0.0f;
        }

        @Override
        public float intensity() {
            return 1.0f;
        }
    };

    /** Analytic depth/normal/roughness/colour fixture plus the mirror oracle. */
    private record Fixture(float[] colour, float[] depth, float[] normal,
                           byte[] roughness, float[] worldPosition, float[] worldNormal,
                           List<Integer> coreHits, List<Integer> roughOrBackground,
                           int analyticHits, boolean[] nearHit) {
        Fixture withUniformRoughness(float value) {
            byte[] adjusted = new byte[roughness.length];
            java.util.Arrays.fill(adjusted, (byte) Math.round(value * 255.0f));
            return new Fixture(colour, depth, normal, adjusted, worldPosition, worldNormal,
                    coreHits, roughOrBackground, analyticHits, nearHit);
        }

        boolean nearAnyAnalyticHit(int index, int radius) {
            return nearHit[index];
        }
    }

    private static Fixture fixture() {
        float[] colour = new float[SIZE * SIZE * 4];
        float[] depth = new float[SIZE * SIZE];
        float[] normal = new float[SIZE * SIZE * 4];
        byte[] roughness = new byte[SIZE * SIZE];
        float[] worldPosition = new float[SIZE * SIZE * 3];
        float[] worldNormal = new float[SIZE * SIZE * 3];
        boolean[] floorPixel = new boolean[SIZE * SIZE];
        java.util.Arrays.fill(depth, 1.0f);

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int index = y * SIZE + x;
                int offset = index * 4;
                float ndcX = (x + 0.5f) / SIZE * 2.0f - 1.0f;
                float ndcY = (y + 0.5f) / SIZE * 2.0f - 1.0f;
                Vector3f direction = viewRay(ndcX, ndcY);

                float floorT = direction.y < 0.0f ? -EYE.y / direction.y : Float.MAX_VALUE;
                float wallT = direction.z < 0.0f ? (WALL_Z - EYE.z) / direction.z
                        : Float.MAX_VALUE;
                boolean floorHit = floorT > 0.0f && floorT < Float.MAX_VALUE;
                boolean wallCandidate = false;
                if (wallT > 0.0f && wallT < Float.MAX_VALUE) {
                    Vector3f hit = new Vector3f(EYE).fma(wallT, direction);
                    wallCandidate = Math.abs(hit.x) <= WALL_HALF_WIDTH
                            && hit.y >= 0.0f && hit.y <= WALL_HEIGHT;
                }

                Vector3f position;
                Vector3f surfaceNormal;
                float surfaceRoughness;
                Vector3f surfaceColour;
                boolean skyPixel = false;
                if (wallCandidate && (!floorHit || wallT < floorT)) {
                    position = new Vector3f(EYE).fma(wallT, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = WALL_ROUGHNESS;
                    surfaceColour = WALL_COLOR;
                } else if (floorHit) {
                    position = new Vector3f(EYE).fma(floorT, direction);
                    surfaceNormal = new Vector3f(0.0f, 1.0f, 0.0f);
                    surfaceRoughness = FLOOR_ROUGHNESS;
                    surfaceColour = FLOOR_COLOR;
                    floorPixel[index] = true;
                } else {
                    position = new Vector3f(EYE).fma(1.0f, direction);
                    surfaceNormal = new Vector3f(0.0f, 0.0f, 1.0f);
                    surfaceRoughness = SKY_ROUGHNESS;
                    surfaceColour = SKY_COLOR;
                    skyPixel = true;
                }

                worldPosition[index * 3] = position.x;
                worldPosition[index * 3 + 1] = position.y;
                worldPosition[index * 3 + 2] = position.z;
                worldNormal[index * 3] = surfaceNormal.x;
                worldNormal[index * 3 + 1] = surfaceNormal.y;
                worldNormal[index * 3 + 2] = surfaceNormal.z;

                // Every rendered surface keeps its device depth, including the
                // rough wall: roughness is an SSR eligibility property, not a
                // coverage property.
                if (!skyPixel) {
                    Vector4f viewPosition = new Vector4f(position, 1.0f).mul(VIEW);
                    Vector4f clip = new Vector4f(viewPosition).mul(PROJECTION);
                    depth[index] = clip.z / clip.w * 0.5f + 0.5f;
                }
                float[] oct = octEncode(surfaceNormal);
                normal[offset] = oct[0];
                normal[offset + 1] = oct[1];
                normal[offset + 2] = 0.0f;
                normal[offset + 3] = 1.0f;
                roughness[index] = (byte) Math.round(surfaceRoughness * 255.0f);
                colour[offset] = surfaceColour.x;
                colour[offset + 1] = surfaceColour.y;
                colour[offset + 2] = surfaceColour.z;
                colour[offset + 3] = 1.0f;
            }
        }

        List<Integer> coreHits = new java.util.ArrayList<>();
        java.util.List<Integer> roughOrBackground = new java.util.ArrayList<>();
        boolean[] analytic = new boolean[SIZE * SIZE];
        for (int index = 0; index < SIZE * SIZE; index++) {
            float surfaceRoughness = Byte.toUnsignedInt(roughness[index]) / 255.0f;
            if (depth[index] >= 1.0f || surfaceRoughness > 0.4f) {
                roughOrBackground.add(index);
                continue;
            }
            if (!floorPixel[index]) continue;
            Vector3f position = new Vector3f(worldPosition[index * 3],
                    worldPosition[index * 3 + 1], worldPosition[index * 3 + 2]);
            Vector3f receiverNormal = new Vector3f(worldNormal[index * 3],
                    worldNormal[index * 3 + 1], worldNormal[index * 3 + 2]);
            Vector3f toCamera = new Vector3f(EYE).sub(position).normalize();
            float nDotV = receiverNormal.dot(toCamera);
            if (nDotV <= 0.05f) continue;
            Vector3f reflected = new Vector3f(-toCamera.x, -toCamera.y, -toCamera.z)
                    .fma(2.0f * nDotV, receiverNormal);
            if (reflected.z >= 0.0f) continue;
            float t = (WALL_Z - position.z) / reflected.z;
            if (!(t > 0.0f) || t > SETTINGS.maxDistanceMetres()) continue;
            Vector3f hit = new Vector3f(position).fma(t, reflected);
            if (Math.abs(hit.x) > WALL_HALF_WIDTH || hit.y < 0.0f || hit.y > WALL_HEIGHT) {
                continue;
            }
            Vector4f clip = new Vector4f(hit, 1.0f).mul(VIEW_PROJECTION);
            if (clip.w <= 0.0f) continue;
            float u = clip.x / clip.w * 0.5f + 0.5f;
            float v = clip.y / clip.w * 0.5f + 0.5f;
            if (u < 0.0f || u > 1.0f || v < 0.0f || v > 1.0f) continue;
            analytic[index] = true;
            if (!isCoreReflection(index, hit, u, v, nDotV, t)) continue;
            coreHits.add(index);
        }

        boolean[] nearHit = new boolean[SIZE * SIZE];
        int analyticHits = 0;
        for (int index = 0; index < SIZE * SIZE; index++) {
            if (analytic[index]) analyticHits++;
        }
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (!analytic[y * SIZE + x]) continue;
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dx = -4; dx <= 4; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || nx >= SIZE || ny < 0 || ny >= SIZE) continue;
                        nearHit[ny * SIZE + nx] = true;
                    }
                }
            }
        }
        return new Fixture(colour, depth, normal, roughness, worldPosition, worldNormal,
                List.copyOf(coreHits), List.copyOf(roughOrBackground), analyticHits, nearHit);
    }

    /**
     * A conservative reflected region: the mirrored hit is at least 0.35 world
     * units inside the wall silhouette, at least 5 px inside the raster, the
     * view angle is not grazing and the ray uses at most 70% of its budget.
     * These margins keep the assertion independent of march discretisation.
     */
    private static boolean isCoreReflection(int index, Vector3f hit, float u, float v,
                                            float nDotV, float t) {
        if (nDotV < 0.2f) return false;
        if (t > 0.7f * SETTINGS.maxDistanceMetres()) return false;
        if (Math.abs(hit.x) > WALL_HALF_WIDTH - 0.35f
                || hit.y < 0.35f || hit.y > WALL_HEIGHT - 0.35f) return false;
        int hitX = (int) (u * SIZE);
        int hitY = (int) (v * SIZE);
        if (hitX < 5 || hitX >= SIZE - 5 || hitY < 5 || hitY >= SIZE - 5) return false;
        return true;
    }

    private static Vector3f viewRay(float ndcX, float ndcY) {
        Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f).mul(INVERSE_PROJECTION);
        near.div(near.w);
        Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f).mul(INVERSE_PROJECTION);
        far.div(far.w);
        Vector3f nearWorld = new Vector3f(near.x, near.y, near.z).mulPosition(INVERSE_VIEW);
        Vector3f farWorld = new Vector3f(far.x, far.y, far.z).mulPosition(INVERSE_VIEW);
        return farWorld.sub(nearWorld).normalize();
    }

    /** Mirrors the octahedral encoding written by scene-surface.frag. */
    private static float[] octEncode(Vector3f normal) {
        Vector3f n = new Vector3f(normal).normalize();
        float denominator = Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z) + 1.0e-8f;
        float x = n.x / denominator;
        float y = n.y / denominator;
        if (n.z < 0.0f) {
            float signX = n.x >= 0.0f ? 1.0f : -1.0f;
            float signY = n.y >= 0.0f ? 1.0f : -1.0f;
            float originalX = x;
            x = (1.0f - Math.abs(y)) * signX;
            y = (1.0f - Math.abs(originalX)) * signY;
        }
        return new float[]{x, y};
    }
}
