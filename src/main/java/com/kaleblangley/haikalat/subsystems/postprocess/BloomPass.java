package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * 记录 Bloom 的高亮提取、降采样和逐级上采样命令。
 * framebuffer 的创建、resize 与释放仍完全归 RenderGraph 管理。
 */
public final class BloomPass implements GlResource {
    private final ShaderProgram extractProgram;
    private final ShaderProgram downsampleProgram;
    private final ShaderProgram upsampleProgram;
    private final ScreenQuad quad;
    private boolean closed;

    public BloomPass() {
        extractProgram = ShaderProgram.fromResource(BloomPass.class,
                "/shaders/postprocess/screen-quad.vert", "/shaders/postprocess/bloom-extract.frag");
        downsampleProgram = ShaderProgram.fromResource(BloomPass.class,
                "/shaders/postprocess/screen-quad.vert", "/shaders/postprocess/bloom-downsample.frag");
        upsampleProgram = ShaderProgram.fromResource(BloomPass.class,
                "/shaders/postprocess/screen-quad.vert", "/shaders/postprocess/bloom-upsample.frag");
        quad = new ScreenQuad();
    }

    public CommandBuffer recordExtract(CommandBuffer cmd, int sourceTexture,
                                       int sourceWidth, int sourceHeight,
                                       float threshold, float softKnee) {
        ensureOpen();
        fullscreenState(cmd)
                .bindShader(extractProgram)
                .bindTexture(0, sourceTexture)
                .setUniformInt(extractProgram, "uSource", 0)
                .setUniformVec2(extractProgram, "uSourceTexelSize",
                        1.0f / sourceWidth, 1.0f / sourceHeight)
                .setUniformFloat(extractProgram, "uThreshold", threshold)
                .setUniformFloat(extractProgram, "uSoftKnee", softKnee)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
        return cmd;
    }

    public CommandBuffer recordDownsample(CommandBuffer cmd, int sourceTexture,
                                          int sourceWidth, int sourceHeight) {
        ensureOpen();
        fullscreenState(cmd)
                .bindShader(downsampleProgram)
                .bindTexture(0, sourceTexture)
                .setUniformInt(downsampleProgram, "uSource", 0)
                .setUniformVec2(downsampleProgram, "uSourceTexelSize",
                        1.0f / sourceWidth, 1.0f / sourceHeight)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
        return cmd;
    }

    public CommandBuffer recordUpsample(CommandBuffer cmd, int highTexture, int lowTexture,
                                        int lowWidth, int lowHeight) {
        ensureOpen();
        fullscreenState(cmd)
                .bindShader(upsampleProgram)
                .bindTexture(0, highTexture)
                .bindTexture(1, lowTexture)
                .setUniformInt(upsampleProgram, "uHigh", 0)
                .setUniformInt(upsampleProgram, "uLow", 1)
                .setUniformVec2(upsampleProgram, "uLowTexelSize",
                        1.0f / lowWidth, 1.0f / lowHeight)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
        return cmd;
    }

    private static CommandBuffer fullscreenState(CommandBuffer cmd) {
        return cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false);
    }

    @Override
    public int id() {
        return extractProgram.id();
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
        quad.close();
        upsampleProgram.close();
        downsampleProgram.close();
        extractProgram.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Bloom pass is closed");
        }
    }
}
