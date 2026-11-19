package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Deterministic studio scene shared by every station: neutral ground, a back
 * wall, scale references, a shadow-casting key light and two coloured fills.
 * Stations add their subject geometry and keep this rig so A/B compares the
 * same receiving surfaces.
 */
final class ExhibitionSceneKit {
    private static final float WALL_Z = -6.0f;
    private static final float GROUND_HALF_EXTENT = 16.0f;

    private ExhibitionSceneKit() {
    }

    static Camera camera(float x, float y, float z, float yawDegrees, float pitchDegrees) {
        return new Camera(new Vector3f(x, y, z), new Vector3f(0.0f, 1.0f, 0.0f),
                yawDegrees, pitchDegrees);
    }

    /** Default observation camera: slightly below eye height, looking at the wall. */
    static Camera defaultCamera() {
        return camera(0.0f, 1.55f, 4.4f, -90.0f, -7.0f);
    }

    static Scene studio(ExhibitionAssets assets, Camera camera) {
        Scene scene = new Scene(camera);
        // Ground: unit quad (x/y plane, +Z normal) rotated flat.
        scene.add(SceneObject.fixed(assets.groundQuad, assets.groundMaterial,
                new Matrix4f().identity().translation(0.0f, 0.0f, 0.0f)
                        .rotateX((float) (-Math.PI / 2.0))
                        .scale(GROUND_HALF_EXTENT, GROUND_HALF_EXTENT, 1.0f), false));
        // Back wall.
        scene.add(SceneObject.fixed(assets.quad, assets.wallMaterial,
                new Matrix4f().identity().translation(0.0f, 2.2f, WALL_Z)
                        .scale(7.5f, 3.2f, 1.0f), false));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.45f, -1.0f, -0.55f),
                new Vector3f(1.0f, 0.94f, 0.86f), 2.6f));
        scene.addLight(SceneLight.point(new Vector3f(2.6f, 2.8f, 2.4f),
                new Vector3f(0.25f, 0.5f, 1.0f), 9.0f, 9.0f));
        scene.addLight(SceneLight.point(new Vector3f(-2.6f, 1.7f, 2.0f),
                new Vector3f(1.0f, 0.55f, 0.25f), 6.0f, 8.0f));
        return scene;
    }

    /** Neutral scale references behind the subject: one thin slab and one dark block. */
    static void addScaleProps(Scene scene, ExhibitionAssets assets) {
        Matrix4f slab = new Matrix4f().identity().translation(-2.9f, 0.45f, -3.2f)
                .scale(0.12f, 0.45f, 0.9f);
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial, slab));
        Matrix4f block = new Matrix4f().identity().translation(2.9f, 0.32f, -3.2f)
                .scale(0.7f, 0.32f, 0.7f);
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial, block));
    }

    static SceneObject subject(ExhibitionAssets assets, org.joml.Matrix4fc model) {
        return SceneObject.fixed(assets.sphere, assets.accentMaterial, model);
    }
}
