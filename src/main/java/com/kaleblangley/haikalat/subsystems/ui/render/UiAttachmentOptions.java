package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.List;
import java.util.Objects;

/** Options for attaching the UI overlay and its optional compositor topology. */
public final class UiAttachmentOptions {
    private final boolean compositorEnabled;
    private final UiBackdropSource backdropSource;
    private final List<UiLayerDescription> layers;
    private final int maximumLayers;
    private final long maximumIntermediatePixels;

    private UiAttachmentOptions(Builder builder) {
        compositorEnabled = builder.compositorEnabled;
        backdropSource = Objects.requireNonNull(builder.backdropSource, "backdropSource");
        layers = List.copyOf(builder.layers);
        maximumLayers = builder.maximumLayers;
        maximumIntermediatePixels = builder.maximumIntermediatePixels;
        if (maximumLayers <= 0) throw new IllegalArgumentException("maximumLayers must be positive");
        if (maximumIntermediatePixels <= 0L) {
            throw new IllegalArgumentException("maximumIntermediatePixels must be positive");
        }
        if (!compositorEnabled && !layers.isEmpty()) {
            throw new IllegalArgumentException("layers require compositorEnabled");
        }
    }

    public static Builder builder() { return new Builder(); }
    public boolean compositorEnabled() { return compositorEnabled; }
    public UiBackdropSource backdropSource() { return backdropSource; }
    public List<UiLayerDescription> layers() { return layers; }
    public int maximumLayers() { return maximumLayers; }
    public long maximumIntermediatePixels() { return maximumIntermediatePixels; }

    public static final class Builder {
        private boolean compositorEnabled;
        private UiBackdropSource backdropSource = UiBackdropSource.unavailable();
        private List<UiLayerDescription> layers = List.of();
        private int maximumLayers = 64;
        private long maximumIntermediatePixels = 16_000_000L;

        public Builder enableCompositor(boolean value) { compositorEnabled = value; return this; }
        public Builder backdropSource(UiBackdropSource value) { backdropSource = value; return this; }
        public Builder layers(List<UiLayerDescription> value) { layers = value; return this; }
        public Builder maximumLayers(int value) { maximumLayers = value; return this; }
        public Builder maximumIntermediatePixels(long value) {
            maximumIntermediatePixels = value;
            return this;
        }
        public UiAttachmentOptions build() { return new UiAttachmentOptions(this); }
    }
}
