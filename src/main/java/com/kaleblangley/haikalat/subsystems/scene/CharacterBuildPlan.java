package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.animation.AnimationGraphDocument;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;

import java.util.List;
import java.util.Objects;

/** CPU-only validated binding between a scene object and character assets. */
public record CharacterBuildPlan(
        SceneCharacterDefinition definition,
        AssetId model,
        AssetId animationLibrary,
        AssetId animationGraph,
        List<AssetId> dependencies,
        AnimationGraphDocument graphDocument
) {
    public CharacterBuildPlan(SceneCharacterDefinition definition, AssetId model,
                              AssetId animationLibrary, AssetId animationGraph,
                              List<AssetId> dependencies) {
        this(definition, model, animationLibrary, animationGraph, dependencies, null);
    }

    public CharacterBuildPlan {
        definition = Objects.requireNonNull(definition, "definition");
        model = Objects.requireNonNull(model, "model");
        animationLibrary = Objects.requireNonNull(animationLibrary, "animationLibrary");
        animationGraph = Objects.requireNonNull(animationGraph, "animationGraph");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (!dependencies.contains(model) || !dependencies.contains(animationLibrary)
                || !dependencies.contains(animationGraph)) {
            throw new IllegalArgumentException("character dependencies must include model, "
                    + "animation library and graph");
        }
    }
}
