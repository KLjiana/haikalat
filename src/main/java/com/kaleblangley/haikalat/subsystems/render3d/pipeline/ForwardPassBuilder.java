package com.kaleblangley.haikalat.subsystems.render3d.pipeline;

import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;

import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.RefractionPass;

import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;

import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PointShadowAtlas;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.SpotShadowAtlas;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;

public final class ForwardPassBuilder {
    /**
     * Pass that records the sorted transparent half of the forward queue after
     * an in-between scene-colour producer (currently the projected-decal
     * batch). It aliases that producer's colour storage and the opaque raster
     * depth, so transparent materials composite over the decal without writing
     * depth and later MSAA consumers keep the topology sample count.
     */
    public static final String TRANSPARENT_PASS = "ForwardTransparency";

    private ForwardPassBuilder() {
    }

    public static void addForwardPasses(RenderGraph graph, RenderSettings settings, Scene scene,
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
                                  PassExecutor surfaceExecutor,
                                  PassExecutor surfaceResolveExecutor,
                                  PassExecutor geometryExecutor,
                                  boolean refraction) {
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

        SceneSurfaceLayout layout = topology.sceneSurface();
        var sharedDepth = layout.raw(SceneBufferChannel.DEPTH);
        if (layout.hasSurface()) {
            RenderGraph.PassBuilder surface = graph.addPass(sharedDepth.producer());
            layout.configureRaster(surface);
            if (hasDirectionalShadow) surface.dependsOn(DirectionalShadowMap.PASS_NAME);
            if (hasPointShadow) surface.dependsOn(PointShadowAtlas.PASS_NAME);
            if (hasSpotShadow) surface.dependsOn(SpotShadowAtlas.PASS_NAME);
            surface.execute(surfaceExecutor);
        }
        if (layout.hasResolve()) {
            var resolve = graph.addPass(layout.single(SceneBufferChannel.DEPTH).producer());
            layout.configureResolve(resolve);
            resolve.execute(surfaceResolveExecutor);
        }

        RenderGraph.PassBuilder geometry = graph.addPass(PostProcessTargets.GEOMETRY_PASS);
        RenderFormat sceneFormat = sceneColorFormat(settings);
        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            if (topology.volumetricFog() || refraction || topology.planarReflections()) {
                // Refraction, volume transparency and the planar-reflection view
                // need a shareable colour attachment; renderbuffer MSAA storage
                // cannot be borrowed by a second pass.
                geometry.createColorsMS(java.util.List.of(PostProcessTargets.SCENE_COLOR),
                        java.util.List.of(sceneFormat), Math.max(2, settings.msaaSamples()));
            } else {
                geometry.createColorMS(PostProcessTargets.SCENE_COLOR, sceneFormat,
                        Math.max(2, settings.msaaSamples()));
            }
        } else {
            geometry.createColor(PostProcessTargets.SCENE_COLOR, sceneFormat);
        }
        if (sharedDepth != null) {
            // Reuse the surface pass depth; only color is cleared here.
            geometry.shareDepthTexture(sharedDepth.name(), sharedDepth.producer());
            geometry.clearColorOnly(0.08f, 0.10f, 0.14f, 1.0f);
            geometry.dependsOn(sharedDepth.producer());
        } else if (((postProcessSettings.fog().enabled() || topology.hdrVfx())
                && settings.antiAliasingMode() != AntiAliasingMode.MSAA)
                || refraction || topology.projectedDecals()) {
            // Refraction shares this texture with its glass pass and samples a
            // resolved snapshot; a renderbuffer depth attachment cannot be
            // borrowed, so both paths request a depth texture. The projected-
            // decal split also needs a shareable depth texture for the
            // transparent forward half that runs after the decal batch.
            geometry.createDepthTexture(PostProcessTargets.SCENE_DEPTH);
            geometry.clearColor(0.08f, 0.10f, 0.14f, 1.0f);
        } else {
            geometry.createDepth();
            geometry.clearColor(0.08f, 0.10f, 0.14f, 1.0f);
        }
        if (hasDirectionalShadow) {
            geometry.dependsOn(DirectionalShadowMap.PASS_NAME);
        }
        if (hasPointShadow) geometry.dependsOn(PointShadowAtlas.PASS_NAME);
        if (hasSpotShadow) geometry.dependsOn(SpotShadowAtlas.PASS_NAME);
        geometry.dependsOn(graph.hasPass(ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS)
                ? ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS
                : ClusteredLightingPassBuilder.LIGHT_UPLOAD_PASS);
        if (topology.gtaoEnabled()) {
            geometry.dependsOn(PostProcessTargets.GTAO_UPSAMPLE_PASS);
        }
        geometry.execute(geometryExecutor);
    }

    /**
     * Adds the transparent half of the scene forward queue after {@code
     * colorSourcePass} (the projected-decal batch). The pass borrows the
     * producer's colour storage and the shared raster depth: no second colour
     * target is allocated, transparent materials still depth-test against the
     * opaque surface, and their ALPHA/ADDITIVE state keeps depth writes
     * disabled. {@code depthTexture} must be a texture attachment of
     * {@code depthProducer} with the same sample count as the colour storage.
     */
    public static void addTransparentForwardPass(RenderGraph graph, RenderFormat format,
                                                 int sampleCount, String colorSourcePass,
                                                 String depthTexture, String depthProducer,
                                                 PassExecutor executor) {
        graph.addPass(TRANSPARENT_PASS)
                .shareColorTextures(java.util.List.of(PostProcessTargets.SCENE_COLOR),
                        java.util.List.of(format), colorSourcePass, sampleCount)
                .shareDepthTexture(depthTexture, depthProducer)
                .noClear()
                .execute(executor);
    }

    /**
     * Adds the two refraction passes after the last pass that writes
     * {@code sceneColor}.  The copy pass produces a single-sample snapshot; the
     * glass pass borrows the scene colour and depth so it depth-tests against
     * the same visible surface while sampling only the snapshot.
     */
    public static void addRefractionPasses(RenderGraph graph, int sampleCount,
                                           RenderFormat sceneFormat, String sourcePassName,
                                           String depthTextureName, String depthProducer,
                                           PassExecutor sourceCopyExecutor,
                                           PassExecutor refractionExecutor) {
        graph.addPass(RefractionPass.SOURCE_COPY_PASS)
                .createColor(RefractionPass.SOURCE_TEXTURE, sceneFormat)
                .noClear()
                .dependsOn(sourcePassName)
                .execute(sourceCopyExecutor);
        RenderGraph.PassBuilder refraction = graph.addPass(RefractionPass.PASS_NAME)
                .shareColorTextures(java.util.List.of(PostProcessTargets.SCENE_COLOR),
                        java.util.List.of(sceneFormat), sourcePassName, sampleCount)
                .shareDepthTexture(depthTextureName, depthProducer)
                .noClear()
                .dependsOn(RefractionPass.SOURCE_COPY_PASS);
        if (!depthProducer.equals(sourcePassName)) refraction.dependsOn(depthProducer);
        refraction.execute(refractionExecutor);
    }

    public static RenderFormat sceneColorFormat(RenderSettings settings) {
        return settings.hdrEnabled() ? RenderFormat.RGBA16F : RenderFormat.SRGB8_ALPHA8;
    }

}
