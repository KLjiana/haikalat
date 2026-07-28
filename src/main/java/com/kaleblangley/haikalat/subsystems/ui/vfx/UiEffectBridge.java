package com.kaleblangley.haikalat.subsystems.ui.vfx;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiId;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.UiVisibility;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiAnimationSignal;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Orchestration bridge that consumes animation signals without mutating animation state. */
public final class UiEffectBridge implements AutoCloseable {
    private static final int MAXIMUM_DEDUP_KEYS = 8192;

    private final UiDocument document;
    private final Thread owner = Thread.currentThread();
    private final Map<UiId, UiNode> targets = new HashMap<>();
    private final Map<BindingKey, UiEffectDefinition> bindings = new HashMap<>();
    private final List<UiEffectInstance> active = new ArrayList<>();
    private final Set<SignalKey> consumed = new LinkedHashSet<>();
    private long nextInstance;
    private long started;
    private long cancelled;
    private long completed;
    private long capacityRejected;
    private int maximumActiveEffects = 256;
    private boolean reducedMotion;
    private boolean closed;

    public UiEffectBridge(UiDocument document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    public UiEffectBridge register(UiNode node) {
        check();
        ensureOpen();
        UiNode value = Objects.requireNonNull(node, "node");
        if (value.document() != document || value.isClosed()) {
            throw new IllegalArgumentException("effect target must be an open document member");
        }
        targets.put(value.id(), value);
        return this;
    }

    public UiEffectBridge bind(UiAnimationSignal.Type type, String payload,
                               UiEffectDefinition definition) {
        check();
        ensureOpen();
        bindings.put(new BindingKey(Objects.requireNonNull(type, "type"),
                Objects.requireNonNullElse(payload, "")),
                Objects.requireNonNull(definition, "definition"));
        return this;
    }

    public UiEffectBridge bindMarker(String payload, UiEffectDefinition definition) {
        return bind(UiAnimationSignal.Type.MARKER, payload, definition);
    }

    public UiEffectBridge reducedMotion(boolean value) {
        check();
        reducedMotion = value;
        return this;
    }

    public UiEffectBridge maximumActiveEffects(int value) {
        check();
        if (value <= 0 || value > 4096) {
            throw new IllegalArgumentException("maximumActiveEffects must be within [1, 4096]");
        }
        maximumActiveEffects = value;
        return this;
    }

    public void consume(List<UiAnimationSignal> signals) {
        check();
        ensureOpen();
        for (UiAnimationSignal signal : Objects.requireNonNull(signals, "signals")) {
            SignalKey signalKey = new SignalKey(signal.sequence(), signal.type(), signal.payload());
            if (!consumed.add(signalKey)) continue;
            trimDedup();
            if (signal.type() == UiAnimationSignal.Type.CANCEL) {
                cancelSequence(signal.sequence());
                continue;
            }
            UiEffectDefinition definition = bindings.get(
                    new BindingKey(signal.type(), signal.payload()));
            if (definition == null) continue;
            UiNode target = targets.get(signal.source());
            if (target == null || target.isClosed() || target.document() != document) continue;
            start(definition, target, signal.sequence());
        }
    }

    public UiEffectInstance start(UiEffectDefinition definition, UiNode target,
                                  long startSequence) {
        check();
        ensureOpen();
        register(target);
        if (active.size() >= maximumActiveEffects) {
            capacityRejected++;
            return null;
        }
        UiEffectDefinition selected = reducedMotion
                ? reducedDefinition(Objects.requireNonNull(definition, "definition"))
                : definition;
        if (selected == null) return null;
        UiEffectInstance instance = new UiEffectInstance(++nextInstance,
                selected, target.id(), startSequence);
        active.add(instance);
        started++;
        return instance;
    }

    public void update(float deltaSeconds) {
        check();
        ensureOpen();
        for (Iterator<UiEffectInstance> iterator = active.iterator(); iterator.hasNext();) {
            UiEffectInstance instance = iterator.next();
            UiNode node = targets.get(instance.target());
            if (node == null || node.isClosed() || node.document() != document) {
                instance.cancel();
            } else {
                instance.update(deltaSeconds, node.visibility() == UiVisibility.VISIBLE);
            }
            if (instance.cancelled()) {
                cancelled++;
                iterator.remove();
            } else if (instance.completed()) {
                completed++;
                iterator.remove();
            }
        }
    }

    public List<UiEffectSnapshot> snapshot() {
        check();
        ensureOpen();
        List<UiEffectSnapshot> result = new ArrayList<>(active.size());
        for (UiEffectInstance instance : active) {
            UiNode node = targets.get(instance.target());
            if (node == null || node.isClosed() || node.visibility() != UiVisibility.VISIBLE) continue;
            LayoutBox layout = document.visualLayoutBox(node);
            result.add(instance.snapshot(new UiScreenRect(
                    layout.x(), layout.y(), layout.width(), layout.height())));
        }
        return List.copyOf(result);
    }

    public Diagnostics diagnostics() {
        check();
        ensureOpen();
        int particles = 0;
        for (UiEffectInstance instance : active) {
            particles += instance.definition().maximumParticles();
        }
        return new Diagnostics(started, completed, cancelled, capacityRejected,
                active.size(), particles, consumed.size());
    }

    @Override
    public void close() {
        check();
        if (closed) return;
        for (UiEffectInstance instance : active) instance.cancel();
        active.clear();
        targets.clear();
        bindings.clear();
        consumed.clear();
        closed = true;
    }

    private void cancelSequence(long sequence) {
        for (UiEffectInstance instance : active) {
            if (instance.startSequence() == sequence) instance.cancel();
        }
    }

    private UiEffectDefinition reducedDefinition(UiEffectDefinition source) {
        if (source.reducedMotionFallback() == UiEffectDefinition.ReducedMotionFallback.NONE) {
            return null;
        }
        return UiEffectDefinition.builder(UiEffectDefinition.Type.RIPPLE)
                .duration(0.08f)
                .particleLifetime(0.08f)
                .seed(source.seed())
                .anchor(source.anchorMode())
                .localBounds(source.localBounds())
                .clip(source.clipPolicy())
                .colors(source.startColor(), source.endColor())
                .reducedMotion(UiEffectDefinition.ReducedMotionFallback.NONE)
                .build();
    }

    private void trimDedup() {
        while (consumed.size() > MAXIMUM_DEDUP_KEYS) {
            Iterator<SignalKey> iterator = consumed.iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private void check() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("UI effect bridge must be accessed from its owner thread");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UI effect bridge is closed");
    }

    private record BindingKey(UiAnimationSignal.Type type, String payload) { }
    private record SignalKey(long sequence, UiAnimationSignal.Type type, String payload) { }

    public record Diagnostics(long started, long completed, long cancelled,
                              long capacityRejected, int activeEffects,
                              int reservedParticles, int dedupKeys) {
        public Diagnostics {
            if (started < 0L || completed < 0L || cancelled < 0L || capacityRejected < 0L
                    || activeEffects < 0 || reservedParticles < 0 || dedupKeys < 0) {
                throw new IllegalArgumentException("effect diagnostics must be non-negative");
            }
        }
    }
}
