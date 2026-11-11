package com.kaleblangley.haikalat.subsystems.postprocess.motionblur;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.DEPTH_OF_FIELD_PASS;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.MOTION_BLUR_COLOR;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.MOTION_BLUR_PASS;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_COLOR;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.TONE_MAPPING_PASS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RG16F;

/**
 * Real-GL contract for the bounded screen-space motion-blur pass.
 *
 * <p>One case covers static zero blur, camera-cut validity identity, a moving
 * object's bounded directional smear, the maximum-radius clamp, jitter removal,
 * and the disabled/active graph contract.  References are the independently
 * read back source colour buffer, not the pass's own output.  The case is
 * guarded and is not executed by the JVM worker run.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class MotionBlurPassGlTest {
    private static final int SIZE = 64;
    private static final int BAR_START = 28;
    private static final int BAR_WIDTH = 8;
    private static final int BAR_END = BAR_START + BAR_WIDTH - 1;
    private static final float SHUTTER = 1.0f;

    @Test
    void motionBlurKeepsStaticFramesIdenticalSmearsInTheVelocityDirectionAndBoundsItsRadius() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Motion Blur GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();

            int velocityTexture = 0;
            int validityTexture = 0;
            try (Framebuffer source = Framebuffer.colorOnly(SIZE, SIZE);
                 Framebuffer target = Framebuffer.colorOnly(SIZE, SIZE);
                 MotionBlurPass pass = new MotionBlurPass(
                         MotionBlurSettings.of(SHUTTER, 8.0f))) {
                prepareBar(source);
                ByteBuffer sharp = readFramebuffer(source);
                assertEquals(255, red(sharp, BAR_START, SIZE / 2));
                assertEquals(0, red(sharp, BAR_START - 1, SIZE / 2));
                assertEquals(0, red(sharp, BAR_END + 1, SIZE / 2));

                // Static camera: the reconstruction must be byte-identical to the input.
                velocityTexture = velocityTexture(0.0f, SIZE);
                validityTexture = validityTexture(255);
                ByteBuffer staticFrame = render(source, target, pass,
                        velocityTexture, validityTexture, 0.0f, 0.0f);
                assertBuffersEqual(sharp, staticFrame, "zero velocity must be an exact identity");

                // Camera cut / first frame: validity 0 must also be an exact identity.
                velocityTexture = velocityTexture(-4.0f / SIZE, SIZE);
                validityTexture = validityTexture(0);
                ByteBuffer invalidVelocity = render(source, target, pass,
                        velocityTexture, validityTexture, 0.0f, 0.0f);
                assertBuffersEqual(sharp, invalidVelocity,
                        "camera cuts and velocity-less surfaces must not smear");

                // Uniform camera pan: previous position 4 px to the left, so the
                // gather reaches left and the bright bar smears to its right.
                validityTexture = validityTexture(255);
                ByteBuffer panned = render(source, target, pass,
                        velocityTexture, validityTexture, 0.0f, 0.0f);
                assertTrue(red(panned, BAR_END + 1, SIZE / 2) > 0,
                        "camera motion must smear the bright bar beyond its edge");
                assertTrue(red(panned, BAR_END + 3, SIZE / 2) > 0,
                        "the smear must reach in the velocity direction, not behind it");
                assertEquals(0, red(panned, BAR_START - 1, SIZE / 2),
                        "the opposite side of the bar must stay unmodified");
                assertTrue(red(panned, BAR_START, SIZE / 2) < red(sharp, BAR_START, SIZE / 2) - 100,
                        "the edge the gather points away from must soften");
                assertTrue(red(panned, BAR_END, SIZE / 2) >= 250,
                        "the opposite bar edge keeps its colour when the gather stays inside it");
                assertEquals(0, red(panned, BAR_END + 5, SIZE / 2),
                        "an 8 px radius with 4 px motion cannot reach 5 px past the edge");
                int smearExtension = lastModifiedColumn(panned, sharp, BAR_END + 1) - (BAR_END + 1);
                assertTrue(smearExtension >= 3 && smearExtension <= 4,
                        "the smear must cover the requested 4 px segment, got " + smearExtension);

                // Object-only velocity: the bar's own pixels blur, but a static
                // background does not invent an object trail (single-frame gather).
                velocityTexture = barVelocityTexture(-4.0f / SIZE, 0.0f);
                ByteBuffer objectOnly = render(source, target, pass,
                        velocityTexture, validityTexture, 0.0f, 0.0f);
                assertTrue(red(objectOnly, BAR_START, SIZE / 2) < red(sharp, BAR_START, SIZE / 2) - 40,
                        "the moving bar's leading edge must soften in the velocity direction");
                assertTrue(red(objectOnly, BAR_END, SIZE / 2) >= 250,
                        "the bar's trailing edge keeps its colour when the gather stays inside it");
                assertEquals(0, red(objectOnly, BAR_END + 2, SIZE / 2),
                        "static background pixels have no velocity and must not smear");
            } finally {
                glDeleteTextures(velocityTexture);
                glDeleteTextures(validityTexture);
            }

            assertMaxRadiusClamp();
            assertJitterIsRemoved();
            assertDisabledPipelineHasNoMotionBlurPass();
            assertActivePipelineWritesADistinctMotionBlurAttachment();
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static void assertMaxRadiusClamp() {
        int velocity = velocityTexture(-20.0f / SIZE, SIZE);
        int validity = validityTexture(255);
        try (Framebuffer source = Framebuffer.colorOnly(SIZE, SIZE);
             Framebuffer target = Framebuffer.colorOnly(SIZE, SIZE);
             MotionBlurPass small = new MotionBlurPass(MotionBlurSettings.of(SHUTTER, 2.0f));
             MotionBlurPass large = new MotionBlurPass(MotionBlurSettings.of(SHUTTER, 8.0f))) {
            prepareBar(source);
            ByteBuffer sharp = readFramebuffer(source);
            ByteBuffer smallBlur = render(source, target, small, velocity, validity, 0.0f, 0.0f);
            ByteBuffer largeBlur = render(source, target, large, velocity, validity, 0.0f, 0.0f);
            int smallExtension = lastModifiedColumn(smallBlur, sharp, BAR_END + 1) - BAR_END;
            int largeExtension = lastModifiedColumn(largeBlur, sharp, BAR_END + 1) - BAR_END;
            assertTrue(smallExtension <= 2, "2 px max radius must not exceed its bound");
            assertTrue(largeExtension <= 8, "8 px max radius must not exceed its bound");
            assertTrue(largeExtension > smallExtension,
                    "the larger configured radius must produce the larger footprint");
        } finally {
            glDeleteTextures(velocity);
            glDeleteTextures(validity);
        }
    }

    private static void assertJitterIsRemoved() {
        int velocity = velocityTexture(-4.0f / SIZE, SIZE);
        int validity = validityTexture(255);
        try (Framebuffer source = Framebuffer.colorOnly(SIZE, SIZE);
             Framebuffer target = Framebuffer.colorOnly(SIZE, SIZE);
             MotionBlurPass pass = new MotionBlurPass(MotionBlurSettings.of(SHUTTER, 8.0f))) {
            prepareBar(source);
            ByteBuffer sharp = readFramebuffer(source);
            ByteBuffer noJitter = render(source, target, pass, velocity, validity, 0.0f, 0.0f);
            ByteBuffer halfPixelJitter = render(source, target, pass, velocity, validity,
                    -0.5f / SIZE, 0.5f / SIZE);
            assertTrue(differingBytes(noJitter, halfPixelJitter) > 0,
                    "the pass must consume the current-frame jitter uniform");
            assertTrue(red(halfPixelJitter, BAR_START - 1, SIZE / 2) <= 16,
                    "jitter removal may only bleed sub-pixel energy to the opposite side");
            assertTrue(lastModifiedColumn(halfPixelJitter, sharp, BAR_END + 1) - (BAR_END + 1) <= 4,
                    "jitter removal must not exceed the unjittered bounded segment");
        } finally {
            glDeleteTextures(velocity);
            glDeleteTextures(validity);
        }
    }

    private static void assertDisabledPipelineHasNoMotionBlurPass() {
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(
                hdrSettings(), PostProcessSettings.defaults(), SIZE, SIZE, null,
                velocitySurface(), null);
             RenderGraph graph = sceneColorGraph()) {
            pipeline.beginFrame(framePose());
            pipeline.addFinalPass(graph);
            assertFalse(graph.hasPass(MOTION_BLUR_PASS),
                    "disabled motion blur must contribute no pass");
            assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                            ToneMappingMode.ACES),
                    graph.description().executionOrder(),
                    "disabled motion blur must preserve the exact baseline colour chain");
        }
    }

    private static void assertActivePipelineWritesADistinctMotionBlurAttachment() {
        PostProcessSettings effects = PostProcessSettings.builder()
                .depthOfField(DepthOfFieldSettings.of(0.2f, 1.0f, 4.0f))
                .motionBlur(MotionBlurSettings.of(SHUTTER, 8.0f)).build();
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(
                hdrSettings(), effects, SIZE, SIZE, null, velocitySurfaceWithDepth(), null);
             RenderGraph graph = sceneColorGraph()) {
            pipeline.beginFrame(framePose());
            pipeline.addFinalPass(graph);
            assertTrue(graph.hasPass(MOTION_BLUR_PASS));
            var description = graph.description();
            var pass = description.passes().stream()
                    .filter(value -> value.name().equals(MOTION_BLUR_PASS))
                    .findFirst().orElseThrow();
            assertEquals(MOTION_BLUR_COLOR,
                    pass.colorAttachments().getFirst().logicalName());
            assertEquals(RenderFormat.RGBA16F.name(),
                    pass.colorAttachments().getFirst().format());
            assertTrue(pass.directDependencies().contains(GEOMETRY_PASS));
            List<String> order = description.executionOrder();
            assertTrue(order.indexOf(DEPTH_OF_FIELD_PASS) < order.indexOf(MOTION_BLUR_PASS),
                    "motion blur must gather after depth of field");
            assertTrue(order.indexOf(MOTION_BLUR_PASS) < order.indexOf(TONE_MAPPING_PASS),
                    "motion blur must run in HDR before tone mapping");
        }

        assertThrows(IllegalArgumentException.class, () -> {
            try (PostProcessPipeline ignored = PostProcessPipeline.create(
                    RenderSettings.builder().antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.NONE).vsync(false).build(),
                    PostProcessSettings.builder()
                            .motionBlur(MotionBlurSettings.of(SHUTTER, 8.0f)).build(),
                    SIZE, SIZE, null, velocitySurface(), null)) {
                // creation must reject a non-HDR motion-blur request
            }
        });
        assertThrows(IllegalStateException.class, () -> {
            try (PostProcessPipeline pipeline = PostProcessPipeline.create(
                    hdrSettings(), PostProcessSettings.builder()
                            .motionBlur(MotionBlurSettings.of(SHUTTER, 8.0f)).build(),
                    SIZE, SIZE, null, depthOnlySurface(), null);
                 RenderGraph graph = sceneColorGraph()) {
                pipeline.beginFrame(framePose());
                pipeline.addFinalPass(graph);
            }
        });
    }

    private static RenderSettings hdrSettings() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static RenderGraph sceneColorGraph() {
        RenderGraph graph = new RenderGraph(SIZE, SIZE);
        graph.addPass(GEOMETRY_PASS)
                .createColor(SCENE_COLOR, RenderFormat.RGBA16F)
                .noClear()
                .execute((resources, commands) -> {
                });
        return graph;
    }

    private static TemporalFramePose framePose() {
        return new TemporalFramePose(0.0f, SIZE, SIZE, 0.0f, 0.0f,
                new Matrix4f(), new Matrix4f(), new Matrix4f(), null, null);
    }

    private static SurfaceInputs velocitySurface() {
        return surface(true);
    }

    private static SurfaceInputs velocitySurfaceWithDepth() {
        return surface(false);
    }

    private static SurfaceInputs depthOnlySurface() {
        return new SurfaceInputs() {
            private final Input depth = new Input(Channel.DEPTH, "sceneDepth", GEOMETRY_PASS);

            @Override
            public Input single(Channel channel) {
                return channel == Channel.DEPTH ? depth : null;
            }

            @Override
            public void validateDependency(String consumerPass, Input input) {
            }

            @Override
            public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                               Input input) {
                return 0;
            }
        };
    }

    private static SurfaceInputs surface(boolean velocityOnly) {
        SurfaceInputs.Input depth =
                new SurfaceInputs.Input(SurfaceInputs.Channel.DEPTH, "sceneDepth", GEOMETRY_PASS);
        SurfaceInputs.Input velocity =
                new SurfaceInputs.Input(SurfaceInputs.Channel.VELOCITY, "sceneVelocity", GEOMETRY_PASS);
        SurfaceInputs.Input validity =
                new SurfaceInputs.Input(SurfaceInputs.Channel.VALIDITY, "sceneValidity", GEOMETRY_PASS);
        return new SurfaceInputs() {
            @Override
            public Input single(Channel channel) {
                if (velocityOnly) return channel == Channel.VELOCITY ? velocity
                        : channel == Channel.VALIDITY ? validity : null;
                return switch (channel) {
                    case DEPTH -> depth;
                    case VELOCITY -> velocity;
                    case VALIDITY -> validity;
                    default -> null;
                };
            }

            @Override
            public void validateDependency(String consumerPass, Input input) {
            }

            @Override
            public int texture(com.kaleblangley.haikalat.core.graph.PassResources resources,
                               Input input) {
                return 0;
            }
        };
    }

    private static ByteBuffer render(Framebuffer source, Framebuffer target, MotionBlurPass pass,
                                     int velocityTexture, int validityTexture,
                                     float jitterUvX, float jitterUvY) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        GlRenderDevice device = new GlRenderDevice();
        var commands = device.createCommandBuffer();
        pass.recordIntoCurrentTarget(commands, source.colorAttachment(), velocityTexture,
                validityTexture, jitterUvX, jitterUvY, SIZE, SIZE);
        device.execute(commands);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static void prepareBar(Framebuffer source) {
        source.bind();
        glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(BAR_START, 0, BAR_WIDTH, SIZE);
        glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
    }

    private static ByteBuffer readFramebuffer(Framebuffer framebuffer) {
        framebuffer.bind();
        glDisable(GL_SCISSOR_TEST);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static int velocityTexture(float uniformU, int size) {
        FloatBuffer data = BufferUtils.createFloatBuffer(size * size * 2);
        for (int index = 0; index < size * size; index++) {
            data.put(uniformU).put(0.0f);
        }
        data.flip();
        return uploadTexture(GL_RG16F, GL_RG, size, data);
    }

    /** The bar carries {@code barU}; the background carries {@code backgroundU}. */
    private static int barVelocityTexture(float barU, float backgroundU) {
        FloatBuffer data = BufferUtils.createFloatBuffer(SIZE * SIZE * 2);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                data.put(x >= BAR_START && x <= BAR_END ? barU : backgroundU).put(0.0f);
            }
        }
        data.flip();
        return uploadTexture(GL_RG16F, GL_RG, SIZE, data);
    }

    private static int validityTexture(int value) {
        ByteBuffer data = BufferUtils.createByteBuffer(SIZE * SIZE);
        for (int index = 0; index < SIZE * SIZE; index++) {
            data.put((byte) value);
        }
        data.flip();
        return uploadTexture(GL_R8, GL_RED, SIZE, data);
    }

    private static int uploadTexture(int internalFormat, int format, int size,
                                     FloatBuffer data) {
        int texture = prepareTexture();
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, size, size, 0, format,
                org.lwjgl.opengl.GL11.GL_FLOAT, data);
        return texture;
    }

    private static int uploadTexture(int internalFormat, int format, int size,
                                     ByteBuffer data) {
        int texture = prepareTexture();
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, size, size, 0, format,
                GL_UNSIGNED_BYTE, data);
        return texture;
    }

    private static int prepareTexture() {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static int red(ByteBuffer pixels, int x, int y) {
        return Byte.toUnsignedInt(pixels.get((y * SIZE + x) * 4));
    }

    /** Last column at or after {@code fromX} that differs from the source, or -1. */
    private static int lastModifiedColumn(ByteBuffer actual, ByteBuffer source, int fromX) {
        int last = -1;
        for (int y = 0; y < SIZE; y++) {
            for (int x = fromX; x < SIZE; x++) {
                int offset = (y * SIZE + x) * 4;
                if (actual.get(offset) != source.get(offset)
                        || actual.get(offset + 1) != source.get(offset + 1)
                        || actual.get(offset + 2) != source.get(offset + 2)) {
                    last = Math.max(last, x);
                }
            }
        }
        return last;
    }

    private static int differingBytes(ByteBuffer left, ByteBuffer right) {
        int count = 0;
        for (int offset = 0; offset < left.capacity(); offset++) {
            if (left.get(offset) != right.get(offset)) count++;
        }
        return count;
    }

    private static void assertBuffersEqual(ByteBuffer expected, ByteBuffer actual, String message) {
        byte[] left = new byte[expected.capacity()];
        byte[] right = new byte[actual.capacity()];
        expected.duplicate().get(left);
        actual.duplicate().get(right);
        assertArrayEquals(left, right, message);
    }
}
