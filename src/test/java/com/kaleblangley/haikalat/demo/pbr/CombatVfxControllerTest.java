package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.ui.UiDocument;
import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.event.PointerEvent;
import com.kaleblangley.haikalat.subsystems.ui.event.UiEventType;
import com.kaleblangley.haikalat.subsystems.ui.layout.LayoutBox;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.Slider;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.windowing.input.KeyModifiers;
import com.kaleblangley.haikalat.subsystems.windowing.input.MouseButton;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatVfxControllerTest {
    @TempDir
    Path directory;

    @Test
    void draftEditsStayLocalUntilApplyPublishesACompleteCandidate() {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            assertEquals("combat-default", controller.effective().profileId());
            controller.mutateDraft(builder -> builder.profileId("draft-edit").rimIntensity(5.0f));
            assertEquals("draft-edit", controller.draft().profileId());
            assertEquals("combat-default", controller.effective().profileId());
            assertEquals(0, preparer.calls);

            assertTrue(controller.applyDraft());
            assertEquals("draft-edit", controller.effective().profileId());
            assertTrue(preparer.calls > 0);
            controller.update(0.1f, new Vector3f());
            assertEquals(1, controller.snapshot(new Vector3f()).meshes().size(),
                    "Apply must not leave the preparation probe mesh in the published effect");
            assertEquals("", controller.failure());
        }
    }

    @Test
    void invalidDraftAndFailedPreparationKeepThePreviousEffect() {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            controller.mutateDraft(builder -> builder.profileId("valid-edit"));
            assertTrue(controller.applyDraft());
            String published = controller.effective().profileId();

            controller.mutateDraft(builder -> builder.dissolveEdgeWidth(2.0f));
            assertEquals(published, controller.draft().profileId(),
                    "invalid draft must not replace the stored draft");
            assertTrue(controller.failure().contains("draft rejected"));

            preparer.failNext = true;
            controller.mutateDraft(builder -> builder.profileId("prepare-failure"));
            assertFalse(controller.applyDraft());
            assertEquals(published, controller.effective().profileId());
            assertTrue(controller.failure().contains("injected preparation failure"));

            controller.mutateDraft(builder -> builder.meshId("missing-mesh"));
            assertFalse(controller.applyDraft());
            assertEquals(published, controller.effective().profileId());
            assertTrue(controller.failure().contains("unknown combat VFX mesh resource"));
        }
    }

    @Test
    void loadFailureKeepsTheActiveProfileAndRecoversFromAValidFile() throws Exception {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            controller.mutateDraft(builder -> builder.profileId("saved-profile"));
            assertTrue(controller.applyDraft());
            controller.save();
            Path file = directory.resolve("combat-vfx.properties");
            assertTrue(Files.isRegularFile(file));

            Files.writeString(file, "schemaVersion=1\nprofileId=broken\n");
            controller.load();
            assertEquals("saved-profile", controller.effective().profileId());
            assertTrue(controller.loadStatus().contains("load failed"));

            CombatVfxProfileCodec.save(file, CombatVfxProfile.defaults()
                    .toBuilder().profileId("loaded-profile").build());
            controller.load();
            assertEquals("loaded-profile", controller.effective().profileId());
            assertTrue(controller.loadStatus().contains("loaded"));
        }
    }

    @Test
    void baselineAbKeepsSeedTimeAndResourcesWhileOnlyVisualsAndLightsChange() {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            controller.mutateDraft(builder -> builder.seed(0x1234L)
                    .chargeSeconds(0.4f).releaseSeconds(0.9f)
                    .fadeSeconds(2.0f).lightEndSeconds(2.8f));
            assertTrue(controller.applyDraft());
            controller.update(0.5f, new Vector3f());
            float phase = controller.phaseSeconds();
            assertTrue(phase > 0.0f);
            CombatVfxProfile author = controller.effective();
            controller.toggleBaseline();
            assertTrue(controller.baselineActive());
            assertEquals(author.profileId(), controller.effective().profileId());
            assertEquals(author.seed(), controller.effective().seed());
            assertEquals(author.releaseSeconds(), controller.effective().releaseSeconds());
            assertEquals(phase, controller.phaseSeconds(), 1.0e-6f);
            EffectSnapshot baseline = controller.snapshot(new Vector3f());
            assertTrue(baseline.visuals().mesh().rimLight().isEmpty());
            assertTrue(baseline.lights().isEmpty());

            controller.toggleBaseline();
            assertFalse(controller.baselineActive());
            assertEquals(phase, controller.phaseSeconds(), 1.0e-6f);
        }
    }

    @Test
    void lightIntensityEditingScalesTheCurveAndZeroStaysZero() {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            controller.mutateDraft(builder -> builder.lightIntensity(0.0f));
            assertTrue(controller.applyDraft());
            controller.update(1.3f, new Vector3f());
            controller.update(0.4f, new Vector3f());
            EffectSnapshot zero = controller.snapshot(new Vector3f());
            assertEquals(1, zero.lights().size());
            assertEquals(0.0f, zero.lights().get(0).intensity(), 1.0e-6f);

            controller.mutateDraft(builder -> builder.lightIntensity(30.0f));
            assertTrue(controller.applyDraft());
            controller.update(1.3f, new Vector3f());
            controller.update(0.4f, new Vector3f());
            EffectSnapshot strong = controller.snapshot(new Vector3f());
            assertEquals(1, strong.lights().size());
            assertEquals(30.0f, strong.lights().get(0).intensity(), 1.0e-4f);
        }
    }

    @Test
    void completedEffectClearsEveryPrimitiveAndLightBeforeReplay() {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            controller.update(1.3f, new Vector3f());
            controller.update(1.0f, new Vector3f());
            controller.update(0.75f, new Vector3f());
            assertTrue(controller.phaseSeconds() >= controller.effective().lightEndSeconds());
            EffectSnapshot ended = controller.snapshot(new Vector3f());
            assertTrue(ended.particles().isEmpty());
            assertTrue(ended.meshes().isEmpty());
            assertTrue(ended.lights().isEmpty());
            assertTrue(controller.snapshot(new Vector3f()).particles().isEmpty(),
                    "fully dissolved effect must not keep emitting");
        }
    }

    @Test
    void cancelClearsRuntimeContentAndReplayRestartsTheProcess() {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            controller.update(1.3f, new Vector3f(1.0f, 0.0f, 0.0f));
            controller.update(0.1f, new Vector3f(1.0f, 0.0f, 0.0f));
            EffectSnapshot active = controller.snapshot(new Vector3f(0.0f, 0.0f, 4.0f));
            assertFalse(active.particles().isEmpty());
            assertFalse(active.lights().isEmpty());

            controller.cancel();
            assertTrue(controller.cancelled());
            assertTrue(controller.snapshot(new Vector3f()).particles().isEmpty());
            assertTrue(controller.snapshot(new Vector3f()).lights().isEmpty());

            controller.replay();
            assertFalse(controller.cancelled());
            assertEquals(0.0f, controller.phaseSeconds(), 1.0e-6f);
            controller.update(0.2f, new Vector3f());
            assertFalse(controller.snapshot(new Vector3f()).meshes().isEmpty());
        }
    }

    @Test
    void pauseFreezesControllerPhaseDissolveAndLifecycleAcrossFadeAndEnd() {
        FakePreparer preparer = new FakePreparer();
        try (CombatVfxController controller = controller(preparer)) {
            Vector3f origin = new Vector3f(1.0f, 0.0f, 0.0f);
            for (int frame = 0; frame < 150; frame++) {
                controller.update(1.0f / 60.0f, origin);
            }
            assertTrue(controller.phaseSeconds() >= 2.49f);
            controller.togglePause();
            float pausedPhase = controller.phaseSeconds();
            EffectSnapshot paused = controller.snapshot(new Vector3f());
            float pausedDissolve = paused.dissolveProgress();
            int pausedParticles = paused.particles().size();
            int pausedLights = paused.lights().size();
            assertTrue(controller.paused());
            assertFalse(controller.cancelled());
            assertTrue(pausedParticles > 0);
            assertTrue(pausedLights > 0);

            for (int frame = 0; frame < 36; frame++) {
                controller.update(1.0f / 60.0f, origin);
            }
            assertEquals(pausedPhase, controller.phaseSeconds(), 1.0e-5f,
                    "controller phase clock must freeze while the effect is paused");
            EffectSnapshot stillPaused = controller.snapshot(new Vector3f());
            assertEquals(pausedDissolve, stillPaused.dissolveProgress(), 1.0e-6f);
            assertEquals(pausedParticles, stillPaused.particles().size());
            assertEquals(pausedLights, stillPaused.lights().size());
            assertTrue(controller.paused());
            assertFalse(controller.cancelled());

            controller.togglePause();
            controller.update(0.1f, origin);
            assertTrue(controller.phaseSeconds() > pausedPhase,
                    "explicit resume must restart the phase clock");
            controller.update(0.5f, origin);
            assertTrue(controller.phaseSeconds() >= controller.effective().lightEndSeconds());
            assertTrue(controller.snapshot(new Vector3f()).particles().isEmpty());
            assertTrue(controller.snapshot(new Vector3f()).lights().isEmpty());
        }
    }

    @Test
    void actualSliderAndApplyButtonEventsDriveTheEffectiveProfile() {
        FakePreparer preparer = new FakePreparer();
        try (UiDocument document = new UiDocument();
             CombatVfxController controller = controller(preparer)) {
            Panel panel = controller.installPanel(() -> false, value -> { },
                    value -> { }, value -> { });
            document.root().add(panel);
            Slider lightIntensity = findSlider(panel, "light intensity");
            lightIntensity.applyLayout(new LayoutBox(0, 0, 100, 20));
            document.dispatch(lightIntensity, pointer(UiEventType.POINTER_DOWN, 100, 10));
            document.dispatch(lightIntensity, pointer(UiEventType.POINTER_UP, 100, 10));
            assertEquals(30.0f, controller.draft().lightIntensity(), 1.0e-5f,
                    "real slider input must update the draft, not the effective profile");
            assertEquals(14.0f, controller.effective().lightIntensity(), 1.0e-5f);

            Button apply = findButton(panel, "Apply");
            apply.applyLayout(new LayoutBox(0, 0, 58, 22));
            document.dispatch(apply, pointer(UiEventType.POINTER_DOWN, 10, 10));
            document.dispatch(apply, pointer(UiEventType.POINTER_UP, 10, 10));
            assertEquals(30.0f, controller.effective().lightIntensity(), 1.0e-5f,
                    "Apply must publish the UI-edited light intensity");
        }
    }

    private static Slider findSlider(UiNode root, String debugName) {
        UiNode found = find(root, node -> node instanceof Slider slider
                && debugName.equals(slider.debugName()));
        assertTrue(found instanceof Slider, "missing slider " + debugName);
        return (Slider) found;
    }

    private static Button findButton(UiNode root, String text) {
        UiNode found = find(root, node -> node instanceof Button button
                && text.equals(button.text()));
        assertTrue(found instanceof Button, "missing button " + text);
        return (Button) found;
    }

    private static UiNode find(UiNode root, java.util.function.Predicate<UiNode> predicate) {
        if (predicate.test(root)) return root;
        for (UiNode child : root.children()) {
            UiNode found = find(child, predicate);
            if (found != null) return found;
        }
        return null;
    }

    private static PointerEvent pointer(UiEventType type, double x, double y) {
        return new PointerEvent(type, 1, System.nanoTime(), KeyModifiers.NONE, 0,
                x, y, 0, 0, 0, 0, MouseButton.LEFT, 1);
    }

    private CombatVfxController controller(FakePreparer preparer) {
        return new CombatVfxController(CombatVfxCatalog.showcase(), preparer,
                CombatVfxProfile.defaults(), directory.resolve("combat-vfx.properties"));
    }

    private static final class FakePreparer implements CombatVfxController.VfxPreparer {
        private int calls;
        private boolean failNext;

        @Override
        public void prepare(EffectSnapshot snapshot) {
            calls++;
            if (failNext) {
                failNext = false;
                throw new IllegalArgumentException("injected preparation failure");
            }
        }
    }
}
