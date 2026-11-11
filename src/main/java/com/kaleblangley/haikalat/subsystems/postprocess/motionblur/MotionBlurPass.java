package com.kaleblangley.haikalat.subsystems.postprocess.motionblur;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * HDR screen-space motion-blur gather, recorded after the temporal resolve and
 * depth of field, before auto-exposure/Bloom/tone mapping.
 *
 * <p>The pass reads the resolved HDR colour plus the borrowed velocity and
 * validity channels and writes a distinct full-size RGBA16F target.  It owns no
 * history: the previous shot is never sampled, so a camera cut cannot smear.
 * Every tap is bounded by {@link MotionBlurSettings#maxRadiusPixels()}; the
 * accumulated motion is scaled by {@link MotionBlurSettings#shutterFraction()}
 * and the current-frame raster jitter is removed before reconstruction.</p>
 */
public final class MotionBlurPass implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final MotionBlurSettings settings;
    private boolean closed;

    public MotionBlurPass(MotionBlurSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("MotionBlurPass requires active motion blur");
        }
        ShaderProgram createdProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(MotionBlurPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/motion-blur.frag");
            createdQuad = new ScreenQuad();
        } catch (RuntimeException failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        quad = createdQuad;
    }

    public MotionBlurSettings settings() {
        return settings;
    }

    /**
     * Records the gather into the currently bound target.
     *
     * @param colorTexture    resolved HDR colour read by the pass
     * @param velocityTexture shared {@code previousStableUv - currentStableUv} velocity
     * @param validityTexture shared per-pixel motion validity
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int velocityTexture, int validityTexture,
                                                 int width, int height) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        if (colorTexture <= 0 || velocityTexture <= 0 || validityTexture <= 0) {
            throw new IllegalArgumentException(
                    "motion blur requires colour, velocity and validity textures");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("motion blur viewport must be positive");
        }
        return cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, colorTexture)
                .bindTexture(1, velocityTexture)
                .bindTexture(2, validityTexture)
                .setUniformInt(program, "uColor", 0)
                .setUniformInt(program, "uVelocity", 1)
                .setUniformInt(program, "uValidity", 2)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformFloat(program, "uShutterFraction", settings.shutterFraction())
                .setUniformFloat(program, "uMaxRadiusPixels", settings.maxRadiusPixels())
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
        if (closed) throw new GlException("MotionBlurPass is closed");
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
