package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** 把线性 HDR 场景经过 exposure、ACES fitted curve 和 gamma 编码输出为 LDR。 */
public final class ToneMappingPass implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private boolean closed;

    public ToneMappingPass() {
        program = ShaderProgram.fromResource(ToneMappingPass.class,
                "/postprocess/screen_quad.vert", "/postprocess/aces_tone_mapping.frag");
        quad = new ScreenQuad();
    }

    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int hdrTexture, float exposure) {
        return recordIntoCurrentTarget(cmd, hdrTexture, 0, exposure, 0.0f);
    }

    /**
     * 把线性 HDR 场景与半分辨率 Bloom 纹理直接合成并映射到 LDR。
     *
     * @param cmd              命令缓冲区
     * @param hdrTexture       HDR 场景纹理
     * @param bloomTexture     Bloom 纹理；0 表示关闭
     * @param exposure         曝光值
     * @param bloomIntensity   Bloom 合成强度
     * @return 当前命令缓冲区
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int hdrTexture, int bloomTexture,
                                                 float exposure, float bloomIntensity) {
        return recordIntoCurrentTarget(cmd, hdrTexture, bloomTexture, exposure, 0, bloomIntensity);
    }

    /**
     * 把 HDR 场景映射到 LDR，并可直接从 1×1 GPU texture 读取自动曝光。
     *
     * @param exposureTexture 1×1 自动曝光纹理；0 表示使用手动 exposure
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int hdrTexture, int bloomTexture,
                                                 float exposure, int exposureTexture,
                                                 float bloomIntensity) {
        ensureOpen();
        cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, hdrTexture)
                .setUniformInt(program, "uHdrScene", 0)
                .setUniformInt(program, "uBloomEnabled", bloomTexture != 0 ? 1 : 0)
                .setUniformFloat(program, "uExposure", exposure)
                .setUniformInt(program, "uAutoExposure", exposureTexture != 0 ? 1 : 0)
                .setUniformFloat(program, "uBloomIntensity", bloomIntensity);
        if (bloomTexture != 0) {
            cmd.bindTexture(1, bloomTexture)
                    .setUniformInt(program, "uBloom", 1);
        }
        if (exposureTexture != 0) {
            cmd.bindTexture(2, exposureTexture)
                    .setUniformInt(program, "uExposureTexture", 2);
        }
        cmd
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
        return cmd;
    }

    /**
     * ACES shader 的纯 JVM 单通道参考实现，不包含最终 gamma 编码。
     *
     * @param linearValue 线性 HDR 输入
     * @param exposure    正曝光值
     * @return 限制在 0～1 的 ACES fitted 输出
     */
    public static float acesChannel(float linearValue, float exposure) {
        if (!Float.isFinite(linearValue) || linearValue < 0.0f) {
            throw new IllegalArgumentException("linearValue must be finite and non-negative");
        }
        if (!Float.isFinite(exposure) || exposure <= 0.0f) {
            throw new IllegalArgumentException("exposure must be finite and positive");
        }
        double color = (double) linearValue * exposure;
        double mapped = color * (2.51 * color + 0.03)
                / (color * (2.43 * color + 0.59) + 0.14);
        return (float) Math.max(0.0, Math.min(1.0, mapped));
    }

    @Override
    public int id() {
        return program.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        quad.close();
        program.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Tone mapping pass is closed");
    }
}
