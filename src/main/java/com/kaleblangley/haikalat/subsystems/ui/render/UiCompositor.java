package com.kaleblangley.haikalat.subsystems.ui.render;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.util.List;
import java.util.Objects;

/**
 * Builds deterministic UI-owned RenderGraph topology and applies explicit resource budgets.
 * Rejected layers remain on the ordinary direct UI path.
 */
public final class UiCompositor {
    public static final String LAYER_PASS_PREFIX = "UiLayer/";
    public static final String BLUR_PASS_PREFIX = "UiBlur/";

    private final UiAttachmentOptions options;
    private Diagnostics diagnostics = Diagnostics.EMPTY;
    private boolean registered;

    public UiCompositor(UiAttachmentOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Registers managed offscreen targets before topology sealing and returns the dependency
     * that the final direct overlay pass must follow.
     */
    public String registerPasses(RenderGraph graph, String dependencyPass) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(dependencyPass, "dependencyPass");
        if (registered) throw new IllegalStateException("UI compositor is already registered");
        if (graph.isTopologySealed()) throw new IllegalStateException("RenderGraph topology is sealed");
        UiCompositorPlan plan = UiCompositorPlan.build(options);
        if (plan == UiCompositorPlan.EMPTY) {
            diagnostics = Diagnostics.EMPTY;
            registered = true;
            return dependencyPass;
        }
        ensurePassNamesAvailable(graph, plan);
        String dependency = dependencyPass;
        for (UiLayerDescription layer : plan.acceptedLayers()) {
            dependency = registerLayer(graph, layer, dependency);
        }
        diagnostics = new Diagnostics(plan.acceptedIds(), plan.fallbacks(),
                plan.intermediatePixels(), plan.acceptedLayers().size(),
                plan.requestedLayers() - plan.acceptedLayers().size());
        registered = true;
        return dependency;
    }

    public Diagnostics diagnostics() { return diagnostics; }

    private static String registerLayer(RenderGraph graph, UiLayerDescription layer,
                                        String dependency) {
        String stable = layer.id();
        String layerPass = LAYER_PASS_PREFIX + stable;
        String color = "UiLayerColor/" + stable;
        graph.addPass(layerPass)
                .createColor(color, requiresLinear(layer)
                        ? RenderFormat.RGBA16F : RenderFormat.SRGB8_ALPHA8)
                .relativeSize(layer.framebufferScale())
                .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                .dependsOn(dependency)
                .execute((resources, commands) -> { });
        String result = layerPass;
        if (layer.effects().contains(UiLayerDescription.Effect.BLUR)
                || layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR)) {
            float scale = layer.framebufferScale() / layer.blurDownsample();
            String horizontal = BLUR_PASS_PREFIX + stable + "/Horizontal";
            graph.addPass(horizontal)
                    .createColor("UiBlurColor/" + stable + "/Horizontal", RenderFormat.RGBA16F)
                    .relativeSize(scale)
                    .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                    .dependsOn(result)
                    .execute((resources, commands) -> { });
            String vertical = BLUR_PASS_PREFIX + stable + "/Vertical";
            graph.addPass(vertical)
                    .createColor("UiBlurColor/" + stable + "/Vertical", RenderFormat.RGBA16F)
                    .relativeSize(scale)
                    .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                    .dependsOn(horizontal)
                    .execute((resources, commands) -> { });
            result = vertical;
        }
        return result;
    }

    private static boolean requiresLinear(UiLayerDescription layer) {
        return layer.effects().contains(UiLayerDescription.Effect.BLUR)
                || layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR)
                || layer.effects().contains(UiLayerDescription.Effect.GLOW)
                || layer.effects().contains(UiLayerDescription.Effect.COLOR_TRANSFORM);
    }

    private static void ensurePassNamesAvailable(RenderGraph graph, UiCompositorPlan plan) {
        for (UiLayerDescription layer : plan.acceptedLayers()) {
            String id = layer.id();
            requirePassAvailable(graph, LAYER_PASS_PREFIX + id);
            if (layer.effects().contains(UiLayerDescription.Effect.BLUR)
                    || layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR)) {
                requirePassAvailable(graph, BLUR_PASS_PREFIX + id + "/Horizontal");
                requirePassAvailable(graph, BLUR_PASS_PREFIX + id + "/Vertical");
            }
        }
    }

    private static void requirePassAvailable(RenderGraph graph, String name) {
        if (graph.hasPass(name)) {
            throw new IllegalArgumentException("UI compositor pass already exists: " + name);
        }
    }

    public enum FallbackReason {
        LAYER_LIMIT,
        PIXEL_BUDGET,
        BACKDROP_SOURCE_UNAVAILABLE,
        TARGET_CREATION_FAILED
    }

    public record Fallback(String layerId, FallbackReason reason) {
        public Fallback {
            Objects.requireNonNull(layerId, "layerId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Diagnostics(List<String> activeLayerIds, List<Fallback> fallbacks,
                              long intermediatePixels, int activeLayers, int directFallbacks) {
        public static final Diagnostics EMPTY =
                new Diagnostics(List.of(), List.of(), 0L, 0, 0);

        public Diagnostics {
            activeLayerIds = List.copyOf(activeLayerIds);
            fallbacks = List.copyOf(fallbacks);
            if (intermediatePixels < 0L || activeLayers < 0 || directFallbacks < 0
                    || activeLayerIds.size() != activeLayers
                    || fallbacks.size() != directFallbacks) {
                throw new IllegalArgumentException("inconsistent compositor diagnostics");
            }
        }
    }
}
