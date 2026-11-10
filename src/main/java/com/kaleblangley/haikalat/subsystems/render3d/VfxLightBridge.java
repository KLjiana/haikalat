package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.vfx.VfxLight;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merges frozen effect lights into the ordinary frame light input.
 *
 * <p>The bridge owns only the temporary key-to-stable-ID mapping; it never
 * mutates the user Scene and never deletes a user light.  Capacity selection is
 * deterministic (priority, then effect identity), and a selected light keeps
 * its entry revision when only unrelated lights change so no downstream cache
 * is invalidated for a value that did not move.</p>
 */
final class VfxLightBridge {
    static final int MAXIMUM_REQUESTED_LIGHTS = 256;

    private final Map<VfxLight.Key, State> states = new HashMap<>();

    record Result(List<SceneLightEntry> extraEntries, VfxLightDiagnostics diagnostics) {
        Result {
            extraEntries = List.copyOf(extraEntries);
        }
    }

    Result merge(List<SceneLightEntry> sceneEntries, List<VfxLight> requested,
                 int maxLocalLights, int maxVfxLights, boolean hdrVfxEnabled,
                 boolean directLightingEnabled, long inputRevision) {
        Objects.requireNonNull(sceneEntries, "sceneEntries");
        Objects.requireNonNull(requested, "requested");
        if (maxLocalLights < 0) throw new IllegalArgumentException("maxLocalLights must be non-negative");
        if (maxVfxLights < 0) throw new IllegalArgumentException("maxVfxLights must be non-negative");
        if (requested.size() > MAXIMUM_REQUESTED_LIGHTS) {
            throw new IllegalArgumentException("vfx light request exceeds "
                    + MAXIMUM_REQUESTED_LIGHTS + " entries");
        }
        Set<VfxLight.Key> keys = new HashSet<>();
        for (VfxLight light : requested) {
            Objects.requireNonNull(light, "light");
            if (!keys.add(light.key())) {
                throw new IllegalArgumentException("duplicate vfx light key " + light.key());
            }
        }
        if (!hdrVfxEnabled) {
            states.clear();
            return new Result(List.of(), new VfxLightDiagnostics(
                    requested.size(), 0, requested.size(), 0,
                    requested.isEmpty() ? "no-active-light" : "hdr-vfx-disabled",
                    inputRevision));
        }
        int sceneLocal = 0;
        for (SceneLightEntry entry : sceneEntries) {
            if (entry.light().type() != LightType.DIRECTIONAL) sceneLocal++;
        }
        int available = Math.max(0, maxLocalLights - sceneLocal);
        int capacity = Math.min(maxVfxLights, available);
        List<VfxLight> ordered = new ArrayList<>(requested);
        ordered.sort(Comparator.comparingInt(VfxLight::priority).reversed()
                .thenComparingLong(VfxLight::effectId)
                .thenComparingInt(VfxLight::lightIndex));
        int selectedCount = Math.min(capacity, ordered.size());
        List<SceneLightEntry> extraEntries = new ArrayList<>(selectedCount);
        Set<VfxLight.Key> retained = new HashSet<>(selectedCount * 2);
        for (int index = 0; index < selectedCount; index++) {
            VfxLight light = ordered.get(index);
            retained.add(light.key());
            State state = states.get(light.key());
            if (state == null) {
                state = new State(LightIdAllocator.next());
                states.put(light.key(), state);
            }
            SceneLight value = SceneLight.point(light.position(), light.color(),
                    light.intensity(), light.range());
            if (!value.equals(state.light)) {
                state.revision = Math.incrementExact(state.revision);
                state.light = value;
            }
            extraEntries.add(new SceneLightEntry(state.stableId, value,
                    ShadowLightHints.DEFAULT, LightVolumeHints.DISABLED, state.revision));
        }
        states.keySet().removeIf(key -> !retained.contains(key));
        String reason;
        if (ordered.isEmpty()) reason = "no-active-light";
        else if (selectedCount < ordered.size()) reason = "capacity-culled";
        else if (!directLightingEnabled) reason = "direct-lighting-disabled";
        else reason = "";
        return new Result(extraEntries, new VfxLightDiagnostics(
                ordered.size(), selectedCount, ordered.size() - selectedCount,
                capacity, reason, inputRevision));
    }

    /** Releases the temporary mapping; user scene lights are never touched. */
    void clear() {
        states.clear();
    }

    int mappedLightCount() {
        return states.size();
    }

    private static final class State {
        private final long stableId;
        private SceneLight light;
        private long revision;

        private State(long stableId) {
            this.stableId = stableId;
        }
    }
}
