package com.kaleblangley.haikalat.subsystems.postprocess.upscale;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSource;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Pipeline-level real-GL acceptance for #35 temporal upscaling.
 *
 * <p>Supplies a low-resolution colour/depth source through the production
 * {@link RenderPipeline} entry, renders one frame, and compares the linear HDR
 * output with an independently driven {@link TemporalUpscalingPass} using the
 * same source, settings and pose.  A replacement source rebinds through
 * {@code applyTemporalUpscalingSource}; a missing source is rejected before the
 * candidate generation is prepared.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TemporalUpscalingPipelineGlTest {
    private static final int REQUESTED_WIDTH = 64;
    private static final int REQUESTED_HEIGHT = 64;
    private static final int INPUT_WIDTH = 32;
    private static final int INPUT_HEIGHT = 32;
    private static final TemporalUpscalingSettings UPSCALING =
            TemporalUpscalingSettings.builder()
                    .enabled(true)
                    .renderScale(0.5f)
                    .historyBlend(0.9f)
                    .sharpness(0.0f)
                    .reactiveThreshold(1.0f)
                    .exposureThreshold(1.0f)
                    .maxReprojectionPixels(64.0f)
                    .quality(TemporalUpscalingSettings.Quality.MEDIUM)
                    .build();

    @Test
    void suppliedSourceReachesThePipelineUpscalerAndMatchesThePassReference() {
        try (GlfwWindow window = window("Temporal Upscaling Pipeline Source")) {
            GlRenderDevice device = new GlRenderDevice();
            int outputWidth = window.width();
            int outputHeight = window.height();
            try (Source first = new Source(device, 0.15f, 0.45f);
                 Source second = new Source(device, 0.65f, 0.15f)) {
                Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 0.0f),
                        new Vector3f(0.0f, 0.0f, -1.0f), 0.0f, 0.0f);
                RenderPipeline pipeline = pipeline(window, camera, first);
                try {
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] actual = pipeline.captureLinearHdrRgbaFloat();
                    assertEquals(outputWidth * outputHeight * 4, actual.length,
                            "the upscaled output must have the target extent");
                    float[] expected = reference(device, camera, first, outputWidth,
                            outputHeight);
                    assertRgbaClose(expected, actual, 2.0e-3f,
                            "the pipeline must match the standalone upscale pass");
                    for (float value : actual) {
                        assertTrue(Float.isFinite(value),
                                "the upscaled pipeline output must stay finite");
                    }

                    pipeline.applyTemporalUpscalingSource(second.source());
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] rebound = pipeline.captureLinearHdrRgbaFloat();
                    float[] reboundExpected = reference(device, camera, second, outputWidth,
                            outputHeight);
                    assertRgbaClose(reboundExpected, rebound, 2.0e-3f,
                            "a replacement source must be rebound into the new generation");
                    assertTrue(maximumChannelDifference(actual, rebound) > 0.05f,
                            "the two sources must produce observably different upscaled frames");
                } finally {
                    pipeline.close();
                }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void resizeRebuildsTheGenerationAndKeepsTheSourceBound() {
        try (GlfwWindow window = window("Temporal Upscaling Pipeline Resize")) {
            GlRenderDevice device = new GlRenderDevice();
            try (Source source = new Source(device, 0.2f, 0.5f)) {
                Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 0.0f),
                        new Vector3f(0.0f, 0.0f, -1.0f), 0.0f, 0.0f);
                RenderPipeline pipeline = pipeline(window, camera, source);
                try {
                    pipeline.execute(device, 1.0f / 60.0f);
                    int width = 48;
                    int height = 40;
                    try (Framebuffer target = Framebuffer.colorOnly(width, height)) {
                        PresentationTarget presentation = PresentationTarget.borrowed(
                                target.id(),
                                ExternalAttachment.borrowedColor(target.colorAttachment(0),
                                        RenderFormat.RGBA8, width, height),
                                null, width, height, 1L);
                        Matrix4f projection = CameraProjection.stable(camera, width, height,
                                new Matrix4f());
                        ExternalCamera external = ExternalCamera.of(
                                camera.getViewMatrix(new Matrix4f()), projection,
                                camera.position(), 0.0f);
                        pipeline.execute(device, external, presentation, 1.0f / 60.0f);
                        float[] resized = pipeline.captureLinearHdrRgbaFloat();
                        assertEquals(width * height * 4, resized.length,
                                "the resized generation must own the new output extent");
                        float[] expected = reference(device, external, source, width, height);
                        assertRgbaClose(expected, resized, 2.0e-3f,
                                "a resize must rebuild the colour chain with the same source");
                    }
                } finally {
                    pipeline.close();
                }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void activeUpscalingWithoutSourceIsRejectedBeforeGeneration() {
        try (GlfwWindow window = window("Temporal Upscaling Pipeline Missing Source")) {
            RenderPipeline pipeline = new RenderPipeline(window, new Scene(new Camera()), null,
                    settings())
                    .postProcessSettings(PostProcessSettings.builder()
                            .temporalUpscaling(UPSCALING)
                            .build());
            try {
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class, pipeline::build);
                assertTrue(failure.getMessage().contains("low-resolution colour/depth source"),
                        failure.getMessage());
                assertEquals(null, pipeline.graph(),
                        "the dependency check must run before a candidate graph exists");
            } finally {
                pipeline.close();
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static RenderPipeline pipeline(GlfwWindow window, Camera camera, Source source) {
        RenderPipeline pipeline = new RenderPipeline(window, new Scene(camera), null, settings())
                .postProcessSettings(PostProcessSettings.builder()
                        .temporalUpscaling(UPSCALING)
                        .build())
                .temporalUpscalingSource(source.source());
        pipeline.build();
        return pipeline;
    }

    private static float[] reference(GlRenderDevice device, Camera camera, Source source,
                                     int outputWidth, int outputHeight) {
        TemporalUpscalingPass pass = new TemporalUpscalingPass(UPSCALING, outputWidth,
                outputHeight);
        try {
            Matrix4f projection = CameraProjection.stable(camera, outputWidth,
                    outputHeight, new Matrix4f());
            Matrix4f view = camera.getViewMatrix(new Matrix4f());
            Matrix4f stableViewProjection = new Matrix4f(projection).mul(view);
            pass.beginFrame();
            var commands = device.createCommandBuffer();
            pass.record(commands, new TemporalUpscalingInputs(
                    source.source().colorTexture(),
                    source.source().depthTexture(),
                    0, 0, 0, 0,
                    INPUT_WIDTH, INPUT_HEIGHT,
                    0.0f, 0.0f, settings().exposure(),
                    new Matrix4f(projection).invert(),
                    new Matrix4f(view).invert(),
                    stableViewProjection));
            device.execute(commands);
            pass.frameSucceeded();
            return readFloat(pass.readOutputFramebuffer());
        } finally {
            pass.close();
        }
    }

    private static RenderSettings settings() {
        return RenderSettings.builder()
                .vsync(false)
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .build();
    }

    private static void assertRgbaClose(float[] expected, float[] actual, float tolerance,
                                        String message) {
        assertEquals(expected.length, actual.length, message + " length");
        for (int index = 0; index < expected.length; index++) {
            if (index % 4 == 3) continue;
            assertEquals(expected[index], actual[index], tolerance,
                    message + " at component " + index);
        }
    }

    private static float maximumChannelDifference(float[] left, float[] right) {
        float maximum = 0.0f;
        for (int index = 0; index < left.length; index++) {
            if (index % 4 == 3) continue;
            maximum = Math.max(maximum, Math.abs(left[index] - right[index]));
        }
        return maximum;
    }

    private static float[] readFloat(Framebuffer framebuffer) {
        int width = framebuffer.width();
        int height = framebuffer.height();
        framebuffer.bind();
        glViewport(0, 0, width, height);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[width * height * 4];
        pixels.get(result);
        return result;
    }

    private static GlfwWindow window(String title) {
        GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(REQUESTED_WIDTH, REQUESTED_HEIGHT).title(title).visible(false).build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        window.setVsync(false);
        return window;
    }

    /** Low-resolution RGBA16F colour + depth source owned by the test host. */
    private static final class Source implements AutoCloseable {
        private static final String VERTEX = """
                #version 460 core
                layout(location = 0) in vec3 aPosition;
                out vec2 vUv;
                void main() {
                    vUv = aPosition.xy * 0.5 + 0.5;
                    gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                }
                """;
        private static final String FRAGMENT = """
                #version 460 core
                in vec2 vUv;
                layout(location = 0) out vec4 fragColor;
                uniform vec3 uTint;
                void main() {
                    vec3 colour = vec3(uTint.r * (0.4 + 0.6 * vUv.x),
                            uTint.g * (0.2 + 0.8 * vUv.y), 0.5);
                    fragColor = vec4(colour, 1.0);
                }
                """;

        private final Framebuffer framebuffer;
        private final ShaderProgram shader;
        private final Mesh quad;
        private final TemporalUpscalingSource source;

        Source(GlRenderDevice device, float red, float green) {
            framebuffer = Framebuffer.fromDescriptor(
                    FramebufferDescriptor.builder(INPUT_WIDTH, INPUT_HEIGHT)
                            .colorTexture(RenderFormat.RGBA16F)
                            .depthTexture()
                            .build());
            shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            quad = Mesh.builder()
                    .vertices(new float[]{
                            -1.0f, -1.0f, 0.0f,
                            1.0f, -1.0f, 0.0f,
                            1.0f, 1.0f, 0.0f,
                            -1.0f, 1.0f, 0.0f
                    }, 3 * Float.BYTES, position)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
            framebuffer.bind();
            glViewport(0, 0, INPUT_WIDTH, INPUT_HEIGHT);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClearDepth(0.5);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            var commands = device.createCommandBuffer();
            commands.bindFramebuffer(framebuffer)
                    .viewport(0, 0, INPUT_WIDTH, INPUT_HEIGHT)
                    .bindShader(shader)
                    .setUniformVec3(shader, "uTint", new Vector3f(red, green, 0.2f))
                    .enableDepthTest(false)
                    .enableBlend(false)
                    .bindMesh(quad)
                    .drawMesh(quad);
            device.execute(commands);
            source = new TemporalUpscalingSource(framebuffer.colorAttachment(0),
                    framebuffer.depthAttachment(), INPUT_WIDTH, INPUT_HEIGHT);
        }

        TemporalUpscalingSource source() {
            return source;
        }

        @Override
        public void close() {
            try {
                quad.close();
                shader.close();
            } finally {
                framebuffer.close();
            }
        }
    }
}
