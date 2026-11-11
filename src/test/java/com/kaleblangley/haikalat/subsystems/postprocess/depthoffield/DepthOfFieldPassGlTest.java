package com.kaleblangley.haikalat.subsystems.postprocess.depthoffield;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SurfaceInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;

import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.DEPTH_OF_FIELD_COLOR;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.DEPTH_OF_FIELD_PASS;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_COLOR;
import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.TONE_MAPPING_PASS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * Real-GL contract for the first bounded DOF production pass.
 *
 * <p>Written as one GL case: focus versus defocus, max-radius bound,
 * foreground leak suppression, and the disabled/active graph no-op contract.
 * It is guarded and is intentionally not executed by the JVM worker run.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class DepthOfFieldPassGlTest {
    private static final int SIZE = 64;
    // A close focus keeps the pixel-space CoC large enough for a 64 px test target.
    private static final float FOCUS = 0.2f;
    private static final float APERTURE = 1.0f;
    private static final float FAR = 2.0f;
    private static final float MAX_COC = 12.0f;

    @Test
    void depthOfFieldKeepsFocusSharpBoundsDefocusSuppressesForegroundLeakAndDisablesCleanly() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Depth of Field GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60.0),
                    1.0f, 0.1f, 100.0f);
            Region square = new Region(SIZE / 2 - 12, SIZE / 2 - 12, 24, 24);
            try (Framebuffer source = Framebuffer.withDepthTexture(SIZE, SIZE);
                 Framebuffer target = Framebuffer.colorOnly(SIZE, SIZE);
                 DepthOfFieldPass focusPass = new DepthOfFieldPass(
                         DepthOfFieldSettings.of(FOCUS, APERTURE, MAX_COC))) {
                prepareSource(source, projection,
                        List.of(new ColorRegion(square, 1.0f, 1.0f, 1.0f)),
                        List.of(new DepthRegion(new Region(0, 0, SIZE, SIZE), FOCUS)));
                ByteBuffer focused = render(source, target, focusPass, projection);
                assertEquals(255, red(focused, SIZE / 2, SIZE / 2));
                assertEquals(0, red(focused, SIZE / 2 + 18, SIZE / 2));

                prepareSource(source, projection,
                        List.of(new ColorRegion(square, 1.0f, 1.0f, 1.0f)),
                        List.of(new DepthRegion(new Region(0, 0, SIZE, SIZE), FAR)));
                ByteBuffer defocused = render(source, target, focusPass, projection);
                assertTrue(red(defocused, SIZE / 2 + 18, SIZE / 2) > 0,
                        "a defocused background must spread beyond the in-focus square");
                assertTrue(red(defocused, SIZE / 2 + 18, SIZE / 2)
                                > red(focused, SIZE / 2 + 18, SIZE / 2) + 2,
                        "defocus must spread more energy beyond the in-focus square");
            }

            try (Framebuffer source = Framebuffer.withDepthTexture(SIZE, SIZE);
                 Framebuffer target = Framebuffer.colorOnly(SIZE, SIZE);
                 DepthOfFieldPass small = new DepthOfFieldPass(
                         DepthOfFieldSettings.of(FOCUS, APERTURE, 2.0f));
                 DepthOfFieldPass large = new DepthOfFieldPass(
                         DepthOfFieldSettings.of(FOCUS, APERTURE, MAX_COC))) {
                prepareSource(source, projection,
                        List.of(new ColorRegion(square, 1.0f, 1.0f, 1.0f)),
                        List.of(new DepthRegion(new Region(0, 0, SIZE, SIZE), FAR)));
                ByteBuffer smallRadius = render(source, target, small, projection);
                ByteBuffer largeRadius = render(source, target, large, projection);
                assertTrue(countLit(largeRadius, 2) > countLit(smallRadius, 2),
                        "the bounded max CoC must control the defocus footprint");
                assertTrue(maxDistance(smallRadius, square, 2) <= 4,
                        "2 px max CoC must not spread beyond its bounded radius");
                assertTrue(maxDistance(largeRadius, square, 2) <= 14,
                        "12 px max CoC must not spread beyond its bounded radius");
            }

            try (Framebuffer source = Framebuffer.withDepthTexture(SIZE, SIZE);
                 Framebuffer target = Framebuffer.colorOnly(SIZE, SIZE);
                 DepthOfFieldPass pass = new DepthOfFieldPass(
                         DepthOfFieldSettings.of(FOCUS, APERTURE, MAX_COC))) {
                Region rightHalf = new Region(SIZE / 2, 0, SIZE / 2, SIZE);
                prepareSource(source, projection,
                        List.of(new ColorRegion(rightHalf, 1.0f, 1.0f, 1.0f)),
                        List.of(new DepthRegion(new Region(0, 0, SIZE / 2, SIZE), FOCUS),
                                new DepthRegion(rightHalf, FAR)));
                ByteBuffer leakControlled = render(source, target, pass, projection);
                assertTrue(red(leakControlled, SIZE / 2 - 1, SIZE / 2) <= 2,
                        "defocused background must not leak over a sharp foreground edge");

                prepareSource(source, projection,
                        List.of(new ColorRegion(rightHalf, 1.0f, 1.0f, 1.0f)),
                        List.of(new DepthRegion(new Region(0, 0, SIZE, SIZE), FAR)));
                ByteBuffer unguarded = render(source, target, pass, projection);
                assertTrue(red(unguarded, SIZE / 2 - 1, SIZE / 2) > 10,
                        "the same edge must blur when the receiver is also defocused");
            }
            assertEquals(GL_NO_ERROR, glGetError());

            assertDisabledPipelineHasNoDepthOfFieldPass();
            assertActivePipelineWritesADistinctDofAttachment();
        }
    }

    private static void assertDisabledPipelineHasNoDepthOfFieldPass() {
        RenderSettings settings = hdrSettings();
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(
                settings, PostProcessSettings.defaults(), SIZE, SIZE, null, depthSurface(), null);
             RenderGraph graph = sceneColorGraph()) {
            pipeline.beginFrame(framePose());
            pipeline.addFinalPass(graph);
            assertFalse(graph.hasPass(DEPTH_OF_FIELD_PASS),
                    "disabled DOF must contribute no pass");
            assertEquals(PostProcessPipeline.passNamesFor(AntiAliasingMode.NONE,
                            ToneMappingMode.ACES),
                    graph.description().executionOrder(),
                    "disabled DOF must preserve the exact baseline colour chain");
        }
    }

    private static void assertActivePipelineWritesADistinctDofAttachment() {
        PostProcessSettings effects = PostProcessSettings.builder()
                .depthOfField(DepthOfFieldSettings.of(FOCUS, APERTURE, MAX_COC)).build();
        try (PostProcessPipeline pipeline = PostProcessPipeline.create(
                hdrSettings(), effects, SIZE, SIZE, null, depthSurface(), null);
             RenderGraph graph = sceneColorGraph()) {
            pipeline.beginFrame(framePose());
            pipeline.addFinalPass(graph);
            assertTrue(graph.hasPass(DEPTH_OF_FIELD_PASS));
            var description = graph.description();
            var pass = description.passes().stream()
                    .filter(value -> value.name().equals(DEPTH_OF_FIELD_PASS))
                    .findFirst().orElseThrow();
            assertEquals(DEPTH_OF_FIELD_COLOR,
                    pass.colorAttachments().getFirst().logicalName());
            assertEquals(RenderFormat.RGBA16F.name(),
                    pass.colorAttachments().getFirst().format());
            assertTrue(pass.directDependencies().contains(GEOMETRY_PASS));
            List<String> order = description.executionOrder();
            assertTrue(order.indexOf(DEPTH_OF_FIELD_PASS) < order.indexOf(TONE_MAPPING_PASS),
                    "DOF must run in HDR before tone mapping");
        }
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

    private static SurfaceInputs depthSurface() {
        SurfaceInputs.Input depth = new SurfaceInputs.Input(SurfaceInputs.Channel.DEPTH,
                "sceneDepth", GEOMETRY_PASS);
        return new SurfaceInputs() {
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

    private static ByteBuffer render(Framebuffer source, Framebuffer target,
                                     DepthOfFieldPass pass, Matrix4f projection) {
        target.bind();
        glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        GlRenderDevice device = new GlRenderDevice();
        var commands = device.createCommandBuffer();
        pass.recordIntoCurrentTarget(commands, source.colorAttachment(),
                source.depthAttachment(), new Matrix4f(projection).invert(), SIZE, SIZE);
        device.execute(commands);
        ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static void prepareSource(Framebuffer source, Matrix4f projection,
                                      List<ColorRegion> colors, List<DepthRegion> depths) {
        source.bind();
        glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        for (ColorRegion color : colors) {
            glScissor(color.region().x(), color.region().y(),
                    color.region().width(), color.region().height());
            glClearColor(color.red(), color.green(), color.blue(), 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
        }
        for (DepthRegion depth : depths) {
            glScissor(depth.region().x(), depth.region().y(),
                    depth.region().width(), depth.region().height());
            glClearDepth(deviceDepth(depth.viewDistance(), projection));
            glClear(GL_DEPTH_BUFFER_BIT);
        }
        glDisable(GL_SCISSOR_TEST);
        glClearDepth(1.0);
    }

    private static double deviceDepth(float viewDistance, Matrix4f projection) {
        Vector4f clip = new Vector4f(0.0f, 0.0f, -viewDistance, 1.0f).mul(projection);
        return clip.z / clip.w * 0.5 + 0.5;
    }

    private static int red(ByteBuffer pixels, int x, int y) {
        return Byte.toUnsignedInt(pixels.get((y * SIZE + x) * 4));
    }

    private static int countLit(ByteBuffer pixels, int threshold) {
        int count = 0;
        for (int offset = 0; offset < pixels.capacity(); offset += 4) {
            if (Byte.toUnsignedInt(pixels.get(offset)) > threshold) count++;
        }
        return count;
    }

    private static int maxDistance(ByteBuffer pixels, Region region, int threshold) {
        int maximum = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (red(pixels, x, y) <= threshold) continue;
                int dx = Math.max(Math.max(region.x() - x, 0),
                        x - (region.x() + region.width() - 1));
                int dy = Math.max(Math.max(region.y() - y, 0),
                        y - (region.y() + region.height() - 1));
                maximum = Math.max(maximum, Math.max(dx, dy));
            }
        }
        return maximum;
    }

    private record Region(int x, int y, int width, int height) {
    }

    private record ColorRegion(Region region, float red, float green, float blue) {
    }

    private record DepthRegion(Region region, float viewDistance) {
    }
}
