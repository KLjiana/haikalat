package com.kaleblangley.haikalat.subsystems.postprocess.depthoffield;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import org.joml.Matrix4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * HDR depth-of-field gather pass, recorded after the temporal resolve and
 * before auto-exposure/Bloom/tone mapping.
 *
 * <p>The pass reads the resolved HDR colour plus the borrowed single-sample
 * scene depth and writes a distinct full-size RGBA16F target.  It never writes
 * the shared depth attachment.  All sampling is a bounded 16-tap golden-angle
 * gather whose radius is clamped by {@link DepthOfFieldSettings#maxCocPixels()};
 * no history is owned here.</p>
 */
public final class DepthOfFieldPass implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final DepthOfFieldSettings settings;
    private boolean closed;

    public DepthOfFieldPass(DepthOfFieldSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("DepthOfFieldPass requires an active depth of field");
        }
        ShaderProgram createdProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(DepthOfFieldPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/depth-of-field.frag");
            createdQuad = new ScreenQuad();
        } catch (RuntimeException failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        quad = createdQuad;
    }

    public DepthOfFieldSettings settings() {
        return settings;
    }

    /**
     * Records the gather into the currently bound target.
     *
     * @param colorTexture     resolved HDR colour read by the pass
     * @param depthTexture     single-sample device depth read by the pass
     * @param inverseProjection inverse of the frame's projection (jitter does not affect z)
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int depthTexture,
                                                 Matrix4f inverseProjection,
                                                 int width, int height) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inverseProjection, "inverseProjection");
        if (colorTexture <= 0 || depthTexture <= 0) {
            throw new IllegalArgumentException("depth of field requires colour and depth textures");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("depth of field viewport must be positive");
        }
        return cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, colorTexture)
                .bindTexture(1, depthTexture)
                .setUniformInt(program, "uColor", 0)
                .setUniformInt(program, "uDepth", 1)
                .setUniformMat4(program, "uInverseProjection", inverseProjection)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformFloat(program, "uFocusDistance", settings.focusDistance())
                .setUniformFloat(program, "uAperture", settings.aperture())
                .setUniformFloat(program, "uMaxCocPixels", settings.maxCocPixels())
                .bindVertexArray(quad.id())
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
        if (closed) throw new GlException("DepthOfFieldPass is closed");
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
