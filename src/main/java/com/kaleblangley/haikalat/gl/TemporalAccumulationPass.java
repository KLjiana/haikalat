package com.kaleblangley.haikalat.gl;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUseProgram;

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
    private boolean closed;

    public TemporalAccumulationPass() {
        this.program = ShaderProgram.fromSources(VERTEX, FRAGMENT);
        this.quad = new ScreenQuad();
    }

    public void render(int currentTextureId, int historyTextureId, float historyWeight, int targetWidth, int targetHeight) {
        ensureOpen();
        glDisable(GL_DEPTH_TEST);
        glViewport(0, 0, targetWidth, targetHeight);
        glClear(GL_COLOR_BUFFER_BIT);

        program.use();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, currentTextureId);
        glUniform1i(glGetUniformLocation(program.id(), "uCurrent"), 0);
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D, historyTextureId);
        glUniform1i(glGetUniformLocation(program.id(), "uHistory"), 1);
        glUniform1f(glGetUniformLocation(program.id(), "uHistoryWeight"), historyWeight);
        quad.draw();
        glUseProgram(0);
        glEnable(GL_DEPTH_TEST);
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
