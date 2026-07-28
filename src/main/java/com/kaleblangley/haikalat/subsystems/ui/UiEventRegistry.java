package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.event.EventPhase;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventListener;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Package-private capture/bubble listener storage.
 *
 * <p>Keeping listener mutation and dispatch-copy semantics here prevents the
 * retained node from also owning event registry policy.</p>
 */
final class UiEventRegistry {
    private final EnumMap<UiEventType, ListenerSet> listeners = new EnumMap<>(UiEventType.class);

    AutoCloseable add(UiEventType type, EventPhase phase,
                      UiEventListener<? super UiEvent> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(listener, "listener");
        ListenerSet set = listeners.computeIfAbsent(type, ignored -> new ListenerSet());
        List<UiEventListener<? super UiEvent>> target =
                phase == EventPhase.CAPTURE ? set.capture : set.bubble;
        target.add(listener);
        return () -> target.remove(listener);
    }

    void dispatch(UiEvent event, boolean capture) {
        ListenerSet set = listeners.get(Objects.requireNonNull(event, "event").type());
        if (set == null) return;
        List<UiEventListener<? super UiEvent>> source = capture ? set.capture : set.bubble;
        if (source.isEmpty()) return;
        for (UiEventListener<? super UiEvent> listener : List.copyOf(source)) {
            listener.handle(event);
        }
    }

    void clear() {
        listeners.clear();
    }

    private static final class ListenerSet {
        private final List<UiEventListener<? super UiEvent>> capture = new ArrayList<>();
        private final List<UiEventListener<? super UiEvent>> bubble = new ArrayList<>();
    }
}
