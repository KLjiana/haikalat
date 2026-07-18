package com.kaleblangley.haikalat.subsystems.ui.style;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.widget.TextField;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiStylePassTest {
    @Test
    void resolvesOnlyDirtyInheritedAndFocusAffectedNodes() {
        AtomicInteger calls = new AtomicInteger();
        StyleResolver defaults = StyleResolver.defaults(Theme.dark());
        UiStylePass pass = new UiStylePass((widget, classes, states, inherited) -> {
            calls.incrementAndGet();
            return defaults.resolve(widget, classes, states, inherited);
        });
        try (UiDocument document = new UiDocument()) {
            TextField field = new TextField();
            document.root().add(field);
            pass.resolve(document);

            calls.set(0);
            pass.resolve(document);
            assertEquals(0, calls.get(), "unchanged tree must not rebuild computed styles");

            document.focusManager().requestFocus(field);
            pass.resolve(document);
            assertEquals(1, calls.get(), "new focus must refresh its pseudo state");

            calls.set(0);
            document.focusManager().requestFocus(null);
            pass.resolve(document);
            assertEquals(1, calls.get(), "old focus must clear its pseudo state");
        }
    }
}
