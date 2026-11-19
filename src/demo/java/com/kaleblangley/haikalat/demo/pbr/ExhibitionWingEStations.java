package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ChromaticAberrationSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.FilmGrainSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.VignetteSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Wing E - camera and final-image stations.
 *
 * <p>#12 DOF, #21 motion blur, #22 SMAA, #23 vignette, #24 film grain and #25
 * chromatic aberration.  The station geometry is a camera test stage with
 * depth markers and high-frequency edges; the phase difference is the real
 * production post-process/AA request, never a tinted overlay.</p>
 */
final class ExhibitionWingEStations {
    private ExhibitionWingEStations() {
    }

    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 12 -> ExhibitionWingEStations::depthOfField;
            case 21 -> ExhibitionWingEStations::motionBlur;
            case 22 -> ExhibitionWingEStations::smaa;
            case 23 -> ExhibitionWingEStations::vignette;
            case 24 -> ExhibitionWingEStations::filmGrain;
            case 25 -> ExhibitionWingEStations::chromaticAberration;
            default -> null;
        };
    }

    private static ExhibitionStationView depthOfField(ExhibitionStationBuildContext context,
                                                      ExhibitionStation station,
                                                      ExhibitionPhase phase) {
        Scene scene = cameraStage(context.assets(), stationCamera());
        RenderFeatureSettings settings = ExhibitionSettings.withPostProcess(context.renderSettings(),
                phase == ExhibitionPhase.EFFECT
                        ? PostProcessSettings.defaults().withDepthOfField(
                                DepthOfFieldSettings.of(2.7f, 1.4f, 12.0f))
                        : PostProcessSettings.defaults());
        return view(scene, settings,
                phase == ExhibitionPhase.EFFECT ? "focus 2.7 m / aperture 1.4 / max CoC 12 px" : "");
    }

    private static ExhibitionStationView motionBlur(ExhibitionStationBuildContext context,
                                                    ExhibitionStation station,
                                                    ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = cameraStage(assets, stationCamera());
        Material accent = assets.accentMaterial;
        // Known-velocity subject: sweeps horizontally across the static stage.
        scene.add(new SceneObject(assets.sphere, accent, (out, frame) ->
                out.identity()
                        .translate(-2.6f + 5.2f * (frame % 90) / 89.0f, 1.15f, -2.0f)
                        .scale(0.55f)));
        RenderFeatureSettings settings = ExhibitionSettings.withPostProcess(context.renderSettings(),
                phase == ExhibitionPhase.EFFECT
                        ? PostProcessSettings.defaults().withMotionBlur(
                                MotionBlurSettings.of(0.5f, 16.0f))
                        : PostProcessSettings.defaults());
        return view(scene, settings,
                phase == ExhibitionPhase.EFFECT ? "shutter 0.5 / max radius 16 px" : "");
    }

    private static ExhibitionStationView smaa(ExhibitionStationBuildContext context,
                                              ExhibitionStation station,
                                              ExhibitionPhase phase) {
        RenderSettings render = ExhibitionSettings.withAntiAliasing(context.renderSettings(),
                phase == ExhibitionPhase.EFFECT ? AntiAliasingMode.SMAA : AntiAliasingMode.NONE);
        Scene scene = cameraStage(context.assets(), stationCamera());
        addHighFrequencyBars(scene, context.assets());
        return view(scene, ExhibitionSettings.base(render),
                phase == ExhibitionPhase.EFFECT ? "SMAA 1x HIGH on ACES HDR" : "no anti-aliasing");
    }

    private static ExhibitionStationView vignette(ExhibitionStationBuildContext context,
                                                  ExhibitionStation station,
                                                  ExhibitionPhase phase) {
        Scene scene = postProcessBoard(context.assets(), stationCamera());
        RenderFeatureSettings settings = ExhibitionSettings.withPostProcess(context.renderSettings(),
                phase == ExhibitionPhase.EFFECT
                        ? PostProcessSettings.builder()
                                .vignette(VignetteSettings.of(0.55f, 0.4f, 0.6f)).build()
                        : PostProcessSettings.defaults());
        return view(scene, settings,
                phase == ExhibitionPhase.EFFECT ? "intensity 0.55 / smoothness 0.4 / roundness 0.6" : "");
    }

    private static ExhibitionStationView filmGrain(ExhibitionStationBuildContext context,
                                                   ExhibitionStation station,
                                                   ExhibitionPhase phase) {
        Scene scene = postProcessBoard(context.assets(), stationCamera());
        RenderFeatureSettings settings = ExhibitionSettings.withPostProcess(context.renderSettings(),
                phase == ExhibitionPhase.EFFECT
                        ? PostProcessSettings.builder()
                                .filmGrain(FilmGrainSettings.of(0.18f, 0.5f, 0x5EED)).build()
                        : PostProcessSettings.defaults());
        return view(scene, settings,
                phase == ExhibitionPhase.EFFECT ? "intensity 0.18 / response 0.5 / seed 0x5EED" : "");
    }

    private static ExhibitionStationView chromaticAberration(ExhibitionStationBuildContext context,
                                                             ExhibitionStation station,
                                                             ExhibitionPhase phase) {
        Scene scene = postProcessBoard(context.assets(), stationCamera());
        RenderFeatureSettings settings = ExhibitionSettings.withPostProcess(context.renderSettings(),
                phase == ExhibitionPhase.EFFECT
                        ? PostProcessSettings.builder()
                                .chromaticAberration(ChromaticAberrationSettings.of(0.01f, 0.8f, 6))
                                .build()
                        : PostProcessSettings.defaults());
        return view(scene, settings,
                phase == ExhibitionPhase.EFFECT ? "intensity 0.01 / radial 0.8 / 6 samples" : "");
    }

    private static ExhibitionStationView view(Scene scene, RenderFeatureSettings settings,
                                              String note) {
        return new ExhibitionStationView(scene, settings, null, note);
    }

    private static Camera stationCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.6f, 4.6f, -90.0f, -5.0f);
    }

    /** Depth-marked stage: near focus target, middle/far poles plus ground and wall. */
    private static Scene cameraStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        Material white = whiteMaterial(assets);
        // Near focus target: a small high-contrast disc about 2.7 m from the camera.
        scene.add(SceneObject.fixed(assets.sphere, white,
                new Matrix4f().identity().translation(0.85f, 1.35f, 1.95f).scale(0.30f)));
        scene.add(SceneObject.fixed(assets.sphere, assets.darkMaterial,
                new Matrix4f().identity().translation(0.85f, 1.35f, 1.95f).scale(0.16f)));
        for (int index = 0; index < 3; index++) {
            float z = -1.6f - index * 1.8f;
            float scale = 1.0f - index * 0.12f;
            scene.add(SceneObject.fixed(assets.cube,
                    index == 1 ? assets.accentMaterial : white,
                    new Matrix4f().identity().translation((index - 1) * 1.7f, 0.55f * scale, z)
                            .scale(0.16f, 0.55f * scale, 0.16f)));
        }
        scene.add(SceneObject.fixed(assets.sphere, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.15f, -3.4f).scale(0.42f)));
        return scene;
    }

    /** Flat brightness board with a centre cross and edge lines for final-image effects. */
    private static Scene postProcessBoard(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        Material white = whiteMaterial(assets);
        Material dark = assets.darkMaterial;
        for (int index = 0; index < 3; index++) {
            Material material = switch (index) {
                case 0 -> dark;
                case 1 -> assets.neutralMaterial;
                default -> white;
            };
            scene.add(SceneObject.fixed(assets.cube, material,
                    new Matrix4f().identity().translation((index - 1) * 2.1f, 1.5f, -3.2f)
                            .scale(0.85f, 0.62f, 0.04f)));
        }
        // Centre cross.
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(0.0f, 1.5f, -2.6f)
                        .scale(0.42f, 0.035f, 0.02f)));
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(0.0f, 1.5f, -2.6f)
                        .scale(0.035f, 0.42f, 0.02f)));
        // Edge lines: visible only when the final image is stretched/offset.
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(-3.4f, 1.0f, -2.9f)
                        .scale(0.03f, 0.9f, 0.02f)));
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(3.4f, 1.0f, -2.9f)
                        .scale(0.03f, 0.9f, 0.02f)));
        return scene;
    }

    /** Rotated thin bars produce the sub-pixel edges SMAA is supposed to repair. */
    private static void addHighFrequencyBars(Scene scene, ExhibitionAssets assets) {
        Material white = whiteMaterial(assets);
        for (int index = 0; index < 7; index++) {
            scene.add(SceneObject.fixed(assets.cube, white,
                    new Matrix4f().identity().translation(-2.4f + index * 0.8f, 1.5f, -2.2f)
                            .rotateZ(0.35f).scale(0.025f, 1.35f, 0.02f), false));
        }
    }

    private static Material whiteMaterial(ExhibitionAssets assets) {
        return assets.pbr(new PbrMaterialProperties(new Vector4f(0.92f, 0.92f, 0.94f, 1.0f),
                0.0f, 0.35f, 1.0f, 1.0f, new Vector3f(0.02f), java.util.Map.of()));
    }
}
