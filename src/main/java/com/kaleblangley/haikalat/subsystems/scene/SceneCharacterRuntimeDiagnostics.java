package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneInstance;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;

import java.util.List;
import java.util.Objects;

/** Immutable character state snapshot suitable for Modern UI or host tooling. */
public record SceneCharacterRuntimeDiagnostics(
        String character,
        ResourceGeneration generation,
        String state,
        float timeSeconds,
        float normalizedTime,
        String transitionTarget,
        float transitionWeight,
        String transitionReason,
        List<String> activeWindows
) {
    public SceneCharacterRuntimeDiagnostics {
        character = requireText(character, "character");
        generation = Objects.requireNonNull(generation, "generation");
        state = Objects.requireNonNull(state, "state");
        transitionTarget = Objects.requireNonNull(transitionTarget, "transitionTarget");
        transitionReason = Objects.requireNonNull(transitionReason, "transitionReason");
        activeWindows = List.copyOf(Objects.requireNonNull(activeWindows, "activeWindows"));
        if (!Float.isFinite(timeSeconds) || timeSeconds < 0.0f) {
            throw new IllegalArgumentException("timeSeconds must be finite and non-negative");
        }
        if (!Float.isFinite(normalizedTime) || normalizedTime < 0.0f) {
            throw new IllegalArgumentException("normalizedTime must be finite and non-negative");
        }
        if (!Float.isFinite(transitionWeight)
                || transitionWeight < 0.0f || transitionWeight > 1.0f) {
            throw new IllegalArgumentException("transitionWeight must be in [0, 1]");
        }
    }

    public static SceneCharacterRuntimeDiagnostics capture(
            String character, ResourceGeneration generation, GltfSceneInstance instance) {
        Objects.requireNonNull(instance, "instance");
        return new SceneCharacterRuntimeDiagnostics(character, generation,
                instance.currentAnimationState(), instance.animationTimeSeconds(),
                instance.currentAnimationNormalizedTime(), instance.animationTransitionTarget(),
                instance.transitionWeight(), instance.animationTransitionReason(),
                instance.activeAnimationWindows());
    }

    public boolean transitioning() {
        return !transitionTarget.isEmpty() && transitionWeight < 1.0f;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
