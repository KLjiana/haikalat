package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;

import java.util.Objects;

/**
 * Complete immutable Render3D feature request.
 *
 * <p>The record embeds the established per-module configuration types instead
 * of duplicating their fields.  Only capabilities that had no configuration
 * before (master pause, direct/IBL contribution, per-family shadow switches,
 * HDR VFX and preview gating) get new booleans.  AO, volumetric fog, sky and
 * the colour-chain effects remain controlled by their existing settings.</p>
 *
 * <p>The opt-in composition passes ({@code volumetricParticles},
 * {@code stochasticTransparency}, {@code weightedOit}, {@code planarReflections}
 * and {@code caustics}) and the volumetric simulation passes ({@code
 * volumetricClouds}, {@code smokeSimulation}, {@code smokeRender}) are appended
 * components whose defaults are the exact {@code disabled()} value of their
 * settings type.  The 19-component constructor used before the S3 wiring is
 * retained and delegates with those disabled defaults, so existing source does
 * not break.</p>
 */
public record RenderFeatureSettings(
        boolean enabled,
        boolean directLighting,
        boolean imageBasedLighting,
        boolean directionalShadows,
        boolean pointShadows,
        boolean spotShadows,
        boolean hdrVfx,
        boolean preview,
        RenderSettings render,
        PostProcessSettings postProcess,
        VolumetricFogSettings volumetricFog,
        OutdoorEnvironmentSettings outdoorEnvironment,
        DirectionalShadowMap directionalShadowMap,
        DirectionalCascadeSettings directionalCascades,
        LocalShadowPipelineSettings localShadows,
        ClusteredLightingSettings clusteredLighting,
        WaterSurfaceSettings waterSurface,
        VolumetricParticleSettings volumetricParticles,
        StochasticTransparencySettings stochasticTransparency,
        VolumetricCloudSettings volumetricClouds,
        SmokeSimulationSettings smokeSimulation,
        SmokeRenderSettings smokeRender,
        WeightedOitSettings weightedOit,
        PlanarReflectionSettings planarReflections,
        CausticsSettings caustics) {
    public RenderFeatureSettings {
        Objects.requireNonNull(render, "render");
        Objects.requireNonNull(postProcess, "postProcess");
        Objects.requireNonNull(volumetricFog, "volumetricFog");
        Objects.requireNonNull(outdoorEnvironment, "outdoorEnvironment");
        Objects.requireNonNull(directionalShadowMap, "directionalShadowMap");
        Objects.requireNonNull(directionalCascades, "directionalCascades");
        Objects.requireNonNull(localShadows, "localShadows");
        Objects.requireNonNull(clusteredLighting, "clusteredLighting");
        Objects.requireNonNull(waterSurface, "waterSurface");
        Objects.requireNonNull(volumetricParticles, "volumetricParticles");
        Objects.requireNonNull(stochasticTransparency, "stochasticTransparency");
        Objects.requireNonNull(volumetricClouds, "volumetricClouds");
        Objects.requireNonNull(smokeSimulation, "smokeSimulation");
        Objects.requireNonNull(smokeRender, "smokeRender");
        Objects.requireNonNull(weightedOit, "weightedOit");
        Objects.requireNonNull(planarReflections, "planarReflections");
        Objects.requireNonNull(caustics, "caustics");
    }

    /**
     * Source-compatible constructor for the pre-S3-single wiring shape.  The
     * composition and volumetric requests default to their exact no-op values.
     */
    public RenderFeatureSettings(
            boolean enabled,
            boolean directLighting,
            boolean imageBasedLighting,
            boolean directionalShadows,
            boolean pointShadows,
            boolean spotShadows,
            boolean hdrVfx,
            boolean preview,
            RenderSettings render,
            PostProcessSettings postProcess,
            VolumetricFogSettings volumetricFog,
            OutdoorEnvironmentSettings outdoorEnvironment,
            DirectionalShadowMap directionalShadowMap,
            DirectionalCascadeSettings directionalCascades,
            LocalShadowPipelineSettings localShadows,
            ClusteredLightingSettings clusteredLighting,
            WaterSurfaceSettings waterSurface,
            VolumetricParticleSettings volumetricParticles,
            StochasticTransparencySettings stochasticTransparency) {
        this(enabled, directLighting, imageBasedLighting, directionalShadows, pointShadows,
                spotShadows, hdrVfx, preview, render, postProcess, volumetricFog,
                outdoorEnvironment, directionalShadowMap, directionalCascades, localShadows,
                clusteredLighting, waterSurface, volumetricParticles, stochasticTransparency,
                VolumetricCloudSettings.disabled(), SmokeSimulationSettings.disabled(),
                SmokeRenderSettings.disabled(), WeightedOitSettings.disabled(),
                PlanarReflectionSettings.disabled(), CausticsSettings.disabled());
    }

    public static RenderFeatureSettings defaults() {
        return builder().build();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .directLighting(directLighting)
                .imageBasedLighting(imageBasedLighting)
                .directionalShadows(directionalShadows)
                .pointShadows(pointShadows)
                .spotShadows(spotShadows)
                .hdrVfx(hdrVfx)
                .preview(preview)
                .render(render)
                .postProcess(postProcess)
                .volumetricFog(volumetricFog)
                .outdoorEnvironment(outdoorEnvironment)
                .directionalShadowMap(directionalShadowMap)
                .directionalCascades(directionalCascades)
                .localShadows(localShadows)
                .clusteredLighting(clusteredLighting)
                .waterSurface(waterSurface)
                .volumetricParticles(volumetricParticles)
                .stochasticTransparency(stochasticTransparency)
                .volumetricClouds(volumetricClouds)
                .smokeSimulation(smokeSimulation)
                .smokeRender(smokeRender)
                .weightedOit(weightedOit)
                .planarReflections(planarReflections)
                .caustics(caustics);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private boolean directLighting = true;
        private boolean imageBasedLighting = true;
        private boolean directionalShadows = true;
        private boolean pointShadows = true;
        private boolean spotShadows = true;
        private boolean hdrVfx = true;
        private boolean preview = true;
        private RenderSettings render = RenderSettings.builder().build();
        private PostProcessSettings postProcess = PostProcessSettings.defaults();
        private VolumetricFogSettings volumetricFog = VolumetricFogSettings.disabled();
        private OutdoorEnvironmentSettings outdoorEnvironment = OutdoorEnvironmentSettings.disabled();
        private DirectionalShadowMap directionalShadowMap = DirectionalShadowMap.defaults();
        private DirectionalCascadeSettings directionalCascades = DirectionalCascadeSettings.defaults();
        private LocalShadowPipelineSettings localShadows = LocalShadowPipelineSettings.legacyDefaults();
        private ClusteredLightingSettings clusteredLighting = ClusteredLightingSettings.defaults();
        private WaterSurfaceSettings waterSurface = WaterSurfaceSettings.disabled();
        private VolumetricParticleSettings volumetricParticles =
                VolumetricParticleSettings.disabled();
        private StochasticTransparencySettings stochasticTransparency =
                StochasticTransparencySettings.disabled();
        private VolumetricCloudSettings volumetricClouds = VolumetricCloudSettings.disabled();
        private SmokeSimulationSettings smokeSimulation = SmokeSimulationSettings.disabled();
        private SmokeRenderSettings smokeRender = SmokeRenderSettings.disabled();
        private WeightedOitSettings weightedOit = WeightedOitSettings.disabled();
        private PlanarReflectionSettings planarReflections =
                PlanarReflectionSettings.disabled();
        private CausticsSettings caustics = CausticsSettings.disabled();

        private Builder() {
        }

        public Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        public Builder directLighting(boolean value) {
            directLighting = value;
            return this;
        }

        public Builder imageBasedLighting(boolean value) {
            imageBasedLighting = value;
            return this;
        }

        public Builder directionalShadows(boolean value) {
            directionalShadows = value;
            return this;
        }

        public Builder pointShadows(boolean value) {
            pointShadows = value;
            return this;
        }

        public Builder spotShadows(boolean value) {
            spotShadows = value;
            return this;
        }

        public Builder hdrVfx(boolean value) {
            hdrVfx = value;
            return this;
        }

        public Builder preview(boolean value) {
            preview = value;
            return this;
        }

        public Builder render(RenderSettings value) {
            render = Objects.requireNonNull(value, "render");
            return this;
        }

        public Builder postProcess(PostProcessSettings value) {
            postProcess = Objects.requireNonNull(value, "postProcess");
            return this;
        }

        public Builder volumetricFog(VolumetricFogSettings value) {
            volumetricFog = Objects.requireNonNull(value, "volumetricFog");
            return this;
        }

        public Builder outdoorEnvironment(OutdoorEnvironmentSettings value) {
            outdoorEnvironment = Objects.requireNonNull(value, "outdoorEnvironment");
            return this;
        }

        public Builder directionalShadowMap(DirectionalShadowMap value) {
            directionalShadowMap = Objects.requireNonNull(value, "directionalShadowMap");
            return this;
        }

        public Builder directionalCascades(DirectionalCascadeSettings value) {
            directionalCascades = Objects.requireNonNull(value, "directionalCascades");
            return this;
        }

        public Builder localShadows(LocalShadowPipelineSettings value) {
            localShadows = Objects.requireNonNull(value, "localShadows");
            return this;
        }

        public Builder clusteredLighting(ClusteredLightingSettings value) {
            clusteredLighting = Objects.requireNonNull(value, "clusteredLighting");
            return this;
        }

        public Builder waterSurface(WaterSurfaceSettings value) {
            waterSurface = Objects.requireNonNull(value, "waterSurface");
            return this;
        }

        public Builder volumetricParticles(VolumetricParticleSettings value) {
            volumetricParticles = Objects.requireNonNull(value, "volumetricParticles");
            return this;
        }

        public Builder stochasticTransparency(StochasticTransparencySettings value) {
            stochasticTransparency = Objects.requireNonNull(value, "stochasticTransparency");
            return this;
        }

        public Builder volumetricClouds(VolumetricCloudSettings value) {
            volumetricClouds = Objects.requireNonNull(value, "volumetricClouds");
            return this;
        }

        public Builder smokeSimulation(SmokeSimulationSettings value) {
            smokeSimulation = Objects.requireNonNull(value, "smokeSimulation");
            return this;
        }

        public Builder smokeRender(SmokeRenderSettings value) {
            smokeRender = Objects.requireNonNull(value, "smokeRender");
            return this;
        }

        public Builder weightedOit(WeightedOitSettings value) {
            weightedOit = Objects.requireNonNull(value, "weightedOit");
            return this;
        }

        public Builder planarReflections(PlanarReflectionSettings value) {
            planarReflections = Objects.requireNonNull(value, "planarReflections");
            return this;
        }

        public Builder caustics(CausticsSettings value) {
            caustics = Objects.requireNonNull(value, "caustics");
            return this;
        }

        public RenderFeatureSettings build() {
            return new RenderFeatureSettings(enabled, directLighting, imageBasedLighting,
                    directionalShadows, pointShadows, spotShadows, hdrVfx, preview, render,
                    postProcess, volumetricFog, outdoorEnvironment, directionalShadowMap,
                    directionalCascades, localShadows, clusteredLighting, waterSurface,
                    volumetricParticles, stochasticTransparency, volumetricClouds,
                    smokeSimulation, smokeRender, weightedOit, planarReflections, caustics);
        }
    }
}
