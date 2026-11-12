package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
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
 * <p>Two shader variants share the same input/output contract.  The fixed
 * {@code ssr.frag} program is used whenever {@link AdaptiveSamplingSettings} is
 * disabled (the exact no-op value) and is also the fixed-budget control of the
 * adaptive feature.  The adaptive {@code ssr-adaptive.frag} program is compiled
 * only when the request is active; it measures a per-tile variance and a
 * per-pixel coarse-march residual and spends a bounded step budget derived from
 * them.  Both variants keep the environment/IBL fallback, the confidence floor
 * and the same target, so the surrounding colour chain is unchanged.</p>
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

    private final SsrSettings settings;
    private final AdaptiveSamplingSettings adaptiveSettings;
    private final ShaderProgram program;
    private final ShaderProgram adaptiveProgram;
    private final ScreenQuad quad;
    private boolean closed;

    public SsrPass(SsrSettings settings) {
        this(settings, AdaptiveSamplingSettings.disabled());
    }

    public SsrPass(SsrSettings settings, AdaptiveSamplingSettings adaptiveSettings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.adaptiveSettings = Objects.requireNonNull(adaptiveSettings, "adaptiveSettings");
        if (!settings.active()) {
            throw new IllegalArgumentException("SsrPass requires active screen-space reflections");
        }
        ShaderProgram createdProgram = null;
        ShaderProgram createdAdaptiveProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(SsrPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/ssr.frag");
            if (adaptiveSettings.active()) {
                createdAdaptiveProgram = ShaderProgram.fromResource(SsrPass.class,
                        "/shaders/postprocess/screen-quad.vert",
                        "/shaders/postprocess/ssr-adaptive.frag");
            }
            createdQuad = new ScreenQuad();
        } catch (RuntimeException failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdAdaptiveProgram, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        adaptiveProgram = createdAdaptiveProgram;
        quad = createdQuad;
    }

    public SsrSettings settings() {
        return settings;
    }

    /** @return the adaptive-budget request; {@code disabled()} for the fixed path */
    public AdaptiveSamplingSettings adaptiveSettings() {
        return adaptiveSettings;
    }

    /**
     * Records the reflection pass into the currently bound target.  The
     * adaptive variant is selected by {@link #adaptiveSettings()}; the input
     * contract and the output target are identical for both variants.
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
        return recordIntoCurrentTarget(cmd, colorTexture, depthTexture, normalTexture,
                roughnessTexture, inverseProjection, inverseView, view, viewProjection,
                environment, width, height, false);
    }

    /**
     * Diagnostic variant used by the real-GL acceptance test: with
     * {@code exposeBudgetInAlpha} the output alpha carries
     * {@code budget / maximumSteps} instead of {@code 1.0}, which lets the test
     * read the allocated budget back without a second target.  Production
     * recording always passes {@code false}; the diagnostic is only available
     * for an active adaptive request.
     */
    CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                          int depthTexture, int normalTexture,
                                          int roughnessTexture,
                                          Matrix4f inverseProjection,
                                          Matrix4f inverseView,
                                          Matrix4f view,
                                          Matrix4f viewProjection,
                                          SsrEnvironmentSource environment,
                                          int width, int height,
                                          boolean exposeBudgetInAlpha) {
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
        if (exposeBudgetInAlpha && !adaptiveSettings.active()) {
            throw new IllegalArgumentException(
                    "budget diagnostics require an active adaptive-sampling request");
        }
        ShaderProgram active = adaptiveSettings.active() ? adaptiveProgram : program;
        cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(active)
                .bindTexture(0, colorTexture)
                .bindTexture(1, depthTexture)
                .bindTexture(2, normalTexture)
                .bindTexture(3, roughnessTexture)
                .setUniformInt(active, "uColor", 0)
                .setUniformInt(active, "uDepth", 1)
                .setUniformInt(active, "uNormal", 2)
                .setUniformInt(active, "uRoughness", 3)
                .setUniformVec2(active, "uViewportSize", width, height)
                .setUniformMat4(active, "uInverseProjection", inverseProjection)
                .setUniformMat4(active, "uInverseView", inverseView)
                .setUniformMat4(active, "uView", view)
                .setUniformMat4(active, "uViewProjection", viewProjection)
                .setUniformFloat(active, "uMaxDistance", settings.maxDistanceMetres())
                .setUniformFloat(active, "uThickness", settings.thicknessMetres())
                .setUniformFloat(active, "uRoughnessCutoff", settings.roughnessCutoff())
                .setUniformFloat(active, "uMinimumConfidence", settings.minimumConfidence())
                .setUniformFloat(active, "uStrength", settings.strength())
                .setUniformFloat(active, "uEdgeFadePixels", settings.edgeFadePixels());
        if (adaptiveSettings.active()) {
            cmd.setUniformInt(active, "uMinimumSteps", adaptiveSettings.minimumSteps())
                    .setUniformInt(active, "uMaximumSteps", adaptiveSettings.maximumSteps())
                    .setUniformFloat(active, "uErrorThreshold",
                            adaptiveSettings.errorThreshold())
                    .setUniformFloat(active, "uTileSizePixels",
                            adaptiveSettings.tileSizePixels())
                    .setUniformFloat(active, "uScreenStepScalePixels",
                            adaptiveSettings.screenStepScalePixels())
                    .setUniformFloat(active, "uDepthJumpScale",
                            adaptiveSettings.depthJumpScale())
                    .setUniformInt(active, "uBudgetDebug", exposeBudgetInAlpha ? 1 : 0);
        } else {
            cmd.setUniformInt(active, "uStepCount", settings.maxSteps());
        }
        float environmentMaxLod = environment.bindPrefilteredSpecular(cmd, ENVIRONMENT_UNIT);
        int available = environmentMaxLod >= 0.0f ? 1 : 0;
        cmd.setUniformInt(active, "uEnvironmentAvailable", available)
                .setUniformInt(active, "uPrefilteredMap", ENVIRONMENT_UNIT);
        if (available != 0) {
            cmd.setUniformFloat(active, "uEnvironmentIntensity", environment.intensity())
                    .setUniformFloat(active, "uEnvironmentRotation",
                            environment.rotationRadians())
                    .setUniformFloat(active, "uPrefilterMaxLod", environmentMaxLod);
        } else {
            cmd.setUniformFloat(active, "uEnvironmentIntensity", 0.0f)
                    .setUniformFloat(active, "uEnvironmentRotation", 0.0f)
                    .setUniformFloat(active, "uPrefilterMaxLod", 0.0f);
        }
        return cmd.bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
    }

    @Override
    public int id() {
        return adaptiveSettings.active() ? adaptiveProgram.id() : program.id();
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
        if (adaptiveProgram != null) {
            try {
                adaptiveProgram.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
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
