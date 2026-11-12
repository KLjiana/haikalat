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
