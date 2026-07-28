package com.kaleblangley.haikalat.subsystems.ui.animation;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;

import java.util.List;
import java.util.Objects;

/** Single-threaded timeline for retained UI visual and layout transitions. */
public final class UiAnimationSystem implements AutoCloseable {
    private final UiDocument document;
    private final Thread ownerThread = Thread.currentThread();
    private final UiAnimationRunList<Entry> active = new UiAnimationRunList<>();
    private long nextId;
    private long updates;
    private long started;
    private long completed;
    private long cancelled;
    private long replaced;
    private int peakActive;
    private boolean reducedMotion;
    private boolean closed;

    public UiAnimationSystem(UiDocument document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    public UiAnimationHandle tweenOpacity(UiNode node, float targetOpacity, UiTweenSpec spec) {
        if (!Float.isFinite(targetOpacity) || targetOpacity < 0.0f || targetOpacity > 1.0f) {
            throw new IllegalArgumentException("targetOpacity must be within [0, 1]");
        }
        ComputedStyle start = requireNode(node).computedStyle();
        return transitionVisual(node, new ComputedStyle(start.background(), start.foreground(),
                start.borderColor(), start.borderWidth(), start.radius(), targetOpacity,
                start.fontSize(), start.fontFamily()), spec);
    }

    public UiAnimationHandle transitionVisual(UiNode node, ComputedStyle target,
                                              UiTweenSpec spec) {
        UiNode checkedNode = requireNode(node);
        Objects.requireNonNull(target, "target");
        UiTweenSpec checkedSpec = Objects.requireNonNull(spec, "spec");
        VisualEntry entry = new VisualEntry(newHandle(), checkedNode, checkedSpec,
                checkedNode.computedStyle(), target);
        return start(entry);
    }

    public UiAnimationHandle transitionLayout(UiNode node, UiStyle target,
                                              UiTweenSpec spec) {
        UiNode checkedNode = requireNode(node);
        Objects.requireNonNull(target, "target");
        UiTweenSpec checkedSpec = Objects.requireNonNull(spec, "spec");
        requireCompatible(checkedNode.style(), target);
        LayoutEntry entry = new LayoutEntry(newHandle(), checkedNode, checkedSpec,
                checkedNode.style(), target);
        return start(entry);
    }

    public UiAnimationHandle animate(UiNode node, UiPropertyTrack track, UiTweenSpec spec) {
        return animate(node, List.of(Objects.requireNonNull(track, "track")), spec);
    }

    public UiAnimationHandle animate(UiNode node, List<UiPropertyTrack> tracks,
                                     UiTweenSpec spec) {
        UiNode checkedNode = requireNode(node);
        List<UiPropertyTrack> checkedTracks = List.copyOf(
                Objects.requireNonNull(tracks, "tracks"));
        if (checkedTracks.isEmpty()) throw new IllegalArgumentException("tracks must not be empty");
        PropertyEntry entry = new PropertyEntry(newHandle(), checkedNode,
                Objects.requireNonNull(spec, "spec"), checkedTracks);
        return start(entry);
    }

    public UiAnimationHandle transitionState(UiNode node, UiInteractionState state,
                                             List<UiPropertyTrack> tracks,
                                             UiTweenSpec spec) {
        UiNode checked = requireNode(node);
        checked.interactionState(Objects.requireNonNull(state, "state"));
        return animate(checked, tracks, spec);
    }

    public UiAnimationSystem reducedMotion(boolean value) {
        checkThread();
        ensureOpen();
        reducedMotion = value;
        return this;
    }

    public boolean reducedMotion() {
        checkThread();
        ensureOpen();
        return reducedMotion;
    }

    public void update(float deltaSeconds) {
        checkThread();
        ensureOpen();
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0.0f) {
            throw new IllegalArgumentException("animation deltaSeconds must be finite and non-negative");
        }
        for (int index = 0; index < active.size();) {
            Entry entry = active.get(index);
            if (entry.node.isClosed()) {
                cancelEntry(entry, false);
                active.removeAt(index);
            } else if (entry.handle.isPaused()) {
                index++;
            } else if (entry.advance(deltaSeconds)) {
                entry.handle.finish();
                completed = Math.incrementExact(completed);
                active.removeAt(index);
            } else {
                index++;
            }
        }
        updates = Math.incrementExact(updates);
    }

    public int activeCount() {
        checkThread();
        ensureOpen();
        return active.size();
    }

    public UiAnimationDiagnostics diagnostics() {
        checkThread();
        ensureOpen();
        int pausedCount = 0;
        int visualCount = 0;
        int layoutCount = 0;
        for (int index = 0; index < active.size(); index++) {
            Entry entry = active.get(index);
            if (entry.handle.isPaused()) pausedCount++;
            if (entry.channel == Channel.VISUAL) visualCount++;
            else layoutCount++;
        }
        return new UiAnimationDiagnostics(updates, started, completed, cancelled, replaced,
                active.size(), pausedCount, visualCount, layoutCount, peakActive);
    }

    public void cancel(UiNode node) {
        checkThread();
        ensureOpen();
        Objects.requireNonNull(node, "node");
        cancelMatching(node, null);
    }

    void cancel(UiAnimationHandle handle) {
        checkThread();
        if (closed || !handle.isActive()) return;
        for (int index = 0; index < active.size(); index++) {
            Entry entry = active.get(index);
            if (entry.handle == handle) {
                active.removeAt(index);
                cancelEntry(entry, false);
                return;
            }
        }
    }

    void pause(UiAnimationHandle handle) {
        checkThread();
        if (closed || handle.state() != UiAnimationHandle.State.ACTIVE) return;
        if (contains(handle)) handle.pauseInternal();
    }

    void resume(UiAnimationHandle handle) {
        checkThread();
        if (closed || handle.state() != UiAnimationHandle.State.PAUSED) return;
        if (contains(handle)) handle.resumeInternal();
    }

    @Override
    public void close() {
        checkThread();
        if (closed) return;
        for (int index = 0; index < active.size(); index++) {
            cancelEntry(active.get(index), false);
        }
        active.clear();
        closed = true;
    }

    private UiAnimationHandle start(Entry entry) {
        cancelMatching(entry.node, entry.channel);
        started = Math.incrementExact(started);
        if (reducedMotion
                || entry.spec.durationSeconds() == 0.0f && entry.spec.delaySeconds() == 0.0f) {
            entry.apply(1.0f, true);
            entry.handle.finish();
            completed = Math.incrementExact(completed);
        } else {
            active.add(entry);
            peakActive = Math.max(peakActive, active.size());
        }
        return entry.handle;
    }

    private void cancelMatching(UiNode node, Channel channel) {
        for (int index = 0; index < active.size();) {
            Entry entry = active.get(index);
            if (entry.node == node && (channel == null || entry.channel == channel)) {
                cancelEntry(entry, channel != null);
                active.removeAt(index);
            } else {
                index++;
            }
        }
    }

    private boolean contains(UiAnimationHandle handle) {
        for (int index = 0; index < active.size(); index++) {
            if (active.get(index).handle == handle) return true;
        }
        return false;
    }

    private void cancelEntry(Entry entry, boolean replacement) {
        entry.handle.markCancelled();
        cancelled = Math.incrementExact(cancelled);
        if (replacement) replaced = Math.incrementExact(replaced);
    }

    private UiNode requireNode(UiNode node) {
        checkThread();
        ensureOpen();
        UiNode checked = Objects.requireNonNull(node, "node");
        if (checked.isClosed() || checked.document() != document) {
            throw new IllegalArgumentException("animation target must be an open node in the owned document");
        }
        return checked;
    }

    private UiAnimationHandle newHandle() {
        return new UiAnimationHandle(Math.incrementExact(nextId), this);
    }

    private void checkThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("UI animations must be accessed from their owner thread");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UI animation system is closed");
    }

    private abstract static class Entry {
        final UiAnimationHandle handle;
        final UiNode node;
        final UiTweenSpec spec;
        final Channel channel;
        float elapsed;

        Entry(UiAnimationHandle handle, UiNode node, UiTweenSpec spec, Channel channel) {
            this.handle = handle;
            this.node = node;
            this.spec = spec;
            this.channel = channel;
        }

        boolean advance(float deltaSeconds) {
            elapsed = Math.min(Float.MAX_VALUE, elapsed + deltaSeconds);
            if (elapsed < spec.delaySeconds()) return false;
            float time = spec.durationSeconds() == 0.0f ? 1.0f
                    : Math.min(1.0f, (elapsed - spec.delaySeconds()) / spec.durationSeconds());
            boolean finished = time >= 1.0f;
            handle.progress(time);
            float progress = finished ? 1.0f : clamp01(spec.easing().apply(time));
            apply(progress, finished);
            return finished;
        }

        abstract void apply(float progress, boolean finished);
    }

    private static final class VisualEntry extends Entry {
        private final ComputedStyle start;
        private final ComputedStyle target;

        VisualEntry(UiAnimationHandle handle, UiNode node, UiTweenSpec spec,
                    ComputedStyle start, ComputedStyle target) {
            super(handle, node, spec, Channel.VISUAL);
            this.start = start;
            this.target = target;
        }

        @Override
        void apply(float progress, boolean finished) {
            if (finished) {
                node.computedStyle(target);
                return;
            }
            node.computedStyle(new ComputedStyle(
                    color(start.background(), target.background(), progress),
                    color(start.foreground(), target.foreground(), progress),
                    color(start.borderColor(), target.borderColor(), progress),
                    lerp(start.borderWidth(), target.borderWidth(), progress),
                    lerp(start.radius(), target.radius(), progress),
                    lerp(start.opacity(), target.opacity(), progress),
                    lerp(start.fontSize(), target.fontSize(), progress),
                    start.fontFamily()));
        }
    }

    private static final class LayoutEntry extends Entry {
        private final UiStyle start;
        private final UiStyle target;

        LayoutEntry(UiAnimationHandle handle, UiNode node, UiTweenSpec spec,
                    UiStyle start, UiStyle target) {
            super(handle, node, spec, Channel.LAYOUT);
            this.start = start;
            this.target = target;
        }

        @Override
        void apply(float progress, boolean finished) {
            if (finished) {
                node.style(target);
                return;
            }
            node.style(new UiStyle(
                    length(start.width(), target.width(), progress),
                    length(start.height(), target.height(), progress),
                    length(start.minWidth(), target.minWidth(), progress),
                    length(start.minHeight(), target.minHeight(), progress),
                    length(start.maxWidth(), target.maxWidth(), progress),
                    length(start.maxHeight(), target.maxHeight(), progress),
                    insets(start.margin(), target.margin(), progress),
                    insets(start.padding(), target.padding(), progress),
                    start.flexDirection(), start.justifyContent(), start.alignItems(),
                    start.alignSelf(), start.positionType(), start.overflow(),
                    lerp(start.flexGrow(), target.flexGrow(), progress),
                    lerp(start.flexShrink(), target.flexShrink(), progress),
                    lerp(start.gap(), target.gap(), progress)));
        }
    }

    private static final class PropertyEntry extends Entry {
        private final List<UiPropertyTrack> tracks;

        PropertyEntry(UiAnimationHandle handle, UiNode node, UiTweenSpec spec,
                      List<UiPropertyTrack> tracks) {
            super(handle, node, spec, Channel.VISUAL);
            this.tracks = tracks;
        }

        @Override
        void apply(float progress, boolean finished) {
            double sampled = finished ? 1.0 : progress;
            for (UiPropertyTrack track : tracks) track.apply(node, sampled);
        }
    }

    private static void requireCompatible(UiStyle start, UiStyle target) {
        if (start.flexDirection() != target.flexDirection()
                || start.justifyContent() != target.justifyContent()
                || start.alignItems() != target.alignItems()
                || start.alignSelf() != target.alignSelf()
                || start.positionType() != target.positionType()
                || start.overflow() != target.overflow()) {
            throw new IllegalArgumentException("layout transition cannot interpolate discrete style fields");
        }
        requireSameUnit(start.width(), target.width());
        requireSameUnit(start.height(), target.height());
        requireSameUnit(start.minWidth(), target.minWidth());
        requireSameUnit(start.minHeight(), target.minHeight());
        requireSameUnit(start.maxWidth(), target.maxWidth());
        requireSameUnit(start.maxHeight(), target.maxHeight());
        requireSameUnits(start.margin(), target.margin());
        requireSameUnits(start.padding(), target.padding());
    }

    private static void requireSameUnits(UiInsets start, UiInsets target) {
        requireSameUnit(start.left(), target.left());
        requireSameUnit(start.top(), target.top());
        requireSameUnit(start.right(), target.right());
        requireSameUnit(start.bottom(), target.bottom());
    }

    private static void requireSameUnit(UiLength start, UiLength target) {
        if (start.unit() != target.unit()) {
            throw new IllegalArgumentException("layout transition length units must match");
        }
    }

    private static UiInsets insets(UiInsets start, UiInsets target, float progress) {
        return new UiInsets(length(start.left(), target.left(), progress),
                length(start.top(), target.top(), progress),
                length(start.right(), target.right(), progress),
                length(start.bottom(), target.bottom(), progress));
    }

    private static UiLength length(UiLength start, UiLength target, float progress) {
        return new UiLength(start.unit(), lerp(start.value(), target.value(), progress));
    }

    private static UiColor color(UiColor start, UiColor target, float progress) {
        return new UiColor(lerp(start.red(), target.red(), progress),
                lerp(start.green(), target.green(), progress),
                lerp(start.blue(), target.blue(), progress),
                lerp(start.alpha(), target.alpha(), progress));
    }

    private static float lerp(float start, float target, float progress) {
        return start + (target - start) * progress;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private enum Channel { VISUAL, LAYOUT }
}
