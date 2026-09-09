package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;

import java.util.Objects;

/** Stable value key for every input that changes RenderGraph/pass/resource structure. */
record PipelineTopology(int width,
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
                        int gtaoHalfWidth,
                        int gtaoHalfHeight,
                        SceneBufferRequirements sceneBuffers) {
    PipelineTopology {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("pipeline topology extent must be positive");
        }
        Objects.requireNonNull(antiAliasingMode, "antiAliasingMode");
        Objects.requireNonNull(sceneBuffers, "sceneBuffers");
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

    static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded,
                                    DirectionalCascadeSettings cascades) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                LocalShadowPipelineSettings.legacyDefaults());
    }

    static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded,
                                    DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
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
                directionalShadow, pointShadow && localShadows.maxPointLights() > 0,
                spotShadow && localShadows.maxSpotLights() > 0, cascades.cascadeCount(),
                cascades.atlasSize(), localShadows.maxPointLights(),
                localShadows.point().resolution(), localShadows.maxSpotLights(),
                localShadows.spot().resolution(), pbr, hdrVfx, embedded,
                effects.gtao().enabled(), (Math.max(1, width) + 1) / 2,
                (Math.max(1, height) + 1) / 2,
                sceneBufferRequirements(scene, settings, effects, samples, hdrVfx));
    }

    private static SceneBufferRequirements sceneBufferRequirements(Scene scene,
                                                                  RenderSettings settings,
                                                                  PostProcessSettings effects,
                                                                  int samples,
                                                                  boolean hdrVfx) {
        SceneBufferRequirements requirements = SceneBufferRequirements.none();
        if (effects.fog().enabled()) {
            requirements = requirements.merge(SceneBufferRequirements.fog());
        }
        if (effects.gtao().enabled()) {
            requirements = requirements.merge(
                    SceneBufferRequirements.gtao(effects.gtao().temporal()));
        }
        if (settings.antiAliasingMode() == AntiAliasingMode.TAA) {
            requirements = requirements.merge(SceneBufferRequirements.taa(
                    reactiveProducerPresent(scene, hdrVfx)));
        }
        return requirements.validated().withSamples(samples);
    }

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

    PipelineTopology withExtent(int width, int height) {
        return new PipelineTopology(width, height, antiAliasingMode, sampleCount, hdr,
                bloom, bloomLevels, automaticExposure, colorGrading, fog,
                directionalShadow, pointShadow, spotShadow, directionalCascadeCount,
                directionalShadowAtlasSize, pointShadowCapacity, pointShadowResolution,
                spotShadowCapacity, spotShadowResolution, pbrMaterials, hdrVfx, embedded,
                gtaoEnabled, (width + 1) / 2, (height + 1) / 2, sceneBuffers);
    }
}
