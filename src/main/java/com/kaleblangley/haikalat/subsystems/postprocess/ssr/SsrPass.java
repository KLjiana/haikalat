package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import org.joml.Matrix4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * HDR screen-space reflection pass, recorded after any temporal resolve and
 * before depth of field/exposure/Bloom/tone mapping.
 *
 * <p>The pass reads the resolved linear HDR colour plus the borrowed
 * single-sample device depth, world normal and roughness channels and writes a
 * distinct full-size RGBA16F target.  It never writes the shared depth or
 * surface attachments and owns no history, so the spatial result can later be
 * denoised by the temporal feature without a second signal owner.</p>
 *
 * <p>Camera facts arrive as matrices: {@code inverseProjection} is the inverse
 * of the raster projection (jitter included when TAA is active, because depth
 * was produced with it), {@code inverseView} the inverse view, {@code view} the
 * view matrix used for ray-depth comparisons and {@code viewProjection} the
 * stable (jitter-free) view-projection used to project march samples.  The
 * colour chain never reads a mutable camera.</p>
 */
public final class SsrPass implements GlResource {
    /** Environment cubemap texture unit. */
    public static final int ENVIRONMENT_UNIT = 4;

    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final SsrSettings settings;
    private boolean closed;

    public SsrPass(SsrSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("SsrPass requires active screen-space reflections");
        }
        ShaderProgram createdProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(SsrPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/ssr.frag");
            createdQuad = new ScreenQuad();
        } catch (RuntimeException failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        quad = createdQuad;
    }

    public SsrSettings settings() {
        return settings;
    }

    /**
     * Records the reflection pass into the currently bound target.
     *
     * @param colorTexture     resolved linear HDR colour
     * @param depthTexture     single-sample shared device depth
     * @param normalTexture    single-sample octahedral world normal
     * @param roughnessTexture single-sample material roughness in {@code [0,1]}
     * @param inverseProjection inverse of the projection the depth was rasterised with
     * @param inverseView      inverse view matrix (view space to world space)
     * @param view             view matrix (world space to view space)
     * @param viewProjection   stable view-projection used to project march samples
     * @param environment      borrowed environment/IBL reflection source
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int depthTexture, int normalTexture,
                                                 int roughnessTexture,
                                                 Matrix4f inverseProjection,
                                                 Matrix4f inverseView,
                                                 Matrix4f view,
                                                 Matrix4f viewProjection,
                                                 SsrEnvironmentSource environment,
                                                 int width, int height) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inverseProjection, "inverseProjection");
        Objects.requireNonNull(inverseView, "inverseView");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(viewProjection, "viewProjection");
        Objects.requireNonNull(environment, "environment");
        if (colorTexture <= 0 || depthTexture <= 0 || normalTexture <= 0
                || roughnessTexture <= 0) {
            throw new IllegalArgumentException(
                    "screen-space reflections require colour, depth, normal and roughness textures");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("screen-space reflection viewport must be positive");
        }
        cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, colorTexture)
                .bindTexture(1, depthTexture)
                .bindTexture(2, normalTexture)
                .bindTexture(3, roughnessTexture)
                .setUniformInt(program, "uColor", 0)
                .setUniformInt(program, "uDepth", 1)
                .setUniformInt(program, "uNormal", 2)
                .setUniformInt(program, "uRoughness", 3)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformMat4(program, "uInverseProjection", inverseProjection)
                .setUniformMat4(program, "uInverseView", inverseView)
                .setUniformMat4(program, "uView", view)
                .setUniformMat4(program, "uViewProjection", viewProjection)
                .setUniformInt(program, "uStepCount", settings.maxSteps())
                .setUniformFloat(program, "uMaxDistance", settings.maxDistanceMetres())
                .setUniformFloat(program, "uThickness", settings.thicknessMetres())
                .setUniformFloat(program, "uRoughnessCutoff", settings.roughnessCutoff())
                .setUniformFloat(program, "uMinimumConfidence", settings.minimumConfidence())
                .setUniformFloat(program, "uStrength", settings.strength())
                .setUniformFloat(program, "uEdgeFadePixels", settings.edgeFadePixels());
        float environmentMaxLod = environment.bindPrefilteredSpecular(cmd, ENVIRONMENT_UNIT);
        int available = environmentMaxLod >= 0.0f ? 1 : 0;
        cmd.setUniformInt(program, "uEnvironmentAvailable", available)
                .setUniformInt(program, "uPrefilteredMap", ENVIRONMENT_UNIT);
        if (available != 0) {
            cmd.setUniformFloat(program, "uEnvironmentIntensity", environment.intensity())
                    .setUniformFloat(program, "uEnvironmentRotation",
                            environment.rotationRadians())
                    .setUniformFloat(program, "uPrefilterMaxLod", environmentMaxLod);
        } else {
            cmd.setUniformFloat(program, "uEnvironmentIntensity", 0.0f)
                    .setUniformFloat(program, "uEnvironmentRotation", 0.0f)
                    .setUniformFloat(program, "uPrefilterMaxLod", 0.0f);
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
        if (closed) throw new GlException("SsrPass is closed");
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
