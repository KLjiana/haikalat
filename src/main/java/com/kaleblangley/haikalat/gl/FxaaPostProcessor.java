package com.kaleblangley.haikalat.gl;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform2f;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;

public final class FxaaPostProcessor implements GlResource {
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
            uniform sampler2D uScene;
            uniform vec2 uInvResolution;

            float luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec3 center = texture(uScene, vUv).rgb;
                vec3 north = texture(uScene, vUv + vec2(0.0, uInvResolution.y)).rgb;
                vec3 south = texture(uScene, vUv - vec2(0.0, uInvResolution.y)).rgb;
                vec3 east  = texture(uScene, vUv + vec2(uInvResolution.x, 0.0)).rgb;
                vec3 west  = texture(uScene, vUv - vec2(uInvResolution.x, 0.0)).rgb;

                float edge = abs(luma(north) - luma(south)) + abs(luma(east) - luma(west));
                float blend = smoothstep(0.08, 0.35, edge);

                vec3 aa = (center * 0.5) + ((north + south + east + west) * 0.125);
                FragColor = vec4(mix(center, aa, blend), 1.0);
            }
            """;

    private final ShaderProgram program;
    private final ScreenQuad quad;
    private boolean closed;

    public FxaaPostProcessor() {
        this.program = ShaderProgram.fromSources(VERTEX, FRAGMENT);
        this.quad = new ScreenQuad();
    }

    public void render(Framebuffer source, int targetWidth, int targetHeight) {
        ensureOpen();
        source.ensureOpen();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_DEPTH_TEST);
        glViewport(0, 0, targetWidth, targetHeight);
        glClear(GL_COLOR_BUFFER_BIT);

        program.use();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, source.colorAttachment());
        glUniform1i(glGetUniformLocation(program.id(), "uScene"), 0);
        glUniform2f(glGetUniformLocation(program.id(), "uInvResolution"), 1.0f / source.width(), 1.0f / source.height());
        quad.draw();
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
        glEnable(GL_DEPTH_TEST);
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

    private void ensureOpen() {
        if (closed) {
            throw new GlException("FXAA post processor is closed");
        }
    }
}
