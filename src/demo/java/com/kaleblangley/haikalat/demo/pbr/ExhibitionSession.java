package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureState;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecal;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared exhibition session: owns the one window-scoped pipeline, the shared
 * asset set and the per-station view cache.  The visible demo and the hidden
 * deterministic integration drive the exact same object.
 *
 * <p>Station switching is a frame-boundary operation: the station scene is
 * published through {@code replaceScene}, the complete feature request is
 * submitted to the production control, and temporal history is reset before
 * the next frame.  Nothing here fakes an effect: a station without a real
 * builder reports its delivery batch instead.</p>
 */
final class ExhibitionSession implements AutoCloseable {
    static final float DEFAULT_DELTA_SECONDS = 1.0f / 60.0f;

    private final GlfwWindow window;
    private final FrameDriver driver;
    private final ExhibitionAssets assets;
    private final RenderSettings renderSettings;
    private RenderPipeline pipeline;
    private final Map<ViewKey, ExhibitionStationView> views = new HashMap<>();
    private final Map<ViewKey, CameraPose> cameraPresets = new HashMap<>();
    private final Scene neutralScene;
    private final CameraPose neutralCameraPreset;
    private final FeedHolder feedHolder;

    private ExhibitionStation station;
    private ExhibitionPhase phase = ExhibitionPhase.BASELINE;
    private ExhibitionStationView view;
    private ExhibitionMuseum museum;
    private ExhibitionMuseum.Visit hallVisit;
    private final Matrix4f coverageProjection = new Matrix4f();
    private final Matrix4f coverageView = new Matrix4f();
    private boolean hallParticleBudgetLimited;
    private boolean hallGiBaseline;
    private int hallEffectId = -1;
    private boolean wired;
    private int frameIndex;
    private float elapsedSeconds;
    private RenderFeatureState lastState;
    private Render3dDiagnostics diagnostics = Render3dDiagnostics.UNAVAILABLE;

    private record ViewKey(int stationId, ExhibitionPhase phase) {
    }

    /** Frozen per-station camera preset restored whenever the station is selected. */
    private record CameraPose(float x, float y, float z, float yaw, float pitch, float zoom) {
        static CameraPose of(com.kaleblangley.haikalat.subsystems.render3d.Camera camera) {
            var position = camera.position();
            return new CameraPose(position.x, position.y, position.z, camera.yaw(),
                    camera.pitch(), camera.zoom());
        }

        void apply(com.kaleblangley.haikalat.subsystems.render3d.Camera camera) {
            camera.setPosition(new org.joml.Vector3f(x, y, z));
            camera.setYaw(yaw);
            camera.setPitch(pitch);
            camera.setZoom(zoom);
            camera.setMovementSpeed(3.0f);
        }
    }

    /**
     * Mutable bridge shared by the always-registered HDR VFX/decal recorders and
     * the currently active station.  The recorders are registered once before
     * {@code build()}; a station only swaps the feed/decal batch.
     */
    static final class FeedHolder {
        Camera camera;
        ExhibitionVfxFeed feed;
        List<ProjectedDecal> decals = List.of();
        int frameIndex;
        float deltaSeconds = DEFAULT_DELTA_SECONDS;
    }

    private ExhibitionSession(GlfwWindow window, FrameDriver driver, ExhibitionAssets assets,
                              RenderSettings renderSettings, RenderPipeline pipeline,
                              Scene neutralScene, FeedHolder feedHolder) {
        this.window = window;
        this.driver = driver;
        this.assets = assets;
        this.renderSettings = renderSettings;
        this.pipeline = pipeline;
        this.neutralScene = neutralScene;
        this.neutralCameraPreset = CameraPose.of(neutralScene.camera());
        this.feedHolder = feedHolder;
        this.lastState = pipeline.features().state();
        select(ExhibitionCatalog.stations().getFirst().id());
    }

    static ExhibitionSession create(GlfwWindow window, boolean hidden) {
        RenderSettings settings = RenderSettings.builder()
                .vsync(!hidden)
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.MANUAL)
                .exposure(1.0f)
                .build();
        FrameDriver driver = new FrameDriver(settings);
        ExhibitionAssets assets = null;
        RenderPipeline pipeline = null;
        try {
            assets = ExhibitionAssets.create(driver.device(), hidden);
            Scene bootScene = ExhibitionSceneKit.studio(assets, ExhibitionSceneKit.defaultCamera());
            ExhibitionSceneKit.addScaleProps(bootScene, assets);
            FeedHolder holder = new FeedHolder();
            pipeline = buildPipeline(window, bootScene, settings, assets, holder);
            pipeline.build();
            return new ExhibitionSession(window, driver, assets, settings, pipeline,
                    ExhibitionSceneKit.studio(assets, ExhibitionSceneKit.defaultCamera()), holder);
        } catch (RuntimeException | Error failure) {
            if (pipeline != null) {
                try {
                    pipeline.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (assets != null) {
                try {
                    assets.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                driver.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static RenderPipeline buildPipeline(GlfwWindow window, Scene scene,
                                                RenderSettings settings, ExhibitionAssets assets,
                                                FeedHolder holder) {
        return buildPipeline(window, scene, settings, assets, holder, false);
    }

    /**
     * @param bare {@code true} for the reduced-resolution reconstruction chain,
     *             which composes the whole scene itself and cannot share the
     *             full-resolution HDR VFX/decal recorders; such stations own no
     *             VFX feed or decal batch
     */
    private static RenderPipeline buildPipeline(GlfwWindow window, Scene scene,
                                                RenderSettings settings, ExhibitionAssets assets,
                                                FeedHolder holder, boolean bare) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings,
                assets.environment());
        return bare ? pipeline : configurePipeline(pipeline, assets, holder, window);
    }

    private static RenderPipeline configurePipeline(RenderPipeline pipeline, ExhibitionAssets assets,
                                                    FeedHolder holder, RenderWindow window) {
        return pipeline
                .maxVfxLights(4)
                .decals(assets.vfxRenderer(), () -> holder.decals)
                // Station VFX is an additive display-space composite; volumetric
                // fog is a separate station feature, so the composite explicitly
                // opts out of the fog prelude instead of silently double-fogging.
                .hdrVfxFogOptOut(true)
                .hdrVfx((resources, commands) -> {
                    ExhibitionVfxFeed feed = holder.feed;
                    if (feed == null) {
                        return;
                    }
                    int sceneDepth = resources.depthAttachment(
                            PostProcessTargets.VFX_SCENE_DEPTH);
                    int w = window.width();
                    int h = window.height();
                    Matrix4f projection = CameraProjection.stable(holder.camera, w, h, new Matrix4f());
                    Matrix4f view = holder.camera.getViewMatrix(new Matrix4f());
                    feed.record(commands, projection, view, sceneDepth,
                            w, h, holder.frameIndex,
                            holder.deltaSeconds);
                });
    }

    ExhibitionStation station() {
        return station;
    }

    ExhibitionPhase phase() {
        return phase;
    }

    boolean wired() {
        return wired;
    }

    ExhibitionBatch pendingBatch() {
        return wired ? null : station.batch();
    }

    String note() {
        return view == null ? "" : view.note();
    }

    RenderPipeline pipeline() {
        return pipeline;
    }

    /** Camera of the currently published station scene, for free-look controls. */
    com.kaleblangley.haikalat.subsystems.render3d.Camera camera() {
        return pipeline.scene().camera();
    }

    RenderSettings renderSettings() {
        return renderSettings;
    }

    ExhibitionAssets assets() {
        return assets;
    }

    FrameDriver driver() {
        return driver;
    }

    int frameIndex() {
        return frameIndex;
    }

    float elapsedSeconds() {
        return elapsedSeconds;
    }

    RenderFeatureState featureState() {
        return lastState;
    }

    Render3dDiagnostics diagnostics() {
        return diagnostics;
    }

    /** Selects one catalogue station, resetting the phase to the baseline. */
    void select(int stationId) {
        station = ExhibitionCatalog.station(stationId);
        ExhibitionStationBuilder builder = ExhibitionStationWiring.builderFor(stationId);
        wired = builder != null;
        phase = ExhibitionPhase.BASELINE;
        applyPhase(builder);
    }

    void next() {
        select(ExhibitionCatalog.next(station.id()).id());
    }

    void previous() {
        select(ExhibitionCatalog.previous(station.id()).id());
    }

    void togglePhase() {
        setPhase(phase.other());
    }

    void setPhase(ExhibitionPhase nextPhase) {
        if (nextPhase == phase) {
            return;
        }
        phase = nextPhase;
        applyPhase(ExhibitionStationWiring.builderFor(station.id()));
    }

    /** Re-applies the current station phase from a clean history. */
    void reset() {
        if (view != null && view.driver() != null) {
            view.driver().onReset(pipeline, phase);
        }
        applyPhase(ExhibitionStationWiring.builderFor(station.id()));
    }

    void showHall(ExhibitionMuseum hall) {
        switchHallScene(hall.scene(), renderSettings, null);
        hall.configure(pipeline);
        pipeline.resetTemporalHistory();
        view = null;
        museum = hall;
        hallVisit = null;
        hallParticleBudgetLimited = false;
        hallGiBaseline = false;
        hallEffectId = -1;
        feedHolder.feed = hall.floorVfx();
        feedHolder.decals = hall.floorDecals();
        frameIndex = 0;
        elapsedSeconds = 0.0f;
    }

    void updateHall(float delta) {
        int nextId = museum.effectStationId(hallEffectId);
        boolean changedVisit = nextId != hallEffectId;
        if (changedVisit) {
            if (nextId < 0) {
                showHall(museum);
            } else {
                var next = museum.visit(nextId);
                var effect = next.view();
                switchHallScene(next.scene(), effect.settings().render(), effect);
                hallVisit = next;
                hallEffectId = nextId;
                hallGiBaseline = false;
                if (effect.driver() != null) {
                    effect.driver().onReset(pipeline, ExhibitionPhase.EFFECT);
                    effect.driver().onSelect(pipeline, ExhibitionPhase.EFFECT);
                }
                feedHolder.feed = effect.driver() == null ? null : effect.driver().vfxFeed();
                feedHolder.decals = List.of();
                pipeline.resetTemporalHistory();
                frameIndex = 0;
                elapsedSeconds = 0f;
            }
        }
        if (hallVisit == null) {
            museum.updateSharedEffects(pipeline, frameIndex, delta);
        } else {
            museum.updateVisitCamera(hallVisit);
            updateHallRequest(changedVisit);
        }
    }

    private void updateHallRequest(boolean changedVisit) {
        int width = window.width();
        int height = window.height();
        var requested = hallVisit.view().settings();
        var particles = requested.volumetricParticles();
        // The free camera can cross a station's authored coverage budget. Check
        // the production bound before requesting the particle pass for this frame.
        boolean limited = false;
        if (particles.active() && width > 0 && height > 0) {
            Camera camera = hallVisit.scene().camera();
            CameraProjection.stable(camera, width, height, coverageProjection)
                    .mul(camera.getViewMatrix(coverageView));
            limited = VolumetricParticlePass.exceedsCoverage(particles, coverageProjection, width, height);
        }
        if (changedVisit || limited != hallParticleBudgetLimited) {
            pipeline.features().submit(limited ? requested.toBuilder()
                    .volumetricParticles(VolumetricParticleSettings.disabled()).build() : requested);
        }
        hallParticleBudgetLimited = limited;
    }

    int hallEffectId() {
        return hallEffectId;
    }

    boolean hallGiComparisonAvailable() {
        return ExhibitionMuseum.supportsGiComparison(hallEffectId);
    }

    boolean hallGiBaseline() {
        return hallGiBaseline;
    }

    void toggleHallGiComparison() {
        if (!hallGiComparisonAvailable()) return;
        var settings = hallVisit.view().settings();
        if (!hallGiBaseline) {
            var baseline = settings.toBuilder();
            switch (hallEffectId) {
                case 39 -> baseline.postProcess(settings.postProcess().withSsgi(SsgiSettings.disabled()));
                case 44 -> baseline.ddgi(DdgiSettings.disabled());
                case 45 -> baseline.vct(VctSettings.disabled());
                default -> throw new IllegalStateException("Unexpected GI exhibit: " + hallEffectId);
            }
            settings = baseline.build();
        }
        pipeline.features().submit(settings);
        hallGiBaseline = !hallGiBaseline;
    }

    boolean hallParticleBudgetLimited() {
        return hallParticleBudgetLimited;
    }

    private void switchHallScene(Scene scene, RenderSettings settings, ExhibitionStationView next) {
        ExhibitionStationView previous = hallVisit == null ? view : hallVisit.view();
        boolean rebuild = (previous != null && previous.pipelineRebuild())
                || (next != null && next.pipelineRebuild());
        pipeline.applyAtmosphere(null);
        pipeline.vfxLights(List.of());
        pipeline.weightedOitSurfaces(List.of());
        pipeline.stochasticTransparencySurfaces(List.of());
        pipeline.vctSceneInput(null);
        switchScene(scene, settings, rebuild);
    }

    private void applyPhase(ExhibitionStationBuilder builder) {
        // Atmosphere and VFX lights live outside RenderFeatureSettings; never
        // let one station's sky/sun/effect lights leak into the next station.
        pipeline.applyAtmosphere(null);
        pipeline.vfxLights(List.of());
        if (!wired || builder == null) {
            switchScene(neutralScene, renderSettings, museum != null);
            clearHall();
            neutralCameraPreset.apply(neutralScene.camera());
            pipeline.features().submit(ExhibitionSettings.base(renderSettings));
            pipeline.resetTemporalHistory();
            view = null;
            feedHolder.feed = null;
            feedHolder.decals = List.of();
            frameIndex = 0;
            elapsedSeconds = 0.0f;
            return;
        }
        ExhibitionStationView next = views.computeIfAbsent(
                new ViewKey(station.id(), phase),
                key -> builder.build(new ExhibitionStationBuildContext(assets, renderSettings),
                        station, phase));
        ViewKey key = new ViewKey(station.id(), phase);
        CameraPose preset = cameraPresets.get(key);
        if (preset == null) {
            cameraPresets.put(key, CameraPose.of(next.scene().camera()));
        } else {
            preset.apply(next.scene().camera());
        }
        boolean rebuildPipeline = museum != null || next.pipelineRebuild()
                || (view != null && view.pipelineRebuild());
        switchScene(next.scene(), next.settings().render(), rebuildPipeline);
        clearHall();
        view = next;
        if (next.driver() != null) {
            next.driver().onSelect(pipeline, phase);
        }
        feedHolder.feed = next.driver() == null ? null : next.driver().vfxFeed();
        feedHolder.decals = next.driver() == null ? List.of()
                : next.driver().projectedDecals(0, DEFAULT_DELTA_SECONDS, phase);
        pipeline.features().submit(next.settings());
        pipeline.resetTemporalHistory();
        frameIndex = 0;
        elapsedSeconds = 0.0f;
    }

    private void clearHall() {
        museum = null;
        hallVisit = null;
        hallParticleBudgetLimited = false;
        hallGiBaseline = false;
        hallEffectId = -1;
    }

    private void switchScene(Scene scene, RenderSettings settings, boolean rebuildPipeline) {
        if (rebuildPipeline) {
            // Scene-derived consumers that are outside the topology key (for
            // example a refraction material) only appear in a freshly built
            // generation, so this phase gets its own pipeline.  Some stations
            // also need a constructor-time render setting (render scale), so
            // the phase's own render settings are used for the build.
            RenderPipeline candidate = buildPipeline(window, scene,
                    settings, assets, feedHolder,
                    settings.renderScale() < 1.0f);
            try {
                candidate.build();
            } catch (RuntimeException | Error failure) {
                try {
                    candidate.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
            RenderPipeline previousPipeline = pipeline;
            pipeline = candidate;
            previousPipeline.close();
        } else {
            pipeline.replaceScene(scene);
        }
    }

    void resize(int width, int height) {
        pipeline.resize(width, height);
    }

    /**
     * Runs one complete deterministic frame.
     *
     * @param deltaSeconds fixed timestep supplied by the caller
     * @param present      {@code true} for the visible window, {@code false} for hidden integration
     */
    void frame(float deltaSeconds, boolean present) {
        ExhibitionStationView active = hallVisit == null ? view : hallVisit.view();
        if (active != null && active.driver() != null) {
            ExhibitionPhase activePhase = hallVisit == null ? phase : ExhibitionPhase.EFFECT;
            active.driver().onFrame(pipeline, frameIndex, deltaSeconds, activePhase);
            feedHolder.decals = active.driver().projectedDecals(frameIndex, deltaSeconds, activePhase);
        }
        feedHolder.frameIndex = frameIndex;
        feedHolder.deltaSeconds = deltaSeconds;
        feedHolder.camera = camera();
        driver.beginFrame();
        pipeline.execute(driver.device(), deltaSeconds);
        driver.recordGraph(pipeline.graph());
        driver.endFrame();
        if (present) {
            driver.present(window::swapBuffers);
        }
        frameIndex++;
        elapsedSeconds += deltaSeconds;
        lastState = pipeline.features().state();
        diagnostics = pipeline.lastRender3dDiagnostics();
    }

    /** Presents a frame whose commands were recorded by {@link #frame(float, boolean)}. */
    void present() {
        driver.present(window::swapBuffers);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            pipeline.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            assets.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        try {
            driver.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
