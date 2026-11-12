package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;

import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueClass;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferRequirements;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneReactivePass;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;

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
                        boolean waterSurface,
                        boolean volumetricParticles,
                        boolean stochasticTransparency,
                        boolean volumetricClouds,
                        boolean volumetricSmoke,
                        boolean weightedOit,
                        boolean planarReflections,
                        boolean caustics,
                        boolean ddgi,
                        boolean voxelConeTracing,
                        boolean hairStrands) {
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

    /**
     * Source-compatible constructor for the pre-S3-single wiring shape.  The S3
     * composition passes and the DDGI/VCT/hair requests default to their exact
     * no-op state.
     */
    public PipelineTopology(int width, int height, AntiAliasingMode antiAliasingMode,
                            int sampleCount, boolean hdr, boolean bloom, int bloomLevels,
                            boolean automaticExposure, boolean colorGrading, boolean fog,
                            boolean directionalShadow, boolean pointShadow, boolean spotShadow,
                            int directionalCascadeCount, int directionalShadowAtlasSize,
                            int pointShadowCapacity, int pointShadowResolution,
                            int spotShadowCapacity, int spotShadowResolution,
                            boolean pbrMaterials, boolean hdrVfx, boolean embedded,
                            boolean gtaoEnabled, boolean gtaoTemporal, int gtaoHalfWidth,
                            int gtaoHalfHeight, SceneSurfaceLayout sceneSurface,
                            boolean volumetricFog, VolumetricFogSettings.Quality volumetricQuality,
                            boolean volumetricEmission, boolean waterSurface,
                            boolean volumetricParticles, boolean stochasticTransparency) {
        this(width, height, antiAliasingMode, sampleCount, hdr, bloom, bloomLevels,
                automaticExposure, colorGrading, fog, directionalShadow, pointShadow, spotShadow,
                directionalCascadeCount, directionalShadowAtlasSize, pointShadowCapacity,
                pointShadowResolution, spotShadowCapacity, spotShadowResolution, pbrMaterials,
                hdrVfx, embedded, gtaoEnabled, gtaoTemporal, gtaoHalfWidth, gtaoHalfHeight,
                sceneSurface, volumetricFog, volumetricQuality, volumetricEmission, waterSurface,
                volumetricParticles, stochasticTransparency, false, false, false, false, false,
                false, false, false);
    }

    /**
     * Source-compatible constructor for the S3 composition shape.  The DDGI,
     * VCT and hair-strand requests default to their exact no-op state.
     */
    public PipelineTopology(int width, int height, AntiAliasingMode antiAliasingMode,
                            int sampleCount, boolean hdr, boolean bloom, int bloomLevels,
                            boolean automaticExposure, boolean colorGrading, boolean fog,
                            boolean directionalShadow, boolean pointShadow, boolean spotShadow,
                            int directionalCascadeCount, int directionalShadowAtlasSize,
                            int pointShadowCapacity, int pointShadowResolution,
                            int spotShadowCapacity, int spotShadowResolution,
                            boolean pbrMaterials, boolean hdrVfx, boolean embedded,
                            boolean gtaoEnabled, boolean gtaoTemporal, int gtaoHalfWidth,
                            int gtaoHalfHeight, SceneSurfaceLayout sceneSurface,
                            boolean volumetricFog, VolumetricFogSettings.Quality volumetricQuality,
                            boolean volumetricEmission, boolean waterSurface,
                            boolean volumetricParticles, boolean stochasticTransparency,
                            boolean volumetricClouds, boolean volumetricSmoke,
                            boolean weightedOit, boolean planarReflections, boolean caustics) {
        this(width, height, antiAliasingMode, sampleCount, hdr, bloom, bloomLevels,
                automaticExposure, colorGrading, fog, directionalShadow, pointShadow, spotShadow,
                directionalCascadeCount, directionalShadowAtlasSize, pointShadowCapacity,
                pointShadowResolution, spotShadowCapacity, spotShadowResolution, pbrMaterials,
                hdrVfx, embedded, gtaoEnabled, gtaoTemporal, gtaoHalfWidth, gtaoHalfHeight,
                sceneSurface, volumetricFog, volumetricQuality, volumetricEmission, waterSurface,
                volumetricParticles, stochasticTransparency, volumetricClouds, volumetricSmoke,
                weightedOit, planarReflections, caustics, false, false, false);
    }

    /** Graph replacement retains compatible targets at the same managed extent. */
    public boolean sameManagedExtent(PipelineTopology other) {
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
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                localShadows, volume, directionalShadowRequested, pointShadowRequested,
                spotShadowRequested, waterSurface, VolumetricParticleSettings.disabled(),
                StochasticTransparencySettings.disabled());
    }

    /**
     * Captures the complete topology key including the opt-in composition
     * features. An active request only changes the key when its required
     * composition inputs exist, so an unavailable request stays a soft
     * effective-state observation instead of silently altering the graph.
     * Cloud and smoke requests default to disabled.
     */
    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded, DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows, VolumetricFogSettings volume,
                                    boolean directionalShadowRequested, boolean pointShadowRequested,
                                    boolean spotShadowRequested,
                                    WaterSurfaceSettings waterSurface,
                                    VolumetricParticleSettings volumetricParticles,
                                    StochasticTransparencySettings stochasticTransparency) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                localShadows, volume, directionalShadowRequested, pointShadowRequested,
                spotShadowRequested, waterSurface, volumetricParticles, stochasticTransparency,
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled(),
                WeightedOitSettings.disabled(), false, PlanarReflectionSettings.disabled(),
                CausticsSettings.disabled(),
                DdgiSettings.disabled(), VctSettings.disabled(), HairStrandSettings.disabled());
    }

    /**
     * Captures the composition requests without the volumetric media. Cloud,
     * smoke and the cloud-shadow field default to disabled; the opt-in
     * composition requests are taken from the caller.
     */
    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded, DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows, VolumetricFogSettings volume,
                                    boolean directionalShadowRequested, boolean pointShadowRequested,
                                    boolean spotShadowRequested,
                                    WaterSurfaceSettings waterSurface,
                                    VolumetricParticleSettings volumetricParticles,
                                    StochasticTransparencySettings stochasticTransparency,
                                    WeightedOitSettings weightedOit, boolean weightedOitSetDeclared,
                                    PlanarReflectionSettings planarReflections,
                                    CausticsSettings caustics) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                localShadows, volume, directionalShadowRequested, pointShadowRequested,
                spotShadowRequested, waterSurface, volumetricParticles, stochasticTransparency,
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled(),
                weightedOit, weightedOitSetDeclared, planarReflections, caustics,
                DdgiSettings.disabled(), VctSettings.disabled(), HairStrandSettings.disabled());
    }

    /**
     * Captures the complete topology key including every opt-in late
     * composition feature (#37/#38/#44/#45/#46).
     */
    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded, DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows, VolumetricFogSettings volume,
                                    boolean directionalShadowRequested, boolean pointShadowRequested,
                                    boolean spotShadowRequested,
                                    WaterSurfaceSettings waterSurface,
                                    VolumetricParticleSettings volumetricParticles,
                                    StochasticTransparencySettings stochasticTransparency,
                                    DdgiSettings ddgiSettings,
                                    VctSettings vctSettings,
                                    HairStrandSettings hairStrandSettings) {
        return capture(scene, settings, effects, width, height, hdrVfx, embedded, cascades,
                localShadows, volume, directionalShadowRequested, pointShadowRequested,
                spotShadowRequested, waterSurface, volumetricParticles, stochasticTransparency,
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), CloudShadowSettings.disabled(),
                WeightedOitSettings.disabled(), false, PlanarReflectionSettings.disabled(),
                CausticsSettings.disabled(), ddgiSettings, vctSettings, hairStrandSettings);
    }

    /**
     * Captures the complete topology key including the two volumetric media that
     * composite over the final scene colour. Clouds additionally consult the
     * shared #10 coverage field: while directional cloud shadows are enabled the
     * layer must reuse the same authored field, otherwise the request stays an
     * unavailable effective observation and does not change the graph.
     *
     * <p>{@code weightedOitSetDeclared} is part of the key: an enabled OIT
     * request without a caller-selected set (or an explicit empty-set
     * declaration) has no compatible composition and must not add the pass.</p>
     */
    public static PipelineTopology capture(Scene scene, RenderSettings settings,
                                    PostProcessSettings effects, int width, int height,
                                    boolean hdrVfx, boolean embedded, DirectionalCascadeSettings cascades,
                                    LocalShadowPipelineSettings localShadows, VolumetricFogSettings volume,
                                    boolean directionalShadowRequested, boolean pointShadowRequested,
                                    boolean spotShadowRequested,
                                    WaterSurfaceSettings waterSurface,
                                    VolumetricParticleSettings volumetricParticles,
                                    StochasticTransparencySettings stochasticTransparency,
                                    VolumetricCloudSettings volumetricClouds,
                                    SmokeSimulationSettings smokeSimulation,
                                    SmokeRenderSettings smokeRender,
                                    CloudShadowSettings directionalCloudShadows,
                                    WeightedOitSettings weightedOit, boolean weightedOitSetDeclared,
                                    PlanarReflectionSettings planarReflections,
                                    CausticsSettings caustics,
                                    DdgiSettings ddgiSettings,
                                    VctSettings vctSettings,
                                    HairStrandSettings hairStrandSettings) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(waterSurface, "waterSurface");
        Objects.requireNonNull(volumetricParticles, "volumetricParticles");
        Objects.requireNonNull(stochasticTransparency, "stochasticTransparency");
        Objects.requireNonNull(volumetricClouds, "volumetricClouds");
        Objects.requireNonNull(smokeSimulation, "smokeSimulation");
        Objects.requireNonNull(smokeRender, "smokeRender");
        Objects.requireNonNull(weightedOit, "weightedOit");
        Objects.requireNonNull(planarReflections, "planarReflections");
        Objects.requireNonNull(caustics, "caustics");
        Objects.requireNonNull(ddgiSettings, "ddgi");
        Objects.requireNonNull(vctSettings, "vct");
        Objects.requireNonNull(hairStrandSettings, "hairStrands");
        boolean bloom = settings.bloomSettings().enabled();
        int samples = settings.antiAliasingMode() == AntiAliasingMode.MSAA
                ? Math.max(2, settings.msaaSamples()) : 1;
        boolean directionalShadow = false;
        boolean pointShadow = false;
        boolean spotShadow = false;
        boolean downwardDirectionalLight = false;
        boolean directionalLight = false;
        for (SceneLight light : scene.lights()) {
            if (light.type() == LightType.DIRECTIONAL) directionalLight = true;
            if (!light.castShadows()) {
                if (light.type() == LightType.DIRECTIONAL
                        && light.direction().y < -CausticsSettings.MINIMUM_LIGHT_DOWNWARD_Y) {
                    downwardDirectionalLight = true;
                }
                continue;
            }
            switch (light.type()) {
                case DIRECTIONAL -> {
                    directionalShadow = true;
                    if (light.direction().y < -CausticsSettings.MINIMUM_LIGHT_DOWNWARD_Y) {
                        downwardDirectionalLight = true;
                    }
                }
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
        boolean particles = volumetricParticleCompositionActive(settings, volume,
                volumetricParticles);
        boolean stochastic = stochasticTransparencyCompositionActive(settings,
                stochasticTransparency);
        boolean clouds = volumetricCloudCompositionActive(settings, volumetricClouds,
                directionalShadowRequested && directionalShadow, directionalCloudShadows);
        boolean smoke = volumetricSmokeCompositionActive(settings, smokeSimulation, smokeRender);
        boolean oit = weightedOitCompositionActive(settings, weightedOit,
                weightedOitSetDeclared);
        boolean planar = planarReflectionCompositionActive(settings, planarReflections);
        boolean causticsActive = causticsCompositionActive(settings, waterSurface, caustics,
                downwardDirectionalLight);
        boolean ddgi = ddgiCompositionActive(settings, ddgiSettings, directionalLight);
        boolean vct = vctCompositionActive(settings, vctSettings);
        boolean hair = hairStrandCompositionActive(settings, hairStrandSettings,
                directionalLight);
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
                        waterSurface.active(), stochastic, clouds, smoke, ddgi, vct, hair),
                volume.enabled(), volume.enabled() ? volume.quality() : VolumetricFogSettings.Quality.BALANCED,
                volume.enabled() && volume.hasEmission(), waterSurface.active(), particles,
                stochastic, clouds, smoke, oit, planar, causticsActive, ddgi, vct, hair);
    }

    /**
     * A particle request participates only with an active volumetric-fog
     * composition and a single-sample colour/depth raster.
     */
    public static boolean volumetricParticleCompositionActive(RenderSettings settings,
                                                              VolumetricFogSettings fog,
                                                              VolumetricParticleSettings particles) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(fog, "fog");
        Objects.requireNonNull(particles, "particles");
        return particles.active() && fog.enabled()
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA;
    }

    /**
     * Stochastic transparency is specified for a stable single-sample raster;
     * MSAA and TAA have no compatible caller target/depth in this version.
     */
    public static boolean stochasticTransparencyCompositionActive(RenderSettings settings,
                                                                  StochasticTransparencySettings stochastic) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(stochastic, "stochastic");
        return stochastic.active()
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA
                && settings.antiAliasingMode() != AntiAliasingMode.TAA;
    }

    /**
     * A cloud request participates only on a stable single-sample HDR raster;
     * MSAA raw attachments and TAA jitter have no compatible same-frame colour
     * and depth pair in this version.
     */
    public static boolean volumetricCloudCompositionActive(RenderSettings settings,
                                                           VolumetricCloudSettings clouds) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clouds, "clouds");
        return clouds.active() && settings.hdrEnabled() && stableSingleSample(settings);
    }

    /**
     * Adds the shared-#10 coupling: while a directional cloud shadow is
     * requested, the visible layer must reuse the same authored coverage field.
     */
    public static boolean volumetricCloudCompositionActive(RenderSettings settings,
                                                           VolumetricCloudSettings clouds,
                                                           boolean directionalShadowsActive,
                                                           CloudShadowSettings directionalCloudShadows) {
        if (!volumetricCloudCompositionActive(settings, clouds)) return false;
        if (!directionalShadowsActive || directionalCloudShadows == null
                || !directionalCloudShadows.active()) {
            return true;
        }
        return clouds.coverageField().equals(directionalCloudShadows);
    }

    /**
     * Smoke needs both owners: the volume pass has no field without the
     * simulation, and a simulation without a consumer is not a rendered medium.
     */
    public static boolean volumetricSmokeCompositionActive(RenderSettings settings,
                                                           SmokeSimulationSettings simulation,
                                                           SmokeRenderSettings render) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(simulation, "simulation");
        Objects.requireNonNull(render, "render");
        return simulation.active() && render.active() && settings.hdrEnabled()
                && stableSingleSample(settings);
    }

    private static boolean stableSingleSample(RenderSettings settings) {
        return settings.antiAliasingMode() != AntiAliasingMode.MSAA
                && settings.antiAliasingMode() != AntiAliasingMode.TAA;
    }

    /**
     * Weighted OIT is an alternative transparent-collection strategy for a
     * caller-selected set.  It needs HDR, a stable non-MSAA/non-TAA
     * single-sample target, and a declared set (an explicit empty set is a
     * legal no-op declaration).  The stochastic collector remains a separate
     * strategy and the two may only claim disjoint sets.
     */
    public static boolean weightedOitCompositionActive(RenderSettings settings,
                                                       WeightedOitSettings weightedOit,
                                                       boolean setDeclared) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(weightedOit, "weightedOit");
        return weightedOit.active() && settings.hdrEnabled() && setDeclared
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA
                && settings.antiAliasingMode() != AntiAliasingMode.TAA;
    }

    /**
     * DDGI needs an HDR linear colour chain, one directional light for the
     * analytic relight and a single-sample raster so its indirect pass can
     * consume the resolved depth/normal view. MSAA and TAA are rejected in the
     * first version: the probe query has no verified jitter/resolve contract.
     */
    public static boolean ddgiCompositionActive(RenderSettings settings, DdgiSettings ddgi,
                                                boolean directionalLightPresent) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(ddgi, "ddgi");
        return ddgi.active() && settings.hdrEnabled()
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA
                && settings.antiAliasingMode() != AntiAliasingMode.TAA
                && directionalLightPresent;
    }

    /** VCT composites radiance into a resolved single-sample HDR target before Bloom/TAA. */
    public static boolean vctCompositionActive(RenderSettings settings, VctSettings vct) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(vct, "vct");
        return vct.enabled() && settings.hdrEnabled()
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA
                && settings.antiAliasingMode() != AntiAliasingMode.TAA;
    }

    /** Planar reflections need HDR and an active, validated single plane. */
    public static boolean planarReflectionCompositionActive(RenderSettings settings,
                                                            PlanarReflectionSettings planar) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(planar, "planar");
        return planar.active() && settings.hdrEnabled() && planar.hasPlane();
    }

    /**
     * Water caustics need HDR, the finite water surface whose colour they are
     * composited under, and a downward main directional light.  The first
     * composition version writes its single-sample contribution back into the
     * shared scene colour, so MSAA's multisampled target is unsupported even
     * though the pass itself can sample an MSAA depth texture.  The pass only
     * adds linear light and never writes the shared depth.
     */
    public static boolean causticsCompositionActive(RenderSettings settings,
                                                    WaterSurfaceSettings water,
                                                    CausticsSettings caustics,
                                                    boolean downwardDirectionalLight) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(water, "water");
        Objects.requireNonNull(caustics, "caustics");
        return caustics.active() && settings.hdrEnabled() && water.active()
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA
                && downwardDirectionalLight;
    }

    /** Hair strands are forward-lit into the transparent HDR stage. */
    public static boolean hairStrandCompositionActive(RenderSettings settings,
                                                      HairStrandSettings hair,
                                                      boolean directionalLightPresent) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(hair, "hair");
        return hair.active() && settings.hdrEnabled() && directionalLightPresent;
    }

    private static SceneSurfaceLayout sceneSurface(Scene scene, RenderSettings settings,
                                                   PostProcessSettings effects, int samples,
                                                   boolean hdrVfx, boolean volume,
                                                   boolean waterSurface,
                                                   boolean stochasticTransparency,
                                                   boolean volumetricClouds,
                                                   boolean volumetricSmoke,
                                                   boolean ddgi, boolean vct,
                                                   boolean hairStrands) {
        SceneBufferRequirements requirements = SceneBufferRequirements.none();
        // Analytic fog and depth of field both consume only the shared single-sample depth.
        if (effects.fog().enabled() || volume || effects.depthOfField().active()) {
            requirements = requirements.mergeView(SceneBufferRequirements.fog());
        }
        // The stochastic collection reprojects against the shared opaque depth; it
        // never writes that depth.
        if (stochasticTransparency) {
            requirements = requirements.mergeView(SceneBufferRequirements.fog());
        }
        // The cloud layer and the smoke volume both march against the same-frame
        // single-sample opaque depth; requesting it here makes the dependency
        // structural instead of an implicit assumption.
        if (volumetricClouds || volumetricSmoke) {
            requirements = requirements.mergeView(SceneBufferRequirements.fog());
        }
        // DDGI queries trilinearly against the shared single-sample depth and
        // octahedral world normal; it never writes either channel.
        if (ddgi) {
            requirements = requirements.mergeView(SceneBufferRequirements.of(
                    SceneBufferChannel.DEPTH, SceneBufferChannel.NORMAL));
        }
        // VCT reconstructs world position from the shared single-sample depth.
        if (vct) {
            requirements = requirements.mergeView(SceneBufferRequirements.fog());
        }
        // Hair depth-tests against the raster depth of the geometry pass.
        if (hairStrands) {
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
                volumetricFog, volumetricQuality, volumetricEmission, waterSurface,
                volumetricParticles, stochasticTransparency, volumetricClouds,
                volumetricSmoke, weightedOit, planarReflections, caustics,
                ddgi, voxelConeTracing, hairStrands);
    }
}
