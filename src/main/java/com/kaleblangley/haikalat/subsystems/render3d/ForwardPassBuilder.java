package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.RenderFormat;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;

final class ForwardPassBuilder {
    private ForwardPassBuilder() {
    }

    static void addForwardPasses(RenderGraph graph, RenderSettings settings, Scene scene,
                                 PassExecutor shadowExecutor, PassExecutor geometryExecutor) {
        boolean hasDirectionalShadow = scene.hasShadowCastingDirectionalLight();
        if (hasDirectionalShadow) {
            graph.addPass(DirectionalShadowMap.PASS_NAME)
                    .createDepthTexture(DirectionalShadowMap.TEXTURE_NAME)
                    .clearDepthOnly()
                    .execute(shadowExecutor);
        }

        RenderGraph.PassBuilder geometry = graph.addPass(PostProcessTargets.GEOMETRY_PASS);
        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            geometry.createColorMS(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA8,
                    Math.max(2, settings.msaaSamples()));
        } else {
            geometry.createColor(PostProcessTargets.SCENE_COLOR, RenderFormat.RGBA8);
        }
        geometry
                .createDepth()
                .clearColor(0.08f, 0.10f, 0.14f, 1.0f);
        if (hasDirectionalShadow) {
            geometry.dependsOn(DirectionalShadowMap.PASS_NAME);
        }
        geometry.execute(geometryExecutor);
    }
}
