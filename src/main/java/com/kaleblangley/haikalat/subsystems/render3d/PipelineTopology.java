package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;

import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueClass;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneReactivePass;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;

import java.util.Objects;

/** Stable value key for every input that changes RenderGraph/pass/resource structure. */
public record PipelineTopology(int width,
                        int height,
                        AntiAliasingMode antiAliasingMode,
                        int sampleCount,
                        boolean hdr,
                        boolean bloom,
                        int bloomLevels,
                        boolean automaticExposure,
                        boolean colorGrading,
                        boolean fog,
                        boolean directionalShadow,
                        boolean pointShadow,
                        boolean spotShadow,
                        int directionalCascadeCount,
                        int directionalShadowAtlasSize,
                        int pointShadowCapacity,
                        int pointShadowResolution,
                        int spotShadowCapacity,
                        int spotShadowResolution,
                        boolean pbrMaterials,
                        boolean hdrVfx,
                        boolean embedded,
                        boolean gtaoEnabled,
                        boolean gtaoTemporal,
                        int gtaoHalfWidth,
                        int gtaoHalfHeight,
                        SceneSurfaceLayout sceneSurface,
                        boolean volumetricFog, VolumetricFogSettings.Quality volumetricQuality,
                        boolean volumetricEmission,
                        boolean waterSurface) {
    public PipelineTopology {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("pipeline topology extent must be positive");
        }
        Objects.requireNonNull(antiAliasingMode, "antiAliasingMode");
        Objects.requireNonNull(sceneSurface, "sceneSurface");
        Objects.requireNonNull(volumetricQuality, "volumetricQuality");
        if (sampleCount < 1) {
            throw new IllegalArgumentException("pipeline topology sample count must be positive");
        }
        if (bloomLevels < 0) {
            throw new IllegalArgumentException("pipeline topology bloom levels must be non-negative");
        }
        if (directionalCascadeCount < 1 || directionalCascadeCount > 4
                || directionalShadowAtlasSize <= 0) {
            throw new IllegalArgumentException("invalid directional cascade topology");
        }
        if (pointShadowCapacity < 0
                || pointShadowCapacity > LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS
                || spotShadowCapacity < 0
                || spotShadowCapacity > LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS
                || pointShadowResolution <= 0 || spotShadowResolution <= 0) {
            throw new IllegalArgumentException("invalid local shadow topology");
        }
        if (gtaoHalfWidth <= 0 || gtaoHalfHeight <= 0) {
            throw new IllegalArgumentException("invalid GTAO half-resolution extent");
        }
    }

    /** Graph replacement retains compatible targets at the same managed extent. */
    boolean sameManagedExtent(PipelineTopology other) {
        return width == other.width && height == other.height && embedded == other.embedded;
    }

    public boolean sameShadowShape(PipelineTopology other) {
        return directionalShadow == other.directionalShadow && pointShadow == other.pointShadow
                && spotShadow == other.spotShadow && directionalCascadeCount == other.directionalCascadeCount
                && directionalShadowAtlasSize == other.directionalShadowAtlasSize
                && pointShadowCapacity == other.pointShadowCapacity && pointShadowResolution == other.pointShadowResolution
                && spotShadowCapacity == other.spotShadowCapacity && spotShadowResolution == other.spotShadowResolution;
    }

    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded,
                                    DirectionalCascadeSettings cascades) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                LocalShadowPipelineSettings.legacyDefaults());
    }

    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded,
                                    DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                localShadows, VolumetricFogSettings.disabled());
    }

    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded, DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows, VolumetricFogSettings volume) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                localShadows, volume, true, true, true);
    }

    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded, DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows, VolumetricFogSettings volume,
                                    boolean directionalShadowRequested, boolean pointShadowRequested,
                                    boolean spotShadowRequested) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                localShadows, volume, directionalShadowRequested, pointShadowRequested,
                spotShadowRequested, WaterSurfaceSettings.disabled());
    }

    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded, DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows, VolumetricFogSettings volume,
                                    boolean directionalShadowRequested, boolean pointShadowRequested,
                                    boolean spotShadowRequested,
                                    WaterSurfaceSettings waterSurface) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(waterSurface, "waterSurface");
        boolean bloom = settings.bloomSettings().enabled();
        int samples = settings.antiAliasingMode() == AntiAliasingMode.MSAA
                ? Math.max(2, settings.msaaSamples()) : 1;
        boolean directionalShadow = false;
        boolean pointShadow = false;
        boolean spotShadow = false;
        for (SceneLight light : scene.lights()) {
            if (!light.castShadows()) continue;
            switch (light.type()) {
                case DIRECTIONAL -> directionalShadow = true;
                case POINT -> pointShadow = true;
                case SPOT -> spotShadow = true;
            }
        }
        boolean pbr = false;
        for (int index = 0; index < scene.rendererCount(); index++) {
            if (scene.rendererAt(index).material().material().model()
                    == MaterialModel.METALLIC_ROUGHNESS) {
                pbr = true;
                break;
            }
        }
        return new PipelineTopology(Math.max(1, width), Math.max(1, height),
                settings.antiAliasingMode(), samples, settings.hdrEnabled(), bloom,
                bloom ? settings.bloomSettings().maxLevels() : 0,
                settings.exposureMode() == ExposureMode.AUTO,
                effects.colorGrading().enabled(), effects.fog().enabled(),
                directionalShadowRequested && directionalShadow,
                pointShadowRequested && pointShadow && localShadows.maxPointLights() > 0,
                spotShadowRequested && spotShadow && localShadows.maxSpotLights() > 0,
                cascades.cascadeCount(),
                cascades.atlasSize(), localShadows.maxPointLights(),
                localShadows.point().resolution(), localShadows.maxSpotLights(),
                localShadows.spot().resolution(), pbr, hdrVfx, embedded,
                effects.gtao().enabled(), effects.gtao().enabled() && effects.gtao().temporal(), (Math.max(1, width) + 1) / 2,
                (Math.max(1, height) + 1) / 2,
                sceneSurface(scene, settings, effects, samples, hdrVfx, volume.enabled(),
                        waterSurface.active()),
                volume.enabled(), volume.enabled() ? volume.quality() : VolumetricFogSettings.Quality.BALANCED,
                volume.enabled() && volume.hasEmission(), waterSurface.active());
    }

    private static SceneSurfaceLayout sceneSurface(Scene scene, RenderSettings settings,
                                                   PostProcessSettings effects, int samples,
                                                   boolean hdrVfx, boolean volume,
                                                   boolean waterSurface) {
        SceneBufferRequirements requirements = SceneBufferRequirements.none();
        // Analytic fog and depth of field both consume only the shared single-sample depth.
        if (effects.fog().enabled() || volume || effects.depthOfField().active()) {
            requirements = requirements.mergeView(SceneBufferRequirements.fog());
        }
        // Motion blur consumes the shared single-sample velocity and validity.
        if (effects.motionBlur().active()) {
            requirements = requirements.mergeView(SceneBufferRequirements.motionBlur());
        }
        // Screen-space reflections consume the single-sample depth, normal and
        // material roughness channels.
        if (effects.ssr().active()) {
            requirements = requirements.mergeView(SceneBufferRequirements.ssr());
        }
        // Screen-space subsurface diffusion consumes depth, world normals and
        // the per-material mask/radius/thickness channel.
        if (effects.subsurfaceScattering().screenSpaceActive()) {
            requirements = requirements.mergeView(
                    SceneBufferRequirements.subsurfaceScattering());
        }
        // Screen-space indirect diffuse additionally consumes the hit
        // roughness and the receiver albedo.
        if (effects.ssgi().active()) {
            requirements = requirements.mergeView(SceneBufferRequirements.ssgi());
        }
        if (effects.gtao().enabled()) {
            requirements = requirements.mergeView(
                    SceneBufferRequirements.gtao(effects.gtao().temporal()));
        }
        boolean taa = settings.antiAliasingMode() == AntiAliasingMode.TAA;
        var reactive = SceneSurfaceLayout.ReactiveMode.NONE;
        if (taa) {
            if (volume) reactive = SceneSurfaceLayout.ReactiveMode.VOLUME_ALIAS;
            else if (reactiveProducerPresent(scene, hdrVfx)) reactive = SceneSurfaceLayout.ReactiveMode.SCENE;
            requirements = requirements.mergeView(SceneBufferRequirements.taa(reactive != SceneSurfaceLayout.ReactiveMode.NONE));
        }
        // Water needs the raster depth at geometry sample count so the pass can
        // compare plane depth with the exact depth it samples; no resolve copy.
        boolean rasterDepth = volume || waterSurface;
        var raw = rasterDepth
                ? SceneBufferRequirements.of(SceneBufferChannel.DEPTH).withSamples(samples)
                : SceneBufferRequirements.none();
        return SceneSurfaceLayout.plan(samples, raw, requirements, reactive);
    }

    public SceneBufferRequirements sceneBuffers() { return sceneSurface.requests(); }

    /**
     * Reactive is only requested when a real producer exists: a declared
     * material distrust value, a transparent/additive queue or HDR VFX.
     */
    private static boolean reactiveProducerPresent(Scene scene, boolean hdrVfx) {
        if (hdrVfx) return true;
        for (MeshRenderer renderer : scene.renderers()) {
            RenderQueueClass queue = RenderQueueClass.classify(renderer.material());
            if (queue == RenderQueueClass.TRANSPARENT_ALPHA
                    || queue == RenderQueueClass.TRANSPARENT_ADDITIVE) {
                return true;
            }
            if (SceneReactivePass.reactiveOf(renderer.material()) > 0.0f) {
                return true;
            }
        }
        return false;
    }

    public PipelineTopology withExtent(int width, int height) {
        return new PipelineTopology(width, height, antiAliasingMode, sampleCount, hdr,
                bloom, bloomLevels, automaticExposure, colorGrading, fog,
                directionalShadow, pointShadow, spotShadow, directionalCascadeCount,
                directionalShadowAtlasSize, pointShadowCapacity, pointShadowResolution,
                spotShadowCapacity, spotShadowResolution, pbrMaterials, hdrVfx, embedded,
                gtaoEnabled, gtaoTemporal, (width + 1) / 2, (height + 1) / 2, sceneSurface,
                volumetricFog, volumetricQuality, volumetricEmission, waterSurface);
    }
}
