package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.HostGlState;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.RenderGraph.PassExecutor;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.ResourceOwnership;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;
import com.kaleblangley.haikalat.core.presentation.PresentationResult;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.core.assets.MaterialModel;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.EnvironmentBackgroundRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewController;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewRenderer;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

public final class RenderPipeline {
    private static final String FULL_SCAN_BENCHMARK_PROPERTY =
            "haikalat.internal.benchmark.fullScanReference";
    private static final String HOST_COLOR_IMPORT = "HaikalatHostColor";
    private static final String HOST_DEPTH_IMPORT = "HaikalatHostDepth";
    private static final String HOST_STENCIL_IMPORT = "HaikalatHostStencil";
    private static final int SHADOW_TEXTURE_UNIT = 7;
    private static final int POINT_SHADOW_TEXTURE_UNIT = 11;
    private static final int SPOT_SHADOW_TEXTURE_UNIT = 12;
    private static final int GTAO_TEXTURE_UNIT = 13;
    private static final AtomicLong PREVIEW_GENERATIONS = new AtomicLong();
    
    // O2: Replace 26 string comparisons with O(1) HashSet lookup
    private static final Set<String> FRAME_OWNED_UNIFORMS = Set.of(
        "uCameraPosition",
        "uDirectionalLightSpace",
        "uDirectionalShadowFrameLightIndex",
        "uHasDirectionalShadow",
        "uShadowMap",
        "uShadowBias",
        "uHasPointShadow",
        "uPointShadowMap",
        "uPointShadowBias",
        "uHasSpotShadow",
        "uSpotShadowMap",
        "uSpotShadowBias",
        "uIrradianceMap",
        "uPrefilteredMap",
        "uBrdfLut",
        "uEnvironmentIntensity",
        "uEnvironmentRotation",
        "uPrefilterMaxLod",
        "uGtaoEnabled",
        "uGtaoMap",
        "uClusterDebugMode"
    );
    private static final Set<String> FRAME_OWNED_UNIFORM_PREFIXES = Set.of(
        "uDirectionalCascadeMatrices[",
        "uDirectionalCascadeSplits["
    );
    private static final int[] NO_CASCADE_COUNTS = new int[0];
    private static final String SHADOW_FAILURE_PROPERTY = "haikalat.test.failShadowPassOnce";
    private static final String SHADOW_FAILURE_MESSAGE = "injected shadow pass failure";
    private final RenderWindow window;
    private Scene scene;
    private final InstancedRenderer instanced;
    private RenderSettings settings;
    private DirectionalShadowMap directionalShadowMap = DirectionalShadowMap.defaults();
    private DirectionalCascadeSettings directionalCascadeSettings =
            DirectionalCascadeSettings.disabled();
    private LocalShadowPipelineSettings localShadowSettings =
            LocalShadowPipelineSettings.legacyDefaults();
    private ClusteredLightingSettings clusteredLightingSettings =
            ClusteredLightingSettings.defaults();
    private ClusterDebugMode clusterDebugMode = ClusterDebugMode.OFF;
    private PointShadowAtlas pointShadowAtlas = PointShadowAtlas.defaults();
    private SpotShadowAtlas spotShadowAtlas = SpotShadowAtlas.defaults();
    private PipelineGeneration activeGeneration;
    private Matrix4f lastDirectionalLightSpaceMatrix = new Matrix4f();
    private List<Matrix4f> lastDirectionalCascadeMatrices = List.of();
    private float[] lastDirectionalCascadeSplits = new float[0];
    /** Per-cascade ordinary caster slice counts used by diagnostics. */
    private int[] lastDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
    /** Counts staged by the current frame and committed only after GPU success. */
    private int[] pendingDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
    /** -1 until sceneFrame stages a directional/no-directional result for this frame. */
    private int pendingDirectionalCascadeCount = -1;
    private List<Matrix4f> lastPointLightSpaceMatrices = List.of();
    private Matrix4f lastSpotLightSpaceMatrix = new Matrix4f();
    private ShadowFramePlan currentShadowFramePlan;
    private ShadowFramePlan lastShadowFramePlan = ShadowFramePlan.EMPTY;
    private final ShadowCasterPlanner shadowCasterPlanner = new ShadowCasterPlanner();
    private ShadowCasterPlanner.ShadowCasterPlan currentShadowCasterPlan;
    private int lastShadowCasterDrawCount;
    private int lastPointShadowCasterDrawCount;
    private int lastSpotShadowCasterDrawCount;
    private PbrEnvironment pbrEnvironment;
    private float iblIntensity;
    private float iblRotationRadians;
    private long visualSettingsRevision;
    private long sceneFastPathReplacementCount;
    private long sceneGraphRebuildCount;
    private SceneFrame currentSceneFrame;
    private FrameLightTable currentLightTable;
    private ClusterGrid currentClusterGrid;
    private int activeFrameIndex;
    private int pipelineFrameIndex;
    private VisibilityStatistics lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;
    private GraphPreviewController previewController = new GraphPreviewController();
    private final Set<RenderDevice> usedDevices = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Material, Boolean> frameStateInvalidationByMaterial = new IdentityHashMap<>();
    private PostProcessSettings postProcessSettings = PostProcessSettings.defaults();
    private OutdoorEnvironmentSettings outdoorEnvironment = OutdoorEnvironmentSettings.disabled();
    private VolumetricFogSettings volumetricFogSettings = VolumetricFogSettings.disabled();
    private PassExecutor hdrVfxRecorder;
    private CameraPassExecutor cameraAwareHdrVfxRecorder;
    private FogCameraPassExecutor fogAwareHdrVfxRecorder;
    private boolean hdrVfxFogOptOut;
    private RenderFrameContext activeFrameContext;
    private RenderFrameContext lastFrameContext;
    private RenderFrameContext lastFailedFrameContext;
    private final TemporalFrameState temporalFrameState = new TemporalFrameState();
    private final TemporalSceneState temporalSceneState = new TemporalSceneState();
    private long topologySettingsRevision;
    private boolean executing;
    private boolean embedded;
    private PresentationTarget initialEmbeddedTarget;
    private long lastCandidateGenerationId;
    private long lastRetiredGenerationId;
    private long generationBuildCount;
    private long generationFailureCount;
    private long generationReuseCount;
    private boolean topologyRebuiltPending;
    private boolean lastFrameTopologyRebuilt;
    private String lastFailureStage = "";
    private boolean benchmarkCpuTimingEnabled;
    private long currentLightPackNanos;
    private BenchmarkCpuTiming lastBenchmarkCpuTiming = BenchmarkCpuTiming.UNAVAILABLE;

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects, InstancedRenderer instanced) {
        this(window, camera, sceneObjects, instanced, RenderSettings.builder().build(), null);
    }

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects,
                          InstancedRenderer instanced, RenderSettings settings) {
        this(window, camera, sceneObjects, instanced, settings, null);
    }

    public RenderPipeline(RenderWindow window, Camera camera, List<SceneObject> sceneObjects,
                          InstancedRenderer instanced, RenderSettings settings,
                          PbrEnvironment pbrEnvironment) {
        this.window = window;
        this.scene = Scene.of(camera, sceneObjects);
        this.instanced = instanced;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.pbrEnvironment = pbrEnvironment;
        initializeIblOverrides(pbrEnvironment);
    }

    public RenderPipeline(RenderWindow window, Scene scene, InstancedRenderer instanced, RenderSettings settings) {
        this(window, scene, instanced, settings, null);
    }

    public RenderPipeline(RenderWindow window, Scene scene, InstancedRenderer instanced,
                          RenderSettings settings, PbrEnvironment pbrEnvironment) {
        this.window = Objects.requireNonNull(window, "window");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.instanced = instanced;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.pbrEnvironment = pbrEnvironment;
        initializeIblOverrides(pbrEnvironment);
    }

    /**
     * Creates a pipeline without a platform window. The target only supplies
     * the initial managed RenderGraph extent and may be replaced per frame.
     */
    public RenderPipeline(PresentationTarget initialTarget, Scene scene,
                          InstancedRenderer instanced, RenderSettings settings) {
        this(initialTarget, scene, instanced, settings, null);
    }

    /**
     * Creates a pipeline without a platform window and with an optional PBR environment.
     */
    public RenderPipeline(PresentationTarget initialTarget, Scene scene,
                          InstancedRenderer instanced, RenderSettings settings,
                          PbrEnvironment pbrEnvironment) {
        this(initialExtent(initialTarget), scene, instanced, settings, pbrEnvironment);
        embedded = true;
        initialEmbeddedTarget = Objects.requireNonNull(initialTarget, "initialTarget");
    }

    public static List<String> passNamesFor(AntiAliasingMode mode) {
        return PostProcessPassBuilder.passNamesFor(mode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        return PostProcessPassBuilder.passNamesFor(mode, directionalShadow);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        return PostProcessPassBuilder.passNamesFor(mode, toneMappingMode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                            boolean directionalShadow) {
        return PostProcessPassBuilder.passNamesFor(mode, toneMappingMode, directionalShadow);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                            BloomSettings bloomSettings, boolean directionalShadow) {
        return PostProcessPassBuilder.passNamesFor(
                mode, toneMappingMode, bloomSettings, directionalShadow);
    }

    public RenderPipelineKind kind() {
        return RenderPipelineKind.FORWARD;
    }

    /** Configures subsystem-owned optional effects before the pipeline is built. */
    public RenderPipeline postProcessSettings(PostProcessSettings value) {
        if (activeGeneration != null) {
            throw new IllegalStateException("post-process settings must be configured before build");
        }
        postProcessSettings = Objects.requireNonNull(value, "postProcessSettings");
        return this;
    }

    /**
     * Configures the v0.24 outdoor environment before the first generation is
     * built.  The snapshot owns no GL resources; applying it is therefore a
     * normal candidate-generation operation and never mutates a live frame.
     */
    public RenderPipeline outdoorEnvironment(OutdoorEnvironmentSettings value) {
        if (activeGeneration != null) {
            throw new IllegalStateException("outdoor environment must be configured before build");
        }
        OutdoorEnvironmentSettings required = Objects.requireNonNull(value, "outdoorEnvironment");
        outdoorEnvironment = required;
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Returns the immutable environment snapshot used by the next frame. */
    public OutdoorEnvironmentSettings outdoorEnvironment() {
        return outdoorEnvironment;
    }

    /** Configures independently lit three-dimensional fog before build. */
    public RenderPipeline volumetricFog(VolumetricFogSettings value) {
        if (activeGeneration != null) throw new IllegalStateException("use applyVolumetricFog after build");
        volumetricFogSettings = Objects.requireNonNull(value, "volumetricFog");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    public VolumetricFogSettings volumetricFog() { return volumetricFogSettings; }

    /** Publishes scalar updates or a fully built replacement generation at a frame boundary. */
    public void applyVolumetricFog(VolumetricFogSettings value) {
        VolumetricFogSettings replacement = Objects.requireNonNull(value, "volumetricFog");
        if (executing) throw new IllegalStateException("fog changes are only allowed at frame boundaries");
        if (replacement.equals(volumetricFogSettings)) return;
        if (activeGeneration == null) { volumetricFog(replacement); return; }
        VolumetricFogSettings previous = volumetricFogSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        volumetricFogSettings = replacement;
        try {
            PipelineTopology topology = PipelineTopology.capture(scene, settings, postProcessSettings,
                    activeGeneration.graph.width(), activeGeneration.graph.height(),
                    hdrVfxRecorder != null, embedded,
                    directionalCascadeSettings, localShadowSettings, replacement);
            if (!topology.equals(activeGeneration.topology)) activateGeneration(createGeneration(scene, topology));
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) volumetricFogSettings = previous;
            throw failure;
        }
    }

    /** Returns the immutable global visual values that will be used by the next frame. */
    public VisualSettings visualSettings() {
        SceneLight direct = firstDirectionalLight();
        StylizedSkySettings fallback = outdoorEnvironment.sky();
        return new VisualSettings(settings.exposure(), iblIntensity, iblRotationRadians,
                direct == null ? fallback.sunDirection() : direct.direction(),
                direct == null ? fallback.sunColor() : direct.color(),
                direct == null ? fallback.sunIntensity() : direct.intensity(),
                settings.antiAliasingMode(), settings.bloomSettings(), outdoorEnvironment,
                volumetricFogSettings);
    }

    public long visualSettingsRevision() {
        return visualSettingsRevision;
    }

    /** Enables allocation-free benchmark timing before frame execution. */
    public void enableBenchmarkCpuTiming() {
        if (executing) throw new IllegalStateException("cannot enable benchmark timing during a frame");
        benchmarkCpuTimingEnabled = true;
    }

    public BenchmarkCpuTiming lastBenchmarkCpuTiming() {
        return lastBenchmarkCpuTiming;
    }

    /**
     * Atomically applies scalar profile values or builds a complete candidate
     * generation when AA/Bloom/Outdoor resource shape changes.
     */
    public void applyVisualSettings(VisualSettings value) {
        applyVisualSettingsCandidate(value, pbrEnvironment);
    }

    /** Atomically applies a visual profile and its borrowed IBL environment. */
    public void applyVisualSettings(VisualSettings value, PbrEnvironment environment) {
        applyVisualSettingsCandidate(value, Objects.requireNonNull(environment, "environment"));
    }

    private void applyVisualSettingsCandidate(VisualSettings value,
                                              PbrEnvironment environment) {
        VisualSettings replacement = Objects.requireNonNull(value, "visualSettings");
        VisualSettings previousVisual = visualSettings();
        PbrEnvironment previousEnvironment = pbrEnvironment;
        boolean environmentChanged = previousEnvironment != environment;
        if (replacement.equals(previousVisual) && !environmentChanged) return;
        if (executing) throw new IllegalStateException("visual settings changes are only allowed at frame start");
        if (firstDirectionalLight() == null) {
            throw new IllegalStateException(
                    "visual directional-light update requires an existing directional light");
        }
        RenderSettings replacementRender = settings.withVisualSettings(
                replacement.manualExposure(), replacement.bloom(),
                replacement.antiAliasingMode());
        RenderSettings previousRender = settings;
        OutdoorEnvironmentSettings previousOutdoor = outdoorEnvironment;
        VolumetricFogSettings previousVolume = volumetricFogSettings;
        float previousIblIntensity = iblIntensity;
        float previousIblRotation = iblRotationRadians;
        PipelineGeneration previousGeneration = activeGeneration;
        pbrEnvironment = environment;
        boolean lightingChanged = Float.compare(previousIblIntensity,
                replacement.iblIntensity()) != 0
                || Float.compare(previousIblRotation, replacement.iblRotationRadians()) != 0;
        lightingChanged |= !previousVisual.directionalLightDirection()
                .equals(replacement.directionalLightDirection())
                || !previousVisual.directionalLightColor()
                .equals(replacement.directionalLightColor())
                || Float.compare(previousVisual.directionalLightIntensity(),
                replacement.directionalLightIntensity()) != 0;
        boolean outdoorChanged = !previousOutdoor.equals(replacement.outdoor());
        settings = replacementRender;
        outdoorEnvironment = replacement.outdoor();
        volumetricFogSettings = replacement.volumetricFog();
        iblIntensity = replacement.iblIntensity();
        iblRotationRadians = replacement.iblRotationRadians();
        try {
            if (activeGeneration == null) {
                applyProfileDirectionalLight(replacement);
                visualSettingsRevision = Math.incrementExact(visualSettingsRevision);
                return;
            }
            PipelineTopology candidateTopology = PipelineTopology.capture(scene, settings,
                    postProcessSettings, activeGeneration.graph.width(), activeGeneration.graph.height(),
                    hdrVfxRecorder != null, embedded,
                    directionalCascadeSettings, localShadowSettings, volumetricFogSettings);
            boolean outdoorPassShapeChanged = outdoorPassShapeChanged(activeGeneration,
                    outdoorEnvironment);
            if (!activeGeneration.topology.equals(candidateTopology) || outdoorPassShapeChanged
                    || environmentChanged) {
                PipelineGeneration candidate = createGeneration(scene, candidateTopology);
                synchronizeOutdoorSun(scene);
                activateGeneration(candidate);
            } else {
                activeGeneration.postProcess.updateVisualScalars(replacement.manualExposure(),
                        replacement.bloom());
                applyIblOverrides(activeGeneration);
                if (outdoorChanged) {
                    synchronizeOutdoorSun(scene);
                }
                if (lightingChanged) {
                    activeGeneration.postProcess.invalidateTemporalHistory();
                }
            }
            applyProfileDirectionalLight(replacement);
            visualSettingsRevision = Math.incrementExact(visualSettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                settings = previousRender;
                outdoorEnvironment = previousOutdoor;
                volumetricFogSettings = previousVolume;
                iblIntensity = previousIblIntensity;
                iblRotationRadians = previousIblRotation;
                pbrEnvironment = previousEnvironment;
            }
            throw failure;
        }
    }

    /**
     * Applies a new outdoor preset as a frame-boundary candidate.  If shader or
     * target creation fails the previous preset and generation remain active.
     */
    public void applyOutdoorEnvironment(OutdoorEnvironmentSettings value) {
        applyOutdoorEnvironment(value, false);
    }

    /** Atomically replaces the outdoor preset and its borrowed, caller-owned IBL. */
    public void applyOutdoorEnvironment(OutdoorEnvironmentSettings value, PbrEnvironment environment) {
        PbrEnvironment previous = pbrEnvironment;
        float previousIntensity = iblIntensity;
        float previousRotation = iblRotationRadians;
        PipelineGeneration previousGeneration = activeGeneration;
        pbrEnvironment = Objects.requireNonNull(environment, "environment");
        iblIntensity = environment.intensity();
        iblRotationRadians = environment.rotationRadians();
        try {
            applyOutdoorEnvironment(value, previous != environment);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                pbrEnvironment = previous;
                iblIntensity = previousIntensity;
                iblRotationRadians = previousRotation;
            }
            throw failure;
        }
    }

    private void applyOutdoorEnvironment(OutdoorEnvironmentSettings value, boolean replaceEnvironment) {
        OutdoorEnvironmentSettings replacement = Objects.requireNonNull(value, "outdoorEnvironment");
        if (activeGeneration == null) {
            outdoorEnvironment(replacement);
            return;
        }
        if (executing) throw new IllegalStateException("outdoor environment changes are only allowed at frame start");
        OutdoorEnvironmentSettings previous = outdoorEnvironment;
        PipelineGeneration previousGeneration = activeGeneration;
        outdoorEnvironment = replacement;
        try {
            PipelineTopology candidateTopology = PipelineTopology.capture(scene, settings,
                    postProcessSettings, activeGeneration.graph.width(), activeGeneration.graph.height(),
                    hdrVfxRecorder != null, embedded,
                    directionalCascadeSettings, localShadowSettings, volumetricFogSettings);
            boolean outdoorPassShapeChanged = outdoorPassShapeChanged(activeGeneration, replacement);
            if (!replaceEnvironment && activeGeneration.topology.equals(candidateTopology) && !outdoorPassShapeChanged) {
                // The active sky reads the immutable appearance at frame boundaries.
                synchronizeOutdoorSun(scene);
                return;
            }
            PipelineGeneration candidate = createGeneration(scene, candidateTopology);
            synchronizeOutdoorSun(scene);
            activateGeneration(candidate);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) outdoorEnvironment = previous;
            throw failure;
        }
    }

    /** Configures the fixed directional cascade atlas before generation creation. */
    public RenderPipeline directionalCascades(DirectionalCascadeSettings value) {
        if (activeGeneration != null) {
            throw new IllegalStateException("directional cascades must be configured before build");
        }
        directionalCascadeSettings = Objects.requireNonNull(value, "directionalCascadeSettings");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Configures bounded local-shadow capacity, scheduling, filtering and caching. */
    public RenderPipeline localShadows(LocalShadowPipelineSettings value) {
        if (activeGeneration != null) {
            throw new IllegalStateException("local shadows must be configured before build");
        }
        localShadowSettings = Objects.requireNonNull(value, "localShadowSettings");
        pointShadowAtlas = value.maxPointLights() == 0 ? null
                : new PointShadowAtlas(value.point(), value.maxPointLights());
        spotShadowAtlas = value.maxSpotLights() == 0 ? null
                : new SpotShadowAtlas(value.spot(), value.maxSpotLights());
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Configures the clustered-forward grid, capacity and memory budget before build. */
    public RenderPipeline clusteredLighting(ClusteredLightingSettings value) {
        if (activeGeneration != null) {
            throw new IllegalStateException("clustered lighting must be configured before build");
        }
        clusteredLightingSettings = Objects.requireNonNull(value, "clusteredLighting");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Returns the frozen clustered-forward settings used by the active generation. */
    public ClusteredLightingSettings clusteredLighting() {
        return clusteredLightingSettings;
    }

    /** Selects a cluster debug visualisation for the next frame; never changes topology. */
    public RenderPipeline clusteredDebug(ClusterDebugMode value) {
        clusterDebugMode = Objects.requireNonNull(value, "clusterDebugMode");
        return this;
    }

    public ClusterDebugMode clusteredDebug() {
        return clusterDebugMode;
    }

    /** Adds a controlled HDR VFX recorder before Bloom and tone mapping. */
    public RenderPipeline hdrVfx(PassExecutor recorder) {
        if (activeGeneration != null) {
            throw new IllegalStateException("HDR VFX must be configured before build");
        }
        if (cameraAwareHdrVfxRecorder != null) {
            throw new IllegalStateException("camera-aware HDR VFX is already configured");
        }
        hdrVfxRecorder = Objects.requireNonNull(recorder, "recorder");
        return this;
    }

    /**
     * Adds an HDR VFX recorder that receives the exact camera used by the pipeline frame.
     */
    public RenderPipeline hdrVfxWithCamera(CameraPassExecutor recorder) {
        if (activeGeneration != null) {
            throw new IllegalStateException("HDR VFX must be configured before build");
        }
        if (hdrVfxRecorder != null) {
            throw new IllegalStateException("legacy HDR VFX is already configured");
        }
        cameraAwareHdrVfxRecorder = Objects.requireNonNull(recorder, "recorder");
        hdrVfxRecorder = (resources, commands) ->
                cameraAwareHdrVfxRecorder.execute(resources, commands, externalFrameCamera());
        return this;
    }

    /** Records depth-aware HDR VFX against the current fog field before the final MSAA resolve. */
    public RenderPipeline hdrVfxWithFog(FogCameraPassExecutor recorder) {
        if (activeGeneration != null || hdrVfxRecorder != null)
            throw new IllegalStateException("HDR VFX must be configured once before build");
        fogAwareHdrVfxRecorder=Objects.requireNonNull(recorder);
        hdrVfxRecorder=(res,cmd)->fogAwareHdrVfxRecorder.execute(res,cmd,fogRasterCamera(),
                requireGeneration().volumetric == null ? null : requireGeneration().volumetric.borrowedView());
        return this;
    }

    /** Explicitly opts a custom HDR recorder out of volumetric attenuation. */
    public RenderPipeline hdrVfxFogOptOut(boolean value) {
        if (activeGeneration != null) throw new IllegalStateException("HDR VFX policy must be configured before build");
        hdrVfxFogOptOut=value; return this;
    }

    public void build() {
        if (embedded) {
            try (HostGlState ignored = HostGlState.capture()) {
                buildInternal();
            }
            return;
        }
        buildInternal();
    }

    private void buildInternal() {
        int w = Math.max(1, window.width());
        int h = Math.max(1, window.height());
        if (embedded && settings.antiAliasingMode() == AntiAliasingMode.MSAA
                && initialEmbeddedTarget != null && initialEmbeddedTarget.hasDepth()
                && initialEmbeddedTarget.samples() != Math.max(2, settings.msaaSamples())) {
            throw new IllegalStateException("embedded depth samples "
                    + initialEmbeddedTarget.samples() + " do not match MSAA geometry samples "
                    + Math.max(2, settings.msaaSamples())
                    + "; provide a matching resolvable host depth target");
        }
        PipelineTopology candidateTopology = PipelineTopology.capture(scene, settings,
                postProcessSettings, w, h, hdrVfxRecorder != null, embedded,
                directionalCascadeSettings, localShadowSettings, volumetricFogSettings);
        PipelineGeneration candidate = createGeneration(scene, candidateTopology);
        synchronizeOutdoorSun(scene);
        activateGeneration(candidate);
    }

    private PipelineGeneration createGeneration(Scene generationScene,
                                                PipelineTopology topology) {
        PipelineGeneration candidate = new PipelineGeneration(topology);
        lastCandidateGenerationId = candidate.id;
        try {
            new PipelineFeaturePolicy(topology, pbrEnvironment != null).validate();
            if (topology.volumetricFog() && postProcessSettings.fog().enabled())
                throw new IllegalStateException("volumetric fog replaces analytic fog; disable the other fog path");
            if (topology.volumetricFog() && hdrVfxRecorder != null && fogAwareHdrVfxRecorder == null && !hdrVfxFogOptOut)
                throw new IllegalStateException("custom HDR VFX must use hdrVfxWithFog or explicitly opt out of fog");
            validatePbrVertexLayouts(generationScene);
            candidate.graph = new RenderGraph(topology.width(), topology.height());
            candidate.cameraUniforms = new CameraUniforms();
            if ((topology.directionalShadow() || topology.pointShadow() || topology.spotShadow())
                    && (topology.pbrMaterials() || topology.volumetricFog())) {
                candidate.shadowSamplingBlock = new ShadowSamplingBlock();
            }
            candidate.clusteredResources = new ClusteredLightingResources(
                    topology.width(), topology.height(), clusteredLightingSettings);
            candidate.clusteredLightingBinder = new ClusteredLightingBinder(
                    candidate.clusteredResources, clusteredLightingSettings);
            ClusteredLightingPassBuilder.addPasses(candidate.graph, topology,
                    candidate.clusteredLightingBinder,
                    Boolean.getBoolean(FULL_SCAN_BENCHMARK_PROPERTY));
            if (topology.volumetricFog()) {
                candidate.volumetric = new VolumetricPassBuilder(candidate.id,
                        topology.width(), topology.height(), volumetricFogSettings,
                        candidate.clusteredResources.maxTotalLights());
                candidate.volumetric.configureColorBudget(topology);
                candidate.volumetric.addPasses(candidate.graph, candidate.clusteredLightingBinder,
                        (res, cmd, shader) -> recordVolumeShadowInputs(candidate, res, cmd, shader));
            }
            if (topology.pbrMaterials()) {
                candidate.pbrMaterialBinder = new PbrMaterialBinder(pbrEnvironment);
                candidate.environmentBackground = new EnvironmentBackgroundRenderer(pbrEnvironment);
                applyIblOverrides(candidate);
            }
            if (outdoorEnvironment.enabled()) {
                candidate.stylizedSky = new StylizedSkyRenderer();
            }
            candidate.postProcess = PostProcessPassBuilder.create(
                    settings, postProcessSettings, window, topology.width(), topology.height(),
                    topology.volumetricFog() ? null : hdrVfxRecorder,
                    topology.sceneBuffers().requiresSurfacePass(),
                    topology.sceneBuffers().requires(SceneBufferChannel.REACTIVE),
                    reactiveExecutor());
            boolean gtaoShared = topology.gtaoEnabled()
                    && topology.sceneBuffers().requiresSurfacePass()
                    && topology.sceneBuffers().requires(SceneBufferChannel.NORMAL);
            if (topology.gtaoEnabled()) {
                if (!gtaoShared) {
                    candidate.gtaoDepthShader = ShaderProgram.fromResource(RenderPipeline.class,
                            "/shaders/render3d/gtao/gtao-depth.vert",
                            "/shaders/render3d/gtao/gtao-depth.frag");
                    candidate.gtaoMaskedDepthShader = ShaderProgram.fromResource(RenderPipeline.class,
                            "/shaders/render3d/gtao/gtao-masked-depth.vert",
                            "/shaders/render3d/gtao/gtao-masked-depth.frag");
                    if (instanced != null) {
                        candidate.gtaoInstancedDepthShader = ShaderProgram.fromResource(RenderPipeline.class,
                                "/shaders/render3d/gtao/gtao-instanced-depth.vert",
                                "/shaders/render3d/gtao/gtao-depth.frag");
                    }
                }
                candidate.postProcess.addGtaoPreGeometryPasses(candidate.graph,
                        gtaoDepthExecutor(), gtaoShared);
            }
            if (topology.sceneBuffers().requiresSurfacePass()) {
                candidate.sceneSurfacePass = new SceneSurfacePass();
                if (topology.sampleCount() > 1) {
                    candidate.sceneSurfaceResolvePass = new SceneSurfaceResolvePass(ForwardPassBuilder.depthOnlyFogSurface(topology));
                }
            }
            if (topology.sceneBuffers().requires(SceneBufferChannel.REACTIVE)) {
                candidate.sceneReactivePass = new SceneReactivePass();
            }
            boolean hasDirectionalShadow = topology.directionalShadow();
            boolean hasPointShadow = topology.pointShadow();
            boolean hasSpotShadow = topology.spotShadow();
            if (hasDirectionalShadow || hasPointShadow || hasSpotShadow) {
                candidate.shadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                        "/shaders/shadows/directional-depth.vert", "/shaders/shadows/directional-depth.frag");
                candidate.maskedShadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                        "/shaders/shadows/masked-directional-depth.vert",
                        "/shaders/shadows/masked-directional-depth.frag");
                if ((hasDirectionalShadow || hasPointShadow || hasSpotShadow)
                        && instanced != null && instanced.castShadows()) {
                    candidate.instancedShadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                            "/shaders/shadows/instanced-directional-depth.vert",
                            "/shaders/shadows/directional-depth.frag");
                }
            }

            ForwardPassBuilder.addForwardPasses(candidate.graph, settings, generationScene,
                    postProcessSettings,
                    directionalShadowMap, directionalCascadeSettings,
                    pointShadowAtlas, spotShadowAtlas, topology,
                    localShadowSettings.cacheStaticTiles(),
                    shadowExecutor(), pointShadowExecutor(), spotShadowExecutor(),
                    surfaceExecutor(), surfaceResolveExecutor(), geometryExecutor());
            if (candidate.volumetric != null) {
                candidate.volumetric.addColorPasses(candidate.graph,generationScene,topology,volumetricTransparentExecutor());
                candidate.volumetric.addReactivePass(candidate.graph,topology,volumetricReactiveExecutor());
                candidate.postProcess.sceneColorInput(VolumetricPassBuilder.SCENE_COLOR,VolumetricPassBuilder.TRANSPARENT_PASS);
            }
            candidate.postProcess.addFinalPass(candidate.graph);
            candidate.finalPassName = candidate.postProcess.finalPassName();
            candidate.previewRenderer = new GraphPreviewRenderer(
                    previewController, candidate.graph, pbrEnvironment,
                    PREVIEW_GENERATIONS.incrementAndGet());
            generationBuildCount++;
            lastFailureStage = "";
            return candidate;
        } catch (RuntimeException failure) {
            generationFailureCount++;
            lastFailureStage = "candidate-build";
            try {
                candidate.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public RenderGraph graph() {
        PipelineGeneration generation = activeGeneration;
        return generation == null ? null : generation.graph;
    }

    /** Explicit, synchronous linear HDR diagnostic readback before Bloom/tone mapping. */
    public float[] captureLinearHdrRgbaFloat() {
        PipelineGeneration generation = activeGeneration;
        if (generation == null || executing) {
            throw new IllegalStateException("HDR capture requires a completed pipeline frame");
        }
        return generation.graph.readColorAttachmentRgbaFloat(
                generation.postProcess.linearHdrTextureName());
    }

    public VolumetricFogDiagnostics volumetricFogDiagnostics() {
        PipelineGeneration generation=activeGeneration;
        if (generation==null || generation.volumetric==null || executing)
            return VolumetricFogDiagnostics.UNAVAILABLE;
        var volume=generation.volumetric;var frame=volume.frameState().previous();
        if(frame==null) return VolumetricFogDiagnostics.UNAVAILABLE;
        var resources=volume.resources();var grid=frame.grid();var history=volume.historyPlan();
        return new VolumetricFogDiagnostics(volume.currentOutput().available(),generation.id,frame.sequence(),
                grid.nx(),grid.ny(),grid.nz(),resources.bytes(),resources.residentBytes(),resources.historyValid(),
                history.count(),history.reason().name(),history.shadowRefit(),frame.successfulIndex(),
                frame.samplePhase(),frame.samplePhaseX(),frame.samplePhaseY(),frame.timeSeconds());
    }

    /** Synchronous diagnostics only; layer bounds use Nz+1 for the two prefix fields. */
    public float[] captureVolumetricSlice(VolumetricFogDiagnostics.Field field,int layer) {
        PipelineGeneration generation=activeGeneration;
        if(generation==null || generation.volumetric==null || executing
                || !generation.volumetric.currentOutput().available())
            throw new IllegalStateException("volume slice capture requires a completed valid fog frame");
        var resources=generation.volumetric.resources();
        var texture=switch(Objects.requireNonNull(field,"field")) {
            case MEDIUM -> resources.medium(); case SOURCE -> resources.source();
            case SCATTERING_TRANSMISSION -> resources.prefix(); case HISTORY_REJECTION -> resources.reject();
            case REACTIVE_PREFIX -> resources.reactivePrefix();
        };
        return texture.readLayerRgbaFloat(layer);
    }

    /** Synchronous diagnostic readback; requires counters enabled before the captured frame. */
    public VolumetricFogDiagnostics.Counters captureVolumetricCounters() {
        PipelineGeneration generation=activeGeneration;
        if(generation==null||generation.volumetric==null||executing
                ||!generation.volumetric.currentOutput().available())
            throw new IllegalStateException("volume counter capture requires a completed valid fog frame");
        var volume=generation.volumetric;
        if(!volume.diagnosticCountersEnabled())
            throw new IllegalStateException("enable haikalat.internal.volume.diagnostics before the captured frame");
        var bytes=java.nio.ByteBuffer.allocateDirect(VolumetricResources.DIAGNOSTICS_BYTES)
                .order(java.nio.ByteOrder.nativeOrder());volume.resources().diagnostics().readSnapshot(0,bytes);
        java.util.function.IntToLongFunction counter=i->Integer.toUnsignedLong(bytes.getInt(i*4));
        var histogram=new java.util.ArrayList<Long>(992);for(int i=16;i<=1007;i++)histogram.add(counter.applyAsLong(i));
        var reasons=new java.util.ArrayList<Long>(6);for(int i=1012;i<=1017;i++)reasons.add(counter.applyAsLong(i));
        return new VolumetricFogDiagnostics.Counters(volume.frameState().previous().sequence(),
                counter.applyAsLong(4),counter.applyAsLong(5),counter.applyAsLong(6),counter.applyAsLong(7),
                counter.applyAsLong(10),counter.applyAsLong(8),counter.applyAsLong(9),
                counter.applyAsLong(0),counter.applyAsLong(1),counter.applyAsLong(2),counter.applyAsLong(3),
                counter.applyAsLong(1010),counter.applyAsLong(1011),histogram,reasons);
    }

    /** Full resolution R8 reactive channel, returned as RGBA floats for explicit diagnostic export. */
    public float[] captureVolumetricReactiveRgbaFloat() {
        PipelineGeneration generation=activeGeneration;
        if(generation==null||generation.volumetric==null||executing
                ||!generation.volumetric.currentOutput().available())
            throw new IllegalStateException("volume reactive capture requires a completed valid fog frame");
        String name=generation.topology.sceneBuffers().requires(SceneBufferChannel.REACTIVE)
                ?com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_REACTIVE
                :VolumetricPassBuilder.REACTIVE_TEXTURE;
        return generation.graph.readColorAttachmentRgbaFloat(name);
    }

    /**
     * Transactionally replaces the scene at a frame boundary.
     *
     * <p>When the shadow-pass topology is unchanged, the existing RenderGraph
     * and render targets are retained and only the scene/lighting bindings are
     * replaced. A topology change still builds a complete candidate while the
     * active graph remains alive, then retires the old graph after success.</p>
     */
    public void replaceScene(Scene candidateScene) {
        Objects.requireNonNull(candidateScene, "candidateScene");
        if (executing) {
            throw new IllegalStateException("scene replacement is only allowed at frame start");
        }
        PipelineGeneration generation = activeGeneration;
        if (generation == null) {
            scene = candidateScene;
            return;
        }
        PipelineTopology candidateTopology = PipelineTopology.capture(candidateScene, settings,
                postProcessSettings, generation.graph.width(), generation.graph.height(),
                hdrVfxRecorder != null, embedded,
                directionalCascadeSettings,
                localShadowSettings, volumetricFogSettings);
        if (generation.topology.equals(candidateTopology)) {
            synchronizeOutdoorSun(candidateScene);
            scene = candidateScene;
            currentSceneFrame = null;
            currentShadowCasterPlan = null;
            shadowCasterPlanner.reset();
            activeFrameContext = null;
            frameStateInvalidationByMaterial.clear();
            sceneFastPathReplacementCount++;
            return;
        }
        PipelineGeneration candidate = createGeneration(candidateScene, candidateTopology);
        synchronizeOutdoorSun(candidateScene);
        scene = candidateScene;
        currentSceneFrame = null;
        currentShadowFramePlan = null;
        currentShadowCasterPlan = null;
        shadowCasterPlanner.reset();
        activateGeneration(candidate);
        sceneGraphRebuildCount++;
    }

    /**
     * 返回当前已构建管线最终写入 backbuffer 的 pass 名称。
     *
     * @return 可供兄弟 subsystem 追加 overlay 的稳定组合锚点
     * @throws IllegalStateException 管线尚未成功 build 或已经 close 时抛出
     */
    public String finalPassName() {
        PipelineGeneration generation = activeGeneration;
        String passName = generation == null ? null : generation.finalPassName;
        if (passName == null) {
            throw new IllegalStateException(
                    "RenderPipeline must be built and open before querying finalPassName");
        }
        return passName;
    }

    public Scene scene() {
        return scene;
    }

    /** Number of topology-preserving scene swaps that reused the active graph. */
    public long sceneFastPathReplacementCount() {
        return sceneFastPathReplacementCount;
    }

    /** Number of scene swaps that required a new graph topology. */
    public long sceneGraphRebuildCount() {
        return sceneGraphRebuildCount;
    }

    /** @return 当前 pipeline 的逻辑预览协调器；不暴露 source native handle */
    public GraphPreviewController previewController() {
        return previewController;
    }

    /**
     * 返回在 UiOverlayPass 内、正式 UI 绘制前使用的受控 recorder。
     * 该入口不会改变 RenderGraph topology。
     */
    public PassExecutor previewOverlayRecorder() {
        PipelineGeneration generation = activeGeneration;
        GraphPreviewRenderer renderer = generation == null ? null : generation.previewRenderer;
        if (renderer == null) {
            throw new IllegalStateException("RenderPipeline must be built before preview attachment");
        }
        return renderer.overlayRecorder();
    }

    public Matrix4f lastDirectionalLightSpaceMatrix() {
        return new Matrix4f(lastDirectionalLightSpaceMatrix);
    }

    public int lastShadowCasterDrawCount() {
        return lastShadowCasterDrawCount;
    }

    /** @return 最近一次点光六面 atlas pass 的 caster draw 总数 */
    public int lastPointShadowCasterDrawCount() {
        return lastPointShadowCasterDrawCount;
    }

    /** @return 最近一次聚光 depth pass 的 caster draw 数 */
    public int lastSpotShadowCasterDrawCount() {
        return lastSpotShadowCasterDrawCount;
    }

    /** @return bounded statistics for the most recently planned shadow views. */
    public ShadowCullingStatistics lastShadowCullingStatistics() {
        return shadowCasterPlanner.statistics();
    }

    /** @return 最近一次成功构建的普通 scene visibility/queue 统计 */
    public VisibilityStatistics lastVisibilityStatistics() {
        return lastVisibilityStatistics;
    }

    /** Builds a bounded diagnostic snapshot on demand; the render hot path stores only scalars. */
    public Render3dDiagnostics lastRender3dDiagnostics() {
        RenderFrameContext context = lastFailureStage.isEmpty() || lastFailedFrameContext == null
                ? lastFrameContext : lastFailedFrameContext;
        VisibilityStatistics visibility = lastVisibilityStatistics;
        PipelineGeneration generation = activeGeneration;
        if (context == null || generation == null) {
            if (lastFailureStage.isEmpty()) return Render3dDiagnostics.UNAVAILABLE;
            return new Render3dDiagnostics(false,
                    Render3dDiagnostics.RevisionSummary.EMPTY, 0, List.of(),
                    generation == null ? 0L : generation.id, lastCandidateGenerationId,
                    lastRetiredGenerationId, generation == null ? "" : generation.topology.toString(),
                    false, Render3dDiagnostics.QueueSummary.EMPTY,
                    Render3dDiagnostics.VisibilitySummary.EMPTY,
                    Render3dDiagnostics.ShadowSummary.EMPTY,
                    Render3dDiagnostics.DepthResolveSummary.EMPTY,
                    Render3dDiagnostics.CacheSummary.EMPTY,
                    Render3dDiagnostics.AmbientOcclusionSummary.EMPTY, lastFailureStage);
        }
        SceneRevisionSnapshot revision = context.revisions();
        var revisions = new Render3dDiagnostics.RevisionSummary(revision.membershipRevision(),
                revision.transformModelRevision(), revision.lightingRevision(),
                revision.materialRenderStateRevision(), revision.cameraRevision(),
                revision.topologySettingsRevision());
        List<String> reasons = context.invalidation().reasons().stream().map(Enum::name).toList();
        var queues = new Render3dDiagnostics.QueueSummary(visibility.opaqueDraws(),
                visibility.maskedDraws(), visibility.alphaDraws(), visibility.additiveDraws(),
                visibility.transparentSortNanos(), visibility.transparentStableTies());
        var visible = new Render3dDiagnostics.VisibilitySummary(visibility.visibilityScanned(),
                visibility.forwardVisible(), visibility.forwardCulled(), visibility.missingBounds(),
                visibility.layerExcluded(), visibility.boundsUpdated(),
                visibility.transparentVisible(), visibility.frustumTestNanos(),
                visibility.totalQueueBuildNanos());
        // The scheduler stages a plan before command execution.  Diagnostics
        // must describe the last completed GPU frame, otherwise a failed frame
        // can combine its matrices/splits with the previous cache counters.
        ShadowFramePlan shadowPlan = lastShadowFramePlan;
        int cascadeCount = shadowPlan.directional().map(value -> value.matrices().size()).orElse(0);
        List<Float> splits = new java.util.ArrayList<>(cascadeCount);
        shadowPlan.directional().ifPresent(value -> {
            for (float split : value.splits()) splits.add(split);
        });
        List<Integer> cascadeCasters = new java.util.ArrayList<>(cascadeCount);
        for (int cascade = 0; cascade < cascadeCount; cascade++) {
            cascadeCasters.add(cascade < lastDirectionalCascadeCasterCounts.length
                    ? lastDirectionalCascadeCasterCounts[cascade] : 0);
        }
        List<Render3dDiagnostics.SelectedShadowLight> selectedLights = shadowPlan.decisions()
                .stream().filter(value -> value.status() == ShadowDecision.Status.SELECTED)
                .map(value -> new Render3dDiagnostics.SelectedShadowLight(value.stableId(),
                        value.type().name(), value.frameLightIndex(), value.slot(),
                        value.priority(), value.score())).toList();
        List<Render3dDiagnostics.RejectedShadowLight> rejectedLights = shadowPlan.decisions()
                .stream().filter(value -> value.status() != ShadowDecision.Status.SELECTED)
                .map(value -> new Render3dDiagnostics.RejectedShadowLight(value.stableId(),
                        value.type().name(), value.frameLightIndex(), value.status().name(),
                        value.priority(), value.score())).toList();
        List<String> missReasons = new java.util.ArrayList<>();
        shadowPlan.directional().ifPresent(value -> value.missReasons().stream()
                .filter(reason -> reason != ShadowFramePlan.MissReason.NONE)
                .map(Enum::name).forEach(missReasons::add));
        shadowPlan.points().stream().filter(PointShadowSlotPlan::dirty)
                .map(PointShadowSlotPlan::missReason).map(Enum::name).forEach(missReasons::add);
        shadowPlan.spots().stream().filter(SpotShadowSlotPlan::dirty)
                .map(SpotShadowSlotPlan::missReason).map(Enum::name).forEach(missReasons::add);
        int pointWidth = pointShadowAtlas == null ? 0 : pointShadowAtlas.width();
        int pointHeight = pointShadowAtlas == null ? 0 : pointShadowAtlas.height();
        int spotWidth = spotShadowAtlas == null ? 0 : spotShadowAtlas.width();
        int spotHeight = spotShadowAtlas == null ? 0 : spotShadowAtlas.height();
        int directionalSize = shadowPlan.directional().isEmpty() ? 0
                : directionalCascadeSettings.enabled() ? directionalCascadeSettings.atlasSize()
                : directionalShadowMap.settings().resolution();
        long depthBytes = 4L * ((long) directionalSize * directionalSize
                + (long) pointWidth * pointHeight + (long) spotWidth * spotHeight);
        var shadows = new Render3dDiagnostics.ShadowSummary(
                shadowPlan.directional().isPresent() ? "selected" : "none",
                shadowPlan.points().isEmpty() ? "none" : "selected",
                shadowPlan.spots().isEmpty() ? "none" : "selected",
                cascadeCount, splits, cascadeCasters, shadowPlan.directionalCandidates(),
                shadowPlan.directional().isPresent() ? 1 : 0, shadowPlan.pointCandidates(),
                shadowPlan.points().size(), shadowPlan.pointCapacity(),
                shadowPlan.spotCandidates(), shadowPlan.spots().size(),
                shadowPlan.spotCapacity(), selectedLights, rejectedLights,
                generation.shadowCache.lastTilesRendered(),
                generation.shadowCache.lastTilesReused(),
                generation.shadowCache.lastCacheHits(),
                generation.shadowCache.lastCacheMisses(), missReasons,
                shadowPlan.filterMode().name(), localShadowSettings.point().resolution(),
                pointWidth, pointHeight, localShadowSettings.spot().resolution(),
                spotWidth, spotHeight, depthBytes);
        boolean surfaceResolved = generation.topology.sceneBuffers().requiresSurfacePass();
        boolean depthResolved = (generation.topology.fog() || generation.topology.hdrVfx()
                || surfaceResolved)
                && generation.topology.antiAliasingMode() == AntiAliasingMode.MSAA;
        var depth = new Render3dDiagnostics.DepthResolveSummary(depthResolved,
                depthResolved ? generation.topology.sampleCount() : 1, 1,
                generation.topology.width(), generation.topology.height());
        var caches = new Render3dDiagnostics.CacheSummary(visibility.forwardQueueReused(),
                visibility.shadowQueueReused(), visibility.modelCacheHits(),
                visibility.modelCacheMisses(), visibility.boundsCacheHits(),
                visibility.boundsCacheMisses(), generationBuildCount, generationFailureCount,
                generationReuseCount, generationBuildCount + generationFailureCount);
        var ambientOcclusion = generation.postProcess.gtaoDiagnostics(
                generation.topology.width(), generation.topology.height());
        ClusteredLightingDiagnostics clustered = clusteredDiagnostics(generation);
        return new Render3dDiagnostics(visibility.available(), revisions,
                context.invalidation().bits(), reasons,
                generation.id, lastCandidateGenerationId, lastRetiredGenerationId,
                generation.topology.toString(), lastFrameTopologyRebuilt, queues, visible,
                shadows, depth, caches, ambientOcclusion, clustered, lastFailureStage);
    }

    private ClusteredLightingDiagnostics clusteredDiagnostics(PipelineGeneration generation) {
        if (generation.clusteredLightingBinder == null) {
            return ClusteredLightingDiagnostics.UNAVAILABLE;
        }
        ClusteredLightingResources.ClusterStorage storage =
                generation.clusteredResources.storage();
        FrameLightTable table = generation.clusteredLightingBinder.stagedTable();
        ClusteredLightingBinder.CounterSnapshot counters =
                generation.clusteredLightingBinder.tryCounterSnapshot();
        boolean countersAvailable = counters != null;
        return new ClusteredLightingDiagnostics(true,
                countersAvailable,
                countersAvailable ? counters.frameSequence() : -1L,
                clusteredLightingSettings.tileSize(), clusteredLightingSettings.zSlices(),
                clusteredLightingSettings.inlineIndicesPerCluster(),
                clusteredLightingSettings.maxLocalLights(),
                clusteredLightingSettings.maxDirectionalLights(),
                table == null ? 0 : table.directionalCount(),
                table == null ? 0 : table.localCount(),
                storage.clusterCount,
                countersAvailable ? counters.overflowClusters() : -1,
                countersAvailable ? counters.maxInlineCount() : -1,
                countersAvailable ? counters.droppedIndices() : -1,
                storage.lightTableBytes, storage.clusterBoundsBytes,
                storage.clusterHeadersBytes, storage.clusterIndicesBytes, storage.totalBytes);
    }

    /** @return 最近一次 shadow pass 绘制的实例 caster 数量 */
    public int lastInstancedShadowCasterCount() {
        return instanced == null ? 0 : instanced.shadowDrawnCount();
    }

    public void execute(RenderDevice device) {
        execute(device, 1.0f / 60.0f);
    }

    /**
     * 执行一帧，并把受上限保护的真实帧间隔交给跨帧后处理。
     *
     * @param device       渲染设备
     * @param deltaSeconds 本帧秒数，必须有限且非负
     */
    public void execute(RenderDevice device, float deltaSeconds) {
        executeFrame(device, deltaSeconds, scene.camera(),
                PresentationTarget.defaultFramebuffer(
                        Math.max(0, window.width()), Math.max(0, window.height())));
    }

    public PresentationResult execute(RenderDevice device, ExternalCamera camera,
                                      PresentationTarget target) {
        return execute(device, camera, target, 1.0f / 60.0f);
    }

    /**
     * Executes one host-directed frame without presenting or swapping buffers.
     */
    public PresentationResult execute(RenderDevice device, ExternalCamera camera,
                                      PresentationTarget target, float deltaSeconds) {
        RenderDevice requiredDevice = Objects.requireNonNull(device, "device");
        ExternalCamera requiredCamera = Objects.requireNonNull(camera, "camera");
        PresentationTarget requiredTarget = Objects.requireNonNull(target, "target");
        if (!requiredTarget.isRenderable()) {
            return PresentationResult.SKIPPED_ZERO_EXTENT;
        }
        try (HostGlState ignored = HostGlState.captureReadFramebuffer(
                requiredTarget.readFramebufferId())) {
            requiredDevice.invalidateState();
            try {
                return executeFrame(requiredDevice, deltaSeconds,
                        requiredCamera, requiredTarget);
            } finally {
                requiredDevice.invalidateState();
            }
        } finally {
            requiredDevice.invalidateState();
        }
    }

    /** Alias matching host-oriented rendering terminology. */
    public PresentationResult render(RenderDevice device, ExternalCamera camera,
                                     PresentationTarget target, float deltaSeconds) {
        return execute(device, camera, target, deltaSeconds);
    }

    private PresentationResult executeFrame(RenderDevice device, float deltaSeconds,
                                            Camera camera, PresentationTarget target) {
        long preparationStart = benchmarkCpuTimingEnabled ? System.nanoTime() : 0L;
        currentLightPackNanos = 0L;
        PipelineGeneration generation = activeGeneration;
        if (generation == null) {
            throw new IllegalStateException("RenderPipeline must be built before execute");
        }
        if (!target.isRenderable()) {
            return PresentationResult.SKIPPED_ZERO_EXTENT;
        }
        if (generation.graph.width() != target.width()
                || generation.graph.height() != target.height()) {
            resize(target.width(), target.height());
            generation = requireGeneration();
        }
        synchronizeHostImports(generation.graph, target);
        usedDevices.add(Objects.requireNonNull(device, "device"));
        currentSceneFrame = null;
        currentLightTable = null;
        currentClusterGrid = null;
        currentShadowCasterPlan = null;
        currentShadowFramePlan = null;
        pendingDirectionalCascadeCount = -1;
        lastShadowCasterDrawCount = 0;
        lastPointShadowCasterDrawCount = 0;
        lastSpotShadowCasterDrawCount = 0;
        long previewFrameSequence = pipelineFrameIndex;
        activeFrameIndex = instanced == null ? pipelineFrameIndex : instanced.frameIndex();
        pipelineFrameIndex++;
        activeFrameContext = RenderFrameContext.capture(scene,
                Objects.requireNonNull(camera, "camera"), target.width(), target.height(),
                deltaSeconds, activeFrameIndex, previewFrameSequence,
                topologySettingsRevision, lastFrameContext);
        executing = true;
        boolean postProcessFrameStarted = false;
        String frameStage = "frame-setup";
        try {
            // Temporal camera/state capture is only paid when a temporal
            // consumer exists; the NONE/FXAA/MSAA paths must keep their
            // pre-v0.24.1 cost.
            boolean temporalActive = generation.sceneSurfacePass != null
                    || settings.antiAliasingMode() == AntiAliasingMode.TAA;
            TemporalFrameState.FrameParameters frameParameters = temporalActive
                    ? captureFrameParameters(activeFrameContext) : null;
            if (temporalActive) {
                temporalFrameState.prepare(frameParameters);
                if (TemporalFrameState.looksLikeCameraCut(
                        temporalFrameState.previous(), frameParameters)) {
                    temporalFrameState.invalidate();
                    temporalSceneState.invalidate();
                    if (instanced != null) instanced.invalidatePreviousFrame();
                }
            }
            generation.postProcess.beginFrame(
                    activeFrameContext.deltaSeconds(), activeFrameContext.camera(),
                    activeFrameContext.width(), activeFrameContext.height(),
                    activeFrameContext.frameIndex(), frameParameters,
                    temporalActive ? temporalFrameState.previous() : null);
            postProcessFrameStarted = true;
            FrameInvalidation invalidation = activeFrameContext.invalidation();
            if (generation.previewRenderer != null) {
                generation.previewRenderer.prepare(device, previewFrameSequence);
            }
            // Membership changes need no global reset: new renderers start with
            // an invalid snapshot and removed renderers are pruned on commit.
            // Freeze renderer membership, model matrices, bounds and queues before any graph
            // callback can observe mutable Scene inputs.
            frameStage = "frame-snapshot";
            SceneFrame builtFrame = sceneFrame();
            if (generation.sceneSurfacePass != null) {
                temporalSceneState.beginFrame(builtFrame);
            }
            activeFrameContext.verifySceneStable(scene, topologySettingsRevision);
            if (generation.volumetric != null) {
                generation.volumetric.prepareTransparentVariants(builtFrame);
                generation.volumetric.shadowPolicy(directionalShadowMap.settings(),localShadowSettings,directionalCascadeSettings);
                generation.volumetric.prepareFrame(activeFrameContext, volumetricFogSettings,currentShadowFramePlan);
                generation.volumetric.rasterProjection(temporalFrameState.current().inverseJitteredProjection());
            }
            generation.postProcess.synchronizeTemporalImports(generation.graph);
            long preparationTotalNanos = benchmarkCpuTimingEnabled
                    ? System.nanoTime() - preparationStart : 0L;
            frameStage = "pass-callback";
            PresentationResult result = generation.graph.execute(device, target);
            if (benchmarkCpuTimingEnabled) {
                long preparationOnly = Math.max(0L, preparationTotalNanos - currentLightPackNanos);
                lastBenchmarkCpuTiming = new BenchmarkCpuTiming(true, preparationOnly,
                        currentLightPackNanos, generation.graph.lastGraphRecordNanos(),
                        generation.graph.lastDeviceSubmitNanos());
            }
            frameStage = "frame-finalize";
            long commandRecordNanos = 0L;
            for (var pass : generation.graph.lastFrameProfile().passes()) {
                commandRecordNanos = Math.addExact(commandRecordNanos, pass.cpuRecordNanos());
            }
            if (currentSceneFrame != null) {
                int shadowVisibleOverride = -1;
                if (currentShadowCasterPlan != null && currentShadowFramePlan != null
                        && currentShadowFramePlan.directional().isPresent()
                        && currentShadowFramePlan.points().isEmpty()
                        && currentShadowFramePlan.spots().isEmpty()
                        && !directionalCascadeSettings.enabled()) {
                    // Legacy directional visibility statistics now come from
                    // the padded planner view; the shared SceneFrame queue is
                    // intentionally no longer pre-culled by an unpadded frustum.
                    shadowVisibleOverride = currentShadowCasterPlan.count(0);
                }
                lastVisibilityStatistics = VisibilityStatistics.from(currentSceneFrame.statistics,
                        commandRecordNanos, generation.graph.lastRecordedCommandCount(),
                        generation.graph.lastRecordedMatrixSnapshots(),
                        generation.graph.lastRecordedObjectPayloads(), shadowVisibleOverride);
            }
            // Fallible finalization first: nothing is published until every
            // temporal owner has completed its potentially failing work.
            generation.postProcess.prepareFrameSuccess();
            if (generation.volumetric != null) generation.volumetric.prepareFrameSuccess();
            if (generation.sceneSurfacePass != null) {
                temporalSceneState.prepareFinalization();
            }
            // Infallible publish phase.
            generation.postProcess.frameSucceeded();
            if (generation.volumetric != null) generation.volumetric.frameSucceeded();
            if (temporalActive) {
                temporalFrameState.commitSuccessfulFrame();
            }
            if (generation.sceneSurfacePass != null) {
                temporalSceneState.commitSuccessfulFrame();
                if (instanced != null) instanced.commitFrame();
            }
            generation.shadowCache.frameSucceeded();
            generation.clusteredLightingBinder.frameSucceeded();
            if (currentShadowFramePlan != null) lastShadowFramePlan = currentShadowFramePlan;
            if (pendingDirectionalCascadeCount >= 0) {
                if (pendingDirectionalCascadeCount == 0) {
                    lastDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
                } else {
                    if (lastDirectionalCascadeCasterCounts.length
                            != pendingDirectionalCascadeCount) {
                        lastDirectionalCascadeCasterCounts =
                                new int[pendingDirectionalCascadeCount];
                    }
                    System.arraycopy(pendingDirectionalCascadeCasterCounts, 0,
                            lastDirectionalCascadeCasterCounts, 0,
                            pendingDirectionalCascadeCount);
                }
            }
            if (generation.previewRenderer != null) {
                generation.previewRenderer.frameSucceeded(previewFrameSequence);
            }
            lastFrameContext = activeFrameContext;
            lastFailedFrameContext = null;
            lastFrameTopologyRebuilt = topologyRebuiltPending;
            if (!topologyRebuiltPending) generationReuseCount++;
            topologyRebuiltPending = false;
            lastFailureStage = "";
            return result;
        } catch (RuntimeException | Error failure) {
            // The integration-only executor fault is raised after a real draw,
            // so expose that boundary distinctly from pass-recording callbacks.
            boolean injectedGpuFailure = Boolean.getBoolean(SHADOW_FAILURE_PROPERTY)
                    && SHADOW_FAILURE_MESSAGE.equals(failure.getMessage());
            lastFailureStage = injectedGpuFailure ? "gpu-execute" : frameStage;
            lastFailedFrameContext = activeFrameContext;
            if (instanced != null) {
                try {
                    instanced.abortFrame();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (postProcessFrameStarted) generation.postProcess.frameFailed();
            if (generation.volumetric != null) generation.volumetric.frameFailed();
            temporalFrameState.discardFrame();
            if (generation.sceneSurfacePass != null) {
                temporalSceneState.discardFrame();
            }
            generation.shadowCache.frameFailed();
            generation.clusteredLightingBinder.frameFailed();
            if (generation.previewRenderer != null) {
                generation.previewRenderer.frameFailed(failure);
            }
            throw failure;
        } finally {
            executing = false;
            activeFrameContext = null;
        }
    }

    public void resize(int w, int h) {
        if (executing) {
            throw new IllegalStateException("pipeline resize is only allowed at frame start");
        }
        if (embedded) {
            try (HostGlState ignored = HostGlState.capture()) {
                resizeInternal(w, h);
            }
            return;
        }
        resizeInternal(w, h);
    }

    private void resizeInternal(int w, int h) {
        if (w <= 0 || h <= 0) return;
        PipelineGeneration current = activeGeneration;
        if (current == null || current.graph.width() == w && current.graph.height() == h) return;
        current.resize(w, h);
        currentSceneFrame = null;
        currentShadowFramePlan = null;
        currentShadowCasterPlan = null;
        topologySettingsRevision++;
    }

    public List<Matrix4f> lastDirectionalCascadeMatrices() {
        return lastDirectionalCascadeMatrices.stream().map(Matrix4f::new).toList();
    }

    public void close() {
        if (embedded) {
            try (HostGlState ignored = HostGlState.capture()) {
                closeGraphResources();
            }
        } else {
            closeGraphResources();
        }
    }

    private void closeGraphResources() {
        PipelineGeneration generation = activeGeneration;
        activeGeneration = null;
        List<RenderDevice> localDevices = List.copyOf(usedDevices);
        usedDevices.clear();
        frameStateInvalidationByMaterial.clear();
        currentSceneFrame = null;
        currentShadowFramePlan = null;
        currentShadowCasterPlan = null;
        lastDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
        pendingDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
        pendingDirectionalCascadeCount = -1;
        shadowCasterPlanner.reset();
        lastShadowFramePlan = ShadowFramePlan.EMPTY;
        lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;

        RuntimeException failure = null;
        failure = closeCollecting(generation, failure);
        for (RenderDevice device : localDevices) {
            try {
                device.invalidateState();
            } catch (RuntimeException invalidateFailure) {
                if (failure == null) failure = invalidateFailure;
                else failure.addSuppressed(invalidateFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void activateGeneration(PipelineGeneration candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.isClosed()) {
            throw new IllegalArgumentException("cannot activate a closed PipelineGeneration");
        }
        PipelineGeneration retired = activeGeneration;
        activeGeneration = candidate;
        currentSceneFrame = null;
        currentShadowFramePlan = null;
        currentShadowCasterPlan = null;
        lastDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
        pendingDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
        pendingDirectionalCascadeCount = -1;
        shadowCasterPlanner.reset();
        frameStateInvalidationByMaterial.clear();
        topologySettingsRevision++;
        topologyRebuiltPending = true;
        if (retired != null) lastRetiredGenerationId = retired.id;
        retireGeneration(retired);
    }

    private void retireGeneration(PipelineGeneration generation) {
        if (generation == null) return;
        RuntimeException failure = null;
        failure = closeCollecting(generation, failure);
        for (RenderDevice device : List.copyOf(usedDevices)) {
            try {
                device.invalidateState();
            } catch (RuntimeException invalidateFailure) {
                if (failure == null) failure = invalidateFailure;
                else failure.addSuppressed(invalidateFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private PipelineGeneration requireGeneration() {
        PipelineGeneration generation = activeGeneration;
        if (generation == null || generation.isClosed()) {
            throw new IllegalStateException("RenderPipeline must be built and open");
        }
        return generation;
    }

    private void initializeIblOverrides(PbrEnvironment environment) {
        iblIntensity = environment == null ? 0.0f : environment.intensity();
        iblRotationRadians = environment == null ? 0.0f : environment.rotationRadians();
    }

    private void applyIblOverrides(PipelineGeneration generation) {
        if (generation.pbrMaterialBinder != null) {
            generation.pbrMaterialBinder.visualOverride(iblIntensity, iblRotationRadians);
        }
        if (generation.environmentBackground != null) {
            generation.environmentBackground.visualOverride(iblIntensity, iblRotationRadians);
        }
    }

    private static boolean outdoorPassShapeChanged(PipelineGeneration generation,
                                                   OutdoorEnvironmentSettings environment) {
        return (generation.stylizedSky != null) != environment.enabled();
    }

    private static RuntimeException closeCollecting(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            if (failure == null) {
                return cleanupFailure instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException("Failed to close render resource", cleanupFailure);
            }
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private PassExecutor surfaceExecutor() {
        return (res, cmd) -> {
            PipelineGeneration generation = requireGeneration();
            if (generation.sceneSurfacePass == null) return;
            SceneFrame frame = sceneFrame();
            TemporalFrameState.FrameParameters current = temporalFrameState.current();
            if (current == null) {
                throw new IllegalStateException(
                        "scene surface pass requires a prepared temporal frame state");
            }
            // Upload the single shared camera block before recording the
            // surface draws; the forward geometry pass reuses it.
            generation.cameraUniforms.update(cmd, frameCamera(), frameWidth(), frameHeight(),
                    settings.antiAliasingMode(), activeFrameIndex);
            generation.sceneSurfacePass.record(cmd, frame, temporalSceneState, current,
                    temporalFrameState.previous(), instanced, activeFrameIndex,
                    generation.cameraUniforms);
        };
    }

    private PassExecutor reactiveExecutor() {
        return (res, cmd) -> {
            PipelineGeneration generation = requireGeneration();
            if (generation.volumetric!=null) volumetricReactiveExecutor().execute(res,cmd);
            if (generation.sceneReactivePass == null) return;
            generation.sceneReactivePass.record(cmd, sceneFrame(), generation.cameraUniforms);
        };
    }

    private PassExecutor surfaceResolveExecutor() {
        return (res, cmd) -> {
            PipelineGeneration generation = requireGeneration();
            if (generation.sceneSurfaceResolvePass == null) return;
            generation.sceneSurfaceResolvePass.record(cmd, res,
                    Math.max(2, settings.msaaSamples()));
        };
    }

    private TemporalFrameState.FrameParameters captureFrameParameters(RenderFrameContext context) {
        Camera camera = context.camera();
        int width = context.width();
        int height = context.height();
        Matrix4f stableProjection = CameraProjection.stable(camera, width, height, new Matrix4f());
        Matrix4f jitteredProjection = new Matrix4f(stableProjection);
        TemporalJitter.applyProjection(jitteredProjection, width, height,
                settings.antiAliasingMode(), context.frameIndex());
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f stableViewProjection = new Matrix4f(stableProjection).mul(view);
        Matrix4f inverseStableViewProjection = new Matrix4f(stableViewProjection).invert();
        Matrix4f inverseJitteredProjection = new Matrix4f(jitteredProjection).invert();
        Vector2f jitterUv = TemporalJitter.uvOffset(settings.antiAliasingMode(),
                context.frameIndex(), width, height, new Vector2f());
        float nearPlane;
        float farPlane;
        Vector3f forward;
        if (camera instanceof ExternalCamera external) {
            nearPlane = external.nearPlane();
            farPlane = external.farPlane();
            forward = external.inverseView().transformDirection(0.0f, 0.0f, -1.0f,
                    new Vector3f()).normalize();
        } else {
            nearPlane = CameraProjection.NEAR_PLANE;
            farPlane = CameraProjection.FAR_PLANE;
            forward = camera.front();
        }
        Vector3f position = camera.position();
        return new TemporalFrameState.FrameParameters(context.frameSequence(), width, height,
                nearPlane, farPlane, position.x, position.y, position.z,
                forward.x, forward.y, forward.z, jitterUv.x, jitterUv.y,
                stableProjection, jitteredProjection, inverseJitteredProjection, view,
                stableViewProjection, inverseStableViewProjection,
                context.revisions().cameraRevision());
    }

    /** Drops all temporal history (camera cut, resize, scene replacement, explicit reset). */
    public void resetTemporalHistory() {        temporalFrameState.invalidate();
        temporalSceneState.invalidate();
        if (instanced != null) instanced.invalidatePreviousFrame();
        if (activeGeneration != null) {
            activeGeneration.postProcess.invalidateTemporalHistory();
            if (activeGeneration.volumetric != null) activeGeneration.volumetric.invalidateHistory();
        }
    }

    /** Test/diagnostic access to committed temporal frame state. */
    TemporalFrameState temporalFrameStateForTest() {
        return temporalFrameState;
    }

    /** Test/diagnostic access to committed temporal scene state. */
    TemporalSceneState temporalSceneStateForTest() {
        return temporalSceneState;
    }

    /** Test/diagnostic access to the active generation. */
    PipelineGeneration activeGenerationForTest() {
        return activeGeneration;
    }

    private PassExecutor geometryExecutor() {
        return (res, cmd) -> {
            if (Boolean.getBoolean("haikalat.test.failTemporalAfterSurface")) {
                System.clearProperty("haikalat.test.failTemporalAfterSurface");
                throw new IllegalStateException(
                        "injected temporal frame failure after surface recording");
            }
            copyHostAttachments(res, cmd);
            ShadowFramePlan plan = shadowFramePlan();
            int gtaoTexture = requireGeneration().topology.gtaoEnabled()
                    ? res.colorAttachment(PostProcessTargets.GTAO_FINAL) : 0;
            renderScene(cmd,
                plan.directional().isPresent()
                        ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                !plan.points().isEmpty()
                        ? res.depthAttachment(PointShadowAtlas.TEXTURE_NAME) : 0,
                !plan.spots().isEmpty()
                        ? res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME) : 0,
                gtaoTexture,requireGeneration().volumetric == null ? 0 : 1);
        };
    }

    private void recordVolumeShadowInputs(PipelineGeneration generation,
                                          com.kaleblangley.haikalat.core.graph.PassResources res,
                                          CommandBuffer cmd, ShaderProgram shader) {
        ShadowFramePlan plan = shadowFramePlan();
        int directional = plan.directional().isPresent() ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0;
        int point = plan.points().isEmpty() ? 0 : res.depthAttachment(PointShadowAtlas.TEXTURE_NAME);
        int spot = plan.spots().isEmpty() ? 0 : res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME);
        boolean fineFog=shader.hasStorageBlock("FineLightTableBlock");
        java.util.function.UnaryOperator<String> uniform=fineFog?VolumetricFogView::fineUniform:java.util.function.UnaryOperator.identity();
        cmd.bindTexture(7,directional).bindTexture(11,point).bindTexture(12,spot)
                .setUniformInt(shader,uniform.apply("uHasDirectionalShadow"),directional != 0 ? 1 : 0)
                .setUniformInt(shader,uniform.apply("uHasPointShadow"),point != 0 ? 1 : 0)
                .setUniformInt(shader,uniform.apply("uHasSpotShadow"),spot != 0 ? 1 : 0)
                .setUniformFloat(shader,uniform.apply("uVolumeDirectionalBias"),directionalShadowMap.settings().bias())
                .setUniformFloat(shader,uniform.apply("uVolumePointBias"),localShadowSettings.point().bias())
                .setUniformFloat(shader,uniform.apply("uVolumeSpotBias"),localShadowSettings.spot().bias());
        if (generation.shadowSamplingBlock != null) generation.shadowSamplingBlock.bind(cmd);
        generation.shadowFrameBinder.bind(cmd, shader, activeFrameContext.camera(),
                lastDirectionalLightSpaceMatrix, lastDirectionalCascadeMatrices, lastDirectionalCascadeSplits,
                directionalCascadeSettings, plan,fineFog);
        for (int index=0;index<4;index++) {
            ShadowTileRect rect = plan.directional().isPresent()
                    ? plan.directional().orElseThrow().tiles().get(Math.min(index,plan.directional().orElseThrow().tiles().size()-1))
                    : new ShadowTileRect(0,0,1,1,0,0,1,1);
            cmd.setUniformVec4(shader,uniform.apply("uVolumeCascadeRects["+index+"]"),new Vector4f(rect.minU(),rect.minV(),rect.maxU(),rect.maxV()));
        }
    }

    private PassExecutor volumetricTransparentExecutor() {
        return (res,cmd)-> {
            requireGeneration().volumetric.fragmentInputs(res);
            var plan=shadowFramePlan();
            cmd.sampleShading(requireGeneration().topology.sampleCount()>1,requireGeneration().topology.sampleCount()>1 ? 1 : 0);
            renderScene(cmd,plan.directional().isPresent() ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                    plan.points().isEmpty() ? 0 : res.depthAttachment(PointShadowAtlas.TEXTURE_NAME),
                    plan.spots().isEmpty() ? 0 : res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME),0,2);
            if (hdrVfxRecorder != null) hdrVfxRecorder.execute(res,cmd);
            cmd.sampleShading(false,0).enableBlend(false).depthMask(true).enableDepthTest(true);
        };
    }

    private PassExecutor volumetricReactiveExecutor() {
        return (res,cmd)-> {
            requireGeneration().volumetric.fragmentInputs(res);
            var generation=requireGeneration(); var plan=shadowFramePlan();
            generation.volumetric.recordReactive(res,cmd);
            renderScene(cmd,plan.directional().isPresent() ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                    plan.points().isEmpty() ? 0 : res.depthAttachment(PointShadowAtlas.TEXTURE_NAME),
                    plan.spots().isEmpty() ? 0 : res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME),0,3);
            generation.volumetric.recordReactiveVfx(cmd);
        };
    }

    private void synchronizeOutdoorSun(Scene targetScene) {
        if (!outdoorEnvironment.enabled()) return;
        List<SceneLight> lights = targetScene.lights();
        for (int i = 0; i < lights.size(); i++) {
            SceneLight light = lights.get(i);
            if (light.type() == LightType.DIRECTIONAL && light.castShadows()) {
                StylizedSkySettings sky = outdoorEnvironment.sky();
                SceneLight replacement = SceneLight.shadowedDirectional(
                        sky.sunDirection(), sky.sunColor(), sky.sunIntensity());
                if (!replacement.equals(light)) targetScene.setLight(i, replacement);
                return;
            }
        }
    }

    private SceneLight firstDirectionalLight() {
        for (SceneLight light : scene.lights()) {
            if (light.type() == LightType.DIRECTIONAL) return light;
        }
        return null;
    }

    private void applyProfileDirectionalLight(VisualSettings replacement) {
        List<SceneLight> lights = scene.lights();
        for (int index = 0; index < lights.size(); index++) {
            SceneLight current = lights.get(index);
            if (current.type() != LightType.DIRECTIONAL) continue;
            if (current.direction().equals(replacement.directionalLightDirection())
                    && current.color().equals(replacement.directionalLightColor())
                    && Float.compare(current.intensity(), replacement.directionalLightIntensity()) == 0) return;
            SceneLight updated = new SceneLight(LightType.DIRECTIONAL,
                    replacement.directionalLightColor(),
                    replacement.directionalLightIntensity(),
                    replacement.directionalLightDirection(), current.position(),
                    current.range(), current.innerConeRadians(), current.outerConeRadians(),
                    current.castShadows());
            if (!updated.equals(current)) scene.setLight(index, updated);
            return;
        }
        throw new IllegalStateException(
                "visual directional-light update requires an existing directional light");
    }

    private PassExecutor gtaoDepthExecutor() {
        return (res, cmd) -> {
            PipelineGeneration generation = requireGeneration();
            SceneFrame frame = sceneFrame();
            generation.cameraUniforms.update(cmd, frameCamera(), frameWidth(), frameHeight(),
                    settings.antiAliasingMode(), activeFrameIndex);
            ShaderProgram depthShader = generation.gtaoDepthShader;
            cmd.bindShader(depthShader)
                    .enableBlend(false).enableDepthTest(true).depthMask(true)
                    // Establish a deterministic raster state, then override it
                    // for every regular renderer from its Material.cullMode.
                    .frontFace(FrontFace.CCW).enableCullFace(true);
            generation.cameraUniforms.bind(depthShader);
            ShaderProgram boundShader = depthShader;
            com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
            boolean frontFaceBound = false;
            boolean cullBound = false;
            boolean boundMirrored = false;
            boolean boundCull = false;
            int boundSkinningEnabled = -1;
            int boundMorphTargetCount = -1;
            for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
                int entry = frame.forwardEntry(queueIndex);
                MeshRenderer renderer = frame.renderer(entry);
                if (!frame.castsOpaqueShadow(entry)) continue;
                boolean mirrored = frame.mirrored(entry);
                boolean cull = renderer.material().material().cullMode() == CullMode.BACK;
                if (!frontFaceBound || mirrored != boundMirrored) {
                    cmd.frontFace(mirrored ? FrontFace.CW : FrontFace.CCW);
                    frontFaceBound = true;
                    boundMirrored = mirrored;
                }
                if (!cullBound || cull != boundCull) {
                    cmd.enableCullFace(cull);
                    cullBound = true;
                    boundCull = cull;
                }
                ShaderProgram shader = frame.masked(entry)
                        ? generation.gtaoMaskedDepthShader : generation.gtaoDepthShader;
                if (shader != boundShader) {
                    cmd.bindShader(shader);
                    generation.cameraUniforms.bind(shader);
                    boundShader = shader;
                    boundSkinningEnabled = -1;
                    boundMorphTargetCount = -1;
                }
                if (shader == generation.gtaoMaskedDepthShader) {
                    bindGtaoMaskedMaterial(cmd, shader, renderer.material());
                }
                cmd.setUniformMat4(shader, "uModel", frame.model(entry));
                SceneDrawBinding binding = renderer.drawBinding();
                int skinningEnabled = binding.skinningEnabled() ? 1 : 0;
                int morphTargetCount = binding.morphTargetCount();
                if (skinningEnabled != boundSkinningEnabled) {
                    cmd.trySetUniformInt(shader, "uSkinningEnabled", skinningEnabled);
                    boundSkinningEnabled = skinningEnabled;
                }
                if (morphTargetCount != boundMorphTargetCount) {
                    cmd.trySetUniformInt(shader, "uMorphTargetCount", morphTargetCount);
                    boundMorphTargetCount = morphTargetCount;
                }
                if (binding != SceneDrawBinding.NONE) {
                    binding.record(cmd, shader, activeFrameIndex, SceneDrawBinding.Pass.DEPTH_PREPASS);
                }
                if (renderer.mesh() != boundMesh) {
                    cmd.bindMesh(renderer.mesh());
                    boundMesh = renderer.mesh();
                }
                cmd.drawMesh(renderer.mesh());
                generation.postProcess.recordGtaoDepthPrepassDraw();
            }
            if (instanced != null && generation.gtaoInstancedDepthShader != null) {
                // Instanced depth uses its own winding convention; do not inherit
                // a mirrored/cull state from the last regular mesh.
                cmd.enableCullFace(false).frontFace(FrontFace.CCW);
                cmd.bindShader(generation.gtaoInstancedDepthShader);
                generation.cameraUniforms.bind(generation.gtaoInstancedDepthShader);
                instanced.renderDepth(cmd, generation.gtaoInstancedDepthShader);
                generation.postProcess.recordGtaoDepthPrepassDraw();
            }
        };
    }

    private void bindGtaoMaskedMaterial(CommandBuffer cmd, ShaderProgram shader,
                                        MaterialInstance instance) {
        Material material = instance.material();
        Material.TextureBinding baseColor = instance.textureOverrides().get(0);
        if (baseColor == null) {
            for (Material.TextureBinding binding : material.defaultTextures()) {
                if (binding.unit() == 0) { baseColor = binding; break; }
            }
        }
        if (baseColor != null) cmd.bindTexture(0, baseColor.texture(), baseColor.sampler());
        UniformValue cutoff = instance.uniformOverrides().get(UniformKey.float1("uAlphaCutoff"));
        if (cutoff == null) cutoff = material.defaultUniforms().get(UniformKey.float1("uAlphaCutoff"));
        UniformValue factor = instance.uniformOverrides().get(UniformKey.vec4("uBaseColorFactor"));
        if (factor == null) factor = material.defaultUniforms().get(UniformKey.vec4("uBaseColorFactor"));
        float cutoffValue = cutoff instanceof UniformValue.FloatVal value ? value.value() : 0.5f;
        Vector4f factorValue = factor instanceof UniformValue.Vec4Val value
                ? value.value() : new Vector4f(1.0f);
        cmd.setUniformInt(shader, "uBaseColorMap", 0)
                .setUniformFloat(shader, "uAlphaCutoff", cutoffValue)
                .setUniformVec4(shader, "uBaseColorFactor", factorValue);
    }

    private void copyHostAttachments(com.kaleblangley.haikalat.core.graph.PassResources resources,
                                     CommandBuffer commands) {
        PresentationTarget target = resources.framePresentationTarget();
        com.kaleblangley.haikalat.backend.framebuffer.Framebuffer geometry =
                resources.currentTarget();
        if (target == null || geometry == null
                || target.framebufferOwnership() != ResourceOwnership.BORROWED
                || target.readFramebufferId() == 0) {
            return;
        }
        if (target.color().isPresent()) {
            commands.blitFramebuffer(target.readFramebufferId(), geometry.id(),
                    target.width(), target.height(), geometry.width(), geometry.height());
        }
        if (target.hasDepth()) {
            int geometrySamples = settings.antiAliasingMode() == AntiAliasingMode.MSAA
                    ? Math.max(2, settings.msaaSamples()) : 1;
            if (target.samples() != geometrySamples) {
                throw new IllegalArgumentException("host depth samples " + target.samples()
                        + " do not match geometry samples " + geometrySamples);
            }
            commands.blitFramebuffer(target.readFramebufferId(), geometry.id(),
                    target.width(), target.height(), geometry.width(), geometry.height(),
                    org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    org.lwjgl.opengl.GL11.GL_NEAREST);
        }
        // Typed blits deliberately leave the command executor at framebuffer 0.
        // Re-establish this managed pass target before geometry recording continues.
        commands.bindFramebuffer(geometry)
                .viewport(0, 0, geometry.width(), geometry.height());
    }

    private void synchronizeHostImports(RenderGraph graph, PresentationTarget target) {
        if (target.framebufferOwnership() == ResourceOwnership.OWNED) {
            graph.removeExternalAttachment(HOST_COLOR_IMPORT);
            graph.removeExternalAttachment(HOST_DEPTH_IMPORT);
            graph.removeExternalAttachment(HOST_STENCIL_IMPORT);
            return;
        }
        target.color().ifPresentOrElse(
                attachment -> graph.importExternalColor(HOST_COLOR_IMPORT, attachment),
                () -> graph.removeExternalAttachment(HOST_COLOR_IMPORT));
        target.depth().ifPresentOrElse(
                attachment -> graph.importExternalDepth(HOST_DEPTH_IMPORT, attachment),
                () -> graph.removeExternalAttachment(HOST_DEPTH_IMPORT));
        target.stencil().ifPresentOrElse(
                attachment -> graph.importExternalStencil(HOST_STENCIL_IMPORT, attachment),
                () -> graph.removeExternalAttachment(HOST_STENCIL_IMPORT));
    }

    private PassExecutor shadowExecutor() {
        return (res, cmd) -> shadowFramePlan().directional().ifPresent(plan -> {
            PipelineGeneration generation = requireGeneration();
            ShaderProgram shadowShader = generation.shadowShader;
            SceneFrame frame = sceneFrame();
            int atlasSize = directionalCascadeSettings.enabled()
                    ? directionalCascadeSettings.atlasSize()
                    : directionalShadowMap.settings().resolution();
            boolean preserveTiles = localShadowSettings.cacheStaticTiles();
            if (preserveTiles && !generation.shadowCache.directionalAtlasInitialized()) {
                cmd.enableScissor(false).viewport(0, 0, atlasSize, atlasSize)
                        .depthMask(true).clear(false, true);
            }
            cmd.bindShader(shadowShader)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    // 基准场景包含双面平面，因此阴影 pass 显式关闭剔除。
                    .enableCullFace(false);
            int count = 0;
            List<Matrix4f> matrices = plan.matrices();
            for (int cascade = 0; cascade < matrices.size(); cascade++) {
                if (!plan.dirtyTiles().get(cascade)) continue;
                ShadowTileRect tile = plan.tiles().get(cascade);
                boolean empty = currentShadowCasterPlan != null
                        && currentShadowCasterPlan.count(cascade) == 0;
                if (preserveTiles || empty) {
                    cmd.enableScissor(true)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                            .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                            .clear(false, true);
                } else {
                    cmd.enableScissor(false)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height());
                }
                int view = cascade;
                count += recordDirectionalShadowCasters(cmd, frame, matrices.get(cascade), view);
                if (instanced != null && instanced.castShadows()
                        && (currentShadowCasterPlan == null
                        || currentShadowCasterPlan.instanceVisible(view))) {
                    ShaderProgram instancedShadowShader = generation.instancedShadowShader;
                    cmd.bindShader(instancedShadowShader)
                            .setUniformMat4(instancedShadowShader, "uLightSpace", matrices.get(cascade));
                    instanced.renderShadow(cmd);
                }
            }
            cmd.enableScissor(false);
            lastShadowCasterDrawCount = count;
        });
    }

    private int recordDirectionalShadowCasters(CommandBuffer cmd, SceneFrame frame,
                                                Matrix4f lightSpace, int view) {
        ShaderProgram boundShader = requireGeneration().shadowShader;
        cmd.bindShader(boundShader).setUniformMat4(boundShader, "uLightSpace", lightSpace);
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        int count = currentShadowCasterPlan == null ? frame.shadowCount
                : currentShadowCasterPlan.count(view);
        int candidateRank = currentShadowCasterPlan == null ? -1
                : currentShadowCasterPlan.firstCandidateRank(view);
        for (int queueIndex = 0; queueIndex < count; queueIndex++) {
            int entry = currentShadowCasterPlan == null
                    ? frame.shadowEntry(queueIndex)
                    : currentShadowCasterPlan.casterAtRank(candidateRank);
            if (currentShadowCasterPlan != null) {
                candidateRank = currentShadowCasterPlan.nextCandidateRank(view, candidateRank);
            }
            MeshRenderer renderer = frame.renderer(entry);
            ShaderProgram shader = shadowShaderFor(renderer);
            if (shader != boundShader) {
                cmd.bindShader(shader).setUniformMat4(shader, "uLightSpace", lightSpace);
                boundShader = shader;
                boundSkinningEnabled = -1;
                boundMorphTargetCount = -1;
            }
            bindMaskedShadowMaterial(cmd, shader, renderer.material());
            cmd.setUniformMat4(shader, "uModel", frame.model(entry));
            SceneDrawBinding binding = renderer.drawBinding();
            int skinningEnabled = binding.skinningEnabled() ? 1 : 0;
            int morphTargetCount = binding.morphTargetCount();
            if (skinningEnabled != boundSkinningEnabled) {
                cmd.trySetUniformInt(shader, "uSkinningEnabled", skinningEnabled);
                boundSkinningEnabled = skinningEnabled;
            }
            if (morphTargetCount != boundMorphTargetCount) {
                cmd.trySetUniformInt(shader, "uMorphTargetCount", morphTargetCount);
                boundMorphTargetCount = morphTargetCount;
            }
            if (binding != SceneDrawBinding.NONE) {
                binding.record(cmd, shader, (int) frame.frameIndex, SceneDrawBinding.Pass.SHADOW);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }
        return count;
    }

    private PassExecutor pointShadowExecutor() {
        return (res, cmd) -> {
            ShadowFramePlan plan = shadowFramePlan();
            if (plan.points().isEmpty()) return;
            PipelineGeneration generation = requireGeneration();
            boolean preserveTiles = localShadowSettings.cacheStaticTiles();
            if (preserveTiles && !generation.shadowCache.pointAtlasInitialized()) {
                cmd.enableScissor(false).viewport(0, 0,
                                pointShadowAtlas.width(), pointShadowAtlas.height())
                        .depthMask(true).clear(false, true);
            }
            ShaderProgram shadowShader = requireGeneration().shadowShader;
            SceneFrame frame = sceneFrame();
            cmd.bindShader(shadowShader)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .enableCullFace(false);
            int draws = 0;
            for (PointShadowSlotPlan slot : plan.points()) {
                if (!slot.dirty()) continue;
                for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                    int view = 4 + slot.slot() * PointShadowAtlas.FACE_COUNT + face;
                    if (!slot.faceDirty(face)) continue;
                    ShadowTileRect tile = slot.faceTiles().get(face);
                    Matrix4f matrix = slot.faceMatrices().get(face);
                    boolean empty = currentShadowCasterPlan != null
                            && currentShadowCasterPlan.count(view) == 0;
                    if (preserveTiles || empty) {
                        cmd.enableScissor(true)
                                .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                                .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                                .clear(false, true);
                    } else {
                        cmd.enableScissor(false)
                                .viewport(tile.x(), tile.y(), tile.width(), tile.height());
                    }
                    cmd.setUniformMat4(shadowShader, "uLightSpace", matrix);
                    draws += recordAllShadowCasters(cmd, frame, matrix, view);
                    if (instanced != null && instanced.castShadows()
                            && (currentShadowCasterPlan == null
                            || currentShadowCasterPlan.instanceVisible(view))) {
                        ShaderProgram instancedShadowShader = generation.instancedShadowShader;
                        cmd.bindShader(instancedShadowShader)
                                .setUniformMat4(instancedShadowShader, "uLightSpace", matrix);
                        instanced.renderShadow(cmd);
                    }
                }
            }
            cmd.enableScissor(false);
            lastPointShadowCasterDrawCount = draws;
        };
    }

    private PassExecutor spotShadowExecutor() {
        return (res, cmd) -> {
            ShadowFramePlan plan = shadowFramePlan();
            if (plan.spots().isEmpty()) return;
            PipelineGeneration generation = requireGeneration();
            boolean preserveTiles = localShadowSettings.cacheStaticTiles();
            if (preserveTiles && !generation.shadowCache.spotAtlasInitialized()) {
                cmd.enableScissor(false).viewport(0, 0,
                                spotShadowAtlas.width(), spotShadowAtlas.height())
                        .depthMask(true).clear(false, true);
            }
            ShaderProgram shadowShader = requireGeneration().shadowShader;
            SceneFrame frame = sceneFrame();
            cmd.bindShader(shadowShader)
                    .enableBlend(false)
                    .enableDepthTest(true)
                    .depthMask(true)
                    .enableCullFace(false);
            int draws = 0;
            for (SpotShadowSlotPlan slot : plan.spots()) {
                if (!slot.dirty()) continue;
                ShadowTileRect tile = slot.tile();
                int view = 16 + slot.slot();
                boolean empty = currentShadowCasterPlan != null
                        && currentShadowCasterPlan.count(view) == 0;
                if (preserveTiles || empty) {
                    cmd.enableScissor(true)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                            .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                            .clear(false, true);
                } else {
                    cmd.enableScissor(false)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height());
                }
                cmd.setUniformMat4(shadowShader, "uLightSpace", slot.lightSpaceMatrix());
                draws += recordAllShadowCasters(cmd, frame, slot.lightSpaceMatrix(), view);
                if (instanced != null && instanced.castShadows()
                        && (currentShadowCasterPlan == null
                        || currentShadowCasterPlan.instanceVisible(view))) {
                    ShaderProgram instancedShadowShader = generation.instancedShadowShader;
                    cmd.bindShader(instancedShadowShader)
                            .setUniformMat4(instancedShadowShader, "uLightSpace",
                                    slot.lightSpaceMatrix());
                    instanced.renderShadow(cmd);
                }
            }
            cmd.enableScissor(false);
            lastSpotShadowCasterDrawCount = draws;
        };
    }

    private int recordAllShadowCasters(CommandBuffer cmd, SceneFrame frame, Matrix4f lightSpace,
                                       int view) {
        ShaderProgram boundShader = requireGeneration().shadowShader;
        // Point faces and spot tiles are recorded back-to-back.  The previous view
        // may have ended with the masked or instanced program bound, so DSA uniform
        // updates alone are not enough to select the ordinary depth shader for the
        // first opaque caster in this view.
        cmd.bindShader(boundShader).setUniformMat4(boundShader, "uLightSpace", lightSpace);
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        int draws = 0;
        int count = currentShadowCasterPlan == null ? frame.rendererCount()
                : currentShadowCasterPlan.count(view);
        int candidateRank = currentShadowCasterPlan == null ? -1
                : currentShadowCasterPlan.firstCandidateRank(view);
        for (int index = 0; index < count; index++) {
            int entry = currentShadowCasterPlan == null
                    ? index : currentShadowCasterPlan.casterAtRank(candidateRank);
            if (currentShadowCasterPlan != null) {
                candidateRank = currentShadowCasterPlan.nextCandidateRank(view, candidateRank);
            }
            MeshRenderer renderer = frame.renderer(entry);
            if (!renderer.castShadows()
                    || !RenderQueueClass.classify(renderer.material()).castsOpaqueShadow()) {
                continue;
            }
            ShaderProgram entryShader = shadowShaderFor(renderer);
            if (entryShader != boundShader) {
                cmd.bindShader(entryShader).setUniformMat4(entryShader, "uLightSpace", lightSpace);
                boundShader = entryShader;
                boundSkinningEnabled = -1;
                boundMorphTargetCount = -1;
            }
            bindMaskedShadowMaterial(cmd, entryShader, renderer.material());
            cmd.setUniformMat4(entryShader, "uModel", frame.model(entry));
            SceneDrawBinding drawBinding = renderer.drawBinding();
            int skinningEnabled = drawBinding.skinningEnabled() ? 1 : 0;
            int morphTargetCount = drawBinding.morphTargetCount();
            if (skinningEnabled != boundSkinningEnabled) {
                cmd.trySetUniformInt(entryShader, "uSkinningEnabled", skinningEnabled);
                boundSkinningEnabled = skinningEnabled;
            }
            if (morphTargetCount != boundMorphTargetCount) {
                cmd.trySetUniformInt(entryShader, "uMorphTargetCount", morphTargetCount);
                boundMorphTargetCount = morphTargetCount;
            }
            if (drawBinding != SceneDrawBinding.NONE) {
                drawBinding.record(cmd, entryShader, (int) frame.frameIndex,
                        SceneDrawBinding.Pass.SHADOW);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
            draws++;
        }
        return draws;
    }

    private ShaderProgram shadowShaderFor(MeshRenderer renderer) {
        return RenderQueueClass.classify(renderer.material()) == RenderQueueClass.MASKED
                ? requireGeneration().maskedShadowShader : requireGeneration().shadowShader;
    }

    private void bindMaskedShadowMaterial(CommandBuffer cmd, ShaderProgram shader,
                                          MaterialInstance instance) {
        if (shader != requireGeneration().maskedShadowShader) return;
        Material material = instance.material();
        Material.TextureBinding baseColor = instance.textureOverrides().get(0);
        if (baseColor == null) {
            for (Material.TextureBinding binding : material.defaultTextures()) {
                if (binding.unit() == 0) { baseColor = binding; break; }
            }
        }
        if (baseColor != null) cmd.bindTexture(0, baseColor.texture(), baseColor.sampler());
        UniformValue cutoff = instance.uniformOverrides().get(UniformKey.float1("uAlphaCutoff"));
        if (cutoff == null) cutoff = material.defaultUniforms().get(UniformKey.float1("uAlphaCutoff"));
        UniformValue factor = instance.uniformOverrides().get(UniformKey.vec4("uBaseColorFactor"));
        if (factor == null) factor = material.defaultUniforms().get(UniformKey.vec4("uBaseColorFactor"));
        float cutoffValue = cutoff instanceof UniformValue.FloatVal value ? value.value() : 0.5f;
        Vector4f factorValue = factor instanceof UniformValue.Vec4Val value
                ? value.value() : new Vector4f(1.0f);
        cmd.setUniformInt(shader, "uBaseColorMap", 0)
                .setUniformFloat(shader, "uAlphaCutoff", cutoffValue)
                .setUniformVec4(shader, "uBaseColorFactor", factorValue);
    }

    private void renderScene(CommandBuffer cmd, int shadowTexture,
                             int pointShadowTexture, int spotShadowTexture,
                             int gtaoTexture,int segment) {
        PipelineGeneration generation = requireGeneration();
        SceneFrame frame = sceneFrame();
        boolean gtaoEnabled = generation.topology.gtaoEnabled();
        boolean sharedDepth = generation.topology.sceneBuffers().requiresSurfacePass();
        Camera camera = frameCamera();
        if (sharedDepth) {
            // The surface pass produced the shared depth; only fragments that
            // match the same surface may contribute color.
            cmd.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        }
        // The GTAO depth prepass or the shared surface pass is the first graph
        // pass that uploads the camera block.  Reusing it avoids a second
        // per-frame UBO update on the enabled paths.
        if (!gtaoEnabled && generation.sceneSurfacePass == null) {
            generation.cameraUniforms.update(cmd, camera, frameWidth(), frameHeight(),
                    settings.antiAliasingMode(), activeFrameIndex);
        }
        if (segment < 2 && generation.stylizedSky != null) {
            generation.stylizedSky.render(cmd, camera, frameWidth(), frameHeight(),
                    outdoorEnvironment.sky());
        } else if (segment < 2 && generation.environmentBackground != null) {
            generation.environmentBackground.render(cmd, camera, frameWidth(), frameHeight());
        }

        ShaderProgram boundShader = null;
        MaterialInstance boundMaterial = null;
        Material boundMaterialTemplate = null;
        boolean boundMaterialHasOverrides = false;
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean boundMirrored = false;
        int boundGtaoTexture = -1;
        int boundSkinningEnabled = -1;
        int boundMorphTargetCount = -1;
        for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            if (segment == 1 && !frame.castsOpaqueShadow(entry)
                    || segment >= 2 && frame.castsOpaqueShadow(entry)) continue;
            MeshRenderer renderer = frame.renderer(entry);
            Matrix4f model = frame.model(entry);
            MaterialInstance material = renderer.material();
            Material materialTemplate = material.material();
            if (segment==3 && materialTemplate.volumetricFogOptOut()) continue;
            ShaderProgram shader = segment >= 2 ? generation.volumetric.transparentShader(materialTemplate) : materialTemplate.shader();
            boolean castsOpaque = gtaoEnabled && frame.castsOpaqueShadow(entry);
            boolean materialHasOverrides = material.hasOverrides();
            boolean mirrored = frame.mirrored(entry);
            if (!frontFaceBound || mirrored != boundMirrored) {
                cmd.frontFace(mirrored ? FrontFace.CW : FrontFace.CCW);
                frontFaceBound = true;
                boundMirrored = mirrored;
            }
            boolean materialChanged = material != boundMaterial;
            boolean materialBindingChanged = materialChanged
                    && (boundMaterialTemplate != materialTemplate
                    || boundMaterialHasOverrides || materialHasOverrides);
            if (materialBindingChanged) {
                material.bind(cmd,shader);
                if (segment >= 2 && !materialTemplate.volumetricFogOptOut()) {
                    cmd.blendFunc(org.lwjgl.opengl.GL11.GL_ONE,materialTemplate.blendMode() == BlendMode.ADDITIVE
                            ? org.lwjgl.opengl.GL11.GL_ONE : org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA)
                            .setUniformInt(shader,"uVolumeAdditive",materialTemplate.blendMode()==BlendMode.ADDITIVE ? 1 : 0);
                }
            }
            boundMaterial = material;
            boundMaterialTemplate = materialTemplate;
            boundMaterialHasOverrides = materialHasOverrides;
            int materialGtaoTexture = castsOpaque
                    ? gtaoTexture : 0;
            boolean gtaoBindingChanged = gtaoEnabled && materialGtaoTexture != boundGtaoTexture;
            if (shader != boundShader
                    || materialBindingChanged && invalidatesFrameState(material)
                    || gtaoBindingChanged) {
                bindFrameState(shader, cmd, shadowTexture, pointShadowTexture, spotShadowTexture,
                        materialGtaoTexture);
                if (material.material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                    generation.pbrMaterialBinder.bind(shader, cmd);
                }
                boundShader = shader;
                if (gtaoEnabled) boundGtaoTexture = materialGtaoTexture;
                boundSkinningEnabled = -1;
                boundMorphTargetCount = -1;
                if (segment >= 2 && !materialTemplate.volumetricFogOptOut()) generation.volumetric.bindQuery(cmd,shader);
            }
            if (segment==3) cmd.setUniformInt(shader,"uVolumeReactiveOnly",1).enableBlend(true)
                    .blendEquation(org.lwjgl.opengl.GL14.GL_MAX).enableDepthTest(false).depthMask(false);
            cmd.setUniformMat4(shader, "uModel", model);
            SceneDrawBinding drawBinding = renderer.drawBinding();
            int skinningEnabled = drawBinding.skinningEnabled() ? 1 : 0;
            int morphTargetCount = drawBinding.morphTargetCount();
            if (skinningEnabled != boundSkinningEnabled) {
                cmd.trySetUniformInt(shader, "uSkinningEnabled", skinningEnabled);
                boundSkinningEnabled = skinningEnabled;
            }
            if (morphTargetCount != boundMorphTargetCount) {
                cmd.trySetUniformInt(shader, "uMorphTargetCount", morphTargetCount);
                boundMorphTargetCount = morphTargetCount;
            }
            if (drawBinding != SceneDrawBinding.NONE) {
                drawBinding.record(cmd, shader, (int) frame.frameIndex,
                        SceneDrawBinding.Pass.FORWARD);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }

        if (segment < 2 && instanced != null) {
            cmd.bindShader(instanced.shader());
            cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
            bindFrameState(instanced.shader(), cmd, shadowTexture,
                    pointShadowTexture, spotShadowTexture,
                    gtaoEnabled ? gtaoTexture : 0);
            instanced.render(cmd);
        }
    }

    private SceneFrame sceneFrame() {
        if (currentSceneFrame != null) return currentSceneFrame;

        RenderFrameContext context = requireFrameContext();
        PipelineGeneration generation = requireGeneration();
        FrameInvalidation invalidation = context.invalidation();
        Matrix4f view = context.camera().getViewMatrix(new Matrix4f());
        long lightPackNanos = 0L;
        long lightPackStart = benchmarkCpuTimingEnabled ? System.nanoTime() : 0L;
        currentLightTable = FrameLightTable.build(context.lightEntries(), view,
                clusteredLightingSettings);
        if (benchmarkCpuTimingEnabled) lightPackNanos += System.nanoTime() - lightPackStart;
        float jitterFootprint = settings.antiAliasingMode() == AntiAliasingMode.TAA ? 0.5f : 0.0f;
        currentClusterGrid = ClusterGrid.create(context.camera(), context.width(),
                context.height(), clusteredLightingSettings, jitterFootprint);
        boolean reschedule = lastShadowFramePlan == ShadowFramePlan.EMPTY
                || invalidation.invalidated(FrameInvalidation.Domain.LIGHTING)
                || invalidation.invalidated(FrameInvalidation.Domain.CAMERA)
                || invalidation.invalidated(FrameInvalidation.Domain.TOPOLOGY_SETTINGS);
        ShadowFramePlan selected = reschedule
                ? generation.shadowLightScheduler.plan(context.lightEntries(), currentLightTable,
                        context.camera(), context.width(), context.height(), localShadowSettings,
                        pointShadowAtlas, spotShadowAtlas, directionalShadowMap,
                        directionalCascadeSettings)
                : lastShadowFramePlan;
        if (selected.directional().isPresent()) {
            ShadowFramePlan.DirectionalPlan directional = selected.directional().orElseThrow();
            lastDirectionalCascadeMatrices = directional.matrices();
            lastDirectionalCascadeSplits = directional.splits();
            lastDirectionalLightSpaceMatrix.set(lastDirectionalCascadeMatrices.getFirst());
        } else {
            lastDirectionalLightSpaceMatrix.identity();
            lastDirectionalCascadeMatrices = List.of();
            lastDirectionalCascadeSplits = new float[0];
        }
        lastPointLightSpaceMatrices = selected.points().isEmpty() ? List.of()
                : selected.points().getFirst().faceMatrices();
        if (!selected.spots().isEmpty()) {
            lastSpotLightSpaceMatrix.set(selected.spots().getFirst().lightSpaceMatrix());
        } else {
            lastSpotLightSpaceMatrix.identity();
        }
        boolean anyShadowView = selected.directional().isPresent()
                || !selected.points().isEmpty() || !selected.spots().isEmpty();
        // The base shadow queue is also the deterministic candidate order consumed by
        // point/spot and per-cascade planning.  A local-light-only scene must not use the
        // identity directional matrix to cull that queue before its own view tests run.
        // The legacy single directional view may use the same padded frustum as its
        // planner; cascades and local views must remain unconditionally in the shared
        // queue so their individual padding is not defeated by an early test.
        boolean legacyDirectional = selected.directional().isPresent()
                && selected.points().isEmpty() && selected.spots().isEmpty()
                && !directionalCascadeSettings.enabled() && settings.sceneVisibility();
        boolean baseShadowCulling = legacyDirectional;
        float baseShadowCullingPadding = 0.0f;
        if (legacyDirectional) {
            var directional = selected.directional().orElseThrow();
            int last = directional.matrices().size() - 1;
            baseShadowCullingPadding = directional.texelWorldSizes()[last]
                    * (selected.filterMode().kernelRadius() + 2.0f);
        }
        SceneFrame built = generation.sceneFrameBuilder.build(scene, context.camera(),
                context.width(), context.height(), lastDirectionalCascadeMatrices.isEmpty()
                        ? lastDirectionalLightSpaceMatrix
                        : lastDirectionalCascadeMatrices.get(lastDirectionalCascadeMatrices.size() - 1),
                anyShadowView, settings.sceneVisibility(), baseShadowCulling,
                baseShadowCullingPadding, context.frameIndex());
        currentShadowCasterPlan = shadowCasterPlanner.plan(selected, context, built,
                settings.sceneVisibility(), instanced);
        if (selected.directional().isPresent()) {
            int cascadeCount = selected.directional().orElseThrow().matrices().size();
            if (pendingDirectionalCascadeCasterCounts.length < cascadeCount) {
                pendingDirectionalCascadeCasterCounts = new int[cascadeCount];
            }
            for (int cascade = 0; cascade < cascadeCount; cascade++) {
                pendingDirectionalCascadeCasterCounts[cascade] = currentShadowCasterPlan.count(cascade);
            }
            pendingDirectionalCascadeCount = cascadeCount;
        } else {
            pendingDirectionalCascadeCount = 0;
        }
        currentShadowFramePlan = generation.shadowCache.prepare(selected, context, scene,
                localShadowSettings, directionalCascadeSettings, currentShadowCasterPlan);
        recordShadowCullingCacheOutcome(currentShadowFramePlan, currentShadowCasterPlan);
        if (generation.shadowSamplingBlock != null) {
            generation.shadowSamplingBlock.update(currentShadowFramePlan, localShadowSettings);
        }
        lightPackStart = benchmarkCpuTimingEnabled ? System.nanoTime() : 0L;
        generation.clusteredLightingBinder.prepare(currentClusterGrid, currentLightTable,
                currentShadowFramePlan, context.frameSequence());
        if (benchmarkCpuTimingEnabled) lightPackNanos += System.nanoTime() - lightPackStart;
        currentLightPackNanos = lightPackNanos;
        currentSceneFrame = built;
        return built;
    }

    private void recordShadowCullingCacheOutcome(ShadowFramePlan plan,
                                                 ShadowCasterPlanner.ShadowCasterPlan culling) {
        int dirty = 0;
        int empty = 0;
        if (plan.directional().isPresent()) {
            ShadowFramePlan.DirectionalPlan directional = plan.directional().orElseThrow();
            for (int cascade = 0; cascade < directional.dirtyTiles().size(); cascade++) {
                if (!directional.dirtyTiles().get(cascade)) continue;
                dirty++;
                if (culling.count(cascade) == 0 && !culling.instanceVisible(cascade)) empty++;
            }
        }
        for (PointShadowSlotPlan point : plan.points()) {
            for (int face = 0; face < PointShadowAtlas.FACE_COUNT; face++) {
                if (!point.faceDirty(face)) continue;
                dirty++;
                int view = 4 + point.slot() * PointShadowAtlas.FACE_COUNT + face;
                if (culling.count(view) == 0 && !culling.instanceVisible(view)) {
                    empty++;
                }
            }
        }
        for (SpotShadowSlotPlan spot : plan.spots()) {
            if (!spot.dirty()) continue;
            dirty++;
            int view = 16 + spot.slot();
            if (culling.count(view) == 0 && !culling.instanceVisible(view)) empty++;
        }
        shadowCasterPlanner.recordCacheOutcome(dirty,
                Math.max(0, culling.activeViews() - dirty), empty);
    }

    private ShadowFramePlan shadowFramePlan() {
        sceneFrame();
        return currentShadowFramePlan == null ? ShadowFramePlan.EMPTY : currentShadowFramePlan;
    }

    private DirectionalCascadePlan directionalCascadePlan(Camera camera, int width, int height,
                                                           SceneLight light) {
        float near = camera instanceof ExternalCamera external
                ? external.nearPlane() : CameraProjection.NEAR_PLANE;
        float far = camera instanceof ExternalCamera external
                ? external.farPlane() : CameraProjection.FAR_PLANE;
        float verticalFov;
        float aspect;
        org.joml.Vector3f forward;
        if (camera instanceof ExternalCamera external) {
            Matrix4f projection = external.projection();
            verticalFov = 2.0f * (float) Math.atan(1.0f / projection.m11());
            aspect = projection.m11() / projection.m00();
            forward = external.inverseView().transformDirection(0.0f, 0.0f, -1.0f,
                    new org.joml.Vector3f()).normalize();
        } else {
            verticalFov = (float) Math.toRadians(camera.zoom());
            aspect = width / (float) height;
            forward = camera.front();
        }
        return DirectionalCascadePlan.create(camera.position(), forward, light.direction(),
                verticalFov, aspect, near, far, directionalCascadeSettings.cascadeCount(),
                directionalCascadeSettings.splitLambda(), directionalCascadeSettings.tileSize());
    }

    private void bindFrameState(ShaderProgram shader, CommandBuffer cmd, int shadowTexture,
                                int pointShadowTexture, int spotShadowTexture,
                                int gtaoTexture) {
        PipelineGeneration generation = requireGeneration();
        generation.cameraUniforms.bind(shader);
        RenderFrameContext context = requireFrameContext();
        ShadowFramePlan shadowPlan = shadowFramePlan();
        generation.shadowFrameBinder.bind(cmd, shader, context.camera(),
                lastDirectionalLightSpaceMatrix, lastDirectionalCascadeMatrices,
                lastDirectionalCascadeSplits, directionalCascadeSettings, shadowPlan);
        boolean hasShadow = shadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasDirectionalShadow", hasShadow ? 1 : 0)
                .trySetUniformInt(shader, "uShadowMap", SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uShadowBias", directionalShadowMap.settings().bias());
        if (hasShadow) {
            cmd.bindTexture(SHADOW_TEXTURE_UNIT, shadowTexture);
        }
        boolean hasPointShadow = pointShadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasPointShadow", hasPointShadow ? 1 : 0)
                .trySetUniformInt(shader, "uPointShadowMap", POINT_SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uPointShadowBias", localShadowSettings.point().bias());
        if (hasPointShadow) {
            cmd.bindTexture(POINT_SHADOW_TEXTURE_UNIT, pointShadowTexture);
        }
        boolean hasSpotShadow = spotShadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasSpotShadow", hasSpotShadow ? 1 : 0)
                .trySetUniformInt(shader, "uSpotShadowMap", SPOT_SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uSpotShadowBias", localShadowSettings.spot().bias());
        if (hasSpotShadow) cmd.bindTexture(SPOT_SHADOW_TEXTURE_UNIT, spotShadowTexture);
        if (generation.topology.gtaoEnabled()) {
            boolean hasGtao = gtaoTexture != 0;
            cmd.trySetUniformInt(shader, "uGtaoEnabled", hasGtao ? 1 : 0)
                    .trySetUniformInt(shader, "uGtaoMap", GTAO_TEXTURE_UNIT);
            if (hasGtao) cmd.bindTexture(GTAO_TEXTURE_UNIT, gtaoTexture);
        }
        if (generation.shadowSamplingBlock != null) generation.shadowSamplingBlock.bind(cmd);
        generation.clusteredLightingBinder.bindForward(cmd, shader);
        cmd.trySetUniformInt(shader, "uClusterDebugMode", clusterDebugMode.ordinal());
    }

    private void validatePbrVertexLayouts(Scene candidateScene) {
        int rendererIndex = 0;
        for (MeshRenderer renderer : candidateScene.renderers()) {
            if (renderer.material().material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                validatePbrVertexLayout(renderer.mesh().vertexLayout(), rendererIndex);
            }
            rendererIndex++;
        }
    }

    private static void validatePbrVertexLayout(VertexLayout layout, int rendererIndex) {
        Map<VertexSemantic, int[]> contract = Map.of(
                VertexSemantic.POSITION, new int[]{0, 3},
                VertexSemantic.TEXCOORD_0, new int[]{1, 2},
                VertexSemantic.NORMAL, new int[]{2, 3},
                VertexSemantic.TANGENT, new int[]{3, 4});
        for (Map.Entry<VertexSemantic, int[]> required : contract.entrySet()) {
            VertexSemantic semantic = required.getKey();
            VertexAttribute attribute = layout.attribute(semantic).orElseThrow(() ->
                    new IllegalArgumentException("PBR renderer[" + rendererIndex
                            + "] mesh is missing " + semantic + " semantic"));
            int expectedLocation = required.getValue()[0];
            int expectedSize = required.getValue()[1];
            if (attribute.index() != expectedLocation || attribute.type() != GL_FLOAT
                    || attribute.size() != expectedSize || attribute.divisor() != 0
                    || attribute.normalized()) {
                throw new IllegalArgumentException("PBR renderer[" + rendererIndex + "] "
                        + semantic + " must be divisor-0 unnormalized GL_FLOAT vec" + expectedSize
                        + " at location " + expectedLocation);
            }
        }
    }

    public record VisibilityStatistics(boolean available, boolean cullingEnabled,
                                       long sceneRevision, int candidateRenderers,
                                       int finiteBoundsRenderers, int unboundedRenderers,
                                       int forwardVisible, int forwardCulled,
                                       int shadowCandidates, int shadowVisible,
                                       int shadowCulled, int staticRenderers,
                                       int dynamicRenderers, int modelCacheHits,
                                       int modelCacheMisses, int boundsCacheHits,
                                       int boundsCacheMisses, boolean forwardQueueReused,
                                       boolean forwardQueueRebuilt, boolean shadowQueueReused,
                                       boolean shadowQueueRebuilt, long modelUpdateNanos,
                                       long boundsTransformNanos, long frustumTestNanos,
                                       long queueSortNanos, long totalQueueBuildNanos,
                                       int opaqueDraws, int maskedDraws,
                                       int additiveDraws, int alphaDraws,
                                       int shaderChanges, int materialChanges, int meshChanges,
                                       int blendChanges, int mirroredChanges,
                                       int visibilityScanned, int boundsUpdated,
                                       int missingBounds, int layerExcluded,
                                       int transparentVisible, long transparentSortNanos,
                                       int transparentStableTies,
                                       long commandRecordNanos, int recordedCommands,
                                       int recordedMatrixSnapshots, int recordedObjectPayloads) {
        public static final VisibilityStatistics UNAVAILABLE = new VisibilityStatistics(false,
                false, 0L, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, false, false, false, false,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0);

        private static VisibilityStatistics from(SceneFrame.Statistics source,
                                                  long commandRecordNanos,
                                                  int recordedCommands,
                                                  int recordedMatrixSnapshots,
                                                  int recordedObjectPayloads,
                                                  int shadowVisibleOverride) {
            int shadowVisible = shadowVisibleOverride < 0
                    ? source.shadowVisible() : shadowVisibleOverride;
            int shadowCulled = shadowVisibleOverride < 0
                    ? source.shadowCulled() : source.shadowCandidates() - shadowVisible;
            return new VisibilityStatistics(true, source.cullingEnabled(), source.sceneRevision(),
                    source.candidateRenderers(), source.finiteBoundsRenderers(),
                    source.unboundedRenderers(), source.forwardVisible(), source.forwardCulled(),
                    source.shadowCandidates(), shadowVisible, shadowCulled,
                    source.staticRenderers(), source.dynamicRenderers(), source.modelCacheHits(),
                    source.modelCacheMisses(), source.boundsCacheHits(), source.boundsCacheMisses(),
                    source.forwardQueueReused(), source.forwardQueueRebuilt(),
                    source.shadowQueueReused(), source.shadowQueueRebuilt(),
                    source.modelUpdateNanos(), source.boundsTransformNanos(),
                    source.frustumTestNanos(), source.queueSortNanos(),
                    source.totalQueueBuildNanos(), source.opaqueDraws(), source.maskedDraws(),
                    source.additiveDraws(), source.alphaDraws(), source.shaderChanges(),
                    source.materialChanges(),
                    source.meshChanges(), source.blendChanges(), source.mirroredChanges(),
                    source.visibilityScanned(), source.boundsUpdated(), source.missingBounds(),
                    source.layerExcluded(), source.transparentVisible(),
                    source.transparentSortNanos(), source.transparentStableTies(),
                    commandRecordNanos, recordedCommands, recordedMatrixSnapshots,
                    recordedObjectPayloads);
        }
    }

    @FunctionalInterface
    public interface CameraPassExecutor {
        void execute(com.kaleblangley.haikalat.core.graph.PassResources resources,
                     CommandBuffer commands, ExternalCamera camera);
    }

    /** 仅在材质确实覆盖引擎逐帧 binding 时，才需要在材质之后重新提交 frame state。 */
    private boolean invalidatesFrameState(MaterialInstance instance) {
        Material material = instance.material();
        
        // O3: Replace Stream API with direct iteration to reduce object allocation
        boolean templateInvalidates = frameStateInvalidationByMaterial.computeIfAbsent(material, candidate -> {
            for (var key : candidate.defaultUniforms().keySet()) {
                if (isFrameOwnedUniform(key.name())) {
                    return true;
                }
            }
            for (var binding : candidate.defaultTextures()) {
                if (isFrameOwnedTextureUnit(binding.unit())) {
                    return true;
                }
            }
            return false;
        });
        
        if (templateInvalidates || !instance.hasOverrides()) return templateInvalidates;
        for (var key : instance.uniformOverrides().keySet()) {
            if (isFrameOwnedUniform(key.name())) return true;
        }
        for (int unit : instance.textureOverrides().keySet()) {
            if (isFrameOwnedTextureUnit(unit)) return true;
        }
        return false;
    }

    static boolean isFrameOwnedUniform(String name) {
        // O2: Use HashSet lookup (O(1)) instead of 26 string comparisons
        if (FRAME_OWNED_UNIFORMS.contains(name)) {
            return true;
        }
        for (String prefix : FRAME_OWNED_UNIFORM_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFrameOwnedTextureUnit(int unit) {
        return unit == SHADOW_TEXTURE_UNIT
                || unit == POINT_SHADOW_TEXTURE_UNIT
                || unit == SPOT_SHADOW_TEXTURE_UNIT
                || unit == PbrMaterialBinder.IRRADIANCE_UNIT
                || unit == PbrMaterialBinder.PREFILTERED_SPECULAR_UNIT
                || unit == PbrMaterialBinder.BRDF_LUT_UNIT
                || unit == GTAO_TEXTURE_UNIT;
    }

    private Camera frameCamera() {
        RenderFrameContext context = activeFrameContext;
        return context == null ? scene.camera() : context.camera();
    }

    @FunctionalInterface
    public interface FogCameraPassExecutor {
        void execute(com.kaleblangley.haikalat.core.graph.PassResources resources,
                     CommandBuffer commands, ExternalCamera camera, VolumetricFogView fog);
    }

    /** Non-overlapping CPU benchmark sections from the most recent frame. */
    public record BenchmarkCpuTiming(boolean available, long framePreparationNanos,
                                     long lightPackAndRecordNanos, long graphRecordNanos,
                                     long deviceSubmitNanos) {
        public static final BenchmarkCpuTiming UNAVAILABLE = new BenchmarkCpuTiming(
                false, 0L, 0L, 0L, 0L);

        public BenchmarkCpuTiming {
            if (framePreparationNanos < 0L || lightPackAndRecordNanos < 0L
                    || graphRecordNanos < 0L || deviceSubmitNanos < 0L) {
                throw new IllegalArgumentException("benchmark CPU timings must be non-negative");
            }
        }
    }

    private ExternalCamera externalFrameCamera() {
        RenderFrameContext context = activeFrameContext;
        if (context != null) return context.camera();
        Camera camera = frameCamera();
        if (camera instanceof ExternalCamera external) {
            return external;
        }
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(
                camera, frameWidth(), frameHeight(), new Matrix4f());
        return new ExternalCamera(view, projection,
                new Matrix4f(projection).mul(view), camera.position(), 0.0f,
                CameraProjection.NEAR_PLANE, CameraProjection.FAR_PLANE,
                camera.visibilityRevision());
    }

    private int frameWidth() {
        RenderFrameContext context = activeFrameContext;
        return context == null ? Math.max(1, window.width()) : context.width();
    }

    private ExternalCamera fogRasterCamera() {
        ExternalCamera stable=externalFrameCamera();
        if (requireGeneration().volumetric == null) return stable;
        var frame=temporalFrameState.current();
        return new ExternalCamera(frame.view(),frame.jitteredProjection(),
                new Matrix4f(frame.jitteredProjection()).mul(frame.view()),stable.position(),stable.partialTick(),
                stable.nearPlane(),stable.farPlane(),stable.revision());
    }

    private int frameHeight() {
        RenderFrameContext context = activeFrameContext;
        return context == null ? Math.max(1, window.height()) : context.height();
    }

    private RenderFrameContext requireFrameContext() {
        RenderFrameContext context = activeFrameContext;
        if (context == null) {
            throw new IllegalStateException("RenderFrameContext is only available during execute");
        }
        return context;
    }

    private static RenderWindow initialExtent(PresentationTarget target) {
        PresentationTarget required = Objects.requireNonNull(target, "initialTarget");
        int width = Math.max(1, required.width());
        int height = Math.max(1, required.height());
        return new RenderWindow() {
            @Override
            public int width() {
                return width;
            }

            @Override
            public int height() {
                return height;
            }
        };
    }
}
