package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Independent scattering/transmittance history with depth reject and clamp. */
final class OutdoorVolumetricTemporalPass implements GlResource {
    private final ShaderProgram shader = ShaderProgram.fromResource(OutdoorVolumetricTemporalPass.class,
            "/shaders/postprocess/screen-quad.vert",
            "/shaders/postprocess/outdoor-volumetric-temporal.frag");
    private final ScreenQuad quad = new ScreenQuad();
    private boolean closed;

    void recordIntoCurrentTarget(CommandBuffer cmd, int currentTexture, int historyTexture,
                                 int depthTexture, int historyDepthTexture,
                                 float historyWeight, float depthReject,
                                 Matrix4f inverseViewProjection, Matrix4f previousViewProjection,
                                 boolean historyValid) {
        cmd.materialState(BlendMode.OPAQUE, false)
                .enableCullFace(false).enableDepthTest(false).depthMask(false)
                .enableFramebufferSrgb(false).bindShader(shader)
                .bindTexture(0, currentTexture).bindTexture(1, Math.max(0, historyTexture))
                .bindTexture(2, depthTexture).bindTexture(3, Math.max(0, historyDepthTexture))
                .setUniformInt(shader, "uCurrent", 0)
                .setUniformInt(shader, "uHistory", 1)
                .setUniformInt(shader, "uDepth", 2)
                .setUniformInt(shader, "uHistoryDepth", 3)
                .setUniformFloat(shader, "uHistoryWeight", historyValid ? historyWeight : 0.0f)
                .setUniformFloat(shader, "uDepthRejectThreshold", depthReject)
                .setUniformMat4(shader, "uInverseViewProjection", inverseViewProjection)
                .setUniformMat4(shader, "uPreviousViewProjection", previousViewProjection)
                .setUniformInt(shader, "uHistoryValid", historyValid ? 1 : 0)
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
