package com.kaleblangley.haikalat.subsystems.resources;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceGenerationTrackerTest {
    @Test
    void invalidationRejectsOldTicketAndAcceptsFreshPublication() {
        ResourceGenerationTracker tracker = new ResourceGenerationTracker();
        AssetId id = AssetId.of("game", "textures/albedo.png");
        ResourceGenerationTracker.Ticket old = tracker.capture(id);

        assertEquals(ResourceGeneration.INITIAL, old.generation());
        assertEquals(new ResourceGeneration(1L), tracker.invalidate(id));
        assertFalse(tracker.isCurrent(old));

        AtomicReference<String> published = new AtomicReference<>();
        assertFalse(tracker.publishIfCurrent(old, "stale", published::set));
        ResourceGenerationTracker.Ticket fresh = tracker.capture(id);
        assertTrue(tracker.publishIfCurrent(fresh, "fresh", published::set));
        assertEquals("fresh", published.get());
    }
}
