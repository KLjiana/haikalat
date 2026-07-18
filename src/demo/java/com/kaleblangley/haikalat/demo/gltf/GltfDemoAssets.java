package com.kaleblangley.haikalat.demo.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.GltfSceneStatistics;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** GltfDemo 独占的两份场景资产及其 GPU 生命周期。 */
final class GltfDemoAssets implements AutoCloseable {
    private final GltfRuntimeLibrary library;
    private final List<GltfSceneAsset> assets;
    private final List<SceneObject> objects;
    private final List<String> inspectionLines;
    private boolean closed;

    private GltfDemoAssets(GltfRuntimeLibrary library, List<GltfSceneAsset> assets,
                           List<SceneObject> objects, List<String> inspectionLines) {
        this.library = library;
        this.assets = List.copyOf(assets);
        this.objects = List.copyOf(objects);
        this.inspectionLines = List.copyOf(inspectionLines);
    }

    static GltfDemoAssets load() {
        ResourceLocator resources = ResourceLocator.classpath(GltfDemo.class);
        GltfAssetLoader loader = new GltfAssetLoader(resources);
        GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
        List<GltfSceneAsset> assets = new ArrayList<>();
        List<SceneObject> objects = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try {
            LoadedGltfScene showcase = loader.load(AssetRef.of("/gltf/showcase.gltf"));
            GltfSceneAsset showcaseGpu = GltfSceneAsset.upload(showcase, library);
            assets.add(showcaseGpu);
            Matrix4f showcaseRoot = new Matrix4f().translation(-2.8f, -0.8f, 0.0f)
                    .scale(1.5f).translate(-0.5f, -0.5f, 0.0f);
            objects.addAll(showcaseGpu.instantiate(showcaseRoot, false));
            appendInspection(lines, "showcase.gltf", showcase, showcaseGpu);

            LoadedGltfScene radio = loader.load(AssetRef.of("/radio.gltf"));
            GltfSceneAsset radioGpu = GltfSceneAsset.upload(radio, library);
            assets.add(radioGpu);
            Matrix4f radioRoot = new Matrix4f().translation(1.5f, -0.8f, 0.0f)
                    .scale(1.8f).translate(-0.81f, -0.42f, -0.15f);
            objects.addAll(radioGpu.instantiate(radioRoot, false));
            appendInspection(lines, "radio.gltf", radio, radioGpu);
            return new GltfDemoAssets(library, assets, objects, lines);
        } catch (RuntimeException failure) {
            RuntimeException primary = closeAssets(assets, failure);
            if (library.activeAssetCount() == 0) {
                try {
                    library.close();
                } catch (RuntimeException cleanup) {
                    primary.addSuppressed(cleanup);
                }
            }
            throw primary;
        }
    }

    List<SceneObject> objects() {
        ensureOpen();
        return objects;
    }

    List<String> inspectionLines() {
        ensureOpen();
        return inspectionLines;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = closeAssets(assets, null);
        try {
            library.close();
        } catch (RuntimeException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }

    private static void appendInspection(List<String> lines, String name,
                                         LoadedGltfScene scene, GltfSceneAsset gpu) {
        GltfSceneStatistics stats = scene.statistics();
        lines.add(String.format(Locale.ROOT,
                "%s | scene %d:%s | node %d/%d | primitive %d",
                name, scene.selectedSceneIndex(), scene.selectedSceneName(),
                stats.reachableNodeCount(), stats.nodeCount(), stats.primitiveCount()));
        lines.add(String.format(Locale.ROOT,
                "  material %d image %d | GPU mesh %d texture %d sampler %d",
                stats.materialCount(), stats.imageCount(), gpu.uniqueMeshCount(),
                gpu.uniqueTextureCount(), gpu.uniqueSamplerCount()));
        lines.add(String.format(Locale.ROOT,
                "  decoded %.1f KiB | vertex %.1f KiB | index %.1f KiB",
                stats.decodedBufferBytes() / 1024.0, stats.vertexBytes() / 1024.0,
                stats.indexBytes() / 1024.0));
        scene.materials().forEach(material -> lines.add(String.format(Locale.ROOT,
                "  material[%d] %s | %s cutoff %.2f | textures %s",
                material.index(), material.name().isBlank() ? "unnamed" : material.name(),
                material.alphaMode(), material.alphaCutoff(), material.textureIndices().keySet())));
        scene.rootNodeIndices().forEach(root -> appendNode(lines, scene, root, 1));
        scene.warnings().forEach(warning -> lines.add("  warning: " + warning));
    }

    private static void appendNode(List<String> lines, LoadedGltfScene scene,
                                   int nodeIndex, int depth) {
        LoadedGltfScene.Node node = scene.nodes().get(nodeIndex);
        lines.add("  ".repeat(depth) + "node[" + node.index() + "] "
                + (node.name().isBlank() ? "unnamed" : node.name()) + " mesh=" + node.meshIndex());
        node.children().forEach(child -> appendNode(lines, scene, child, depth + 1));
    }

    private static RuntimeException closeAssets(List<GltfSceneAsset> assets,
                                                RuntimeException primary) {
        RuntimeException failure = primary;
        for (int index = assets.size() - 1; index >= 0; index--) {
            try {
                assets.get(index).close();
            } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("GltfDemo assets are closed");
    }
}
