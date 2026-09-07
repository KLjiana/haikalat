package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Downsamples the current scene device depth into the independent volume history. */
final class OutdoorVolumetricDepthHistoryPass implements GlResource {
    private final ShaderProgram shader = ShaderProgram.fromResource(OutdoorVolumetricDepthHistoryPass.class,
            "/shaders/postprocess/screen-quad.vert",
            "/shaders/postprocess/outdoor-volumetric-depth-history.frag");
    private final ScreenQuad quad = new ScreenQuad();
    private boolean closed;

    void recordIntoCurrentTarget(CommandBuffer commands, int sceneDepthTexture) {
        commands.materialState(BlendMode.OPAQUE, false)
                .enableCullFace(false).enableDepthTest(false).depthMask(false)
                .enableFramebufferSrgb(false).bindShader(shader)
                .bindTexture(0, sceneDepthTexture)
                .setUniformInt(shader, "uSceneDepth", 0)
                .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .materialState(BlendMode.OPAQUE, true);
    }

    @Override public int id() { return shader.id(); }
    @Override public boolean isClosed() { return closed; }

    @Override public void close() {
        if (closed) return;
        quad.close();
        shader.close();
        closed = true;
    }
}
