package com.kaleblangley.haikalat.subsystems.postprocess.vrs;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingCapability;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingRate;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingState;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.sss.SubsurfaceScatteringPass;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;

/**
 * Real-GL acceptance for roadmap feature #42, Variable Rate Shading.
 *
 * <p>The class is guarded like every other {@code *GlTest}: the worker does not
 * run it, the Orchestrator executes it serially.  Each case first queries the
 * exact extension, entry points and limits; cases that need the implemented
 * {@code GL_NV_shading_rate_image} path abort with an explicit
 * {@code NOT_EVALUATED} assumption when that path is genuinely unavailable, so
 * a driver without VRS can never be mistaken for a passing implementation.</p>
 *
 * <p>Cases:</p>
 * <ol>
 *   <li>capability reporting (runs first; aborts with {@code NOT_EVALUATED} when
 *       the implemented path is absent);</li>
 *   <li>coarse tiles become blockwise, the fine centre stays full rate, and the
 *       previous image/palette are restored;</li>
 *   <li>thin-line, derivative and transparent caveats visible as negative
 *       controls;</li>
 *   <li>the real screen-space subsurface full-screen consumer is shaded at the
 *       tile rate;</li>
 *   <li>pipeline active/fallback/rejection and disabled no-op wiring.</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VariableRateShadingGlTest {
    private static final int SIZE = 128;
    private static final float THIN_LINE_X = 8.5f;

    private static final String FULLSCREEN_VERTEX = """
            #version 460 core
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String VRS_PROBE_FRAGMENT = """
            #version 460 core
            layout(location = 0) out vec4 FragColor;
            uniform vec2 uViewportSize;
            uniform float uThinLineX;
            uniform float uAlphaGradient;

            void main() {
                float x = gl_FragCoord.x;
                float y = gl_FragCoord.y;
                // Alternating per-pixel values: a coarse fragment invocation may
                // only return one of them for the whole footprint.
                float highFrequency = mod(floor(x), 2.0) * 0.5 + mod(floor(y), 2.0) * 0.25;
                // A one-pixel feature can be widened or lost when the invocation
                // centre is not the feature column.
                float line = 1.0 - step(0.5, abs(x - uThinLineX));
                // Derivative-dependent output: coarse shading gives one value per
                // footprint, so derivative-driven detail loses spatial resolution.
                float derivative = clamp(fwidth(sin(x * 0.35)) * 4.0, 0.0, 1.0);
                float alpha = uAlphaGradient > 0.5 ? fract(x / 7.0) : 1.0;
                FragColor = vec4(highFrequency, derivative, line, alpha);
            }
            """;

    private static final SubsurfaceScatteringSettings SSS =
            SubsurfaceScatteringSettings.builder()
                    .profile(SubsurfaceScatteringSettings.Profile.SKIN)
                    .diffusionRadiusMetres(0.12f)
                    .maxDistanceMetres(20.0f)
                    .build();
    private static final Matrix4f PROJECTION = new Matrix4f().perspective(
            (float) Math.toRadians(55.0), 1.0f, 0.1f, 100.0f);
    private static final Matrix4f INVERSE_PROJECTION = new Matrix4f(PROJECTION).invert();

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

    private static GlfwWindow window(String title) {
        GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title(title).visible(false).build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        return window;
    }

    @Test
    void capabilityIsQueriedAndUnsupportedIsReportedAsNotEvaluated() {
        try (GlfwWindow window = window("VRS Capability")) {
            VariableRateShadingCapability capability =
                    VariableRateShadingCapability.inspectCurrent();
            System.out.println("VRS capability: " + capability.summary());
            System.out.println("VRS context: " + GlDebug.contextInfo());
            assertTrue(capability.contextAvailable(), "a real context must be inspectable");
            if (!capability.nvImagePathAvailable()) {
                assertFalse(capability.unsupportedReason().isBlank(),
                        "an unsupported state must name the missing extension or symbol");
                assertNotEquals(VariableRateShadingCapability.Status.SUPPORTED_NV_IMAGE,
                        capability.status());
                // Abort instead of passing: an unsupported driver is not VRS evidence.
                org.junit.jupiter.api.Assumptions.abort(
                        "NOT_EVALUATED: implemented NV shading-rate path unavailable: "
                                + capability.summary());
            }
            assertEquals(VariableRateShadingCapability.Status.SUPPORTED_NV_IMAGE,
                    capability.status());
            assertEquals("", capability.unsupportedReason());
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void coarseTilesAreBlockwiseAndTheFineCentreStaysFullRate() {
        try (GlfwWindow window = window("VRS Blocks");
             Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA8)) {
            VariableRateShadingCapability capability =
                    VariableRateShadingCapability.inspectCurrent();
            assumeTrue(capability.nvImagePathAvailable(), () ->
                    "NOT_EVALUATED: GL_NV_shading_rate_image real path unavailable: "
                            + capability.summary());
            VariableRateShadingSettings settings = VariableRateShadingSettings.builder()
                    .coarseRate(VariableRateShadingRate.TWO_BY_TWO).build();
            try (VariableRateShadingPass shading = new VariableRateShadingPass(settings,
                    capability, SIZE, SIZE)) {
                byte[] baseline = renderProbe(target, false, null, false);
                target.bind();
                int previousBinding = VariableRateShadingState.boundImage();
                int[] previousPalette = VariableRateShadingState.palette(
                        0, capability.paletteSize());

                byte[] shaded = renderProbe(target, true, shading, false);
                target.bind();
                int restoredBinding = VariableRateShadingState.boundImage();
                int[] restoredPalette = VariableRateShadingState.palette(
                        0, capability.paletteSize());

                assertEquals(previousBinding, restoredBinding,
                        "the previous shading-rate image must be restored");
                assertArrayEquals(previousPalette, restoredPalette,
                        "the previous shading-rate palette must be restored");
                assertFalse(Arrays.equals(baseline, shaded),
                        "coarse shading must change at least one pixel");
                assertCoarseBlocksAreUniform(shaded, shading.plan());
                assertFineRegionHasVariation(shaded, shading.plan());
                assertEquals(GL_NO_ERROR, glGetError());

                byte[] restored = renderProbe(target, false, null, false);
                assertArrayEquals(baseline, restored,
                        "the next full-rate draw must be byte-identical after restore");
            }
        }
    }

    @Test
    void thinLineDerivativeAndTransparencyCaveatsAreVisible() {
        try (GlfwWindow window = window("VRS Caveats");
             Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA8)) {
            VariableRateShadingCapability capability =
                    VariableRateShadingCapability.inspectCurrent();
            assumeTrue(capability.nvImagePathAvailable(), () ->
                    "NOT_EVALUATED: GL_NV_shading_rate_image real path unavailable: "
                            + capability.summary());
            VariableRateShadingSettings settings = VariableRateShadingSettings.builder()
                    .coarseRate(VariableRateShadingRate.TWO_BY_TWO).build();
            try (VariableRateShadingPass shading = new VariableRateShadingPass(settings,
                    capability, SIZE, SIZE)) {
                byte[] fine = renderProbe(target, false, null, false);
                byte[] coarse = renderProbe(target, true, shading, false);

                int fineLinePixels = countBlueLine(fine);
                int coarseLinePixels = countBlueLine(coarse);
                assertEquals(SIZE, fineLinePixels,
                        "the fine render must preserve exactly one 1-pixel line per row");
                assertNotEquals(fineLinePixels, coarseLinePixels,
                        "coarse shading must widen or lose the one-pixel line");

                int derivativeDifferences = 0;
                for (int index = 0; index < SIZE * SIZE; index++) {
                    int x = index % SIZE;
                    int y = index / SIZE;
                    if (shading.plan().finePixel(x, y)) continue;
                    if (channel(fine, pixelIndex(x, y), 1)
                            != channel(coarse, pixelIndex(x, y), 1)) {
                        derivativeDifferences++;
                    }
                }
                assertTrue(derivativeDifferences > 0,
                        "coarse shading must visibly change derivative-driven detail");

                byte[] fineBlend = renderProbe(target, false, null, true);
                byte[] coarseBlend = renderProbe(target, true, shading, true);
                assertFalse(Arrays.equals(fineBlend, coarseBlend),
                        "alpha blending must not hide the coarse shading difference");
                assertCoarseBlocksAreUniform(coarseBlend, shading.plan());
                assertFineRegionHasVariation(coarseBlend, shading.plan());
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void realSubsurfaceFullScreenConsumerIsShadedAtTheTileRate() {
        try (GlfwWindow window = window("VRS Subsurface Consumer");
             Framebuffer target = Framebuffer.mrt(SIZE, SIZE, GL_RGBA32F)) {
            VariableRateShadingCapability capability =
                    VariableRateShadingCapability.inspectCurrent();
            assumeTrue(capability.nvImagePathAvailable(), () ->
                    "NOT_EVALUATED: GL_NV_shading_rate_image real path unavailable: "
                            + capability.summary());
            VariableRateShadingSettings settings = VariableRateShadingSettings.builder()
                    .coarseRate(VariableRateShadingRate.TWO_BY_TWO).build();
            try (VariableRateShadingPass shading = new VariableRateShadingPass(settings,
                    capability, SIZE, SIZE)) {
                float[] fine = renderSubsurface(target, false, null);
                float[] coarse = renderSubsurface(target, true, shading);
                assertFalse(Arrays.equals(fine, coarse),
                        "the real SSS consumer must produce different pixels under VRS");
                for (float value : coarse) {
                    assertTrue(Float.isFinite(value), "SSS output must stay finite");
                }
                assertCoarseBlocksAreFloatUniform(coarse, shading.plan());
                assertFineRegionHasFloatVariation(coarse, shading.plan());
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void pipelineReportsActiveOrFallbackAndDisabledIsANoOp() {
        try (GlfwWindow window = window("VRS Pipeline")) {
            VariableRateShadingCapability capability =
                    VariableRateShadingCapability.inspectCurrent();
            RenderSettings hdr = RenderSettings.builder()
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .vsync(false).build();
            VariableRateShadingSettings request = VariableRateShadingSettings.builder().build();
            PostProcessSettings preset = PostProcessSettings.defaults()
                    .withSubsurfaceScattering(SSS)
                    .withVariableRateShading(request);
            int baseline = GlDebug.resources().liveResources().size();

            try (PostProcessPipeline pipeline = PostProcessPipeline.create(hdr, preset,
                    SIZE, SIZE, null, NO_SURFACE, null, null)) {
                assertEquals(capability.nvImagePathAvailable(),
                        pipeline.variableRateShadingActive(),
                        "the active flag must match the queried capability");
                if (capability.nvImagePathAvailable()) {
                    assertTrue(pipeline.variableRateShadingStatus().startsWith("active:"));
                    // Candidate-first rate-image resize must keep the request active and
                    // publish the new extent without requiring a graph rebuild.
                    pipeline.resize(SIZE + 32, SIZE + 32);
                    assertTrue(pipeline.variableRateShadingActive());
                } else {
                    assertTrue(pipeline.variableRateShadingStatus().startsWith("fallback:"));
                    assertTrue(pipeline.variableRateShadingStatus().contains("GL_"));
                }
                assertEquals(request, pipeline.variableRateShadingRequest());
            }
            assertEquals(baseline, GlDebug.resources().liveResources().size(),
                    "pipeline close must release the shading-rate image and consumer");

            try (PostProcessPipeline disabled = PostProcessPipeline.create(hdr,
                    PostProcessSettings.defaults().withSubsurfaceScattering(SSS),
                    SIZE, SIZE, null, NO_SURFACE, null, null)) {
                assertFalse(disabled.variableRateShadingActive());
                assertEquals("disabled", disabled.variableRateShadingStatus());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> PostProcessPipeline.create(hdr,
                            PostProcessSettings.defaults().withVariableRateShading(request),
                            SIZE, SIZE, null, NO_SURFACE, null, null),
                    "an active request without the declared consumer must be rejected");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static byte[] renderProbe(Framebuffer target, boolean applyShading,
                                      VariableRateShadingPass shading, boolean blend) {
        try (ShaderProgram shader = ShaderProgram.fromSources(FULLSCREEN_VERTEX,
                VRS_PROBE_FRAGMENT);
             VertexArray vao = new VertexArray()) {
            GlRenderDevice device = new GlRenderDevice();
            var cmd = device.createCommandBuffer();
            cmd.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                    .enableDepthTest(false)
                    .enableCullFace(false)
                    .enableBlend(false)
                    .clearColor(0.15f, 0.05f, 0.05f, 1.0f)
                    .clear(true, false);
            if (blend) {
                cmd.enableBlend(true).blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            }
            boolean began = false;
            if (applyShading) {
                shading.recordBegin(cmd);
                began = true;
            }
            cmd.bindShader(shader)
                    .setUniformVec2(shader, "uViewportSize", SIZE, SIZE)
                    .setUniformFloat(shader, "uThinLineX", THIN_LINE_X)
                    .setUniformFloat(shader, "uAlphaGradient", blend ? 1.0f : 0.0f)
                    .bindVertexArray(vao.id())
                    .drawArrays(GL_TRIANGLES, 0, 3);
            if (began) {
                shading.recordEnd(cmd);
            }
            device.execute(cmd);
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            byte[] result = new byte[SIZE * SIZE * 4];
            pixels.get(result);
            return result;
        }
    }

    private static float[] renderSubsurface(Framebuffer target, boolean applyShading,
                                            VariableRateShadingPass shading) {
        try (Texture2D color = floatTexture(4, colourFixture());
             Texture2D depth = floatTexture(1, depthFixture());
             Texture2D normal = floatTexture(4, normalFixture());
             Texture2D mask = floatTexture(4, maskFixture());
             SubsurfaceScatteringPass pass = new SubsurfaceScatteringPass(SSS)) {
            GlRenderDevice device = new GlRenderDevice();
            var cmd = device.createCommandBuffer();
            cmd.bindFramebuffer(target).viewport(0, 0, SIZE, SIZE)
                    .enableDepthTest(false)
                    .enableCullFace(false)
                    .enableBlend(false)
                    .clearColor(0.0f, 0.0f, 0.0f, 1.0f)
                    .clear(true, false);
            boolean began = false;
            if (applyShading) {
                shading.recordBegin(cmd);
                began = true;
            }
            pass.recordIntoCurrentTarget(cmd, color.id(), depth.id(), normal.id(), mask.id(),
                    INVERSE_PROJECTION, new Matrix4f(), SIZE, SIZE);
            if (began) {
                shading.recordEnd(cmd);
            }
            device.execute(cmd);
            target.bind();
            glViewport(0, 0, SIZE, SIZE);
            FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
            float[] result = new float[SIZE * SIZE * 4];
            pixels.get(result);
            return result;
        }
    }

    private static Texture2D floatTexture(int channels, float[] data) {
        int format = channels == 1 ? GL_R32F : GL_RGBA32F;
        Texture2D texture = Texture2D.createEmpty(SIZE, SIZE, format);
        ByteBuffer upload = BufferUtils.createByteBuffer(SIZE * SIZE * channels * Float.BYTES);
        upload.asFloatBuffer().put(data);
        texture.uploadRegion(0, 0, SIZE, SIZE, upload);
        return texture;
    }

    private static float[] colourFixture() {
        float[] data = new float[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int index = (y * SIZE + x) * 4;
                data[index] = (x % 8) / 7.0f;
                data[index + 1] = (y % 8) / 7.0f;
                data[index + 2] = 0.25f;
                data[index + 3] = 1.0f;
            }
        }
        return data;
    }

    private static float[] depthFixture() {
        // Device depth 0.5 maps to a negative view Z for the test projection,
        // so every pixel is a valid participating surface.
        float[] data = new float[SIZE * SIZE];
        Arrays.fill(data, 0.5f);
        return data;
    }

    private static float[] normalFixture() {
        float[] data = new float[SIZE * SIZE * 4];
        for (int index = 0; index < SIZE * SIZE; index++) {
            data[index * 4 + 3] = 1.0f;
        }
        return data;
    }

    private static float[] maskFixture() {
        float[] data = new float[SIZE * SIZE * 4];
        for (int index = 0; index < SIZE * SIZE; index++) {
            data[index * 4] = 1.0f;
            data[index * 4 + 1] = 0.02f;
            data[index * 4 + 2] = 0.05f;
            data[index * 4 + 3] = 1.0f;
        }
        return data;
    }

    private static void assertCoarseBlocksAreUniform(byte[] pixels,
                                                     VariableRateShadingPlan plan) {
        int blocks = 0;
        for (int y = 0; y + 1 < SIZE; y += 2) {
            for (int x = 0; x + 1 < SIZE; x += 2) {
                if (!allCoarse(plan, x, y)) continue;
                blocks++;
                for (int offset = 0; offset < 4; offset++) {
                    assertEquals(channel(pixels, pixelIndex(x, y), offset),
                            channel(pixels, pixelIndex(x + 1, y), offset),
                            "a 2x2 coarse block must be uniform at " + x + "," + y);
                    assertEquals(channel(pixels, pixelIndex(x, y), offset),
                            channel(pixels, pixelIndex(x, y + 1), offset),
                            "a 2x2 coarse block must be uniform at " + x + "," + y);
                    assertEquals(channel(pixels, pixelIndex(x, y), offset),
                            channel(pixels, pixelIndex(x + 1, y + 1), offset),
                            "a 2x2 coarse block must be uniform at " + x + "," + y);
                }
            }
        }
        assertTrue(blocks > 0, "the plan must expose at least one coarse block");
    }

    private static void assertFineRegionHasVariation(byte[] pixels,
                                                     VariableRateShadingPlan plan) {
        int blocks = 0;
        boolean varied = false;
        for (int y = 0; y + 1 < SIZE; y += 2) {
            for (int x = 0; x + 1 < SIZE; x += 2) {
                if (plan.finePixel(x, y) && plan.finePixel(x + 1, y)
                        && plan.finePixel(x, y + 1) && plan.finePixel(x + 1, y + 1)) {
                    blocks++;
                    for (int offset = 0; offset < 4 && !varied; offset++) {
                        if (channel(pixels, pixelIndex(x, y), offset)
                                != channel(pixels, pixelIndex(x + 1, y), offset)) {
                            varied = true;
                        }
                    }
                }
            }
        }
        assertTrue(blocks > 0, "the plan must expose at least one fine block");
        assertTrue(varied, "the fine region must keep per-pixel shading variation");
    }

    private static void assertCoarseBlocksAreFloatUniform(float[] pixels,
                                                          VariableRateShadingPlan plan) {
        int blocks = 0;
        for (int y = 0; y + 1 < SIZE; y += 2) {
            for (int x = 0; x + 1 < SIZE; x += 2) {
                if (!allCoarse(plan, x, y)) continue;
                blocks++;
                for (int offset = 0; offset < 4; offset++) {
                    assertEquals(pixels[pixelIndex(x, y) + offset],
                            pixels[pixelIndex(x + 1, y) + offset], 0.0f,
                            "a coarse SSS block must be bit-equal at " + x + "," + y);
                    assertEquals(pixels[pixelIndex(x, y) + offset],
                            pixels[pixelIndex(x, y + 1) + offset], 0.0f,
                            "a coarse SSS block must be bit-equal at " + x + "," + y);
                    assertEquals(pixels[pixelIndex(x, y) + offset],
                            pixels[pixelIndex(x + 1, y + 1) + offset], 0.0f,
                            "a coarse SSS block must be bit-equal at " + x + "," + y);
                }
            }
        }
        assertTrue(blocks > 0);
    }

    private static void assertFineRegionHasFloatVariation(float[] pixels,
                                                          VariableRateShadingPlan plan) {
        int blocks = 0;
        boolean varied = false;
        for (int y = 0; y + 1 < SIZE; y += 2) {
            for (int x = 0; x + 1 < SIZE; x += 2) {
                if (plan.finePixel(x, y) && plan.finePixel(x + 1, y)
                        && plan.finePixel(x, y + 1) && plan.finePixel(x + 1, y + 1)) {
                    blocks++;
                    for (int offset = 0; offset < 4 && !varied; offset++) {
                        if (Float.compare(pixels[pixelIndex(x, y) + offset],
                                pixels[pixelIndex(x + 1, y) + offset]) != 0) {
                            varied = true;
                        }
                    }
                }
            }
        }
        assertTrue(blocks > 0);
        assertTrue(varied, "the fine SSS region must keep per-pixel variation");
    }

    private static boolean allCoarse(VariableRateShadingPlan plan, int x, int y) {
        return !plan.finePixel(x, y) && !plan.finePixel(x + 1, y)
                && !plan.finePixel(x, y + 1) && !plan.finePixel(x + 1, y + 1);
    }

    private static int pixelIndex(int x, int y) {
        return (y * SIZE + x) * 4;
    }

    private static int channel(byte[] pixels, int pixelIndex, int channel) {
        return pixels[pixelIndex + channel] & 0xFF;
    }

    private static int countBlueLine(byte[] pixels) {
        int count = 0;
        for (int index = 0; index < SIZE * SIZE; index++) {
            if (channel(pixels, index * 4, 2) > 200) count++;
        }
        return count;
    }
}
