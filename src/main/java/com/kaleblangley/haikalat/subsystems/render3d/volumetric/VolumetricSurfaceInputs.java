package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;

import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;

import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;

import com.kaleblangley.haikalat.core.graph.PassResources;

import static com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS;
import static com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH;

/** Fog combines graph-owned geometry color with raw and single-sample shared depth. */
public record VolumetricSurfaceInputs(SceneSurfaceInputs surface) {
    SceneSurfaceLayout.Attachment rawDepth() { return surface.layout().raw(DEPTH); }
    SceneSurfaceLayout.Attachment singleDepth() { return surface.single(DEPTH); }

    int rawDepth(PassResources resources) { return surface.frame().require(resources, rawDepth()); }
    int singleDepth(PassResources resources) { return surface.frame().require(resources, singleDepth()); }

    public void validate() {
        var graph = surface.graph().description();
        var geometry = SceneSurfaceInputs.pass(graph, GEOMETRY_PASS);
        if (geometry.width() != graph.width() || geometry.height() != graph.height()
                || geometry.samples() != rawDepth().samples()) {
            throw new IllegalArgumentException("invalid volumetric geometry extent or samples");
        }
        SceneSurfaceInputs.validateAttachment(geometry, rawDepth());
        if (geometry.colorAttachments().isEmpty() || !geometry.colorAttachments().getFirst().format().equals("RGBA16F")) {
            throw new IllegalArgumentException("volumetric geometry color must be linear RGBA16F");
        }
        surface.validateDependency(VolumetricPassBuilder.SURFACE_GUIDE_PASS, singleDepth());
        surface.validateDependency(VolumetricPassBuilder.COMPOSITE_PASS, rawDepth());
    }
}
