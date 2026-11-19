package com.kaleblangley.haikalat.subsystems.render3d.lighting;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.ClusterDebugMode;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraUniforms;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PcssSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowFrameBinder;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowFramePlan;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowSamplingBlock;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Objects;

/**
 * Uploads the per-frame lighting/shadow inputs of the forward pass.
 *
 * <p>Owns the binding order for camera block, shadow sampling block, cluster
 * light data and the shadow/ao texture units.  Frame facts (plans, matrices,
 * textures) are passed in; it does not read the central pipeline.</p>
 */
public final class FrameLightBinder {
    public static final int SHADOW_TEXTURE_UNIT = 7;
    public static final int POINT_SHADOW_TEXTURE_UNIT = 11;
    public static final int SPOT_SHADOW_TEXTURE_UNIT = 12;
    public static final int GTAO_TEXTURE_UNIT = 13;

    private final CameraUniforms cameraUniforms;
    private final ShadowFrameBinder shadowFrameBinder;
    private final ShadowSamplingBlock shadowSamplingBlock;
    private final ClusteredLightingBinder clusteredLightingBinder;
    private final DirectionalShadowMap directionalShadowMap;
    private final DirectionalCascadeSettings cascadeSettings;
    private final LocalShadowPipelineSettings localShadowSettings;
    private final boolean gtaoEnabled;
    private final boolean directLightingEnabled;
    private final boolean imageBasedLightingEnabled;
    private final CloudShadowFrameTime cloudShadowFrameTime = new CloudShadowFrameTime();
    private boolean forwardContactShadowsEnabled = true;

    /**
     * Suppresses the material-level shadow-map-space contact term while the
     * production screen-space pass is the single contact-shadow consumer.
     */
    public void forwardContactShadowsEnabled(boolean value) {
        forwardContactShadowsEnabled = value;
    }

    /** @return whether the material-level contact term is currently bound */
    public boolean forwardContactShadowsEnabled() {
        return forwardContactShadowsEnabled;
    }

    public FrameLightBinder(CameraUniforms cameraUniforms, ShadowFrameBinder shadowFrameBinder,
                            ShadowSamplingBlock shadowSamplingBlock,
                            ClusteredLightingBinder clusteredLightingBinder,
                            DirectionalShadowMap directionalShadowMap,
                            DirectionalCascadeSettings cascadeSettings,
                            LocalShadowPipelineSettings localShadowSettings,
                            boolean gtaoEnabled, boolean directLightingEnabled,
                            boolean imageBasedLightingEnabled) {
        this.cameraUniforms = Objects.requireNonNull(cameraUniforms, "cameraUniforms");
        this.shadowFrameBinder = Objects.requireNonNull(shadowFrameBinder, "shadowFrameBinder");
        this.shadowSamplingBlock = shadowSamplingBlock;
        this.clusteredLightingBinder = Objects.requireNonNull(clusteredLightingBinder,
                "clusteredLightingBinder");
        this.directionalShadowMap = Objects.requireNonNull(directionalShadowMap, "directionalShadowMap");
        this.cascadeSettings = Objects.requireNonNull(cascadeSettings, "cascadeSettings");
        this.localShadowSettings = Objects.requireNonNull(localShadowSettings, "localShadowSettings");
        this.gtaoEnabled = gtaoEnabled;
        this.directLightingEnabled = directLightingEnabled;
        this.imageBasedLightingEnabled = imageBasedLightingEnabled;
    }

    public void bind(ShaderProgram shader, CommandBuffer cmd, Camera camera,
                     ShadowFramePlan shadowPlan, Matrix4f directionalLightSpace,
                     List<Matrix4f> cascadeMatrices, float[] cascadeSplits,
                     ClusterDebugMode clusterDebugMode, int shadowTexture, int pointShadowTexture,
                     int spotShadowTexture, int gtaoTexture) {
        cameraUniforms.bind(shader);
        shadowFrameBinder.bind(cmd, shader, camera, directionalLightSpace, cascadeMatrices,
                cascadeSplits, cascadeSettings, shadowPlan);
        boolean hasShadow = shadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasDirectionalShadow", hasShadow ? 1 : 0)
                .trySetUniformInt(shader, "uShadowMap", SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uShadowBias", directionalShadowMap.settings().bias());
        PcssSettings pcss = directionalShadowMap.settings().pcss();
        cmd.trySetUniformInt(shader, "uPcssEnabled", pcss.active() ? 1 : 0)
                .trySetUniformFloat(shader, "uPcssLightSize", pcss.lightWorldSize())
                .trySetUniformInt(shader, "uPcssBlockerRadiusTexels", pcss.blockerRadiusTexels())
                .trySetUniformInt(shader, "uPcssBlockerTaps", pcss.blockerTaps())
                .trySetUniformInt(shader, "uPcssFilterRadiusTexels", pcss.filterRadiusTexels())
                .trySetUniformInt(shader, "uPcssFilterTaps", pcss.filterTaps())
                .trySetUniformFloat(shader, "uPcssDepthBias", pcss.depthBias());
        bindCascadeTexelWorld(shader, cmd, shadowPlan);
        var contactShadows = directionalShadowMap.settings().contactShadows();
        cmd.trySetUniformInt(shader, "uContactShadowEnabled",
                        contactShadows.active() && forwardContactShadowsEnabled ? 1 : 0)
                .trySetUniformFloat(shader, "uContactShadowDistance", contactShadows.distance())
                .trySetUniformFloat(shader, "uContactShadowThickness", contactShadows.thickness())
                .trySetUniformInt(shader, "uContactShadowSteps", contactShadows.steps())
                .trySetUniformFloat(shader, "uContactShadowStrength", contactShadows.strength());
        CloudShadowSettings cloudShadows = directionalShadowMap.settings().cloudShadows();
        float cloudShadowTime = cloudShadowFrameTime.advance(shadowPlan.frameTiming());
        cmd.trySetUniformInt(shader, "uCloudShadowEnabled", cloudShadows.active() ? 1 : 0)
                .trySetUniformFloat(shader, "uCloudShadowCoverage", cloudShadows.coverage())
                .trySetUniformFloat(shader, "uCloudShadowSoftness", cloudShadows.softness())
                .trySetUniformFloat(shader, "uCloudShadowScale", cloudShadows.scale())
                .trySetUniformFloat(shader, "uCloudShadowSpeed", cloudShadows.speed())
                .trySetUniformFloat(shader, "uCloudShadowStrength", cloudShadows.strength())
                .trySetUniformFloat(shader, "uCloudShadowTime", cloudShadowTime);
        if (hasShadow) {
            cmd.bindTexture(SHADOW_TEXTURE_UNIT, shadowTexture);
        }
        boolean hasPointShadow = pointShadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasPointShadow", hasPointShadow ? 1 : 0)
                .trySetUniformInt(shader, "uPointShadowMap", POINT_SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uPointShadowBias",
                        localShadowSettings.point().bias());
        if (hasPointShadow) {
            cmd.bindTexture(POINT_SHADOW_TEXTURE_UNIT, pointShadowTexture);
        }
        boolean hasSpotShadow = spotShadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasSpotShadow", hasSpotShadow ? 1 : 0)
                .trySetUniformInt(shader, "uSpotShadowMap", SPOT_SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uSpotShadowBias", localShadowSettings.spot().bias());
        if (hasSpotShadow) {
            cmd.bindTexture(SPOT_SHADOW_TEXTURE_UNIT, spotShadowTexture);
        }
        boolean hasGtao = gtaoEnabled && gtaoTexture != 0;
        cmd.trySetUniformInt(shader, "uGtaoEnabled", hasGtao ? 1 : 0)
                .trySetUniformInt(shader, "uGtaoMap", GTAO_TEXTURE_UNIT)
                .bindSampler(GTAO_TEXTURE_UNIT, null)
                .bindTexture(GTAO_TEXTURE_UNIT, hasGtao ? gtaoTexture : 0);
        if (shadowSamplingBlock != null) {
            shadowSamplingBlock.bind(cmd);
        }
        clusteredLightingBinder.bindForward(cmd, shader);
        cmd.trySetUniformInt(shader, "uClusterDebugMode", clusterDebugMode.ordinal());
        // The PBR material templates default these to enabled; the frame gate
        // overrides them after material binding so disabling a contribution
        // does not require a new shader variant.
        cmd.trySetUniformInt(shader, "uEnableDirect", directLightingEnabled ? 1 : 0)
                .trySetUniformInt(shader, "uEnableDiffuseIbl", imageBasedLightingEnabled ? 1 : 0)
                .trySetUniformInt(shader, "uEnableSpecularIbl", imageBasedLightingEnabled ? 1 : 0);
    }

    /**
     * Uploads the world-space size of one shadow-map texel for the selected
     * cascade.  The cascade plan owns the value; the legacy single-map path
     * derives it from its symmetric orthographic radius and resolution.
     */
    private void bindCascadeTexelWorld(ShaderProgram shader, CommandBuffer cmd,
                                       ShadowFramePlan plan) {
        float legacyTexelWorld = 2.0f * directionalShadowMap.settings().sceneRadius()
                / directionalShadowMap.settings().resolution();
        float[] texelWorldSizes = cascadeTexelWorldSizes(legacyTexelWorld,
                cascadeSettings.enabled(),
                plan.directional().map(ShadowFramePlan.DirectionalPlan::texelWorldSizes)
                        .orElse(new float[0]));
        for (int index = 0; index < texelWorldSizes.length; index++) {
            cmd.trySetUniformFloat(shader,
                    "uDirectionalCascadeTexelWorld[" + index + "]", texelWorldSizes[index]);
        }
    }

    /**
     * Resolves four cascade texel sizes for the uniform array.  The legacy
     * single-map plan deliberately stores a culling placeholder, so its real
     * footprint must come from the symmetric orthographic radius; cascaded
     * plans already carry the per-cascade value and the last entry is repeated
     * for unused slots.
     */
    static float[] cascadeTexelWorldSizes(float legacyTexelWorld, boolean cascaded,
                                          float[] planTexelWorldSizes) {
        if (!Float.isFinite(legacyTexelWorld) || legacyTexelWorld <= 0.0f) {
            throw new IllegalArgumentException("legacyTexelWorld must be finite and positive");
        }
        if (planTexelWorldSizes == null) {
            throw new IllegalArgumentException("planTexelWorldSizes must not be null");
        }
        float[] result = new float[4];
        for (int index = 0; index < result.length; index++) {
            result[index] = !cascaded || planTexelWorldSizes.length == 0
                    ? legacyTexelWorld
                    : planTexelWorldSizes[Math.min(index, planTexelWorldSizes.length - 1)];
        }
        return result;
    }
}
