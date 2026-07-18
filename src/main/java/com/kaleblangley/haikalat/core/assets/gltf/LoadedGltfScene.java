package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 纯 JVM、不可变的已解码 glTF 静态场景。 */
public final class LoadedGltfScene {
    private final AssetRef source;
    private final int selectedSceneIndex;
    private final String selectedSceneName;
    private final List<Integer> rootNodeIndices;
    private final List<Node> nodes;
    private final List<Primitive> primitives;
    private final List<MaterialDef> materials;
    private final List<TextureDef> textures;
    private final List<ImageDef> images;
    private final List<SamplerDef> samplers;
    private final List<String> warnings;
    private final GltfSceneStatistics statistics;

    LoadedGltfScene(AssetRef source, int selectedSceneIndex, String selectedSceneName,
                    List<Integer> rootNodeIndices, List<Node> nodes, List<Primitive> primitives,
                    List<MaterialDef> materials, List<TextureDef> textures, List<ImageDef> images,
                    List<SamplerDef> samplers, List<String> warnings, GltfSceneStatistics statistics) {
        this.source = Objects.requireNonNull(source, "source");
        this.selectedSceneIndex = selectedSceneIndex;
        this.selectedSceneName = selectedSceneName == null ? "" : selectedSceneName;
        this.rootNodeIndices = List.copyOf(rootNodeIndices);
        this.nodes = List.copyOf(nodes);
        this.primitives = List.copyOf(primitives);
        this.materials = List.copyOf(materials);
        this.textures = List.copyOf(textures);
        this.images = List.copyOf(images);
        this.samplers = List.copyOf(samplers);
        this.warnings = List.copyOf(warnings);
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    public AssetRef source() { return source; }
    public int selectedSceneIndex() { return selectedSceneIndex; }
    public String selectedSceneName() { return selectedSceneName; }
    public List<Integer> rootNodeIndices() { return rootNodeIndices; }
    public List<Node> nodes() { return nodes; }
    public List<Primitive> primitives() { return primitives; }
    public List<MaterialDef> materials() { return materials; }
    public List<TextureDef> textures() { return textures; }
    public List<ImageDef> images() { return images; }
    public List<SamplerDef> samplers() { return samplers; }
    public List<String> warnings() { return warnings; }
    public GltfSceneStatistics statistics() { return statistics; }

    public record Node(int index, String name, int meshIndex, List<Integer> children,
                       Matrix4fc localTransform, Matrix4fc worldTransform,
                       boolean reachable, boolean mirrored) {
        public Node {
            name = name == null ? "" : name;
            children = List.copyOf(children);
            localTransform = new Matrix4f(Objects.requireNonNull(localTransform, "localTransform"));
            worldTransform = new Matrix4f(Objects.requireNonNull(worldTransform, "worldTransform"));
        }
        @Override public Matrix4fc localTransform() { return new Matrix4f(localTransform); }
        @Override public Matrix4fc worldTransform() { return new Matrix4f(worldTransform); }
    }

    public record Primitive(int index, int meshIndex, int primitiveIndex, String name,
                            MeshData mesh, int materialIndex, boolean hasVertexColor) {
        public Primitive {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mesh, "mesh");
        }
    }

    public record MaterialDef(int index, String name, PbrMaterialProperties properties,
                              Map<PbrTextureRole, Integer> textureIndices, boolean doubleSided,
                              GltfAlphaMode alphaMode, float alphaCutoff) {
        public MaterialDef {
            name = name == null ? "" : name;
            Objects.requireNonNull(properties, "properties");
            textureIndices = Map.copyOf(textureIndices);
            Objects.requireNonNull(alphaMode, "alphaMode");
            if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0f || alphaCutoff > 1.0f) {
                throw new IllegalArgumentException("alphaCutoff must be finite and in [0, 1]");
            }
        }
    }

    public record TextureDef(int index, int imageIndex, int samplerIndex) {}

    public record ImageDef(int index, String name, String mimeType, String sourceUri, byte[] encoded) {
        public ImageDef {
            name = name == null ? "" : name;
            Objects.requireNonNull(mimeType, "mimeType");
            sourceUri = sourceUri == null ? "" : sourceUri;
            encoded = Objects.requireNonNull(encoded, "encoded").clone();
        }
        @Override public byte[] encoded() { return encoded.clone(); }
    }

    public record SamplerDef(int index, int minFilter, int magFilter, int wrapS, int wrapT) {}

    public record ImageVariantKey(int imageIndex, TextureColorSpace colorSpace) {
        public ImageVariantKey { Objects.requireNonNull(colorSpace, "colorSpace"); }
    }
}
