package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.surface.DepthResolveDescriptor;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;

import com.kaleblangley.haikalat.core.AntiAliasingMode;

import java.util.Objects;

/** Single pure policy boundary for cross-feature topology compatibility. */
public record PipelineFeaturePolicy(PipelineTopology topology, boolean pbrEnvironmentAvailable) {
    public PipelineFeaturePolicy {
        Objects.requireNonNull(topology, "topology");
    }

    public void validate() {
        validateRenderScale();
        if ((topology.colorGrading() || topology.fog() || topology.volumetricFog()) && !topology.hdr()) {
            throw new IllegalStateException("Color grading and fog require HDR tone mapping");
        }
        if (topology.antiAliasingMode() == AntiAliasingMode.SMAA && !topology.hdr()) {
            throw new IllegalStateException("SMAA requires HDR tone mapping");
        }
        if (topology.hdrVfx() && !topology.hdr()) {
            throw new IllegalStateException("HDR VFX requires HDR tone mapping");
        }
        if (topology.pbrMaterials() && !topology.hdr()) {
            throw new IllegalStateException("metallic-roughness PBR requires HDR/ACES output");
        }
        if (topology.pbrMaterials() && !pbrEnvironmentAvailable) {
            throw new IllegalStateException(
                    "PBR scene requires an explicit borrowed PbrEnvironment");
        }
        if (topology.gtaoEnabled() && !topology.pbrMaterials()) {
            throw new IllegalStateException("GTAO requires at least one metallic-roughness PBR material");
        }
        // ALBEDO is only planned for screen-space indirect diffuse.  Keep the
        // full channel/PBR/HDR dependency explicit even if the request reaches
        // the candidate assembly without the unified feature policy.
        if (topology.sceneBuffers().requires(SceneBufferChannel.ALBEDO)) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "screen-space indirect diffuse requires HDR tone mapping");
            }
            if (!topology.pbrMaterials()) {
                throw new IllegalStateException(
                        "screen-space indirect diffuse requires at least one "
                                + "metallic-roughness PBR material (ALBEDO/ROUGHNESS)");
            }
            if (!topology.sceneBuffers().requires(SceneBufferChannel.DEPTH)
                    || !topology.sceneBuffers().requires(SceneBufferChannel.NORMAL)
                    || !topology.sceneBuffers().requires(SceneBufferChannel.ROUGHNESS)) {
                throw new IllegalStateException(
                        "screen-space indirect diffuse requires depth, normal, roughness and "
                                + "albedo scene surface channels");
            }
        }
        if (topology.sceneBuffers().requires(SceneBufferChannel.ROUGHNESS)
                && !topology.pbrMaterials()) {
            throw new IllegalStateException(
                    "screen-space reflections or indirect diffuse require at least one metallic-roughness PBR material");
        }
        if (topology.fog() && topology.antiAliasingMode() == AntiAliasingMode.MSAA) {
            DepthResolveDescriptor.forTopology(topology);
        }
        if (topology.waterSurface() && !topology.hdr()) {
            throw new IllegalStateException(
                    "water surface requires HDR tone mapping for linear absorption composition");
        }
        if (topology.waterSurface() && topology.volumetricFog()) {
            throw new IllegalStateException(
                    "water surface does not compose with volumetric fog in this version");
        }
        if (topology.waterRefraction()) {
            if (!topology.waterSurface()) {
                throw new IllegalStateException(
                        "water refraction requires an active finite water surface");
            }
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "water refraction requires HDR tone mapping");
            }
            if (topology.sceneSurface().raw(SceneBufferChannel.DEPTH) == null) {
                throw new IllegalStateException(
                        "water refraction requires the shared raster scene depth");
            }
        }
        if (topology.volumetricParticles()) {
            if (!topology.volumetricFog()) {
                throw new IllegalStateException(
                        "volumetric particles require the volumetric fog colour composition");
            }
            if (topology.sampleCount() != 1) {
                throw new IllegalStateException(
                        "volumetric particles require a resolved single-sample colour source");
            }
            if (topology.sceneSurface().single(SceneBufferChannel.DEPTH) == null) {
                throw new IllegalStateException(
                        "volumetric particles require the shared single-sample opaque depth");
            }
        }
        if (topology.stochasticTransparency()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "stochastic transparency requires HDR tone mapping for linear compositing");
            }
            if (topology.sampleCount() != 1) {
                throw new IllegalStateException(
                        "stochastic transparency requires a stable single-sample HDR target");
            }
            if (topology.sceneSurface().single(SceneBufferChannel.DEPTH) == null) {
                throw new IllegalStateException(
                        "stochastic transparency requires the shared single-sample scene depth");
            }
        }
        if (topology.volumetricClouds()) {
            requireSingleSampleHdrDepth("volumetric clouds");
        }
        if (topology.volumetricSmoke()) {
            requireSingleSampleHdrDepth("smoke volume rendering");
        }
        if (topology.weightedOit()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "weighted OIT requires HDR tone mapping for linear compositing");
            }
            if (topology.sampleCount() != 1
                    || topology.antiAliasingMode() == AntiAliasingMode.TAA) {
                throw new IllegalStateException(
                        "weighted OIT requires a stable non-jittered single-sample HDR target");
            }
            // The collector never binds or writes the shared opaque depth; a
            // missing depth surface is therefore not an error here.
        }
        if (topology.planarReflections()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "planar reflections require HDR tone mapping");
            }
        }
        if (topology.caustics()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "caustics require HDR tone mapping for additive linear composition");
            }
            if (!topology.waterSurface()) {
                throw new IllegalStateException(
                        "caustics require an active finite water surface");
            }
            if (topology.sampleCount() != 1) {
                throw new IllegalStateException(
                        "caustics require a single-sample scene colour target in this version");
            }
            if (topology.sceneSurface().raw(SceneBufferChannel.DEPTH) == null) {
                throw new IllegalStateException(
                        "caustics require the shared raster scene depth");
            }
        }
        if (topology.screenSpaceContactShadows()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "screen-space contact shadows require HDR tone mapping");
            }
            if (topology.sampleCount() != 1) {
                throw new IllegalStateException(
                        "screen-space contact shadows require a single-sample scene colour target "
                                + "in this version");
            }
            if (topology.sceneSurface().single(SceneBufferChannel.DEPTH) == null) {
                throw new IllegalStateException(
                        "screen-space contact shadows require the shared single-sample scene depth");
            }
        }
        if (topology.ddgi()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "DDGI requires HDR tone mapping for linear indirect compositing");
            }
            if (topology.sampleCount() != 1) {
                throw new IllegalStateException(
                        "DDGI requires a single-sample raster; MSAA/TAA are not supported");
            }
            if (topology.sceneSurface().single(SceneBufferChannel.DEPTH) == null
                    || topology.sceneSurface().single(SceneBufferChannel.NORMAL) == null) {
                throw new IllegalStateException(
                        "DDGI requires the shared single-sample depth and world normal");
            }
        }
        if (topology.voxelConeTracing()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "voxel cone tracing requires HDR tone mapping for linear compositing");
            }
            if (topology.sampleCount() != 1) {
                throw new IllegalStateException(
                        "voxel cone tracing requires a resolved single-sample HDR target");
            }
            if (topology.sceneSurface().single(SceneBufferChannel.DEPTH) == null) {
                throw new IllegalStateException(
                        "voxel cone tracing requires the shared single-sample scene depth");
            }
        }
        if (topology.ddgi() && topology.voxelConeTracing()) {
            throw new IllegalStateException(
                    "DDGI and voxel cone tracing are mutually exclusive in this version");
        }
        if ((topology.ddgi() || topology.voxelConeTracing())
                && topology.antiAliasingMode() == AntiAliasingMode.TAA) {
            throw new IllegalStateException(
                    "DDGI/VCT require a stable non-jittered raster; TAA is not supported");
        }
        // SSGI and DDGI/VCT both add indirect diffuse. The unified feature
        // control rejects the combination, and this assembly-time check keeps
        // hand-built topologies from double-counting it.
        if ((topology.ddgi() || topology.voxelConeTracing())
                && topology.sceneBuffers().requires(SceneBufferChannel.ALBEDO)) {
            throw new IllegalStateException(
                    "DDGI/VCT and SSGI both consume indirect diffuse; enable only one");
        }
        if (topology.hairStrands()) {
            if (!topology.hdr()) {
                throw new IllegalStateException(
                        "hair strands require HDR tone mapping for linear strand compositing");
            }
            if (topology.sceneSurface().raw(SceneBufferChannel.DEPTH) == null) {
                throw new IllegalStateException(
                        "hair strands require the shared raster depth for depth testing");
            }
        }
    }

    /**
     * Rejects every topology-represented composition that assumes a
     * full-resolution scene raster while the advanced render scale is below
     * one.  At {@code renderScale == 1.0} no branch runs, so existing topologies
     * validate exactly as before.  Compositions that only exist in
     * {@code PostProcessSettings} (depth of field, motion blur, the temporal
     * denoiser, screen-space subsurface scattering, adaptive sampling and VRS)
     * are owned by the settings gate in {@code RenderFeaturePolicy} and the
     * legacy {@code RenderPipeline} validation; they have no topology flag to
     * consult here.
     */
    private void validateRenderScale() {
        float scale = topology.renderScale();
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.MSAA,
                topology.antiAliasingMode() == AntiAliasingMode.MSAA);
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.TAA,
                topology.antiAliasingMode() == AntiAliasingMode.TAA);
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.LOW_RESOLUTION_LDR,
                !topology.hdr());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.BLOOM, topology.bloom());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.AUTO_EXPOSURE,
                topology.automaticExposure());
        // SSGI also requests the roughness channel, so its own code is checked
        // first; a topology with SSGI and SSR reports the indirect-diffuse code.
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.SSGI,
                topology.sceneBuffers().requires(SceneBufferChannel.ALBEDO));
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.SSR,
                topology.sceneBuffers().requires(SceneBufferChannel.ROUGHNESS));
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.GTAO, topology.gtaoEnabled());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.VOLUMETRIC_FOG,
                topology.volumetricFog());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.VOLUMETRIC_PARTICLES,
                topology.volumetricParticles());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.STOCHASTIC_TRANSPARENCY,
                topology.stochasticTransparency());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.VOLUMETRIC_CLOUDS,
                topology.volumetricClouds());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.VOLUMETRIC_SMOKE,
                topology.volumetricSmoke());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.WEIGHTED_OIT,
                topology.weightedOit());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.PLANAR_REFLECTIONS,
                topology.planarReflections());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.CAUSTICS,
                topology.caustics());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.DDGI, topology.ddgi());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.VOXEL_CONE_TRACING,
                topology.voxelConeTracing());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.HAIR_STRANDS,
                topology.hairStrands());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.CONTACT_SHADOWS,
                topology.screenSpaceContactShadows());
        // Dependent compositions are checked before their prerequisite so the
        // reason names the request that needs the full-resolution scene data.
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.WATER_REFRACTION,
                topology.waterRefraction());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.UNDERWATER_VOLUME,
                topology.underwaterVolume());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.WATER_SURFACE,
                topology.waterSurface());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.PROJECTED_DECALS,
                topology.projectedDecals());
        RenderScalePolicy.rejectTopology(scale, RenderScalePolicy.Feature.EMBEDDED_TARGET,
                topology.embedded());
    }

    /**
     * The two opt-in volumetric media consume the resolved HDR colour and the
     * same-frame single-sample opaque depth. This is the assembly-time defence
     * for hand-built topologies; the settings capture already keeps unsupported
     * AA modes out of the key.
     */
    private void requireSingleSampleHdrDepth(String feature) {
        if (!topology.hdr()) {
            throw new IllegalStateException(
                    feature + " requires HDR tone mapping for linear composition");
        }
        if (topology.sampleCount() != 1) {
            throw new IllegalStateException(
                    feature + " requires a resolved single-sample colour source");
        }
        if (topology.antiAliasingMode() == AntiAliasingMode.TAA) {
            throw new IllegalStateException(
                    feature + " requires a stable single-sample raster; TAA is unavailable");
        }
        if (topology.sceneSurface().single(SceneBufferChannel.DEPTH) == null) {
            throw new IllegalStateException(
                    feature + " requires the shared single-sample opaque depth");
        }
    }

    /**
     * Rejects the first-version refraction + finite-water combination instead
     * of silently overwriting one producer with the other.
     */
    public void validateRefractionWater(boolean refractionPresent) {
        if (refractionPresent && topology.waterSurface()) {
            throw new IllegalStateException(
                    "single-layer refraction and finite water surface are mutually exclusive "
                            + "in this version; disable one of them");
        }
    }

    /**
     * The first SSR version consumes opaque-only surface depth/normal/roughness
     * while the HDR colour may already contain water or glass.  Reject those
     * compositions instead of reflecting through transparent pixels.
     */
    public void validateSsrComposition(boolean ssrPresent, boolean refractionPresent) {
        if (!ssrPresent) return;
        if (topology.waterSurface()) {
            throw new IllegalStateException(
                    "screen-space reflections do not compose with the finite water surface "
                            + "in this version");
        }
        if (refractionPresent) {
            throw new IllegalStateException(
                    "screen-space reflections do not compose with single-layer refraction "
                            + "in this version");
        }
    }
}
