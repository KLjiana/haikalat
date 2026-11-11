package com.kaleblangley.haikalat.subsystems.render3d.environment;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT;

/**
 * HDR single-scattering atmosphere sky producer.
 *
 * <p>The pass precomputes a transmittance LUT (upper hemisphere) and a
 * sun-relative sky-view LUT with two compute shaders, then draws a full-screen
 * sky into the currently bound HDR target.  It owns its three programs, its
 * screen quad and both LUT textures; it never binds or owns a framebuffer.  The
 * calling pass provides the target and viewport, exactly like other screen-quad
 * producers.</p>
 *
 * <p>Both LUTs are rebuilt every recorded frame.  The tables are small
 * (256x64 and 32x32 by default, about 136 KiB) and rebuilding removes any
 * stale-table state when the sun or the observer altitude moves.  LUT extent
 * changes reallocate storage through {@link #rebuild(AtmosphereSettings)};
 * recording with different extents without calling rebuild is rejected before
 * any GPU command is recorded.</p>
 *
 * <p>The pass replaces the stylized-sky and IBL-background paths rather than
 * composing with them.  It does not integrate scene fog: distance fog and
 * volumetric fog remain independent media and must not add a second
 * Rayleigh/Mie in-scattering term.  It writes no depth and performs no scene
 * depth test, so it is a background producer recorded before opaque geometry.</p>
 */
public final class AtmosphereSkyPass implements GlResource {
    private static final String SHADER_ROOT = "/shaders/render3d/atmosphere/";
    private static final String COMMON_SHADER = SHADER_ROOT + "atmosphere-common.glsl";
    private static final String TRANSMITTANCE_SHADER = SHADER_ROOT + "atmosphere-transmittance.comp";
    private static final String SKY_VIEW_SHADER = SHADER_ROOT + "atmosphere-sky-view.comp";
    private static final String SKY_SHADER = SHADER_ROOT + "atmosphere-sky.frag";
    private static final String SCREEN_QUAD_VERTEX = "/shaders/postprocess/screen-quad.vert";

    private final ShaderProgram transmittanceProgram;
    private final ShaderProgram skyViewProgram;
    private final ShaderProgram skyProgram;
    private final ScreenQuad quad;
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseViewRotation = new Matrix4f();
    private final Vector3f rayleighScatteringUnit = new Vector3f();
    private final Vector3f sunDirectionLocal = new Vector3f();

    private Texture2D transmittanceLut;
    private Texture2D skyViewLut;
    private int transmittanceLutWidth;
    private int transmittanceLutHeight;
    private int skyViewLutWidth;
    private int skyViewLutHeight;
    private boolean closed;

    public AtmosphereSkyPass(AtmosphereSettings settings) {
        Objects.requireNonNull(settings, "settings");
        ShaderProgram createdTransmittance = null;
        ShaderProgram createdSkyView = null;
        ShaderProgram createdSky = null;
        ScreenQuad createdQuad = null;
        Texture2D createdTransmittanceLut = null;
        Texture2D createdSkyViewLut = null;
        try {
            createdTransmittance = ShaderProgram.fromComputeSource(computeSource(TRANSMITTANCE_SHADER));
            createdSkyView = ShaderProgram.fromComputeSource(computeSource(SKY_VIEW_SHADER));
            createdSky = ShaderProgram.builder()
                    .resource(AtmosphereSkyPass.class, ShaderStage.VERTEX, SCREEN_QUAD_VERTEX)
                    .stage(ShaderStage.FRAGMENT, fragmentSource(), SKY_SHADER)
                    .link();
            createdQuad = new ScreenQuad();
            createdTransmittanceLut = createLut(settings.transmittanceLutWidth(),
                    settings.transmittanceLutHeight(), GL_CLAMP_TO_EDGE);
            createdSkyViewLut = createLut(settings.skyViewLutWidth(), settings.skyViewLutHeight(),
                    GL_REPEAT);
            transmittanceProgram = createdTransmittance;
            skyViewProgram = createdSkyView;
            skyProgram = createdSky;
            quad = createdQuad;
            transmittanceLut = createdTransmittanceLut;
            skyViewLut = createdSkyViewLut;
            transmittanceLutWidth = settings.transmittanceLutWidth();
            transmittanceLutHeight = settings.transmittanceLutHeight();
            skyViewLutWidth = settings.skyViewLutWidth();
            skyViewLutHeight = settings.skyViewLutHeight();
        } catch (RuntimeException failure) {
            closeSuppressed(createdSkyViewLut, failure);
            closeSuppressed(createdTransmittanceLut, failure);
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdSky, failure);
            closeSuppressed(createdSkyView, failure);
            closeSuppressed(createdTransmittance, failure);
            throw failure;
        }
    }

    /**
     * Records LUT generation and the full-screen sky into the currently bound
     * target.  The caller owns the target, viewport and depth state baseline.
     *
     * @param inverseProjection  inverse of the projection used for the viewport
     * @param inverseViewRotation inverse rotation of the view matrix (translation removed)
     * @param observerAltitudeMeters altitude above the configured planet surface
     */
    public void record(CommandBuffer cmd, Matrix4f inverseProjection,
                       Matrix4f inverseViewRotation, float observerAltitudeMeters,
                       AtmosphereSettings settings) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(inverseProjection, "inverseProjection");
        Objects.requireNonNull(inverseViewRotation, "inverseViewRotation");
        Objects.requireNonNull(settings, "settings");
        if (!inverseProjection.isFinite() || !inverseViewRotation.isFinite()) {
            throw new IllegalArgumentException("atmosphere view matrices must be finite");
        }
        if (!Float.isFinite(observerAltitudeMeters) || observerAltitudeMeters < 0.0f
                || observerAltitudeMeters > settings.atmosphereThicknessMeters()) {
            throw new IllegalArgumentException(
                    "observerAltitudeMeters must be finite and within the atmosphere thickness");
        }
        if (settings.transmittanceLutWidth() != transmittanceLutWidth
                || settings.transmittanceLutHeight() != transmittanceLutHeight
                || settings.skyViewLutWidth() != skyViewLutWidth
                || settings.skyViewLutHeight() != skyViewLutHeight) {
            throw new IllegalArgumentException(
                    "atmosphere LUT extents changed; call rebuild(settings) before recording");
        }

        float planetRadius = settings.planetRadiusMeters();
        float topRadiusUnit = settings.atmosphereTopRadiusMeters() / planetRadius;
        float observerRadiusUnit = (planetRadius + observerAltitudeMeters) / planetRadius;
        float rayleighScaleHeightUnit = settings.rayleighScaleHeightMeters() / planetRadius;
        float mieScaleHeightUnit = settings.mieScaleHeightMeters() / planetRadius;
        float mieScatteringUnit = settings.mieScatteringPerMeter() * planetRadius;
        float mieExtinctionUnit = (settings.mieScatteringPerMeter()
                + settings.mieAbsorptionPerMeter()) * planetRadius;
        rayleighScatteringUnit.set(settings.rayleighScatteringInternal()).mul(planetRadius);

        int transmittanceGroupsX = (transmittanceLutWidth + 7) / 8;
        int transmittanceGroupsY = (transmittanceLutHeight + 7) / 8;
        int skyViewGroupsX = (skyViewLutWidth + 7) / 8;
        int skyViewGroupsY = (skyViewLutHeight + 7) / 8;
        int imageBarrier = GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT;

        cmd.bindShader(transmittanceProgram)
                .bindImage(transmittanceLut, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .setUniformFloat(transmittanceProgram, "uTopRadius", topRadiusUnit)
                .setUniformVec3(transmittanceProgram, "uRayleighScatteringUnit",
                        rayleighScatteringUnit)
                .setUniformFloat(transmittanceProgram, "uRayleighScaleHeightUnit",
                        rayleighScaleHeightUnit)
                .setUniformFloat(transmittanceProgram, "uMieExtinctionUnit", mieExtinctionUnit)
                .setUniformFloat(transmittanceProgram, "uMieScaleHeightUnit", mieScaleHeightUnit)
                .setUniformInt(transmittanceProgram, "uSampleCount",
                        settings.transmittanceSamples())
                .dispatchCompute(transmittanceGroupsX, transmittanceGroupsY, 1)
                .memoryBarrier(imageBarrier);

        Vector3f lightDirection = settings.sunDirectionInternal();
        float sunHeight = Math.max(-1.0f, Math.min(1.0f, -lightDirection.y));
        float sunHorizontal = (float) Math.sqrt(Math.max(0.0, 1.0 - sunHeight * sunHeight));
        sunDirectionLocal.set(0.0f, sunHeight, sunHorizontal);

        cmd.bindShader(skyViewProgram)
                .bindImage(skyViewLut, 0, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                .bindTexture(0, transmittanceLut)
                .setUniformInt(skyViewProgram, "uTransmittanceLut", 0)
                .setUniformFloat(skyViewProgram, "uTopRadius", topRadiusUnit)
                .setUniformFloat(skyViewProgram, "uObserverRadius", observerRadiusUnit)
                .setUniformVec3(skyViewProgram, "uRayleighScatteringUnit",
                        rayleighScatteringUnit)
                .setUniformFloat(skyViewProgram, "uRayleighScaleHeightUnit",
                        rayleighScaleHeightUnit)
                .setUniformFloat(skyViewProgram, "uMieScatteringUnit", mieScatteringUnit)
                .setUniformFloat(skyViewProgram, "uMieExtinctionUnit", mieExtinctionUnit)
                .setUniformFloat(skyViewProgram, "uMieScaleHeightUnit", mieScaleHeightUnit)
                .setUniformFloat(skyViewProgram, "uMieAnisotropy", settings.mieAnisotropy())
                .setUniformVec3(skyViewProgram, "uSunDirectionLocal", sunDirectionLocal)
                .setUniformInt(skyViewProgram, "uSampleCount", settings.skyViewSamples())
                .dispatchCompute(skyViewGroupsX, skyViewGroupsY, 1)
                .memoryBarrier(imageBarrier);

        cmd.bindShader(skyProgram)
                .bindTexture(0, skyViewLut)
                .setUniformInt(skyProgram, "uSkyViewLut", 0)
                .setUniformMat4(skyProgram, "uInverseProjection", inverseProjection)
                .setUniformMat4(skyProgram, "uInverseViewRotation", inverseViewRotation)
                .setUniformVec3(skyProgram, "uSunDirection", lightDirection)
                .setUniformFloat(skyProgram, "uSunIntensity",
                        settings.sunIntensityWattsPerSquareMetre())
                .setUniformFloat(skyProgram, "uExposure", settings.exposure())
                .setUniformFloat(skyProgram, "uSunAngularRadius",
                        settings.sunAngularRadiusRadians())
                .setUniformFloat(skyProgram, "uSunDiscIntensity", settings.sunDiscIntensity())
                .enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .depthMask(false)
                .enableFramebufferSrgb(false)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true)
                .depthMask(true)
                .enableBlend(false);
    }

    /** Convenience overload for callers that still own a {@link Camera}. */
    public void record(CommandBuffer cmd, Camera camera, int width, int height,
                       float observerAltitudeMeters, AtmosphereSettings settings) {
        Objects.requireNonNull(camera, "camera");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("atmosphere viewport must be positive");
        }
        inverseProjection.set(CameraProjection.stable(camera, width, height, new Matrix4f()))
                .invert();
        inverseViewRotation.set(camera.getViewMatrix()).m30(0.0f).m31(0.0f).m32(0.0f).invert();
        record(cmd, inverseProjection, inverseViewRotation, observerAltitudeMeters, settings);
    }

    /**
     * Reallocates both LUT textures for new extents at a frame boundary.
     *
     * <p>The candidate textures are fully created before the old pair is
     * retired.  A creation failure closes the candidate and leaves the active
     * pair and extents untouched.  A failure while retiring the old pair keeps
     * the new pair active and is reported to the caller.  No-op when the extents
     * are unchanged.</p>
     */
    public void rebuild(AtmosphereSettings settings) {
        ensureOpen();
        Objects.requireNonNull(settings, "settings");
        if (settings.transmittanceLutWidth() == transmittanceLutWidth
                && settings.transmittanceLutHeight() == transmittanceLutHeight
                && settings.skyViewLutWidth() == skyViewLutWidth
                && settings.skyViewLutHeight() == skyViewLutHeight) {
            return;
        }
        Texture2D candidateTransmittance = null;
        Texture2D candidateSkyView = null;
        try {
            candidateTransmittance = createLut(settings.transmittanceLutWidth(),
                    settings.transmittanceLutHeight(), GL_CLAMP_TO_EDGE);
            candidateSkyView = createLut(settings.skyViewLutWidth(), settings.skyViewLutHeight(),
                    GL_REPEAT);
        } catch (RuntimeException failure) {
            closeSuppressed(candidateSkyView, failure);
            closeSuppressed(candidateTransmittance, failure);
            throw failure;
        }

        Texture2D retiredTransmittance = transmittanceLut;
        Texture2D retiredSkyView = skyViewLut;
        transmittanceLut = candidateTransmittance;
        skyViewLut = candidateSkyView;
        transmittanceLutWidth = settings.transmittanceLutWidth();
        transmittanceLutHeight = settings.transmittanceLutHeight();
        skyViewLutWidth = settings.skyViewLutWidth();
        skyViewLutHeight = settings.skyViewLutHeight();

        RuntimeException retirementFailure = null;
        try {
            retiredSkyView.close();
        } catch (RuntimeException failure) {
            retirementFailure = failure;
        }
        try {
            retiredTransmittance.close();
        } catch (RuntimeException failure) {
            if (retirementFailure == null) retirementFailure = failure;
            else retirementFailure.addSuppressed(failure);
        }
        if (retirementFailure != null) throw retirementFailure;
    }

    int transmittanceLutId() {
        ensureOpen();
        return transmittanceLut.id();
    }

    int skyViewLutId() {
        ensureOpen();
        return skyViewLut.id();
    }

    int transmittanceLutWidth() { return transmittanceLutWidth; }
    int transmittanceLutHeight() { return transmittanceLutHeight; }
    int skyViewLutWidth() { return skyViewLutWidth; }
    int skyViewLutHeight() { return skyViewLutHeight; }

    @Override
    public int id() {
        return skyProgram.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            skyViewLut.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            transmittanceLut.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            quad.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            skyProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            skyViewProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        try {
            transmittanceProgram.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }

    private static Texture2D createLut(int width, int height, int wrapT) {
        Texture2D texture = Texture2D.createEmpty(width, height, GL_RGBA16F);
        try {
            texture.setWrap(GL_CLAMP_TO_EDGE, wrapT);
            return texture;
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
    }

    private static String computeSource(String shaderPath) {
        return "#version 460 core\n" + readResource(COMMON_SHADER) + "\n"
                + readResource(shaderPath);
    }

    private static String fragmentSource() {
        return "#version 460 core\n" + readResource(COMMON_SHADER) + "\n"
                + readResource(SKY_SHADER);
    }

    private static String readResource(String path) {
        try (var stream = AtmosphereSkyPass.class.getResourceAsStream(path)) {
            if (stream == null) throw new GlException("missing atmosphere shader " + path);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new GlException("cannot read atmosphere shader " + path, failure);
        }
    }

    private void ensureOpen() {
        if (closed) throw new GlException("atmosphere sky pass is closed");
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
