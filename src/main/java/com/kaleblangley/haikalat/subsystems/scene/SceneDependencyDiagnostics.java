package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;

import java.util.List;
import java.util.Objects;

/** Bounded content diagnostics suitable for a UI overlay or host log. */
public record SceneDependencyDiagnostics(
        AssetId scene,
        ResourceGeneration generation,
        List<AssetId> dependencies,
        List<AssetId> missing,
        String failureCode,
        String detail
) {
    public SceneDependencyDiagnostics {
        scene = Objects.requireNonNull(scene, "scene");
        generation = Objects.requireNonNull(generation, "generation");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        missing = List.copyOf(Objects.requireNonNull(missing, "missing"));
        failureCode = failureCode == null ? "" : failureCode;
        detail = detail == null ? "" : detail;
        if (failureCode.isBlank() != detail.isBlank()) {
            throw new IllegalArgumentException("failureCode and detail must be empty or both set");
        }
    }

    public boolean healthy() {
        return missing.isEmpty() && failureCode.isEmpty();
    }

    public static SceneDependencyDiagnostics success(AssetId scene,
                                                      ResourceGeneration generation,
                                                      List<AssetId> dependencies) {
        return new SceneDependencyDiagnostics(scene, generation, dependencies,
                List.of(), "", "");
    }

    public static SceneDependencyDiagnostics failure(AssetId scene,
                                                     ResourceGeneration generation,
                                                     List<AssetId> dependencies,
                                                     List<AssetId> missing,
                                                     String code, String detail) {
        return new SceneDependencyDiagnostics(scene, generation, dependencies, missing,
                Objects.requireNonNull(code, "code"), Objects.requireNonNull(detail, "detail"));
    }
}
