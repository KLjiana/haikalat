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
    static final UiCompositorPlan EMPTY = new UiCompositorPlan(List.of(), List.of(), 0L, 0);

    private final List<UiLayerDescription> acceptedLayers;
    private final List<UiCompositor.Fallback> fallbacks;
    private final long intermediatePixels;
    private final int requestedLayers;

    private UiCompositorPlan(List<UiLayerDescription> acceptedLayers,
                             List<UiCompositor.Fallback> fallbacks,
                             long intermediatePixels, int requestedLayers) {
        this.acceptedLayers = List.copyOf(acceptedLayers);
        this.fallbacks = List.copyOf(fallbacks);
        this.intermediatePixels = intermediatePixels;
        this.requestedLayers = requestedLayers;
    }

    static UiCompositorPlan build(UiAttachmentOptions options) {
        Objects.requireNonNull(options, "options");
        if (!options.compositorEnabled() || options.layers().isEmpty()) return EMPTY;
        List<UiLayerDescription> ordered = new ArrayList<>(options.layers());
        ordered.sort(Comparator.comparingInt(UiLayerDescription::zOrder)
                .thenComparing(UiLayerDescription::id));
        validateUniqueTree(ordered);
        List<UiLayerDescription> accepted = new ArrayList<>();
        List<UiCompositor.Fallback> fallbacks = new ArrayList<>();
        long pixels = 0L;
        for (UiLayerDescription layer : ordered) {
            UiCompositor.FallbackReason rejected = null;
            long layerPixels = layer.estimatedPixels();
            int intermediateCount = layer.effects().contains(UiLayerDescription.Effect.BLUR)
                    || layer.effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR) ? 3 : 1;
            long requestedPixels = Math.multiplyExact(layerPixels, intermediateCount);
            if (accepted.size() >= options.maximumLayers()) {
                rejected = UiCompositor.FallbackReason.LAYER_LIMIT;
            } else if (layerPixels > 4096L * 4096L
                    || pixels + requestedPixels > options.maximumIntermediatePixels()) {
                rejected = UiCompositor.FallbackReason.PIXEL_BUDGET;
            } else if (layer.backdropRequired() && !options.backdropSource().isAvailable()) {
                rejected = UiCompositor.FallbackReason.BACKDROP_SOURCE_UNAVAILABLE;
            }
            if (rejected != null) {
                fallbacks.add(new UiCompositor.Fallback(layer.id(), rejected));
                continue;
            }
            accepted.add(layer);
            pixels += requestedPixels;
        }
        return new UiCompositorPlan(accepted, fallbacks, pixels, ordered.size());
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
            }
        }
    }
}
