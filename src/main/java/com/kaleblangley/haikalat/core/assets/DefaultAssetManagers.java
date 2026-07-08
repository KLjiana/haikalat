package com.kaleblangley.haikalat.core.assets;

public final class DefaultAssetManagers {
    private DefaultAssetManagers() {
    }

    public static ModelAssetManager models(ResourceLocator locator) {
        AssimpModelLoader assimp = new AssimpModelLoader(locator);
        return new ModelAssetManager()
                .register("obj", new ObjModelLoader(locator))
                .register("gltf", assimp)
                .register("glb", assimp)
                .register("fbx", assimp);
    }
}
