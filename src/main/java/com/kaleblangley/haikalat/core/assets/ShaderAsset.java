package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.shader.ShaderStage;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable stage-to-resource description independent of a linked GL program. */
public final class ShaderAsset {
    private final Map<ShaderStage, AssetRef> stages;

    public ShaderAsset(AssetRef vertexShader, AssetRef fragmentShader) {
        this(Map.of(
                ShaderStage.VERTEX, Objects.requireNonNull(vertexShader, "vertexShader"),
                ShaderStage.FRAGMENT, Objects.requireNonNull(fragmentShader, "fragmentShader")));
    }

    public ShaderAsset(Map<ShaderStage, AssetRef> stages) {
        Objects.requireNonNull(stages, "stages");
        if (stages.isEmpty()) throw new IllegalArgumentException("shader asset requires at least one stage");
        EnumMap<ShaderStage, AssetRef> copy = new EnumMap<>(ShaderStage.class);
        stages.forEach((stage, ref) -> copy.put(
                Objects.requireNonNull(stage, "stage"), Objects.requireNonNull(ref, "ref")));
        validate(copy);
        this.stages = Map.copyOf(copy);
    }

    public static ShaderAsset of(String vertexShader, String fragmentShader) {
        return new ShaderAsset(AssetRef.of(vertexShader), AssetRef.of(fragmentShader));
    }

    public static ShaderAsset compute(String computeShader) {
        return new ShaderAsset(Map.of(ShaderStage.COMPUTE, AssetRef.of(computeShader)));
    }

    public Map<ShaderStage, AssetRef> stages() {
        return stages;
    }

    public Optional<AssetRef> stage(ShaderStage stage) {
        return Optional.ofNullable(stages.get(Objects.requireNonNull(stage, "stage")));
    }

    public AssetRef vertexShader() {
        return required(ShaderStage.VERTEX);
    }

    public AssetRef fragmentShader() {
        return required(ShaderStage.FRAGMENT);
    }

    private AssetRef required(ShaderStage stage) {
        AssetRef ref = stages.get(stage);
        if (ref == null) throw new IllegalStateException("Shader asset has no " + stage + " stage");
        return ref;
    }

    private static void validate(Map<ShaderStage, AssetRef> stages) {
        boolean compute = stages.containsKey(ShaderStage.COMPUTE);
        if (compute && stages.size() != 1) {
            throw new IllegalArgumentException("compute shader assets cannot contain graphics stages");
        }
        if (!compute && !stages.containsKey(ShaderStage.VERTEX)) {
            throw new IllegalArgumentException("graphics shader assets require a vertex stage");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShaderAsset asset && stages.equals(asset.stages);
    }

    @Override
    public int hashCode() {
        return stages.hashCode();
    }

    @Override
    public String toString() {
        return "ShaderAsset" + stages;
    }
}
