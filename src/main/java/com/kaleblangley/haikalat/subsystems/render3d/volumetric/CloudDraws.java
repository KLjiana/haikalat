package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass.CLOUD_UNIT;
import static com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass.COLOR_UNIT;
import static com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass.DEPTH_UNIT;
import static com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass.HISTORY_CLOUD_UNIT;
import static com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass.HISTORY_STATE_UNIT;
import static com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass.closeSuppressed;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Owns the programs and quad, and records the three stages of a cloud frame. */
final class CloudDraws implements AutoCloseable {
    private static final String VERTEX_RESOURCE = "/shaders/render3d/volumetric/cloud.vert";
    static final String MARCH_RESOURCE = "/shaders/render3d/volumetric/cloud-march.frag";
    private static final String RECONSTRUCT_RESOURCE =
            "/shaders/render3d/volumetric/cloud-reconstruct.frag";
    private static final String COMPOSITE_RESOURCE =
            "/shaders/render3d/volumetric/cloud-composite.frag";
    static final String DENSITY_RESOURCE = "/shaders/render3d/volumetric/cloud-density.glsl";
    static final String LIGHTING_RESOURCE = "/shaders/render3d/volumetric/cloud-lighting.glsl";
    private static final String MODEL_MARKER = "// CLOUD_MODEL_HELPERS";

    private final VolumetricCloudSettings settings;
    private final ShaderProgram marchProgram;
    private final ShaderProgram reconstructProgram;
    private final ShaderProgram compositeProgram;
    private final ScreenQuad screen;

    CloudDraws(VolumetricCloudSettings settings) {
        this.settings = settings;
        ShaderProgram march = null;
        ShaderProgram reconstruct = null;
        ShaderProgram composite = null;
        try {
            String vertex = vertexSource();
            march = ShaderProgram.fromSources(vertex, marchSource());
            reconstruct = ShaderProgram.fromSources(vertex, reconstructSource());
            composite = ShaderProgram.fromSources(vertex, compositeSource());
            screen = new ScreenQuad();
            marchProgram = march;
            reconstructProgram = reconstruct;
            compositeProgram = composite;
        } catch (RuntimeException failure) {
            closeSuppressed(composite, failure);
            closeSuppressed(reconstruct, failure);
            closeSuppressed(march, failure);
            throw failure;
        }
    }

    void recordMarch(CommandBuffer cmd, Framebuffer target, int fullWidth, int fullHeight,
                     int sceneDepthTexture, Matrix4f inverseProjection, Matrix4f inverseView,
                     float timeSeconds) {
        CloudShadowSettings coverage = settings.coverageField();
        // Render the low-resolution march.
        cmd.enableScissor(false)
                .enableBlend(false)
                .enableDepthTest(false)
                .depthMask(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .bindShader(marchProgram)
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .setUniformInt(marchProgram, "uSceneDepth", DEPTH_UNIT)
                .setUniformVec2(marchProgram, "uFullExtent", fullWidth, fullHeight)
                .setUniformVec2(marchProgram, "uLowExtent", target.width(), target.height())
                .setUniformMat4(marchProgram, "uInverseProjection", inverseProjection)
                .setUniformMat4(marchProgram, "uInverseView", inverseView)
                .setUniformFloat(marchProgram, "uLayerBase", settings.baseAltitudeMetres())
                .setUniformFloat(marchProgram, "uLayerTop", settings.topAltitudeMetres())
                .setUniformVec3(marchProgram, "uLightDirection", settings.lightDirectionInternal())
                .setUniformVec3(marchProgram, "uLightColor", settings.lightColorInternal())
                .setUniformFloat(marchProgram, "uShapeDetail", settings.shapeDetail())
                .setUniformFloat(marchProgram, "uCloudBodyStrength", settings.bodyStrength())
                .setUniformFloat(marchProgram, "uMultipleScatteringStrength",
                        settings.multipleScatteringStrength())
                .setUniformVec3(marchProgram, "uSkyLightColor", settings.skyLightColorInternal())
                .setUniformFloat(marchProgram, "uAerialPerspectiveDistance",
                        settings.aerialPerspectiveDistanceMetres())
                .setUniformFloat(marchProgram, "uDensity", settings.density())
                .setUniformFloat(marchProgram, "uAbsorption", settings.absorption())
                .setUniformFloat(marchProgram, "uScattering", settings.scattering())
                .setUniformFloat(marchProgram, "uAnisotropy", settings.anisotropy())
                .setUniformFloat(marchProgram, "uStepSizeMetres", settings.stepSizeMetres())
                .setUniformInt(marchProgram, "uMaximumSteps", settings.maximumSteps())
                .setUniformInt(marchProgram, "uLightSteps", settings.lightSteps())
                .setUniformFloat(marchProgram, "uMaximumLightDistance",
                        settings.maximumLightDistanceMetres())
                .setUniformFloat(marchProgram, "uCloudShadowCoverage", coverage.coverage())
                .setUniformFloat(marchProgram, "uCloudShadowSoftness", coverage.softness())
                .setUniformFloat(marchProgram, "uCloudShadowScale", coverage.scale())
                .setUniformFloat(marchProgram, "uCloudShadowSpeed", coverage.speed())
                .setUniformFloat(marchProgram, "uCloudShadowTime", timeSeconds)
                .bindVertexArray(screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
    }

    void recordReconstruction(CommandBuffer cmd, Framebuffer lowTarget, CloudHistory history,
                              int sceneDepthTexture, Matrix4f view, Matrix4f inverseView,
                              Matrix4f inverseProjection, Matrix4f previousView,
                              Matrix4f previousViewProjection, float exposure, boolean historyAccepted) {
        // Reconstruct to the target extent and write the history candidate.
        Framebuffer target = history.writeFramebuffer();
        cmd.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .bindShader(reconstructProgram)
                .bindTexture(CLOUD_UNIT, lowTarget.colorAttachment())
                .bindTexture(DEPTH_UNIT, sceneDepthTexture)
                .bindTexture(HISTORY_CLOUD_UNIT, history.readCloudTexture())
                .bindTexture(HISTORY_STATE_UNIT, history.readStateTexture())
                .setUniformInt(reconstructProgram, "uCloud", CLOUD_UNIT)
                .setUniformInt(reconstructProgram, "uSceneDepth", DEPTH_UNIT)
                .setUniformInt(reconstructProgram, "uHistoryCloud", HISTORY_CLOUD_UNIT)
                .setUniformInt(reconstructProgram, "uHistoryState", HISTORY_STATE_UNIT)
                .setUniformMat4(reconstructProgram, "uView", view)
                .setUniformMat4(reconstructProgram, "uInverseView", inverseView)
                .setUniformMat4(reconstructProgram, "uInverseProjection", inverseProjection)
                .setUniformMat4(reconstructProgram, "uPreviousView", previousView)
                .setUniformMat4(reconstructProgram, "uPreviousViewProjection", previousViewProjection)
                .setUniformVec2(reconstructProgram, "uFullExtent", target.width(), target.height())
                .setUniformVec2(reconstructProgram, "uLowExtent", lowTarget.width(), lowTarget.height())
                .setUniformFloat(reconstructProgram, "uLayerBase", settings.baseAltitudeMetres())
                .setUniformFloat(reconstructProgram, "uLayerTop", settings.topAltitudeMetres())
                .setUniformFloat(reconstructProgram, "uDepthToleranceMetres",
                        settings.depthToleranceMetres())
                .setUniformFloat(reconstructProgram, "uHistoryBlend", settings.historyBlend())
                .setUniformFloat(reconstructProgram, "uExposure", exposure)
                .setUniformInt(reconstructProgram, "uHistoryValid", historyAccepted ? 1 : 0)
                .bindVertexArray(screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
    }

    void recordComposite(CommandBuffer cmd, Framebuffer target, Framebuffer colorSource,
                         int resolvedCloud) {
        // Composite S + T * scene into the pass output.
        cmd.bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height())
                .bindShader(compositeProgram)
                .bindTexture(CLOUD_UNIT, resolvedCloud)
                .bindTexture(COLOR_UNIT, colorSource.colorAttachment())
                .setUniformInt(compositeProgram, "uCloud", CLOUD_UNIT)
                .setUniformInt(compositeProgram, "uColorSource", COLOR_UNIT)
                .bindVertexArray(screen.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableBlend(false)
                .depthMask(true)
                .enableDepthTest(true)
                .enableScissor(false);
    }

    /** @return the packaged vertex stage; no variant is generated for this pass */
    static String vertexSource() {
        return resource(VERTEX_RESOURCE);
    }

    /** @return the march stage with its density and lighting helpers assembled */
    static String marchSource() {
        String stage = resource(MARCH_RESOURCE);
        int offset = stage.indexOf(MODEL_MARKER);
        if (offset < 0 || stage.indexOf(MODEL_MARKER, offset + MODEL_MARKER.length()) >= 0) {
            throw new IllegalStateException("volumetric cloud march requires exactly one model marker");
        }
        return stage.substring(0, offset) + resource(DENSITY_RESOURCE) + "\n"
                + resource(LIGHTING_RESOURCE) + stage.substring(offset + MODEL_MARKER.length());
    }

    /** @return the packaged reconstruction stage */
    static String reconstructSource() {
        return resource(RECONSTRUCT_RESOURCE);
    }

    /** @return the packaged composite stage */
    static String compositeSource() {
        return resource(COMPOSITE_RESOURCE);
    }

    static String resource(String path) {
        try (var stream = CloudDraws.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing volumetric cloud shader " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot read volumetric cloud shader " + path, failure);
        }
    }

    @Override
    public void close() {
        RuntimeException failure = VolumetricCloudPass.close(screen, null);
        failure = VolumetricCloudPass.close(compositeProgram, failure);
        failure = VolumetricCloudPass.close(reconstructProgram, failure);
        failure = VolumetricCloudPass.close(marchProgram, failure);
        if (failure != null) {
            throw failure;
        }
    }
}
