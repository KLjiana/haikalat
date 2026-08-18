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
                                 SpotShadowMap spotShadowMap,
                                 PassExecutor shadowExecutor,
                                 PassExecutor pointShadowExecutor,
                                 PassExecutor spotShadowExecutor,
                                 PassExecutor geometryExecutor) {
        boolean hasDirectionalShadow = LightingBinder.shadowDirectionalLight(scene).isPresent();
        boolean hasPointShadow = LightingBinder.shadowPointLight(scene).isPresent();
        boolean hasSpotShadow = LightingBinder.shadowSpotLight(scene).isPresent();
        if (hasDirectionalShadow) {
            graph.addPass(DirectionalShadowMap.PASS_NAME)
                    .createDepthTexture(DirectionalShadowMap.TEXTURE_NAME)
                    .fixedSize(cascadeSettings.enabled() ? cascadeSettings.atlasSize()
                                    : shadowMap.settings().resolution(),
                            cascadeSettings.enabled() ? cascadeSettings.atlasSize()
                                    : shadowMap.settings().resolution())
                    .clearDepthOnly()
                    .execute(shadowExecutor);
        }
        if (hasPointShadow) {
            graph.addPass(PointShadowAtlas.PASS_NAME)
                    .createDepthTexture(PointShadowAtlas.TEXTURE_NAME)
                    .fixedSize(pointShadowAtlas.width(), pointShadowAtlas.height())
                    .clearDepthOnly()
                    .execute(pointShadowExecutor);
        }
        if (hasSpotShadow) {
            graph.addPass(SpotShadowMap.PASS_NAME)
                    .createDepthTexture(SpotShadowMap.TEXTURE_NAME)
                    .fixedSize(spotShadowMap.settings().resolution(),
                            spotShadowMap.settings().resolution())
                    .clearDepthOnly()
                    .execute(spotShadowExecutor);
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
        if (hasSpotShadow) geometry.dependsOn(SpotShadowMap.PASS_NAME);
        geometry.execute(geometryExecutor);
    }

    static RenderFormat sceneColorFormat(RenderSettings settings) {
        return settings.hdrEnabled() ? RenderFormat.RGBA16F : RenderFormat.SRGB8_ALPHA8;
    }
}
