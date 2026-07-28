package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiNodeChildrenTest {
    @Test
    void reusesStableSnapshotsAndInvalidatesThemAfterTreeMutation() {
        try (UiDocument document = new UiDocument()) {
            Panel parent = document.root();
            Panel child = new Panel();
            List<UiNode> emptySnapshot = parent.children();

            parent.add(child);
            List<UiNode> populatedSnapshot = parent.children();

            assertTrue(emptySnapshot.isEmpty(), "previous snapshots must remain immutable");
            assertEquals(List.of(child), populatedSnapshot);
            assertSame(populatedSnapshot, parent.children(), "unchanged tree must reuse its snapshot");

            parent.remove(child);
            assertTrue(parent.children().isEmpty());
            assertEquals(List.of(child), populatedSnapshot,
                    "removing a child must not mutate an already returned snapshot");
        }
    }

    @Test
    void closeClearsCurrentChildViewWithoutMutatingPublishedSnapshots() {
        Panel parent = new Panel();
        Panel child = new Panel();
        parent.add(child);
        List<UiNode> published = parent.children();

        parent.close();

        assertTrue(parent.children().isEmpty());
        assertEquals(List.of(child), published,
                "closing a subtree must not mutate an already returned snapshot");
        assertTrue(child.isClosed());
    }
}
