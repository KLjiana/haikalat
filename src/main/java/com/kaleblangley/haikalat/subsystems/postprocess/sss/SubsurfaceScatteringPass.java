package com.kaleblangley.haikalat.subsystems.postprocess.sss;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import org.joml.Matrix4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Bounded screen-space diffusion for the subsurface-scattering material signal.
 *
 * <p>This is the #33 production consumer of the #32 material input. It reads
 * the resolved linear HDR colour, the shared single-sample device depth and
 * octahedral world normal, and the {@code SUBSURFACE} material mask/rgba
 * channel produced by the shared surface pass. One fixed 16-tap Poisson-disk
 * gather runs inside each pixel's material radius (metres converted to pixels
 * from the reconstructed view position), rejects depth/normal boundaries and
 * leaves non-scattering pixels bit-exact.</p>
 *
 * <p>The pass is stateless: it owns the shader and the screen quad only, and
 * the caller/graph owns the full-size RGBA16F target. There is no history,
 * no private framebuffer and no pixel read-back, so resize is a graph-owned
 * target reallocation and a failed frame cannot leave a partially advanced
 * private state. {@link #close()} is idempotent and releases exactly those two
 * owned objects.</p>
 *
 * <p>The authored radius is an upper bound for the per-pixel mask radius; the
 * shader also applies a compile-time 64-pixel cap, a fixed tap count, a fixed
 * depth/normal tolerance and a fixed specular-highlight protection window. The
 * disabled request never constructs this pass.</p>
 */
public final class SubsurfaceScatteringPass implements GlResource {
    /** Resolved linear HDR colour texture unit. */
    public static final int COLOR_UNIT = 0;
    /** Single-sample shared device depth texture unit. */
    public static final int DEPTH_UNIT = 1;
    /** Single-sample octahedral world normal texture unit. */
    public static final int NORMAL_UNIT = 2;
    /** RGBA16F {@code SUBSURFACE} material mask unit. */
    public static final int SUBSURFACE_UNIT = 3;

    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final SubsurfaceScatteringSettings settings;
    private boolean closed;

    public SubsurfaceScatteringPass(SubsurfaceScatteringSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.screenSpaceActive()) {
            throw new IllegalArgumentException(
                    "SubsurfaceScatteringPass requires active screen-space subsurface scattering");
        }
        ShaderProgram createdProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(SubsurfaceScatteringPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/subsurface-scattering.frag");
            createdQuad = new ScreenQuad();
        } catch (RuntimeException | Error failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        quad = createdQuad;
    }

    public SubsurfaceScatteringSettings settings() {
        return settings;
    }

    /**
     * Records the diffusion pass into the currently bound target.
     *
     * @param colorTexture       resolved linear HDR colour
     * @param depthTexture       single-sample shared device depth
     * @param normalTexture      single-sample octahedral world normal
     * @param subsurfaceTexture  RGBA16F {@code SUBSURFACE} material mask
     * @param inverseProjection  inverse of the projection the depth was rasterised with
     * @param inverseView        inverse view matrix (view space to world space)
     * @param width              output width in pixels
     * @param height             output height in pixels
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int depthTexture, int normalTexture,
                                                 int subsurfaceTexture,
                                                 Matrix4f inverseProjection,
                                                 Matrix4f inverseView,
                                                 int width, int height) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inverseProjection, "inverseProjection");
        Objects.requireNonNull(inverseView, "inverseView");
        if (colorTexture <= 0 || depthTexture <= 0 || normalTexture <= 0
                || subsurfaceTexture <= 0) {
            throw new IllegalArgumentException(
                    "screen-space subsurface scattering requires colour, depth, normal and "
                            + "subsurface textures");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "screen-space subsurface diffusion viewport must be positive");
        }
        cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(COLOR_UNIT, colorTexture)
                .bindTexture(DEPTH_UNIT, depthTexture)
                .bindTexture(NORMAL_UNIT, normalTexture)
                .bindTexture(SUBSURFACE_UNIT, subsurfaceTexture)
                .setUniformInt(program, "uColor", COLOR_UNIT)
                .setUniformInt(program, "uDepth", DEPTH_UNIT)
                .setUniformInt(program, "uNormal", NORMAL_UNIT)
                .setUniformInt(program, "uSubsurface", SUBSURFACE_UNIT)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformMat4(program, "uInverseProjection", inverseProjection)
                .setUniformMat4(program, "uInverseView", inverseView)
                .setUniformFloat(program, "uMaxDistanceMetres", settings.maxDistanceMetres())
                .setUniformFloat(program, "uMaxRadiusMetres",
                        settings.diffusionRadiusMetres());
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
        if (closed) throw new GlException("SubsurfaceScatteringPass is closed");
    }

    private static void closeSuppressed(AutoCloseable resource, Throwable primary) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
