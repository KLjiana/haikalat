package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;

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
    SceneSurfaceResolvePass(SceneSurfaceLayout layout) {
        String path = "/shaders/postprocess/scene-surface-resolve.frag";
        program = ShaderProgram.builder().resource(SceneSurfaceResolvePass.class, ShaderStage.VERTEX,
                "/shaders/postprocess/screen-quad.vert")
                .stage(ShaderStage.FRAGMENT, SceneSurfacePass.fragmentSource(path, layout.resolveShaderDefines()), path)
                .link();
        try {
            quad = new ScreenQuad();
        } catch (RuntimeException | Error failure) {
            try { program.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void record(CommandBuffer cmd, PassResources resources, SceneSurfaceInputs inputs) {
        if (closed) throw new GlException("scene surface resolve pass is closed");
        var layout = inputs.layout();
        var frame = inputs.frame();
        cmd.enableBlend(false).enableDepthTest(false).enableCullFace(false)
                .enableFramebufferSrgb(false).bindShader(program);
        for (var output : layout.resolve()) {
            var input = layout.raw(output.channel());
            int texture = frame.require(resources, input);
            String uniform = switch (output.channel()) {
                case DEPTH -> "uDepth";
                case NORMAL -> "uNormal";
                case VELOCITY -> "uVelocity";
                case PREVIOUS_SURFACE_DEPTH -> "uPreviousDepth";
                case VALIDITY -> "uValidity";
                default -> throw new IllegalStateException("no resolve input for " + output.channel());
            };
            cmd.bindTextureMultisample(output.colorSlot(), texture)
                    .setUniformInt(program, uniform, output.colorSlot());
        }
        cmd.setUniformInt(program, "uSamples", layout.samples())
                .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6);
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
