package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;

import java.util.Objects;

/** Single pure policy boundary for cross-feature topology compatibility. */
record PipelineFeaturePolicy(PipelineTopology topology, boolean pbrEnvironmentAvailable) {
    PipelineFeaturePolicy {
        Objects.requireNonNull(topology, "topology");
    }

    void validate() {
        if ((topology.colorGrading() || topology.fog()) && !topology.hdr()) {
            throw new IllegalStateException("Color grading and fog require HDR tone mapping");
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
        if (topology.fog() && topology.antiAliasingMode() == AntiAliasingMode.MSAA) {
            DepthResolveDescriptor.forTopology(topology);
        }
    }
}
