package com.kaleblangley.haikalat.subsystems.postprocess.smaa;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glDeleteSamplers;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;

/**
 * Real-GL contract for the SMAA 1x production passes.
 *
 * <p>Case 1 runs the actual edge-detection, blending-weight and
 * neighbourhood-blending shaders on a synthetic thin diagonal line and a hard
 * diagonal step.  The GPU edge mask is compared byte-for-byte with
 * {@link SmaaEdgeReference}; the blended image is compared with the non-AA
 * source (which is also the fixture, not the pass output), must contain
 * intermediate values where the baseline is binary, must reduce full 255
 * steps, and must leave pixels outside a bounded neighbourhood untouched.
 * Case 2 checks the exact disabled graph and restoring it after an SMAA
 * generation.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SmaaPassGlTest {
    private static final int SIZE = 96;
    private static final int BOUND = 24;
    private static final SurfaceInputs NO_SURFACE_INPUTS = new SurfaceInputs() {
        @Override
        public Input single(Channel channel) {
            return null;
        }

        @Override
        public void validateDependency(String consumerPass, Input input) {
        }

        @Override
        public int texture(PassResources resources, Input input) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void smaaEdgeDetectionMatchesTheCpuReferenceAndBlendingReducesDiagonalAliasing() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SMAA Pass GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();

            try (SmaaPass pass = new SmaaPass();
                 Framebuffer edges = Framebuffer.colorOnly(SIZE, SIZE);
                 Framebuffer blend = Framebuffer.colorOnly(SIZE, SIZE);
                 Framebuffer output = Framebuffer.colorOnly(SIZE, SIZE)) {
                assertDiagonalFixture(pass, device, edges, blend, output, thinDiagonal(), true);
                assertDiagonalFixture(pass, device, edges, blend, output, hardDiagonal(), false);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void disabledGraphHasNoSmaaPassesAndRestoresTheExactBaseline() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("SMAA Disabled Graph GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            assertDisabledBaseline();
            assertSmaaGraphOrder();
            assertDisabledBaseline();
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static void assertDiagonalFixture(SmaaPass pass, GlRenderDevice device,
                                              Framebuffer edges, Framebuffer blend,
                                              Framebuffer output, byte[] source, boolean thinLine) {
        try (Texture2D sourceTexture = Texture2D.fromRgba8(
                SIZE, SIZE, source, TextureColorSpace.LINEAR)) {
            sourceTexture.setWrap(GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE);

            clear(edges);
            CommandBuffer commands = device.createCommandBuffer();
            // A preceding PBR/glTF material may leave a nearest/repeat sampler
            // on unit 0.  SMAA must clear it and keep its linear/clamp contract.
            int hostileSampler = glGenSamplers();
            glSamplerParameteri(hostileSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glSamplerParameteri(hostileSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(hostileSampler, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glSamplerParameteri(hostileSampler, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glBindSampler(0, hostileSampler);
            pass.recordEdgeDetection(commands, sourceTexture.id(), SIZE, SIZE);
            device.execute(commands);
            glBindSampler(0, 0);
            glDeleteSamplers(hostileSampler);
            byte[] edgeMask = read(edges);

            byte[] expectedEdges = SmaaEdgeReference.detect(source, SIZE, SIZE);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int offset = (y * SIZE + x) * 4;
                    assertEquals(Byte.toUnsignedInt(expectedEdges[offset]),
                            Byte.toUnsignedInt(edgeMask[offset]),
                            "GPU edge R differs from the CPU reference at (" + x + "," + y + ")");
                    assertEquals(Byte.toUnsignedInt(expectedEdges[offset + 1]),
                            Byte.toUnsignedInt(edgeMask[offset + 1]),
                            "GPU edge G differs from the CPU reference at (" + x + "," + y + ")");
                }
            }

            clear(blend);
            commands = device.createCommandBuffer();
            pass.recordBlendingWeights(commands, edges.colorAttachment(), SIZE, SIZE);
            device.execute(commands);
            byte[] weights = read(blend);

            clear(output);
            commands = device.createCommandBuffer();
            pass.recordNeighborhoodBlending(commands, sourceTexture.id(),
                    blend.colorAttachment(), SIZE, SIZE);
            device.execute(commands);
            byte[] blended = read(output);

            if (thinLine) {
                assertThinDiagonalResult(source, blended, weights);
            } else {
                assertHardDiagonalResult(source, blended, weights);
            }
            assertBoundedNeighbourhood(source, blended, thinLine);
        }
    }

    private static void assertThinDiagonalResult(byte[] source, byte[] blended, byte[] weights) {
        int intermediate = 0;
        int spread = 0;
        int peak = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                int baseline = Byte.toUnsignedInt(source[offset]);
                int value = Byte.toUnsignedInt(blended[offset]);
                peak = Math.max(peak, value);
                if (Math.abs(x - y) <= 2) {
                    if (value > 4 && value < 251) {
                        intermediate++;
                    }
                    if (baseline == 0 && value > 4) {
                        spread++;
                    }
                }
            }
        }
        assertTrue(intermediate >= 8,
                "SMAA must introduce intermediate coverage on a thin diagonal line: " + intermediate);
        assertTrue(spread >= 1,
                "SMAA must spread energy to a neighbour the binary baseline left black");
        assertTrue(peak >= 96, "the thin line must not be erased by blending: peak=" + peak);
        assertTrue(intermediateStepCount(blended) < intermediateStepCount(source),
                "SMAA must reduce the number of full 255 steps on a thin diagonal");
        assertTrue(nonZeroInBand(weights, true, true) > 0,
                "blending-weight pass must emit weights on the diagonal");
        assertEquals(0, nonZeroInBand(weights, true, false),
                "flat regions more than " + BOUND + " px from the line must have zero weights");
    }

    private static void assertHardDiagonalResult(byte[] source, byte[] blended, byte[] weights) {
        int intermediate = 0;
        int baselineFullSteps = 0;
        int blendedFullSteps = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                int value = Byte.toUnsignedInt(blended[offset]);
                if (Math.abs((x + y) - (SIZE - 1)) <= 2
                        && value > 4 && value < 251) {
                    intermediate++;
                }
                if (x > 0) {
                    int left = (y * SIZE + x - 1) * 4;
                    int baselineStep = Math.abs(Byte.toUnsignedInt(source[offset])
                            - Byte.toUnsignedInt(source[left]));
                    int blendedStep = Math.abs(value - Byte.toUnsignedInt(blended[left]));
                    if (baselineStep == 255) baselineFullSteps++;
                    if (blendedStep == 255) blendedFullSteps++;
                }
            }
        }
        assertTrue(intermediate >= 16,
                "SMAA must blend a hard diagonal boundary: " + intermediate);
        assertTrue(blendedFullSteps < baselineFullSteps,
                "SMAA must remove some full 255 staircase steps: "
                        + blendedFullSteps + " vs " + baselineFullSteps);
        assertTrue(nonZeroInBand(weights, false, true) > 0,
                "blending-weight pass must emit weights along the hard boundary");
    }

    private static int intermediateStepCount(byte[] image) {
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 1; x < SIZE; x++) {
                int current = (y * SIZE + x) * 4;
                int previous = (y * SIZE + x - 1) * 4;
                if (Math.abs(Byte.toUnsignedInt(image[current])
                        - Byte.toUnsignedInt(image[previous])) == 255) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertBoundedNeighbourhood(byte[] source, byte[] blended, boolean thinLine) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int distance = thinLine ? Math.abs(x - y) : Math.abs((x + y) - (SIZE - 1));
                if (distance <= BOUND) {
                    continue;
                }
                int offset = (y * SIZE + x) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    assertEquals(Byte.toUnsignedInt(source[offset + channel]),
                            Byte.toUnsignedInt(blended[offset + channel]),
                            "SMAA changed an out-of-neighbourhood pixel at (" + x + "," + y + ")");
                }
            }
        }
    }

    private static int nonZeroInBand(byte[] rgba, boolean thinLine, boolean insideBand) {
        int count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int distance = thinLine ? Math.abs(x - y)
                        : Math.abs((x + y) - (SIZE - 1));
                if ((distance <= BOUND) != insideBand) {
                    continue;
                }
                int offset = (y * SIZE + x) * 4;
                if (rgba[offset] != 0 || rgba[offset + 1] != 0
                        || rgba[offset + 2] != 0 || rgba[offset + 3] != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertDisabledBaseline() {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .vsync(false)
                .build();
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(settings,
                PostProcessSettings.defaults(), SIZE, SIZE, null, NO_SURFACE_INPUTS, null);
             RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.SRGB8_ALPHA8)
                    .noClear()
                    .execute((resources, commands) -> {
                    });
            pipeline.addFinalPass(graph);

            assertFalse(graph.hasPass(PostProcessTargets.SMAA_EDGE_DETECTION_PASS));
            assertFalse(graph.hasPass(PostProcessTargets.SMAA_BLENDING_WEIGHT_PASS));
            assertFalse(graph.hasPass(PostProcessTargets.SMAA_PASS));
            assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE),
                    graph.description().executionOrder(),
                    "disabled anti-aliasing must keep the exact baseline colour chain");
            assertEquals(PostProcessTargets.PRESENT_PASS, pipeline.finalPassName());
        }
    }

    private static void assertSmaaGraphOrder() {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.SMAA)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(settings,
                PostProcessSettings.defaults(), SIZE, SIZE, null, NO_SURFACE_INPUTS, null);
             RenderGraph graph = new RenderGraph(SIZE, SIZE)) {
            graph.addPass(PostProcessTargets.GEOMETRY_PASS)
                    .createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .execute((resources, commands) -> {
                    });
            pipeline.addFinalPass(graph);

            List<String> order = graph.description().executionOrder();
            assertEquals(List.of(
                    PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS,
                    PostProcessTargets.SMAA_EDGE_DETECTION_PASS,
                    PostProcessTargets.SMAA_BLENDING_WEIGHT_PASS,
                    PostProcessTargets.SMAA_PASS), order);
            assertTrue(graph.passWritesToBackbuffer(PostProcessTargets.SMAA_PASS));
            assertEquals(PostProcessTargets.SMAA_PASS, pipeline.finalPassName());
        }
    }

    private static void clear(Framebuffer target) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static byte[] read(Framebuffer target) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        byte[] result = new byte[SIZE * SIZE * 4];
        pixels.get(result);
        return result;
    }

    private static byte[] thinDiagonal() {
        byte[] pixels = new byte[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pixels[(y * SIZE + x) * 4 + 3] = (byte) 255;
            }
        }
        for (int y = 8; y < SIZE - 8; y++) {
            int x = y;
            int offset = (y * SIZE + x) * 4;
            pixels[offset] = (byte) 255;
            pixels[offset + 1] = (byte) 255;
            pixels[offset + 2] = (byte) 255;
        }
        return pixels;
    }

    private static byte[] hardDiagonal() {
        byte[] pixels = new byte[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int value = x + y < SIZE ? 255 : 0;
                int offset = (y * SIZE + x) * 4;
                pixels[offset] = (byte) value;
                pixels[offset + 1] = (byte) value;
                pixels[offset + 2] = (byte) value;
                pixels[offset + 3] = (byte) 255;
            }
        }
        return pixels;
    }
}
