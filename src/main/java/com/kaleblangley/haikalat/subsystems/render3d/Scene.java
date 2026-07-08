package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Scene {
    private final Camera camera;
    private final List<MeshRenderer> renderers = new ArrayList<>();
    private final List<SceneLight> lights = new ArrayList<>();

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
        return this;
    }

    public Scene add(SceneObject object) {
        Objects.requireNonNull(object, "object");
        return add(MeshRenderer.animated(object.mesh(), object.material(), object.updater()));
    }

    public Scene addLight(SceneLight light) {
        lights.add(Objects.requireNonNull(light, "light"));
        return this;
    }

    public List<MeshRenderer> renderers() {
        return List.copyOf(renderers);
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
