package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.runtime.AutoExposureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** 记录对数亮度、逐级归约和 1×1 曝光历史更新命令。 */
public final class AutoExposurePass implements GlResource {
    private final ShaderProgram luminanceProgram;
    private final ShaderProgram reductionProgram;
    private final ShaderProgram adaptationProgram;
    private final ScreenQuad quad;
    private final Framebuffer[] history;
    private int readIndex;
    private boolean historyValid;
    private boolean frameRecorded;
    private boolean closed;

    public AutoExposurePass() {
        ShaderProgram luminance = null;
        ShaderProgram reduction = null;
        ShaderProgram adaptation = null;
        ScreenQuad screenQuad = null;
        Framebuffer firstHistory = null;
        Framebuffer secondHistory = null;
        try {
            luminance = ShaderProgram.fromResource(AutoExposurePass.class,
                    "/postprocess/screen_quad.vert", "/postprocess/log_luminance.frag");
            reduction = ShaderProgram.fromResource(AutoExposurePass.class,
                    "/postprocess/screen_quad.vert", "/postprocess/luminance_reduce.frag");
            adaptation = ShaderProgram.fromResource(AutoExposurePass.class,
                    "/postprocess/screen_quad.vert", "/postprocess/exposure_adaptation.frag");
            screenQuad = new ScreenQuad();
            firstHistory = createHistory();
            secondHistory = createHistory();
        } catch (RuntimeException failure) {
            closeQuietly(secondHistory);
            closeQuietly(firstHistory);
            closeQuietly(screenQuad);
            closeQuietly(adaptation);
            closeQuietly(reduction);
            closeQuietly(luminance);
            throw failure;
        }
        luminanceProgram = luminance;
        reductionProgram = reduction;
        adaptationProgram = adaptation;
        quad = screenQuad;
        history = new Framebuffer[]{firstHistory, secondHistory};
    }

    /** 记录从线性 HDR 颜色提取对数亮度的全屏 pass。 */
    public CommandBuffer recordLuminance(CommandBuffer cmd, int hdrTexture, int width, int height) {
        ensureOpen();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("luminance dimensions must be positive");
        }
        return beginFullscreen(cmd, luminanceProgram)
                .bindTexture(0, hdrTexture)
                .setUniformInt(luminanceProgram, "uHdrScene", 0)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
    }

    /** 记录覆盖全部输入 texel、累计对数亮度总和与像素权重的归约 pass。 */
    public CommandBuffer recordReduction(CommandBuffer cmd, int inputTexture,
                                         int inputWidth, int inputHeight,
                                         int outputWidth, int outputHeight,
                                         boolean inputHasWeights) {
        ensureOpen();
        if (inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("reduction dimensions must be positive");
        }
        return beginFullscreen(cmd, reductionProgram)
                .bindTexture(0, inputTexture)
                .setUniformInt(reductionProgram, "uInput", 0)
                .setUniformVec2(reductionProgram, "uInputSize", inputWidth, inputHeight)
                .setUniformVec2(reductionProgram, "uOutputSize", outputWidth, outputHeight)
                .setUniformInt(reductionProgram, "uInputHasWeights", inputHasWeights ? 1 : 0)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
    }

    /** 把本帧目标曝光写入尚未发布的 1×1 history。 */
    public CommandBuffer recordAdaptation(CommandBuffer cmd, int averageLogLuminanceTexture,
                                          float initialExposure, AutoExposureSettings settings,
                                          float deltaSeconds) {
        ensureOpen();
        if (settings == null) {
            throw new NullPointerException("settings");
        }
        if (!Float.isFinite(initialExposure) || initialExposure <= 0.0f) {
            throw new IllegalArgumentException("initialExposure must be finite and positive");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        Framebuffer write = history[writeIndex()];
        beginFullscreen(cmd, adaptationProgram)
                .bindFramebuffer(write)
                .viewport(0, 0, 1, 1)
                .bindTexture(0, averageLogLuminanceTexture)
                .bindTexture(1, history[readIndex].colorAttachment())
                .setUniformInt(adaptationProgram, "uAverageLogLuminance", 0)
                .setUniformInt(adaptationProgram, "uPreviousExposure", 1)
                .setUniformInt(adaptationProgram, "uHistoryValid", historyValid ? 1 : 0)
                .setUniformFloat(adaptationProgram, "uInitialExposure", initialExposure)
                .setUniformFloat(adaptationProgram, "uMinExposure", settings.minExposure())
                .setUniformFloat(adaptationProgram, "uMaxExposure", settings.maxExposure())
                .setUniformFloat(adaptationProgram, "uKeyValue", settings.keyValue())
                .setUniformFloat(adaptationProgram, "uBrightenSpeed", settings.brightenSpeed())
                .setUniformFloat(adaptationProgram, "uDarkenSpeed", settings.darkenSpeed())
                .setUniformFloat(adaptationProgram, "uDeltaSeconds", deltaSeconds)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
        frameRecorded = true;
        return cmd;
    }

    /** @return 本帧新曝光所在纹理；仅供随后执行的 tone mapping 采样 */
    public int frameExposureTexture() {
        ensureOpen();
        if (!frameRecorded) {
            throw new IllegalStateException("Automatic exposure has not been recorded for this frame");
        }
        return history[writeIndex()].colorAttachment();
    }

    /** 仅在整张 RenderGraph 成功执行后发布本帧 history。 */
    public void commitFrame() {
        ensureOpen();
        if (!frameRecorded) {
            return;
        }
        readIndex = writeIndex();
        historyValid = true;
        frameRecorded = false;
    }

    /** 执行失败后保留上一张有效 history，并允许下一帧覆盖同一个 write target。 */
    public void discardFrame() {
        ensureOpen();
        frameRecorded = false;
    }

    public boolean historyValid() {
        return historyValid;
    }

    /** 计算给定平均亮度对应的目标曝光，供纯 JVM 测试和工具复用。 */
    public static float targetExposure(float averageLuminance, AutoExposureSettings settings) {
        if (!Float.isFinite(averageLuminance) || averageLuminance < 0.0f) {
            throw new IllegalArgumentException("averageLuminance must be finite and non-negative");
        }
        if (settings == null) {
            throw new NullPointerException("settings");
        }
        float target = settings.keyValue() / Math.max(averageLuminance, 1.0e-4f);
        return Math.max(settings.minExposure(), Math.min(settings.maxExposure(), target));
    }

    /** 使用与 shader 相同的帧率无关指数公式计算一次时间适应。 */
    public static float adaptExposure(float previousExposure, float targetExposure,
                                      float deltaSeconds, AutoExposureSettings settings) {
        if (!Float.isFinite(previousExposure) || previousExposure <= 0.0f
                || !Float.isFinite(targetExposure) || targetExposure <= 0.0f) {
            throw new IllegalArgumentException("exposure values must be finite and positive");
        }
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        if (settings == null) {
            throw new NullPointerException("settings");
        }
        float boundedPrevious = Math.max(settings.minExposure(),
                Math.min(settings.maxExposure(), previousExposure));
        float boundedTarget = Math.max(settings.minExposure(),
                Math.min(settings.maxExposure(), targetExposure));
        float speed = boundedTarget > boundedPrevious
                ? settings.brightenSpeed() : settings.darkenSpeed();
        double weight = 1.0 - Math.exp(-speed * deltaSeconds);
        float adapted = (float) (boundedPrevious + (boundedTarget - boundedPrevious) * weight);
        return Math.max(settings.minExposure(), Math.min(settings.maxExposure(), adapted));
    }

    @Override
    public int id() {
        return adaptationProgram.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        history[1].close();
        history[0].close();
        quad.close();
        adaptationProgram.close();
        reductionProgram.close();
        luminanceProgram.close();
        historyValid = false;
        frameRecorded = false;
        closed = true;
    }

    private CommandBuffer beginFullscreen(CommandBuffer cmd, ShaderProgram program) {
        return cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program);
    }

    private int writeIndex() {
        return 1 - readIndex;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Auto exposure pass is closed");
        }
    }

    private static Framebuffer createHistory() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(1, 1)
                .colorTexture(RenderFormat.R16F)
                .build());
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
            // 构造回滚必须继续释放更早创建的资源。
        }
    }
}
