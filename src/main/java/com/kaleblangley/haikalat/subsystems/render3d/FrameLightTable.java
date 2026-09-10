package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Frozen per-frame light values plus the stableId to frameLightIndex mapping.
 *
 * <p>Records are ordered Directional first then Local, each sorted by stableId.
 * Values are copied from the captured {@link SceneLightEntry}; later Scene
 * mutation can never change this table.</p>
 */
final class FrameLightTable {
    static final int TYPE_DIRECTIONAL = 0;
    static final int TYPE_POINT = 1;
    static final int TYPE_SPOT = 2;

    record Record(long stableId,
                  LightType type,
                  Vector3f position,
                  Vector3f direction,
                  Vector3f color,
                  float intensity,
                  float range,
                  float innerConeRadians,
                  float outerConeRadians,
                  boolean castShadows,
                  Vector3f viewPosition) {
        Record {
            position = new Vector3f(Objects.requireNonNull(position, "position"));
            direction = new Vector3f(Objects.requireNonNull(direction, "direction"));
            color = new Vector3f(Objects.requireNonNull(color, "color"));
            viewPosition = new Vector3f(Objects.requireNonNull(viewPosition, "viewPosition"));
        }

        int gpuType() {
            return switch (type) {
                case DIRECTIONAL -> TYPE_DIRECTIONAL;
                case POINT -> TYPE_POINT;
                case SPOT -> TYPE_SPOT;
            };
        }
    }

    private final List<Record> directional;
    private final List<Record> local;
    private final Map<Long, Integer> frameLightIndexByStableId;

    private FrameLightTable(List<Record> directional, List<Record> local,
                            Map<Long, Integer> frameLightIndexByStableId) {
        this.directional = List.copyOf(directional);
        this.local = List.copyOf(local);
        this.frameLightIndexByStableId = Map.copyOf(frameLightIndexByStableId);
    }

    static FrameLightTable build(List<SceneLightEntry> entries, Matrix4f view,
                                 ClusteredLightingSettings settings) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(settings, "settings");
        List<Record> directional = new ArrayList<>();
        List<Record> local = new ArrayList<>();
        Vector3f viewPosition = new Vector3f();
        for (SceneLightEntry entry : entries) {
            SceneLight light = entry.light();
            view.transformPosition(light.position(), viewPosition);
            Record record = new Record(entry.stableId(), light.type(),
                    light.position(), light.direction(), light.color(), light.intensity(),
                    light.range(), light.innerConeRadians(), light.outerConeRadians(),
                    light.castShadows(), viewPosition);
            if (light.type() == LightType.DIRECTIONAL) {
                directional.add(record);
            } else {
                local.add(record);
            }
        }
        if (directional.size() > settings.maxDirectionalLights()) {
            throw new IllegalStateException("clustered lighting received "
                    + directional.size() + " directional lights but maxDirectionalLights is "
                    + settings.maxDirectionalLights());
        }
        if (local.size() > settings.maxLocalLights()) {
            throw new IllegalStateException("clustered lighting received "
                    + local.size() + " local lights but maxLocalLights is "
                    + settings.maxLocalLights());
        }
        Comparator<Record> byStableId = Comparator.comparingLong(Record::stableId);
        directional.sort(byStableId);
        local.sort(byStableId);
        Map<Long, Integer> indices = new HashMap<>(
                Math.max(16, (directional.size() + local.size()) * 2));
        for (int index = 0; index < directional.size(); index++) {
            indices.put(directional.get(index).stableId(), index);
        }
        int localOffset = directional.size();
        for (int index = 0; index < local.size(); index++) {
            indices.put(local.get(index).stableId(), localOffset + index);
        }
        return new FrameLightTable(directional, local, indices);
    }

    List<Record> directional() {
        return directional;
    }

    List<Record> local() {
        return local;
    }

    Record record(int frameLightIndex) {
        if (frameLightIndex < 0 || frameLightIndex >= totalCount()) {
            throw new IndexOutOfBoundsException("frameLightIndex out of range: " + frameLightIndex);
        }
        return frameLightIndex < directional.size()
                ? directional.get(frameLightIndex)
                : local.get(frameLightIndex - directional.size());
    }

    /** @return the frame light index for a stable id, or -1 when the light is not in this frame */
    int frameLightIndex(long stableId) {
        Integer index = frameLightIndexByStableId.get(stableId);
        return index == null ? -1 : index;
    }

    int directionalCount() {
        return directional.size();
    }

    int localCount() {
        return local.size();
    }

    int totalCount() {
        return directional.size() + local.size();
    }
}
