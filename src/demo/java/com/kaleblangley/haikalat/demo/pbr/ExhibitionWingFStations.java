package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingRate;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalDenoiseSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.vrs.VariableRateShadingSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleCollisionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSortingSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleSystem;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.GpuParticleVfx;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;
import java.util.Map;

/**
 * Wing F - particle scale and reconstruction stations.
 *
 * <p>#15 depth-based particle collision, #26 GPU particle sorting and #27 GPU
 * particle indirect rendering record real GPU particle owners through the
 * station's HDR VFX feed (same-frame single-sample scene depth included), #28
 * declares the documented world-space {@link OitSurface} set through the
 * pipeline's {@code weightedOitSurfaces(...)} API, and #34/#35/#41/#42 are
 * wired only through real production settings.</p>
 *
 * <p>Each particle phase owns one station-local {@link GpuParticleVfx}
 * consumer, because the shared {@code VfxRenderer} consumer permanently binds
 * to the first {@link GpuParticleSystem} it records and rejects a second owner;
 * a station-local consumer is GL-free and lets a station keep both a baseline
 * and an effect owner generation. The baseline/effect pair keeps identical
 * geometry, camera and emission; only the observed production path changes.</p>
 */
final class ExhibitionWingFStations {
    /** Fixed particle step recorded per feed call; a station owns this cadence. */
    private static final float PARTICLE_FIXED_STEP_SECONDS = 1.0f / 60.0f;
    /** Shared SSR preset: one ray per pixel, bounded 9 m / 32 step march. */
    private static final float SSR_DISTANCE_METRES = 9.0f;
    private static final int SSR_STEPS = 32;
    private static final float SSR_THICKNESS_METRES = 0.35f;

    private ExhibitionWingFStations() {
    }

    /**
     * @return the real builder for every wing F station #15 #26 #27 #28 #34 #35
     *         #41 #42 (8/8); {@code null} for non-wing-F ids
     */
    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 15 -> ExhibitionWingFStations::particleCollision;
            case 26 -> ExhibitionWingFStations::particleSorting;
            case 27 -> ExhibitionWingFStations::particleIndirect;
            case 28 -> ExhibitionWingFStations::weightedOit;
            case 34 -> ExhibitionWingFStations::temporalDenoise;
            // #35 temporal upscaling: the internal reduced-resolution chain is
            // reconstructed to the output extent; the session builds it as a
            // bare pipeline because the full-resolution HDR VFX/decal recorders
            // cannot compose with the reduced scene extent.
            case 35 -> ExhibitionWingFStations::temporalUpscaling;
            case 41 -> ExhibitionWingFStations::adaptiveSampling;
            case 42 -> ExhibitionWingFStations::variableRateShading;
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // GPU particle stations #15 / #26 / #27
    // ------------------------------------------------------------------

    /**
     * #27 GPU particle indirect rendering. The effect records one real
     * {@link GpuParticleSystem} update plus its GPU-owned indirect draw per
     * frame (emission 4 per fixed 1/60 s step, lifetime 2.0 s, capacity 512 ->
     * a stable active set below capacity). The baseline keeps the same scene
     * and the same owner alive but returns no VFX feed, so no particle command
     * is recorded at all: the documented empty-feed no-op.
     */
    private static ExhibitionStationView particleIndirect(ExhibitionStationBuildContext context,
                                                          ExhibitionStation station,
                                                          ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = particleIndirectStage(context.assets(), particleCamera());
        GpuParticleSettings particles = particleSettings(512,
                new Vector3f(-1.40f, 0.70f, -2.40f), new Vector3f(1.40f, 2.00f, -1.20f),
                2.0f, 0.25f, 0.05f, 0.35f, 0.070f,
                new Vector3f(0.25f, 0.65f, 1.00f), 1.30f);
        return particleStation(context, phase, scene, particles,
                GpuParticleSortingSettings.disabled(),
                GpuParticleCollisionSettings.disabled(),
                4, -1, GpuParticleVfx.SortingMode.NONE,
                "GPU indirect particles: 512 cap / 4 per 1/60 s step / 2.0 s life / "
                        + "additive indirect draw from the GPU active count",
                "baseline: identical scene and owner, empty feed (no particle record)");
    }

    /**
     * #26 GPU particle sorting. Both phases record the same particle set with
     * identical emission; the baseline owner has no sort generation (additive
     * indirect path) and the effect owner sorts VIEW_DEPTH / BACK_TO_FRONT /
     * STABLE, which switches the production draw to premultiplied alpha-over.
     */
    private static ExhibitionStationView particleSorting(ExhibitionStationBuildContext context,
                                                         ExhibitionStation station,
                                                         ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = particleSortingStage(context.assets(), particleCamera());
        GpuParticleSettings particles = particleSettings(512,
                new Vector3f(-1.60f, 0.70f, -3.40f), new Vector3f(1.60f, 2.10f, -1.00f),
                1.6f, 0.10f, 0.02f, 0.15f, 0.100f,
                new Vector3f(0.35f, 0.70f, 1.00f), 0.55f);
        GpuParticleSortingSettings sorting = effect
                ? GpuParticleSortingSettings.enabled(
                        GpuParticleSortingSettings.SortKey.VIEW_DEPTH,
                        GpuParticleSortingSettings.SortDirection.BACK_TO_FRONT,
                        512, GpuParticleSortingSettings.EqualKeyPolicy.STABLE)
                : GpuParticleSortingSettings.disabled();
        return particleStation(context, phase, scene, particles, sorting,
                GpuParticleCollisionSettings.disabled(),
                5, 5, effect ? GpuParticleVfx.SortingMode.GPU : GpuParticleVfx.SortingMode.NONE,
                "GPU sorting: VIEW_DEPTH / BACK_TO_FRONT / STABLE, 512 cap, "
                        + "premultiplied alpha-over sorted draw",
                "baseline: same particles, no sort generation (additive indirect draw)");
    }

    /**
     * #15 depth-based particle collision. Both phases spawn the identical
     * emission; the baseline owner has collision disabled (free flight) while
     * the effect owner samples the same-frame single-sample opaque depth and
     * bounces on the real step/floor geometry in the particle path.
     */
    private static ExhibitionStationView particleCollision(ExhibitionStationBuildContext context,
                                                           ExhibitionStation station,
                                                           ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = particleCollisionStage(context.assets(), particleCamera());
        GpuParticleSettings particles = particleSettings(384,
                new Vector3f(-1.50f, 1.30f, -2.80f), new Vector3f(1.50f, 2.00f, -1.60f),
                2.5f, 4.50f, 0.02f, 0.20f, 0.120f,
                new Vector3f(1.00f, 0.50f, 0.15f), 1.40f);
        GpuParticleCollisionSettings collision = effect
                ? GpuParticleCollisionSettings.enabled(
                        GpuParticleCollisionSettings.DepthSampling.TEXEL_CENTRE,
                        0.35f, 0.35f, 0.45f, 1, 0.0f)
                : GpuParticleCollisionSettings.disabled();
        return particleStation(context, phase, scene, particles,
                GpuParticleSortingSettings.disabled(), collision,
                2, 2, GpuParticleVfx.SortingMode.NONE,
                "collision on: same-frame opaque depth plane, thickness 0.35 m, "
                        + "restitution 0.35, friction 0.45; particles settle on the step/floor",
                "baseline: identical emission, collision disabled (free fall through the floor)");
    }

    /**
     * Creates the station-owned particle owner through the shared assets and
     * records it through a station-local {@link GpuParticleVfx} consumer.
     *
     * <p>A negative {@code baselineEmission} selects the empty-feed baseline
     * (owner alive, nothing recorded). Both phases keep identical geometry and
     * camera because callers build the same scene preset.</p>
     */
    private static ExhibitionStationView particleStation(ExhibitionStationBuildContext context,
                                                         ExhibitionPhase phase, Scene scene,
                                                         GpuParticleSettings particleSettings,
                                                         GpuParticleSortingSettings sorting,
                                                         GpuParticleCollisionSettings collision,
                                                         int effectEmission, int baselineEmission,
                                                         GpuParticleVfx.SortingMode sortingMode,
                                                         String effectNote, String baselineNote) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        GpuParticleSystem owner = context.assets().gpuParticles(particleSettings, sorting,
                collision);
        int emission = effect ? effectEmission : baselineEmission;
        ParticleFeed feed = emission < 0 ? null : new ParticleFeed(owner, particleSettings,
                new GpuParticleVfx.Settings(PARTICLE_FIXED_STEP_SECONDS, emission, sortingMode));
        return new ExhibitionStationView(scene,
                ExhibitionSettings.base(context.renderSettings()),
                new ParticleDriver(feed),
                effect ? effectNote : baselineNote);
    }

    private static GpuParticleSettings particleSettings(int capacity, Vector3f spawnMinimum,
                                                        Vector3f spawnMaximum,
                                                        float lifetimeSeconds, float gravity,
                                                        float minimumSpeed, float maximumSpeed,
                                                        float particleSize, Vector3f color,
                                                        float intensity) {
        return new GpuParticleSettings(capacity, spawnMinimum, spawnMaximum, lifetimeSeconds,
                gravity, minimumSpeed, maximumSpeed, particleSize, color, intensity);
    }

    private static Camera particleCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.85f, 4.70f, -90.0f, -12.0f);
    }

    /** Studio plus a dark contrast board and depth-tested receiver columns. */
    private static Scene particleIndirectStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.60f, -4.40f)
                        .scale(2.80f, 1.60f, 0.05f), false));
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(-2.10f, 0.90f, -3.00f)
                        .scale(0.25f, 0.90f, 0.25f)));
        scene.add(SceneObject.fixed(assets.cube, assets.accentMaterial,
                new Matrix4f().identity().translation(2.00f, 0.70f, -2.00f)
                        .scale(0.30f, 0.70f, 0.30f)));
        return scene;
    }

    /** Deep receiver stage so the translucent particle layers overlap in depth. */
    private static Scene particleSortingStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.70f, -4.50f)
                        .scale(3.00f, 1.70f, 0.05f), false));
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(-1.20f, 0.90f, -3.40f)
                        .scale(0.35f, 0.90f, 0.35f)));
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(1.20f, 0.90f, -2.20f)
                        .scale(0.35f, 0.90f, 0.35f)));
        return scene;
    }

    /** Step plus floor receivers in the particle path, with a wall behind. */
    private static Scene particleCollisionStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        // Step: its top face is the elevated collision receiver.
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(0.0f, 0.28f, -2.40f)
                        .scale(1.50f, 0.28f, 1.10f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.70f, -4.60f)
                        .scale(2.60f, 1.50f, 0.05f), false));
        scene.add(SceneObject.fixed(assets.cube, whiteMaterial(assets),
                new Matrix4f().identity().translation(1.90f, 0.50f, -1.50f)
                        .scale(0.18f, 0.50f, 0.18f)));
        return scene;
    }

    /**
     * Records the station-owned particle owner into the production HDR VFX
     * command buffer. The consumer is station-local and GL-free: the shared
     * {@code VfxRenderer} consumer would bind to the first owner for its whole
     * lifetime, while a station needs a baseline and an effect owner generation.
     */
    private static final class ParticleFeed implements ExhibitionVfxFeed {
        private final GpuParticleSystem system;
        private final GpuParticleSettings particleSettings;
        private final GpuParticleVfx consumer;
        private ParticleFeed(GpuParticleSystem system, GpuParticleSettings particleSettings,
                             GpuParticleVfx.Settings consumerSettings) {
            this.system = system;
            this.particleSettings = particleSettings;
            this.consumer = new GpuParticleVfx(consumerSettings);
        }

        private void replay() {
            consumer.replay();
        }

        @Override
        public void record(CommandBuffer commands, Matrix4f projection, Matrix4f view,
                           int sceneDepthTexture, int width, int height, int frameIndex,
                           float deltaSeconds) {
            consumer.record(commands, system, particleSettings, projection, view,
                    sceneDepthTexture, width, height,
                    GpuParticleVfx.Boundary.BEFORE_TRANSPARENT_COLLECTORS);
        }
    }

    /** Publishes one station particle feed and restarts its deterministic history on reset. */
    private static final class ParticleDriver implements ExhibitionStationDriver {
        private final ParticleFeed feed;

        private ParticleDriver(ParticleFeed feed) {
            this.feed = feed;
        }

        @Override
        public ExhibitionVfxFeed vfxFeed() {
            return feed;
        }

        @Override
        public void onReset(RenderPipeline pipeline, ExhibitionPhase phase) {
            if (feed != null) {
                feed.replay();
            }
        }
    }

    // ------------------------------------------------------------------
    // #28 weighted blended OIT
    // ------------------------------------------------------------------

    /**
     * Baseline = the same five layers drawn by the ordinary sorted alpha
     * queue; effect = the same world-space parallelogram set drawn by the
     * weighted blended OIT collector. Both phases keep identical opaque
     * receiver geometry. The OIT layers deliberately ignore opaque depth
     * occlusion (documented #28 limitation), while the sorted reference is
     * depth tested by the opaque pass.
     */
    private static ExhibitionStationView weightedOit(ExhibitionStationBuildContext context,
                                                     ExhibitionStation station,
                                                     ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = oitStage(context.assets(), stationCamera(), !effect);
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings());
        if (effect) {
            WeightedOitSettings oit = WeightedOitSettings.builder()
                    .enabled(true)
                    .depthWeighting(true)
                    .depthScale(10.0f)
                    .referenceDepth(8.0f)
                    .minimumWeight(0.01f)
                    .maximumWeight(300.0f)
                    .alphaThreshold(0.0f)
                    .maximumSurfaces(16)
                    .build();
            settings = settings.toBuilder().weightedOit(oit).build();
        }
        return new ExhibitionStationView(scene, settings,
                new OitSurfaceDriver(effect ? OIT_LAYERS : List.of()),
                effect
                        ? "weighted blended OIT: 5 HDR layers / depth weight 10 @ 8 m ref / "
                                + "transmittance = prod(1-a)"
                        : "sorted alpha reference: same 5 layers in the main transparent queue");
    }

    /** Bounded world-space layer set shared by the reference and OIT phases. */
    private static final List<OitLayer> OIT_LAYERS = List.of(
            new OitLayer(-1.10f, 1.50f, -1.90f, 0.80f, 0.50f, 2.40f, 0.35f, 0.20f, 0.55f),
            new OitLayer(0.90f, 1.35f, -2.30f, 0.85f, 0.55f, 0.25f, 1.90f, 0.45f, 0.45f),
            new OitLayer(-0.50f, 1.75f, -2.80f, 0.75f, 0.45f, 0.30f, 0.55f, 2.60f, 0.50f),
            new OitLayer(0.30f, 1.20f, -3.30f, 0.95f, 0.50f, 2.20f, 1.70f, 0.25f, 0.40f),
            new OitLayer(-0.80f, 1.30f, -3.90f, 0.70f, 0.40f, 0.90f, 0.35f, 2.20f, 0.60f));

    private record OitLayer(float x, float y, float z, float halfWidth, float halfHeight,
                            float red, float green, float blue, float alpha) {
        OitSurface toSurface() {
            return OitSurface.of(new Vector3f(x, y, z),
                    new Vector3f(halfWidth, 0.0f, 0.0f),
                    new Vector3f(0.0f, halfHeight, 0.0f),
                    new Vector3f(red, green, blue), alpha);
        }
    }

    /** Publishes the selected OIT set at a frame boundary (empty clears it). */
    private static final class OitSurfaceDriver implements ExhibitionStationDriver {
        private final List<OitSurface> surfaces;

        private OitSurfaceDriver(List<OitLayer> layers) {
            List<OitSurface> mapped = new java.util.ArrayList<>(layers.size());
            for (OitLayer layer : layers) {
                mapped.add(layer.toSurface());
            }
            this.surfaces = List.copyOf(mapped);
        }

        @Override
        public void onSelect(RenderPipeline pipeline, ExhibitionPhase phase) {
            pipeline.weightedOitSurfaces(surfaces);
        }
    }

    private static Scene oitStage(ExhibitionAssets assets, Camera camera, boolean sortedReference) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        // Opaque receiver geometry: the layers interleave with this column.
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(0.40f, 1.15f, -2.60f)
                        .scale(0.22f, 1.15f, 0.22f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.35f, -4.60f)
                        .scale(2.4f, 1.35f, 0.06f), false));
        if (!sortedReference) {
            return scene;
        }
        // Sorted reference: the identical world-space quads through the PBR
        // alpha queue (emissive approximates the OIT linear HDR layer colour).
        for (OitLayer layer : OIT_LAYERS) {
            Material material = PbrMaterials.createWithBindings(assets.pbrShader(),
                    new PbrMaterialProperties(
                            new Vector4f(0.04f, 0.04f, 0.04f, layer.alpha()),
                            0.0f, 0.85f, 1.0f, 1.0f,
                            new Vector3f(layer.red(), layer.green(), layer.blue()), Map.of()),
                    Map.of(), assets.fallbacks(), CullMode.NONE, false, false, 0.0f,
                    BlendMode.ALPHA);
            scene.add(SceneObject.fixed(assets.quad, material,
                    new Matrix4f().identity().translation(layer.x(), layer.y(), layer.z())
                            .scale(layer.halfWidth(), layer.halfHeight(), 1.0f), false));
        }
        return scene;
    }

    // ------------------------------------------------------------------
    // #34 temporal denoising (SSR signal)
    // ------------------------------------------------------------------

    /**
     * Meaningful pair: the baseline keeps the same SSR request with the
     * denoiser disabled, so the A/B isolates exactly the temporal resolve.
     * The subject slowly sweeps across the mirror stage to exercise
     * reprojection and disocclusion.
     */
    private static ExhibitionStationView temporalDenoise(ExhibitionStationBuildContext context,
                                                         ExhibitionStation station,
                                                         ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = ssrStage(context.assets(), stationCamera(), true);
        SsrSettings ssr = ssr();
        TemporalDenoiseSettings denoise = effect
                ? TemporalDenoiseSettings.builder()
                        .enabled(true)
                        .historyLengthFrames(12)
                        .neighborhoodClampRadius(2)
                        .depthRejectionThreshold(0.02f)
                        .normalRejectionThreshold(0.85f)
                        .reactiveThreshold(0.40f)
                        .maxReprojectionPixels(64.0f)
                        .build()
                : TemporalDenoiseSettings.disabled();
        PostProcessSettings effects = PostProcessSettings.builder()
                .ssr(ssr)
                .temporalDenoise(denoise)
                .build();
        return new ExhibitionStationView(scene,
                ExhibitionSettings.withPostProcess(context.renderSettings(), effects),
                null,
                effect
                        ? "SSR signal + temporal denoise: history 12 / clamp r2 / reactive 0.4 "
                                + "/ reprojection 64 px"
                        : "baseline: identical SSR signal, temporal denoise disabled");
    }

    // ------------------------------------------------------------------
    // #35 temporal upscaling (reduced-resolution scene raster)
    // ------------------------------------------------------------------

    /**
     * The reduced scene raster ({@code renderScale 0.5}) is reconstructed to
     * the output extent by the pipeline-owned internal temporal upscaler.  A
     * caller {@code TemporalUpscalingSource} cannot be registered from a
     * station, and an active {@link TemporalUpscalingSettings} request without
     * that source fails closed during generation assembly, so the station only
     * changes the render scale and every other full-resolution composition
     * stays disabled as the render-scale policy requires.
     */
    private static ExhibitionStationView temporalUpscaling(ExhibitionStationBuildContext context,
                                                           ExhibitionStation station,
                                                           ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = fidelityStage(context.assets(), stationCamera());
        RenderSettings render = effect
                ? ExhibitionSettings.withRenderScale(context.renderSettings(), 0.5f)
                : ExhibitionSettings.withRenderScale(context.renderSettings(), 1.0f);
        RenderFeatureSettings settings = ExhibitionSettings.base(render);
        // The reduced-resolution scene extent is part of the pipeline build, so
        // both phases get a freshly built pipeline with their own render scale.
        return new ExhibitionStationView(scene, settings, null,
                effect
                        ? "renderScale 0.5 internal temporal reconstruction (R = 0.5 x O)"
                        : "baseline: renderScale 1.0, native-resolution chain",
                true);
    }

    private static Scene fidelityStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        Material white = whiteMaterial(assets);
        // High-frequency checkerboard: 8 x 4 tiles on the back wall.
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 8; column++) {
                Material material = ((row + column) & 1) == 0 ? white : assets.darkMaterial;
                scene.add(SceneObject.fixed(assets.cube, material,
                        new Matrix4f().identity()
                                .translation(-2.10f + column * 0.60f, 1.00f + row * 0.60f, -3.30f)
                                .scale(0.28f, 0.28f, 0.03f), false));
            }
        }
        // Sub-pixel thin bars with a width ramp.
        for (int index = 0; index < 7; index++) {
            float width = 0.012f + index * 0.006f;
            scene.add(SceneObject.fixed(assets.cube, white,
                    new Matrix4f().identity()
                            .translation(-1.80f + index * 0.60f, 1.50f, -2.30f)
                            .rotateZ(0.30f)
                            .scale(width, 0.90f, 0.02f), false));
        }
        // Output ruler: 10/20 cm ticks.
        for (int index = 0; index < 9; index++) {
            Material material = (index & 1) == 0 ? white : assets.neutralMaterial;
            scene.add(SceneObject.fixed(assets.cube, material,
                    new Matrix4f().identity()
                            .translation(-2.00f + index * 0.50f, 0.15f, -1.20f)
                            .scale(0.04f, 0.12f + index * 0.02f, 0.02f), false));
        }
        // Smooth bright slab: magnification and history stability reference.
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(0.0f, 2.40f, -3.40f)
                        .scale(2.40f, 0.35f, 0.03f), false));
        return scene;
    }

    // ------------------------------------------------------------------
    // #41 adaptive SSR sampling
    // ------------------------------------------------------------------

    /**
     * Same SSR scene and same fixed SSR budget in both phases; only the
     * adaptive step-budget policy changes, so the A/B isolates the sample
     * allocation. The adaptive ceiling equals the SSR hard budget (32), which
     * the production validation requires.
     */
    private static ExhibitionStationView adaptiveSampling(ExhibitionStationBuildContext context,
                                                          ExhibitionStation station,
                                                          ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = ssrStage(context.assets(), stationCamera(), false);
        SsrSettings ssr = ssr();
        AdaptiveSamplingSettings adaptive = effect
                ? AdaptiveSamplingSettings.builder()
                        .enabled(true)
                        .minimumSteps(8)
                        .maximumSteps(32)
                        .tileSizePixels(8)
                        .errorThreshold(0.10f)
                        .screenStepScalePixels(4.0f)
                        .depthJumpScale(1.0f)
                        .build()
                : AdaptiveSamplingSettings.disabled();
        PostProcessSettings effects = PostProcessSettings.builder()
                .ssr(ssr)
                .adaptiveSampling(adaptive)
                .build();
        return new ExhibitionStationView(scene,
                ExhibitionSettings.withPostProcess(context.renderSettings(), effects), null,
                effect
                        ? "adaptive SSR budget: floor 8 / ceiling 32 steps, 8 px tiles, "
                                + "error threshold 0.1"
                        : "baseline: fixed 32-step SSR march, adaptive sampling disabled");
    }

    // ------------------------------------------------------------------
    // #42 variable rate shading (screen-space SSS consumer)
    // ------------------------------------------------------------------

    /**
     * The declared production consumer is the screen-space subsurface
     * scattering full-screen pass. Both phases keep the same SSS request and
     * the same SSS-mask material; only the {@link VariableRateShadingSettings}
     * request changes. On hardware without {@code GL_NV_shading_rate_image} the
     * request is reported as a fallback and the consumer draws at full rate.
     */
    private static ExhibitionStationView variableRateShading(
            ExhibitionStationBuildContext context, ExhibitionStation station,
            ExhibitionPhase phase) {
        boolean effect = phase == ExhibitionPhase.EFFECT;
        Scene scene = vrsStage(context.assets(), stationCamera());
        SubsurfaceScatteringSettings subsurface = SubsurfaceScatteringSettings.builder()
                .enabled(true)
                .scatterColor(0.85f, 0.36f, 0.24f)
                .intensity(1.10f)
                .profile(SubsurfaceScatteringSettings.Profile.SKIN)
                .profileScaleMetres(0.025f)
                .thicknessMetres(0.06f)
                .diffusionRadiusMetres(0.20f)
                .screenSpaceEnabled(true)
                .maxDistanceMetres(12.0f)
                .build();
        VariableRateShadingSettings vrs = effect
                ? VariableRateShadingSettings.builder()
                        .enabled(true)
                        .fineRate(VariableRateShadingRate.ONE_BY_ONE)
                        .coarseRate(VariableRateShadingRate.TWO_BY_TWO)
                        .fineRegionFraction(0.50f)
                        .build()
                : VariableRateShadingSettings.disabled();
        PostProcessSettings effects = PostProcessSettings.builder()
                .subsurfaceScattering(subsurface)
                .variableRateShading(vrs)
                .build();
        return new ExhibitionStationView(scene,
                ExhibitionSettings.withPostProcess(context.renderSettings(), effects), null,
                effect
                        ? "VRS: centre 50% at 1x1, periphery 2x2; consumer = screen-space SSS "
                                + "(needs GL_NV_shading_rate_image, else fallback full rate)"
                        : "baseline: identical SSS consumer at full rate, VRS disabled");
    }

    private static Scene vrsStage(ExhibitionAssets assets, Camera camera) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        Material white = whiteMaterial(assets);
        // SSS-mask producer: the only production material path that writes the
        // shared SUBSURFACE channel (created through the public PBR factory
        // because ExhibitionAssets has no subsurface factory yet).
        Material subsurface = PbrMaterials.createWithSubsurface(assets.pbrShader(),
                new PbrMaterialProperties(new Vector4f(0.92f, 0.42f, 0.30f, 1.0f),
                        0.0f, 0.55f, 1.0f, 1.0f, new Vector3f(0.02f), Map.of()),
                Map.of(), assets.fallbacks(),
                SubsurfaceScatteringSettings.builder()
                        .enabled(true)
                        .scatterColor(0.86f, 0.32f, 0.22f)
                        .intensity(1.10f)
                        .profile(SubsurfaceScatteringSettings.Profile.SKIN)
                        .profileScaleMetres(0.025f)
                        .thicknessMetres(0.06f)
                        .diffusionRadiusMetres(0.20f)
                        .screenSpaceEnabled(true)
                        .maxDistanceMetres(12.0f)
                        .build());
        scene.add(SceneObject.fixed(assets.sphere, subsurface,
                new Matrix4f().identity().translation(-1.20f, 1.10f, -2.20f).scale(0.75f)));
        scene.add(SceneObject.fixed(assets.quad, subsurface,
                new Matrix4f().identity().translation(1.30f, 1.55f, -2.60f)
                        .scale(0.90f, 1.00f, 1.0f), false));
        // High-contrast board: the documented limitation is that VRS only
        // covers the SSS full-screen pass, not geometric thin lines/text.
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(0.0f, 1.60f, -3.80f)
                        .scale(2.60f, 1.20f, 0.04f), false));
        for (int index = 0; index < 9; index++) {
            scene.add(SceneObject.fixed(assets.cube, white,
                    new Matrix4f().identity()
                            .translation(-2.00f + index * 0.50f, 1.60f, -3.70f)
                            .scale(0.020f, 1.00f, 0.02f), false));
        }
        // Smooth bright/dark patches for the coarse-rate comparison.
        scene.add(SceneObject.fixed(assets.cube, white,
                new Matrix4f().identity().translation(-2.40f, 0.55f, -2.60f)
                        .scale(0.65f, 0.55f, 0.20f)));
        scene.add(SceneObject.fixed(assets.cube, assets.neutralMaterial,
                new Matrix4f().identity().translation(2.40f, 0.55f, -2.60f)
                        .scale(0.65f, 0.55f, 0.20f)));
        return scene;
    }

    // ------------------------------------------------------------------
    // shared scenes / presets
    // ------------------------------------------------------------------

    private static SsrSettings ssr() {
        return SsrSettings.builder()
                .enabled(true)
                .maxDistanceMetres(SSR_DISTANCE_METRES)
                .maxSteps(SSR_STEPS)
                .thicknessMetres(SSR_THICKNESS_METRES)
                .roughnessCutoff(0.40f)
                .minimumConfidence(0.15f)
                .strength(0.90f)
                .edgeFadePixels(24.0f)
                .build();
    }

    private static Camera stationCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.65f, 4.60f, -90.0f, -8.0f);
    }

    /**
     * Reflective stage: mirror wall, mirror floor patch, a roughness ladder and
     * coloured subjects reflected by those surfaces. The moving variant adds a
     * slow horizontal sweep for the temporal-denoise reprojection case.
     */
    private static Scene ssrStage(ExhibitionAssets assets, Camera camera, boolean movingSubject) {
        Scene scene = ExhibitionSceneKit.studio(assets, camera);
        ExhibitionSceneKit.addScaleProps(scene, assets);
        Material mirror = mirrorMaterial(assets, 0.06f);
        // Mirror wall behind the subject.
        scene.add(SceneObject.fixed(assets.quad, mirror,
                new Matrix4f().identity().translation(0.0f, 1.90f, -4.60f)
                        .scale(5.60f, 2.20f, 1.0f), false));
        // Mirror floor patch in front of the subject.
        scene.add(SceneObject.fixed(assets.quad, mirror,
                new Matrix4f().identity().translation(0.0f, 0.02f, -1.60f)
                        .rotateX((float) (-Math.PI / 2.0))
                        .scale(3.60f, 2.60f, 1.0f), false));
        // Roughness ladder on the mirror wall: 0.08 / 0.30 / 0.55.
        for (int index = 0; index < 3; index++) {
            scene.add(SceneObject.fixed(assets.cube, mirrorMaterial(assets, 0.08f + index * 0.23f),
                    new Matrix4f().identity()
                            .translation(-2.60f + index * 2.60f, 1.65f, -4.45f)
                            .scale(0.22f, 0.55f, 0.05f), false));
        }
        // Coloured subjects reflected by the mirror surfaces.
        scene.add(SceneObject.fixed(assets.sphere, assets.accentMaterial,
                new Matrix4f().identity().translation(-1.50f, 0.85f, -2.60f).scale(0.50f)));
        scene.add(SceneObject.fixed(assets.cube, satinMaterial(assets,
                        new Vector4f(0.85f, 0.35f, 0.20f, 1.0f), 0.28f, 0.10f),
                new Matrix4f().identity().translation(1.55f, 0.75f, -2.90f)
                        .scale(0.55f, 0.55f, 0.55f)));
        SceneObject sweep = movingSubject
                ? new SceneObject(assets.sphere, satinMaterial(assets,
                        new Vector4f(0.95f, 0.80f, 0.25f, 1.0f), 0.18f, 0.20f),
                        (out, frame) -> {
                            float t = (frame % 120) / 119.0f;
                            out.identity().translate(-2.10f + 4.20f * t, 1.15f, -1.50f)
                                    .scale(0.42f);
                        })
                : SceneObject.fixed(assets.sphere, satinMaterial(assets,
                        new Vector4f(0.95f, 0.80f, 0.25f, 1.0f), 0.18f, 0.20f),
                        new Matrix4f().identity().translation(0.25f, 1.15f, -1.60f)
                                .scale(0.42f));
        scene.add(sweep);
        // Opaque foreground occluder: screen-edge fallback and disocclusion.
        scene.add(SceneObject.fixed(assets.cube, whiteMaterial(assets),
                new Matrix4f().identity().translation(-2.40f, 1.10f, -1.10f)
                        .scale(0.30f, 1.10f, 0.30f)));
        return scene;
    }

    private static Material mirrorMaterial(ExhibitionAssets assets, float roughness) {
        return satinMaterial(assets, new Vector4f(0.52f, 0.54f, 0.60f, 1.0f), roughness, 0.95f);
    }

    private static Material satinMaterial(ExhibitionAssets assets, Vector4f baseColor,
                                          float roughness, float metallic) {
        return assets.pbr(new PbrMaterialProperties(baseColor, metallic, roughness,
                1.0f, 1.0f, new Vector3f(), Map.of()));
    }

    private static Material whiteMaterial(ExhibitionAssets assets) {
        return assets.pbr(new PbrMaterialProperties(new Vector4f(0.92f, 0.92f, 0.94f, 1.0f),
                0.0f, 0.35f, 1.0f, 1.0f, new Vector3f(0.02f), Map.of()));
    }
}
