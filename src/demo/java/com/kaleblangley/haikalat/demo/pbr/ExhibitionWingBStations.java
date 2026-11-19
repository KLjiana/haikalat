package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortion;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortionSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenDistortionSource;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.VertexDisplacementDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.VertexDisplacementSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecal;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxDistortionProjector;
import com.kaleblangley.haikalat.subsystems.vfx.VfxDistortion;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/**
 * Wing B - VFX and scene-interaction stations.
 *
 * <p>#9 contact shadows and #10 cloud shadows are pure {@link ShadowSettings}
 * components on the one directional shadow map: baseline and effect publish
 * the exact same scene, light and shadow-map tuning and differ only in the
 * contact/cloud component.  #5 vertex displacement uses the real per-object
 * {@link VertexDisplacementDrawBinding} on a bounded shared mesh; the baseline
 * is the same object with {@link VertexDisplacementSettings#disabled()}.</p>
 *
 * <p>#4 screen-space distortion feeds a station-owned {@link VfxDistortion}
 * through the session's registered HDR VFX composite, projects it with
 * {@link VfxDistortionProjector} and publishes the bounded list through
 * {@link ScreenDistortionSource}.  #6 projected decals returns a bounded real
 * {@link ProjectedDecal} batch through the session's registered production
 * decal recorder.  Both baselines keep the identical scene with the component
 * disabled and no frame data.</p>
 */
final class ExhibitionWingBStations {
    /** Directional shadow-map tuning shared by the contact/cloud shadow presets. */
    private static final int SHADOW_RESOLUTION = 2048;
    private static final float SHADOW_SCENE_RADIUS = 20.0f;
    private static final float SHADOW_NEAR_PLANE = 0.1f;
    private static final float SHADOW_FAR_PLANE = 80.0f;
    private static final float SHADOW_BIAS = 0.003f;

    private ExhibitionWingBStations() {
    }

    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 9 -> ExhibitionWingBStations::contactShadows;
            case 10 -> ExhibitionWingBStations::cloudShadows;
            case 5 -> ExhibitionWingBStations::vertexDisplacement;
            case 4 -> ExhibitionWingBStations::screenDistortion;
            case 6 -> ExhibitionWingBStations::projectedDecals;
            default -> null;
        };
    }

    private static ExhibitionStationView contactShadows(ExhibitionStationBuildContext context,
                                                        ExhibitionStation station,
                                                        ExhibitionPhase phase) {
        Scene scene = contactShadowStage(context.assets(), contactShadowCamera());
        ContactShadowSettings contact = phase == ExhibitionPhase.EFFECT
                ? new ContactShadowSettings(true, 0.9f, 0.35f, 24, 0.55f)
                : ContactShadowSettings.disabled();
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .directionalShadowMap(shadowMap(contact, CloudShadowSettings.disabled()))
                .build();
        return new ExhibitionStationView(scene, settings, null,
                phase == ExhibitionPhase.EFFECT
                        ? "distance 0.9 m / thickness 0.35 / 24 steps / strength 0.55"
                        : "");
    }

    private static ExhibitionStationView cloudShadows(ExhibitionStationBuildContext context,
                                                      ExhibitionStation station,
                                                      ExhibitionPhase phase) {
        Scene scene = cloudShadowStage(context.assets(), cloudShadowCamera());
        CloudShadowSettings cloud = phase == ExhibitionPhase.EFFECT
                ? new CloudShadowSettings(true, 0.62f, 0.4f, 0.2f, 1.4f, 0.72f)
                : CloudShadowSettings.disabled();
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .directionalShadowMap(shadowMap(ContactShadowSettings.disabled(), cloud))
                .build();
        return new ExhibitionStationView(scene, settings, null,
                phase == ExhibitionPhase.EFFECT
                        ? "coverage 0.62 / softness 0.4 / scale 0.2 / speed 1.4 / strength 0.72"
                        : "");
    }

    private static ExhibitionStationView vertexDisplacement(ExhibitionStationBuildContext context,
                                                             ExhibitionStation station,
                                                             ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        VertexDisplacementSettings model = phase == ExhibitionPhase.EFFECT
                ? VertexDisplacementSettings.localSinusoidal(0.12f, 2.4f, 1.6f, 0.0f)
                : VertexDisplacementSettings.disabled();
        VertexDisplacementDrawBinding binding = new VertexDisplacementDrawBinding(model);
        Scene scene = displacementStage(assets);
        Matrix4f subjectModel = new Matrix4f().identity()
                .translation(0.0f, 1.12f, -1.6f).scale(1.05f);
        // Same mesh/material/model in both phases; only the draw binding changes.
        scene.add(SceneObject.fixed(assets.sphere, assets.accentMaterial, subjectModel,
                true, binding));
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings());
        ExhibitionStationDriver driver = phase == ExhibitionPhase.EFFECT
                ? new DisplacementTimeDriver(binding, model) : null;
        return new ExhibitionStationView(scene, settings, driver,
                phase == ExhibitionPhase.EFFECT
                        ? "amplitude 0.12 / frequency 2.4 / speed 1.6 / phase 0"
                        : "");
    }

    private static ExhibitionStationView screenDistortion(ExhibitionStationBuildContext context,
                                                          ExhibitionStation station,
                                                          ExhibitionPhase phase) {
        Scene scene = screenDistortionStage(context.assets());
        ScreenDistortionDriver driver = phase == ExhibitionPhase.EFFECT
                ? new ScreenDistortionDriver() : null;
        RenderFeatureSettings settings = phase == ExhibitionPhase.EFFECT
                ? ExhibitionSettings.withPostProcess(context.renderSettings(),
                        PostProcessSettings.builder()
                                .screenDistortion(ScreenDistortionSettings.builder()
                                        .enabled(true)
                                        .maximumOffsetUv(0.05f)
                                        .maximumContributions(2)
                                        .edgeFeatherUv(0.05f)
                                        .source(driver.source())
                                        .build())
                                .build())
                : ExhibitionSettings.base(context.renderSettings());
        return new ExhibitionStationView(scene, settings, driver,
                phase == ExhibitionPhase.EFFECT
                        ? "shock ring r 1.25 m / haze r 0.9 m / peak offset 0.05 UV / 2 layers"
                        : "");
    }

    private static ExhibitionStationView projectedDecals(ExhibitionStationBuildContext context,
                                                         ExhibitionStation station,
                                                         ExhibitionPhase phase) {
        Scene scene = projectedDecalStage(context.assets());
        ProjectedDecalSettings decalSettings = phase == ExhibitionPhase.EFFECT
                ? new ProjectedDecalSettings(true, 0.6f, 0.05f, 0.5f, false, true, 8)
                : ProjectedDecalSettings.disabled();
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .projectedDecals(decalSettings)
                .build();
        ExhibitionStationDriver driver = phase == ExhibitionPhase.EFFECT
                ? new ProjectedDecalDriver(projectedDecalBatch()) : null;
        return new ExhibitionStationView(scene, settings, driver,
                phase == ExhibitionPhase.EFFECT
                        ? "3 decals on floor/slope/wall / thickness 0.6 / surfaceOffset 0.05"
                                + " / facing 0.5 / occluder in front"
                        : "");
    }

    /** Stationary grounded column plus a floor gap ladder, on a floor-level camera. */
    private static Scene contactShadowStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        // Stationary grounded subject: its base sits exactly on the floor, so the
        // screen-space contact term darkens the floor band around it.
        scene.add(SceneObject.fixed(assets.cube, assets.accentMaterial,
                new Matrix4f().identity().translation(-0.4f, 0.6f, -1.5f)
                        .scale(0.55f, 0.6f, 0.55f)));
        // Gap ladder: identical blocks lifted by increasing off-ground gaps; the
        // contact darkening fades out as the gap approaches the march distance.
        float[] gaps = {0.0f, 0.06f, 0.18f, 0.45f, 0.9f};
        for (int index = 0; index < gaps.length; index++) {
            float x = -2.1f + index * 0.9f;
            Material material = index == 0 ? assets.neutralMaterial : assets.darkMaterial;
            scene.add(SceneObject.fixed(assets.cube, material,
                    new Matrix4f().identity()
                            .translation(x, 0.3f + gaps[index], -1.8f)
                            .scale(0.3f)));
        }
        // Grounded receiver near the frame edge: the pass intentionally falls back
        // to the cascaded result inside the 5% screen border instead of faking it.
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(2.35f, 0.35f, -1.3f)
                        .scale(0.32f, 0.35f, 0.32f)));
        return scene;
    }

    /** Large floor plus fixed shadow-casting directional light and readable props. */
    private static Scene cloudShadowStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        // Wide low plinth gives the world-space cloud mask a flat, undistorted
        // receiving surface in front of the camera.
        scene.add(SceneObject.fixed(assets.cube, assets.wallMaterial,
                new Matrix4f().identity().translation(0.0f, 0.05f, -1.2f)
                        .scale(3.2f, 0.05f, 2.1f), false));
        // Static props at different heights prove the mask is anchored in world
        // space (floor, plinth and wall shade with different light directions).
        Material[] materials = {assets.neutralMaterial, assets.accentMaterial,
                assets.darkMaterial};
        for (int index = 0; index < materials.length; index++) {
            scene.add(SceneObject.fixed(assets.cube, materials[index],
                    new Matrix4f().identity()
                            .translation(-1.8f + index * 1.8f, 0.45f, -2.6f)
                            .scale(0.42f, 0.45f, 0.42f)));
        }
        return scene;
    }

    /** Open studio with a bounded subject mesh and scale references. */
    private static Scene displacementStage(ExhibitionAssets assets) {
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.75f, 4.4f, -90.0f, -8.0f));
        ExhibitionSceneKit.addScaleProps(scene, assets);
        return scene;
    }

    /**
     * Background grid, foreground occluders and a real shock-ring cue.  The
     * distortion anchor sits behind the foreground bars, so the screen-space
     * foreground-depth rejection is observable: the bars must not warp.
     */
    private static Scene screenDistortionStage(ExhibitionAssets assets) {
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.55f, 4.4f, -90.0f, -8.0f));
        Material light = assets.neutralMaterial;
        Material dark = assets.darkMaterial;
        for (int index = 0; index < 7; index++) {
            scene.add(SceneObject.fixed(assets.cube,
                    index % 2 == 0 ? light : dark,
                    new Matrix4f().identity()
                            .translate(-2.4f + index * 0.8f, 1.5f, -3.6f)
                            .scale(0.06f, 0.75f, 0.03f), false));
        }
        for (int index = 0; index < 4; index++) {
            scene.add(SceneObject.fixed(assets.cube, light,
                    new Matrix4f().identity()
                            .translate(0.0f, 0.85f + index * 0.45f, -3.58f)
                            .scale(2.6f, 0.035f, 0.03f), false));
        }
        // Foreground bars: closer than the effect plane; the pass rejects them
        // by scene depth instead of warping the foreground.
        scene.add(SceneObject.fixed(assets.cube, dark,
                new Matrix4f().identity().translate(-1.7f, 0.75f, -0.7f)
                        .scale(0.1f, 0.75f, 0.06f)));
        scene.add(SceneObject.fixed(assets.cube, dark,
                new Matrix4f().identity().translate(1.7f, 0.75f, -0.7f)
                        .scale(0.1f, 0.75f, 0.06f)));
        // Keep the lit cue in front of both distortion planes. It marks the
        // wave without being sampled into the grid or casting dots on the floor.
        for (int index = 0; index < 14; index++) {
            double angle = index * Math.PI * 2.0 / 14.0;
            scene.add(SceneObject.fixed(assets.cube, assets.accentMaterial,
                    new Matrix4f().identity()
                            .translate((float) (1.25 * Math.cos(angle)),
                                    1.4f + (float) (1.25 * Math.sin(angle)), -2.0f)
                            .scale(0.07f), false));
        }
        return scene;
    }

    /** Floor, a tilted ramp and a wall panel as opaque decal receivers, plus an occluder. */
    private static Scene projectedDecalStage(ExhibitionAssets assets) {
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.5f, 4.4f, -90.0f, -10.0f));
        // Tilted ramp receiver: rotateZ(+0.35) tilts its top face toward the camera.
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translate(1.75f, 0.35f, -1.4f)
                        .rotateZ(0.35f).scale(1.1f, 0.12f, 1.4f)));
        // Wall panel receiver in front of the studio back wall.
        scene.add(SceneObject.fixed(assets.cube, assets.wallMaterial,
                new Matrix4f().identity().translate(-1.7f, 1.35f, -2.6f)
                        .scale(1.05f, 1.25f, 0.06f)));
        // Opaque occluder standing on the floor decal: its depth must reject the
        // decal instead of leaking it onto the blocker.
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translate(0.7f, 0.42f, -0.55f)
                        .scale(0.32f, 0.42f, 0.32f)));
        return scene;
    }

    /** Same three world-space batches in both phases; only the phase list differs. */
    private static List<ProjectedDecal> projectedDecalBatch() {
        AssetRef floorTexture =
                AssetRef.of("/vfx/masks/kenney/light_masks/transparent/circle_a_streaks_noise.png");
        AssetRef slopeTexture =
                AssetRef.of("/vfx/masks/kenney/light_masks/transparent/circle_b_streaks_noise.png");
        AssetRef wallTexture =
                AssetRef.of("/vfx/masks/kenney/light_masks/transparent/circle_c_streaks_noise.png");
        // The ramp top face is rotateZ(+0.35) of the +Y face: normal
        // (-sin(0.35), cos(0.35), 0) and face centre offset by R * (0, 0.12, 0).
        return List.of(
                new ProjectedDecal(1L, floorTexture,
                        new Vector3f(0.0f, 0.004f, -1.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f),
                        new Vector3f(0.0f, 0.0f, -1.0f),
                        new Vector2f(2.6f, 2.2f),
                        new Vector4f(1.0f, 0.55f, 0.25f, 0.95f), 2.0f),
                new ProjectedDecal(2L, slopeTexture,
                        new Vector3f(1.709f, 0.463f, -1.4f),
                        new Vector3f(-0.343f, 0.939f, 0.0f),
                        new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector2f(1.5f, 1.9f),
                        new Vector4f(0.4f, 0.8f, 1.0f, 0.9f), 1.8f),
                new ProjectedDecal(3L, wallTexture,
                        new Vector3f(-1.7f, 1.35f, -2.52f),
                        new Vector3f(0.0f, 0.0f, 1.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f),
                        new Vector2f(1.5f, 1.9f),
                        new Vector4f(1.0f, 0.3f, 0.15f, 0.85f), 2.0f));
    }

    private static DirectionalShadowMap shadowMap(ContactShadowSettings contact,
                                                  CloudShadowSettings cloud) {
        return new DirectionalShadowMap(new ShadowSettings(SHADOW_RESOLUTION,
                SHADOW_SCENE_RADIUS, SHADOW_NEAR_PLANE, SHADOW_FAR_PLANE, SHADOW_BIAS,
                contact, cloud));
    }

    private static Camera contactShadowCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.05f, 3.4f, -90.0f, -14.0f);
    }

    private static Camera cloudShadowCamera() {
        return ExhibitionSceneKit.camera(0.0f, 2.0f, 5.6f, -90.0f, -20.0f);
    }

    /** Supplies the caller-owned clock to the per-object displacement binding. */
    private static final class DisplacementTimeDriver implements ExhibitionStationDriver {
        private final VertexDisplacementDrawBinding binding;
        private final VertexDisplacementSettings settings;
        private float elapsedSeconds;

        private DisplacementTimeDriver(VertexDisplacementDrawBinding binding,
                                       VertexDisplacementSettings settings) {
            this.binding = binding;
            this.settings = settings;
        }

        @Override
        public void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
            // Cached views may be re-entered; restart the deterministic clock.
            elapsedSeconds = 0.0f;
            binding.update(settings, 0.0f);
        }

        @Override
        public void onFrame(RenderPipeline pipeline, int frameIndex, float deltaSeconds,
                            ExhibitionPhase phase) {
            elapsedSeconds += deltaSeconds;
            binding.update(settings, elapsedSeconds);
        }
    }

    /**
     * Projects the station's world-space shock ring/haze into the bounded
     * screen-space contribution list.  The session's registered HDR VFX
     * composite calls {@link #record} before the distortion pass records, so the
     * same frame sees the updated list; the source instance stays stable for the
     * pipeline generation, so enabling the effect rebuilds the graph only once.
     */
    private static final class ScreenDistortionDriver implements ExhibitionStationDriver {
        private static final long EFFECT_ID = 4L;
        private static final Vector3f RING_CENTER = new Vector3f(0.0f, 1.4f, -2.4f);
        private static final Vector3f HAZE_CENTER = new Vector3f(-0.25f, 1.1f, -2.4f);

        private List<ScreenDistortion> distortions = List.of();
        private float elapsedSeconds;
        private float phase;

        ScreenDistortionSource source() {
            return this::currentDistortions;
        }

        @Override
        public void onSelect(RenderPipeline pipeline, ExhibitionPhase exhibitionPhase) {
            elapsedSeconds = 0f;
        }

        @Override
        public void onFrame(RenderPipeline pipeline, int frameIndex, float deltaSeconds,
                            ExhibitionPhase exhibitionPhase) {
            phase = elapsedSeconds;
            elapsedSeconds += deltaSeconds;
        }

        @Override
        public ExhibitionVfxFeed vfxFeed() {
            return this::record;
        }

        private void record(CommandBuffer commands, Matrix4f projection, Matrix4f view,
                            int sceneDepthTexture, int width, int height, int frameIndex,
                            float deltaSeconds) {
            List<VfxDistortion> authored = List.of(
                    new VfxDistortion(EFFECT_ID, 0, RING_CENTER, 1.25f,
                            new Vector2f(0.05f, 0.02f), 1.0f, 0.04f, 1.0f, 0.0f, phase),
                    new VfxDistortion(EFFECT_ID, 1, HAZE_CENTER, 0.9f,
                            new Vector2f(0.02f, 0.035f), 0.8f, 0.04f, 0.0f, 0.6f, phase));
            distortions = VfxDistortionProjector.project(authored, projection, view);
        }

        private List<ScreenDistortion> currentDistortions() {
            return distortions;
        }
    }

    /** Supplies the static, bounded world-space projected-decal batch each frame. */
    private static final class ProjectedDecalDriver implements ExhibitionStationDriver {
        private final List<ProjectedDecal> decals;

        private ProjectedDecalDriver(List<ProjectedDecal> decals) {
            this.decals = decals;
        }

        @Override
        public List<ProjectedDecal> projectedDecals(int frameIndex, float deltaSeconds,
                                                    ExhibitionPhase phase) {
            return decals;
        }
    }
}
