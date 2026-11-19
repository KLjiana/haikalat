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
 * <p>Two shader sources share the same input/output contract: the fixed
 * {@code ssr.frag} program is used whenever {@link AdaptiveSamplingSettings} is
 * disabled (the exact no-op value) and is also the fixed-budget control of the
 * adaptive feature.  The adaptive {@code ssr-adaptive.frag} program is compiled
 * only when the request is active; it measures a per-tile variance and a
 * per-pixel coarse-march residual and spends a bounded step budget derived from
 * them.  Both variants keep the environment/IBL fallback, the confidence floor
 * and the same target, so the surrounding colour chain is unchanged.  An
 * optional {@code SSR_REFLECTION_SIGNAL} compile variant of either source
 * writes only the bounded reflection contribution plus the usable hit
 * confidence in alpha for the temporal denoiser; the default constructors keep
 * the legacy composited output byte-for-byte.</p>
 *
 * <p>Camera facts arrive as matrices: {@code inverseProjection} is the inverse
 * of the raster projection (jitter included when TAA is active, because depth
 * was produced with it), {@code inverseView} the inverse view, {@code view} the
 * view matrix used for ray-depth comparisons and {@code viewProjection} the
 * view-projection consistent with the depth raster (jittered while TAA is
 * active) used to project march samples.  The colour chain never reads a
 * mutable camera.</p>
 */
public final class SsrPass implements GlResource {
    /** Environment cubemap texture unit. */
    public static final int ENVIRONMENT_UNIT = 4;

    /** GLSL marker the signal variant requires in the shared fragment source. */
    private static final String REFLECTION_SIGNAL_MARKER = "SSR_REFLECTION_SIGNAL";
    /** Compile-time prelude that selects the contribution/confidence output. */
    private static final String REFLECTION_SIGNAL_PRELUDE =
            "#define SSR_REFLECTION_SIGNAL 1";

    private final SsrSettings settings;
    private final AdaptiveSamplingSettings adaptiveSettings;
    private final boolean reflectionSignal;
    private final ShaderProgram program;
    private final ShaderProgram adaptiveProgram;
    private final ShaderProgram signalProgram;
    private final ShaderProgram signalAdaptiveProgram;
    private final ScreenQuad quad;
    private boolean closed;

    public SsrPass(SsrSettings settings) {
        this(settings, AdaptiveSamplingSettings.disabled(), false);
    }

    public SsrPass(SsrSettings settings, AdaptiveSamplingSettings adaptiveSettings) {
        this(settings, adaptiveSettings, false);
    }

    /**
     * Creates the reflection pass, optionally selecting the contribution /
     * confidence signal contract instead of the legacy composited colour.
     *
     * <p>The signal variant is compiled with
     * {@code SSR_REFLECTION_SIGNAL}: it writes
     * {@code strength * Fresnel * hitWeight * (hitRadiance - environment)}
     * into RGB and the post-floor usable hit confidence into alpha. Zero
     * confidence therefore means the exact zero contribution, so a caller can
     * composite {@code base + contribution} and keep base-colour regions
     * byte-identical. The pass object otherwise keeps the same inputs, target
     * and resource lifecycle; the legacy constructor pair is unchanged.</p>
     */
    public SsrPass(SsrSettings settings, AdaptiveSamplingSettings adaptiveSettings,
                   boolean reflectionSignal) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.adaptiveSettings = Objects.requireNonNull(adaptiveSettings, "adaptiveSettings");
        this.reflectionSignal = reflectionSignal;
        if (!settings.active()) {
            throw new IllegalArgumentException("SsrPass requires active screen-space reflections");
        }
        ShaderProgram createdProgram = null;
        ShaderProgram createdAdaptiveProgram = null;
        ShaderProgram createdSignalProgram = null;
        ShaderProgram createdSignalAdaptiveProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(SsrPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/ssr.frag");
            if (reflectionSignal) {
                createdSignalProgram = createdProgram.withFragmentPrelude(
                        REFLECTION_SIGNAL_MARKER, REFLECTION_SIGNAL_PRELUDE);
            }
            if (adaptiveSettings.active()) {
                createdAdaptiveProgram = ShaderProgram.fromResource(SsrPass.class,
                        "/shaders/postprocess/screen-quad.vert",
                        "/shaders/postprocess/ssr-adaptive.frag");
                if (reflectionSignal) {
                    createdSignalAdaptiveProgram = createdAdaptiveProgram.withFragmentPrelude(
                            REFLECTION_SIGNAL_MARKER, REFLECTION_SIGNAL_PRELUDE);
                }
            }
            createdQuad = new ScreenQuad();
        } catch (RuntimeException failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdSignalAdaptiveProgram, failure);
            closeSuppressed(createdSignalProgram, failure);
            closeSuppressed(createdAdaptiveProgram, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        adaptiveProgram = createdAdaptiveProgram;
        signalProgram = createdSignalProgram;
        signalAdaptiveProgram = createdSignalAdaptiveProgram;
        quad = createdQuad;
    }

    public SsrSettings settings() {
        return settings;
    }

    /** @return the adaptive-budget request; {@code disabled()} for the fixed path */
    public AdaptiveSamplingSettings adaptiveSettings() {
        return adaptiveSettings;
    }

    /** @return true when the pass writes the contribution/confidence signal */
    public boolean reflectionSignal() {
        return reflectionSignal;
    }

    private ShaderProgram activeProgram() {
        if (adaptiveSettings.active()) {
            return reflectionSignal ? signalAdaptiveProgram : adaptiveProgram;
        }
        return reflectionSignal ? signalProgram : program;
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
     * @param viewProjection   view-projection consistent with the raster projection
     *                         that produced the depth (jittered while TAA is active)
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
        if (exposeBudgetInAlpha && reflectionSignal) {
            throw new IllegalArgumentException(
                    "budget diagnostics cannot share the alpha channel with the reflection "
                            + "confidence signal");
        }
        ShaderProgram active = activeProgram();
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
                            adaptiveSettings.depthJumpScale());
            // The reflection-signal variant does not read the debug alpha, so
            // GLSL drops the uniform from that program; skip the upload there
            // instead of failing on a missing location.
            if (!reflectionSignal) {
                cmd.setUniformInt(active, "uBudgetDebug", exposeBudgetInAlpha ? 1 : 0);
            }
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
        return activeProgram().id();
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
        if (signalProgram != null) {
            try {
                signalProgram.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (signalAdaptiveProgram != null) {
            try {
                signalAdaptiveProgram.close();
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
