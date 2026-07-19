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
    private long membershipRevision;

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
        membershipRevision = Math.incrementExact(membershipRevision);
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

    /**
     * 更新不会改变渲染管线拓扑的灯光属性。
     * 灯光类型或阴影投射状态变化时必须重新构建渲染管线。
     */
    public Scene setLight(int index, SceneLight light) {
        SceneLight replacement = Objects.requireNonNull(light, "light");
        SceneLight existing = lights.get(index);
        if (existing.type() != replacement.type()) {
            throw new IllegalArgumentException("light[" + index + "].type cannot change from "
                    + existing.type() + " to " + replacement.type() + "; rebuild the pipeline");
        }
        if (existing.castShadows() != replacement.castShadows()) {
            throw new IllegalArgumentException("light[" + index + "].castShadows cannot change from "
                    + existing.castShadows() + " to " + replacement.castShadows()
                    + "; rebuild the pipeline");
        }
        lights.set(index, replacement);
        return this;
    }

    public List<MeshRenderer> renderers() {
        return List.copyOf(renderers);
    }

    /** @return renderer membership 的单调修订号；灯光参数变化不会修改它 */
    public long membershipRevision() {
        return membershipRevision;
    }

    int rendererCount() {
        return renderers.size();
    }

    MeshRenderer rendererAt(int index) {
        return renderers.get(index);
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
