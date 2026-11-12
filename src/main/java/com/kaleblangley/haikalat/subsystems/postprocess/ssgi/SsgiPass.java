package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import org.joml.Matrix4f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Bounded screen-space indirect diffuse for the resolved linear HDR colour.
 *
 * <p>This is the #39 production consumer of the shared surface depth, world
 * normal, roughness and albedo channels. One fixed cosine-weighted hemisphere is
 * traced per pixel with a bounded sample and march budget; hits inside the
 * raster add the neighbouring surface radiance, offscreen misses add the
 * receiver's ambient radiance approximation and the result is composited as one
 * extra albedo-weighted diffuse bounce. No existing ambient is subtracted and no
 * AO is reapplied, so the pass cannot double count the forward direct, IBL
 * ambient or AO terms.</p>
 *
 * <p>The pass is stateless: it owns the shader and the screen quad only, and the
 * caller/graph owns the full-size RGBA16F target. There is no history, no
 * private framebuffer and no pixel read-back, so resize is a graph-owned target
 * reallocation and a failed frame cannot leave a partially advanced private
 * state. The borrowed environment source is bound per draw and never closed by
 * this pass; a null source falls back to the settings' uniform ambient
 * approximation. {@link #close()} is idempotent and releases exactly the two
 * owned objects.</p>
 */
public final class SsgiPass implements GlResource {
    /** Resolved linear HDR radiance texture unit. */
    public static final int COLOR_UNIT = 0;
    /** Single-sample shared device depth texture unit. */
    public static final int DEPTH_UNIT = 1;
    /** Single-sample octahedral world normal texture unit. */
    public static final int NORMAL_UNIT = 2;
    /** Single-sample material roughness texture unit. */
    public static final int ROUGHNESS_UNIT = 3;
    /** Single-sample material albedo texture unit. */
    public static final int ALBEDO_UNIT = 4;
    /** Borrowed irradiance environment cubemap unit. */
    public static final int IRRADIANCE_UNIT = 5;

    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final SsgiSettings settings;
    private boolean closed;

    public SsgiPass(SsgiSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("SsgiPass requires an active SSGI request");
        }
        ShaderProgram createdProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(SsgiPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/ssgi.frag");
            createdQuad = new ScreenQuad();
        } catch (RuntimeException | Error failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        quad = createdQuad;
    }

    public SsgiSettings settings() {
        return settings;
    }

    /**
     * Records the indirect-diffuse pass into the currently bound target.
     *
     * @param colorTexture       resolved linear HDR radiance
     * @param depthTexture       single-sample shared device depth
     * @param normalTexture      single-sample octahedral world normal
     * @param roughnessTexture   single-sample material roughness
     * @param albedoTexture      single-sample material albedo
     * @param inverseProjection  inverse of the projection the depth was rasterised with
     * @param projection         that same projection (view space to clip space)
     * @param view               view matrix (world space to view space)
     * @param environment        borrowed irradiance source, or {@code null} for
     *                           the uniform ambient fallback
     * @param width              output width in pixels
     * @param height             output height in pixels
     */
    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int depthTexture, int normalTexture,
                                                 int roughnessTexture, int albedoTexture,
                                                 Matrix4f inverseProjection,
                                                 Matrix4f projection, Matrix4f view,
                                                 SsgiEnvironmentSource environment,
                                                 int width, int height) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inverseProjection, "inverseProjection");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(view, "view");
        if (colorTexture <= 0 || depthTexture <= 0 || normalTexture <= 0
                || roughnessTexture <= 0 || albedoTexture <= 0) {
            throw new IllegalArgumentException(
                    "screen-space indirect diffuse requires colour, depth, normal, roughness and "
                            + "albedo textures");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "screen-space indirect-diffuse viewport must be positive");
        }
        cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(COLOR_UNIT, colorTexture)
                .bindTexture(DEPTH_UNIT, depthTexture)
                .bindTexture(NORMAL_UNIT, normalTexture)
                .bindTexture(ROUGHNESS_UNIT, roughnessTexture)
                .bindTexture(ALBEDO_UNIT, albedoTexture)
                .setUniformInt(program, "uColor", COLOR_UNIT)
                .setUniformInt(program, "uDepth", DEPTH_UNIT)
                .setUniformInt(program, "uNormal", NORMAL_UNIT)
                .setUniformInt(program, "uRoughness", ROUGHNESS_UNIT)
                .setUniformInt(program, "uAlbedo", ALBEDO_UNIT)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformMat4(program, "uInverseProjection", inverseProjection)
                .setUniformMat4(program, "uProjection", projection)
                .setUniformMat4(program, "uView", view)
                .setUniformInt(program, "uSampleCount", settings.sampleCount())
                .setUniformInt(program, "uStepCount", settings.stepCount())
                .setUniformFloat(program, "uMaxDistance", settings.maxDistanceMetres())
                .setUniformFloat(program, "uThickness", settings.thicknessMetres())
                .setUniformFloat(program, "uStrength", settings.strength())
                .setUniformFloat(program, "uMinimumSampleRoughness",
                        settings.minimumSampleRoughness())
                .setUniformFloat(program, "uMaximumOffscreenFraction",
                        settings.maximumOffscreenFraction())
                .setUniformFloat(program, "uEdgeFadePixels", settings.edgeFadePixels())
                .setUniformVec3(program, "uAmbientFallback",
                        new org.joml.Vector3f(settings.ambientFallbackRed(),
                                settings.ambientFallbackGreen(),
                                settings.ambientFallbackBlue()));
        boolean environmentAvailable = environment != null
                && environment.bindIrradiance(cmd, IRRADIANCE_UNIT);
        cmd.setUniformInt(program, "uIrradiance", IRRADIANCE_UNIT)
                .setUniformInt(program, "uEnvironmentAvailable",
                        environmentAvailable ? 1 : 0)
                .setUniformFloat(program, "uEnvironmentIntensity",
                        environmentAvailable ? environment.intensity() : 0.0f)
                .setUniformFloat(program, "uEnvironmentRotation",
                        environmentAvailable ? environment.rotationRadians() : 0.0f);
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
        if (closed) throw new GlException("SsgiPass is closed");
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
