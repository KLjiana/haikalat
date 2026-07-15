package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.AutoExposureSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.AutoExposurePass;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glGetTexImage;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL30.GL_RG;

/** 验证 R16F、奇数尺寸亮度归约和跨帧曝光 history 的真实 GPU 行为。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class AutoExposureGlTest {
    @Test
    void r16fStoresFiniteValuesAboveAndBelowOne() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            Framebuffer target = r16fTarget(1, 1);
            try {
                clearRed(target, 2.5f);
                assertEquals(2.5f, readRed(target), 0.01f);
                clearRed(target, 0.125f);
                assertEquals(0.125f, readRed(target), 0.002f);
                GlDebug.checkError("r16fStoresFiniteValuesAboveAndBelowOne");
            } finally {
                target.close();
            }
        }
    }

    @Test
    void nineByOneNonUniformLuminanceKeepsHighlightWeightAcrossFullChain() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            AutoExposurePass pass = new AutoExposurePass();
            try {
                float average = reduceSingleHighlight(pass, device, 9, 8, 16.0f);

                assertEquals((float) Math.log(16.0) / 9.0f, average, 0.002f);
                GlDebug.checkError("nineByOneNonUniformLuminanceKeepsHighlightWeightAcrossFullChain");
            } finally {
                pass.close();
            }
        }
    }

    @Test
    void leftAndRightEdgeTexelsHaveEqualWeightAt1280Pixels() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            AutoExposurePass pass = new AutoExposurePass();
            try {
                float left = reduceSingleHighlight(pass, device, 1280, 0, 16.0f);
                float right = reduceSingleHighlight(pass, device, 1280, 1279, 16.0f);
                float expected = (float) Math.log(16.0) / 1280.0f;

                assertEquals(expected, left, 1.0e-5f);
                assertEquals(expected, right, 1.0e-5f);
                assertEquals(left, right, 1.0e-6f);
                GlDebug.checkError("leftAndRightEdgeTexelsHaveEqualWeightAt1280Pixels");
            } finally {
                pass.close();
            }
        }
    }

    @Test
    void graphBuiltAtOneByOneMeasuresWholeImageAfterResize() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            AutoExposurePass pass = new AutoExposurePass();
            ScreenQuad quad = new ScreenQuad();
            ShaderProgram sourceProgram = ShaderProgram.fromSources(
                    "#version 330 core\nlayout(location=0) in vec2 aPosition;\n"
                            + "void main(){gl_Position=vec4(aPosition,0.0,1.0);}\n",
                    "#version 330 core\nout float FragLogLuminance;\nuniform vec2 uSize;\n"
                            + "void main(){ivec2 p=ivec2(gl_FragCoord.xy);ivec2 s=ivec2(uSize);"
                            + "FragLogLuminance=(all(equal(p,s-ivec2(1))))?log(16.0):0.0;}\n");
            RenderGraph graph = new RenderGraph(1, 1);
            Framebuffer[] resultTarget = new Framebuffer[1];
            try {
                addResizeReductionGraph(graph, pass, sourceProgram, quad, resultTarget);
                graph.resize(47, 33);
                graph.execute(device);

                float[] reduction = readRg(resultTarget[0]);
                assertEquals(47.0f * 33.0f, reduction[1], 0.5f);
                assertEquals((float) Math.log(16.0) / (47.0f * 33.0f),
                        reduction[0] / reduction[1], 1.0e-5f);
                GlDebug.checkError("graphBuiltAtOneByOneMeasuresWholeImageAfterResize");
            } finally {
                graph.close();
                sourceProgram.close();
                quad.close();
                pass.close();
            }
        }
    }

    @Test
    void exposureMovesMonotonicallyAndDiscardedFrameDoesNotPublishHistory() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            Framebuffer average = rg32fTarget(1, 1);
            AutoExposureSettings settings = AutoExposureSettings.defaults();
            AutoExposurePass pass = new AutoExposurePass();
            try {
                float dark = adapt(pass, device, average, 0.01f, 1.0f, settings, true);
                float bright = adapt(pass, device, average, 4.0f, 1.0f, settings, true);
                assertTrue(dark > 1.0f && dark <= settings.maxExposure());
                assertTrue(bright < dark && bright >= settings.minExposure());

                AutoExposurePass failureSafe = new AutoExposurePass();
                try {
                    float baseline = adapt(failureSafe, device, average, 0.18f, 1.0f,
                            settings, true);
                    float discarded = adapt(failureSafe, device, average, 4.0f, 1.0f,
                            settings, false);
                    float resumed = adapt(failureSafe, device, average, 0.18f, 1.0f,
                            settings, true);
                    assertEquals(1.0f, baseline, 0.01f);
                    assertTrue(discarded < baseline);
                    assertEquals(baseline, resumed, 0.02f,
                            "discarded write target must not become the next frame's read history");
                } finally {
                    failureSafe.close();
                }
                GlDebug.checkError("exposureMovesMonotonicallyAndDiscardedFrameDoesNotPublishHistory");
            } finally {
                pass.close();
                average.close();
            }
        }
    }

    private static float reduceSingleHighlight(AutoExposurePass pass, GlRenderDevice device,
                                               int width, int highlightX, float highlight) {
        Framebuffer hdr = rgba16fTarget(width, 1);
        Framebuffer luminance = r16fTarget(width, 1);
        List<Framebuffer> reductions = new ArrayList<>(14);
        try {
            clearRgb(hdr, 1.0f);
            glEnable(GL_SCISSOR_TEST);
            glScissor(highlightX, 0, 1, 1);
            glClearColor(highlight, highlight, highlight, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glDisable(GL_SCISSOR_TEST);
            device.invalidateState();

            CommandBuffer cmd = new CommandBuffer().bindFramebuffer(luminance)
                    .viewport(0, 0, width, 1);
            pass.recordLuminance(cmd, hdr.colorAttachment(), width, 1);
            Framebuffer input = luminance;
            for (int level = 0; level < 14; level++) {
                int outputWidth = level == 13
                        ? 1 : Math.max(1, Math.round(width * (float) Math.scalb(1.0, -(level + 1))));
                Framebuffer output = rg32fTarget(outputWidth, 1);
                reductions.add(output);
                cmd.bindFramebuffer(output).viewport(0, 0, outputWidth, 1);
                pass.recordReduction(cmd, input.colorAttachment(), input.width(), input.height(),
                        output.width(), output.height(), level > 0);
                input = output;
            }
            device.execute(cmd);
            float[] reduction = readRg(reductions.getLast());
            return reduction[0] / Math.max(reduction[1], 1.0f);
        } finally {
            for (int i = reductions.size() - 1; i >= 0; i--) {
                reductions.get(i).close();
            }
            luminance.close();
            hdr.close();
        }
    }

    private static void addResizeReductionGraph(RenderGraph graph, AutoExposurePass pass,
                                                ShaderProgram sourceProgram, ScreenQuad quad,
                                                Framebuffer[] resultTarget) {
        String previousPass = "resize-source-pass";
        String previousTexture = "resize-source";
        graph.addPass(previousPass)
                .createColor(previousTexture, RenderFormat.R16F)
                .noClear()
                .execute((resources, cmd) -> {
                    Framebuffer target = resources.currentTarget();
                    cmd.enableBlend(false)
                            .enableDepthTest(false)
                            .enableCullFace(false)
                            .bindShader(sourceProgram)
                            .setUniformVec2(sourceProgram, "uSize", target.width(), target.height())
                            .bindVertexArray(quad.id())
                            .drawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, 6);
                });
        for (int level = 0; level < 14; level++) {
            final int reductionLevel = level;
            final String inputPass = previousPass;
            final String inputTexture = previousTexture;
            String passName = "resize-reduce-pass-" + level;
            String outputTexture = "resize-reduce-" + level;
            RenderGraph.PassBuilder builder = graph.addPass(passName)
                    .createColor(outputTexture, RenderFormat.RG32F)
                    .noClear()
                    .dependsOn(inputPass);
            if (level == 13) {
                builder.fixedSize(1, 1);
            } else {
                builder.relativeSize((float) Math.scalb(1.0, -(level + 1)));
            }
            builder.execute((resources, cmd) -> {
                Framebuffer input = resources.framebufferOfPass(inputPass);
                Framebuffer output = resources.currentTarget();
                pass.recordReduction(cmd, resources.colorAttachment(inputTexture),
                        input.width(), input.height(), output.width(), output.height(),
                        reductionLevel > 0);
                if (reductionLevel == 13) {
                    resultTarget[0] = output;
                }
            });
            previousPass = passName;
            previousTexture = outputTexture;
        }
    }

    private static float adapt(AutoExposurePass pass, GlRenderDevice device, Framebuffer average,
                               float luminance, float deltaSeconds, AutoExposureSettings settings,
                               boolean commit) {
        clearRg(average, (float) Math.log(Math.max(luminance, 1.0e-4f)), 1.0f);
        device.invalidateState();
        CommandBuffer cmd = new CommandBuffer();
        pass.recordAdaptation(cmd, average.colorAttachment(), 1.0f, settings, deltaSeconds);
        int exposureTexture = pass.frameExposureTexture();
        device.execute(cmd);
        float exposure = readTextureRed(exposureTexture);
        device.invalidateState();
        if (commit) {
            pass.commitFrame();
        } else {
            pass.discardFrame();
        }
        return exposure;
    }

    private static Framebuffer r16fTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.R16F)
                .build());
    }

    private static Framebuffer rgba16fTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.RGBA16F)
                .build());
    }

    private static Framebuffer rg32fTarget(int width, int height) {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(RenderFormat.RG32F)
                .build());
    }

    private static void clearRed(Framebuffer target, float value) {
        target.bind();
        glClearColor(value, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static void clearRgb(Framebuffer target, float value) {
        target.bind();
        glClearColor(value, value, value, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static void clearRg(Framebuffer target, float red, float green) {
        target.bind();
        glClearColor(red, green, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    private static float readRed(Framebuffer target) {
        target.bind();
        FloatBuffer pixel = BufferUtils.createFloatBuffer(1);
        glReadPixels(0, 0, 1, 1, GL_RED, GL_FLOAT, pixel);
        return pixel.get(0);
    }

    private static float readTextureRed(int texture) {
        FloatBuffer pixel = BufferUtils.createFloatBuffer(1);
        glBindTexture(GL_TEXTURE_2D, texture);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RED, GL_FLOAT, pixel);
        return pixel.get(0);
    }

    private static float[] readRg(Framebuffer target) {
        target.bind();
        FloatBuffer pixel = BufferUtils.createFloatBuffer(2);
        glReadPixels(0, 0, 1, 1, GL_RG, GL_FLOAT, pixel);
        return new float[]{pixel.get(0), pixel.get(1)};
    }
}
