package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small deterministic stable-ID-to-slot allocator; it never owns GL resources. */
final class ShadowAtlasAllocator {
    private final long[] owners;

    ShadowAtlasAllocator(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("capacity must be non-negative");
        owners = new long[capacity];
    }

    int capacity() {
        return owners.length;
    }

    List<Long> owners() {
        List<Long> result = new ArrayList<>(owners.length);
        for (long owner : owners) if (owner != 0L) result.add(owner);
        return List.copyOf(result);
    }

    Assignment assign(List<Long> selectedIds) {
        List<Long> selected = List.copyOf(selectedIds);
        if (selected.size() > owners.length) {
            throw new IllegalArgumentException("selection exceeds allocator capacity");
        }
        long[] previous = Arrays.copyOf(owners, owners.length);
        for (int slot = 0; slot < owners.length; slot++) {
            if (owners[slot] != 0L && !selected.contains(owners[slot])) owners[slot] = 0L;
        }
        for (long id : selected) {
            if (slotOf(id) >= 0) continue;
            int free = firstFree();
            if (free < 0) throw new IllegalStateException("no free shadow slot");
            owners[free] = id;
        }
        Map<Long, Integer> slots = new LinkedHashMap<>();
        Map<Long, Boolean> newOwnership = new LinkedHashMap<>();
        for (long id : selected) {
            int slot = slotOf(id);
            slots.put(id, slot);
            newOwnership.put(id, previous[slot] != id);
        }
        return new Assignment(slots, newOwnership);
    }

    void clear() {
        Arrays.fill(owners, 0L);
    }

    private int firstFree() {
        for (int slot = 0; slot < owners.length; slot++) if (owners[slot] == 0L) return slot;
        return -1;
    }

    private int slotOf(long id) {
        for (int slot = 0; slot < owners.length; slot++) if (owners[slot] == id) return slot;
        return -1;
    }

    record Assignment(Map<Long, Integer> slots, Map<Long, Boolean> newOwnership) {
        Assignment {
            slots = Map.copyOf(slots);
            newOwnership = Map.copyOf(newOwnership);
        }

        int slot(long stableId) {
            Integer slot = slots.get(stableId);
            if (slot == null) throw new IllegalArgumentException("light is not assigned: " + stableId);
            return slot;
        }

        boolean newlyAssigned(long stableId) {
            return Boolean.TRUE.equals(newOwnership.get(stableId));
        }
    }
}
