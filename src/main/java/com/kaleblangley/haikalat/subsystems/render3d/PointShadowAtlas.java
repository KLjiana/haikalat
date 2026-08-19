package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Uses one stable 3x2 block per point light to represent six 90-degree frusta. */
public final class PointShadowAtlas {
    public static final String PASS_NAME = "PointShadowPass";
    public static final String TEXTURE_NAME = "PointShadowAtlas";
    public static final int FACE_COUNT = 6;
    public static final int COLUMNS = 3;
    public static final int ROWS = 2;

    private static final Vector3f[] DIRECTIONS = {
            new Vector3f(1.0f, 0.0f, 0.0f), new Vector3f(-1.0f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, -1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.0f, 0.0f, -1.0f)
    };
    private static final Vector3f[] UPS = {
            new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(0.0f, -1.0f, 0.0f),
            new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.0f, 0.0f, -1.0f),
            new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(0.0f, -1.0f, 0.0f)
    };

    private final LocalShadowSettings settings;
    private final int capacity;

    public PointShadowAtlas(LocalShadowSettings settings) {
        this(settings, 1);
    }

    public PointShadowAtlas(LocalShadowSettings settings, int capacity) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (capacity < 1 || capacity > LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS) {
            throw new IllegalArgumentException("point shadow capacity must be in [1, 2]");
        }
        this.capacity = capacity;
    }

    public static PointShadowAtlas defaults() {
        return new PointShadowAtlas(LocalShadowSettings.defaults());
    }

    public LocalShadowSettings settings() {
        return settings;
    }

    public int capacity() {
        return capacity;
    }

    public int width() {
        return Math.multiplyExact(settings.resolution(), COLUMNS);
    }

    public int height() {
        return Math.multiplyExact(settings.resolution(), Math.multiplyExact(ROWS, capacity));
    }

    public int viewportX(int face) {
        return viewportX(0, face);
    }

    public int viewportY(int face) {
        return viewportY(0, face);
    }

    public int viewportX(int slot, int face) {
        requireSlot(slot);
        requireFace(face);
        return face % COLUMNS * settings.resolution();
    }

    public int viewportY(int slot, int face) {
        requireSlot(slot);
        requireFace(face);
        return (slot * ROWS + face / COLUMNS) * settings.resolution();
    }

    ShadowTileRect faceTile(int slot, int face) {
        int x = viewportX(slot, face);
        int y = viewportY(slot, face);
        int size = settings.resolution();
        return new ShadowTileRect(x, y, size, size,
                x / (float) width(), y / (float) height(),
                (x + size) / (float) width(), (y + size) / (float) height());
    }

    List<ShadowTileRect> faceTiles(int slot) {
        List<ShadowTileRect> result = new ArrayList<>(FACE_COUNT);
        for (int face = 0; face < FACE_COUNT; face++) result.add(faceTile(slot, face));
        return List.copyOf(result);
    }

    public List<Matrix4f> faceMatrices(SceneLight light) {
        Objects.requireNonNull(light, "light");
        if (light.type() != LightType.POINT) {
            throw new IllegalArgumentException("Point shadow atlas requires a point light");
        }
        if (settings.nearPlane() >= light.range()) {
            throw new IllegalArgumentException("point light range must exceed shadow nearPlane");
        }
        Vector3f position = light.position();
        Matrix4f projection = new Matrix4f().perspective((float) (Math.PI * 0.5),
                1.0f, settings.nearPlane(), light.range());
        List<Matrix4f> matrices = new ArrayList<>(FACE_COUNT);
        for (int face = 0; face < FACE_COUNT; face++) {
            Matrix4f view = new Matrix4f().lookAt(position,
                    new Vector3f(position).add(DIRECTIONS[face]), UPS[face]);
            matrices.add(new Matrix4f(projection).mul(view));
        }
        return List.copyOf(matrices);
    }

    private static void requireFace(int face) {
        if (face < 0 || face >= FACE_COUNT) {
            throw new IllegalArgumentException("face must be in [0, 5]");
        }
    }

    private void requireSlot(int slot) {
        if (slot < 0 || slot >= capacity) {
            throw new IllegalArgumentException("slot must be in [0, " + (capacity - 1) + "]");
        }
    }
}
