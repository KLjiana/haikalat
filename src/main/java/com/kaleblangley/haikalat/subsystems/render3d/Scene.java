package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Scene {
    private final Camera camera;
    private final List<MeshRenderer> renderers = new ArrayList<>();
    private final List<SceneLight> lights = new ArrayList<>();
    private List<MeshRenderer> forwardDrawOrder;
    private List<MeshRenderer> shadowDrawOrder;

    public Scene(Camera camera) {
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    public static Scene of(Camera camera, List<SceneObject> objects) {
        Scene scene = new Scene(camera);
        for (SceneObject object : objects) {
            scene.add(object);
        }
        return scene;
    }

    public Camera camera() {
        return camera;
    }

    public Scene add(MeshRenderer renderer) {
        renderers.add(Objects.requireNonNull(renderer, "renderer"));
        forwardDrawOrder = null;
        shadowDrawOrder = null;
        return this;
    }

    public Scene add(SceneObject object) {
        Objects.requireNonNull(object, "object");
        MeshRenderer renderer = MeshRenderer.animated(object.mesh(), object.material(), object.updater());
        return add(object.castShadows() ? renderer : renderer.withoutShadows());
    }

    public Scene addLight(SceneLight light) {
        lights.add(Objects.requireNonNull(light, "light"));
        return this;
    }

    public List<MeshRenderer> renderers() {
        return List.copyOf(renderers);
    }

    List<MeshRenderer> forwardDrawOrder() {
        if (forwardDrawOrder == null) forwardDrawOrder = SceneDrawOrder.forward(renderers);
        return forwardDrawOrder;
    }

    List<MeshRenderer> shadowDrawOrder() {
        if (shadowDrawOrder == null) shadowDrawOrder = SceneDrawOrder.shadow(renderers);
        return shadowDrawOrder;
    }

    public List<SceneLight> lights() {
        return List.copyOf(lights);
    }

    public Optional<SceneLight> firstShadowCastingDirectionalLight() {
        return lights.stream()
                .filter(light -> light.type() == LightType.DIRECTIONAL && light.castShadows())
                .findFirst();
    }

    public boolean hasShadowCastingDirectionalLight() {
        return firstShadowCastingDirectionalLight().isPresent();
    }
}
