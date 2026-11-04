package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, GL-free compositor candidate plan.
 *
 * <p>All ordering, budget and parent validation happens before RenderGraph is mutated.
 * {@link UiCompositor} can therefore register a complete candidate or leave the graph
 * untouched when planning fails.</p>
 */
final class UiCompositorPlan {
    static final UiCompositorPlan EMPTY = new UiCompositorPlan(List.of(), List.of(), 0L, 0, Set.of());

    private final List<UiLayerDescription> acceptedLayers;
    private final List<UiCompositor.Fallback> fallbacks;
    private final long intermediatePixels;
    private final int requestedLayers;
    private final Set<String> disabledEffects;

    private UiCompositorPlan(List<UiLayerDescription> acceptedLayers,
                             List<UiCompositor.Fallback> fallbacks,
                             long intermediatePixels, int requestedLayers, Set<String> disabledEffects) {
        this.acceptedLayers = List.copyOf(acceptedLayers);
        this.fallbacks = List.copyOf(fallbacks);
        this.intermediatePixels = intermediatePixels;
        this.requestedLayers = requestedLayers;
        this.disabledEffects=Set.copyOf(disabledEffects);
    }

    static UiCompositorPlan build(UiAttachmentOptions options) {
        return build(options,Set.of());
    }
    static UiCompositorPlan build(UiAttachmentOptions options,Set<String> forceDisableEffects) {
        return build(options,forceDisableEffects,options.maximumIntermediatePixels());
    }
    static UiCompositorPlan build(UiAttachmentOptions options,Set<String> forceDisableEffects,long maximumPixels) {
        Objects.requireNonNull(options, "options");
        if (!options.compositorEnabled() || options.layers().isEmpty()) return EMPTY;
        List<UiLayerDescription> ordered = new ArrayList<>(options.layers());
        ordered.sort(Comparator.comparingInt(UiLayerDescription::zOrder)
                .thenComparing(UiLayerDescription::id));
        validateUniqueTree(ordered);
        List<UiLayerDescription> accepted = new ArrayList<>();
        List<UiCompositor.Fallback> fallbacks = new ArrayList<>();
        long pixels = 0L;
        Set<String> disabledEffects = new HashSet<>();
        // Reserve every affordable group before spending any budget on optional filtering.
        for (UiLayerDescription layer : ordered) {
            UiCompositor.FallbackReason rejected = null;
            long plain=estimatedPixels(layer,false);
            if (accepted.size() >= options.maximumLayers()) {
                rejected = UiCompositor.FallbackReason.LAYER_LIMIT;
            } else if (plain==Long.MAX_VALUE||plain > maximumPixels-pixels) {
                rejected=plain==Long.MAX_VALUE?UiCompositor.FallbackReason.EXTENT_LIMIT:UiCompositor.FallbackReason.PIXEL_BUDGET;
            }
            if (rejected != null) {
                fallbacks.add(new UiCompositor.Fallback(layer.id(), rejected));
                continue;
            }
            accepted.add(layer);
            pixels += plain;
        }
        for(UiLayerDescription layer:accepted) {
            long requested=estimatedPixels(layer,true),plain=estimatedPixels(layer,false);
            if(forceDisableEffects.contains(layer.id())||requested==Long.MAX_VALUE||requested-plain>maximumPixels-pixels) {
                disabledEffects.add(layer.id());
                fallbacks.add(new UiCompositor.Fallback(layer.id(),UiCompositor.FallbackReason.OPTIONAL_EFFECT_BUDGET));
            } else pixels+=requested-plain;
        }
        return new UiCompositorPlan(accepted, fallbacks, pixels, ordered.size(), disabledEffects);
    }

    Set<String> disabledEffects() {return disabledEffects;}
    int directFallbacks() {return requestedLayers-acceptedLayers.size();}
    private static long estimatedPixels(UiLayerDescription layer,boolean effects) {
        boolean blur=effects&&(layer.effects().contains(UiLayerDescription.Effect.BLUR)||layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR));
        double padding=blur?layer.blurRadius():0;
        long width=(long)Math.ceil((layer.bounds().width()+padding*2)*layer.framebufferScale());
        long height=(long)Math.ceil((layer.bounds().height()+padding*2)*layer.framebufferScale());
        if(width<1||height<1||width>4096||height>4096)return Long.MAX_VALUE;
        long pixels=Math.multiplyExact(width,height);
        if(blur){int d=layer.blurDownsample();pixels=Math.addExact(pixels,Math.multiplyExact(2L,Math.multiplyExact((width+d-1)/d,(height+d-1)/d)));}
        return pixels;
    }

    List<UiLayerDescription> acceptedLayers() {
        return acceptedLayers;
    }

    List<String> acceptedIds() {
        return acceptedLayers.stream().map(UiLayerDescription::id).toList();
    }

    List<UiCompositor.Fallback> fallbacks() {
        return fallbacks;
    }

    long intermediatePixels() {
        return intermediatePixels;
    }

    int requestedLayers() {
        return requestedLayers;
    }

    private static void validateUniqueTree(List<UiLayerDescription> layers) {
        Map<String, UiLayerDescription> byId = new HashMap<>();
        for (UiLayerDescription layer : layers) {
            if (byId.put(layer.id(), layer) != null) {
                throw new IllegalArgumentException("duplicate UI layer id: " + layer.id());
            }
        }
        for (UiLayerDescription layer : layers) {
            if (!layer.parentId().isEmpty() && !byId.containsKey(layer.parentId())) {
                throw new IllegalArgumentException("missing parent layer: " + layer.parentId());
            }
            Set<String> visited = new HashSet<>();
            UiLayerDescription current = layer;
            while (!current.parentId().isEmpty()) {
                if (!visited.add(current.id())) {
                    throw new IllegalArgumentException("UI layer parent cycle at " + current.id());
                }
                current = byId.get(current.parentId());
                if(visited.size()>=8)throw new IllegalArgumentException("UI layer nesting exceeds 8: "+layer.id());
            }
        }
    }
}
