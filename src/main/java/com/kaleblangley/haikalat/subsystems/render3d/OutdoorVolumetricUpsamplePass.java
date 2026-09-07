package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Depth-aware bilateral reconstruction of the low-resolution volume result. */
final class OutdoorVolumetricUpsamplePass implements GlResource {
    private final ShaderProgram shader = ShaderProgram.fromResource(OutdoorVolumetricUpsamplePass.class,
            "/shaders/postprocess/screen-quad.vert",
            "/shaders/postprocess/outdoor-volumetric-upsample.frag");
    private final ScreenQuad quad = new ScreenQuad();
    private boolean closed;

    void recordIntoCurrentTarget(CommandBuffer cmd, int sceneColor, int volumeColor,
                                 int sceneDepth, int volumeWidth, int volumeHeight) {
        cmd.materialState(BlendMode.OPAQUE, false)
                .enableCullFace(false).enableDepthTest(false).depthMask(false)
                .enableFramebufferSrgb(false).bindShader(shader)
                .bindTexture(0, sceneColor).bindTexture(1, volumeColor).bindTexture(2, sceneDepth)
                .setUniformInt(shader, "uSceneColor", 0)
                .setUniformInt(shader, "uVolumeColor", 1)
                .setUniformInt(shader, "uSceneDepth", 2)
                .setUniformVec2(shader, "uVolumeTexel", 1.0f / Math.max(1, volumeWidth),
                        1.0f / Math.max(1, volumeHeight))
                .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .materialState(BlendMode.OPAQUE, true);
    }

    @Override public int id() { return shader.id(); }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() {
        if (closed) return;
        quad.close(); shader.close(); closed = true;
    }
}
