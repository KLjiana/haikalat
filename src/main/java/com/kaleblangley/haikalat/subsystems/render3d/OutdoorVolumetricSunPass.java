package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/**
 * Formal HDR outdoor sunlight volume pass.  It is intentionally independent
 * from the older bounded spotlight experiment: the pass consumes the current
 * scene depth and the current directional shadow atlas and replaces the HDR
 * scene colour with {@code scene*T + singleScatter}.
 */
final class OutdoorVolumetricSunPass implements GlResource {
    private static final int MAX_CASCADES = 4;
    private static final int MAX_VOLUMES = OutdoorEnvironmentSettings.MAX_LOCAL_VOLUMES;
    private static final int SCENE_COLOR_UNIT = 0;
    private static final int SCENE_DEPTH_UNIT = 1;
    private static final int SHADOW_UNIT = 2;

    private final ShaderProgram shader = ShaderProgram.fromResource(OutdoorVolumetricSunPass.class,
            "/shaders/postprocess/screen-quad.vert",
            "/shaders/postprocess/outdoor-volumetric-sun.frag");
    private final ScreenQuad quad = new ScreenQuad();
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f emptyCascadeMatrix = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f sunDirection = new Vector3f();
    private final Vector3f sunColor = new Vector3f();
    private final Vector3f environmentColor = new Vector3f();
    private final Vector3f globalFogColor = new Vector3f();
    private final Vector3f zero = new Vector3f();
    private long frames;
    private boolean closed;

    void recordIntoCurrentTarget(CommandBuffer cmd, int sceneColorTexture, int sceneDepthTexture,
                                 int shadowTexture, Camera camera,
                                 OutdoorEnvironmentSettings settings,
                                 List<Matrix4f> cascadeMatrices, float[] cascadeSplits,
                                 int frameIndex, int frameWidth, int frameHeight) {
        ensureOpen();
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(camera, "camera");
        OutdoorEnvironmentSettings environment = Objects.requireNonNull(settings, "settings");
        VolumetricSunSettings volume = environment.volumetricSun();
        if (!volume.enabled()) return;
        if (sceneDepthTexture == 0) {
            throw new IllegalArgumentException("outdoor volume requires a scene depth texture");
        }

        // The volume target is downsampled, but the camera projection belongs
        // to the full presentation frame.  Using the low-resolution target's
        // aspect (or a 1x1 fallback) bends the reconstruction rays whenever
        // the window is not square.
        CameraProjection.stable(camera,
                Math.max(1, frameWidth), Math.max(1, frameHeight), projection);
        camera.getViewMatrix(view);
        inverseViewProjection.set(projection).mul(view).invert();
        sunDirection.set(environment.sky().sunDirectionInternal());
        sunColor.set(environment.sky().sunColorInternal());
        cameraPosition.set(camera.positionInternal());
        int cascadeCount = Math.min(MAX_CASCADES, cascadeMatrices == null ? 0 : cascadeMatrices.size());

        FogSettings globalFog = environment.globalFog();
        float globalDistanceDensity = globalFog.enabled() ? globalFog.distanceDensity() : 0.0f;
        float globalHeightDensity = globalFog.enabled() ? globalFog.heightDensity() : 0.0f;
        float globalMaximumOpacity = globalFog.enabled() ? globalFog.maximumOpacity() : 0.0f;
        cmd.materialState(BlendMode.OPAQUE, false)
                .enableCullFace(false)
                .enableDepthTest(false)
                .depthMask(false)
                .enableFramebufferSrgb(false)
                .bindShader(shader)
                .bindTexture(SCENE_COLOR_UNIT, Math.max(sceneColorTexture, 0))
                .bindTexture(SCENE_DEPTH_UNIT, sceneDepthTexture)
                .bindTexture(SHADOW_UNIT, shadowTexture)
                .trySetUniformInt(shader, "uSceneColor", SCENE_COLOR_UNIT)
                .setUniformInt(shader, "uSceneDepth", SCENE_DEPTH_UNIT)
                .setUniformInt(shader, "uShadowMap", SHADOW_UNIT)
                .setUniformMat4(shader, "uInverseViewProjection", inverseViewProjection)
                .setUniformMat4(shader, "uView", view)
                .setUniformVec3(shader, "uCameraPosition", cameraPosition)
                .setUniformVec3(shader, "uSunDirection", sunDirection)
                .setUniformVec3(shader, "uSunColor", sunColor)
                .setUniformFloat(shader, "uSunIntensity", environment.sky().sunIntensity())
                .setUniformVec3(shader, "uEnvironmentColor",
                        environmentColor.set(environment.sky().horizonColorInternal()))
                .setUniformFloat(shader, "uEnvironmentIntensity",
                        environment.sky().environmentIntensity())
                .setUniformVec3(shader, "uScatteringColor", volume.scatteringColor())
                .setUniformInt(shader, "uSteps", volume.steps())
                .setUniformFloat(shader, "uMaximumDistance", volume.maximumDistance())
                .setUniformFloat(shader, "uDensity", volume.density())
                .setUniformFloat(shader, "uAnisotropy", volume.anisotropy())
                .trySetUniformFloat(shader, "uHistoryWeight", volume.historyWeight())
                // Rotate a short, deterministic low-discrepancy sequence. The
                // independent temporal pass then averages the phases without
                // coupling this effect to scene TAA jitter.
                .setUniformFloat(shader, "uFramePhase", (frameIndex & 7) / 8.0f)
                .setUniformInt(shader, "uCascadeCount", cascadeCount)
                .trySetUniformFloat(shader, "uCascadeAtlasSize", cascadeCount > 1
                        ? 2.0f : 1.0f)
                .setUniformFloat(shader, "uGlobalDistanceDensity", globalDistanceDensity)
                .setUniformFloat(shader, "uGlobalHeightDensity", globalHeightDensity)
                .setUniformFloat(shader, "uGlobalHeightFalloff", globalFog.heightFalloff())
                .setUniformFloat(shader, "uGlobalBaseHeight", globalFog.baseHeight())
                .setUniformFloat(shader, "uGlobalMaximumOpacity", globalMaximumOpacity)
                .setUniformVec3(shader, "uGlobalFogColor", globalFogColor.set(
                        environment.globalFog().red(), environment.globalFog().green(),
                        environment.globalFog().blue()))
                .setUniformInt(shader, "uLocalFogCount", environment.localFogVolumes().size())
                .setUniformInt(shader, "uNoiseSeed", environment.noiseSeed())
                .setUniformFloat(shader, "uWindTime", frames / 60.0f * environment.windSpeed());
        for (int index = 0; index < MAX_CASCADES; index++) {
            Matrix4f matrix = cascadeCount == 0 ? emptyCascadeMatrix : cascadeMatrices.get(
                    Math.min(index, cascadeCount - 1));
            float split = cascadeCount == 0 ? camera instanceof ExternalCamera external
                    ? external.farPlane() : CameraProjection.FAR_PLANE
                    : cascadeSplits[Math.min(index, cascadeSplits.length - 1)];
            cmd.setUniformMat4(shader, "uCascadeMatrices[" + index + "]", matrix)
                    .setUniformFloat(shader, "uCascadeSplits[" + index + "]", split);
        }
        for (int index = 0; index < MAX_VOLUMES; index++) {
            if (index < environment.localFogVolumes().size()) {
                LocalFogVolume fog = environment.localFogVolumes().get(index);
                Vector3f center = fog.centerInternal();
                Vector3f extent = fog.extentInternal();
                Vector3f color = fog.colorInternal();
                cmd.setUniformVec3(shader, "uFogCenters[" + index + "]", center)
                        .setUniformVec3(shader, "uFogExtents[" + index + "]", extent)
                        .setUniformVec3(shader, "uFogColors[" + index + "]", color)
                        .setUniformFloat(shader, "uFogDensities[" + index + "]", fog.density())
                        .setUniformFloat(shader, "uFogNoiseScales[" + index + "]", fog.noiseScale())
                        .setUniformFloat(shader, "uFogNoiseAmounts[" + index + "]", fog.noiseAmount())
                        .setUniformInt(shader, "uFogShapes[" + index + "]",
                                fog.shape() == LocalFogVolume.Shape.SPHERE ? 0 : 1);
            } else {
                cmd.setUniformVec3(shader, "uFogCenters[" + index + "]", zero)
                        .setUniformVec3(shader, "uFogExtents[" + index + "]", zero)
                        .setUniformVec3(shader, "uFogColors[" + index + "]", zero)
                        .setUniformFloat(shader, "uFogDensities[" + index + "]", 0.0f)
                        .setUniformFloat(shader, "uFogNoiseScales[" + index + "]", 0.0f)
                        .setUniformFloat(shader, "uFogNoiseAmounts[" + index + "]", 0.0f)
                        .setUniformInt(shader, "uFogShapes[" + index + "]", 0);
            }
        }
        cmd.bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6)
                .materialState(BlendMode.OPAQUE, true);
        frames = Math.incrementExact(frames);
    }

    long framesRecorded() { return frames; }

    @Override public int id() { return shader.id(); }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        quad.close();
        shader.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Outdoor volumetric sun pass is closed");
    }
}
