package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

public final class TemporalAccumulationPass implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final int uCurrentLoc;
    private final int uHistoryLoc;
    private final int uWeightLoc;
    private boolean closed;

    public TemporalAccumulationPass() {
        this.program = ShaderProgram.fromResource(TemporalAccumulationPass.class,
                "/postprocess/screen_quad.vert", "/postprocess/temporal_accumulation.frag");
        this.quad = new ScreenQuad();
        this.uCurrentLoc = program.uniformLocation("uCurrent");
        this.uHistoryLoc = program.uniformLocation("uHistory");
        this.uWeightLoc = program.uniformLocation("uHistoryWeight");
    }

    public CommandBuffer record(CommandBuffer cmd, int currentTexId, int historyTexId,
                                 float historyWeight, int targetW, int targetH) {
        cmd.bindFramebuffer(GL_FRAMEBUFFER, 0);
        cmd.viewport(0, 0, targetW, targetH);
        cmd.enableDepthTest(false);
        cmd.clear(true, false);
        cmd.bindShader(program);
        cmd.bindTexture(0, currentTexId);
        cmd.bindTexture(1, historyTexId);
        cmd.setUniformInt(program, "uCurrent", 0);
        cmd.setUniformInt(program, "uHistory", 1);
        cmd.setUniformFloat(program, "uHistoryWeight", historyWeight);
        cmd.bindVertexArray(quad.id());
        cmd.drawArrays(GL_TRIANGLES, 0, 6);
        cmd.enableDepthTest(true);
        return cmd;
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

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Temporal accumulation pass is closed");
        }
    }
}
