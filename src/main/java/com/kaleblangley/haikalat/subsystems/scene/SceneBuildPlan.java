package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.assets.gltf.GltfImageData;
import com.kaleblangley.haikalat.subsystems.resources.AssetId;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable CPU-only scene result. It contains no OpenGL object and can safely
 * cross from a decoder executor to a GL-thread upload queue.
 */
public final class SceneBuildPlan {
    private final AssetId sceneId;
    private final ResourceGeneration generation;
    private final SceneDefinition definition;
    private final Map<AssetVariant, LoadedGltfScene> gltfAssets;
    private final Map<AssetVariant, Map<Integer, GltfImageData>> decodedImages;
    private final List<InstancePlan> instances;
    private final List<CharacterBuildPlan> characters;

    public SceneBuildPlan(AssetId sceneId, ResourceGeneration generation,
                          SceneDefinition definition,
                          Map<AssetVariant, LoadedGltfScene> gltfAssets,
                          List<InstancePlan> instances) {
        this(sceneId, generation, definition, gltfAssets, Map.of(), instances, List.of());
    }

    public SceneBuildPlan(AssetId sceneId, ResourceGeneration generation,
                          SceneDefinition definition,
                          Map<AssetVariant, LoadedGltfScene> gltfAssets,
                          Map<AssetVariant, Map<Integer, GltfImageData>> decodedImages,
                          List<InstancePlan> instances) {
        this(sceneId, generation, definition, gltfAssets, decodedImages, instances, List.of());
    }

    public SceneBuildPlan(AssetId sceneId, ResourceGeneration generation,
                          SceneDefinition definition,
                          Map<AssetVariant, LoadedGltfScene> gltfAssets,
                          Map<AssetVariant, Map<Integer, GltfImageData>> decodedImages,
                          List<InstancePlan> instances,
                          List<CharacterBuildPlan> characters) {
        this.sceneId = Objects.requireNonNull(sceneId, "sceneId");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.gltfAssets = Map.copyOf(Objects.requireNonNull(gltfAssets, "gltfAssets"));
        Objects.requireNonNull(decodedImages, "decodedImages");
        Map<AssetVariant, Map<Integer, GltfImageData>> imageCopy = new java.util.LinkedHashMap<>();
        decodedImages.forEach((variant, images) -> imageCopy.put(
                Objects.requireNonNull(variant, "decoded image variant"),
                Map.copyOf(Objects.requireNonNull(images, "decoded images"))));
        this.decodedImages = Map.copyOf(imageCopy);
        this.instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
        this.characters = List.copyOf(Objects.requireNonNull(characters, "characters"));
    }

    public AssetId sceneId() {
        return sceneId;
    }

    public ResourceGeneration generation() {
        return generation;
    }

    public SceneDefinition definition() {
        return definition;
    }

    public Map<AssetVariant, LoadedGltfScene> gltfAssets() {
        return gltfAssets;
    }

    public Map<AssetVariant, Map<Integer, GltfImageData>> decodedImages() {
        return decodedImages;
    }

    public List<InstancePlan> instances() {
        return instances;
    }

    public List<CharacterBuildPlan> characters() {
        return characters;
    }

    public record AssetVariant(AssetId asset, ResourceGeneration generation,
                               String scene, boolean strictExtensions) {
        public AssetVariant(AssetId asset, String scene, boolean strictExtensions) {
            this(asset, ResourceGeneration.INITIAL, scene, strictExtensions);
        }

        public AssetVariant {
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(generation, "generation");
            scene = Objects.requireNonNull(scene, "scene");
        }
    }

    public record InstancePlan(String nodeId, String parentNodeId,
                               SceneDefinition.TransformDefinition transform,
                               AssetVariant asset,
                               boolean animated,
                               String initialAnimation,
                               boolean loop,
                               boolean castShadows) {
        public InstancePlan {
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            transform = Objects.requireNonNull(transform, "transform");
            asset = Objects.requireNonNull(asset, "asset");
        }
    }
}
