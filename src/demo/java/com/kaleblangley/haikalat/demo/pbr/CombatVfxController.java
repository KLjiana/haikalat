package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.style.UiInsets;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Label;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.ui.widget.Toggle;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.VfxVisualSet;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Demo authoring controller for the fixed combat VFX process.
 *
 * <p>Draft edits never mutate the active effect.  Applying a draft builds and
 * validates a complete candidate asset, prepares its textures through the
 * renderer owner, and only then swaps the instance; any failure keeps the
 * previous effect and reports the reason.  The controller owns the CPU asset
 * and instance only — the renderer keeps every GPU handle.</p>
 */
final class CombatVfxController implements AutoCloseable {
    /** Owner-thread preparation seam; the production adapter is {@link VfxRenderer#prepare}. */
    @FunctionalInterface
    interface VfxPreparer {
        void prepare(EffectSnapshot snapshot);
    }

    private final CombatVfxCatalog catalog;
    private final VfxPreparer preparer;
    private final Path profilePath;
    private CombatVfxProfile draft;
    private CombatVfxProfile effective;
    private EffectAsset asset;
    private EffectInstance instance;
    private VfxVisualSet baselineVisuals;
    private boolean baselineActive;
    private boolean cancelled;
    private boolean pipelinePaused;
    private boolean impactSpawned;
    private boolean meshSpawned;
    private float phaseSeconds;
    private String failure = "";
    private String loadStatus = "";
    private Float dissolveOverride;
    private Label statusLabel;

    CombatVfxController(CombatVfxCatalog catalog, VfxRenderer renderer,
                        CombatVfxProfile initial, Path profilePath) {
        this(catalog, renderer::prepare, initial, profilePath);
    }

    CombatVfxController(CombatVfxCatalog catalog, VfxPreparer preparer,
                        CombatVfxProfile initial, Path profilePath) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.preparer = Objects.requireNonNull(preparer, "preparer");
        this.profilePath = Objects.requireNonNull(profilePath, "profilePath");
        draft = Objects.requireNonNull(initial, "initial");
        effective = initial;
        rebuild(initial);
    }

    CombatVfxProfile draft() {
        return draft;
    }

    CombatVfxProfile effective() {
        return effective;
    }

    String failure() {
        return failure;
    }

    String loadStatus() {
        return loadStatus;
    }

    boolean baselineActive() {
        return baselineActive;
    }

    boolean paused() {
        return instance.paused();
    }

    boolean cancelled() {
        return cancelled;
    }

    float phaseSeconds() {
        return phaseSeconds;
    }

    void setPipelinePaused(boolean value) {
        pipelinePaused = value;
    }

    void update(float deltaSeconds, Vector3f origin) {
        if (pipelinePaused || cancelled || instance.paused()) return;
        phaseSeconds += deltaSeconds;
        if (!Float.isFinite(phaseSeconds)) {
            throw new IllegalStateException("combat VFX phase clock overflowed");
        }
        instance.update(deltaSeconds, origin);
        if (!meshSpawned) {
            instance.spawnMesh(new Matrix4f().translation(origin));
            meshSpawned = true;
        }
        if (phaseSeconds >= effective.releaseSeconds()) {
            if (!instance.emitting() && phaseSeconds < effective.fadeSeconds()) {
                instance.emitting(true);
            }
            if (!impactSpawned) {
                instance.spawnDecal(new Vector3f(origin.x, -1.0f, origin.z),
                        new Vector3f(0.0f, 0.0f, 1.0f), new Vector2f(0.9f, 0.32f),
                        phaseSeconds * 0.4f);
                impactSpawned = true;
            }
        }
        if (phaseSeconds >= effective.fadeSeconds() && instance.emitting()) {
            instance.emitting(false);
        }
        instance.dissolve(dissolveAt(phaseSeconds));
        if (phaseSeconds >= effective.lightEndSeconds()) {
            // The effect has reached its defined end: remove every primitive and
            // light so the showcase returns to the same baseline as cancel().
            instance.cancel();
        }
        if (phaseSeconds > effective.lightEndSeconds() + 2.0f) replay();
    }

    private float dissolveAt(float phase) {
        if (dissolveOverride != null) return dissolveOverride;
        if (!effective.dissolveEnabled()) return 0.0f;
        if (phase <= effective.fadeSeconds()) return 0.0f;
        if (phase >= effective.lightEndSeconds()) return 1.0f;
        return (phase - effective.fadeSeconds())
                / (effective.lightEndSeconds() - effective.fadeSeconds());
    }

    EffectSnapshot snapshot(Vector3f cameraPosition) {
        EffectSnapshot raw = instance.snapshot(cameraPosition);
        if (!baselineActive) return raw;
        VfxVisualSet visuals = baselineVisuals;
        if (visuals == null) {
            visuals = baselineVisualsFor(effective);
            baselineVisuals = visuals;
        }
        return raw.withVisuals(visuals)
                .withFrameValues(raw.effectTimeSeconds(), raw.dissolveProgress(), List.of());
    }

    void replay() {
        dissolveOverride = null;
        rebuildInstance(effective);
        failure = "";
    }

    void cancel() {
        instance.cancel();
        cancelled = true;
    }

    void togglePause() {
        if (instance.paused()) instance.resume();
        else instance.pause();
    }

    /** Applies the current draft, or keeps the previous effect and reports why. */
    boolean applyDraft() {
        return applyProfile(draft);
    }

    boolean applyProfile(CombatVfxProfile candidate) {
        EffectAsset candidateAsset = null;
        EffectInstance candidateInstance = null;
        try {
            candidateAsset = CombatVfxEffectFactory.build(candidate, catalog);
            candidateInstance = candidateAsset.instantiate(candidate.seed());
            candidateInstance.spawnMesh(new Matrix4f());
            preparer.prepare(candidateInstance.snapshot(new Vector3f()));
            // The probe mesh exists only to resolve textures; clear it before
            // publication so the first real update spawns exactly one mesh at
            // the effect origin.
            candidateInstance.replay();
        } catch (RuntimeException | Error failure) {
            closeInstance(candidateInstance);
            closeAsset(candidateAsset);
            this.failure = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            return false;
        }
        closeInstance(instance);
        closeAsset(asset);
        asset = candidateAsset;
        instance = candidateInstance;
        instance.emitting(false);
        effective = candidate;
        cancelled = false;
        impactSpawned = false;
        meshSpawned = false;
        phaseSeconds = 0.0f;
        dissolveOverride = null;
        baselineActive = false;
        baselineVisuals = null;
        failure = "";
        return true;
    }

    void save() {
        try {
            CombatVfxProfileCodec.save(profilePath, effective);
            loadStatus = "saved " + profilePath.getFileName();
        } catch (RuntimeException failure) {
            loadStatus = "save failed: " + failure.getMessage();
        }
    }

    void load() {
        try {
            CombatVfxProfile loaded = CombatVfxProfileCodec.load(profilePath);
            if (applyProfile(loaded)) {
                draft = loaded;
                loadStatus = "loaded " + profilePath.getFileName();
            } else {
                loadStatus = "load rejected: " + failure;
            }
        } catch (RuntimeException failure) {
            this.failure = failure.getMessage();
            loadStatus = "load failed: " + failure.getMessage();
        }
    }

    /** A/B keeps the same instance, seed, time and resources; only visuals and lights differ. */
    void toggleBaseline() {
        if (!baselineActive) {
            baselineVisuals = baselineVisualsFor(effective);
            baselineActive = true;
        } else {
            baselineActive = false;
        }
    }

    private VfxVisualSet baselineVisualsFor(CombatVfxProfile profile) {
        CombatVfxProfile baseline = profile.toBuilder()
                .rimEnabled(false)
                .uvFlowEnabled(false)
                .dissolveEnabled(false)
                .lightEnabled(false)
                .build();
        try (EffectAsset temporary = CombatVfxEffectFactory.build(baseline, catalog)) {
            return temporary.visuals();
        }
    }

    void mutateDraft(Consumer<CombatVfxProfile.Builder> mutation) {
        CombatVfxProfile.Builder builder = draft.toBuilder();
        mutation.accept(builder);
        try {
            draft = builder.build();
        } catch (IllegalArgumentException failure) {
            this.failure = "draft rejected: " + failure.getMessage();
        }
    }

    Panel installUi(UiSystem ui, Supplier<Boolean> pipelinePauseState,
                    Consumer<Boolean> pipelinePauseSetter,
                    Consumer<Boolean> hdrVfxSetter,
                    Consumer<Boolean> directLightingSetter) {
        return installPanel(pipelinePauseState, pipelinePauseSetter, hdrVfxSetter,
                directLightingSetter);
    }

    /** Builds the authoring panel without requiring a GL-backed {@link UiSystem}. */
    Panel installPanel(Supplier<Boolean> pipelinePauseState,
                       Consumer<Boolean> pipelinePauseSetter,
                       Consumer<Boolean> hdrVfxSetter,
                       Consumer<Boolean> directLightingSetter) {
        Panel panel = new Panel();
        panel.debugName("CombatVfxAuthoring");
        panel.style(UiStyle.builder().width(UiLength.points(310)).height(UiLength.points(505))
                .padding(UiInsets.points(8))
                .gap(3).flexDirection(UiStyle.FlexDirection.COLUMN).build());
        Label title = new Label("Combat VFX 0.28");
        title.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(24)).build());
        panel.add(title);

        slider(panel, "rim intensity", 0.0, 8.0, draft.rimIntensity(),
                value -> mutateDraft(b -> b.rimIntensity((float) value)));
        slider(panel, "rim exponent", 0.25, 8.0, draft.rimExponent(),
                value -> mutateDraft(b -> b.rimExponent((float) value)));
        slider(panel, "uv speed u", -0.5, 0.5, draft.uvSpeedU(),
                value -> mutateDraft(b -> b.uvSpeedU((float) value)));
        slider(panel, "uv amplitude", 0.0, 0.5, draft.uvNoiseAmplitude(),
                value -> mutateDraft(b -> b.uvNoiseAmplitude((float) value)));
        slider(panel, "dissolve progress", 0.0, 1.0, dissolveAt(phaseSeconds),
                value -> dissolveOverride = (float) value);
        slider(panel, "dissolve edge", 0.0, 0.5, draft.dissolveEdgeWidth(),
                value -> mutateDraft(b -> b.dissolveEdgeWidth((float) value)));
        slider(panel, "light intensity", 0.0, 30.0, draft.lightIntensity(),
                value -> mutateDraft(b -> b.lightIntensity((float) value)));
        slider(panel, "light range", 0.5, 15.0, draft.lightRange(),
                value -> mutateDraft(b -> b.lightRange((float) value)));
        slider(panel, "light priority", 0.0, 20.0, draft.lightPriority(),
                value -> mutateDraft(b -> b.lightPriority((int) Math.round(value))));

        Panel actions = new Panel();
        actions.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(56)).gap(4)
                .flexDirection(UiStyle.FlexDirection.ROW).build());
        actions.add(button("Replay", this::replay));
        actions.add(button("Cancel", this::cancel));
        actions.add(button("Pause", this::togglePause));
        actions.add(button("Apply", this::applyDraft));
        actions.add(button("A/B", this::toggleBaseline));
        actions.add(button("Save", this::save));
        actions.add(button("Load", this::load));
        panel.add(actions);

        Panel gates = new Panel();
        gates.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(28)).gap(4)
                .flexDirection(UiStyle.FlexDirection.ROW).build());
        Toggle hdr = new Toggle("HDR VFX");
        hdr.value(true);
        hdr.onValueChanged(hdrVfxSetter::accept);
        Toggle direct = new Toggle("Direct light");
        direct.value(true);
        direct.onValueChanged(directLightingSetter::accept);
        Toggle pipelinePause = new Toggle("Pipeline pause");
        pipelinePause.value(pipelinePauseState.get());
        pipelinePause.onValueChanged(value -> {
            pipelinePauseSetter.accept(value);
            setPipelinePaused(value);
        });
        gates.add(hdr).add(direct).add(pipelinePause);
        panel.add(gates);

        Label status = new Label("");
        status.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(112)).build());
        statusLabel = status;
        panel.add(status);
        return panel;
    }

    void updateStatus(com.kaleblangley.haikalat.subsystems.render3d.VfxLightDiagnostics lights,
                      int textureCount) {
        Label status = statusLabel;
        if (status == null) return;
        status.text(String.format(Locale.ROOT,
                "phase %.2fs  alive=%s  paused=%s  cancelled=%s%n"
                        + "requested=%s  effective=%s  A/B=%s%n"
                        + "lights req=%d sel=%d cut=%d cap=%d %s%n"
                        + "textures=%d  draft=%s%n"
                        + "%s %s",
                phaseSeconds, instance.isAlive(), instance.paused(),
                cancelled, effective.profileId(), draft.profileId(), baselineActive,
                lights.requested(), lights.selected(), lights.culled(), lights.capacity(),
                lights.reason(), textureCount, draft.profileId(),
                failure.isEmpty() ? "" : "ERR: " + failure,
                loadStatus));
    }

    private static Slider slider(Panel panel, String label, double minimum, double maximum,
                                 double value, java.util.function.DoubleConsumer listener) {
        Label caption = new Label(label);
        caption.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(14)).build());
        Slider slider = new Slider(minimum, maximum, value);
        slider.debugName(label);
        slider.style(UiStyle.builder().width(UiLength.percent(100))
                .height(UiLength.points(18)).build());
        slider.onValueChanged(listener);
        panel.add(caption).add(slider);
        return slider;
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.style(UiStyle.builder().width(UiLength.points(58))
                .height(UiLength.points(22)).build());
        button.onClick(action);
        return button;
    }

    private void rebuild(CombatVfxProfile profile) {
        EffectAsset created = CombatVfxEffectFactory.build(profile, catalog);
        EffectInstance createdInstance = created.instantiate(profile.seed());
        closeInstance(instance);
        closeAsset(asset);
        asset = created;
        instance = createdInstance;
        instance.emitting(false);
        cancelled = false;
        impactSpawned = false;
        meshSpawned = false;
        phaseSeconds = 0.0f;
        dissolveOverride = null;
    }

    private void rebuildInstance(CombatVfxProfile profile) {
        closeInstance(instance);
        instance = asset.instantiate(profile.seed());
        instance.emitting(false);
        cancelled = false;
        impactSpawned = false;
        meshSpawned = false;
        phaseSeconds = 0.0f;
    }

    private static void closeInstance(EffectInstance value) {
        if (value != null) value.close();
    }

    private static void closeAsset(EffectAsset value) {
        if (value != null) value.close();
    }

    @Override
    public void close() {
        closeInstance(instance);
        closeAsset(asset);
        instance = null;
        asset = null;
    }
}
