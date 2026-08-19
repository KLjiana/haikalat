package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;

import java.util.Objects;

/** Fixed 1x1 or 2x2 atlas for up to four independently stable spot-shadow tiles. */
public final class SpotShadowAtlas {
    public static final String PASS_NAME = SpotShadowMap.PASS_NAME;
    public static final String TEXTURE_NAME = SpotShadowMap.TEXTURE_NAME;

    private final LocalShadowSettings settings;
    private final int capacity;
    private final int columns;
    private final int rows;
    private final SpotShadowMap projection;

    public SpotShadowAtlas(LocalShadowSettings settings, int capacity) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (capacity < 1 || capacity > LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS) {
            throw new IllegalArgumentException("spot shadow capacity must be in [1, 4]");
        }
        this.capacity = capacity;
        columns = capacity == 1 ? 1 : 2;
        rows = (capacity + columns - 1) / columns;
        projection = new SpotShadowMap(settings);
    }

    public static SpotShadowAtlas defaults() {
        return new SpotShadowAtlas(LocalShadowSettings.defaults(), 1);
    }

    public LocalShadowSettings settings() {
        return settings;
    }

    public int capacity() {
        return capacity;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int width() {
        return Math.multiplyExact(columns, settings.resolution());
    }

    public int height() {
        return Math.multiplyExact(rows, settings.resolution());
    }

    public int viewportX(int slot) {
        requireSlot(slot);
        return slot % columns * settings.resolution();
    }

    public int viewportY(int slot) {
        requireSlot(slot);
        return slot / columns * settings.resolution();
    }

    public Matrix4f lightSpaceMatrix(SceneLight light) {
        return projection.lightSpaceMatrix(light);
    }

    ShadowTileRect tile(int slot) {
        int x = viewportX(slot);
        int y = viewportY(slot);
        int size = settings.resolution();
        return new ShadowTileRect(x, y, size, size,
                x / (float) width(), y / (float) height(),
                (x + size) / (float) width(), (y + size) / (float) height());
    }

    private void requireSlot(int slot) {
        if (slot < 0 || slot >= capacity) {
            throw new IllegalArgumentException("slot must be in [0, " + (capacity - 1) + "]");
        }
    }
}
