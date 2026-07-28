package com.kaleblangley.haikalat.subsystems.scene;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Immutable options for CPU scene loading and optional development reload. */
public final class SceneAssetOptions {
    private final int maxSceneBytes;
    private final boolean strictExtensions;
    private final HotReloadMode hotReload;
    private final Duration reloadDebounce;
    private final Executor executor;

    private SceneAssetOptions(Builder builder) {
        maxSceneBytes = builder.maxSceneBytes;
        strictExtensions = builder.strictExtensions;
        hotReload = builder.hotReload;
        reloadDebounce = builder.reloadDebounce;
        executor = builder.executor;
    }

    public static SceneAssetOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxSceneBytes() { return maxSceneBytes; }
    public boolean strictExtensions() { return strictExtensions; }
    public HotReloadMode hotReload() { return hotReload; }
    public Duration reloadDebounce() { return reloadDebounce; }
    public Executor executor() { return executor; }

    public static final class Builder {
        private int maxSceneBytes = SceneJsonParser.MAX_DOCUMENT_BYTES;
        private boolean strictExtensions = true;
        private HotReloadMode hotReload = HotReloadMode.MANUAL;
        private Duration reloadDebounce = Duration.ofMillis(150);
        private Executor executor;

        public Builder maxSceneBytes(int value) {
            if (value <= 0 || value > SceneJsonParser.MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("maxSceneBytes must be in 1.."
                        + SceneJsonParser.MAX_DOCUMENT_BYTES);
            }
            maxSceneBytes = value;
            return this;
        }

        public Builder strictExtensions(boolean value) {
            strictExtensions = value;
            return this;
        }

        public Builder hotReload(HotReloadMode value) {
            hotReload = Objects.requireNonNull(value, "hotReload");
            return this;
        }

        public Builder reloadDebounce(Duration value) {
            Objects.requireNonNull(value, "reloadDebounce");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("reloadDebounce must be positive");
            }
            reloadDebounce = value;
            return this;
        }

        public Builder executor(Executor value) {
            executor = Objects.requireNonNull(value, "executor");
            return this;
        }

        public SceneAssetOptions build() {
            return new SceneAssetOptions(this);
        }
    }
}
