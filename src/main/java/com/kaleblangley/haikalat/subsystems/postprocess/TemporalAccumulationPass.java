package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.TaaResolveInputs;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/**
 * Native TAA resolve plus the legacy low-level color blend retained for
 * external callers.  The legacy path has no depth/velocity inputs and must not
 * be presented as the v0.24.1 temporal resolve.
 */
public final class TemporalAccumulationPass implements GlResource {
    private final ShaderProgram program;
    private final ShaderProgram resolveProgram;
    private final ScreenQuad quad;
    private final int uCurrentLoc;
    private final int uHistoryLoc;
    private final int uWeightLoc;
    private boolean closed;

    public TemporalAccumulationPass() {
        ShaderProgram resolve = null;
        try {
            this.program = ShaderProgram.fromResource(TemporalAccumulationPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/temporal-accumulation.frag");
            resolve = ShaderProgram.fromResource(TemporalAccumulationPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/taa-resolve.frag");
            this.resolveProgram = resolve;
            this.quad = new ScreenQuad();
            this.uCurrentLoc = program.uniformLocation("uCurrent");
            this.uHistoryLoc = program.uniformLocation("uHistory");
            this.uWeightLoc = program.uniformLocation("uHistoryWeight");
        } catch (RuntimeException failure) {
            if (resolve != null) resolve.close();
            throw failure;
        }
    }

    /** Legacy same-UV color blend; not the v0.24.1 temporal resolve. */
    public CommandBuffer record(CommandBuffer cmd, int currentTexId, int historyTexId,
                                 float historyWeight, int targetW, int targetH) {
        cmd.bindFramebuffer(GL_FRAMEBUFFER, 0);
        cmd.viewport(0, 0, targetW, targetH);
        return recordIntoCurrentTarget(cmd, currentTexId, historyTexId, historyWeight);
    }

    /** Legacy same-UV color blend into the currently bound target. */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int currentTexId, int historyTexId,
                                                 float historyWeight) {
        ensureOpen();
        cmd.enableBlend(false);
        cmd.enableDepthTest(false);
        cmd.enableCullFace(false);
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

    /**
     * Records the native resolve into {@code target} (color at attachment 0,
     * current linear depth at attachment 1).  The caller owns history commit.
     */
    public CommandBuffer recordResolve(CommandBuffer cmd, Framebuffer target,
                                       TaaResolveInputs inputs) {
        ensureOpen();
        if (target == null || target.colorAttachmentCount() < 2) {
            throw new IllegalArgumentException(
                    "TAA resolve target must provide color and linear depth attachments");
        }
        cmd.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                // The resolve math runs in linear space; sRGB history targets
                // rely on the hardware to encode on write (a no-op for float).
                .enableFramebufferSrgb(true)
                .bindShader(resolveProgram)
                .bindTexture(0, inputs.currentColorTexture())
                .bindTexture(1, inputs.historyColorTexture())
                .bindTexture(2, inputs.historyDepthTexture())
                .bindTexture(3, inputs.sceneDepthTexture())
                .bindTexture(4, inputs.velocityTexture())
                .bindTexture(5, inputs.previousSurfaceDepthTexture())
                .bindTexture(6, inputs.validityTexture())
                .bindTexture(7, inputs.reactiveTexture())
                .setUniformInt(resolveProgram, "uCurrent", 0)
                .setUniformInt(resolveProgram, "uHistoryColor", 1)
                .setUniformInt(resolveProgram, "uHistoryDepth", 2)
                .setUniformInt(resolveProgram, "uSceneDepth", 3)
                .setUniformInt(resolveProgram, "uVelocity", 4)
                .setUniformInt(resolveProgram, "uPreviousDepth", 5)
                .setUniformInt(resolveProgram, "uValidity", 6)
                .setUniformInt(resolveProgram, "uReactive", 7)
                .setUniformVec2(resolveProgram, "uExtent", inputs.width(), inputs.height())
                .setUniformVec2(resolveProgram, "uJitter",
                        inputs.currentJitterUvX(), inputs.currentJitterUvY())
                .setUniformVec2(resolveProgram, "uPreviousJitter",
                        inputs.previousJitterUvX(), inputs.previousJitterUvY())
                .setUniformMat4(resolveProgram, "uInverseProjection", inputs.inverseProjection())
                .setUniformMat4(resolveProgram, "uPreviousStableViewProjection",
                        inputs.previousStableViewProjection())
                .setUniformMat4(resolveProgram, "uInverseView", inputs.inverseView())
                .setUniformFloat(resolveProgram, "uHistoryWeight", inputs.settings().historyWeight())
                .setUniformFloat(resolveProgram, "uDepthAbsoluteTolerance",
                        inputs.settings().depthAbsoluteTolerance())
                .setUniformFloat(resolveProgram, "uDepthRelativeTolerance",
                        inputs.settings().depthRelativeTolerance())
                .setUniformFloat(resolveProgram, "uReactiveStrength",
                        inputs.settings().reactiveStrength())
                .setUniformInt(resolveProgram, "uHistoryValid", inputs.hasHistory() ? 1 : 0)
                .setUniformInt(resolveProgram, "uVarianceClipping",
                        inputs.settings().varianceClipping() ? 1 : 0)
                .setUniformInt(resolveProgram, "uNeighborhoodRadius",
                        inputs.settings().neighborhoodRadius())
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
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
        resolveProgram.close();
        program.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Temporal accumulation pass is closed");
        }
    }
}
