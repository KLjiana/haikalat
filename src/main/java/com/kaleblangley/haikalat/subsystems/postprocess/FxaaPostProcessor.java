package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

public final class FxaaPostProcessor implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final int uSceneLocation;
    private final int uInvResLocation;
    private boolean closed;

    public FxaaPostProcessor() {
        this.program = ShaderProgram.fromResource(FxaaPostProcessor.class,
                "/postprocess/screen_quad.vert", "/postprocess/fxaa.frag");
        this.quad = new ScreenQuad();
        this.uSceneLocation = program.uniformLocation("uScene");
        this.uInvResLocation = program.uniformLocation("uInvResolution");
    }

    public CommandBuffer record(CommandBuffer cmd, Framebuffer source, int targetW, int targetH) {
        cmd.bindFramebuffer(GL_FRAMEBUFFER, 0);
        cmd.viewport(0, 0, targetW, targetH);
        cmd.enableDepthTest(false);
        cmd.clear(true, false);
        cmd.bindShader(program);
        cmd.bindTexture(0, source.colorAttachment());
        cmd.setUniformInt(program, "uScene", 0);
        cmd.setUniformVec2(program, "uInvResolution", 1.0f / source.width(), 1.0f / source.height());
        cmd.bindVertexArray(quad.id());
        cmd.drawArrays(GL_TRIANGLES, 0, 6);
        cmd.enableDepthTest(true);
        return cmd;
    }

    public ShaderProgram program() {
        return program;
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
        if (closed) {
            return;
        }
        quad.close();
        program.close();
        closed = true;
    }

    public void ensureOpen() {
        if (closed) {
            throw new GlException("FXAA post processor is closed");
        }
    }
}
