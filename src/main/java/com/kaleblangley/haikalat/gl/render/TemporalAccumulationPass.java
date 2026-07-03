package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.material.ShaderProgram;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

public final class TemporalAccumulationPass implements GlResource {
    private static final String VERTEX = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            layout (location = 1) in vec2 aUv;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            out vec4 FragColor;
            in vec2 vUv;
            uniform sampler2D uCurrent;
            uniform sampler2D uHistory;
            uniform float uHistoryWeight;
            void main() {
                vec3 current = texture(uCurrent, vUv).rgb;
                vec3 history = texture(uHistory, vUv).rgb;
                FragColor = vec4(mix(current, history, clamp(uHistoryWeight, 0.0, 0.95)), 1.0);
            }
            """;

    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final int uCurrentLoc;
    private final int uHistoryLoc;
    private final int uWeightLoc;
    private boolean closed;

    public TemporalAccumulationPass() {
        this.program = ShaderProgram.fromSources(VERTEX, FRAGMENT);
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
