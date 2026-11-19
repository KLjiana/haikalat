package com.kaleblangley.haikalat.subsystems.postprocess.distortion;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortion;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortionSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import org.joml.Vector4f;

import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Screen-space distortion pass over the composited HDR colour.
 *
 * <p>The pass records after the VFX composite pass.  It reads the composite
 * colour attachment and the scene depth texture written by that pass, and
 * writes a different attachment, so the image being sampled is never the image
 * currently being written.  Contributions are pure CPU data; this owner holds
 * only the shader and the quad and is created only when the effect is active.</p>
 */
public final class ScreenDistortionPass implements GlResource {
    private static final int MAXIMUM_SLOTS = ScreenDistortion.MAXIMUM_CONTRIBUTIONS;

    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final ScreenDistortionSettings settings;
    private final Vector4f[] shapes = new Vector4f[MAXIMUM_SLOTS];
    private final Vector4f[] offsets = new Vector4f[MAXIMUM_SLOTS];
    private final Vector4f[] depths = new Vector4f[MAXIMUM_SLOTS];
    private final String[] shapeUniforms = new String[MAXIMUM_SLOTS];
    private final String[] offsetUniforms = new String[MAXIMUM_SLOTS];
    private final String[] depthUniforms = new String[MAXIMUM_SLOTS];
    private boolean closed;

    public ScreenDistortionPass(ScreenDistortionSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException(
                    "ScreenDistortionPass requires an active distortion");
        }
        for (int index = 0; index < MAXIMUM_SLOTS; index++) {
            shapes[index] = new Vector4f();
            offsets[index] = new Vector4f();
            depths[index] = new Vector4f();
            shapeUniforms[index] = "uDistortionShape[" + index + "]";
            offsetUniforms[index] = "uDistortionOffset[" + index + "]";
            depthUniforms[index] = "uDistortionDepth[" + index + "]";
        }
        ShaderProgram createdProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(ScreenDistortionPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/screen-distortion.frag");
            createdQuad = new ScreenQuad();
        } catch (RuntimeException failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        quad = createdQuad;
    }

    public ScreenDistortionSettings settings() {
        return settings;
    }

    /**
     * Records one full-screen distortion draw into the currently bound target.
     *
     * @param cmd                command buffer on the GPU owner thread
     * @param colorTexture       composited HDR colour attachment (read only)
     * @param depthTexture       scene depth written by the VFX composite (read only)
     * @param contributions      this frame's pure-data contributions
     * @param width              target width in pixels
     * @param height             target height in pixels
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int depthTexture,
                                                 List<ScreenDistortion> contributions,
                                                 int width, int height) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(contributions, "contributions");
        if (colorTexture <= 0 || depthTexture <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "screen distortion input and viewport must be valid");
        }
        List<ScreenDistortion> ordered = ScreenDistortion.orderForComposite(
                contributions, settings.maximumContributions());
        cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, colorTexture)
                .bindTexture(1, depthTexture)
                .setUniformInt(program, "uColor", 0)
                .setUniformInt(program, "uSceneDepth", 1)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformFloat(program, "uMaximumOffsetUv", settings.maximumOffsetUv())
                .setUniformFloat(program, "uEdgeFeatherUv", settings.edgeFeatherUv())
                .setUniformInt(program, "uContributionCount", ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            ScreenDistortion contribution = ordered.get(index);
            shapes[index].set(contribution.centerUv().x, contribution.centerUv().y,
                    contribution.extentUv().x, contribution.extentUv().y);
            offsets[index].set(contribution.uvOffset().x, contribution.uvOffset().y,
                    contribution.mask(), contribution.effectDepth());
            depths[index].set(contribution.depthThickness(), contribution.haze(),
                    contribution.phase(), contribution.ring());
            cmd.setUniformVec4(program, shapeUniforms[index], shapes[index])
                    .setUniformVec4(program, offsetUniforms[index], offsets[index])
                    .setUniformVec4(program, depthUniforms[index], depths[index]);
        }
        return cmd.bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
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
        if (closed) return;
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
        closed = true;
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("ScreenDistortionPass is closed");
    }

    private static void closeSuppressed(AutoCloseable resource, RuntimeException primary) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
