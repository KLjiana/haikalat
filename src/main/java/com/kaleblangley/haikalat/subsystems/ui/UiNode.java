package com.kaleblangley.haikalat.subsystems.ui;

import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureContext;
import com.kaleblangley.haikalat.subsystems.ui.layout.MeasureResult;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiInteractionState;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiVisualTransform;
import com.kaleblangley.haikalat.subsystems.ui.event.EventPhase;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventListener;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.style.ComputedStyle;
import com.kaleblangley.haikalat.subsystems.ui.style.Theme;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.render.UiLayerDescription;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** retained-mode UI tree 的单线程可变节点。 */
public abstract class UiNode implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    private final UiId id = new UiId(NEXT_ID.getAndIncrement());
    private final UiTreeLinks tree = new UiTreeLinks(this);
    private final UiDirtyState dirty = new UiDirtyState();
    private final UiEventRegistry events = new UiEventRegistry();
    private UiStyle style = UiStyle.defaults();
    private ComputedStyle computedStyle = defaultComputedStyle();
    private LayoutBox layoutBox = LayoutBox.EMPTY;
    private UiVisibility visibility = UiVisibility.VISIBLE;
    private UiSemanticRole semanticRole = UiSemanticRole.NONE;
    private String semanticName = "";
    private String semanticValue = "";
    private String debugName = "";
    private boolean enabled = true;
    private boolean focusable;
    private boolean hitTestVisible = true;
    private boolean clipChildren;
    private UiVisualTransform visualTransform = UiVisualTransform.IDENTITY;
    private UiInteractionState interactionState = UiInteractionState.NORMAL;
    private double animatedValue;
    private double animatedGradientOffset;
    private double animatedShadowStrength;
    private double animatedEffectStrength;
    private UiLayerDescription layerDescription;
    private boolean closed;

    protected UiNode() {
    }

    public final UiId id() {
        return id;
    }

    public final UiDocument document() {
        return tree.document();
    }

    public final UiNode parent() {
        return tree.parent();
    }

    public final List<UiNode> children() {
        return tree.children();
    }

    public final UiNode add(UiNode child) {
        ensureOpen();
        Objects.requireNonNull(child, "child").ensureOpen();
        tree.prepareAdd(child);
        Runnable mutation = () -> {
            tree.appendPrepared(child);
            markTreeChanged();
        };
        mutate(mutation);
        return this;
    }

    public final UiNode remove(UiNode child) {
        ensureOpen();
        Objects.requireNonNull(child, "child");
        tree.requireChild(child);
        mutate(() -> {
            if (tree.document() != null) tree.document().beforeSubtreeDetached(child);
            tree.remove(child);
            markTreeChanged();
        });
        return this;
    }

    public final UiStyle style() {
        return style;
    }

    public final UiNode style(UiStyle value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "style");
        if (!style.equals(value)) {
            style = value;
            markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT,
                    UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
        }
        return this;
    }

    public final ComputedStyle computedStyle() {
        return computedStyle;
    }

    public final UiNode computedStyle(ComputedStyle value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "computedStyle");
        if (!computedStyle.equals(value)) {
            boolean measureChanged = Float.compare(computedStyle.fontSize(), value.fontSize()) != 0
                    || !computedStyle.fontFamily().equals(value.fontFamily());
            computedStyle = value;
            if (measureChanged) {
                markDirty(UiDirtyFlag.MEASURE, UiDirtyFlag.LAYOUT, UiDirtyFlag.PAINT);
            } else {
                markDirty(UiDirtyFlag.PAINT);
            }
        }
        return this;
    }

    public final LayoutBox layoutBox() {
        return layoutBox;
    }

    public final void applyLayout(LayoutBox value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "layoutBox");
        if (!layoutBox.equals(value)) {
            layoutBox = value;
            markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
        }
        dirty.remove(UiDirtyFlag.LAYOUT);
        dirty.remove(UiDirtyFlag.MEASURE);
    }

    public final UiVisibility visibility() {
        return visibility;
    }

    public final UiNode visibility(UiVisibility value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "visibility");
        if (visibility != value) {
            boolean layoutChanged = visibility == UiVisibility.COLLAPSED || value == UiVisibility.COLLAPSED;
            visibility = value;
            markDirty(layoutChanged ? UiDirtyFlag.LAYOUT : UiDirtyFlag.PAINT,
                    UiDirtyFlag.HIT_TEST);
        }
        return this;
    }

    public final boolean enabled() { return enabled; }
    public final UiNode enabled(boolean value) {
        ensureOpen();
        if (enabled != value) {
            enabled = value;
            interactionState = value ? UiInteractionState.NORMAL : UiInteractionState.DISABLED;
            markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.PAINT);
        }
        return this;
    }

    public final boolean focusable() { return focusable; }
    public final UiNode focusable(boolean value) { ensureOpen(); focusable = value; return this; }
    public final boolean hitTestVisible() { return hitTestVisible; }
    public final UiNode hitTestVisible(boolean value) {
        ensureOpen();
        if (hitTestVisible != value) {
            hitTestVisible = value;
            markDirty(UiDirtyFlag.HIT_TEST);
        }
        return this;
    }
    public final boolean clipChildren() { return clipChildren; }
    public final UiNode clipChildren(boolean value) {
        ensureOpen();
        if (clipChildren != value) {
            clipChildren = value;
            markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST);
        }
        return this;
    }

    public final UiVisualTransform visualTransform() {
        return visualTransform;
    }

    public final UiNode visualTransform(UiVisualTransform value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "visualTransform");
        if (!visualTransform.equals(value)) {
            visualTransform = value;
            markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST, UiDirtyFlag.COMPOSITOR);
        }
        return this;
    }

    public final UiInteractionState interactionState() {
        return interactionState;
    }

    public final UiNode interactionState(UiInteractionState value) {
        ensureOpen();
        value = Objects.requireNonNull(value, "interactionState");
        if (!enabled && value != UiInteractionState.DISABLED) {
            return this;
        }
        if (interactionState != value) {
            interactionState = value;
            markDirty(UiDirtyFlag.STYLE, UiDirtyFlag.PAINT);
        }
        return this;
    }

    public final double animatedValue() { return animatedValue; }
    public final double animatedGradientOffset() { return animatedGradientOffset; }
    public final double animatedShadowStrength() { return animatedShadowStrength; }
    public final double animatedEffectStrength() { return animatedEffectStrength; }

    public final UiNode animatedValue(double value) {
        animatedValue = requireAnimationScalar(value, "animatedValue");
        markDirty(UiDirtyFlag.PAINT);
        return this;
    }

    public final UiNode animatedGradientOffset(double value) {
        animatedGradientOffset = requireAnimationScalar(value, "animatedGradientOffset");
        markDirty(UiDirtyFlag.PAINT);
        return this;
    }

    public final UiNode animatedShadowStrength(double value) {
        animatedShadowStrength = requireAnimationScalar(value, "animatedShadowStrength");
        markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.COMPOSITOR);
        return this;
    }

    public final UiNode animatedEffectStrength(double value) {
        animatedEffectStrength = requireAnimationScalar(value, "animatedEffectStrength");
        markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.COMPOSITOR);
        return this;
    }

    public final UiLayerDescription layerDescription() { return layerDescription; }

    public final UiNode layerDescription(UiLayerDescription value) {
        ensureOpen();
        if (!Objects.equals(layerDescription, value)) {
            layerDescription = value;
            markDirty(UiDirtyFlag.PAINT, UiDirtyFlag.COMPOSITOR);
        }
        return this;
    }

    public final UiNode semantics(UiSemanticRole role, String name, String value) {
        ensureOpen();
        semanticRole = Objects.requireNonNull(role, "role");
        semanticName = Objects.requireNonNullElse(name, "");
        semanticValue = Objects.requireNonNullElse(value, "");
        markDirty(UiDirtyFlag.SEMANTICS);
        return this;
    }

    public final UiSemanticRole semanticRole() { return semanticRole; }
    public final String semanticName() { return semanticName; }
    public final String semanticValue() { return semanticValue; }
    public final String debugName() { return debugName; }
    public final UiNode debugName(String value) {
        ensureOpen();
        debugName = Objects.requireNonNullElse(value, "");
        return this;
    }

    public final Set<UiDirtyFlag> dirtyFlags() {
        return dirty.snapshot();
    }

    public final boolean isDirty(UiDirtyFlag flag) {
        return dirty.contains(flag);
    }

    public final void clearDirty(UiDirtyFlag... flags) {
        dirty.remove(flags);
    }

    public MeasureResult measure(MeasureContext context) {
        return new MeasureResult(0.0f, 0.0f);
    }

    public String widgetType() {
        return getClass().getSimpleName();
    }

    /** 子树相对 Yoga absolute layout 的额外视觉 X 偏移；滚动容器覆盖该值。 */
    public double childVisualOffsetX() { return 0.0; }

    /** 子树相对 Yoga absolute layout 的额外视觉 Y 偏移；滚动容器覆盖该值。 */
    public double childVisualOffsetY() { return 0.0; }

    /** 为指定阶段注册 listener；返回值可直接用于解除注册。 */
    public final AutoCloseable on(UiEventType type, EventPhase phase,
                                  UiEventListener<? super UiEvent> listener) {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(listener, "listener");
        return events.add(type, phase, listener);
    }

    /** 注册 target/bubble listener。 */
    public final AutoCloseable on(UiEventType type, UiEventListener<? super UiEvent> listener) {
        return on(type, EventPhase.BUBBLE, listener);
    }

    /** 控件可覆盖该方法实现未被 preventDefault 的默认行为。 */
    protected void handleDefaultEvent(UiEvent event) {
    }

    public final boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        if (tree.isRoot() && tree.document() != null && !tree.document().isClosing()) {
            throw new IllegalStateException("UI document root can only be closed by its document");
        }
        if (tree.document() != null && tree.document().dispatching()) {
            tree.document().enqueueMutation(this::closeNow);
            return;
        }
        closeNow();
    }

    final void initializeRoot(UiDocument owner) {
        tree.initializeRoot(owner);
    }

    final List<UiNode> mutableChildren() {
        return tree.mutableChildren();
    }

    final void attachTo(UiDocument owner) {
        tree.attachTo(owner);
    }

    final UiTreeLinks treeLinks() {
        return tree;
    }

    final void closeSubtree() {
        tree.closeChildren();
        events.clear();
        closed = true;
    }

    private void closeNow() {
        if (closed) return;
        if (tree.parent() != null) tree.parent().remove(this);
        closeSubtree();
    }

    final void dispatchListeners(UiEvent event, boolean capture) {
        events.dispatch(event, capture);
    }

    final void dispatchDefault(UiEvent event) {
        handleDefaultEvent(event);
    }

    protected final void markDirty(UiDirtyFlag... flags) {
        ensureOpen();
        boolean layoutPropagation = false;
        for (UiDirtyFlag flag : flags) {
            dirty.add(flag);
            layoutPropagation |= flag == UiDirtyFlag.MEASURE || flag == UiDirtyFlag.LAYOUT;
        }
        if (layoutPropagation && tree.parent() != null) {
            tree.parent().markAncestorLayoutDirty();
        }
    }

    protected final void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("UI node " + id + " is closed");
        }
    }

    private void markAncestorLayoutDirty() {
        dirty.add(UiDirtyFlag.LAYOUT);
        if (tree.parent() != null) tree.parent().markAncestorLayoutDirty();
    }

    private void markTreeChanged() {
        markDirty(UiDirtyFlag.LAYOUT, UiDirtyFlag.PAINT, UiDirtyFlag.HIT_TEST,
                UiDirtyFlag.SEMANTICS);
    }

    private void mutate(Runnable mutation) {
        if (tree.document() != null && tree.document().dispatching()) {
            tree.document().enqueueMutation(mutation);
        } else {
            mutation.run();
        }
    }

    private static ComputedStyle defaultComputedStyle() {
        return com.kaleblangley.haikalat.subsystems.ui.style.StyleResolver
                .defaults(Theme.dark()).resolve("Node", Set.of(), Set.of(), null);
    }

    private double requireAnimationScalar(double value, String name) {
        ensureOpen();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return value;
    }

}
