package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;

final class ForwardPassBuilder {
    private ForwardPassBuilder() {
    }

    static void addForwardPasses(RenderGraph graph, RenderSettings settings, Scene scene,
                                 PostProcessSettings postProcessSettings,
                                 DirectionalShadowMap shadowMap,
                                 DirectionalCascadeSettings cascadeSettings,
                                 PointShadowAtlas pointShadowAtlas,
                                 SpotShadowAtlas spotShadowAtlas,
                                 PipelineTopology topology,
                                 boolean preserveShadowTiles,
                                 PassExecutor shadowExecutor,
                                 PassExecutor pointShadowExecutor,
                                 PassExecutor spotShadowExecutor,
                                 PassExecutor geometryExecutor) {
        boolean hasDirectionalShadow = topology.directionalShadow();
        boolean hasPointShadow = topology.pointShadow();
        boolean hasSpotShadow = topology.spotShadow();
        if (hasDirectionalShadow) {
            RenderGraph.PassBuilder pass = graph.addPass(DirectionalShadowMap.PASS_NAME)
                    .createDepthTexture(DirectionalShadowMap.TEXTURE_NAME)
                    .fixedSize(cascadeSettings.enabled() ? cascadeSettings.atlasSize()
                                    : shadowMap.settings().resolution(),
                            cascadeSettings.enabled() ? cascadeSettings.atlasSize()
                                    : shadowMap.settings().resolution());
            if (preserveShadowTiles) pass.noClear();
            else pass.clearDepthOnly();
            pass.execute(shadowExecutor);
        }
        if (hasPointShadow) {
            RenderGraph.PassBuilder pass = graph.addPass(PointShadowAtlas.PASS_NAME)
                    .createDepthTexture(PointShadowAtlas.TEXTURE_NAME)
                    .fixedSize(pointShadowAtlas.width(), pointShadowAtlas.height());
            if (preserveShadowTiles) pass.noClear();
            else pass.clearDepthOnly();
            pass.execute(pointShadowExecutor);
        }
        if (hasSpotShadow) {
            RenderGraph.PassBuilder pass = graph.addPass(SpotShadowAtlas.PASS_NAME)
                    .createDepthTexture(SpotShadowAtlas.TEXTURE_NAME)
                    .fixedSize(spotShadowAtlas.width(), spotShadowAtlas.height());
            if (preserveShadowTiles) pass.noClear();
            else pass.clearDepthOnly();
            pass.execute(spotShadowExecutor);
        }

        RenderGraph.PassBuilder geometry = graph.addPass(PostProcessTargets.GEOMETRY_PASS);
        RenderFormat sceneFormat = sceneColorFormat(settings);
        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            geometry.createColorMS(PostProcessTargets.SCENE_COLOR, sceneFormat,
                    Math.max(2, settings.msaaSamples()));
        } else {
            geometry.createColor(PostProcessTargets.SCENE_COLOR, sceneFormat);
        }
        if (postProcessSettings.fog().enabled()
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA) {
            geometry.createDepthTexture(PostProcessTargets.SCENE_DEPTH);
        } else {
            geometry.createDepth();
        }
        geometry.clearColor(0.08f, 0.10f, 0.14f, 1.0f);
        if (hasDirectionalShadow) {
            geometry.dependsOn(DirectionalShadowMap.PASS_NAME);
        }
        if (hasPointShadow) geometry.dependsOn(PointShadowAtlas.PASS_NAME);
        if (hasSpotShadow) geometry.dependsOn(SpotShadowAtlas.PASS_NAME);
        if (topology.gtaoEnabled()) {
            geometry.dependsOn(PostProcessTargets.GTAO_UPSAMPLE_PASS);
        }
        geometry.execute(geometryExecutor);
    }

    static RenderFormat sceneColorFormat(RenderSettings settings) {
        return settings.hdrEnabled() ? RenderFormat.RGBA16F : RenderFormat.SRGB8_ALPHA8;
    }
}
