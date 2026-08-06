package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable UI/host-facing snapshot of a serialized scene candidate. */
public record SerializedSceneDiagnostics(
        AssetId scene,
        ResourceGeneration generation,
        List<CharacterDiagnostics> characters,
        SceneAssetSnapshot assetSnapshot
) {
    public SerializedSceneDiagnostics {
        scene = Objects.requireNonNull(scene, "scene");
        generation = Objects.requireNonNull(generation, "generation");
        characters = List.copyOf(Objects.requireNonNull(characters, "characters"));
        assetSnapshot = Objects.requireNonNull(assetSnapshot, "assetSnapshot");
    }

    public static SerializedSceneDiagnostics from(SceneBuildPlan plan,
                                                  SceneAssetSnapshot assetSnapshot) {
        Objects.requireNonNull(plan, "plan");
        List<CharacterDiagnostics> characters = new ArrayList<>();
        for (CharacterBuildPlan character : plan.characters()) {
            characters.add(new CharacterDiagnostics(character.definition().id(),
                    character.definition().object(), character.definition().initialState(),
                    character.animationLibrary(), character.animationGraph(),
                    character.graphDocument() == null ? 0 : character.graphDocument().states().size(),
                    character.definition().parameters().keySet().stream().toList()));
        }
        return new SerializedSceneDiagnostics(plan.sceneId(), plan.generation(), characters,
                assetSnapshot);
    }

    public record CharacterDiagnostics(
            String id,
            String object,
            String initialState,
            AssetId animationLibrary,
            AssetId animationGraph,
            int graphStateCount,
            List<String> parameterNames
    ) {
        public CharacterDiagnostics {
            id = requireText(id, "id");
            object = requireText(object, "object");
            initialState = requireText(initialState, "initialState");
            animationLibrary = Objects.requireNonNull(animationLibrary, "animationLibrary");
            animationGraph = Objects.requireNonNull(animationGraph, "animationGraph");
            if (graphStateCount < 0) throw new IllegalArgumentException("graphStateCount < 0");
            parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "parameterNames"));
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
