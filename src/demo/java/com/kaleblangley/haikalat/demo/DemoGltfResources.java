package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.SceneAssetConfig;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.GltfLoadOptions;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.assets.gltf.SceneSelection;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Demo manifest 中 glTF 静态场景的装配与显式资源所有者。 */
final class DemoGltfResources implements AutoCloseable {
    private final GltfRuntimeLibrary library;
    private final List<GltfSceneAsset> assets;
    private final List<SceneObject> objects;
    private boolean closed;

    private DemoGltfResources(GltfRuntimeLibrary library, List<GltfSceneAsset> assets,
                              List<SceneObject> objects) {
        this.library = library;
        this.assets = List.copyOf(assets);
        this.objects = List.copyOf(objects);
    }

    static DemoGltfResources load(ResourceLocator locator, SceneAssetConfig config) {
        GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
        List<GltfSceneAsset> assets = new ArrayList<>();
        List<SceneObject> objects = new ArrayList<>();
        try {
            GltfAssetLoader loader = new GltfAssetLoader(locator);
            for (Map.Entry<String, SceneAssetConfig.GltfSceneDef> entry : config.gltfScenes().entrySet()) {
                SceneAssetConfig.GltfSceneDef def = entry.getValue();
                SceneSelection selection = selection(def.scene());
                LoadedGltfScene loaded = loader.load(def.path(), new GltfLoadOptions(selection, true,
                        com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLimits.defaults()));
                GltfSceneAsset asset = GltfSceneAsset.upload(loaded, library);
                assets.add(asset);
                Matrix4f root = new Matrix4f().translation(def.position())
                        .rotateXYZ(def.rotationRadians()).scale(def.scale());
                objects.addAll(asset.instantiate(root, def.castShadows()));
            }
            return new DemoGltfResources(library, assets, objects);
        } catch (RuntimeException failure) {
            RuntimeException primary = closeAssets(assets, failure);
            if (library.activeAssetCount() == 0) {
                try { library.close(); }
                catch (RuntimeException cleanupFailure) { primary.addSuppressed(cleanupFailure); }
            }
            throw primary;
        }
    }

    List<SceneObject> objects() {
        if (closed) throw new IllegalStateException("Demo glTF resources are closed");
        return objects;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = closeAssets(assets, null);
        try { library.close(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private static SceneSelection selection(String value) {
        if (value.equalsIgnoreCase("default")) return new SceneSelection.Default();
        try { return new SceneSelection.ByIndex(Integer.parseInt(value)); }
        catch (NumberFormatException ignored) { return new SceneSelection.ByName(value); }
    }

    private static RuntimeException closeAssets(List<GltfSceneAsset> assets, RuntimeException primary) {
        RuntimeException failure = primary;
        for (int index = assets.size() - 1; index >= 0; index--) {
            try { assets.get(index).close(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        return failure;
    }
}
