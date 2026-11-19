package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSceneInput;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Map;

/**
 * Wing H - high-cost research stations.
 *
 * <p>#37 stochastic transparency, #39 SSGI, #44 DDGI and #45 voxel cone
 * tracing.  Each station builds the real input its production consumer needs:
 * a layered transparent collection declared through the driver hook for #37,
 * a coloured interior with adjacent corners for #39, a probe test room with an
 * occluding wall for #44 and a voxelizable room with a one-voxel thin wall for
 * #45.  The baseline phase is always the exact no-op snapshot over the very same
 * scene; nothing is faked with coloured overlays.</p>
 */
final class ExhibitionWingHStations {
    /** Conservative #37 preset: four samples, unbiased HASH jitter, bounded history. */
    private static final StochasticTransparencySettings STOCHASTIC_PRESET =
            StochasticTransparencySettings.builder()
                    .enabled(true)
                    .samplesPerFrame(4)
                    .seed(StochasticTransparencySettings.DEFAULT_SEED)
                    .jitterMode(StochasticTransparencySettings.JitterMode.HASH)
                    .alphaThreshold(0.0f)
                    .coverageGamma(1.0f)
                    .temporalAccumulation(true)
                    .historyWeight(1.0f)
                    .maximumHistoryLength(16)
                    .neighborhoodClampRadius(2)
                    .reactiveThreshold(1.0f)
                    .velocityRejectionPixels(32.0f)
                    .depthRejectionThreshold(0.02f)
                    .maximumSurfaces(64)
                    .build();

    /** Conservative #44 preset: an 18-probe grid enclosing the station room. */
    private static final DdgiSettings DDGI_PRESET = DdgiSettings.builder()
            .enabled(true)
            .intensity(1.35f)
            .surfaceAlbedo(0.66f)
            .skyRadiance(0.14f, 0.12f, 0.09f)
            .gridOrigin(-3.8f, -0.05f, -4.9f)
            .spacingMetres(1.45f)
            .probes(3, 2, 3)
            .updateBudget(2)
            .hysteresis(0.45f)
            .maximumRayDistance(8.0f)
            .raySteps(16)
            .depthFaceResolution(32)
            .borderFadeMetres(0.5f)
            .maximumIrradiance(32.0f)
            .build();

    private ExhibitionWingHStations() {
    }

    /** Ordinary alpha geometry for the panels while their stochastic pass is inactive. */
    static void addGalleryPanels(Scene scene, ExhibitionAssets assets, Matrix4f placement) {
        for (OitSurface panel : layeredPanels()) {
            Material material = PbrMaterials.createWithBindings(assets.pbrShader(),
                    new PbrMaterialProperties(new Vector4f(0.03f, 0.03f, 0.03f, panel.alpha()),
                            0f, 0.85f, 1f, 1f, panel.color(), Map.of()),
                    Map.of(), assets.fallbacks(), CullMode.NONE, false, false, 0f, BlendMode.ALPHA);
            Vector3f right = panel.halfRight().mul(2f);
            Vector3f up = panel.halfUp().mul(2f);
            Vector3f normal = right.cross(up, new Vector3f()).normalize();
            Vector3f center = panel.center();
            // The shared textured quad spans [-0.5, 0.5] in each axis.
            Matrix4f basis = new Matrix4f().set(
                    right.x, right.y, right.z, 0f, up.x, up.y, up.z, 0f,
                    normal.x, normal.y, normal.z, 0f, center.x, center.y, center.z, 1f);
            scene.add(SceneObject.fixed(assets.quad, material, placement.mul(basis, new Matrix4f()), false));
        }
    }

    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 37 -> ExhibitionWingHStations::stochasticTransparency;
            case 39 -> ExhibitionWingHStations::ssgi;
            case 44 -> ExhibitionWingHStations::ddgi;
            case 45 -> ExhibitionWingHStations::voxelConeTracing;
            default -> null;
        };
    }

    // ---- #37 Stochastic Transparency -------------------------------------

    private static ExhibitionStationView stochasticTransparency(
            ExhibitionStationBuildContext context, ExhibitionStation station,
            ExhibitionPhase phase) {
        Scene scene = layeredTransparencyStage(context.assets());
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .stochasticTransparency(phase == ExhibitionPhase.EFFECT
                        ? STOCHASTIC_PRESET : StochasticTransparencySettings.disabled())
                .build();
        return view(scene, settings, new StochasticSurfaceDriver(layeredPanels()),
                phase == ExhibitionPhase.EFFECT
                        ? "4 samples / HASH / 16-frame history / 5 surfaces"
                        : "");
    }

    /**
     * Multi-layer transparent sheets: overlapping coloured cards plus a tilted
     * card so the estimator faces layers at different depths, not one plane.
     */
    private static List<OitSurface> layeredPanels() {
        float tilt = 0.4f;
        float cos = (float) Math.cos(tilt);
        float sin = (float) Math.sin(tilt);
        return List.of(
                OitSurface.of(new Vector3f(0.0f, 1.55f, -2.6f),
                        new Vector3f(1.7f, 0.0f, 0.0f), new Vector3f(0.0f, 1.05f, 0.0f),
                        new Vector3f(0.95f, 0.32f, 0.18f), 0.35f),
                OitSurface.of(new Vector3f(-0.95f, 1.35f, -1.9f),
                        new Vector3f(1.05f, 0.0f, 0.0f), new Vector3f(0.0f, 0.75f, 0.0f),
                        new Vector3f(0.16f, 0.55f, 0.95f), 0.30f),
                OitSurface.of(new Vector3f(0.95f, 1.5f, -3.2f),
                        new Vector3f(1.25f, 0.0f, 0.0f), new Vector3f(0.0f, 0.85f, 0.0f),
                        new Vector3f(0.35f, 0.85f, 0.35f), 0.30f),
                OitSurface.of(new Vector3f(0.2f, 2.2f, -2.2f),
                        new Vector3f(0.75f * cos, 0.75f * sin, 0.0f),
                        new Vector3f(-0.6f * sin, 0.6f * cos, 0.0f),
                        new Vector3f(0.9f, 0.85f, 0.8f), 0.45f),
                OitSurface.of(new Vector3f(-0.3f, 0.95f, -1.45f),
                        new Vector3f(0.85f, 0.0f, 0.0f), new Vector3f(0.0f, 0.5f, 0.0f),
                        new Vector3f(0.55f, 0.15f, 0.75f), 0.25f));
    }

    /** High-contrast backing board plus scale props behind the transparent sheets. */
    private static Scene layeredTransparencyStage(ExhibitionAssets assets) {
        Scene scene = ExhibitionSceneKit.studio(assets, transparencyCamera());
        Material white = material(assets, 0.90f, 0.90f, 0.92f, 0.42f);
        for (int index = 0; index < 6; index++) {
            Material panel = index % 2 == 0 ? white : assets.darkMaterial;
            addBox(scene, assets, panel, -2.85f + index * 1.14f, 1.6f, -3.9f,
                    0.54f, 1.25f, 0.04f);
        }
        ExhibitionSceneKit.addScaleProps(scene, assets);
        return scene;
    }

    private static Camera transparencyCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.6f, 3.9f, -90.0f, -5.0f);
    }

    // ---- #39 SSGI ---------------------------------------------------------

    private static final SsgiSettings EXHIBITION_SSGI = SsgiSettings.builder()
            .enabled(true)
            .halfResolution(true)
            .sampleCount(8)
            .stepCount(8)
            .maxDistanceMetres(2.8f)
            .thicknessMetres(0.37f)
            .maximumOffscreenFraction(0.0f)
            .strength(1.0f)
            .build();

    private static ExhibitionStationView ssgi(ExhibitionStationBuildContext context,
                                              ExhibitionStation station,
                                              ExhibitionPhase phase) {
        Scene scene = colourBleedRoom(context.assets());
        RenderFeatureSettings settings = ExhibitionSettings.withPostProcess(
                context.renderSettings(),
                phase == ExhibitionPhase.EFFECT
                        ? PostProcessSettings.defaults().withSsgi(EXHIBITION_SSGI)
                        : PostProcessSettings.defaults());
        return view(scene, settings, null,
                phase == ExhibitionPhase.EFFECT
                        ? "half-resolution tracing / 8 rays × 8 steps / 2.8 m / guided resolve"
                        : "no screen-space indirect diffuse");
    }

    /** Two open corners, each with an emitting fin and angled neutral receivers. */
    private static Scene colourBleedRoom(ExhibitionAssets assets) {
        Scene scene = new Scene(interiorCamera());
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.20f, -1.0f, -0.45f),
                new Vector3f(1.0f), 0.38f));
        Material frame = material(assets, 0.045f, 0.060f, 0.085f, 0.88f);
        Material ceramic = material(assets, 0.68f, 0.68f, 0.68f, 0.84f);
        Material amber = emissive(assets, 1.0f, 0.11f, 0.012f, 5.6f);
        Material blue = emissive(assets, 0.018f, 0.25f, 1.0f, 5.6f);
        addBox(scene, assets, frame, 0f, 0.12f, -1.55f, 3.45f, 0.12f, 2.9f);
        for (int side : new int[]{-1, 1}) {
            addBox(scene, assets, ceramic, side * 1.67f, 0.30f, -1.65f, 1.55f, 0.06f, 2.45f);
            addBox(scene, assets, frame, side * 3.22f, 1.47f, -1.95f, 0.13f, 1.23f, 1.68f);
            // Inward face projects its colour to the ceramic plate and floor.
            addBox(scene, assets, side < 0 ? amber : blue, side * 3.06f, 1.53f, -1.95f,
                    0.06f, 0.94f, 1.39f);
            scene.add(SceneObject.fixed(assets.cube, ceramic, new Matrix4f()
                    .translation(side * 1.70f, 1.42f, -3.30f).rotateY(side * 0.33f)
                    .scale(1.18f, 1.06f, 0.10f)));
            addBox(scene, assets, frame, side * 1.76f, 0.50f, -1.96f, 0.60f, 0.14f, 0.58f);
            scene.add(SceneObject.fixed(assets.sphere, ceramic, new Matrix4f()
                    .translation(side * 1.76f, 1.09f, -1.96f).scale(0.50f)));
        }
        return scene;
    }

    // ---- #44 DDGI ---------------------------------------------------------

    private static ExhibitionStationView ddgi(ExhibitionStationBuildContext context,
                                              ExhibitionStation station,
                                              ExhibitionPhase phase) {
        Scene scene = probeTestRoom(context.assets());
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .ddgi(phase == ExhibitionPhase.EFFECT ? DDGI_PRESET : DdgiSettings.disabled())
                .build();
        return view(scene, settings, null,
                phase == ExhibitionPhase.EFFECT
                        ? "18 probe markers / 1.45 m spacing / two probe updates per frame"
                        : "");
    }

    /** Matched receivers inside and outside the world-space DDGI grid. */
    private static Scene probeTestRoom(ExhibitionAssets assets) {
        Scene scene = new Scene(interiorCamera());
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(0f, -0.78f, 0.62f),
                new Vector3f(1f, 0.79f, 0.51f), 2.6f));
        Material floor = material(assets, 0.45f, 0.45f, 0.46f, 0.85f);
        Material frame = material(assets, 0.065f, 0.084f, 0.11f, 0.82f);
        Material receiver = material(assets, 0.70f, 0.70f, 0.70f, 0.86f);
        Material marker = assets.alphaPbr(new PbrMaterialProperties(new Vector4f(0f, 0f, 0f, 0.76f),
                0f, 0.85f, 1f, 1f, new Vector3f(0.07f, 0.88f, 0.76f), Map.of()));
        addBox(scene, assets, frame, 0f, 0.10f, -1.65f, 3.55f, 0.10f, 2.95f);
        for (int side : new int[]{-1, 1}) {
            float x = side * 1.72f;
            addBox(scene, assets, floor, x, 0.24f, -1.73f, 1.49f, 0.04f, 2.64f);
            addBox(scene, assets, frame, x, 0.40f, -2.52f, 1.09f, 0.12f, 0.46f);
            addBox(scene, assets, receiver, x, 1.28f, -2.54f, 0.83f, 0.77f, 0.10f);
            // Rear-facing key lights the ground and roof behind both receivers.
            addBox(scene, assets, frame, x, 2.48f, -2.94f, 1.26f, 0.13f, 1.08f);
            for (float postX : new float[]{x - 1.14f, x + 1.14f}) {
                addBox(scene, assets, frame, postX, 1.38f, -3.78f, 0.09f, 1.10f, 0.10f);
            }
        }
        // Exact configured probe centres. The transparent markers do not enter the depth atlas.
        float spacing = DDGI_PRESET.spacingMetres();
        for (int z = 0; z < DDGI_PRESET.probesZ(); z++) {
            for (int y = 0; y < DDGI_PRESET.probesY(); y++) {
                for (int x = 0; x < DDGI_PRESET.probesX(); x++) {
                    scene.add(SceneObject.fixed(assets.sphere, marker, new Matrix4f()
                            .translation(DDGI_PRESET.gridOriginX() + (x + 0.5f) * spacing,
                                    DDGI_PRESET.gridOriginY() + (y + 0.5f) * spacing,
                                    DDGI_PRESET.gridOriginZ() + (z + 0.5f) * spacing)
                            .scale(0.068f), false));
                }
            }
        }
        return scene;
    }

    // ---- #45 Voxel Cone Tracing -------------------------------------------

    private static final VctSettings VCT_PRESET = VctSettings
            .enabled(64, new Vector3f(0.0f, 4.91f, -1.6f), 4.8f)
            .withIntensities(0.42f, 0.04f)
            .withConeCounts(6, 1)
            .withTrace(34, 0.08f, 0.8f);

    private static ExhibitionStationView voxelConeTracing(
            ExhibitionStationBuildContext context, ExhibitionStation station,
            ExhibitionPhase phase) {
        Scene scene = new Scene(interiorCamera());
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(0f, -1f, 0.50f),
                new Vector3f(1f), 0.48f));
        VctSceneInput input = buildVoxelExhibit(scene, context.assets());
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .vct(phase == ExhibitionPhase.EFFECT ? VCT_PRESET : VctSettings.disabled())
                .build();
        return view(scene, settings, new VctSceneDriver(input),
                phase == ExhibitionPhase.EFFECT
                        ? "64³ / 15 cm cells / matched emitters / solid baffle on the right"
                        : "");
    }

    /** Registers the same boxes in the raster scene and in the triangle input. */
    private static VctSceneInput buildVoxelExhibit(Scene scene, ExhibitionAssets assets) {
        VctSceneInput.Builder input = VctSceneInput.builder(1L);
        Material base = material(assets, 0.05f, 0.065f, 0.085f, 0.88f);
        Material ceramic = material(assets, 0.58f, 0.58f, 0.58f, 0.9f);
        Material emitter = emissive(assets, 0.07f, 0.72f, 0.49f, 4.8f);
        Material ruler = material(assets, 0.24f, 0.39f, 0.38f, 0.82f);
        Vector3f black = new Vector3f();
        Vector3f emitted = new Vector3f(0.07f, 0.72f, 0.49f).mul(4.8f);
        voxelBox(scene, assets, input, base, black, 0f, 0.12f, -1.40f, 3.50f, 0.12f, 2.90f);
        for (int side : new int[]{-1, 1}) {
            voxelBox(scene, assets, input, ceramic, black,
                    side * 1.69f, 0.29f, -1.45f, 1.55f, 0.05f, 2.58f);
            voxelBox(scene, assets, input, ceramic, black,
                    side * 1.32f, 1.16f, -2.94f, 0.87f, 0.82f, 0.12f);
            voxelBox(scene, assets, input, emitter, emitted,
                    side * 2.22f, 0.98f, -1.01f, 0.46f, 0.64f, 0.40f);
        }
        // Across the right light path, tall enough to cover multiple cells.
        voxelBox(scene, assets, input, base, black, 1.95f, 1.10f, -1.85f,
                0.57f, 0.76f, 0.42f);
        float cell = VCT_PRESET.voxelSize();
        for (int step = -4; step <= 4; step++) {
            float height = step % 2 == 0 ? cell : cell * 0.68f;
            addBox(scene, assets, ruler, step * cell, 0.25f + height * 0.5f, 1.09f,
                    cell * 0.44f, height * 0.5f, cell * 0.44f);
        }
        return input.build();
    }

    private static void voxelBox(Scene scene, ExhibitionAssets assets, VctSceneInput.Builder input,
                                 Material material, Vector3f radiance, float x, float y, float z,
                                 float halfX, float halfY, float halfZ) {
        addBox(scene, assets, material, x, y, z, halfX, halfY, halfZ);
        input.addBox(new Vector3f(x - halfX, y - halfY, z - halfZ),
                new Vector3f(x + halfX, y + halfY, z + halfZ), radiance.x, radiance.y, radiance.z);
    }

    /** Stages the caller-owned transparent collection / triangle snapshot per phase. */
    private static final class StochasticSurfaceDriver implements ExhibitionStationDriver {
        private final List<OitSurface> surfaces;

        private StochasticSurfaceDriver(List<OitSurface> surfaces) {
            this.surfaces = List.copyOf(surfaces);
        }

        @Override
        public void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
            pipeline.stochasticTransparencySurfaces(
                    phase == ExhibitionPhase.EFFECT ? surfaces : List.of());
        }
    }

    private static final class VctSceneDriver implements ExhibitionStationDriver {
        private final VctSceneInput sceneInput;

        private VctSceneDriver(VctSceneInput sceneInput) {
            this.sceneInput = sceneInput;
        }

        @Override
        public void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
            if (phase == ExhibitionPhase.EFFECT) {
                pipeline.vctSurface(0.55f, new Vector3f(0.78f, 0.76f, 0.72f));
                pipeline.vctSceneInput(sceneInput);
            } else {
                pipeline.vctSceneInput(null);
            }
        }
    }

    // ---- shared helpers ---------------------------------------------------

    private static ExhibitionStationView view(Scene scene, RenderFeatureSettings settings,
                                              ExhibitionStationDriver driver, String note) {
        return new ExhibitionStationView(scene, settings, driver, note);
    }

    private static Camera interiorCamera() {
        return ExhibitionSceneKit.camera(0.0f, 3.4f, 5.3f, -90.0f, -14.0f);
    }

    /** PBR material with an explicit base colour; used for station-local surfaces. */
    private static Material material(ExhibitionAssets assets, float red, float green, float blue,
                                     float roughness) {
        return assets.pbr(new PbrMaterialProperties(new Vector4f(red, green, blue, 1.0f),
                0.0f, roughness, 1.0f, 1.0f, new Vector3f(), Map.of()));
    }

    /** Emissive PBR source used by the VCT station to seed voxel radiance. */
    private static Material emissive(ExhibitionAssets assets, float red, float green, float blue,
                                     float strength) {
        return assets.pbr(new PbrMaterialProperties(new Vector4f(red, green, blue, 1.0f),
                0.0f, 0.85f, 1.0f, 1.0f,
                new Vector3f(red, green, blue).mul(strength), Map.of()));
    }

    /**
     * Box transform for the shared unit cube mesh (its vertices span -1..1, so
     * the half extents are the world-space half sizes).
     */
    private static Matrix4f box(float x, float y, float z,
                                float halfX, float halfY, float halfZ) {
        return new Matrix4f().identity().translation(x, y, z).scale(halfX, halfY, halfZ);
    }

    private static void addBox(Scene scene, ExhibitionAssets assets, Material material,
                               float x, float y, float z,
                               float halfX, float halfY, float halfZ) {
        scene.add(SceneObject.fixed(assets.cube, material, box(x, y, z, halfX, halfY, halfZ)));
    }
}
