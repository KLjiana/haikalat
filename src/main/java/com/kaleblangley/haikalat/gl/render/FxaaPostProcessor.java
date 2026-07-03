package com.kaleblangley.haikalat.gl.render;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.GlResource;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.fb.Framebuffer;
import com.kaleblangley.haikalat.gl.material.ShaderProgram;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

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
    private final int uSceneLocation;
    private final int uInvResLocation;
    private boolean closed;

    public FxaaPostProcessor() {
        this.program = ShaderProgram.fromSources(VERTEX, FRAGMENT);
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
