package com.kaleblangley.haikalat.subsystems.resources;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Coordinates resource reload generations without depending on a GPU backend. */
public final class ResourceGenerationTracker {
    private final ConcurrentHashMap<AssetId, Slot> slots = new ConcurrentHashMap<>();

    public ResourceGeneration current(AssetId assetId) {
        Slot slot = slot(assetId);
        synchronized (slot) {
            return slot.generation;
        }
    }

    /** Captures the identity and generation that an asynchronous operation must retain. */
    public Ticket capture(AssetId assetId) {
        Slot slot = slot(assetId);
        synchronized (slot) {
            return new Ticket(assetId, slot.generation);
        }
    }

    /** Advances one asset generation, invalidating all tickets captured before this call. */
    public ResourceGeneration invalidate(AssetId assetId) {
        Slot slot = slot(assetId);
        synchronized (slot) {
            slot.generation = slot.generation.next();
            return slot.generation;
        }
    }

    public boolean isCurrent(Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        Slot slot = slot(ticket.assetId());
        synchronized (slot) {
            return slot.generation.equals(ticket.generation());
        }
    }

    /** Checks and publishes under the same per-asset generation lock. */
    public <T> boolean publishIfCurrent(Ticket ticket, T value,
                                        Consumer<? super T> publisher) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(publisher, "publisher");
        Slot slot = slot(ticket.assetId());
        synchronized (slot) {
            if (!slot.generation.equals(ticket.generation())) {
                return false;
            }
            publisher.accept(value);
            return true;
        }
    }

    private Slot slot(AssetId assetId) {
        return slots.computeIfAbsent(Objects.requireNonNull(assetId, "assetId"),
                ignored -> new Slot());
    }

    public record Ticket(AssetId assetId, ResourceGeneration generation) {
        public Ticket {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(generation, "generation");
        }
    }

    private static final class Slot {
        private ResourceGeneration generation = ResourceGeneration.INITIAL;
    }
}
