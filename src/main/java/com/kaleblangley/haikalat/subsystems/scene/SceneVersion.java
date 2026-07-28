package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneInstance;
import com.kaleblangley.haikalat.subsystems.resources.ResourceGeneration;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete GL-thread scene generation assembled from one immutable CPU plan.
 * Instances are closed before their shared GPU assets.
 */
public final class SceneVersion implements AutoCloseable {
    private final ResourceGeneration generation;
    private final Scene scene;
    private final List<GltfSceneInstance> animatedInstances;
    private final List<GltfSceneAsset> assets;
    private final List<GltfGpuAssetCache.Lease> leases;
    private boolean closed;

    private SceneVersion(ResourceGeneration generation, Scene scene,
                         List<GltfSceneInstance> animatedInstances,
                         List<GltfSceneAsset> assets,
                         List<GltfGpuAssetCache.Lease> leases) {
        this.generation = generation;
        this.scene = scene;
        this.animatedInstances = List.copyOf(animatedInstances);
        this.assets = List.copyOf(assets);
        this.leases = List.copyOf(leases);
    }

    /**
     * Creates GPU resources and scene instances. Call only on the owning GL
     * thread, after the CPU plan has completed.
     */
    public static SceneVersion build(SceneBuildPlan plan, GltfRuntimeLibrary library) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(library, "library");
        Map<SceneBuildPlan.AssetVariant, GltfSceneAsset> uploaded = new LinkedHashMap<>();
        List<GltfSceneInstance> animated = new ArrayList<>();
        try {
            for (Map.Entry<SceneBuildPlan.AssetVariant, LoadedGltfScene> entry
                    : plan.gltfAssets().entrySet()) {
                uploaded.put(entry.getKey(), GltfSceneAsset.upload(entry.getValue(), library));
            }
            Map<String, Matrix4f> worlds = worldTransforms(plan.definition());
            SceneDefinition.CameraDefinition cameraDef = plan.definition().camera();
            Matrix4f cameraWorld = worlds.get(cameraDef.node());
            Camera camera = cameraFrom(cameraWorld, cameraDef.projection().fovYDegrees());
            Scene scene = new Scene(camera);
            addLights(scene, plan.definition(), worlds);
            for (SceneBuildPlan.InstancePlan instance : plan.instances()) {
                GltfSceneAsset asset = uploaded.get(instance.asset());
                Matrix4f world = worlds.get(instance.nodeId());
                if (instance.animated()) {
                    GltfSceneInstance animatedInstance =
                            asset.instantiateAnimated(world, instance.castShadows());
                    if (instance.initialAnimation() != null) {
                        int index = animatedInstance.animationNames()
                                .indexOf(instance.initialAnimation());
                        if (index < 0) {
                            throw new IllegalArgumentException("animation not found: "
                                    + instance.initialAnimation());
                        }
                        animatedInstance.play(index, instance.loop()
                                ? AnimationPlayer.LoopMode.LOOP : AnimationPlayer.LoopMode.ONCE);
                    }
                    animated.add(animatedInstance);
                    for (SceneObject object : animatedInstance.objects()) scene.add(object);
                } else {
                    for (SceneObject object : asset.instantiate(world, instance.castShadows())) {
                        scene.add(object);
                    }
                }
            }
            return new SceneVersion(plan.generation(), scene, animated,
                    new ArrayList<>(uploaded.values()), List.of());
        } catch (RuntimeException failure) {
            closeAnimated(animated, failure);
            closeAssets(uploaded.values(), failure);
            throw failure;
        }
    }

    /** Builds a scene generation using shared exact-generation GPU leases. */
    public static SceneVersion build(SceneBuildPlan plan, GltfGpuAssetCache cache) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(cache, "cache");
        Map<SceneBuildPlan.AssetVariant, GltfGpuAssetCache.Lease> leases = new LinkedHashMap<>();
        List<GltfSceneInstance> animated = new ArrayList<>();
        try {
            for (Map.Entry<SceneBuildPlan.AssetVariant, LoadedGltfScene> entry
                    : plan.gltfAssets().entrySet()) {
                SceneBuildPlan.AssetVariant variant = entry.getKey();
                leases.put(variant, cache.acquire(variant.asset(), variant.generation(),
                        variant.scene() + ";strict=" + variant.strictExtensions(), entry.getValue(),
                        plan.decodedImages().getOrDefault(variant, Map.of())));
            }
            Map<String, Matrix4f> worlds = worldTransforms(plan.definition());
            SceneDefinition.CameraDefinition cameraDef = plan.definition().camera();
            Camera camera = cameraFrom(worlds.get(cameraDef.node()),
                    cameraDef.projection().fovYDegrees());
            Scene scene = new Scene(camera);
            addLights(scene, plan.definition(), worlds);
            for (SceneBuildPlan.InstancePlan instance : plan.instances()) {
                GltfSceneAsset asset = leases.get(instance.asset()).asset();
                Matrix4f world = worlds.get(instance.nodeId());
                if (instance.animated()) {
                    GltfSceneInstance animatedInstance =
                            asset.instantiateAnimated(world, instance.castShadows());
                    if (instance.initialAnimation() != null) {
                        int index = animatedInstance.animationNames()
                                .indexOf(instance.initialAnimation());
                        if (index < 0) {
                            throw new IllegalArgumentException("animation not found: "
                                    + instance.initialAnimation());
                        }
                        animatedInstance.play(index, instance.loop()
                                ? AnimationPlayer.LoopMode.LOOP : AnimationPlayer.LoopMode.ONCE);
                    }
                    animated.add(animatedInstance);
                    for (SceneObject object : animatedInstance.objects()) scene.add(object);
                } else {
                    for (SceneObject object : asset.instantiate(world, instance.castShadows())) {
                        scene.add(object);
                    }
                }
            }
            return new SceneVersion(plan.generation(), scene, animated, List.of(),
                    new ArrayList<>(leases.values()));
        } catch (RuntimeException failure) {
            closeAnimated(animated, failure);
            closeLeases(leases.values(), failure);
            throw failure;
        }
    }

    public ResourceGeneration generation() {
        return generation;
    }

    public Scene scene() {
        ensureOpen();
        return scene;
    }

    public List<GltfSceneInstance> animatedInstances() {
        ensureOpen();
        return animatedInstances;
    }

    public boolean isClosed() {
        return closed;
    }

    public void update(float deltaSeconds) {
        ensureOpen();
        for (GltfSceneInstance instance : animatedInstances) instance.update(deltaSeconds);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (int i = animatedInstances.size() - 1; i >= 0; i--) {
            try {
                animatedInstances.get(i).close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        for (int i = assets.size() - 1; i >= 0; i--) {
            try {
                assets.get(i).close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        for (int i = leases.size() - 1; i >= 0; i--) {
            try {
                leases.get(i).close();
            } catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static Map<String, Matrix4f> worldTransforms(SceneDefinition definition) {
        Map<String, SceneDefinition.NodeDefinition> nodes = new LinkedHashMap<>();
        for (SceneDefinition.NodeDefinition node : definition.nodes()) nodes.put(node.id(), node);
        Map<String, Matrix4f> result = new HashMap<>();
        for (SceneDefinition.NodeDefinition node : definition.nodes()) {
            resolveWorld(node.id(), nodes, result);
        }
        return result;
    }

    private static Matrix4f resolveWorld(String id,
                                         Map<String, SceneDefinition.NodeDefinition> nodes,
                                         Map<String, Matrix4f> result) {
        Matrix4f existing = result.get(id);
        if (existing != null) return new Matrix4f(existing);
        SceneDefinition.NodeDefinition node = nodes.get(id);
        SceneDefinition.TransformDefinition t = node.transform();
        Matrix4f local = new Matrix4f()
                .translate(t.translationX(), t.translationY(), t.translationZ())
                .rotate(t.rotationX(), t.rotationY(), t.rotationZ(), t.rotationW())
                .scale(t.scaleX(), t.scaleY(), t.scaleZ());
        Matrix4f world = node.parent() == null
                ? local : resolveWorld(node.parent(), nodes, result).mul(local);
        result.put(id, new Matrix4f(world));
        return world;
    }

    private static Camera cameraFrom(Matrix4f world, float fovYDegrees) {
        Vector3f position = world.getTranslation(new Vector3f());
        Vector3f front = world.transformDirection(new Vector3f(0.0f, 0.0f, -1.0f)).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(front.z, front.x));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0f, Math.min(1.0f, front.y))));
        Camera camera = new Camera(position, new Vector3f(0.0f, 1.0f, 0.0f), yaw, pitch);
        camera.setZoom(fovYDegrees);
        return camera;
    }

    private static void addLights(Scene scene, SceneDefinition definition,
                                  Map<String, Matrix4f> worlds) {
        for (SceneDefinition.NodeDefinition node : definition.nodes()) {
            SceneDefinition.LightDefinition light = node.light();
            if (light == null) continue;
            Matrix4f world = worlds.get(node.id());
            Vector3f position = world.getTranslation(new Vector3f());
            Vector3f direction = world.transformDirection(new Vector3f(0.0f, 0.0f, -1.0f))
                    .normalize();
            Vector3f color = new Vector3f(light.colorR(), light.colorG(), light.colorB());
            float inner = (float) Math.toRadians(light.innerConeDegrees());
            float outer = (float) Math.toRadians(light.outerConeDegrees());
            SceneLight value = switch (light.type()) {
                case "directional" -> new SceneLight(
                        com.kaleblangley.haikalat.subsystems.render3d.LightType.DIRECTIONAL,
                        color, light.intensity(), direction, new Vector3f(), 0.0f,
                        0.0f, 0.0f, light.castShadows());
                case "point" -> new SceneLight(
                        com.kaleblangley.haikalat.subsystems.render3d.LightType.POINT,
                        color, light.intensity(), new Vector3f(0.0f, -1.0f, 0.0f),
                        position, light.range(), 0.0f, 0.0f, light.castShadows());
                case "spot" -> new SceneLight(
                        com.kaleblangley.haikalat.subsystems.render3d.LightType.SPOT,
                        color, light.intensity(), direction, position, light.range(),
                        inner, outer, light.castShadows());
                default -> throw new IllegalStateException("unknown light type " + light.type());
            };
            scene.addLight(value);
        }
    }

    private static void closeAnimated(List<GltfSceneInstance> instances, RuntimeException primary) {
        for (int i = instances.size() - 1; i >= 0; i--) {
            try { instances.get(i).close(); }
            catch (RuntimeException error) { primary.addSuppressed(error); }
        }
    }

    private static void closeAssets(Iterable<GltfSceneAsset> assets, RuntimeException primary) {
        List<GltfSceneAsset> list = new ArrayList<>();
        assets.forEach(list::add);
        for (int i = list.size() - 1; i >= 0; i--) {
            try { list.get(i).close(); }
            catch (RuntimeException error) { primary.addSuppressed(error); }
        }
    }

    private static void closeLeases(Iterable<GltfGpuAssetCache.Lease> leases,
                                    RuntimeException primary) {
        List<GltfGpuAssetCache.Lease> list = new ArrayList<>();
        leases.forEach(list::add);
        for (int i = list.size() - 1; i >= 0; i--) {
            try { list.get(i).close(); }
            catch (RuntimeException error) { primary.addSuppressed(error); }
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("scene version is closed");
    }
}
