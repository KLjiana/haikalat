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
        ensureOpen();
        cmd.enableDepthTest(false)
                .bindShader(program)
                .bindTexture(0, hdrTexture)
                .setUniformInt(program, "uHdrScene", 0)
                .setUniformFloat(program, "uExposure", exposure)
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
