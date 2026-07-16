package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.style.Theme;

import java.time.Duration;
import java.util.Objects;

/** 一个窗口对应的一份不可变 UI 子系统配置。 */
public final class UiConfig {
    private final Theme theme;
    private final float maximumDeltaSeconds;
    private final long doubleClickNanos;
    private final float doubleClickDistance;
    private final int snapshotSlots;
    private final int initialPrimitiveCapacity;
    private final int maximumPrimitives;
    private final int glyphAtlasWidth;
    private final int glyphAtlasHeight;
    private final int maximumGlyphAtlasPages;
    private final boolean asynchronousSnapshots;
    private final UiDebugOptions debugOptions;

    private UiConfig(Builder builder) {
        theme = builder.theme;
        maximumDeltaSeconds = builder.maximumDeltaSeconds;
        doubleClickNanos = builder.doubleClickNanos;
        doubleClickDistance = builder.doubleClickDistance;
        snapshotSlots = builder.snapshotSlots;
        initialPrimitiveCapacity = builder.initialPrimitiveCapacity;
        maximumPrimitives = builder.maximumPrimitives;
        glyphAtlasWidth = builder.glyphAtlasWidth;
        glyphAtlasHeight = builder.glyphAtlasHeight;
        maximumGlyphAtlasPages = builder.maximumGlyphAtlasPages;
        asynchronousSnapshots = builder.asynchronousSnapshots;
        debugOptions = builder.debugOptions;
    }

    public static UiConfig defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }
    public Theme theme() { return theme; }
    public float maximumDeltaSeconds() { return maximumDeltaSeconds; }
    public long doubleClickNanos() { return doubleClickNanos; }
    public float doubleClickDistance() { return doubleClickDistance; }
    public int snapshotSlots() { return snapshotSlots; }
    public int initialPrimitiveCapacity() { return initialPrimitiveCapacity; }
    public int maximumPrimitives() { return maximumPrimitives; }
    public int glyphAtlasWidth() { return glyphAtlasWidth; }
    public int glyphAtlasHeight() { return glyphAtlasHeight; }
    public int maximumGlyphAtlasPages() { return maximumGlyphAtlasPages; }
    public boolean asynchronousSnapshots() { return asynchronousSnapshots; }
    public UiDebugOptions debugOptions() { return debugOptions; }

    /** 使用保守资源上限的配置 builder。 */
    public static final class Builder {
        private Theme theme = Theme.dark();
        private float maximumDeltaSeconds = 0.1f;
        private long doubleClickNanos = Duration.ofMillis(500).toNanos();
        private float doubleClickDistance = 5.0f;
        private int snapshotSlots = 2;
        private int initialPrimitiveCapacity = 2_048;
        private int maximumPrimitives = 65_536;
        private int glyphAtlasWidth = 1_024;
        private int glyphAtlasHeight = 1_024;
        private int maximumGlyphAtlasPages = 8;
        private boolean asynchronousSnapshots;
        private UiDebugOptions debugOptions = UiDebugOptions.NONE;

        public Builder theme(Theme value) { theme = Objects.requireNonNull(value, "theme"); return this; }
        public Builder maximumDeltaSeconds(float value) {
            if (!Float.isFinite(value) || value <= 0.0f) {
                throw new IllegalArgumentException("maximumDeltaSeconds must be finite and positive");
            }
            maximumDeltaSeconds = value;
            return this;
        }
        public Builder doubleClickInterval(Duration value) {
            long nanos = Objects.requireNonNull(value, "doubleClickInterval").toNanos();
            if (nanos <= 0L) throw new IllegalArgumentException("doubleClickInterval must be positive");
            doubleClickNanos = nanos;
            return this;
        }
        public Builder doubleClickDistance(float value) {
            if (!Float.isFinite(value) || value < 0.0f) {
                throw new IllegalArgumentException("doubleClickDistance must be finite and non-negative");
            }
            doubleClickDistance = value;
            return this;
        }
        public Builder snapshotSlots(int value) {
            if (value < 2 || value > 3) {
                throw new IllegalArgumentException("snapshotSlots must be 2 or 3");
            }
            snapshotSlots = value;
            return this;
        }
        public Builder primitiveCapacity(int initial, int maximum) {
            if (initial <= 0 || maximum < initial) {
                throw new IllegalArgumentException("primitive capacities must satisfy 0 < initial <= maximum");
            }
            initialPrimitiveCapacity = initial;
            maximumPrimitives = maximum;
            return this;
        }
        public Builder glyphAtlas(int width, int height, int maximumPages) {
            if (width <= 0 || height <= 0 || maximumPages <= 0) {
                throw new IllegalArgumentException("glyph atlas dimensions and page count must be positive");
            }
            glyphAtlasWidth = width;
            glyphAtlasHeight = height;
            maximumGlyphAtlasPages = maximumPages;
            return this;
        }
        /** 选择 snapshot 发布模型，并把默认槽数同步为同步双槽或异步三槽。 */
        public Builder asynchronousSnapshots(boolean value) {
            asynchronousSnapshots = value;
            snapshotSlots = value ? 3 : 2;
            return this;
        }
        public Builder debugOptions(UiDebugOptions value) {
            debugOptions = Objects.requireNonNull(value, "debugOptions");
            return this;
        }
        public UiConfig build() { return new UiConfig(this); }
    }
}
