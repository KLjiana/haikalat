package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.FogMediumSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LightType;
import com.kaleblangley.haikalat.subsystems.render3d.LocalFogVolume;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.subsystems.render3d.environment.AtmosphereSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Map;

/**
 * Wing G - specialised capabilities.
 *
 * <p>#29 atmosphere, #32 forward SSS, #33 screen-space SSS, #38 volumetric
 * particles, #40 volumetric clouds, #43 volumetric smoke and #46 hair strands.
 * Every station keeps one neutral studio geometry for both phases; the phase
 * difference is the real production request (material, render-feature
 * component or, for #29, the pipeline-owned atmosphere sky) - never a tinted
 * overlay. Heavy stations use the conservative documented quality tiers.</p>
 *
 * <p>The atmosphere is outside {@link RenderFeatureSettings}, so #29 publishes
 * it through a station driver. Because the session keeps one pipeline across
 * stations, every wing G station driver also clears a stale atmosphere and
 * re-authors its directional light before the feature request is submitted, so
 * a baseline never inherits #29's sky or sun direction.</p>
 */
final class ExhibitionWingGStations {
    /** Session studio key light (light-travel direction), reused by #46/#43. */
    private static final Vector3f STUDIO_LIGHT_TRAVEL = new Vector3f(-0.45f, -1.0f, -0.55f);
    /** #29: low sun (about 12 degrees elevation) ahead of the observation camera. */
    private static final Vector3f ATMOSPHERE_LIGHT_TRAVEL = new Vector3f(0.18f, -0.21f, 0.96f);
    /** #32: warm light behind the skin subject, travelling toward the camera. */
    private static final Vector3f SUBSURFACE_BACKLIGHT_TRAVEL = new Vector3f(0.18f, -0.28f, 0.94f);
    /** #40: cloud single-scatter light also drives the scene directional light. */
    private static final Vector3f CLOUD_LIGHT_TRAVEL = new Vector3f(-0.40f, -0.75f, -0.52f);
    /** #38: analytic particle medium light (travel direction), matches the key light. */
    private static final Vector3f PARTICLE_LIGHT_TRAVEL = new Vector3f(-0.35f, -1.0f, -0.25f);

    /** Bounded single-scatter earth atmosphere at the LOW LUT tier (cheapest documented). */
    private static final AtmosphereSettings ATMOSPHERE =
            AtmosphereSettings.earth(AtmosphereSettings.Quality.LOW)
                    .withSunDirection(ATMOSPHERE_LIGHT_TRAVEL);

    /** #40 A/B share this sky and the cloud march's sun direction. */
    private static final AtmosphereSettings CLOUD_SKY = ATMOSPHERE.withSunDirection(CLOUD_LIGHT_TRAVEL);

    private static final PbrMaterialProperties SKIN_PROPERTIES = new PbrMaterialProperties(
            new Vector4f(0.86f, 0.60f, 0.50f, 1.0f), 0.0f, 0.55f, 1.0f, 1.0f,
            new Vector3f(), Map.of());
    /** #33 seam plates: strongly different diffuse albedo makes the gather visible. */
    private static final PbrMaterialProperties SKIN_PLATE_PROPERTIES = new PbrMaterialProperties(
            new Vector4f(0.45f, 0.22f, 0.16f, 1.0f), 0.0f, 0.5f, 1.0f, 1.0f,
            new Vector3f(), Map.of());
    private static final PbrMaterialProperties WAX_PLATE_PROPERTIES = new PbrMaterialProperties(
            new Vector4f(0.95f, 0.93f, 0.88f, 1.0f), 0.0f, 0.4f, 1.0f, 1.0f,
            new Vector3f(), Map.of());

    /** #32: thick skin body, forward transmission only (no screen-space pass). */
    private static final SubsurfaceScatteringSettings SSS_FORWARD_THICK =
            SubsurfaceScatteringSettings.builder()
                    .profile(SubsurfaceScatteringSettings.Profile.SKIN)
                    .scatterColor(0.90f, 0.34f, 0.20f)
                    .intensity(1.2f)
                    .profileScaleMetres(0.02f)
                    .thicknessSource(SubsurfaceScatteringSettings.ThicknessSource.CONSTANT)
                    .thicknessMetres(0.03f)
                    .screenSpaceEnabled(false)
                    .diffusionRadiusMetres(0.12f)
                    .maxDistanceMetres(2.0f)
                    .build();
    /** #32: same profile on a thin plate, so the thickness attenuation is observable. */
    private static final SubsurfaceScatteringSettings SSS_FORWARD_THIN =
            SSS_FORWARD_THICK.toBuilder().thicknessMetres(0.008f).build();
    /**
     * #33: screen-space mask request, shared by the material and the post-process
     * pass.  A thin shell keeps the thickness attenuation high, so the mask's
     * effective radius stays a usable multiple of a pixel at the station distance.
     */
    private static final SubsurfaceScatteringSettings SSS_SCREEN_SPACE =
            SubsurfaceScatteringSettings.builder()
                    .profile(SubsurfaceScatteringSettings.Profile.SKIN)
                    .scatterColor(0.90f, 0.34f, 0.20f)
                    .intensity(1.5f)
                    .profileScaleMetres(0.02f)
                    .thicknessSource(SubsurfaceScatteringSettings.ThicknessSource.CONSTANT)
                    .thicknessMetres(0.005f)
                    .screenSpaceEnabled(true)
                    .diffusionRadiusMetres(0.50f)
                    .maxDistanceMetres(20.0f)
                    .build();
    /** #33: pale wax neighbour so the diffusion gather has a second diffuse colour to mix. */
    private static final SubsurfaceScatteringSettings SSS_SCREEN_SPACE_WAX =
            SSS_SCREEN_SPACE.toBuilder()
                    .profile(SubsurfaceScatteringSettings.Profile.UNIFORM)
                    .scatterColor(0.95f, 0.90f, 0.86f)
                    .build();

    /** #38: one bounded analytic smoke/dust ellipsoid plus the fog it composites over. */
    private static final VolumetricParticleSettings PARTICLES = new VolumetricParticleSettings(
            true, 1.5f, 0.2f, 0.8f,
            new Vector3f(0.0f, 1.5f, -2.4f), new Vector3f(1.1f, 0.9f, 1.0f),
            32, 0.1f, PARTICLE_LIGHT_TRAVEL, new Vector3f(3.5f, 3.2f, 3.0f), 0.2f,
            VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 0.5f);
    private static final VolumetricFogSettings PARTICLE_FOG = new VolumetricFogSettings(
            true, 40.0f, VolumetricFogSettings.Quality.BALANCED,
            FogMediumSettings.homogeneous(0.015f, new Vector3f(0.70f, 0.75f, 0.85f),
                    new Vector3f()),
            List.of(LocalFogVolume.sphere(new Vector3f(-2.2f, 1.0f, -4.0f), 0.9f, 0.06f,
                    new Vector3f(0.85f, 0.80f, 0.70f))),
            0.3f, false, 0.0f, 1337, new Vector3f(0.10f, 0.0f, 0.0f));

    /** #40: one authored coverage field shared by the visible layer and #10 cloud shadows. */
    private static final CloudShadowSettings CLOUD_COVERAGE =
            new CloudShadowSettings(true, 0.32f, 0.34f, 0.00105f, 2.0f, 0.65f);
    private static final VolumetricCloudSettings CLOUDS = new VolumetricCloudSettings(
            true, CLOUD_COVERAGE, 850.0f, 1_475.0f, 0.0048f, 0.05f, 0.95f,
            CLOUD_LIGHT_TRAVEL, new Vector3f(12.0f, 10.9f, 9.6f), 0.3f,
            40, 40.0f, 4, 2_000.0f, 0.5f, 0.85f, 0.1f, 2.0f, 5.0f, 30.0f)
            .withCloudscape(0.48f, new Vector3f(0.24f, 0.32f, 0.43f), 11_500.0f)
            .withCloudModel(0.82f, 0.85f);

    /** #43: 32^3 deterministic smoke grid with a bounded buoyant source. */
    private static final SmokeSimulationSettings SMOKE_SIMULATION = new SmokeSimulationSettings(
            true, 32, 32, 32, new Vector3f(0.0f, 1.3f, -3.2f), new Vector3f(1.2f, 1.2f, 1.2f),
            1.0f / 60.0f, new Vector3f(0.15f, 0.0f, 0.05f), 0.8f, 3.0f, 0.6f, 1.5f, 2,
            List.of(new SmokeSimulationSettings.Source(new Vector3f(0.0f, 0.8f, -3.2f), 0.6f,
                    16.0f, 4.0f, 0.0f, 3_600.0f)));
    private static final SmokeRenderSettings SMOKE_RENDER = new SmokeRenderSettings(
            true, 0.30f, 0.70f, 3.0f, STUDIO_LIGHT_TRAVEL, new Vector3f(3.0f, 2.7f, 2.4f),
            0.2f, 32, 0.15f, SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f);

    /** #46: deterministic ribbon lock; SORTED_ALPHA (the session uses AA NONE, not MSAA). */
    private static final HairStrandSettings HAIR = HairStrandSettings.builder()
            .strandCount(96).segmentsPerStrand(10)
            .strandLengthMetres(1.0f).rootSpreadMetres(0.12f)
            .flowDirection(new Vector3f(0.0f, -1.0f, 0.0f)).curlMetres(0.07f)
            .strandRadiusMetres(0.012f)
            .widthPixels(3.0f).minimumWidthPixels(1.5f).maximumWidthPixels(8.0f)
            .nearDistanceMetres(8.0f).farDistanceMetres(18.0f)
            .lodMinimumPixels(16.0f).lodWidthScale(0.6f)
            .alpha(0.9f).alphaCutoff(0.3f)
            .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
            .rootColor(new Vector3f(0.09f, 0.06f, 0.04f))
            .tipColor(new Vector3f(0.46f, 0.32f, 0.20f))
            .diffuseStrength(0.9f).specularStrength(2.5f)
            .primaryExponent(48.0f).secondaryExponent(128.0f).secondaryWeight(0.5f)
            .colorVariation(0.2f).castsShadow(true).seed(0x5EED_1A17L)
            .build();
    /** World placement of the procedural lock (root hangs from this point). */
    private static final Matrix4f HAIR_MODEL =
            new Matrix4f().identity().translate(0.0f, 3.1f, -1.75f);

    static Matrix4f placeHair(Matrix4f placement) {
        return placement.mul(HAIR_MODEL, new Matrix4f());
    }

    private ExhibitionWingGStations() {
    }

    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 29 -> ExhibitionWingGStations::atmosphere;
            case 32 -> ExhibitionWingGStations::subsurfaceForward;
            case 33 -> ExhibitionWingGStations::subsurfaceScreenSpace;
            case 38 -> ExhibitionWingGStations::volumetricParticles;
            case 40 -> ExhibitionWingGStations::volumetricClouds;
            case 43 -> ExhibitionWingGStations::volumetricSmoke;
            case 46 -> ExhibitionWingGStations::hairStrands;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- #29

    private static ExhibitionStationView atmosphere(ExhibitionStationBuildContext context,
                                                    ExhibitionStation station,
                                                    ExhibitionPhase phase) {
        Scene scene = horizonStage(context.assets(),
                ExhibitionSceneKit.camera(0.0f, 1.6f, 4.4f, -90.0f, 1.5f),
                ATMOSPHERE_LIGHT_TRAVEL);
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings());
        String note = phase == ExhibitionPhase.EFFECT
                ? "single-scatter sky: LOW LUT 128x32/16x16, 32/16 samples, sun 12 deg, "
                        + "exposure 0.02; both LUTs rebuilt every frame (heavy)"
                : "no atmosphere: neutral HDR environment background";
        return new ExhibitionStationView(scene, settings,
                driver(ATMOSPHERE_LIGHT_TRAVEL, true, null), note);
    }

    /** Neutral horizon stage: huge ground, near occluders and far ridge silhouettes. */
    private static Scene horizonStage(ExhibitionAssets assets, Camera camera,
                                      Vector3f lightTravel) {
        Scene scene = new Scene(camera);
        scene.add(SceneObject.fixed(assets.groundQuad, assets.groundMaterial,
                new Matrix4f().identity().rotateX((float) (-Math.PI / 2.0))
                        .scale(16.0f, 16.0f, 1.0f), false));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(lightTravel),
                new Vector3f(1.0f, 0.94f, 0.86f), 2.8f));
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                at(-2.4f, 0.9f, -7.0f, 0.35f, 0.9f, 0.35f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                at(2.6f, 0.6f, -9.5f, 0.55f, 0.6f, 0.55f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                at(0.0f, 1.3f, -45.0f, 9.0f, 1.3f, 0.8f), false));
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                at(-9.0f, 2.0f, -65.0f, 6.0f, 2.0f, 0.8f), false));
        return scene;
    }

    // ------------------------------------------------------------ #32 SSS

    /**
     * Neutral stage with a back light on the far side of the subject.  The dark
     * backdrop is a non-casting receiver so it cannot shadow the subject; the
     * two weak point lights keep the silhouette readable.
     */
    private static Scene backlitStage(ExhibitionAssets assets, Camera camera,
                                      Vector3f lightTravel) {
        Scene scene = new Scene(camera);
        scene.add(SceneObject.fixed(assets.groundQuad, assets.groundMaterial,
                new Matrix4f().identity().rotateX((float) (-Math.PI / 2.0))
                        .scale(16.0f, 16.0f, 1.0f), false));
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(lightTravel),
                new Vector3f(1.0f, 0.86f, 0.70f), 3.2f));
        scene.addLight(SceneLight.point(new Vector3f(2.6f, 2.8f, 2.4f),
                new Vector3f(0.25f, 0.5f, 1.0f), 5.0f, 9.0f));
        scene.addLight(SceneLight.point(new Vector3f(-2.6f, 1.7f, 2.0f),
                new Vector3f(1.0f, 0.55f, 0.25f), 3.5f, 8.0f));
        scene.add(SceneObject.fixed(assets.quad, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 2.2f, -6.0f)
                        .scale(5.5f, 2.8f, 1.0f), false));
        return scene;
    }

    private static ExhibitionStationView subsurfaceForward(ExhibitionStationBuildContext context,
                                                           ExhibitionStation station,
                                                           ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = backlitStage(assets,
                ExhibitionSceneKit.camera(0.0f, 1.6f, 4.6f, -90.0f, -4.0f),
                SUBSURFACE_BACKLIGHT_TRAVEL);
        Material thick = phase == ExhibitionPhase.EFFECT
                ? subsurfaceMaterial(context, SKIN_PROPERTIES, SSS_FORWARD_THICK)
                : assets.pbr(SKIN_PROPERTIES);
        Material thin = phase == ExhibitionPhase.EFFECT
                ? subsurfaceMaterial(context, SKIN_PROPERTIES, SSS_FORWARD_THIN) : thick;
        scene.add(SceneObject.fixed(assets.sphere, thick, at(0.0f, 1.5f, -2.7f,
                0.95f, 0.95f, 0.95f)));
        // Thin plate: same profile and light, lower thickness means less attenuation.
        scene.add(SceneObject.fixed(assets.cube, thin,
                new Matrix4f().identity().translate(1.75f, 1.3f, -2.5f)
                        .rotateY(-0.35f).scale(0.42f, 0.42f, 0.03f)));
        String note = phase == ExhibitionPhase.EFFECT
                ? "forward skin profile: scale 0.02 m, thickness 0.03 m / 0.008 m, "
                        + "scatter intensity 1.2, warm back light"
                : "same skin material with scattering disabled";
        return new ExhibitionStationView(scene,
                ExhibitionSettings.base(context.renderSettings()),
                driver(SUBSURFACE_BACKLIGHT_TRAVEL, false, null), note);
    }

    static ExhibitionStationView hallSubsurface(ExhibitionStationBuildContext context) {
        ExhibitionAssets assets = context.assets();
        Scene scene = new Scene(ExhibitionSceneKit.camera(0f, 1.6f, 4.6f, -90f, -4f));
        Material[] samples = {assets.pbr(SKIN_PROPERTIES),
                subsurfaceMaterial(context, SKIN_PROPERTIES, SSS_FORWARD_THICK),
                subsurfaceMaterial(context, SKIN_PROPERTIES, SSS_FORWARD_THIN)};
        for (int index = 0; index < samples.length; index++) {
            float x = (index - 1) * 1.7f;
            scene.add(SceneObject.fixed(assets.subsurfaceSample(), samples[index],
                    at(x, 1.35f, -2.7f, 0.65f, 0.72f, 0.40f)));
            scene.addLight(SceneLight.point(new Vector3f(x, 1.35f, -3.60f),
                    new Vector3f(1f), 34f, 1.85f));
        }
        return new ExhibitionStationView(scene, ExhibitionSettings.base(context.renderSettings()),
                null, "Matched backlights and geometry: off / 30 mm / 8 mm");
    }

    // ---------------------------------------------------- #33 screen-space SSS

    private static ExhibitionStationView subsurfaceScreenSpace(
            ExhibitionStationBuildContext context, ExhibitionStation station,
            ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.6f, 4.6f, -90.0f, -4.0f));
        // High-contrast backdrop: bright card behind the skin, dark card beside it.
        scene.add(SceneObject.fixed(assets.cube, emissiveMaterial(assets, 2.5f),
                at(0.0f, 2.0f, -4.2f, 1.5f, 0.8f, 0.03f), false));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                at(-2.1f, 1.3f, -3.6f, 0.9f, 1.3f, 0.03f), false));
        Material skin = phase == ExhibitionPhase.EFFECT
                ? subsurfaceMaterial(context, SKIN_PLATE_PROPERTIES, SSS_SCREEN_SPACE)
                : assets.pbr(SKIN_PLATE_PROPERTIES);
        Material wax = phase == ExhibitionPhase.EFFECT
                ? subsurfaceMaterial(context, WAX_PLATE_PROPERTIES, SSS_SCREEN_SPACE_WAX)
                : assets.pbr(WAX_PLATE_PROPERTIES);
        // Two coplanar plates: the diffusion gather crosses their shared seam,
        // which is the visible screen-space contribution at this station.
        scene.add(SceneObject.fixed(assets.cube, skin,
                at(-0.5f, 1.5f, -2.6f, 0.5f, 0.6f, 0.03f)));
        scene.add(SceneObject.fixed(assets.cube, wax,
                at(0.5f, 1.5f, -2.6f, 0.5f, 0.6f, 0.03f)));
        scene.add(SceneObject.fixed(assets.sphere, skin,
                at(-1.9f, 1.4f, -2.4f, 0.6f, 0.6f, 0.6f)));
        PostProcessSettings postProcess = phase == ExhibitionPhase.EFFECT
                ? PostProcessSettings.defaults()
                        .withSubsurfaceScattering(SSS_SCREEN_SPACE)
                : PostProcessSettings.defaults();
        String note = phase == ExhibitionPhase.EFFECT
                ? "screen-space mask: 0.50 m radius (thickness-attenuated, 64 px cap), "
                        + "20 m gather bound, intensity 1.5, 16-tap Poisson diffusion"
                : "diffusion disabled (same skin/wax geometry)";
        return new ExhibitionStationView(scene,
                ExhibitionSettings.withPostProcess(context.renderSettings(), postProcess),
                driver(STUDIO_LIGHT_TRAVEL, false, null), note);
    }

    private static Material subsurfaceMaterial(ExhibitionStationBuildContext context,
                                               PbrMaterialProperties properties,
                                               SubsurfaceScatteringSettings settings) {
        return PbrMaterials.createWithSubsurface(context.assets().pbrShader(),
                properties, Map.of(), context.assets().fallbacks(), settings);
    }

    // --------------------------------------------------------- #38 particles

    private static ExhibitionStationView volumetricParticles(ExhibitionStationBuildContext context,
                                                             ExhibitionStation station,
                                                             ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.7f, 4.6f, -90.0f, -2.0f));
        ExhibitionSceneKit.addScaleProps(scene, assets);
        // Real depth inputs: a slab behind the puff and a pillar crossing it.
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                at(0.0f, 1.2f, -4.2f, 1.6f, 1.2f, 0.06f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                at(0.95f, 1.2f, -2.6f, 0.10f, 0.9f, 0.10f)));
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .volumetricFog(phase == ExhibitionPhase.EFFECT
                        ? PARTICLE_FOG : VolumetricFogSettings.disabled())
                .volumetricParticles(phase == ExhibitionPhase.EFFECT
                        ? PARTICLES : VolumetricParticleSettings.disabled())
                .build();
        String note = phase == ExhibitionPhase.EFFECT
                ? "ellipsoid body density 1.5, 32 steps / 0.10 m, fog composites first, "
                        + "depth-truncated behind the slab"
                : "fog and particle medium disabled";
        return new ExhibitionStationView(scene, settings,
                driver(PARTICLE_LIGHT_TRAVEL, false, null), note);
    }

    // ------------------------------------------------------------ #40 clouds

    private static ExhibitionStationView volumetricClouds(ExhibitionStationBuildContext context,
                                                          ExhibitionStation station,
                                                          ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = horizonStage(assets,
                ExhibitionSceneKit.camera(0.0f, 2.6f, 10.0f, -90.0f, 5.0f),
                CLOUD_LIGHT_TRAVEL);
        // Ground receivers so the coupled #10 cloud-shadow field is observable.
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                at(0.0f, 0.15f, -6.0f, 3.0f, 0.15f, 1.2f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                at(3.0f, 0.1f, -4.0f, 1.2f, 0.1f, 0.8f)));
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .directionalShadowMap(phase == ExhibitionPhase.EFFECT
                        ? cloudShadowMap(CLOUD_COVERAGE) : DirectionalShadowMap.defaults())
                .volumetricClouds(phase == ExhibitionPhase.EFFECT
                        ? CLOUDS : VolumetricCloudSettings.disabled())
                .build();
        String note = phase == ExhibitionPhase.EFFECT
                ? "layer 850-1475 m, 3D erosion and sky fill, 11.5 km distance fade; "
                        + "half resolution, 40/4 steps, history 0.85; shared #10 coverage"
                : "same atmosphere and sun; cloud layer and cloud shadows disabled";
        return new ExhibitionStationView(scene, settings, new ExhibitionStationDriver() {
            @Override
            public void onSelect(RenderPipeline pipeline, ExhibitionPhase ignored) {
                pipeline.applyAtmosphere(CLOUD_SKY);
                restoreDirectionalLight(pipeline.scene(), CLOUD_LIGHT_TRAVEL);
            }
        }, note);
    }

    private static DirectionalShadowMap cloudShadowMap(CloudShadowSettings coverage) {
        ShadowSettings defaults = ShadowSettings.directionalDefaults();
        return new DirectionalShadowMap(new ShadowSettings(defaults.resolution(),
                defaults.sceneRadius(), defaults.nearPlane(), defaults.farPlane(),
                defaults.bias(), defaults.contactShadows(), coverage, defaults.pcss()));
    }

    // ------------------------------------------------------------- #43 smoke

    private static ExhibitionStationView volumetricSmoke(ExhibitionStationBuildContext context,
                                                         ExhibitionStation station,
                                                         ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.6f, 4.6f, -90.0f, -3.0f));
        // Injector base marks the real source position under the bounded grid.
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                at(0.0f, 0.22f, -3.2f, 0.55f, 0.22f, 0.55f)));
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .smokeSimulation(phase == ExhibitionPhase.EFFECT
                        ? SMOKE_SIMULATION : SmokeSimulationSettings.disabled())
                .smokeRender(phase == ExhibitionPhase.EFFECT
                        ? SMOKE_RENDER : SmokeRenderSettings.disabled())
                .build();
        String note = phase == ExhibitionPhase.EFFECT
                ? "32^3 fixed-step grid, 2 steps/frame, buoyancy 0.8, 32 render steps; "
                        + "simulation and consumer both active"
                : "smoke simulation and volume consumer disabled";
        // Smoke simulation state is not part of resetTemporalHistory. A fresh
        // pipeline resets both the GPU volume and fixed-step timeline on replay.
        return new ExhibitionStationView(scene, settings,
                driver(STUDIO_LIGHT_TRAVEL, false, null), note, true);
    }

    // -------------------------------------------------------------- #46 hair

    private static ExhibitionStationView hairStrands(ExhibitionStationBuildContext context,
                                                     ExhibitionStation station,
                                                     ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets,
                ExhibitionSceneKit.camera(0.0f, 1.9f, 4.2f, -90.0f, -2.0f));
        // Real receiver board behind the lock; the key light casts the strand shadow onto it.
        scene.add(SceneObject.fixed(assets.cube, whiteMaterial(assets),
                at(0.0f, 1.9f, -2.9f, 1.15f, 1.15f, 0.06f)));
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .hairStrands(phase == ExhibitionPhase.EFFECT ? HAIR : HairStrandSettings.disabled())
                .build();
        String note = phase == ExhibitionPhase.EFFECT
                ? "96 strands x 10 segments, seed 0x5EED1A17, SORTED_ALPHA, "
                        + "Kajiya-Kay + 3x3 PCF shadow; MSAA is rejected (session AA NONE)"
                : "procedural strand lock and its shadow caster disabled";
        return new ExhibitionStationView(scene, settings,
                driver(STUDIO_LIGHT_TRAVEL, false, HAIR_MODEL), note);
    }

    // -------------------------------------------------------------- helpers

    /**
     * Clears a stale #29 atmosphere before a station publishes its own request
     * and restores the station's authored light-travel direction (the pipeline
     * sun-synchronisation replaces it while an atmosphere is active). Optionally
     * places the procedural hair lock; a repeat placement is a no-op.
     */
    private static ExhibitionStationDriver driver(Vector3f lightTravel, boolean atmosphere,
                                                  Matrix4f hairModel) {
        Vector3f travel = new Vector3f(lightTravel);
        Matrix4f placement = hairModel == null ? null : new Matrix4f(hairModel);
        return new ExhibitionStationDriver() {
            @Override
            public void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
                pipeline.applyAtmosphere(atmosphere && phase == ExhibitionPhase.EFFECT
                        ? ATMOSPHERE : null);
                restoreDirectionalLight(pipeline.scene(), travel);
                if (placement != null) {
                    pipeline.hairStrandModel(placement);
                }
            }
        };
    }

    private static void restoreDirectionalLight(Scene scene, Vector3f lightTravel) {
        List<SceneLight> lights = scene.lights();
        for (int index = 0; index < lights.size(); index++) {
            SceneLight light = lights.get(index);
            if (light.type() != LightType.DIRECTIONAL || !light.castShadows()) {
                continue;
            }
            SceneLight replacement = SceneLight.shadowedDirectional(new Vector3f(lightTravel),
                    light.color(), light.intensity());
            if (!replacement.equals(light)) {
                scene.setLight(index, replacement);
            }
            return;
        }
    }

    private static Matrix4f at(float x, float y, float z, float sx, float sy, float sz) {
        return new Matrix4f().identity().translate(x, y, z).scale(sx, sy, sz);
    }

    private static Material whiteMaterial(ExhibitionAssets assets) {
        return assets.pbr(new PbrMaterialProperties(new Vector4f(0.92f, 0.92f, 0.94f, 1.0f),
                0.0f, 0.35f, 1.0f, 1.0f, new Vector3f(0.02f), Map.of()));
    }

    private static Material emissiveMaterial(ExhibitionAssets assets, float emission) {
        return assets.pbr(new PbrMaterialProperties(new Vector4f(0.92f, 0.92f, 0.94f, 1.0f),
                0.0f, 0.55f, 1.0f, 1.0f, new Vector3f(emission), Map.of()));
    }
}
