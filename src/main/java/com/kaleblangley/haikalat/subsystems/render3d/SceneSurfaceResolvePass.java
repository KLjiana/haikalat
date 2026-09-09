package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Resolves the multisampled shared scene surface into single-sample depth and
 * surface channels.  For every pixel the device-depth-nearest covered sample is
 * chosen, and that sample's normal, velocity, previous depth, validity and
 * reactive values are carried over together (planning doc section 14.3).
 */
final class SceneSurfaceResolvePass implements AutoCloseable {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private boolean closed;

    SceneSurfaceResolvePass() {
        this.program = ShaderProgram.fromResource(SceneSurfaceResolvePass.class,
                "/shaders/postprocess/screen-quad.vert",
                "/shaders/postprocess/scene-surface-resolve.frag");
        this.quad = new ScreenQuad();
    }

    void record(CommandBuffer cmd, PassResources resources, int samples) {
        if (closed) throw new GlException("scene surface resolve pass is closed");
        Framebuffer source = resources.framebufferOfPass(PostProcessTargets.SCENE_SURFACE_PASS);
        if (source == null || source.colorAttachmentCount() < 4
                || !source.depthAttachmentIsTexture()) {
            return;
        }
        cmd.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTextureMultisample(0, source.colorAttachment(0))
                .bindTextureMultisample(1, source.colorAttachment(1))
                .bindTextureMultisample(2, source.colorAttachment(2))
                .bindTextureMultisample(3, source.colorAttachment(3))
                .bindTextureMultisample(4, source.depthAttachment())
                .setUniformInt(program, "uNormal", 0)
                .setUniformInt(program, "uVelocity", 1)
                .setUniformInt(program, "uPreviousDepth", 2)
                .setUniformInt(program, "uValidity", 3)
                .setUniformInt(program, "uDepth", 4)
                .setUniformInt(program, "uSamples", samples)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            quad.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            program.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
}
