package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseStackTest {
    @Test
    void closesOwnedResourcesInReverseOrderAndIsIdempotent() {
        List<String> closed = new ArrayList<>();
        CloseStack stack = new CloseStack();
        stack.own(() -> closed.add("first"));
        stack.own(() -> closed.add("second"));

        stack.close();
        stack.close();

        assertEquals(List.of("second", "first"), closed);
    }

    @Test
    void preservesAllCleanupFailuresAsSuppressed() {
        CloseStack stack = new CloseStack();
        stack.own(() -> { throw new IllegalStateException("first"); });
        stack.own(() -> { throw new IllegalArgumentException("second"); });

        RuntimeException failure = assertThrows(RuntimeException.class, stack::close);

        assertEquals("second", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("first", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void constructionFailureRemainsPrimaryWhenRollbackAlsoFails() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            try (CloseStack stack = new CloseStack()) {
                stack.own(() -> { throw new IllegalArgumentException("cleanup"); });
                throw new IllegalStateException("construction");
            }
        });

        assertEquals("construction", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("cleanup", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void releasedResourcesRemainOwnedByTheCaller() {
        List<String> closed = new ArrayList<>();
        CloseStack stack = new CloseStack();
        stack.own(() -> closed.add("resource"));
        stack.releaseOwnership();
        stack.close();

        assertTrue(closed.isEmpty());
    }
}
