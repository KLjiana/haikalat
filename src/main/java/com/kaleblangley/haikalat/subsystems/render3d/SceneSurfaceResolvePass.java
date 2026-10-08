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
    private final boolean depthOnly;

    SceneSurfaceResolvePass() {
        this(false);
    }
    SceneSurfaceResolvePass(boolean depthOnly) {
        this.depthOnly=depthOnly;
        this.program = depthOnly ? ShaderProgram.fromSources(vertexSource(), """
                #version 460 core
                uniform sampler2DMS uDepth; uniform int uSamples;
                layout(location=0) out float depth;
                void main(){depth=1;for(int s=0;s<uSamples;s++)depth=min(depth,texelFetch(uDepth,ivec2(gl_FragCoord.xy),s).r);}
                """) : ShaderProgram.fromResource(SceneSurfaceResolvePass.class,
                "/shaders/postprocess/screen-quad.vert",
                "/shaders/postprocess/scene-surface-resolve.frag");
        this.quad = new ScreenQuad();
    }
    private static String vertexSource() {
        try (var input=SceneSurfaceResolvePass.class.getResourceAsStream("/shaders/postprocess/screen-quad.vert")) {
            if (input==null) throw new GlException("missing screen quad shader");
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new GlException("cannot load screen quad shader",failure); }
    }

    void record(CommandBuffer cmd, PassResources resources, int samples) {
        if (closed) throw new GlException("scene surface resolve pass is closed");
        Framebuffer source = resources.framebufferOfPass(PostProcessTargets.SCENE_SURFACE_PASS);
        if (source == null || !depthOnly && source.colorAttachmentCount() < 4
                || !source.depthAttachmentIsTexture()) {
            return;
        }
        if (depthOnly) {
            cmd.enableBlend(false).enableDepthTest(false).enableCullFace(false).enableFramebufferSrgb(false)
                    .bindShader(program).bindTextureMultisample(4,source.depthAttachment())
                    .setUniformInt(program,"uDepth",4).setUniformInt(program,"uSamples",samples)
                    .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES,0,6);
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
