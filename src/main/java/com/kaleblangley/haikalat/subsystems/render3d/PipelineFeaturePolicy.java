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
                    "screen-space reflections require at least one metallic-roughness PBR material");
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
}
