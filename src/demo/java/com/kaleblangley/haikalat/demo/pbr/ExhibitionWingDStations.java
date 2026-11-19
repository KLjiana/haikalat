package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.RefractionSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterRefractionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Map;

/**
 * Wing D - water, refraction and reflections.
 *
 * <p>#11 water refraction, #14 finite water surface, #20 SSR, #30 planar
 * reflections and #36 caustics. The shared observation subject is a real
 * 5 m x 5 m pool with a 1 m deep floor, checker receiver tiles, two partially
 * submerged boxes and a surrounding quay; every A/B phase keeps the exact same
 * geometry and only switches the production component inside the complete
 * {@link RenderFeatureSettings} snapshot. The baseline of a water-carried
 * station (#11/#30/#36) keeps the finite water surface active and sets the
 * observed component to its exact {@code disabled()} value, so the A/B compares
 * "with and without the effect" on the same receiving surface instead of
 * comparing "water versus no water".</p>
 *
 * <p>#13 single-layer glass refraction uses a scene-owned consumer
 * ({@code RefractionPass.presentIn(scene)}) that is not part of
 * {@code PipelineTopology}, so both #13 phases are returned with
 * {@code pipelineRebuild = true}: the session then builds a fresh generation for
 * the phase scene and the refraction pass is assembled from the real glass
 * material. The baseline keeps the identical sphere pose and camera with an
 * opaque PBR material, so the A/B compares refraction against an opaque surface
 * of the same silhouette instead of tinting the frame.</p>
 */
final class ExhibitionWingDStations {
    private static final float WATER_LEVEL = 0.0f;
    private static final float POOL_FLOOR_Y = -1.0f;
    private static final float RIM_CENTER = 3.0f;
    private static final float RIM_HALF_THICKNESS = 0.25f;
    private static final float POOL_HALF_EXTENT = RIM_CENTER - RIM_HALF_THICKNESS;
    private static final float RIM_TOP_Y = 0.35f;
    private static final float FLAT_QUAD_ROTATION = (float) (-Math.PI / 2.0);

    private static final Vector3f WATER_TINT = new Vector3f(0.05f, 0.21f, 0.24f);
    private static final Vector3f SKY_FALLBACK = new Vector3f(0.42f, 0.62f, 0.8f);

    private ExhibitionWingDStations() {
    }

    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 11 -> ExhibitionWingDStations::waterRefraction;
            case 13 -> ExhibitionWingDStations::refraction;
            case 14 -> ExhibitionWingDStations::waterSurface;
            case 20 -> ExhibitionWingDStations::screenSpaceReflections;
            case 30 -> ExhibitionWingDStations::planarReflections;
            case 36 -> ExhibitionWingDStations::caustics;
            default -> null;
        };
    }

    /** #11: the same pool seen through the finite water surface's screen-space refraction. */
    private static ExhibitionStationView waterRefraction(ExhibitionStationBuildContext context,
                                                         ExhibitionStation station,
                                                         ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = poolScene(assets, ExhibitionSceneKit.camera(0.0f, 1.7f, 5.2f, -90.0f, -14.0f));
        addSubmergedRuler(scene, assets);
        addForegroundPoles(scene, assets);
        WaterSurfaceSettings water = poolWater(2.0f, 0.65f, 0.085f, 0.6f, 0.75f, 0.4f);
        RenderFeatureSettings.Builder request = ExhibitionSettings
                .base(context.renderSettings()).toBuilder()
                .waterSurface(water);
        if (phase == ExhibitionPhase.EFFECT) {
            // High preset from the #11 card: stronger offset, thinner-water bias.
            request.waterRefraction(WaterRefractionSettings.builder()
                    .enabled(true)
                    .ior(1.333f)
                    .strength(1.25f)
                    .maximumScreenOffset(0.04f)
                    .thicknessScale(1.25f)
                    .foregroundRejectionBias(0.02f)
                    // Missing background is water, not a second unshifted view of the model.
                    .offscreenFallback(WaterRefractionSettings.OffscreenFallback.EDGE_FADE_TINT)
                    .offscreenFallbackColor(WATER_TINT.x, WATER_TINT.y, WATER_TINT.z)
                    .build());
        }
        return view(scene, request.build(), phase == ExhibitionPhase.EFFECT
                ? "IOR 1.333 / strength 1.25 / max offset 0.04 UV / thickness x1.25 / foreground bias 0.02 m"
                : "water surface without screen-space refraction");
    }

    /**
     * #13: a large single-layer glass volume in front of a high-contrast
     * backdrop. Both phases carry {@code pipelineRebuild = true} because the
     * glass material is a scene-derived consumer outside the topology key.
     */
    private static ExhibitionStationView refraction(ExhibitionStationBuildContext context,
                                                    ExhibitionStation station,
                                                    ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        boolean glass = phase == ExhibitionPhase.EFFECT;
        Scene scene = refractionStage(assets, glass);
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings());
        return new ExhibitionStationView(scene, settings, null,
                glass
                        ? "glass: IOR 1.5 / thickness 1.0 m / roughness 0 / transmission 1 / reflection 0"
                        : "opaque PBR volume in the same pose (single-layer refraction off)",
                true);
    }

    /** #14: the finite water surface itself over the same dry basin. */
    private static ExhibitionStationView waterSurface(ExhibitionStationBuildContext context,
                                                      ExhibitionStation station,
                                                      ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = poolScene(assets, ExhibitionSceneKit.camera(0.0f, 1.7f, 5.2f, -90.0f, -14.0f));
        RenderFeatureSettings settings = phase == ExhibitionPhase.EFFECT
                ? ExhibitionSettings.base(context.renderSettings()).toBuilder()
                        .waterSurface(poolWater(2.4f, 0.55f, 0.07f, 0.7f, 0.9f, 0.4f))
                        .build()
                : ExhibitionSettings.base(context.renderSettings());
        return view(scene, settings, phase == ExhibitionPhase.EFFECT
                ? "level 0.0 m / 5.5x5.5 m pool / wavelength 2.4 m / amplitude 0.07 / absorption 0.9 1/m / shore 0.4 m"
                : "");
    }

    /** #20: roughness ladder plus on-screen and off-screen reflectable emitters. */
    private static ExhibitionStationView screenSpaceReflections(ExhibitionStationBuildContext context,
                                                                ExhibitionStation station,
                                                                ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ssrScene(assets, ExhibitionSceneKit.camera(0.0f, 1.7f, 5.0f, -90.0f, -20.0f));
        RenderFeatureSettings settings = ExhibitionSettings.withPostProcess(
                context.renderSettings(), phase == ExhibitionPhase.EFFECT
                        ? PostProcessSettings.builder()
                                .ssr(SsrSettings.builder()
                                        .enabled(true)
                                        .maxDistanceMetres(8.0f)
                                        .maxSteps(48)
                                        .thicknessMetres(0.35f)
                                        .roughnessCutoff(0.35f)
                                        .minimumConfidence(0.12f)
                                        .strength(0.9f)
                                        .edgeFadePixels(32.0f)
                                        .build())
                                .build()
                        : PostProcessSettings.defaults());
        return view(scene, settings, phase == ExhibitionPhase.EFFECT
                ? "8 m ray / 48 steps / thickness 0.35 m / roughness cutoff 0.35 / strength 0.9 / edge fade 32 px"
                : "");
    }

    /** #30: mirror-like pool with known markers; planar reflection sampled by the water. */
    private static ExhibitionStationView planarReflections(ExhibitionStationBuildContext context,
                                                           ExhibitionStation station,
                                                           ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = poolScene(assets, ExhibitionSceneKit.camera(0.0f, 1.6f, 5.4f, -90.0f, -9.0f));
        addReflectionMarkers(scene, assets);
        WaterSurfaceSettings water = poolWater(3.2f, 0.25f, 0.035f, 1.0f, 0.35f, 0.2f);
        RenderFeatureSettings.Builder request = ExhibitionSettings
                .base(context.renderSettings()).toBuilder()
                .waterSurface(water);
        if (phase == ExhibitionPhase.EFFECT) {
            // Medium tier from the #30 card: half resolution, update every 2 frames.
            request.planarReflections(PlanarReflectionSettings.builder()
                    .enabled(true)
                    .planeY(WATER_LEVEL)
                    .resolutionScale(0.5f)
                    .maxResolution(1024)
                    .updateIntervalFrames(2)
                    .nearPlaneOffset(0.02f)
                    .build());
        }
        return view(scene, request.build(), phase == ExhibitionPhase.EFFECT
                ? "plane y=0 / half resolution (<=1024 px) / update every 2 frames / near offset 0.02 m"
                : "water surface with IBL fallback only (planar reflections off)");
    }

    /** #36: caustics projected through the finite water onto the submerged receiver. */
    private static ExhibitionStationView caustics(ExhibitionStationBuildContext context,
                                                  ExhibitionStation station,
                                                  ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = poolScene(assets, ExhibitionSceneKit.camera(0.0f, 1.9f, 5.6f, -90.0f, -20.0f));
        addCausticsOccluder(scene, assets);
        WaterSurfaceSettings water = poolWater(2.6f, 0.5f, 0.07f, 0.5f, 0.7f, 0.35f);
        RenderFeatureSettings.Builder request = ExhibitionSettings
                .base(context.renderSettings()).toBuilder()
                .waterSurface(water);
        if (phase == ExhibitionPhase.EFFECT) {
            request.caustics(CausticsSettings.builder()
                    .enabled(true)
                    .intensity(1.35f)
                    .color(0.55f, 0.8f, 1.0f)
                    .worldScaleMetres(1.4f)
                    .animationSpeed(0.3f)
                    .maximumProjectionDistance(8.0f)
                    .waterPlane(WATER_LEVEL)
                    .waterRegion(-POOL_HALF_EXTENT, -POOL_HALF_EXTENT,
                            POOL_HALF_EXTENT, POOL_HALF_EXTENT)
                    .receiverHeight(POOL_FLOOR_Y - 0.3f, -0.05f)
                    .depthBias(0.05f)
                    .distanceFalloff(0.08f)
                    .edgeFadeDistance(0.8f)
                    .maximumReceiverSlopeDegrees(55.0f)
                    .patternTexture("")
                    .build());
        }
        return view(scene, request.build(), phase == ExhibitionPhase.EFFECT
                ? "intensity 1.35 / pattern 1.4 m / speed 0.3 / max projection 8 m / receiver band [-1.3,-0.05] m"
                : "water surface without projected caustics");
    }

    private static ExhibitionStationView view(Scene scene, RenderFeatureSettings settings,
                                              String note) {
        return new ExhibitionStationView(scene, settings, null, note);
    }

    /** Bounded production water request for the shared pool rectangle. */
    private static WaterSurfaceSettings poolWater(float wavelength, float speed, float amplitude,
                                                  float direction, float absorption,
                                                  float shoreFade) {
        return WaterSurfaceSettings.builder()
                .enabled(true)
                .level(WATER_LEVEL)
                .bounds(-POOL_HALF_EXTENT, -POOL_HALF_EXTENT,
                        POOL_HALF_EXTENT, POOL_HALF_EXTENT)
                .waveWavelength(wavelength)
                .waveSpeed(speed)
                .waveAmplitude(amplitude)
                .waveDirectionRadians(direction)
                .waterColor(WATER_TINT.x, WATER_TINT.y, WATER_TINT.z)
                .absorptionDensity(absorption)
                .shoreFadeDistance(shoreFade)
                .reflectionFallback(SKY_FALLBACK.x, SKY_FALLBACK.y, SKY_FALLBACK.z)
                .build();
    }

    /**
     * The shared D-wing basin: 1 m deep floor with high-contrast checker
     * receiver tiles, a quay ring above the water line, dry shore ground, back
     * wall and the same neutral studio light rig every other station uses.
     */
    private static Scene poolScene(ExhibitionAssets assets, Camera camera) {
        Scene scene = new Scene(camera);
        Material floor = pbr(assets, 0.22f, 0.24f, 0.26f, 0.0f, 0.88f);
        scene.add(SceneObject.fixed(assets.quad, floor,
                flatQuad(0.0f, POOL_FLOOR_Y, 0.0f, 3.6f, 3.6f), false));
        addCheckerTiles(scene, assets);
        addPoolRim(scene, assets, floor);
        addOuterGround(scene, assets, floor);
        scene.add(SceneObject.fixed(assets.quad, assets.wallMaterial,
                new Matrix4f().identity().translation(0.0f, 2.2f, -8.0f)
                        .scale(9.0f, 3.2f, 1.0f), false));
        addSubmergedBoxes(scene, assets);
        addNeutralLights(scene);
        return scene;
    }

    /** High-contrast receiver pattern so refraction offsets and caustics are readable. */
    private static void addCheckerTiles(Scene scene, ExhibitionAssets assets) {
        Material light = pbr(assets, 0.82f, 0.85f, 0.88f, 0.0f, 0.55f);
        Material dark = pbr(assets, 0.05f, 0.06f, 0.07f, 0.0f, 0.7f);
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 5; column++) {
                float x = -1.9f + column * 0.95f;
                float z = -1.9f + row * 0.95f;
                scene.add(SceneObject.fixed(assets.cube,
                        ((row + column) & 1) == 0 ? light : dark,
                        new Matrix4f().identity().translation(x, POOL_FLOOR_Y + 0.02f, z)
                                .scale(0.44f, 0.01f, 0.44f), false));
            }
        }
    }

    /** Quay ring whose top is above the water line, forming a readable shore. */
    private static void addPoolRim(Scene scene, ExhibitionAssets assets, Material material) {
        float centerY = (POOL_FLOOR_Y + RIM_TOP_Y) * 0.5f;
        float halfHeight = (RIM_TOP_Y - POOL_FLOOR_Y) * 0.5f;
        scene.add(SceneObject.fixed(assets.cube, material,
                new Matrix4f().identity()
                        .translation(0.0f, centerY, -RIM_CENTER)
                        .scale(RIM_CENTER + RIM_HALF_THICKNESS, halfHeight, RIM_HALF_THICKNESS)));
        scene.add(SceneObject.fixed(assets.cube, material,
                new Matrix4f().identity()
                        .translation(0.0f, centerY, RIM_CENTER)
                        .scale(RIM_CENTER + RIM_HALF_THICKNESS, halfHeight, RIM_HALF_THICKNESS)));
        scene.add(SceneObject.fixed(assets.cube, material,
                new Matrix4f().identity()
                        .translation(RIM_CENTER, centerY, 0.0f)
                        .scale(RIM_HALF_THICKNESS, halfHeight, RIM_CENTER + RIM_HALF_THICKNESS)));
        scene.add(SceneObject.fixed(assets.cube, material,
                new Matrix4f().identity()
                        .translation(-RIM_CENTER, centerY, 0.0f)
                        .scale(RIM_HALF_THICKNESS, halfHeight, RIM_CENTER + RIM_HALF_THICKNESS)));
    }

    /** Dry shore ground outside the quay, kept level with the quay top. */
    private static void addOuterGround(Scene scene, ExhibitionAssets assets, Material material) {
        float inner = RIM_CENTER + RIM_HALF_THICKNESS;
        float outer = 8.0f;
        float halfStrip = (outer - inner) * 0.5f;
        float centerStrip = (outer + inner) * 0.5f;
        scene.add(SceneObject.fixed(assets.quad, material,
                flatQuad(0.0f, RIM_TOP_Y, centerStrip, outer, halfStrip), false));
        scene.add(SceneObject.fixed(assets.quad, material,
                flatQuad(0.0f, RIM_TOP_Y, -centerStrip, outer, halfStrip), false));
        scene.add(SceneObject.fixed(assets.quad, material,
                flatQuad(centerStrip, RIM_TOP_Y, 0.0f, halfStrip, inner), false));
        scene.add(SceneObject.fixed(assets.quad, material,
                flatQuad(-centerStrip, RIM_TOP_Y, 0.0f, halfStrip, inner), false));
    }

    /** Two blocks crossing the water line: one partially, one fully submerged. */
    private static void addSubmergedBoxes(Scene scene, ExhibitionAssets assets) {
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity()
                        .translation(-1.5f, POOL_FLOOR_Y + 0.55f, -1.3f)
                        .scale(0.4f, 0.55f, 0.4f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity()
                        .translation(1.6f, POOL_FLOOR_Y + 0.35f, 0.9f)
                        .scale(0.55f, 0.35f, 0.55f)));
    }

    /** #11: alternating submerged ruler bars give the refracted lookup a known target. */
    private static void addSubmergedRuler(Scene scene, ExhibitionAssets assets) {
        Material light = pbr(assets, 0.9f, 0.92f, 0.95f, 0.0f, 0.4f);
        Material dark = pbr(assets, 0.03f, 0.03f, 0.04f, 0.0f, 0.5f);
        for (int index = 0; index < 8; index++) {
            float x = -2.0f + index * 0.57f;
            scene.add(SceneObject.fixed(assets.cube, (index & 1) == 0 ? light : dark,
                    new Matrix4f().identity()
                            .translation(x, POOL_FLOOR_Y + 0.45f, -2.42f)
                            .scale(0.1f, 0.45f, 0.05f), false));
        }
    }

    /** #11: foreground poles between the camera and the pool; their pixels must be rejected. */
    private static void addForegroundPoles(Scene scene, ExhibitionAssets assets) {
        scene.add(SceneObject.fixed(assets.cube, assets.accentMaterial,
                new Matrix4f().identity().translation(1.25f, 1.15f, 3.3f)
                        .scale(0.06f, 0.8f, 0.06f)));
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(-1.75f, 1.05f, 3.5f)
                        .scale(0.05f, 0.7f, 0.05f)));
    }

    /** #30: known above-water markers with an obvious mirrored position. */
    private static void addReflectionMarkers(Scene scene, ExhibitionAssets assets) {
        Material white = pbr(assets, 0.92f, 0.93f, 0.95f, 0.0f, 0.35f);
        scene.add(SceneObject.fixed(assets.cube, assets.accentMaterial,
                new Matrix4f().identity().translation(-1.5f, -0.1f, -1.9f)
                        .scale(0.06f, 0.9f, 0.06f)));
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(1.7f, -0.15f, -1.4f)
                        .scale(0.06f, 0.85f, 0.06f)));
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(0.0f, RIM_TOP_Y + 0.25f, -RIM_CENTER)
                        .scale(0.18f, 0.25f, 0.18f)));
        scene.add(SceneObject.fixed(assets.cube, assets.accentMaterial,
                new Matrix4f().identity().translation(-2.2f, RIM_TOP_Y + 0.18f, RIM_CENTER)
                        .scale(0.16f, 0.18f, 0.16f)));
    }

    /** #36: an above-water occlusion slab; caustics must not light it. */
    private static void addCausticsOccluder(Scene scene, ExhibitionAssets assets) {
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(-1.5f, 0.13f, -1.3f)
                        .scale(0.6f, 0.03f, 0.6f)));
    }

    /**
     * #13 stage: a large sphere (radius 1.2 m) resting on a checker floor in
     * front of a black/white stripe wall and coloured emissive bands. The
     * high-frequency backdrop makes the screen-space refraction offset and its
     * edge fallback observable, and the opaque baseline keeps the exact same
     * silhouette.
     */
    private static Scene refractionStage(ExhibitionAssets assets, boolean glass) {
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.65f, 5.2f, -90.0f, -6.0f));
        addStripeBackdrop(scene, assets);
        addCheckerStage(scene, assets);
        Material volume = glass
                ? assets.glass(new PbrMaterialProperties(new Vector4f(0.94f, 0.97f, 0.99f, 1.0f),
                        0.0f, 0.05f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        RefractionSettings.of(1.5f, 1.0f, 0.0f, 1.0f, 0.0f))
                : pbr(assets, 0.42f, 0.46f, 0.50f, 0.0f, 0.35f);
        scene.add(SceneObject.fixed(assets.sphere, volume,
                new Matrix4f().identity().translation(0.0f, 1.2f, 0.0f).scale(1.2f)));
        return scene;
    }

    /** Alternating white/black stripe wall plus coloured emissive bands behind the glass. */
    private static void addStripeBackdrop(Scene scene, ExhibitionAssets assets) {
        Material white = pbr(assets, 0.94f, 0.95f, 0.97f, 0.0f, 0.45f);
        Material black = pbr(assets, 0.015f, 0.015f, 0.02f, 0.0f, 0.55f);
        for (int index = 0; index < 12; index++) {
            float x = -2.75f + index * 0.5f;
            scene.add(SceneObject.fixed(assets.cube, (index & 1) == 0 ? white : black,
                    new Matrix4f().identity().translation(x, 1.8f, -3.2f)
                            .scale(0.24f, 1.8f, 0.04f), false));
        }
        Material red = emissive(assets, 0.4f, 0.02f, 0.02f,
                new Vector3f(2.6f, 0.15f, 0.1f), 0.0f, 0.6f);
        Material green = emissive(assets, 0.02f, 0.4f, 0.05f,
                new Vector3f(0.15f, 2.6f, 0.2f), 0.0f, 0.6f);
        Material blue = emissive(assets, 0.02f, 0.05f, 0.4f,
                new Vector3f(0.1f, 0.2f, 2.6f), 0.0f, 0.6f);
        scene.add(SceneObject.fixed(assets.cube, red,
                new Matrix4f().identity().translation(0.0f, 2.75f, -3.13f)
                        .scale(2.5f, 0.12f, 0.03f), false));
        scene.add(SceneObject.fixed(assets.cube, green,
                new Matrix4f().identity().translation(0.0f, 0.5f, -3.13f)
                        .scale(2.5f, 0.1f, 0.03f), false));
        scene.add(SceneObject.fixed(assets.cube, blue,
                new Matrix4f().identity().translation(-2.35f, 1.8f, -3.08f)
                        .scale(0.1f, 1.4f, 0.03f), false));
        scene.add(SceneObject.fixed(assets.cube, blue,
                new Matrix4f().identity().translation(2.35f, 1.8f, -3.08f)
                        .scale(0.1f, 1.4f, 0.03f), false));
    }

    /** Reflective-free high-contrast checker floor around and below the glass volume. */
    private static void addCheckerStage(Scene scene, ExhibitionAssets assets) {
        Material light = pbr(assets, 0.9f, 0.92f, 0.94f, 0.0f, 0.4f);
        Material dark = pbr(assets, 0.03f, 0.03f, 0.04f, 0.0f, 0.5f);
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 7; column++) {
                float x = -3.0f + column * 1.0f;
                float z = -2.5f + row * 1.0f;
                scene.add(SceneObject.fixed(assets.cube,
                        ((row + column) & 1) == 0 ? light : dark,
                        new Matrix4f().identity().translation(x, 0.015f, z)
                                .scale(0.48f, 0.015f, 0.48f), false));
            }
        }
    }

    /**
     * #20 stage: emissive wall bars as reflectable content, a four-step metallic
     * roughness ladder on the floor and bright emitters near/off the frame edge.
     */
    private static Scene ssrScene(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        Material warm = emissive(assets, 0.04f, 0.03f, 0.03f,
                new Vector3f(3.4f, 1.1f, 0.25f), 0.0f, 0.85f);
        Material cool = emissive(assets, 0.03f, 0.03f, 0.04f,
                new Vector3f(0.25f, 1.0f, 3.4f), 0.0f, 0.85f);
        for (int index = 0; index < 5; index++) {
            scene.add(SceneObject.fixed(assets.cube, (index & 1) == 0 ? warm : cool,
                    new Matrix4f().identity()
                            .translation(-2.8f + index * 1.4f, 0.95f, -5.9f)
                            .scale(0.14f, 0.42f, 0.05f), false));
        }
        for (int index = 0; index < 3; index++) {
            scene.add(SceneObject.fixed(assets.cube, index == 1 ? warm : cool,
                    new Matrix4f().identity()
                            .translation(-1.8f + index * 1.8f, 2.9f, -5.9f)
                            .scale(0.12f, 0.3f, 0.05f), false));
        }
        // Large polished strips make the roughness ladder span the lower frame:
        // smooth strips show the sharp screen-space reflection, the rough end
        // keeps the prefiltered environment/IBL fallback.
        float[] ladder = {0.05f, 0.15f, 0.30f, 0.55f};
        for (int index = 0; index < ladder.length; index++) {
            Material plate = pbr(assets, 0.92f, 0.94f, 0.97f, 1.0f, ladder[index]);
            scene.add(SceneObject.fixed(assets.cube, plate,
                    new Matrix4f().identity()
                            .translation(-3.0f + index * 2.0f, 0.03f, -2.0f)
                            .scale(1.0f, 0.02f, 5.0f), false));
        }
        // Emitters at / beyond the frame edge: their floor reflection is unavailable
        // to the screen-space march, so the pass must fall back to the IBL term.
        scene.add(SceneObject.fixed(assets.cube, warm,
                new Matrix4f().identity().translation(5.8f, 1.3f, -1.6f)
                        .scale(0.35f, 0.5f, 0.35f), false));
        scene.add(SceneObject.fixed(assets.cube, cool,
                new Matrix4f().identity().translation(-5.8f, 1.3f, -1.6f)
                        .scale(0.35f, 0.5f, 0.35f), false));
        return scene;
    }

    /** Same neutral lights as {@link ExhibitionSceneKit#studio} so A/B and station swaps stay stable. */
    private static void addNeutralLights(Scene scene) {
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.45f, -1.0f, -0.55f),
                new Vector3f(1.0f, 0.94f, 0.86f), 2.6f));
        scene.addLight(SceneLight.point(new Vector3f(2.6f, 2.8f, 2.4f),
                new Vector3f(0.25f, 0.5f, 1.0f), 9.0f, 9.0f));
        scene.addLight(SceneLight.point(new Vector3f(-2.6f, 1.7f, 2.0f),
                new Vector3f(1.0f, 0.55f, 0.25f), 6.0f, 8.0f));
    }

    private static Matrix4f flatQuad(float x, float y, float z, float halfX, float halfZ) {
        return new Matrix4f().identity()
                .translation(x, y, z)
                .rotateX(FLAT_QUAD_ROTATION)
                .scale(halfX, halfZ, 1.0f);
    }

    private static Material pbr(ExhibitionAssets assets, float red, float green, float blue,
                                float metallic, float roughness) {
        return assets.pbr(new PbrMaterialProperties(
                new Vector4f(red, green, blue, 1.0f), metallic, roughness, 1.0f, 1.0f,
                new Vector3f(), Map.of()));
    }

    private static Material emissive(ExhibitionAssets assets, float red, float green, float blue,
                                     Vector3f emissive, float metallic, float roughness) {
        return assets.pbr(new PbrMaterialProperties(
                new Vector4f(red, green, blue, 1.0f), metallic, roughness, 1.0f, 1.0f,
                emissive, Map.of()));
    }
}
