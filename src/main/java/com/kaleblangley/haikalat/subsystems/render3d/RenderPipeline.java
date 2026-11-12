package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneRenderer;

import com.kaleblangley.haikalat.subsystems.render3d.ao.GtaoAllocationPlan;
import com.kaleblangley.haikalat.subsystems.render3d.ao.GtaoPasses;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsPass;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiIndirectPass;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiProbeVolume;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.environment.AtmosphereSettings;
import com.kaleblangley.haikalat.subsystems.render3d.environment.StylizedSkyRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraUniforms;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDeformationDefaults;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.TemporalJitter;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.ForwardPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineFeaturePolicy;
import com.kaleblangley.haikalat.subsystems.render3d.pipeline.PipelineGeneration;
import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionPass;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderFrameContext;
import com.kaleblangley.haikalat.subsystems.render3d.scene.FrameInvalidation;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueClass;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneRevisionSnapshot;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalSceneState;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricAllocationDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricAllocationPlan;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricFogDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricFogView;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulation;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeVolumePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricResources;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricSurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencyInputs;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencyPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctBox;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctPass;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctProbeBatch;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSceneInput;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfacePass;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;

import com.kaleblangley.haikalat.subsystems.vfx.VfxLight;

import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;

import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusterGrid;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingBinder;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingResources;
import com.kaleblangley.haikalat.subsystems.render3d.FrameLightTable;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalCascadePlan;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PointShadowAtlas;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PointShadowSlotPlan;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowCasterPlanner;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowCullingStatistics;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowDecision;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowFramePlan;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowSamplingBlock;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowTileRect;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ScreenSpaceContactShadowPass;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.SpotShadowAtlas;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.SpotShadowSlotPlan;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.state.HostGlState;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingCapability;
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
import com.kaleblangley.haikalat.subsystems.render3d.pbr.RefractionPass;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewController;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.FrameLightBinder;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewRenderer;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.vrs.VariableRateShadingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaInvalidationReason;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

public final class RenderPipeline {
    private static final String FULL_SCAN_BENCHMARK_PROPERTY =
            "haikalat.internal.benchmark.fullScanReference";
    /** Deterministic wave clock limit; beyond one hour float phase precision is not useful. */
    private static final float WATER_TIME_LIMIT_SECONDS = 3600.0f;
    private static final String HOST_COLOR_IMPORT = "HaikalatHostColor";
    private static final String HOST_DEPTH_IMPORT = "HaikalatHostDepth";
    private static final String HOST_STENCIL_IMPORT = "HaikalatHostStencil";
    /** Graph bridge that copies scene colour + DDGI indirect into the HDR chain input. */
    static final String DDGI_INDIRECT_COLOR = "ddgiIndirectColor";
    /** Graph bridge that owns the VCT composite target before the post-process chain. */
    static final String VCT_COLOR = "vctCompositeColor";
    /** VCT dirty-region voxelization; runs after the opaque forward pass. */
    static final String VCT_VOXELIZE_PASS = "VctVoxelizePass";
    /** VCT explicit probe trace; runs after voxelization and before the composite. */
    static final String VCT_TRACE_PASS = "VctProbeTracePass";
    /** VCT screen-space composite that adds the cone-traced radiance. */
    static final String VCT_COMPOSITE_PASS = "VctCompositePass";
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
    private int maximumTextureDimension;
    private final java.util.ArrayDeque<RetiredGeneration> retiredGenerations = new java.util.ArrayDeque<>();
    private long lastVolumeCandidatePeakBytes;
    private static final class RetiredGeneration {
        private final PipelineGeneration generation;
        private com.kaleblangley.haikalat.backend.sync.GpuFence fence;

        private RetiredGeneration(PipelineGeneration generation) { this.generation = generation; }
    }

    private void fenceRetirement(RetiredGeneration retired) {
        if (retired.fence != null) return;
        if (Boolean.getBoolean("haikalat.test.failRetirementFence"))
            throw new IllegalStateException("injected retirement fence creation failure");
        retired.fence = com.kaleblangley.haikalat.backend.sync.GpuFence.insert();
    }

    private void collectRetiredGenerations(boolean wait) {
        RuntimeException failure = null;
        boolean collected = false;
        while (!retiredGenerations.isEmpty()) {
            var retired = retiredGenerations.peekFirst();
            try { fenceRetirement(retired); }
            catch (RuntimeException failureToFence) { lastFailureStage = "retirement-after-publication"; throw failureToFence; }
            if (!retired.fence.isSignaled()) {
                if (!wait) break;
                if (!retired.fence.waitFor(java.util.concurrent.TimeUnit.SECONDS.toNanos(5))) {
                    lastFailureStage = "retirement-timeout";
                    throw new IllegalStateException("timed out waiting for pipeline retirement fence");
                }
            }
            retiredGenerations.removeFirst();
            collected = true;
            failure = closeCollecting(retired.fence, failure);
            failure = closeCollecting(retired.generation, failure);
        }
        if (collected) for (RenderDevice device : usedDevices) device.invalidateState();
        if (failure != null) { lastFailureStage = "retirement-after-publication"; throw failure; }
    }

    void drainRetirementsForTest() { requireGpuOwner(); collectRetiredGenerations(true); }
    private Thread gpuOwner;
    private org.lwjgl.opengl.GLCapabilities gpuContext;
    private long glfwContext;
    private boolean closed;
    private boolean shuttingDown;

    private void requireGpuOwner() {
        if (closed || shuttingDown) throw new IllegalStateException("RenderPipeline is closed or shutting down");
        requireOwnerContext();
    }

    private void requireOwnerContext() {
        if (gpuOwner == null) return;
        if (Thread.currentThread() != gpuOwner) throw new IllegalStateException("RenderPipeline requires its render owner thread");
        org.lwjgl.opengl.GLCapabilities current;
        try { current = org.lwjgl.opengl.GL.getCapabilities(); }
        catch (IllegalStateException absent) { throw new IllegalStateException("RenderPipeline requires its current GL Context", absent); }
        if (current != gpuContext || glfwContext != 0 && org.lwjgl.glfw.GLFW.glfwGetCurrentContext() != glfwContext)
            throw new IllegalStateException("RenderPipeline requires its original current GL Context");
    }
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
    /**
     * Capability query used by feature resolution. Production resolves the
     * current context; package-level tests can inject the JVM answer without a
     * GL context. No caller can make this seam report support on a device that
     * lacks the NV entry points.
     */
    private Supplier<VariableRateShadingCapability> variableRateShadingCapabilitySupplier =
            VariableRateShadingCapability::inspectCurrent;
    private OutdoorEnvironmentSettings outdoorEnvironment = OutdoorEnvironmentSettings.disabled();
    /** {@code null} means the bounded atmosphere background is disabled. */
    private AtmosphereSettings atmosphereSettings;
    private VolumetricFogSettings volumetricFogSettings = VolumetricFogSettings.disabled();
    private VolumetricParticleSettings volumetricParticleSettings =
            VolumetricParticleSettings.disabled();
    private StochasticTransparencySettings stochasticTransparencySettings =
            StochasticTransparencySettings.disabled();
    private VolumetricCloudSettings volumetricCloudSettings = VolumetricCloudSettings.disabled();
    private SmokeSimulationSettings smokeSimulationSettings = SmokeSimulationSettings.disabled();
    private SmokeRenderSettings smokeRenderSettings = SmokeRenderSettings.disabled();
    /** Set when a failed frame may have advanced the smoke simulation on the GPU. */
    private boolean smokeSimulationResetPending;
    /** True once the current frame recorded its smoke simulation pass. */
    private boolean smokeSimulationAdvanced;
    /** Caller-selected stochastic surface set for subsequent frames; empty means no draw. */
    private List<OitSurface> stochasticTransparencySurfaces = List.of();
    /** Weighted blended OIT is the alternative transparent-collection strategy. */
    private WeightedOitSettings weightedOitSettings = WeightedOitSettings.disabled();
    /**
     * Caller-selected OIT surface set for subsequent frames.  {@code
     * weightedOitSetDeclared} distinguishes "never declared" from an explicit
     * empty set that declares the strategy without drawing.
     */
    private List<OitSurface> weightedOitSurfaces = List.of();
    private boolean weightedOitSetDeclared;
    /** Cached intersection result; recomputed only when either staged set changes. */
    private boolean weightedOitStochasticOverlap;
    private PlanarReflectionSettings planarReflectionSettings =
            PlanarReflectionSettings.disabled();
    private CausticsSettings causticsSettings = CausticsSettings.disabled();
    private WaterSurfaceSettings waterSurfaceSettings = WaterSurfaceSettings.disabled();
    private DdgiSettings ddgiSettings = DdgiSettings.disabled();
    private VctSettings vctSettings = VctSettings.disabled();
    private HairStrandSettings hairStrandSettings = HairStrandSettings.disabled();
    /** Caller-converted visible opaque triangles for the next VCT update; never built here. */
    private VctSceneInput vctSceneInput;
    /** Dirty world boxes bounding scene changes since the last successful voxel update. */
    private List<VctBox> vctDirtyBoxes = List.of();
    /** Optional explicit VCT probe batch recorded after voxelization; no implicit readback. */
    private VctProbeBatch vctProbeBatch;
    /** Scene-wide VCT receiver proxies used by the first version's composite. */
    private float vctSurfaceRoughness = 0.5f;
    private final Vector3f vctSurfaceAlbedo = new Vector3f(0.8f);
    /** Placement of the procedural hair lock for the forward and shadow passes. */
    private final Matrix4f hairStrandModel = new Matrix4f();
    private float waterTimeSeconds;
    private PassExecutor hdrVfxRecorder;
    private CameraPassExecutor cameraAwareHdrVfxRecorder;
    private FogCameraPassExecutor fogAwareHdrVfxRecorder;
    private boolean hdrVfxFogOptOut;
    private boolean directLightingEnabled = true;
    private boolean imageBasedLightingEnabled = true;
    private boolean directionalShadowsEnabled = true;
    private boolean pointShadowsEnabled = true;
    private boolean spotShadowsEnabled = true;
    private boolean hdrVfxEnabled = true;
    private boolean previewEnabled = true;
    private boolean featureMasterEnabled = true;
    private List<VfxLight> pendingVfxLights = List.of();
    private long vfxLightInputRevision;
    private int maxVfxLights = 4;
    private final VfxLightBridge vfxLightBridge = new VfxLightBridge();
    private VfxLightDiagnostics vfxLightDiagnostics = VfxLightDiagnostics.UNAVAILABLE;
    private final RenderFeaturesControl features = new RenderFeaturesControl(this);
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
        publishCompatibilityFeatureState();
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
        return PostProcessPipeline.passNamesFor(mode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        return withDirectionalShadow(PostProcessPipeline.passNamesFor(mode), directionalShadow);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        return PostProcessPipeline.passNamesFor(mode, toneMappingMode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                            boolean directionalShadow) {
        return withDirectionalShadow(
                PostProcessPipeline.passNamesFor(mode, toneMappingMode), directionalShadow);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                            BloomSettings bloomSettings, boolean directionalShadow) {
        return withDirectionalShadow(
                PostProcessPipeline.passNamesFor(mode, toneMappingMode, bloomSettings),
                directionalShadow);
    }

    private static List<String> withDirectionalShadow(List<String> passes, boolean directionalShadow) {
        if (!directionalShadow) {
            return passes;
        }
        return Stream.concat(Stream.of(DirectionalShadowMap.PASS_NAME), passes.stream()).toList();
    }

    public RenderPipelineKind kind() {
        return RenderPipelineKind.FORWARD;
    }

    /** Unified feature configuration, observation and batch submission entry. */
    public RenderFeaturesControl features() {
        return features;
    }

    long submitFeatureSettings(RenderFeatureSettings value) {
        Objects.requireNonNull(value, "settings");
        if (closed || shuttingDown) throw new IllegalStateException("RenderPipeline is closed");
        if (executing) {
            throw new IllegalStateException("feature requests are only allowed at frame boundaries");
        }
        if (gpuOwner != null) requireGpuOwner();
        return features.recordRequest(value);
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
        if (required.enabled() && atmosphereSettings != null) {
            throw new IllegalStateException(
                    "stylized outdoor sky and atmosphere sky are mutually exclusive");
        }
        outdoorEnvironment = required;
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Returns the immutable environment snapshot used by the next frame. */
    public OutdoorEnvironmentSettings outdoorEnvironment() {
        return outdoorEnvironment;
    }

    /**
     * Configures the bounded atmosphere sky before build.  {@code null}
     * disables it.  The atmosphere is a replacement background, so it cannot
     * be selected together with the stylized outdoor sky.
     */
    public RenderPipeline atmosphere(AtmosphereSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyAtmosphere after build");
        }
        if (value != null && outdoorEnvironment.enabled()) {
            throw new IllegalStateException(
                    "atmosphere sky and stylized outdoor sky are mutually exclusive");
        }
        atmosphereSettings = value;
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current atmosphere sky configuration, or {@code null} when disabled. */
    public AtmosphereSettings atmosphere() {
        return atmosphereSettings;
    }

    /** Publishes atmosphere changes at a synchronous render-owner frame boundary. */
    public void applyAtmosphere(AtmosphereSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) { applyAtmosphereInternal(value); }
        } else {
            applyAtmosphereInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyAtmosphereInternal(AtmosphereSettings value) {
        if (executing) {
            throw new IllegalStateException("atmosphere changes are only allowed at frame boundaries");
        }
        if (value != null && outdoorEnvironment.enabled()) {
            throw new IllegalStateException(
                    "atmosphere sky and stylized outdoor sky are mutually exclusive");
        }
        if (Objects.equals(value, atmosphereSettings)) return;
        if (activeGeneration == null) { atmosphere(value); return; }
        AtmosphereSettings previous = atmosphereSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        atmosphereSettings = value;
        try {
            if (sameAtmosphereLutExtents(previous, value)) {
                // The pass samples the current settings every frame; only a LUT
                // extent change needs a replacement generation.
                topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
                return;
            }
            PipelineTopology topology = captureTopology(scene,
                    activeGeneration.graph().width(), activeGeneration.graph().height(),
                    volumetricParticleSettings, stochasticTransparencySettings);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) atmosphereSettings = previous;
            throw failure;
        }
    }

    private static boolean sameAtmosphereLutExtents(AtmosphereSettings first,
                                                    AtmosphereSettings second) {
        if (first == null || second == null) return first == second;
        return first.transmittanceLutWidth() == second.transmittanceLutWidth()
                && first.transmittanceLutHeight() == second.transmittanceLutHeight()
                && first.skyViewLutWidth() == second.skyViewLutWidth()
                && first.skyViewLutHeight() == second.skyViewLutHeight();
    }

    /** Configures independently lit three-dimensional fog before build. */
    public RenderPipeline volumetricFog(VolumetricFogSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) throw new IllegalStateException("use applyVolumetricFog after build");
        volumetricFogSettings = Objects.requireNonNull(value, "volumetricFog");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    public VolumetricFogSettings volumetricFog() { return volumetricFogSettings; }

    /** Publishes scalar updates or a fully built replacement generation at a frame boundary. */
    public void applyVolumetricFog(VolumetricFogSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) { applyVolumetricFogInternal(value); }
        } else applyVolumetricFogInternal(value);
        publishCompatibilityFeatureState();
    }

    private void applyVolumetricFogInternal(VolumetricFogSettings value) {
        VolumetricFogSettings replacement = Objects.requireNonNull(value, "volumetricFog");
        if (executing) throw new IllegalStateException("fog changes are only allowed at frame boundaries");
        if (replacement.equals(volumetricFogSettings)) return;
        if (activeGeneration == null) { volumetricFog(replacement); return; }
        VolumetricFogSettings previous = volumetricFogSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        volumetricFogSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(scene,
                    activeGeneration.graph().width(), activeGeneration.graph().height(),
                    volumetricParticleSettings, stochasticTransparencySettings);
            if (!topology.equals(activeGeneration.topology()))
                activateGeneration(createGeneration(scene, topology, activeGeneration));
            else activeGeneration.postProcess().invalidateTemporalHistory(TaaInvalidationReason.VOLUMETRIC_SETTINGS);
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) volumetricFogSettings = previous;
            throw failure;
        }
    }

    /** Configures the bounded local volumetric-particle medium before build. */
    public RenderPipeline volumetricParticles(VolumetricParticleSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyVolumetricParticles after build");
        }
        volumetricParticleSettings = Objects.requireNonNull(value, "volumetricParticles");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current local volumetric-particle configuration; no GPU resource is exposed. */
    public VolumetricParticleSettings volumetricParticles() {
        return volumetricParticleSettings;
    }

    /**
     * Publishes a particle settings change at a frame boundary. The pass bakes its
     * settings at construction, so the complete candidate generation is rebuilt;
     * a failure keeps both the active generation and the previous settings.
     */
    public void applyVolumetricParticles(VolumetricParticleSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyVolumetricParticlesInternal(value);
            }
        } else {
            applyVolumetricParticlesInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyVolumetricParticlesInternal(VolumetricParticleSettings value) {
        VolumetricParticleSettings replacement =
                Objects.requireNonNull(value, "volumetricParticles");
        if (executing) {
            throw new IllegalStateException(
                    "volumetric particle changes are only allowed at frame boundaries");
        }
        if (replacement.equals(volumetricParticleSettings)) {
            return;
        }
        if (activeGeneration == null) {
            volumetricParticles(replacement);
            return;
        }
        VolumetricParticleSettings previous = volumetricParticleSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        volumetricParticleSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(activeGeneration, replacement,
                    stochasticTransparencySettings);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            activeGeneration.postProcess().invalidateTemporalHistory(
                    TaaInvalidationReason.VOLUMETRIC_PARTICLE_SETTINGS);
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                volumetricParticleSettings = previous;
            }
            throw failure;
        }
    }

    /** Configures the bounded visible cloud layer before build. */
    public RenderPipeline volumetricClouds(VolumetricCloudSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyVolumetricClouds after build");
        }
        volumetricCloudSettings = Objects.requireNonNull(value, "volumetricClouds");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current visible cloud-layer configuration; no GPU resource is exposed. */
    public VolumetricCloudSettings volumetricClouds() {
        return volumetricCloudSettings;
    }

    /**
     * Publishes a cloud-layer settings change at a frame boundary. The pass bakes
     * its settings and owns the temporal history, so the complete candidate
     * generation is rebuilt; a failure keeps the active generation and the
     * previous settings.
     */
    public void applyVolumetricClouds(VolumetricCloudSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyVolumetricCloudsInternal(value);
            }
        } else {
            applyVolumetricCloudsInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyVolumetricCloudsInternal(VolumetricCloudSettings value) {
        VolumetricCloudSettings replacement = Objects.requireNonNull(value, "volumetricClouds");
        if (executing) {
            throw new IllegalStateException(
                    "volumetric cloud changes are only allowed at frame boundaries");
        }
        if (replacement.equals(volumetricCloudSettings)) return;
        if (activeGeneration == null) {
            volumetricClouds(replacement);
            return;
        }
        VolumetricCloudSettings previous = volumetricCloudSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        volumetricCloudSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(scene,
                    activeGeneration.graph().width(), activeGeneration.graph().height());
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                volumetricCloudSettings = previous;
            }
            throw failure;
        }
    }

    /** Configures the fixed-step smoke simulation owner before build. */
    public RenderPipeline smokeSimulation(SmokeSimulationSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applySmokeSimulation after build");
        }
        smokeSimulationSettings = Objects.requireNonNull(value, "smokeSimulation");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current smoke-simulation configuration; no GPU resource is exposed. */
    public SmokeSimulationSettings smokeSimulation() {
        return smokeSimulationSettings;
    }

    /** Configures the smoke volume-rendering consumer before build. */
    public RenderPipeline smokeRender(SmokeRenderSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applySmokeRender after build");
        }
        smokeRenderSettings = Objects.requireNonNull(value, "smokeRender");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current smoke volume-rendering configuration; no GPU resource is exposed. */
    public SmokeRenderSettings smokeRender() {
        return smokeRenderSettings;
    }

    /**
     * Publishes a smoke-simulation change at a frame boundary. A grid or source
     * change replaces the owner, so the complete candidate generation is rebuilt;
     * a failure keeps the active generation, simulation and settings.
     */
    public void applySmokeSimulation(SmokeSimulationSettings value) {
        applySmokeTransactionally(Objects.requireNonNull(value, "smokeSimulation"),
                smokeRenderSettings);
    }

    /** Publishes a smoke render-settings change at a frame boundary. */
    public void applySmokeRender(SmokeRenderSettings value) {
        applySmokeTransactionally(smokeSimulationSettings,
                Objects.requireNonNull(value, "smokeRender"));
    }

    private void applySmokeTransactionally(SmokeSimulationSettings simulation,
                                           SmokeRenderSettings render) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applySmokeInternal(simulation, render);
            }
        } else {
            applySmokeInternal(simulation, render);
        }
        publishCompatibilityFeatureState();
    }

    private void applySmokeInternal(SmokeSimulationSettings simulation,
                                    SmokeRenderSettings render) {
        if (executing) {
            throw new IllegalStateException("smoke changes are only allowed at frame boundaries");
        }
        if (simulation.equals(smokeSimulationSettings) && render.equals(smokeRenderSettings)) {
            return;
        }
        if (activeGeneration == null) {
            smokeSimulationSettings = simulation;
            smokeRenderSettings = render;
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
            return;
        }
        SmokeSimulationSettings previousSimulation = smokeSimulationSettings;
        SmokeRenderSettings previousRender = smokeRenderSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        smokeSimulationSettings = simulation;
        smokeRenderSettings = render;
        try {
            PipelineTopology topology = captureTopology(scene,
                    activeGeneration.graph().width(), activeGeneration.graph().height());
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                smokeSimulationSettings = previousSimulation;
                smokeRenderSettings = previousRender;
            }
            throw failure;
        }
    }

    /** Configures the bounded stochastic-transparency collection before build. */
    public RenderPipeline stochasticTransparency(StochasticTransparencySettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyStochasticTransparency after build");
        }
        StochasticTransparencySettings requested =
                Objects.requireNonNull(value, "stochasticTransparency");
        if (requested.active()
                && stochasticTransparencySurfaces.size() > requested.maximumSurfaces()) {
            throw new IllegalArgumentException("the staged stochastic surface set ("
                    + stochasticTransparencySurfaces.size()
                    + ") exceeds the requested maximumSurfaces "
                    + requested.maximumSurfaces());
        }
        validateTransparentSetClaims(weightedOitSettings, requested);
        stochasticTransparencySettings = requested;
        recomputeWeightedOitOverlap();
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current stochastic-transparency configuration; no GPU resource is exposed. */
    public StochasticTransparencySettings stochasticTransparency() {
        return stochasticTransparencySettings;
    }

    /**
     * Publishes a stochastic-transparency settings change at a frame boundary. A
     * capacity change allocates a new quad owner; a failure keeps the active
     * generation, pass and previous settings.
     */
    public void applyStochasticTransparency(StochasticTransparencySettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyStochasticTransparencyInternal(value);
            }
        } else {
            applyStochasticTransparencyInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyStochasticTransparencyInternal(StochasticTransparencySettings value) {
        StochasticTransparencySettings replacement =
                Objects.requireNonNull(value, "stochasticTransparency");
        if (executing) {
            throw new IllegalStateException(
                    "stochastic transparency changes are only allowed at frame boundaries");
        }
        if (replacement.equals(stochasticTransparencySettings)) {
            return;
        }
        if (replacement.active()
                && stochasticTransparencySurfaces.size() > replacement.maximumSurfaces()) {
            throw new IllegalArgumentException("the staged stochastic surface set ("
                    + stochasticTransparencySurfaces.size()
                    + ") exceeds the requested maximumSurfaces "
                    + replacement.maximumSurfaces());
        }
        validateTransparentSetClaims(weightedOitSettings, replacement);
        if (activeGeneration == null) {
            stochasticTransparency(replacement);
            return;
        }
        StochasticTransparencySettings previous = stochasticTransparencySettings;
        PipelineGeneration previousGeneration = activeGeneration;
        stochasticTransparencySettings = replacement;
        recomputeWeightedOitOverlap();
        try {
            PipelineTopology topology = captureTopology(activeGeneration,
                    volumetricParticleSettings, replacement);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            activeGeneration.postProcess().invalidateTemporalHistory(
                    TaaInvalidationReason.STOCHASTIC_TRANSPARENCY_SETTINGS);
            if (activeGeneration.stochasticTransparency() != null) {
                activeGeneration.stochasticTransparency().invalidate();
            }
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                stochasticTransparencySettings = previous;
                recomputeWeightedOitOverlap();
            }
            throw failure;
        }
    }

    /**
     * Stages the caller-selected transparent surface set for the next and
     * subsequent frames. An empty list records no draw and advances no history.
     * The list is copied; surfaces are never retained by the pass.
     */
    public RenderPipeline stochasticTransparencySurfaces(List<OitSurface> surfaces) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException(
                    "stochastic surfaces are only allowed at frame boundaries");
        }
        List<OitSurface> copy = List.copyOf(Objects.requireNonNull(surfaces, "surfaces"));
        if (copy.size() > stochasticTransparencySettings.maximumSurfaces()) {
            throw new IllegalArgumentException("surface count " + copy.size()
                    + " exceeds the configured maximumSurfaces "
                    + stochasticTransparencySettings.maximumSurfaces());
        }
        if (weightedOitSettings.active()
                && transparentSetsIntersect(weightedOitSurfaces, copy)) {
            throw new IllegalArgumentException(
                    "a surface cannot be claimed by weighted OIT and stochastic transparency "
                            + "in the same frame");
        }
        boolean changed = !copy.equals(stochasticTransparencySurfaces);
        stochasticTransparencySurfaces = copy;
        recomputeWeightedOitOverlap();
        if (changed && activeGeneration != null
                && activeGeneration.stochasticTransparency() != null) {
            activeGeneration.stochasticTransparency().invalidate();
        }
        return this;
    }

    /** Current caller-selected stochastic surface set; immutable and empty by default. */
    public List<OitSurface> stochasticTransparencySurfaces() {
        return stochasticTransparencySurfaces;
    }

    /**
     * Configures weighted blended OIT before the first generation is built.
     *
     * <p>OIT is an alternative transparent-collection strategy: it consumes the
     * caller-selected {@link #weightedOitSurfaces(List)} set instead of the
     * sorted alpha queue, and it is mutually exclusive with stochastic
     * transparency for the same surface.  An enabled request needs HDR; MSAA and
     * TAA have no compatible single-sample target and keep the request
     * {@code UNAVAILABLE} without changing the graph.</p>
     */
    public RenderPipeline weightedOit(WeightedOitSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) throw new IllegalStateException("use applyWeightedOit after build");
        WeightedOitSettings requested = Objects.requireNonNull(value, "weightedOit");
        validateWeightedOitAdmission(requested);
        weightedOitSettings = requested;
        recomputeWeightedOitOverlap();
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current weighted-OIT configuration; no GPU resource is exposed. */
    public WeightedOitSettings weightedOit() {
        return weightedOitSettings;
    }

    /**
     * Publishes a weighted-OIT settings change at a frame boundary. A capacity
     * change allocates a new quad owner; a failure keeps the active generation,
     * pass and previous settings.
     */
    public void applyWeightedOit(WeightedOitSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyWeightedOitInternal(value);
            }
        } else {
            applyWeightedOitInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyWeightedOitInternal(WeightedOitSettings value) {
        WeightedOitSettings replacement = Objects.requireNonNull(value, "weightedOit");
        if (executing) {
            throw new IllegalStateException("weighted OIT changes are only allowed at frame boundaries");
        }
        validateWeightedOitAdmission(replacement);
        validateTransparentSetClaims(replacement, stochasticTransparencySettings);
        if (replacement.equals(weightedOitSettings)) return;
        if (activeGeneration == null) {
            weightedOit(replacement);
            return;
        }
        WeightedOitSettings previous = weightedOitSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        weightedOitSettings = replacement;
        recomputeWeightedOitOverlap();
        try {
            PipelineTopology topology = captureTopology(activeGeneration,
                    volumetricParticleSettings, stochasticTransparencySettings);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            activeGeneration.postProcess().invalidateTemporalHistory(
                    TaaInvalidationReason.WEIGHTED_OIT_SETTINGS);
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                weightedOitSettings = previous;
                recomputeWeightedOitOverlap();
            }
            throw failure;
        }
    }

    /**
     * Stages the caller-selected OIT surface set for the next and subsequent
     * frames.  Calling this method with an empty list still declares the OIT
     * strategy (a valid no-op collection); never calling it leaves the request
     * {@code NO_TRANSPARENT_SET}.  The list is copied and surfaces are never
     * retained by the pass.
     */
    public RenderPipeline weightedOitSurfaces(List<OitSurface> surfaces) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException(
                    "weighted OIT surfaces are only allowed at frame boundaries");
        }
        List<OitSurface> copy = List.copyOf(Objects.requireNonNull(surfaces, "surfaces"));
        if (weightedOitSettings.active() && copy.size() > weightedOitSettings.maximumSurfaces()) {
            throw new IllegalArgumentException("surface count " + copy.size()
                    + " exceeds the configured maximumSurfaces "
                    + weightedOitSettings.maximumSurfaces());
        }
        if (stochasticTransparencySettings.active()
                && transparentSetsIntersect(copy, stochasticTransparencySurfaces)) {
            throw new IllegalArgumentException(
                    "a surface cannot be claimed by weighted OIT and stochastic transparency "
                            + "in the same frame");
        }
        List<OitSurface> previousSurfaces = weightedOitSurfaces;
        boolean declarationChanged = !weightedOitSetDeclared;
        weightedOitSurfaces = copy;
        weightedOitSetDeclared = true;
        recomputeWeightedOitOverlap();
        if (declarationChanged && weightedOitSettings.active() && activeGeneration != null) {
            // Declaring the set is what adds the pass to the topology key, so the
            // first declaration after build publishes a candidate generation.
            PipelineGeneration previousGeneration = activeGeneration;
            try {
                PipelineTopology topology = captureTopology(activeGeneration,
                        volumetricParticleSettings, stochasticTransparencySettings);
                if (!topology.equals(activeGeneration.topology())) {
                    activateGeneration(createGeneration(scene, topology, activeGeneration));
                }
            } catch (RuntimeException | Error failure) {
                if (activeGeneration == previousGeneration) {
                    weightedOitSurfaces = previousSurfaces;
                    weightedOitSetDeclared = false;
                    recomputeWeightedOitOverlap();
                }
                throw failure;
            }
        }
        return this;
    }

    /** Current caller-selected OIT surface set; immutable and empty by default. */
    public List<OitSurface> weightedOitSurfaces() {
        return weightedOitSurfaces;
    }

    /** @return true once the caller declared the OIT strategy (even with an empty set) */
    public boolean weightedOitSetDeclared() {
        return weightedOitSetDeclared;
    }

    /** Configures the single-plane reflection view before the first generation is built. */
    public RenderPipeline planarReflections(PlanarReflectionSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyPlanarReflections after build");
        }
        PlanarReflectionSettings requested = Objects.requireNonNull(value, "planarReflections");
        validatePlanarReflectionAdmission(requested);
        planarReflectionSettings = requested;
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current planar-reflection configuration; no GPU resource is exposed. */
    public PlanarReflectionSettings planarReflections() {
        return planarReflectionSettings;
    }

    /**
     * Publishes a planar-reflection settings change at a frame boundary.  The
     * pass bakes the plane/resolution/interval, so the complete candidate
     * generation is rebuilt; a failure keeps the previous generation and value.
     */
    public void applyPlanarReflections(PlanarReflectionSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyPlanarReflectionsInternal(value);
            }
        } else {
            applyPlanarReflectionsInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyPlanarReflectionsInternal(PlanarReflectionSettings value) {
        PlanarReflectionSettings replacement =
                Objects.requireNonNull(value, "planarReflections");
        if (executing) {
            throw new IllegalStateException(
                    "planar reflection changes are only allowed at frame boundaries");
        }
        validatePlanarReflectionAdmission(replacement);
        if (replacement.equals(planarReflectionSettings)) return;
        if (activeGeneration == null) {
            planarReflections(replacement);
            return;
        }
        PlanarReflectionSettings previous = planarReflectionSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        planarReflectionSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(activeGeneration,
                    volumetricParticleSettings, stochasticTransparencySettings);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            activeGeneration.postProcess().invalidateTemporalHistory(
                    TaaInvalidationReason.PLANAR_REFLECTION_SETTINGS);
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                planarReflectionSettings = previous;
            }
            throw failure;
        }
    }

    /** Configures the bounded water-caustics contribution before the first build. */
    public RenderPipeline caustics(CausticsSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) throw new IllegalStateException("use applyCaustics after build");
        CausticsSettings requested = Objects.requireNonNull(value, "caustics");
        validateCausticsAdmission(requested);
        causticsSettings = requested;
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current caustics configuration; no GPU resource is exposed. */
    public CausticsSettings caustics() {
        return causticsSettings;
    }

    /**
     * Publishes a caustics settings change at a frame boundary.  The pass bakes
     * its pattern texture and sample count, so a change rebuilds the candidate
     * generation; a failure keeps the previous generation and value.
     */
    public void applyCaustics(CausticsSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyCausticsInternal(value);
            }
        } else {
            applyCausticsInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyCausticsInternal(CausticsSettings value) {
        CausticsSettings replacement = Objects.requireNonNull(value, "caustics");
        if (executing) {
            throw new IllegalStateException("caustics changes are only allowed at frame boundaries");
        }
        validateCausticsAdmission(replacement);
        if (replacement.active() && !waterSurfaceSettings.active()) {
            throw new IllegalArgumentException(
                    "caustics require an active finite water surface");
        }
        if (replacement.equals(causticsSettings)) return;
        if (activeGeneration == null) {
            caustics(replacement);
            return;
        }
        CausticsSettings previous = causticsSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        causticsSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(activeGeneration,
                    volumetricParticleSettings, stochasticTransparencySettings);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            activeGeneration.postProcess().invalidateTemporalHistory(
                    TaaInvalidationReason.CAUSTICS_SETTINGS);
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                causticsSettings = previous;
            }
            throw failure;
        }
    }

    private void validateWeightedOitAdmission(WeightedOitSettings value) {
        validateTransparentSetClaims(value, stochasticTransparencySettings);
        if (!value.active()) return;
        if (!settings.hdrEnabled()) {
            throw new IllegalArgumentException(
                    "weighted OIT requires HDR tone mapping for linear compositing");
        }
        if (weightedOitSetDeclared
                && weightedOitSurfaces.size() > value.maximumSurfaces()) {
            throw new IllegalArgumentException("the staged OIT surface set ("
                    + weightedOitSurfaces.size() + ") exceeds the requested maximumSurfaces "
                    + value.maximumSurfaces());
        }
    }

    private void validatePlanarReflectionAdmission(PlanarReflectionSettings value) {
        if (!value.active()) return;
        if (!settings.hdrEnabled()) {
            throw new IllegalArgumentException("planar reflections require HDR tone mapping");
        }
        if (!value.hasPlane()) {
            throw new IllegalArgumentException(
                    "planar reflections require a finite non-degenerate plane");
        }
    }

    private void validateCausticsAdmission(CausticsSettings value) {
        if (!value.active()) return;
        if (!settings.hdrEnabled()) {
            throw new IllegalArgumentException(
                    "caustics require HDR tone mapping for additive linear composition");
        }
    }

    /**
     * Fail-closed dependency check for the three S3 composition features after
     * all configuration fields are set.  The build/visual/water apply paths call
     * this before any candidate generation is prepared.
     */
    private void validateCompositionDependencies() {
        if (weightedOitSettings.active() && !settings.hdrEnabled()) {
            throw new IllegalArgumentException(
                    "weighted OIT requires HDR tone mapping for linear compositing");
        }
        if (planarReflectionSettings.active() && !settings.hdrEnabled()) {
            throw new IllegalArgumentException("planar reflections require HDR tone mapping");
        }
        if (causticsSettings.active()) {
            if (!settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "caustics require HDR tone mapping for additive linear composition");
            }
            if (!waterSurfaceSettings.active()) {
                throw new IllegalArgumentException(
                        "caustics require an active finite water surface");
            }
        }
    }

    private void validateTransparentSetClaims(WeightedOitSettings oit,
                                              StochasticTransparencySettings stochastic) {
        if (!oit.active() || !stochastic.active()) return;
        if (transparentSetsIntersect(weightedOitSurfaces, stochasticTransparencySurfaces)) {
            throw new IllegalArgumentException(
                    "a surface cannot be claimed by weighted OIT and stochastic transparency "
                            + "in the same frame");
        }
    }

    private void recomputeWeightedOitOverlap() {
        weightedOitStochasticOverlap = weightedOitSettings.active()
                && stochasticTransparencySettings.active()
                && weightedOitSetDeclared
                && transparentSetsIntersect(weightedOitSurfaces,
                stochasticTransparencySurfaces);
    }

    private static boolean transparentSetsIntersect(List<OitSurface> first,
                                                     List<OitSurface> second) {
        if (first.isEmpty() || second.isEmpty()) return false;
        List<OitSurface> smaller = first.size() <= second.size() ? first : second;
        List<OitSurface> larger = smaller == first ? second : first;
        Set<OitSurface> lookup = new java.util.HashSet<>(smaller);
        for (OitSurface surface : larger) {
            if (lookup.contains(surface)) return true;
        }
        return false;
    }

    /**
     * Configures the dynamic DDGI probe grid before build. Disabled is the
     * exact no-op value: no probe atlas, program or draw exists.
     */
    public RenderPipeline ddgi(DdgiSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyDdgi after build");
        }
        ddgiSettings = Objects.requireNonNull(value, "ddgi");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current DDGI request; no probe GPU resource is exposed. */
    public DdgiSettings ddgi() {
        return ddgiSettings;
    }

    /**
     * Publishes a DDGI settings change at a frame boundary. The probe volume and
     * indirect pass bake their settings at construction, so the complete
     * candidate generation is rebuilt; a failure keeps the active generation and
     * the previous settings active.
     */
    public void applyDdgi(DdgiSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyDdgiInternal(value);
            }
        } else {
            applyDdgiInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyDdgiInternal(DdgiSettings value) {
        DdgiSettings replacement = Objects.requireNonNull(value, "ddgi");
        if (executing) {
            throw new IllegalStateException("DDGI changes are only allowed at frame boundaries");
        }
        if (replacement.equals(ddgiSettings)) {
            return;
        }
        if (activeGeneration == null) {
            ddgi(replacement);
            return;
        }
        DdgiSettings previous = ddgiSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        ddgiSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(activeGeneration,
                    volumetricParticleSettings, stochasticTransparencySettings);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                ddgiSettings = previous;
            }
            throw failure;
        }
    }

    /**
     * Configures bounded voxel cone tracing before build. Disabled is the exact
     * no-op value: no volume, program or composite pass exists.
     */
    public RenderPipeline vct(VctSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyVct after build");
        }
        vctSettings = Objects.requireNonNull(value, "vct");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current VCT request; no volume GPU resource is exposed. */
    public VctSettings vct() {
        return vctSettings;
    }

    /**
     * Publishes a VCT settings change at a frame boundary. The pass rebuilds its
     * complete runtime as part of a candidate generation; a failure keeps the
     * active generation, volume and previous settings active.
     */
    public void applyVct(VctSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyVctInternal(value);
            }
        } else {
            applyVctInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyVctInternal(VctSettings value) {
        VctSettings replacement = Objects.requireNonNull(value, "vct");
        if (executing) {
            throw new IllegalStateException("VCT changes are only allowed at frame boundaries");
        }
        if (replacement.equals(vctSettings)) {
            return;
        }
        if (activeGeneration == null) {
            vct(replacement);
            return;
        }
        VctSettings previous = vctSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        vctSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(activeGeneration,
                    volumetricParticleSettings, stochasticTransparencySettings);
            activateGeneration(createGeneration(scene, topology, activeGeneration));
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                vctSettings = previous;
            }
            throw failure;
        }
    }

    /**
     * Configures the deterministic hair-strand lock before build. Disabled is
     * the exact no-op value: no strand mesh, program or draw exists.
     */
    public RenderPipeline hairStrands(HairStrandSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) {
            throw new IllegalStateException("use applyHairStrands after build");
        }
        hairStrandSettings = Objects.requireNonNull(value, "hairStrands");
        topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        return this;
    }

    /** Current hair-strand request; no pass GPU resource is exposed. */
    public HairStrandSettings hairStrands() {
        return hairStrandSettings;
    }

    /**
     * Publishes a hair settings change at a frame boundary. Strand geometry is
     * baked per pass, so the complete candidate generation is rebuilt; a failure
     * keeps the active generation and previous settings active.
     */
    public void applyHairStrands(HairStrandSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) {
                applyHairStrandsInternal(value);
            }
        } else {
            applyHairStrandsInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyHairStrandsInternal(HairStrandSettings value) {
        HairStrandSettings replacement = Objects.requireNonNull(value, "hairStrands");
        if (executing) {
            throw new IllegalStateException(
                    "hair strand changes are only allowed at frame boundaries");
        }
        if (replacement.equals(hairStrandSettings)) {
            return;
        }
        if (activeGeneration == null) {
            hairStrands(replacement);
            return;
        }
        HairStrandSettings previous = hairStrandSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        hairStrandSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(activeGeneration,
                    volumetricParticleSettings, stochasticTransparencySettings);
            PipelineGeneration candidate = createGeneration(scene, topology, activeGeneration);
            activateGeneration(candidate);
            // Generation replacement reuses the static shadow cache when the
            // shadow shape is unchanged. The hair lock is outside that cache key,
            // so enabling/disabling the caster (or rebuilding its geometry) must
            // invalidate the borrowed tiles exactly like a model move does.
            if (castsShadow(previous) || castsShadow(replacement)) {
                candidate.shadowCache().invalidateAll();
            }
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) {
                hairStrandSettings = previous;
            }
            throw failure;
        }
    }

    private static boolean castsShadow(HairStrandSettings settings) {
        return settings.active() && settings.castsShadow();
    }

    /**
     * Stages the caller-converted visible opaque triangle snapshot for the next
     * VCT update. The pipeline does not extract triangles from the scene; pass
     * {@code null} to record no voxelization update and keep the last volume.
     */
    public RenderPipeline vctSceneInput(VctSceneInput value) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException(
                    "VCT scene input is only allowed at frame boundaries");
        }
        vctSceneInput = value;
        return this;
    }

    /** Current staged VCT triangle snapshot, or {@code null} when none was supplied. */
    public VctSceneInput vctSceneInput() {
        return vctSceneInput;
    }

    /**
     * Stages the dirty world boxes that bound every scene change since the last
     * successful voxel update. An empty list with a new revision is a full update.
     */
    public RenderPipeline vctDirtyBoxes(List<VctBox> value) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException(
                    "VCT dirty regions are only allowed at frame boundaries");
        }
        vctDirtyBoxes = List.copyOf(Objects.requireNonNull(value, "dirtyBoxes"));
        return this;
    }

    /** Current staged VCT dirty-region list; immutable and empty by default. */
    public List<VctBox> vctDirtyBoxes() {
        return vctDirtyBoxes;
    }

    /** Stages an explicit probe batch for the VCT cone trace, or {@code null} for none. */
    public RenderPipeline vctProbes(VctProbeBatch value) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException("VCT probe batches are only allowed at frame boundaries");
        }
        vctProbeBatch = value;
        return this;
    }

    /** Current staged explicit VCT probe batch, or {@code null}. */
    public VctProbeBatch vctProbes() {
        return vctProbeBatch;
    }

    /**
     * Sets the scene-wide receiver proxies used by the first VCT composite
     * version. These are scalars, so no generation rebuild is required.
     */
    public RenderPipeline vctSurface(float roughness, Vector3f albedo) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException("VCT surface proxies are only allowed at frame boundaries");
        }
        if (!Float.isFinite(roughness) || roughness < 0.0f || roughness > 1.0f) {
            throw new IllegalArgumentException("VCT surface roughness must be in [0, 1]");
        }
        Objects.requireNonNull(albedo, "albedo");
        if (!albedo.isFinite() || albedo.x < 0.0f || albedo.y < 0.0f || albedo.z < 0.0f) {
            throw new IllegalArgumentException("VCT surface albedo must be finite and non-negative");
        }
        vctSurfaceRoughness = roughness;
        vctSurfaceAlbedo.set(albedo);
        return this;
    }

    public float vctSurfaceRoughness() {
        return vctSurfaceRoughness;
    }

    /** Defensive copy of the scene-wide VCT receiver albedo. */
    public Vector3f vctSurfaceAlbedo() {
        return new Vector3f(vctSurfaceAlbedo);
    }

    /**
     * Places the procedural hair lock for the forward and shadow passes. The
     * model matrix may change per frame without a generation rebuild.
     */
    public RenderPipeline hairStrandModel(Matrix4fc value) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException("hair model changes are only allowed at frame boundaries");
        }
        Objects.requireNonNull(value, "model");
        if (!value.isFinite()) {
            throw new IllegalArgumentException("hair model matrix must be finite");
        }
        hairStrandModel.set(value);
        // The hair lock is a dynamic shadow caster that is not part of the
        // scene/light/planner cache key. Moving it must invalidate the static
        // tiles, otherwise the previous placement's shadow survives.
        if (activeGeneration != null && hairStrandSettings.active()) {
            activeGeneration.shadowCache().invalidateAll();
        }
        return this;
    }

    /** @return a copy of the current hair-lock placement */
    public Matrix4f hairStrandModel() {
        return new Matrix4f(hairStrandModel);
    }

    /** Configures the finite water surface before the first generation is built. */
    public RenderPipeline waterSurface(WaterSurfaceSettings value) {
        requireGpuOwner();
        if (activeGeneration != null) throw new IllegalStateException("use applyWaterSurface after build");
        WaterSurfaceSettings replacement = Objects.requireNonNull(value, "waterSurface");
        if (waterSurfaceSettings.active() != replacement.active()) {
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        }
        waterSurfaceSettings = replacement;
        return this;
    }

    /** Current finite water surface configuration; does not expose GPU resources. */
    public WaterSurfaceSettings waterSurface() {
        return waterSurfaceSettings;
    }

    /** Publishes scalar water changes or a fully built replacement generation at a frame boundary. */
    public void applyWaterSurface(WaterSurfaceSettings value) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) { applyWaterSurfaceInternal(value); }
        } else {
            applyWaterSurfaceInternal(value);
        }
        publishCompatibilityFeatureState();
    }

    private void applyWaterSurfaceInternal(WaterSurfaceSettings value) {
        WaterSurfaceSettings replacement = Objects.requireNonNull(value, "waterSurface");
        if (executing) throw new IllegalStateException("water surface changes are only allowed at frame boundaries");
        if (replacement.equals(waterSurfaceSettings)) return;
        if (!replacement.active() && causticsSettings.active()) {
            throw new IllegalArgumentException(
                    "caustics require an active finite water surface");
        }
        if (activeGeneration == null) { waterSurface(replacement); return; }
        WaterSurfaceSettings previous = waterSurfaceSettings;
        PipelineGeneration previousGeneration = activeGeneration;
        waterSurfaceSettings = replacement;
        try {
            PipelineTopology topology = captureTopology(scene,
                    activeGeneration.graph().width(), activeGeneration.graph().height(),
                    volumetricParticleSettings, stochasticTransparencySettings);
            if (!topology.equals(activeGeneration.topology())) {
                activateGeneration(createGeneration(scene, topology, activeGeneration));
            }
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) waterSurfaceSettings = previous;
            throw failure;
        }
    }

    /** Current advanced GTAO configuration; does not expose private resources. */
    public com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings gtao() { return postProcessSettings.gtao(); }

    /**
     * Current advanced screen-space indirect diffuse request.  The request is
     * owned by {@link PostProcessSettings}; the unified feature control only
     * reports and publishes its effective participation.
     */
    public SsgiSettings ssgi() { return postProcessSettings.ssgi(); }

    /** Current advanced adaptive SSR sampling request; does not own the SSR pass. */
    public AdaptiveSamplingSettings adaptiveSampling() {
        return postProcessSettings.adaptiveSampling();
    }

    /** Current advanced variable-rate-shading request; the pass remains post-process owned. */
    public VariableRateShadingSettings variableRateShading() {
        return postProcessSettings.variableRateShading();
    }

    /**
     * Package-level capability seam for JVM feature-control tests.  Production
     * always uses {@link VariableRateShadingCapability#inspectCurrent()}; an
     * injected value can only describe a capability, not create a GL pass.
     */
    void variableRateShadingCapabilitySupplier(
            Supplier<VariableRateShadingCapability> supplier) {
        variableRateShadingCapabilitySupplier =
                Objects.requireNonNull(supplier, "supplier");
    }

    /** Updates only GTAO at a synchronous render-owner frame boundary. */
    public void applyGtao(com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings replacement) {
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) { applyGtaoInternal(replacement); }
        } else applyGtaoInternal(replacement);
        publishCompatibilityFeatureState();
    }

    private void applyGtaoInternal(com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings replacement) {
        Objects.requireNonNull(replacement, "gtao");
        if (executing) throw new IllegalStateException("GTAO changes are only allowed at frame boundaries");
        if (replacement.equals(gtao())) return;
        var previous = postProcessSettings;
        var before = activeGeneration;
        postProcessSettings = previous.withGtao(replacement);
        try {
            if (before != null) {
                var topology = captureTopology(scene, before.graph().width(),
                        before.graph().height(), volumetricParticleSettings,
                        stochasticTransparencySettings);
                if (!topology.equals(before.topology())) activateGeneration(createGeneration(scene, topology, before));
                else {
                    if (before.gtao() != null) {
                        before.gtao().applySettings(replacement);
                        before.postProcess().invalidateTemporalHistory(TaaInvalidationReason.GTAO_SETTINGS);
                    }
                }
            }
            topologySettingsRevision = Math.incrementExact(topologySettingsRevision);
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == before) postProcessSettings = previous;
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
        requireGpuOwner();
        if (embedded && gpuOwner != null) {
            try (HostGlState ignored = HostGlState.capture()) { applyVisualSettingsInternal(value, environment); }
        } else applyVisualSettingsInternal(value, environment);
        publishCompatibilityFeatureState();
    }

    private void applyVisualSettingsInternal(VisualSettings value, PbrEnvironment environment) {
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
                validateCompositionDependencies();
                applyProfileDirectionalLight(replacement);
                visualSettingsRevision = Math.incrementExact(visualSettingsRevision);
                return;
            }
            validateCompositionDependencies();
            PipelineTopology candidateTopology = captureTopology(scene,
                    activeGeneration.graph().width(), activeGeneration.graph().height(),
                    volumetricParticleSettings, stochasticTransparencySettings);
            boolean outdoorPassShapeChanged = outdoorPassShapeChanged(activeGeneration,
                    outdoorEnvironment);
            if (!activeGeneration.topology().equals(candidateTopology) || outdoorPassShapeChanged
                    || environmentChanged) {
                PipelineGeneration candidate = createGeneration(scene, candidateTopology, activeGeneration);
                try {
                    activateGeneration(candidate);
                } finally {
                    if (activeGeneration == candidate) {
                        synchronizeOutdoorSun(scene);
                        applyProfileDirectionalLight(replacement);
                        if (environmentChanged || lightingChanged || outdoorChanged) {
                            invalidateLightingHistory(candidate);
                        }
                        visualSettingsRevision = Math.incrementExact(visualSettingsRevision);
                    }
                }
                return;
            } else {
                activeGeneration.postProcess().updateVisualScalars(replacement.manualExposure(),
                        replacement.bloom());
                applyIblOverrides(activeGeneration);
                if (outdoorChanged) {
                    synchronizeOutdoorSun(scene);
                }
                if (lightingChanged || outdoorChanged) {
                    invalidateLightingHistory(activeGeneration);
                } else if (!previousVolume.equals(volumetricFogSettings)) {
                    activeGeneration.postProcess().invalidateTemporalHistory(TaaInvalidationReason.VOLUMETRIC_SETTINGS);
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
        publishCompatibilityFeatureState();
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
        publishCompatibilityFeatureState();
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
            PipelineTopology candidateTopology = captureTopology(scene,
                    activeGeneration.graph().width(), activeGeneration.graph().height(),
                    volumetricParticleSettings, stochasticTransparencySettings);
            boolean outdoorPassShapeChanged = outdoorPassShapeChanged(activeGeneration, replacement);
            if (!replaceEnvironment && activeGeneration.topology().equals(candidateTopology) && !outdoorPassShapeChanged) {
                // The active sky reads the immutable appearance at frame boundaries.
                synchronizeOutdoorSun(scene);
                if (!previous.equals(replacement)) invalidateLightingHistory(activeGeneration);
                return;
            }
            PipelineGeneration candidate = createGeneration(scene, candidateTopology, activeGeneration);
            try {
                activateGeneration(candidate);
            } finally {
                if (activeGeneration == candidate) {
                    synchronizeOutdoorSun(scene);
                    invalidateLightingHistory(candidate);
                }
            }
        } catch (RuntimeException | Error failure) {
            if (activeGeneration == previousGeneration) outdoorEnvironment = previous;
            throw failure;
        }
    }

    private static void invalidateLightingHistory(PipelineGeneration generation) {
        generation.postProcess().invalidateTemporalHistory(TaaInvalidationReason.LIGHTING_SETTINGS);
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
                requireGeneration().volumetric() == null ? null : requireGeneration().volumetric().borrowedView());
        return this;
    }

    /** Explicitly opts a custom HDR recorder out of volumetric attenuation. */
    public RenderPipeline hdrVfxFogOptOut(boolean value) {
        if (activeGeneration != null) throw new IllegalStateException("HDR VFX policy must be configured before build");
        hdrVfxFogOptOut=value; return this;
    }

    /**
     * Publishes one complete immutable effect-light input for the next frame.
     *
     * <p>Values are merged with the ordinary Scene lights before the frame
     * freeze, so the visual recording and the surface lighting consume the same
     * revision.  The call never mutates or removes a user Scene light.  Call on
     * the pipeline owner thread; the list is copied.</p>
     */
    public RenderPipeline vfxLights(List<VfxLight> lights) {
        Objects.requireNonNull(lights, "lights");
        if (lights.size() > VfxLightBridge.MAXIMUM_REQUESTED_LIGHTS) {
            throw new IllegalArgumentException("vfx light input exceeds "
                    + VfxLightBridge.MAXIMUM_REQUESTED_LIGHTS + " entries");
        }
        java.util.Set<VfxLight.Key> keys = new java.util.HashSet<>();
        for (VfxLight light : lights) {
            Objects.requireNonNull(light, "light");
            if (!keys.add(light.key())) {
                throw new IllegalArgumentException("duplicate vfx light key " + light.key());
            }
        }
        pendingVfxLights = List.copyOf(lights);
        vfxLightInputRevision = Math.incrementExact(vfxLightInputRevision);
        return this;
    }

    public List<VfxLight> vfxLights() {
        return pendingVfxLights;
    }

    /** Per-frame maximum of temporary effect lights; 0 disables them without touching scene lights. */
    public RenderPipeline maxVfxLights(int value) {
        if (value < 0) throw new IllegalArgumentException("maxVfxLights must be non-negative");
        maxVfxLights = value;
        return this;
    }

    public int maxVfxLights() {
        return maxVfxLights;
    }

    /** Bounded last-frame effect-light observation; never contains GPU handles. */
    public VfxLightDiagnostics vfxLightDiagnostics() {
        return vfxLightDiagnostics;
    }

    public void build() {
        requireGpuOwner();
        if (embedded) {
            try (HostGlState ignored = HostGlState.capture()) {
                buildInternal();
            }
            return;
        }
        buildInternal();
    }

    private void buildInternal() {
        RenderFeatureSettings requestedFeatures = features.pending() ? features.requested() : null;
        RenderFeatureSettings effectiveFeatures = requestedFeatures;
        long requestedRevision = features.requestedRevision();
        RenderFeaturePolicy.Resolved resolvedFeatures = null;
        if (requestedFeatures != null) {
            try {
                resolvedFeatures = RenderFeaturePolicy.resolve(requestedFeatures, featureFacts());
                effectiveFeatures = effectiveFeatureSettings(requestedFeatures, resolvedFeatures);
            } catch (RuntimeException failure) {
                features.failed(failure.getMessage());
                throw failure;
            }
            applyFeatureFields(effectiveFeatures);
        }
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
        PipelineTopology candidateTopology = captureTopology(scene, w, h,
                volumetricParticleSettings, stochasticTransparencySettings);
        validateCompositionDependencies();
        PipelineGeneration candidate = createGeneration(scene, candidateTopology);
        synchronizeOutdoorSun(scene);
        activateGeneration(candidate);
        if (requestedFeatures != null) {
            features.publish(requestedFeatures, effectiveFeatures, requestedRevision,
                    resolvedFeatures.activities());
        } else {
            publishCompatibilityFeatureState();
        }
    }

    private PipelineTopology captureTopology(PipelineGeneration generation,
                                             VolumetricParticleSettings particles,
                                             StochasticTransparencySettings stochastic) {
        return captureTopology(scene, generation.graph().width(),
                generation.graph().height(), particles, stochastic);
    }

    private PipelineTopology captureTopology(Scene captureScene, int width, int height,
                                             VolumetricParticleSettings particles,
                                             StochasticTransparencySettings stochastic) {
        return PipelineTopology.capture(captureScene, settings, postProcessSettings,
                width, height, hdrVfxEnabled && hdrVfxRecorder != null, embedded,
                directionalCascadeSettings, localShadowSettings, volumetricFogSettings,
                directionalShadowsEnabled, pointShadowsEnabled, spotShadowsEnabled,
                waterSurfaceSettings, particles, stochastic, volumetricCloudSettings,
                smokeSimulationSettings, smokeRenderSettings,
                directionalShadowMap.settings().cloudShadows(),
                weightedOitSettings, weightedOitSetDeclared, planarReflectionSettings,
                causticsSettings,
                ddgiSettings, vctSettings, hairStrandSettings,
                directionalShadowMap.settings().contactShadows());
    }

    /** Captures the topology from the pipeline's current immutable settings. */
    private PipelineTopology captureTopology(Scene captureScene, int width, int height) {
        return captureTopology(captureScene, width, height, volumetricParticleSettings,
                stochasticTransparencySettings);
    }

    /**
     * Colour producer consumed by the volumetric transparency replay. The
     * particle bridge, when present, sits after the fog composite so the
     * recorded order is fog composite -&gt; particles -&gt; transparency/HDR VFX.
     */
    static String volumetricTransparentColorSource(boolean particlesActive) {
        return particlesActive ? VolumetricParticlePass.PASS_NAME
                : VolumetricPassBuilder.COMPOSITE_PASS;
    }

    /**
     * Declares the particle colour bridge between the fog composite and the
     * transparency replay. The pass owns the copied/composited image; this graph
     * pass hands that image back into the shared colour chain so the ordered
     * consumer chain remains fog composite -> particles -> transparency/HDR VFX.
     */
    static void addVolumetricParticlePass(RenderGraph graph, String colorSourcePass,
                                          String depthProducer, PassExecutor executor) {
        graph.addPass(VolumetricParticlePass.PASS_NAME)
                .createColor(VolumetricParticlePass.COLOR_TEXTURE, RenderFormat.RGBA16F)
                .noClear()
                .dependsOn(colorSourcePass)
                .dependsOn(depthProducer)
                .execute(executor);
    }

    /**
     * Declares the in-place stochastic composite over the current scene colour.
     * The pass borrows only the colour storage: it has no depth attachment and
     * never writes the shared opaque depth.
     */
    static void addStochasticTransparencyPass(RenderGraph graph, String colorTexture,
                                              String colorProducer, RenderFormat format,
                                              int sampleCount, String depthProducer,
                                              PassExecutor executor) {
        var pass = graph.addPass(StochasticTransparencyPass.PASS_NAME)
                .shareColorTextures(List.of(colorTexture), List.of(format), colorProducer,
                        sampleCount)
                .noClear();
        if (!depthProducer.equals(colorProducer)) {
            pass.dependsOn(depthProducer);
        }
        pass.execute(executor);
    }

    /**
     * Declares the cloud-layer colour bridge after every scene-colour composite.
     * The graph pass owns its RGBA16F target, has no depth attachment, and lets
     * the next colour consumer read the pass-owned composited image.
     */
    static void addVolumetricCloudPass(RenderGraph graph, String colorSourcePass,
                                       String depthProducer, PassExecutor executor) {
        var pass = graph.addPass(VolumetricCloudPass.PASS_NAME)
                .createColor(VolumetricCloudPass.COLOR_TEXTURE, RenderFormat.RGBA16F)
                .noClear()
                .dependsOn(colorSourcePass);
        if (!depthProducer.equals(colorSourcePass)) {
            pass.dependsOn(depthProducer);
        }
        pass.execute(executor);
    }

    /**
     * Declares the smoke volume colour bridge after the fog/particle/cloud
     * composite. The pass has no depth attachment and never writes the shared
     * opaque depth.
     */
    static void addSmokeVolumePass(RenderGraph graph, String colorSourcePass,
                                   String depthProducer, PassExecutor executor) {
        var pass = graph.addPass(SmokeVolumePass.PASS_NAME)
                .createColor(SmokeVolumePass.COLOR_TEXTURE, RenderFormat.RGBA16F)
                .noClear()
                .dependsOn(SmokeSimulation.PASS_NAME)
                .dependsOn(colorSourcePass);
        if (!depthProducer.equals(colorSourcePass)) {
            pass.dependsOn(depthProducer);
        }
        pass.execute(executor);
    }

    /** Declares the deterministic fixed-step smoke simulation compute stage. */
    static void addSmokeSimulationPass(RenderGraph graph, PassExecutor executor) {
        graph.addPass(SmokeSimulation.PASS_NAME)
                .computeOnly()
                .execute(executor);
    }

    /**
     * Declares the in-place weighted-OIT resolve over the current scene colour.
     * The pass owns its two accumulation attachments; the graph pass borrows
     * only colour storage and has no depth attachment, so the shared opaque
     * depth is never written.
     */
    static void addWeightedOitPass(RenderGraph graph, String colorTexture,
                                   String colorProducer, RenderFormat format, int sampleCount,
                                   PassExecutor executor) {
        graph.addPass(WeightedOitPass.PASS_NAME)
                .shareColorTextures(List.of(colorTexture), List.of(format), colorProducer,
                        sampleCount)
                .noClear()
                .execute(executor);
    }

    /**
     * Declares the independent planar-reflection subview between the scene
     * colour producer and its reflection consumers.  The pass owns its
     * RGBA16F+depth target; the graph pass borrows the scene colour storage
     * only so the executor can restore that framebuffer after recording.
     */
    static void addPlanarReflectionPass(RenderGraph graph, String colorTexture,
                                        String colorProducer, RenderFormat format, int sampleCount,
                                        PassExecutor executor) {
        graph.addPass(PlanarReflectionPass.PASS_NAME)
                .shareColorTextures(List.of(colorTexture), List.of(format), colorProducer,
                        sampleCount)
                .noClear()
                .execute(executor);
    }

    /**
     * Declares the water-caustics contribution as an in-place scene-colour
     * writer after the opaque/transparent producer and before the water
     * consumer.  The pass owns its colour target, samples the shared raster
     * depth and never writes that depth.
     */
    static void addCausticsPass(RenderGraph graph, String colorTexture, String colorProducer,
                                RenderFormat format, int sampleCount, String depthTexture,
                                String depthProducer, PassExecutor executor) {
        var pass = graph.addPass(CausticsPass.PASS_NAME)
                .shareColorTextures(List.of(colorTexture), List.of(format), colorProducer,
                        sampleCount)
                .noClear();
        if (!depthProducer.equals(colorProducer)) pass.dependsOn(depthProducer);
        pass.execute(executor);
    }

    /**
     * Declares the screen-space contact-shadow darkening as an in-place scene
     * colour writer after the opaque/transparent producer and before the water
     * consumer. The pass owns its colour target, samples the shared
     * single-sample raster depth and never writes that depth.
     */
    static void addScreenSpaceContactShadowPass(RenderGraph graph, String colorTexture,
                                                String colorProducer, RenderFormat format,
                                                int sampleCount, String depthTexture,
                                                String depthProducer, PassExecutor executor) {
        var pass = graph.addPass(ScreenSpaceContactShadowPass.PASS_NAME)
                .shareColorTextures(List.of(colorTexture), List.of(format), colorProducer,
                        sampleCount)
                .noClear();
        if (!depthProducer.equals(colorProducer)) pass.dependsOn(depthProducer);
        pass.execute(executor);
    }

    /**
     * Declares the DDGI probe update (after the opaque forward pass) and the
     * screen-space indirect bridge that copies the current HDR colour and adds
     * the probe contribution before the post-process chain. The indirect pass
     * borrows the shared single-sample depth/normal and never writes depth.
     */
    static void addDdgiPasses(RenderGraph graph, String colorProducer, String depthProducer,
                              String normalProducer, RenderFormat format,
                              PassExecutor probeExecutor, PassExecutor indirectExecutor) {
        graph.addPass(DdgiProbeVolume.PASS_NAME)
                .writeToExternalTarget()
                .noClear()
                .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                .execute(probeExecutor);
        var indirect = graph.addPass(DdgiIndirectPass.PASS_NAME)
                .createColor(DDGI_INDIRECT_COLOR, format)
                .noClear()
                .dependsOn(DdgiProbeVolume.PASS_NAME)
                .dependsOn(colorProducer);
        if (!depthProducer.equals(colorProducer)
                && !depthProducer.equals(DdgiProbeVolume.PASS_NAME)) {
            indirect.dependsOn(depthProducer);
        }
        if (!normalProducer.equals(colorProducer)
                && !normalProducer.equals(depthProducer)
                && !normalProducer.equals(DdgiProbeVolume.PASS_NAME)) {
            indirect.dependsOn(normalProducer);
        }
        indirect.execute(indirectExecutor);
    }

    /**
     * Declares the bounded VCT chain: dirty-region voxelization after the opaque
     * forward pass, optional explicit probe trace, then the screen-space
     * composite that copies the current colour and adds cone-traced radiance
     * before Bloom/TAA. The composite never writes the shared depth.
     */
    static void addVctPasses(RenderGraph graph, String colorProducer, String depthProducer,
                             RenderFormat format, PassExecutor voxelizeExecutor,
                             PassExecutor traceExecutor, PassExecutor compositeExecutor) {
        graph.addPass(VCT_VOXELIZE_PASS)
                .computeOnly()
                .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                .execute(voxelizeExecutor);
        graph.addPass(VCT_TRACE_PASS)
                .computeOnly()
                .dependsOn(VCT_VOXELIZE_PASS)
                .execute(traceExecutor);
        var composite = graph.addPass(VCT_COMPOSITE_PASS)
                .createColor(VCT_COLOR, format)
                .noClear()
                .dependsOn(VCT_VOXELIZE_PASS)
                .dependsOn(VCT_TRACE_PASS)
                .dependsOn(colorProducer);
        if (!depthProducer.equals(colorProducer)) {
            composite.dependsOn(depthProducer);
        }
        composite.execute(compositeExecutor);
    }

    /**
     * Declares the hair forward pass in the transparent HDR stage. It aliases
     * the geometry colour and raster depth; it never creates a second colour
     * target and never writes the shared depth beyond normal depth testing
     * (CUTOUT writes depth exactly like the sorted transparent contract does
     * not; SORTED_ALPHA keeps depth writes disabled).
     */
    static void addHairStrandPass(RenderGraph graph, PipelineTopology topology,
                                  RenderSettings settings, String depthTexture,
                                  String depthProducer, PassExecutor executor) {
        graph.addPass(HairStrandPass.PASS_NAME)
                .shareColorTextures(List.of(PostProcessTargets.SCENE_COLOR),
                        List.of(ForwardPassBuilder.sceneColorFormat(settings)),
                        PostProcessTargets.GEOMETRY_PASS, topology.sampleCount())
                .shareDepthTexture(depthTexture, depthProducer)
                .noClear()
                .execute(executor);
    }
    private PipelineGeneration createGeneration(Scene generationScene,
                                                PipelineTopology topology) {
        return createGeneration(generationScene, topology, null);
    }

    private PipelineGeneration createGeneration(Scene generationScene, PipelineTopology topology,
                                                PipelineGeneration reusable) {
        requireGpuOwner();
        collectRetiredGenerations(retiredGenerations.size() >= 4);
        if (gpuOwner == null) {
            com.kaleblangley.haikalat.backend.GlCapabilityContract.requireCurrent();
            gpuOwner = Thread.currentThread();
            gpuContext = org.lwjgl.opengl.GL.getCapabilities();
            glfwContext = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            maximumTextureDimension = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
        }
        if (reusable != null && !topology.sameManagedExtent(reusable.topology())) reusable = null;
        PipelineGeneration candidate = new PipelineGeneration(topology, reusable, generationScene == scene, maximumTextureDimension,
                volumetricFogSettings);
        lastCandidateGenerationId = candidate.id();
        boolean refraction = RefractionPass.presentIn(generationScene);
        if (atmosphereSettings != null && outdoorEnvironment.enabled()) {
            throw new IllegalStateException(
                    "atmosphere sky and stylized outdoor sky are mutually exclusive");
        }
        try {
            new PipelineFeaturePolicy(topology, pbrEnvironment != null).validate();
            new PipelineFeaturePolicy(topology, pbrEnvironment != null)
                    .validateRefractionWater(refraction);
            new PipelineFeaturePolicy(topology, pbrEnvironment != null)
                    .validateSsrComposition(postProcessSettings.ssr().active(), refraction);
            GtaoAllocationPlan.validateDeviceExtent(topology.width(), topology.height(), maximumTextureDimension);
            long retainedSurfaceBytes = activeGeneration == null ? 0 : activeGeneration.surfaceBytes();
            for (var retired : retiredGenerations) {
                retainedSurfaceBytes = Math.addExact(retainedSurfaceBytes, retired.generation.surfaceBytes());
            }
            candidate.prepareSurfaceBudget(reusable, retainedSurfaceBytes);
            if (Boolean.getBoolean("haikalat.test.failGtaoPrepare"))
                throw new IllegalStateException("injected GTAO CPU preparation failure");
            long retainedGtaoBytes = activeGeneration == null ? 0 : activeGeneration.retainedGtaoBytes();
            long retainedVolumeBytes = activeGeneration == null ? 0 : activeGeneration.retainedVolumeBytes();
            for (var retired : retiredGenerations) {
                retainedGtaoBytes = Math.addExact(retainedGtaoBytes, retired.generation.retainedGtaoBytes());
                retainedVolumeBytes = Math.addExact(retainedVolumeBytes, retired.generation.retainedVolumeBytes());
            }
            boolean sharedGtao = !Boolean.getBoolean("haikalat.test.gtaoDedicatedDepth");
            candidate.prepareModuleBudgets(generationScene, postProcessSettings.gtao(), sharedGtao,
                    retainedGtaoBytes, retainedVolumeBytes, clusteredLightingSettings);
            lastVolumeCandidatePeakBytes = candidate.volumeAllocation().peakBytes();
            if (topology.volumetricFog() && postProcessSettings.fog().enabled())
                throw new IllegalStateException("volumetric fog replaces analytic fog; disable the other fog path");
            if (topology.volumetricFog() && hdrVfxRecorder != null && fogAwareHdrVfxRecorder == null && !hdrVfxFogOptOut)
                throw new IllegalStateException("custom HDR VFX must use hdrVfxWithFog or explicitly opt out of fog");
            validatePbrVertexLayouts(generationScene);
            if (topology.volumetricParticles()) {
                volumetricParticleSettings.validateFogComposition(volumetricFogSettings);
            }
            if (topology.volumetricClouds() && topology.directionalShadow()) {
                var shadowClouds = directionalShadowMap.settings().cloudShadows();
                if (directionalShadowsEnabled && shadowClouds.active()
                        && !volumetricCloudSettings.coverageField().equals(shadowClouds)) {
                    throw new IllegalStateException(
                            "the visible cloud layer requires the same CloudShadowSettings coverage "
                                    + "field as the enabled directional cloud shadow");
                }
            }
            candidate.assembleGraph(clusteredLightingSettings, Boolean.getBoolean(FULL_SCAN_BENCHMARK_PROPERTY));
            candidate.assembleVolumetric(volumetricFogSettings, generationScene, reusable,
                    (res, cmd, shader) -> recordVolumeShadowInputs(requireGeneration(), res, cmd, shader));
            candidate.assembleVolumetricParticles(volumetricParticleSettings, reusable);
            candidate.assembleVolumetricClouds(volumetricCloudSettings);
            candidate.assembleSmoke(smokeSimulationSettings, smokeRenderSettings, reusable);
            candidate.assembleStochasticTransparency(stochasticTransparencySettings, reusable);
            candidate.assembleWeightedOit(weightedOitSettings, reusable);
            candidate.assemblePlanarReflection(planarReflectionSettings, reusable);
            candidate.assembleCaustics(causticsSettings, reusable);
            candidate.assembleScreenSpaceContactShadows(
                    directionalShadowMap.settings().contactShadows(), reusable);
            candidate.assembleDdgi(ddgiSettings, reusable);
            candidate.assembleVct(vctSettings, reusable);
            candidate.assembleHairStrands(hairStrandSettings, reusable);
            candidate.assembleEnvironment(pbrEnvironment, outdoorEnvironment.enabled(),
                    atmosphereSettings);
            if (reusable == null || reusable.pbrMaterialBinder() != candidate.pbrMaterialBinder()) applyIblOverrides(candidate);
            PassExecutor postVfx = topology.volumetricFog() || !hdrVfxEnabled
                    ? null : hdrVfxRecorder;
            candidate.assemblePostProcess(settings, postProcessSettings, postVfx, reactiveExecutor());
            candidate.assembleGtao(postProcessSettings.gtao(), reusable, instanced != null, sharedGtao);
            candidate.assembleSurfacePasses(reusable);
            candidate.assembleDrawing(new GenerationDrawHost(candidate), directionalShadowMap,
                    pointShadowAtlas, spotShadowAtlas, directionalCascadeSettings,
                    localShadowSettings, instanced, directLightingEnabled,
                    imageBasedLightingEnabled);
            ForwardPassBuilder.addForwardPasses(candidate.graph(), settings, generationScene,
                    postProcessSettings,
                    directionalShadowMap, directionalCascadeSettings,
                    pointShadowAtlas, spotShadowAtlas, topology,
                    localShadowSettings.cacheStaticTiles(),
                    shadowExecutor(), pointShadowExecutor(), spotShadowExecutor(),
                    surfaceExecutor(candidate), surfaceResolveExecutor(candidate), geometryExecutor(),
                    refraction);
            if (candidate.smokeSimulation() != null) {
                // The simulation is a deterministic compute stage: it has no
                // colour/depth consumer and no dependency on the visible frame.
                addSmokeSimulationPass(candidate.graph(), smokeSimulationExecutor(candidate));
            }
            if (candidate.hairStrands() != null) {
                var rawDepth = topology.sceneSurface().raw(SceneBufferChannel.DEPTH);
                if (rawDepth == null) {
                    throw new IllegalStateException(
                            "hair strands require the shared raster depth for depth testing");
                }
                addHairStrandPass(candidate.graph(), topology, settings, rawDepth.name(),
                        rawDepth.producer(), hairStrandExecutor(candidate));
            }
            String sceneColorTexture = PostProcessTargets.SCENE_COLOR;
            String sceneColorProducer = PostProcessTargets.GEOMETRY_PASS;
            VolumetricSurfaceInputs volumeInputs = null;
            if (candidate.volumetric() != null) {
                volumeInputs = new VolumetricSurfaceInputs(candidate.surfaceInputs());
                candidate.volumetric().addColorPrePasses(candidate.graph(), generationScene,
                        topology, volumeInputs);
                String transparentColorSource = volumetricTransparentColorSource(
                        candidate.volumetricParticles() != null);
                if (candidate.volumetricParticles() != null) {
                    // Particles consume the fog-composited HDR colour and opaque depth,
                    // then hand their pass-owned target back to the graph colour chain
                    // before the transparency replay and HDR VFX.
                    var singleDepth = topology.sceneSurface().single(SceneBufferChannel.DEPTH);
                    if (singleDepth == null) {
                        throw new IllegalStateException(
                                "volumetric particles require the shared single-sample opaque depth");
                    }
                    addVolumetricParticlePass(candidate.graph(),
                            VolumetricPassBuilder.COMPOSITE_PASS, singleDepth.producer(),
                            volumetricParticleExecutor(candidate));
                    transparentColorSource = volumetricTransparentColorSource(true);
                }
                candidate.volumetric().addTransparentPass(candidate.graph(), topology, volumeInputs,
                        volumetricTransparentExecutor(), transparentColorSource);
                candidate.volumetric().addReactivePass(candidate.graph(), topology, volumeInputs, volumetricReactiveExecutor());
                candidate.postProcess().sceneColorInput(VolumetricPassBuilder.SCENE_COLOR, VolumetricPassBuilder.TRANSPARENT_PASS);
                sceneColorProducer = VolumetricPassBuilder.TRANSPARENT_PASS;
            }
            java.util.List<String> waterDependencies = new java.util.ArrayList<>();
            if (candidate.planarReflection() != null) {
                // The reflection is an independent single-sample subview. It is
                // recorded after the opaque scene producer and before the water
                // reflection consumer, and never samples its own target.
                addPlanarReflectionPass(candidate.graph(), sceneColorTexture,
                        sceneColorProducer, ForwardPassBuilder.sceneColorFormat(settings),
                        topology.sampleCount(), planarReflectionExecutor(candidate,
                                sceneColorProducer));
                waterDependencies.add(PlanarReflectionPass.PASS_NAME);
            }
            if (candidate.caustics() != null) {
                var rasterDepth = topology.sceneSurface().raw(SceneBufferChannel.DEPTH);
                if (rasterDepth == null) {
                    throw new IllegalStateException(
                            "caustics require the shared raster scene depth");
                }
                // The pass adds linear light to the opaque scene colour before the
                // water pass consumes it; the first version has no water mask, so
                // compositing after the water pass would incorrectly light the
                // water surface itself.
                addCausticsPass(candidate.graph(), sceneColorTexture, sceneColorProducer,
                        ForwardPassBuilder.sceneColorFormat(settings), topology.sampleCount(),
                        rasterDepth.name(), rasterDepth.producer(),
                        causticsExecutor(candidate, sceneColorProducer));
                waterDependencies.add(CausticsPass.PASS_NAME);
                candidate.postProcess().sceneColorInput(sceneColorTexture,
                        CausticsPass.PASS_NAME);
                if (topology.sampleCount() > 1) {
                    candidate.postProcess().hdrResolveSource(CausticsPass.PASS_NAME);
                }
            }
            if (candidate.screenSpaceContactShadows() != null) {
                var singleDepth = topology.sceneSurface().single(SceneBufferChannel.DEPTH);
                if (singleDepth == null) {
                    throw new IllegalStateException(
                            "screen-space contact shadows require the shared single-sample scene depth");
                }
                // The pass darkens the opaque/transparent colour before water and
                // further transparent consumers; it never attaches or writes depth.
                addScreenSpaceContactShadowPass(candidate.graph(), sceneColorTexture,
                        sceneColorProducer, ForwardPassBuilder.sceneColorFormat(settings),
                        topology.sampleCount(), singleDepth.name(), singleDepth.producer(),
                        screenSpaceContactShadowExecutor(candidate, sceneColorProducer));
                waterDependencies.add(ScreenSpaceContactShadowPass.PASS_NAME);
                candidate.postProcess().sceneColorInput(sceneColorTexture,
                        ScreenSpaceContactShadowPass.PASS_NAME);
            }
            if (candidate.topology().waterSurface()) {
                candidate.assembleWaterSurface(waterExecutor(candidate, sceneColorProducer),
                        waterDependencies);
                candidate.postProcess().sceneColorInput(WaterSurfacePass.COLOR,
                        WaterSurfacePass.PASS_NAME);
                sceneColorTexture = WaterSurfacePass.COLOR;
                sceneColorProducer = WaterSurfacePass.PASS_NAME;
                if (candidate.topology().sampleCount() > 1) {
                    candidate.postProcess().hdrResolveSource(WaterSurfacePass.PASS_NAME);
                }
            }
            if (refraction) {
                String colourProducer = topology.volumetricFog()
                        ? VolumetricPassBuilder.TRANSPARENT_PASS
                        : PostProcessTargets.GEOMETRY_PASS;
                var sharedDepth = topology.sceneSurface().raw(
                        com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH);
                String depthTexture = sharedDepth == null
                        ? PostProcessTargets.SCENE_DEPTH : sharedDepth.name();
                String depthProducer = sharedDepth == null
                        ? PostProcessTargets.GEOMETRY_PASS : sharedDepth.producer();
                ForwardPassBuilder.addRefractionPasses(candidate.graph(), topology.sampleCount(),
                        ForwardPassBuilder.sceneColorFormat(settings), colourProducer,
                        depthTexture, depthProducer, refractionSourceExecutor(colourProducer),
                        refractionExecutor());
                candidate.postProcess().sceneColorInput(PostProcessTargets.SCENE_COLOR,
                        RefractionPass.PASS_NAME);
                sceneColorProducer = RefractionPass.PASS_NAME;
                if (topology.sampleCount() > 1) {
                    candidate.postProcess().hdrResolveSource(RefractionPass.PASS_NAME);
                }
            }
            if (candidate.weightedOit() != null) {
                // OIT is an alternative transparent-collection strategy: it resolves
                // over the final scene colour in place, after the main transparent
                // queue/water/refraction and before every post-process consumer. The
                // shared colour target has no depth attachment.
                addWeightedOitPass(candidate.graph(), sceneColorTexture, sceneColorProducer,
                        ForwardPassBuilder.sceneColorFormat(settings), topology.sampleCount(),
                        weightedOitExecutor(candidate));
                candidate.postProcess().sceneColorInput(sceneColorTexture,
                        WeightedOitPass.PASS_NAME);
                if (topology.sampleCount() > 1) {
                    candidate.postProcess().hdrResolveSource(WeightedOitPass.PASS_NAME);
                }
                sceneColorProducer = WeightedOitPass.PASS_NAME;
            }
            if (candidate.stochasticTransparency() != null) {
                // The collection composites over the final scene colour in place and
                // before every post-process fog/DOF/Bloom/TAA consumer. Its shared
                // colour target has no depth attachment, so opaque depth is never
                // written by this pass.
                var singleDepth = topology.sceneSurface().single(SceneBufferChannel.DEPTH);
                if (singleDepth == null) {
                    throw new IllegalStateException(
                            "stochastic transparency requires the shared single-sample scene depth");
                }
                addStochasticTransparencyPass(candidate.graph(), sceneColorTexture,
                        sceneColorProducer, ForwardPassBuilder.sceneColorFormat(settings),
                        topology.sampleCount(), singleDepth.producer(),
                        stochasticTransparencyExecutor(candidate));
                candidate.postProcess().sceneColorInput(sceneColorTexture,
                        StochasticTransparencyPass.PASS_NAME);
                if (topology.sampleCount() > 1) {
                    candidate.postProcess().hdrResolveSource(StochasticTransparencyPass.PASS_NAME);
                }
            }
            if (candidate.volumetricClouds() != null) {
                // Clouds integrate their own medium once, after every colour
                // composite (atmosphere/fog/particles/transparency/water/glass)
                // and after the stochastic collection, but before the
                // post-process HDR chain. The pass-owned target is handed back
                // to the graph colour chain with one full-screen copy.
                var singleDepth = topology.sceneSurface().single(SceneBufferChannel.DEPTH);
                if (singleDepth == null) {
                    throw new IllegalStateException(
                            "volumetric clouds require the shared single-sample opaque depth");
                }
                addVolumetricCloudPass(candidate.graph(), sceneColorProducer,
                        singleDepth.producer(), volumetricCloudExecutor(candidate, sceneColorProducer));
                candidate.postProcess().sceneColorInput(VolumetricCloudPass.COLOR_TEXTURE,
                        VolumetricCloudPass.PASS_NAME);
                sceneColorTexture = VolumetricCloudPass.COLOR_TEXTURE;
                sceneColorProducer = VolumetricCloudPass.PASS_NAME;
            }
            if (candidate.smokeVolume() != null) {
                // Smoke consumes the field advanced by the simulation compute
                // stage and composites after the fog/particle/cloud colour chain,
                // before the post-process HDR chain. Its own shader declares only
                // the density field and the opaque depth, so every medium is
                // integrated exactly once.
                var singleDepth = topology.sceneSurface().single(SceneBufferChannel.DEPTH);
                if (singleDepth == null) {
                    throw new IllegalStateException(
                            "smoke volume rendering requires the shared single-sample opaque depth");
                }
                addSmokeVolumePass(candidate.graph(), sceneColorProducer,
                        singleDepth.producer(), smokeVolumeExecutor(candidate, sceneColorProducer));
                candidate.postProcess().sceneColorInput(SmokeVolumePass.COLOR_TEXTURE,
                        SmokeVolumePass.PASS_NAME);
                sceneColorTexture = SmokeVolumePass.COLOR_TEXTURE;
                sceneColorProducer = SmokeVolumePass.PASS_NAME;
            }
            if (candidate.ddgiIndirect() != null) {
                var singleDepth = topology.sceneSurface().single(SceneBufferChannel.DEPTH);
                var singleNormal = topology.sceneSurface().single(SceneBufferChannel.NORMAL);
                if (singleDepth == null || singleNormal == null) {
                    throw new IllegalStateException(
                            "DDGI requires the shared single-sample depth and world normal");
                }
                addDdgiPasses(candidate.graph(), sceneColorProducer, singleDepth.producer(),
                        singleNormal.producer(), ForwardPassBuilder.sceneColorFormat(settings),
                        ddgiProbeExecutor(candidate),
                        ddgiIndirectExecutor(candidate, sceneColorProducer));
                candidate.postProcess().sceneColorInput(DDGI_INDIRECT_COLOR,
                        DdgiIndirectPass.PASS_NAME);
                sceneColorTexture = DDGI_INDIRECT_COLOR;
                sceneColorProducer = DdgiIndirectPass.PASS_NAME;
            }
            if (candidate.vct() != null) {
                var singleDepth = topology.sceneSurface().single(SceneBufferChannel.DEPTH);
                if (singleDepth == null) {
                    throw new IllegalStateException(
                            "voxel cone tracing requires the shared single-sample scene depth");
                }
                addVctPasses(candidate.graph(), sceneColorProducer, singleDepth.producer(),
                        ForwardPassBuilder.sceneColorFormat(settings),
                        vctVoxelizeExecutor(candidate), vctTraceExecutor(candidate),
                        vctCompositeExecutor(candidate, sceneColorProducer));
                candidate.postProcess().sceneColorInput(VCT_COLOR, VCT_COMPOSITE_PASS);
                sceneColorTexture = VCT_COLOR;
                sceneColorProducer = VCT_COMPOSITE_PASS;
            }
            candidate.finishAssembly(previewController, pbrEnvironment,
                    PREVIEW_GENERATIONS.incrementAndGet(), previewEnabled);
            if (Boolean.getBoolean("haikalat.test.failVolumeGraph"))
                candidate.graph().addPass("InvalidVolumeCandidate").computeOnly().dependsOn("MissingVolumeProducer").execute((res, cmd) -> {});
            if (Boolean.getBoolean("haikalat.test.failGtaoGraph"))
                candidate.graph().addPass("InvalidGtaoCandidate").computeOnly().dependsOn("MissingGtaoProducer").execute((res, cmd) -> {});
            candidate.graph().compile();
            candidate.surfaceInputs().validate();
            candidate.postProcess().validateSurfaceInputs();
            if (volumeInputs != null) volumeInputs.validate();
            if (candidate.gtaoInputs() != null) candidate.gtaoInputs().validate();
            generationBuildCount++;
            lastFailureStage = "";
            return candidate;
        } catch (RuntimeException | Error failure) {
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
        return generation == null ? null : generation.graph();
    }

    /** Explicit, synchronous linear HDR diagnostic readback before Bloom/tone mapping. */
    public float[] captureLinearHdrRgbaFloat() {
        PipelineGeneration generation = activeGeneration;
        if (generation == null || executing) {
            throw new IllegalStateException("HDR capture requires a completed pipeline frame");
        }
        return generation.graph().readColorAttachmentRgbaFloat(
                generation.postProcess().linearHdrTextureName());
    }

    /** CPU snapshot of conservative fog allocation budgets; no GL calls or retirement waits. */
    public VolumetricAllocationDiagnostics volumetricAllocationDiagnostics() {
        if (gpuOwner != null && Thread.currentThread() != gpuOwner)
            throw new IllegalStateException("allocation diagnostics require the render owner thread");
        PipelineGeneration generation = activeGeneration;
        VolumetricAllocationPlan allocation = generation == null ? null : generation.volumeAllocation();
        long retiring = 0;
        for (var retired : retiredGenerations)
            retiring = Math.addExact(retiring, retired.generation.retainedVolumeBytes());
        return new VolumetricAllocationDiagnostics(allocation == null ? 0 : allocation.privateBytes(),
                allocation == null ? 0 : allocation.colorBytes(), retiring,
                lastVolumeCandidatePeakBytes, retiredGenerations.size());
    }

    public VolumetricFogDiagnostics volumetricFogDiagnostics() {
        PipelineGeneration generation=activeGeneration;
        if (generation==null || generation.volumetric()==null || executing)
            return VolumetricFogDiagnostics.UNAVAILABLE;
        var volume=generation.volumetric();var frame=volume.frameState().previous();
        if(frame==null) return VolumetricFogDiagnostics.UNAVAILABLE;
        var resources=volume.resources();var grid=frame.grid();var history=volume.historyPlan();
        return new VolumetricFogDiagnostics(volume.currentOutput().available(),generation.id(),frame.sequence(),
                grid.nx(),grid.ny(),grid.nz(),resources.bytes(),resources.residentBytes(),resources.historyValid(),
                history.count(),history.reason().name(),history.shadowRefit(),frame.successfulIndex(),
                frame.samplePhase(),frame.samplePhaseX(),frame.samplePhaseY(),frame.timeSeconds());
    }

    /** Synchronous diagnostics only; layer bounds use Nz+1 for the two prefix fields. */
    public float[] captureVolumetricSlice(VolumetricFogDiagnostics.Field field,int layer) {
        PipelineGeneration generation=activeGeneration;
        if(generation==null || generation.volumetric()==null || executing
                || !generation.volumetric().currentOutput().available())
            throw new IllegalStateException("volume slice capture requires a completed valid fog frame");
        var resources=generation.volumetric().resources();
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
        if(generation==null||generation.volumetric()==null||executing
                ||!generation.volumetric().currentOutput().available())
            throw new IllegalStateException("volume counter capture requires a completed valid fog frame");
        var volume=generation.volumetric();
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
        if(generation==null||generation.volumetric()==null||executing
                ||!generation.volumetric().currentOutput().available())
            throw new IllegalStateException("volume reactive capture requires a completed valid fog frame");
        String name=generation.topology().sceneSurface().reactive() != null
                ?com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.SCENE_REACTIVE
                :VolumetricPassBuilder.REACTIVE_TEXTURE;
        return generation.graph().readColorAttachmentRgbaFloat(name);
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
        requireGpuOwner();
        Objects.requireNonNull(candidateScene, "candidateScene");
        if (executing) {
            throw new IllegalStateException("scene replacement is only allowed at frame start");
        }
        PipelineGeneration generation = activeGeneration;
        if (generation == null) {
            scene = candidateScene;
            return;
        }
        PipelineTopology candidateTopology = captureTopology(candidateScene,
                generation.graph().width(), generation.graph().height(),
                volumetricParticleSettings, stochasticTransparencySettings);
        if (generation.topology().equals(candidateTopology)) {
            synchronizeOutdoorSun(candidateScene);
            scene = candidateScene;
            currentSceneFrame = null;
            currentShadowCasterPlan = null;
            shadowCasterPlanner.reset();
            activeFrameContext = null;
            frameStateInvalidationByMaterial.clear();
            resetTemporalHistory();
            if (generation.gtao() != null) generation.gtao().invalidateHistory(GtaoPasses.InvalidationReason.WORLD);
            sceneFastPathReplacementCount++;
            return;
        }
        PipelineGeneration candidate = createGeneration(candidateScene, candidateTopology, generation);
        synchronizeOutdoorSun(candidateScene);
        activateGeneration(candidate, candidateScene);
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
        String passName = generation == null ? null : generation.finalPassName();
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
        GraphPreviewRenderer renderer = generation == null ? null : generation.previewRenderer();
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
                    generation == null ? 0L : generation.id(), lastCandidateGenerationId,
                    lastRetiredGenerationId, generation == null ? "" : generation.topology().toString(),
                    false, Render3dDiagnostics.QueueSummary.EMPTY,
                    Render3dDiagnostics.VisibilitySummary.EMPTY,
                    Render3dDiagnostics.ShadowSummary.EMPTY,
                    Render3dDiagnostics.DepthResolveSummary.EMPTY,
                    Render3dDiagnostics.CacheSummary.EMPTY,
                    Render3dDiagnostics.AmbientOcclusionSummary.EMPTY,
                    ClusteredLightingDiagnostics.UNAVAILABLE, VfxLightDiagnostics.UNAVAILABLE,
                    lastFailureStage);
        }
        SceneRevisionSnapshot revision = context.revisions();
        var revisions = new Render3dDiagnostics.RevisionSummary(revision.membershipRevision(),
                revision.transformModelRevision(), revision.lightingRevision(),
                revision.materialRenderStateRevision(), revision.cameraRevision(),
                revision.topologySettingsRevision());
        List<String> reasons = new java.util.ArrayList<>(context.invalidation().reasons().stream().map(Enum::name).toList());
        if (lastFailureStage.isEmpty()) {
            String taaReason = generation.postProcess().completedTaaInvalidationReason();
            if (!taaReason.isEmpty()) reasons.add(taaReason);
            if (generation.gtao() != null && !generation.gtao().completedInvalidationReason().isEmpty())
                reasons.add(generation.gtao().completedInvalidationReason());
        }
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
                generation.shadowCache().lastTilesRendered(),
                generation.shadowCache().lastTilesReused(),
                generation.shadowCache().lastCacheHits(),
                generation.shadowCache().lastCacheMisses(), missReasons,
                shadowPlan.filterMode().name(), localShadowSettings.point().resolution(),
                pointWidth, pointHeight, localShadowSettings.spot().resolution(),
                spotWidth, spotHeight, depthBytes);
        boolean surfaceResolved = generation.topology().sceneSurface().hasSurface();
        boolean depthResolved = (generation.topology().fog() || generation.topology().hdrVfx()
                || surfaceResolved)
                && generation.topology().antiAliasingMode() == AntiAliasingMode.MSAA;
        var depth = new Render3dDiagnostics.DepthResolveSummary(depthResolved,
                depthResolved ? generation.topology().sampleCount() : 1, 1,
                generation.topology().width(), generation.topology().height());
        var caches = new Render3dDiagnostics.CacheSummary(visibility.forwardQueueReused(),
                visibility.shadowQueueReused(), visibility.modelCacheHits(),
                visibility.modelCacheMisses(), visibility.boundsCacheHits(),
                visibility.boundsCacheMisses(), generationBuildCount, generationFailureCount,
                generationReuseCount, generationBuildCount + generationFailureCount);
        var ambientOcclusion = generation.gtao() == null
                ? GtaoPasses.disabledDiagnostics(generation.topology().width(), generation.topology().height())
                : generation.gtao().diagnostics(generation.topology().width(), generation.topology().height());
        ClusteredLightingDiagnostics clustered = clusteredDiagnostics(generation);
        return new Render3dDiagnostics(visibility.available(), revisions,
                context.invalidation().bits(), reasons,
                generation.id(), lastCandidateGenerationId, lastRetiredGenerationId,
                generation.topology().toString(), lastFrameTopologyRebuilt, queues, visible,
                shadows, depth, caches, ambientOcclusion, clustered, vfxLightDiagnostics,
                lastFailureStage);
    }

    private ClusteredLightingDiagnostics clusteredDiagnostics(PipelineGeneration generation) {
        if (generation.clusteredLightingBinder() == null) {
            return ClusteredLightingDiagnostics.UNAVAILABLE;
        }
        ClusteredLightingResources.ClusterStorage storage =
                generation.clusteredResources().storage();
        FrameLightTable table = generation.clusteredLightingBinder().stagedTable();
        ClusteredLightingBinder.CounterSnapshot counters =
                generation.clusteredLightingBinder().tryCounterSnapshot();
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
        requireGpuOwner();
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
        requireGpuOwner();
        if (executing) throw new IllegalStateException("RenderPipeline frame is already executing");
        collectRetiredGenerations(false);
        long preparationStart = benchmarkCpuTimingEnabled ? System.nanoTime() : 0L;
        currentLightPackNanos = 0L;
        PipelineGeneration generation = activeGeneration;
        if (generation == null) {
            throw new IllegalStateException("RenderPipeline must be built before execute");
        }
        if (!target.isRenderable()) {
            return PresentationResult.SKIPPED_ZERO_EXTENT;
        }
        if (features.pending()) {
            // A paused pipeline only prepares a new snapshot when that snapshot
            // resumes output; otherwise the request stays pending without
            // allocating GPU resources.
            if (features.effective().enabled() || features.requested().enabled()) {
                applyPendingFeatureRequest();
            }
        } else if (features.status() != RenderFeatureStatus.FAILED) {
            // A failed request is a settled observation; refreshing it here
            // would silently turn FAILED into APPLIED on the next frame.
            publishCompatibilityFeatureState();
        }
        if (!features.effective().enabled()) {
            return PresentationResult.SKIPPED_PAUSED;
        }
        generation = requireGeneration();
        if (generation.graph().width() != target.width()
                || generation.graph().height() != target.height()) {
            resize(target.width(), target.height());
            generation = requireGeneration();
        }
        synchronizeHostImports(generation.graph(), target);
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
        if (generation.topology().waterSurface()) {
            waterTimeSeconds = Math.min(WATER_TIME_LIMIT_SECONDS,
                    waterTimeSeconds + Math.max(0.0f, deltaSeconds));
        }
        VfxLightBridge.Result mergedLights = vfxLightBridge.merge(scene.lightEntries(),
                pendingVfxLights, clusteredLightingSettings.maxLocalLights(), maxVfxLights,
                hdrVfxEnabled, features.effective().directLighting(), vfxLightInputRevision);
        vfxLightDiagnostics = mergedLights.diagnostics();
        activeFrameContext = RenderFrameContext.capture(scene,
                Objects.requireNonNull(camera, "camera"), target.width(), target.height(),
                deltaSeconds, activeFrameIndex, previewFrameSequence,
                topologySettingsRevision, lastFrameContext, mergedLights.extraEntries());
        executing = true;
        smokeSimulationAdvanced = false;
        boolean postProcessFrameStarted = false;
        String frameStage = "frame-setup";
        try {
            // Temporal camera/state capture is only paid when a temporal
            // consumer exists; the NONE/FXAA/MSAA paths must keep their
            // pre-v0.24.1 cost.
            boolean temporalActive = generation.sceneSurfacePass() != null
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
            generation.postProcess().beginFrame(postProcessPose(activeFrameContext, frameParameters));
            postProcessFrameStarted = true;
            FrameInvalidation invalidation = activeFrameContext.invalidation();
            if (generation.previewRenderer() != null) {
                generation.previewRenderer().prepare(device, previewFrameSequence);
            }
            // Membership changes need no global reset: new renderers start with
            // an invalid snapshot and removed renderers are pruned on commit.
            // Freeze renderer membership, model matrices, bounds and queues before any graph
            // callback can observe mutable Scene inputs.
            frameStage = "frame-snapshot";
            SceneFrame builtFrame = sceneFrame();
            if (generation.sceneSurfacePass() != null) {
                temporalSceneState.beginFrame(builtFrame);
            }
            activeFrameContext.verifySceneStable(scene, topologySettingsRevision);
            if (generation.gtao() != null) generation.gtao().prepareFrame(activeFrameContext, builtFrame,
                    generation.cameraUniforms(), instanced, generation.gtaoInputs(), generation.deformationDefaults());
            if (generation.volumetric() != null) {
                generation.volumetric().prepareTransparentVariants(builtFrame);
                generation.volumetric().shadowPolicy(directionalShadowMap.settings(),localShadowSettings,directionalCascadeSettings);
                generation.volumetric().prepareFrame(activeFrameContext, volumetricFogSettings,currentShadowFramePlan);
                generation.volumetric().rasterProjection(temporalFrameState.current().inverseJitteredProjection());
            }
            if (generation.gtao() != null) generation.gtao().synchronizeTemporalImport(generation.graph());
            generation.postProcess().synchronizeTemporalImports(generation.graph());
            if (generation.topology().sceneSurface().hasSurface() || generation.topology().sceneSurface().reactive() != null) {
                generation.surfaceInputs().beginFrame(activeFrameContext);
            }
            long preparationTotalNanos = benchmarkCpuTimingEnabled
                    ? System.nanoTime() - preparationStart : 0L;
            frameStage = "pass-callback";
            if (generation.volumetricClouds() != null) {
                // Marks the history candidate before any graph callback records
                // the layer; only frameSucceeded() publishes it.
                generation.volumetricClouds().beginFrame();
                generation.advanceCloudTime(activeFrameContext.frameSequence(), deltaSeconds);
            }
            generation.markSubmitted();
            PresentationResult result = generation.graph().execute(device, target);
            if (benchmarkCpuTimingEnabled) {
                long preparationOnly = Math.max(0L, preparationTotalNanos - currentLightPackNanos);
                lastBenchmarkCpuTiming = new BenchmarkCpuTiming(true, preparationOnly,
                        currentLightPackNanos, generation.graph().lastGraphRecordNanos(),
                        generation.graph().lastDeviceSubmitNanos());
            }
            frameStage = "frame-finalize";
            long commandRecordNanos = 0L;
            for (var pass : generation.graph().lastFrameProfile().passes()) {
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
                lastVisibilityStatistics = VisibilityStatistics.from(currentSceneFrame.statistics(),
                        commandRecordNanos, generation.graph().lastRecordedCommandCount(),
                        generation.graph().lastRecordedMatrixSnapshots(),
                        generation.graph().lastRecordedObjectPayloads(), shadowVisibleOverride);
            }
            // Fallible finalization first: nothing is published until every
            // temporal owner has completed its potentially failing work.
            generation.postProcess().prepareFrameSuccess();
            if (generation.volumetric() != null) generation.volumetric().prepareFrameSuccess();
            if (generation.sceneSurfacePass() != null) {
                temporalSceneState.prepareFinalization();
            }
            // Infallible publish phase.
            generation.postProcess().frameSucceeded();
            if (generation.stochasticTransparency() != null) {
                generation.stochasticTransparency().frameSucceeded();
            }
            if (generation.gtao() != null) generation.gtao().frameSucceeded();
            if (generation.volumetric() != null) generation.volumetric().frameSucceeded();
            if (generation.volumetricClouds() != null) {
                generation.volumetricClouds().frameSucceeded();
            }
            if (temporalActive) {
                temporalFrameState.commitSuccessfulFrame();
            }
            if (generation.sceneSurfacePass() != null) {
                temporalSceneState.commitSuccessfulFrame();
                if (instanced != null) instanced.commitFrame();
            }
            generation.shadowCache().frameSucceeded();
            generation.clusteredLightingBinder().frameSucceeded();
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
            if (generation.previewRenderer() != null) {
                generation.previewRenderer().frameSucceeded(previewFrameSequence);
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
            if (postProcessFrameStarted) generation.postProcess().frameFailed();
            if (generation.stochasticTransparency() != null) {
                generation.stochasticTransparency().frameFailed();
            }
            if (generation.gtao() != null) generation.gtao().frameFailed();
            if (generation.volumetric() != null) generation.volumetric().frameFailed();
            if (generation.volumetricClouds() != null) {
                generation.volumetricClouds().frameFailed();
            }
            if (smokeSimulationAdvanced) {
                // The GPU may have executed a partial simulation; replay from a
                // known zero field instead of continuing an unknown step count.
                smokeSimulationResetPending = true;
            }
            temporalFrameState.discardFrame();
            if (generation.sceneSurfacePass() != null) {
                temporalSceneState.discardFrame();
            }
            generation.shadowCache().frameFailed();
            generation.clusteredLightingBinder().frameFailed();
            if (generation.previewRenderer() != null) {
                generation.previewRenderer().frameFailed(failure);
            }
            throw failure;
        } finally {
            generation.surfaceInputs().endFrame();
            executing = false;
            activeFrameContext = null;
        }
    }

    public void resize(int w, int h) {
        requireGpuOwner();
        if (executing) {
            throw new IllegalStateException("pipeline resize is only allowed at frame start");
        }
        collectRetiredGenerations(true);
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
        if (current == null || current.graph().width() == w && current.graph().height() == h) return;
        current.resize(w, h);
        lastVolumeCandidatePeakBytes = current.volumeAllocation().peakBytes();
        currentSceneFrame = null;
        currentShadowFramePlan = null;
        currentShadowCasterPlan = null;
        topologySettingsRevision++;
    }

    public List<Matrix4f> lastDirectionalCascadeMatrices() {
        return lastDirectionalCascadeMatrices.stream().map(Matrix4f::new).toList();
    }

    public void close() {
        if (closed) return;
        requireOwnerContext();
        if (executing) throw new IllegalStateException("cannot close an executing pipeline");
        shuttingDown = true;
        vfxLightBridge.clear();
        pendingVfxLights = List.of();
        if (embedded) {
            try (HostGlState ignored = HostGlState.capture()) {
                closeGraphResources();
            }
        } else {
            closeGraphResources();
        }
    }

    private void closeGraphResources() {
        // Keep owners reachable on a timeout so a later legal close can retry.
        PipelineGeneration pending = activeGeneration;
        if (pending != null) {
            activeGeneration = null;
            retireGeneration(pending);
        }
        collectRetiredGenerations(true);
        closed = true;
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
        activateGeneration(candidate, scene);
    }

    private void activateGeneration(PipelineGeneration candidate, Scene candidateScene) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.isClosed()) {
            throw new IllegalArgumentException("cannot activate a closed PipelineGeneration");
        }
        PipelineGeneration retired = activeGeneration;
        boolean reusesSceneState = candidate.reusesSceneState();
        boolean worldChanged = scene != candidateScene;
        boolean shadowShapeChanged = retired != null && !retired.topology().sameShadowShape(candidate.topology());
        try {
            candidate.adoptBorrowedResources(retired);
        } catch (RuntimeException | Error failure) {
            try { candidate.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        activeGeneration = candidate;
        scene = candidateScene;
        // One contact-shadow consumer: when the production screen-space pass is
        // active it owns the attenuation, so the material-level shadow-map-space
        // term must not darken the same receiver a second time.
        candidate.frameLightBinder().forwardContactShadowsEnabled(
                !candidate.topology().screenSpaceContactShadows());
        boolean gtaoSettingsChanged = candidate.gtao() != null && !candidate.gtao().settings().equals(postProcessSettings.gtao());
        if (gtaoSettingsChanged) candidate.gtao().applySettings(postProcessSettings.gtao());
        if (worldChanged) {
            invalidateSurfaceHistory();
            candidate.postProcess().invalidateTemporalHistory();
            if (candidate.gtao() != null) candidate.gtao().invalidateHistory(GtaoPasses.InvalidationReason.WORLD);
            if (candidate.volumetric() != null) candidate.volumetric().invalidateHistory();
            if (candidate.volumetricClouds() != null) {
                candidate.volumetricClouds().invalidateHistory();
            }
            if (candidate.stochasticTransparency() != null) {
                candidate.stochasticTransparency().invalidate();
            }
        } else if (reusesSceneState && candidate.postProcess() != null) {
            boolean gtaoChanged = retired.gtao() != candidate.gtao() || gtaoSettingsChanged;
            boolean volumeChanged = retired.volumetric() != candidate.volumetric()
                    || !retired.volumeSettings().equals(candidate.volumeSettings());
            if (gtaoChanged) candidate.postProcess().invalidateTemporalHistory(TaaInvalidationReason.GTAO_TOPOLOGY);
            else if (volumeChanged) candidate.postProcess().invalidateTemporalHistory(TaaInvalidationReason.VOLUMETRIC_TOPOLOGY);
            else if (shadowShapeChanged) candidate.postProcess().invalidateTemporalHistory(TaaInvalidationReason.SHADOW_TOPOLOGY);
            if (retired.topology().antiAliasingMode() != candidate.topology().antiAliasingMode()) {
                invalidateSurfaceHistory();
                if (candidate.gtao() != null && retired.gtao() == candidate.gtao()) {
                    candidate.gtao().invalidateHistory(GtaoPasses.InvalidationReason.PROJECTION);
                }
                if (candidate.volumetric() != null && retired.volumetric() == candidate.volumetric()) {
                    candidate.volumetric().invalidateHistory();
                }
            }
        }
        applyIblOverrides(candidate);
        currentSceneFrame = null;
        currentShadowFramePlan = null;
        currentShadowCasterPlan = null;
        lastDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
        pendingDirectionalCascadeCasterCounts = NO_CASCADE_COUNTS;
        pendingDirectionalCascadeCount = -1;
        if (!reusesSceneState || shadowShapeChanged) shadowCasterPlanner.reset();
        frameStateInvalidationByMaterial.clear();
        topologySettingsRevision++;
        topologyRebuiltPending = true;
        if (retired != null) lastRetiredGenerationId = retired.id();
        retireGeneration(retired);
    }

    private void retireGeneration(PipelineGeneration generation) {
        if (generation == null) return;
        generation.retire();
        RuntimeException failure = null;
        if (generation.submitted()) {
            // Keep the owner reachable even if creating its completion fence fails.
            // A later fence covers these submissions and any intervening work.
            var pending = new RetiredGeneration(generation);
            retiredGenerations.addLast(pending);
            try { fenceRetirement(pending); }
            catch (RuntimeException fenceFailure) { failure = fenceFailure; }
        } else failure = closeCollecting(generation, failure);
        for (RenderDevice device : List.copyOf(usedDevices)) {
            try {
                device.invalidateState();
            } catch (RuntimeException invalidateFailure) {
                if (failure == null) failure = invalidateFailure;
                else failure.addSuppressed(invalidateFailure);
            }
        }
        if (Boolean.getBoolean("haikalat.test.failVolumeRetirement")) {
            System.clearProperty("haikalat.test.failVolumeRetirement");
            var injected = new IllegalStateException("injected volume retirement failure after publication");
            if (failure == null) failure = injected; else failure.addSuppressed(injected);
        }
        if (Boolean.getBoolean("haikalat.test.failGtaoRetirement")) {
            System.clearProperty("haikalat.test.failGtaoRetirement");
            var injected = new IllegalStateException("injected GTAO retirement failure after publication");
            if (failure == null) failure = injected; else failure.addSuppressed(injected);
        }
        if (failure != null) { lastFailureStage = "retirement-after-publication"; throw failure; }
    }

    private RenderFeatureSettings featureSettingsSnapshot() {
        return new RenderFeatureSettings(featureMasterEnabled, directLightingEnabled,
                imageBasedLightingEnabled, directionalShadowsEnabled, pointShadowsEnabled,
                spotShadowsEnabled, hdrVfxEnabled, previewEnabled, settings, postProcessSettings,
                volumetricFogSettings, outdoorEnvironment, directionalShadowMap,
                directionalCascadeSettings, localShadowSettings, clusteredLightingSettings,
                waterSurfaceSettings, volumetricParticleSettings, stochasticTransparencySettings,
                volumetricCloudSettings, smokeSimulationSettings, smokeRenderSettings,
                weightedOitSettings, planarReflectionSettings, causticsSettings,
                ddgiSettings, vctSettings, hairStrandSettings);
    }

    RenderFeaturePolicy.Facts featureFacts() {
        boolean pbr = false;
        for (int index = 0; index < scene.rendererCount(); index++) {
            if (scene.rendererAt(index).material().material().model()
                    == MaterialModel.METALLIC_ROUGHNESS) {
                pbr = true;
                break;
            }
        }
        boolean directional = false;
        boolean point = false;
        boolean spot = false;
        boolean downwardDirectional = false;
        boolean directionalLight = false;
        for (SceneLight light : scene.lights()) {
            if (light.type() == LightType.DIRECTIONAL && !directionalLight) {
                directionalLight = true;
                downwardDirectional =
                        light.direction().y < -CausticsSettings.MINIMUM_LIGHT_DOWNWARD_Y;
            }
            if (!light.castShadows()) continue;
            switch (light.type()) {
                case DIRECTIONAL -> directional = true;
                case POINT -> point = true;
                case SPOT -> spot = true;
            }
        }
        return new RenderFeaturePolicy.Facts(pbr, directional, point, spot,
                pbrEnvironment != null, hdrVfxRecorder != null, fogAwareHdrVfxRecorder != null,
                hdrVfxFogOptOut, previewController.selected().isPresent(),
                downwardDirectional, weightedOitSetDeclared, weightedOitStochasticOverlap,
                directionalLight,
                variableRateShadingCapabilitySupplier.get().nvImagePathAvailable());
    }

    private void publishCompatibilityFeatureState() {
        RenderFeatureSettings snapshot = featureSettingsSnapshot();
        try {
            var resolved = RenderFeaturePolicy.resolve(snapshot, featureFacts());
            features.refresh(snapshot, resolved.activities());
        } catch (RuntimeException unresolved) {
            features.refresh(snapshot, java.util.List.of());
        }
    }

    /**
     * The applied snapshot for a submitted request.  A post-process feature
     * that is requested but unavailable in this scene is disabled only in this
     * effective copy, so the candidate generation can fall back to the safe
     * baseline while {@code requested()} still records the caller intent.
     */
    private static RenderFeatureSettings effectiveFeatureSettings(
            RenderFeatureSettings requested, RenderFeaturePolicy.Resolved resolved) {
        if (resolved.effectivePostProcess().equals(requested.postProcess())) {
            return requested;
        }
        return requested.toBuilder().postProcess(resolved.effectivePostProcess()).build();
    }

    void applyFeatureFields(RenderFeatureSettings requested) {
        featureMasterEnabled = requested.enabled();
        directLightingEnabled = requested.directLighting();
        imageBasedLightingEnabled = requested.imageBasedLighting();
        directionalShadowsEnabled = requested.directionalShadows();
        pointShadowsEnabled = requested.pointShadows();
        spotShadowsEnabled = requested.spotShadows();
        hdrVfxEnabled = requested.hdrVfx();
        previewEnabled = requested.preview();
        settings = requested.render();
        postProcessSettings = requested.postProcess();
        volumetricFogSettings = requested.volumetricFog();
        outdoorEnvironment = requested.outdoorEnvironment();
        directionalShadowMap = requested.directionalShadowMap();
        directionalCascadeSettings = requested.directionalCascades();
        localShadowSettings = requested.localShadows();
        clusteredLightingSettings = requested.clusteredLighting();
        waterSurfaceSettings = requested.waterSurface();
        volumetricParticleSettings = requested.volumetricParticles();
        volumetricCloudSettings = requested.volumetricClouds();
        smokeSimulationSettings = requested.smokeSimulation();
        smokeRenderSettings = requested.smokeRender();
        StochasticTransparencySettings requestedStochastic = requested.stochasticTransparency();
        if (requestedStochastic.active()
                && stochasticTransparencySurfaces.size() > requestedStochastic.maximumSurfaces()) {
            throw new IllegalArgumentException("the staged stochastic surface set ("
                    + stochasticTransparencySurfaces.size()
                    + ") exceeds the requested maximumSurfaces "
                    + requestedStochastic.maximumSurfaces());
        }
        stochasticTransparencySettings = requestedStochastic;
        WeightedOitSettings requestedOit = requested.weightedOit();
        if (requestedOit.active()
                && weightedOitSurfaces.size() > requestedOit.maximumSurfaces()) {
            throw new IllegalArgumentException("the staged OIT surface set ("
                    + weightedOitSurfaces.size() + ") exceeds the requested maximumSurfaces "
                    + requestedOit.maximumSurfaces());
        }
        weightedOitSettings = requestedOit;
        planarReflectionSettings = requested.planarReflections();
        causticsSettings = requested.caustics();
        recomputeWeightedOitOverlap();
        ddgiSettings = requested.ddgi();
        vctSettings = requested.vct();
        hairStrandSettings = requested.hairStrands();
        pointShadowAtlas = localShadowSettings.maxPointLights() == 0 ? null
                : new PointShadowAtlas(localShadowSettings.point(),
                localShadowSettings.maxPointLights());
        spotShadowAtlas = localShadowSettings.maxSpotLights() == 0 ? null
                : new SpotShadowAtlas(localShadowSettings.spot(),
                localShadowSettings.maxSpotLights());
    }

    private static boolean sameFeatureIntentExceptMaster(RenderFeatureSettings previous,
                                                         RenderFeatureSettings next) {
        return previous.directLighting() == next.directLighting()
                && previous.imageBasedLighting() == next.imageBasedLighting()
                && previous.directionalShadows() == next.directionalShadows()
                && previous.pointShadows() == next.pointShadows()
                && previous.spotShadows() == next.spotShadows()
                && previous.hdrVfx() == next.hdrVfx()
                && previous.preview() == next.preview()
                && java.util.Objects.equals(previous.render(), next.render())
                && java.util.Objects.equals(previous.postProcess(), next.postProcess())
                && java.util.Objects.equals(previous.volumetricFog(), next.volumetricFog())
                && java.util.Objects.equals(previous.outdoorEnvironment(),
                next.outdoorEnvironment())
                && java.util.Objects.equals(previous.directionalShadowMap(),
                next.directionalShadowMap())
                && java.util.Objects.equals(previous.directionalCascades(),
                next.directionalCascades())
                && java.util.Objects.equals(previous.localShadows(), next.localShadows())
                && java.util.Objects.equals(previous.clusteredLighting(),
                next.clusteredLighting())
                && java.util.Objects.equals(previous.waterSurface(), next.waterSurface())
                && java.util.Objects.equals(previous.volumetricParticles(),
                next.volumetricParticles())
                && java.util.Objects.equals(previous.stochasticTransparency(),
                next.stochasticTransparency())
                && java.util.Objects.equals(previous.volumetricClouds(),
                next.volumetricClouds())
                && java.util.Objects.equals(previous.smokeSimulation(),
                next.smokeSimulation())
                && java.util.Objects.equals(previous.smokeRender(), next.smokeRender())
                && java.util.Objects.equals(previous.weightedOit(), next.weightedOit())
                && java.util.Objects.equals(previous.planarReflections(),
                next.planarReflections())
                && java.util.Objects.equals(previous.caustics(), next.caustics())
                && java.util.Objects.equals(previous.ddgi(), next.ddgi())
                && java.util.Objects.equals(previous.vct(), next.vct())
                && java.util.Objects.equals(previous.hairStrands(), next.hairStrands());
    }

    private void applyPendingFeatureRequest() {
        RenderFeatureSettings requested = features.requested();
        long revision = features.requestedRevision();
        features.markPreparing();
        RenderFeatureSettings previous = featureSettingsSnapshot();
        boolean wasPaused = !features.effective().enabled();
        try {
            RenderFeaturePolicy.Resolved resolved = RenderFeaturePolicy.resolve(requested,
                    featureFacts());
            RenderFeatureSettings effective = effectiveFeatureSettings(requested, resolved);
            if (sameFeatureIntentExceptMaster(previous, effective)) {
                applyFeatureFields(effective);
                features.publish(requested, effective, revision, resolved.activities());
                if (wasPaused && effective.enabled()) resetTemporalHistory();
                return;
            }
            applyFeatureFields(effective);
            PipelineGeneration current = activeGeneration;
            try {
                PipelineTopology topology = captureTopology(scene,
                        current.graph().width(), current.graph().height(),
                        volumetricParticleSettings, stochasticTransparencySettings);
                PipelineGeneration candidate = createGeneration(scene, topology, current);
                synchronizeOutdoorSun(scene);
                activateGeneration(candidate);
            } catch (RuntimeException failure) {
                if (activeGeneration != current) {
                    // The candidate became active before retirement failed. The
                    // published generation and the effective configuration must
                    // not diverge: keep the resolved fields and publish them.
                    features.publish(requested, effective, revision, resolved.activities());
                    if (wasPaused && effective.enabled()) resetTemporalHistory();
                    return;
                }
                applyFeatureFields(previous);
                features.failed(failure.getMessage());
                lastFailureStage = "feature-request";
                return;
            }
            features.publish(requested, effective, revision, resolved.activities());
            if (wasPaused && effective.enabled()) resetTemporalHistory();
        } catch (RuntimeException failure) {
            applyFeatureFields(previous);
            features.failed(failure.getMessage());
            lastFailureStage = "feature-request";
        }
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
        if (generation.pbrMaterialBinder() != null) {
            generation.pbrMaterialBinder().visualOverride(iblIntensity, iblRotationRadians);
        }
        if (generation.environmentBackground() != null) {
            generation.environmentBackground().visualOverride(iblIntensity, iblRotationRadians);
        }
    }

    private static boolean outdoorPassShapeChanged(PipelineGeneration generation,
                                                   OutdoorEnvironmentSettings environment) {
        return (generation.stylizedSky() != null) != environment.enabled();
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

    private PassExecutor surfaceExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            var inputs = generation.surfaceInputs().frame();
            SceneFrame frame = sceneFrame();
            TemporalFrameState.FrameParameters current = temporalFrameState.current();
            if (current == null) {
                throw new IllegalStateException(
                        "scene surface pass requires a prepared temporal frame state");
            }
            // Upload the single shared camera block before recording the
            // surface draws; the forward geometry pass reuses it.
            generation.cameraUniforms().update(cmd, frameCamera(), frameWidth(), frameHeight(),
                    settings.antiAliasingMode(), activeFrameIndex);
            generation.deformationDefaults().record(cmd);
            generation.sceneSurfacePass().record(cmd, frame, temporalSceneState, current,
                    temporalFrameState.previous(), instanced, activeFrameIndex,
                    generation.cameraUniforms());
            inputs.rasterProduced(res);
        };
    }

    private PassExecutor reactiveExecutor() {
        return (res, cmd) -> {
            PipelineGeneration generation = requireGeneration();
            if (generation.volumetric()!=null) volumetricReactiveExecutor().execute(res,cmd);
            if (generation.sceneReactivePass() == null) return;
            generation.deformationDefaults().record(cmd);
            generation.sceneReactivePass().record(cmd, sceneFrame(), generation.cameraUniforms());
        };
    }

    private PassExecutor surfaceResolveExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            var inputs = generation.surfaceInputs().frame();
            generation.sceneSurfaceResolvePass().record(cmd, res, generation.surfaceInputs());
            inputs.resolveProduced(res);
        };
    }

    private PassExecutor waterExecutor(PipelineGeneration generation, String sceneColorSource) {
        return (res, cmd) -> {
            var layout = generation.topology().sceneSurface();
            var depth = layout.raw(SceneBufferChannel.DEPTH);
            if (depth == null) {
                throw new IllegalStateException("water surface requires the shared raster depth");
            }
            TemporalFrameState.FrameParameters frame = temporalFrameState.current();
            if (frame == null) {
                throw new IllegalStateException(
                        "water surface requires a prepared temporal frame state");
            }
            Framebuffer sceneColor = res.framebufferOfPass(sceneColorSource);
            Framebuffer target = res.currentTarget();
            if (sceneColor == null || target == null) {
                throw new IllegalStateException("water surface requires the geometry colour target");
            }
            generation.waterSurfacePass().record(cmd, sceneColor, target,
                    res.depthAttachment(depth.name()), planarReflectionTexture(generation), frame,
                    waterSurfaceSettings, pbrEnvironment, iblIntensity, iblRotationRadians,
                    waterTimeSeconds);
        };
    }

    /**
     * Colour texture of the planar pass when it has valid content for this
     * frame; {@code 0} keeps the water shader on its environment/fallback path.
     */
    private static int planarReflectionTexture(PipelineGeneration generation) {
        var planar = generation.planarReflection();
        return planar != null && planar.hasValidContent() ? planar.colorTexture() : 0;
    }

    private PassExecutor hairStrandExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            HairStrandPass hair = generation.hairStrands();
            if (hair == null) {
                throw new IllegalStateException(
                        "hair strand pass is absent from the active topology");
            }
            if (!hairStrandSettings.active()) {
                return;
            }
            SceneLight light = firstDirectionalLight();
            if (light == null) {
                throw new IllegalStateException("hair strands require a directional light");
            }
            Vector3f ambient = new Vector3f(light.color()).mul(0.15f);
            ambient.x = Math.min(ambient.x, HairStrandPass.HairStrandLight.MAXIMUM_COMPONENT);
            ambient.y = Math.min(ambient.y, HairStrandPass.HairStrandLight.MAXIMUM_COMPONENT);
            ambient.z = Math.min(ambient.z, HairStrandPass.HairStrandLight.MAXIMUM_COMPONENT);
            HairStrandPass.HairStrandLight hairLight = new HairStrandPass.HairStrandLight(
                    light.direction(), light.color(), light.intensity(), ambient);
            ShadowFramePlan plan = shadowFramePlan();
            boolean legacyDirectionalShadow = plan.directional().isPresent()
                    && !directionalCascadeSettings.enabled();
            int shadowTexture = legacyDirectionalShadow
                    ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0;
            float texelSize = 1.0f / Math.max(1, directionalShadowMap.settings().resolution());
            hair.record(cmd, externalFrameCamera(), hairStrandModel, hairStrandSettings,
                    hairLight, shadowTexture, lastDirectionalLightSpaceMatrix,
                    directionalShadowMap.settings().bias(), texelSize);
        };
    }

    /** Late directional caster hook: records the hair lock into the current shadow tile. */
    private void recordHairShadowCaster(CommandBuffer cmd, Matrix4fc lightSpace, int cascade) {
        PipelineGeneration generation = requireGeneration();
        HairStrandPass hair = generation.hairStrands();
        if (hair == null || !hairStrandSettings.active() || !hairStrandSettings.castsShadow()) {
            return;
        }
        SceneLight light = firstDirectionalLight();
        if (light == null) {
            return;
        }
        hair.recordShadow(cmd, hairStrandModel, lightSpace, hairStrandSettings,
                light.direction());
    }

    private PassExecutor ddgiProbeExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            SceneLight light = firstDirectionalLight();
            if (light == null) {
                throw new IllegalStateException("DDGI requires a directional light");
            }
            Vector3f radiance = new Vector3f(light.color()).mul(light.intensity());
            if (radiance.x > DdgiSettings.MAXIMUM_LIGHT_RADIANCE_COMPONENT
                    || radiance.y > DdgiSettings.MAXIMUM_LIGHT_RADIANCE_COMPONENT
                    || radiance.z > DdgiSettings.MAXIMUM_LIGHT_RADIANCE_COMPONENT) {
                throw new IllegalStateException(
                        "DDGI main-light radiance exceeds the bounded probe input");
            }
            generation.ddgiVolume().recordUpdate(cmd, sceneFrame(), light.direction(), radiance);
        };
    }

    private PassExecutor ddgiIndirectExecutor(PipelineGeneration generation,
                                              String colorProducer) {
        return (res, cmd) -> {
            Framebuffer source = res.framebufferOfPass(colorProducer);
            if (source == null) {
                throw new IllegalStateException(
                        "DDGI indirect pass requires the current scene colour target");
            }
            var depth = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.DEPTH);
            var normal = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.NORMAL);
            if (depth == null || normal == null) {
                throw new IllegalStateException(
                        "DDGI indirect pass requires the shared single-sample depth and normal");
            }
            int depthTexture = generation.surfaceInputs().frame().require(res, depth);
            int normalTexture = generation.surfaceInputs().frame().require(res, normal);
            TemporalFrameState.FrameParameters frame = temporalFrameState.current();
            if (frame == null) {
                throw new IllegalStateException(
                        "DDGI indirect pass requires a prepared temporal frame state");
            }
            DdgiIndirectPass indirect = generation.ddgiIndirect();
            indirect.record(cmd, source, depthTexture, normalTexture, frame);
            Framebuffer target = res.currentTarget();
            if (target == null) {
                throw new IllegalStateException(
                        "DDGI indirect pass requires a managed colour target");
            }
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height())
                    .blitColor(indirect.colorTarget(), target);
        };
    }

    private PassExecutor vctVoxelizeExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            VctSceneInput input = vctSceneInput;
            if (input == null || generation.vct() == null) {
                return;
            }
            VctPass.UpdateStats stats = generation.vct().recordVoxelize(cmd, input,
                    vctDirtyBoxes);
            if (!VctPass.UpdateStats.NONE.equals(stats)) {
                // Dirty boxes describe work since the last successful update.
                vctDirtyBoxes = List.of();
            }
        };
    }

    private PassExecutor vctTraceExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            VctProbeBatch batch = vctProbeBatch;
            VctPass pass = generation.vct();
            if (batch == null || pass == null || !pass.volumeValid()) {
                return;
            }
            pass.recordProbeTrace(cmd, batch);
        };
    }

    private PassExecutor vctCompositeExecutor(PipelineGeneration generation,
                                              String colorProducer) {
        return (res, cmd) -> {
            Framebuffer source = res.framebufferOfPass(colorProducer);
            Framebuffer target = res.currentTarget();
            if (source == null || target == null) {
                throw new IllegalStateException(
                        "voxel cone tracing requires the current scene colour target");
            }
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height())
                    .blitColor(source, target);
            VctPass pass = generation.vct();
            if (pass == null || !pass.volumeValid()) {
                return;
            }
            var depth = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.DEPTH);
            if (depth == null) {
                throw new IllegalStateException(
                        "voxel cone tracing requires the shared single-sample scene depth");
            }
            int depthTexture = generation.surfaceInputs().frame().require(res, depth);
            pass.recordComposite(cmd, target, depthTexture, externalFrameCamera(),
                    vctSurfaceRoughness, vctSurfaceAlbedo);
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

    /**
     * Adapts the frame's camera facts to the post-process colour chain.
     *
     * <p>The chain never reads the mutable {@link Camera} or the Render3D frame
     * context; it receives an immutable pose with the jittered inverse
     * projection used by the temporal resolve and the fog matrices.</p>
     */
    private TemporalFramePose postProcessPose(RenderFrameContext context,
                                              TemporalFrameState.FrameParameters frame) {
        int width = Math.max(1, context.width());
        int height = Math.max(1, context.height());
        if (frame != null) {
            Matrix4f fogInverseViewProjection = null;
            Vector3f fogCameraPosition = null;
            if (postProcessSettings.fog().enabled()) {
                fogInverseViewProjection = new Matrix4f(frame.jitteredProjection())
                        .mul(frame.view()).invert();
                fogCameraPosition = new Vector3f(frame.cameraPositionX(),
                        frame.cameraPositionY(), frame.cameraPositionZ());
            }
            return new TemporalFramePose(context.deltaSeconds(), width, height,
                    frame.jitterUvX(), frame.jitterUvY(),
                    frame.inverseJitteredProjection(),
                    new Matrix4f(frame.view()).invert(),
                    frame.stableViewProjection(),
                    fogInverseViewProjection, fogCameraPosition);
        }
        // Frames without a temporal surface pass still need stable camera facts
        // when the analytic fog pass is enabled.
        Matrix4f stableProjection = CameraProjection.stable(context.camera(), width, height,
                new Matrix4f());
        Matrix4f jitteredProjection = new Matrix4f(stableProjection);
        CameraUniforms.applyTemporalJitter(jitteredProjection, width, height,
                settings.antiAliasingMode(), context.frameIndex());
        Matrix4f view = context.camera().getViewMatrix(new Matrix4f());
        Matrix4f fogInverseViewProjection = null;
        Vector3f fogCameraPosition = null;
        if (postProcessSettings.fog().enabled()) {
            fogInverseViewProjection = new Matrix4f(jitteredProjection).mul(view).invert();
            fogCameraPosition = context.camera().position();
        }
        return new TemporalFramePose(context.deltaSeconds(), width, height, 0.0f, 0.0f,
                new Matrix4f(jitteredProjection).invert(),
                new Matrix4f(view).invert(),
                new Matrix4f(stableProjection).mul(view),
                fogInverseViewProjection, fogCameraPosition);
    }

    /** Drops all temporal history (camera cut, resize, scene replacement, explicit reset). */
    public void resetTemporalHistory() {
        requireGpuOwner();
        if (executing) throw new IllegalStateException("history reset is only allowed at frame boundaries");
        invalidateSurfaceHistory();
        if (activeGeneration != null) {
            activeGeneration.postProcess().invalidateTemporalHistory();
            if (activeGeneration.gtao() != null) activeGeneration.gtao().invalidateHistory();
            if (activeGeneration.volumetric() != null) activeGeneration.volumetric().invalidateHistory();
            if (activeGeneration.volumetricClouds() != null) {
                activeGeneration.volumetricClouds().invalidateHistory();
            }
            if (activeGeneration.stochasticTransparency() != null) {
                activeGeneration.stochasticTransparency().invalidate();
            }
        }
    }

    private void invalidateSurfaceHistory() {
        temporalFrameState.invalidate();
        temporalSceneState.invalidate();
        if (instanced != null) instanced.invalidatePreviousFrame();
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
            int gtaoTexture = requireGeneration().topology().gtaoEnabled()
                    ? res.colorAttachment(PostProcessTargets.GTAO_FINAL) : 0;
            renderScene(cmd,
                plan.directional().isPresent()
                        ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                !plan.points().isEmpty()
                        ? res.depthAttachment(PointShadowAtlas.TEXTURE_NAME) : 0,
                !plan.spots().isEmpty()
                        ? res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME) : 0,
                gtaoTexture,requireGeneration().volumetric() == null ? 0 : 1);
        };
    }

    private PassExecutor refractionSourceExecutor(String sourcePass) {
        return (res, cmd) -> {
            Framebuffer source = res.framebufferOfPass(sourcePass);
            Framebuffer target = res.currentTarget();
            if (source != null && target != null) RefractionPass.recordSourceCopy(cmd, source, target);
        };
    }

    /**
     * Records only refraction materials against the pre-pass snapshot. The
     * pass shares the scene-colour attachment and depth with the producer but
     * samples {@link RefractionPass#SOURCE_TEXTURE}, so it cannot feed back into
     * its own output.
     */
    private PassExecutor refractionExecutor() {
        return (res, cmd) -> RefractionPass.record(cmd, sceneFrame(), frameCamera(),
                res.colorAttachment(RefractionPass.SOURCE_TEXTURE),
                frameWidth(), frameHeight());
    }

    private void recordVolumeShadowInputs(PipelineGeneration generation,
                                          com.kaleblangley.haikalat.core.graph.PassResources res,
                                          CommandBuffer cmd, ShaderProgram shader) {
        generation.shadowCasterRecorder().recordVolumeInputs(res, cmd, shader, shadowFramePlan(),
                activeFrameContext.camera(), generation.shadowSamplingBlock(),
                generation.shadowFrameBinder(), lastDirectionalLightSpaceMatrix,
                lastDirectionalCascadeMatrices, lastDirectionalCascadeSplits);
    }

    private PassExecutor volumetricTransparentExecutor() {
        return (res,cmd)-> {
            requireGeneration().volumetric().fragmentInputs(res);
            var plan=shadowFramePlan();
            cmd.sampleShading(requireGeneration().topology().sampleCount()>1,requireGeneration().topology().sampleCount()>1 ? 1 : 0);
            renderScene(cmd,plan.directional().isPresent() ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                    plan.points().isEmpty() ? 0 : res.depthAttachment(PointShadowAtlas.TEXTURE_NAME),
                    plan.spots().isEmpty() ? 0 : res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME),0,2);
            if (hdrVfxRecorder != null) hdrVfxRecorder.execute(res,cmd);
            cmd.sampleShading(false,0).enableBlend(false).depthMask(true).enableDepthTest(true);
        };
    }

    private PassExecutor volumetricParticleExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            var particles = generation.volumetricParticles();
            if (particles == null || generation.volumetric() == null) {
                throw new IllegalStateException(
                        "volumetric particles require the volumetric fog colour composition");
            }
            Framebuffer source = res.framebufferOfPass(VolumetricPassBuilder.COMPOSITE_PASS);
            if (source == null) {
                throw new IllegalStateException(
                        "volumetric particles require the fog opaque colour composite");
            }
            var singleDepth = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.DEPTH);
            if (singleDepth == null) {
                throw new IllegalStateException(
                        "volumetric particles require the shared single-sample opaque depth");
            }
            int depth = generation.surfaceInputs().frame().require(res, singleDepth);
            // The fog compositor reconstructs against the jittered raster, so the
            // particle rays must use the same camera even when the frame is TAA.
            particles.record(cmd, source, depth, fogRasterCamera());
            Framebuffer target = res.currentTarget();
            if (target == null) {
                throw new IllegalStateException(
                        "volumetric particles require a managed colour target");
            }
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height())
                    .blitColor(particles.colorTarget(), target);
        };
    }

    /**
     * Advances the smoke owner at the deterministic simulation stage. A failed
     * frame may have submitted a partial simulation, so the next frame clears
     * the field before advancing instead of continuing from an unknown state.
     */
    private PassExecutor smokeSimulationExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            var simulation = generation.smokeSimulation();
            if (simulation == null) {
                throw new IllegalStateException(
                        "smoke simulation pass is absent from the active topology");
            }
            if (smokeSimulationResetPending) {
                simulation.reset(cmd);
                smokeSimulationResetPending = false;
            }
            smokeSimulationAdvanced = true;
            simulation.advance(cmd, requireFrameContext().deltaSeconds());
        };
    }

    private PassExecutor volumetricCloudExecutor(PipelineGeneration generation,
                                                 String colorSourcePass) {
        return (res, cmd) -> {
            var clouds = generation.volumetricClouds();
            if (clouds == null) {
                throw new IllegalStateException(
                        "volumetric cloud pass is absent from the active topology");
            }
            Framebuffer source = res.framebufferOfPass(colorSourcePass);
            if (source == null) {
                throw new IllegalStateException(
                        "volumetric clouds require the preceding scene-colour producer "
                                + colorSourcePass);
            }
            var singleDepth = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.DEPTH);
            if (singleDepth == null) {
                throw new IllegalStateException(
                        "volumetric clouds require the shared single-sample opaque depth");
            }
            int depth = generation.surfaceInputs().frame().require(res, singleDepth);
            clouds.record(cmd, source, depth, externalFrameCamera(),
                    generation.cloudTimeSeconds(), settings.exposure());
            Framebuffer target = res.currentTarget();
            if (target == null) {
                throw new IllegalStateException(
                        "volumetric clouds require a managed colour target");
            }
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height())
                    .blitColor(clouds.colorTarget(), target);
        };
    }

    private PassExecutor smokeVolumeExecutor(PipelineGeneration generation,
                                             String colorSourcePass) {
        return (res, cmd) -> {
            var smoke = generation.smokeVolume();
            var simulation = generation.smokeSimulation();
            if (smoke == null || simulation == null) {
                throw new IllegalStateException(
                        "smoke volume rendering requires the simulation owner");
            }
            Framebuffer source = res.framebufferOfPass(colorSourcePass);
            if (source == null) {
                throw new IllegalStateException(
                        "smoke volume rendering requires the preceding scene-colour producer "
                                + colorSourcePass);
            }
            var singleDepth = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.DEPTH);
            if (singleDepth == null) {
                throw new IllegalStateException(
                        "smoke volume rendering requires the shared single-sample opaque depth");
            }
            int depth = generation.surfaceInputs().frame().require(res, singleDepth);
            smoke.record(cmd, source, depth, externalFrameCamera(), simulation);
            Framebuffer target = res.currentTarget();
            if (target == null) {
                throw new IllegalStateException(
                        "smoke volume rendering requires a managed colour target");
            }
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height())
                    .blitColor(smoke.colorTarget(), target);
        };
    }

    private PassExecutor stochasticTransparencyExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            var stochastic = generation.stochasticTransparency();
            if (stochastic == null) {
                throw new IllegalStateException(
                        "stochastic transparency pass is absent from the active topology");
            }
            List<OitSurface> surfaces = stochasticTransparencySurfaces;
            if (surfaces.isEmpty()) {
                // No selected set means no draw and no history advance.
                return;
            }
            var singleDepth = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.DEPTH);
            if (singleDepth == null) {
                throw new IllegalStateException(
                        "stochastic transparency requires the shared single-sample scene depth");
            }
            int depth = generation.surfaceInputs().frame().require(res, singleDepth);
            ExternalCamera camera = externalFrameCamera();
            StochasticTransparencyInputs inputs = new StochasticTransparencyInputs(
                    camera.view(), camera.projection(), camera.inverseProjection(),
                    camera.inverseView(), depth);
            stochastic.record(cmd, surfaces, inputs, stochasticTransparencySettings);
            Framebuffer target = res.currentTarget();
            if (target == null) {
                throw new IllegalStateException(
                        "stochastic transparency requires the shared scene colour target");
            }
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height());
            stochastic.resolve(cmd, stochasticTransparencySettings);
        };
    }

    private PassExecutor weightedOitExecutor(PipelineGeneration generation) {
        return (res, cmd) -> {
            var oit = generation.weightedOit();
            if (oit == null) {
                throw new IllegalStateException(
                        "weighted OIT pass is absent from the active topology");
            }
            List<OitSurface> surfaces = weightedOitSurfaces;
            if (surfaces.isEmpty()) {
                // A declared empty collection records no draw and leaves the
                // caller's HDR target untouched.
                return;
            }
            ExternalCamera camera = externalFrameCamera();
            oit.record(cmd, surfaces, camera.view(), camera.projection(), weightedOitSettings);
            Framebuffer target = res.currentTarget();
            if (target == null) {
                throw new IllegalStateException(
                        "weighted OIT requires the shared scene colour target");
            }
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height());
            oit.resolve(cmd, weightedOitSettings);
        };
    }

    private PassExecutor planarReflectionExecutor(PipelineGeneration generation,
                                                  String restoreTargetPass) {
        return (res, cmd) -> {
            var planar = generation.planarReflection();
            if (planar == null) {
                throw new IllegalStateException(
                        "planar reflection pass is absent from the active topology");
            }
            Framebuffer restore = res.framebufferOfPass(restoreTargetPass);
            if (restore == null) {
                throw new IllegalStateException(
                        "planar reflection requires the " + restoreTargetPass
                                + " colour target to restore");
            }
            ShadowFramePlan plan = shadowFramePlan();
            int shadowTexture = plan.directional().isPresent()
                    ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0;
            int pointShadowTexture = plan.points().isEmpty()
                    ? 0 : res.depthAttachment(PointShadowAtlas.TEXTURE_NAME);
            int spotShadowTexture = plan.spots().isEmpty()
                    ? 0 : res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME);
            planar.record(cmd, frameCamera(), frameWidth(), frameHeight(), restore,
                    (recordingCmd, frame) -> recordMirrorScene(recordingCmd, generation,
                            frame, shadowTexture, pointShadowTexture, spotShadowTexture));
            // The reflection recorder uploaded its own camera block. Restore the
            // main scene block so later passes that bind CameraUniforms still see
            // the primary camera, not the mirrored one.
            generation.cameraUniforms().update(cmd, frameCamera(), frameWidth(), frameHeight(),
                    settings.antiAliasingMode(), activeFrameIndex);
        };
    }

    /**
     * Draws the opaque/forward scene into the reflection target with the
     * mirrored camera and the pass's oblique projection.  The reflection view is
     * deliberately independent: it uploads its own camera block (no TAA jitter),
     * never samples the reflection target while writing it, and does not rerun
     * shadow/GTAO/IBL production.
     */
    private void recordMirrorScene(CommandBuffer cmd, PipelineGeneration generation,
                                   PlanarReflectionPass.Frame frame, int shadowTexture,
                                   int pointShadowTexture, int spotShadowTexture) {
        ExternalCamera camera = new ExternalCamera(frame.view(), frame.projection(),
                frame.viewProjection(), frame.position(), 0.0f,
                CameraProjection.NEAR_PLANE, CameraProjection.FAR_PLANE, 0L);
        SceneRenderer renderer = new SceneRenderer(new ReflectionDrawHost(generation, camera,
                frame.width(), frame.height()));
        renderer.render(cmd, shadowTexture, pointShadowTexture, spotShadowTexture, 0, 0);
    }

    /** SceneRenderer host that swaps only the camera-facing facts of a generation. */
    private final class ReflectionDrawHost implements SceneRenderer.Host {
        private final PipelineGeneration generation;
        private final ExternalCamera camera;
        private final int width;
        private final int height;

        private ReflectionDrawHost(PipelineGeneration generation, ExternalCamera camera,
                                   int width, int height) {
            this.generation = generation;
            this.camera = camera;
            this.width = width;
            this.height = height;
        }

        @Override
        public SceneFrame frame() {
            return sceneFrame();
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int frameIndex() {
            return activeFrameIndex;
        }

        @Override
        public boolean gtaoEnabled() {
            return false;
        }

        @Override
        public boolean sharedDepth() {
            return false;
        }

        @Override
        public InstancedRenderer instanced() {
            return instanced;
        }

        @Override
        public SceneDeformationDefaults deformationDefaults() {
            return generation.deformationDefaults();
        }

        @Override
        public void updateCameraUniforms(CommandBuffer cmd) {
            // The oblique reflection projection cannot accept a second TAA jitter.
            generation.cameraUniforms().update(cmd, camera, width, height,
                    AntiAliasingMode.NONE, activeFrameIndex);
        }

        @Override
        public void renderBackground(CommandBuffer cmd, Camera camera, int width, int height,
                                     int segment) {
            if (generation.atmosphereSky() != null) {
                generation.atmosphereSky().record(cmd, camera, width, height,
                        Math.max(0.0f, camera.position().y), atmosphereSettings);
            } else if (generation.stylizedSky() != null) {
                generation.stylizedSky().render(cmd, camera, width, height,
                        outdoorEnvironment.sky());
            } else if (generation.environmentBackground() != null) {
                generation.environmentBackground().render(cmd, camera, width, height);
            }
        }

        @Override
        public ShaderProgram transparentShader(Material template, ShaderProgram fallback) {
            // The reflection records the forward segment only.
            return fallback;
        }

        @Override
        public void bindVolumeQuery(CommandBuffer cmd, ShaderProgram shader) {
        }

        @Override
        public void bindFrameState(ShaderProgram shader, CommandBuffer cmd, int shadowTexture,
                                   int pointShadowTexture, int spotShadowTexture,
                                   int gtaoTexture) {
            RenderPipeline.this.bindFrameState(shader, cmd, camera, shadowTexture,
                    pointShadowTexture, spotShadowTexture, gtaoTexture);
        }

        @Override
        public boolean invalidatesFrameState(MaterialInstance material) {
            return RenderPipeline.this.invalidatesFrameState(material);
        }

        @Override
        public void bindPbrMaterial(ShaderProgram shader, CommandBuffer cmd) {
            if (generation.pbrMaterialBinder() != null) {
                generation.pbrMaterialBinder().bind(shader, cmd);
            }
        }
    }

    private PassExecutor causticsExecutor(PipelineGeneration generation,
                                          String colorSourcePass) {
        return (res, cmd) -> {
            var caustics = generation.caustics();
            if (caustics == null) {
                throw new IllegalStateException(
                        "caustics pass is absent from the active topology");
            }
            var rasterDepth = generation.topology().sceneSurface()
                    .raw(SceneBufferChannel.DEPTH);
            if (rasterDepth == null) {
                throw new IllegalStateException("caustics require the shared raster scene depth");
            }
            TemporalFrameState.FrameParameters frame = temporalFrameState.current();
            if (frame == null) {
                throw new IllegalStateException(
                        "caustics require a prepared temporal frame state");
            }
            SceneLight light = firstDirectionalLight();
            if (light == null) {
                throw new IllegalStateException("caustics require a directional light");
            }
            Framebuffer source = res.framebufferOfPass(colorSourcePass);
            Framebuffer target = res.currentTarget();
            if (source == null || target == null) {
                throw new IllegalStateException(
                        "caustics require the scene colour target to copy");
            }
            caustics.record(cmd, source, res.depthAttachment(rasterDepth.name()), frame,
                    light.direction(), waterTimeSeconds);
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height())
                    .blitColor(caustics.colorTarget(), target);
        };
    }

    private PassExecutor screenSpaceContactShadowExecutor(PipelineGeneration generation,
                                                          String colorSourcePass) {
        return (res, cmd) -> {
            var contactShadow = generation.screenSpaceContactShadows();
            if (contactShadow == null) {
                throw new IllegalStateException(
                        "screen-space contact-shadow pass is absent from the active topology");
            }
            var singleDepth = generation.topology().sceneSurface()
                    .single(SceneBufferChannel.DEPTH);
            if (singleDepth == null) {
                throw new IllegalStateException(
                        "screen-space contact shadows require the shared single-sample scene depth");
            }
            TemporalFrameState.FrameParameters frame = temporalFrameState.current();
            if (frame == null) {
                throw new IllegalStateException(
                        "screen-space contact shadows require a prepared temporal frame state");
            }
            SceneLight light = firstDirectionalLight();
            if (light == null) {
                throw new IllegalStateException(
                        "screen-space contact shadows require a directional light");
            }
            Framebuffer source = res.framebufferOfPass(colorSourcePass);
            Framebuffer target = res.currentTarget();
            if (source == null || target == null) {
                throw new IllegalStateException(
                        "screen-space contact shadows require the scene colour target to copy");
            }
            contactShadow.record(cmd, source, contactShadow.colorTarget(),
                    res.depthAttachment(singleDepth.name()), frame,
                    directionalShadowMap.settings().contactShadows(), light.direction());
            cmd.bindFramebuffer(target)
                    .viewport(0, 0, target.width(), target.height())
                    .blitColor(contactShadow.colorTarget(), target);
        };
    }

    private PassExecutor volumetricReactiveExecutor() {
        return (res,cmd)-> {
            requireGeneration().volumetric().fragmentInputs(res);
            var generation=requireGeneration(); var plan=shadowFramePlan();
            generation.volumetric().recordReactive(res,cmd);
            renderScene(cmd,plan.directional().isPresent() ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                    plan.points().isEmpty() ? 0 : res.depthAttachment(PointShadowAtlas.TEXTURE_NAME),
                    plan.spots().isEmpty() ? 0 : res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME),0,3);
            generation.volumetric().recordReactiveVfx(cmd);
        };
    }

    private void synchronizeOutdoorSun(Scene targetScene) {
        if (atmosphereSettings != null) {
            // The atmosphere owns the sun direction, but the scene's light
            // colour/intensity stay authored; the vector convention is the
            // same light-travel direction used by SceneLight and the shader.
            List<SceneLight> lights = targetScene.lights();
            for (int i = 0; i < lights.size(); i++) {
                SceneLight light = lights.get(i);
                if (light.type() == LightType.DIRECTIONAL && light.castShadows()) {
                    SceneLight replacement = SceneLight.shadowedDirectional(
                            new Vector3f(atmosphereSettings.sunDirection()),
                            light.color(), light.intensity());
                    if (!replacement.equals(light)) targetScene.setLight(i, replacement);
                    return;
                }
            }
            return;
        }
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
        return (res, cmd) -> {
            ShadowFramePlan plan = shadowFramePlan();
            if (plan.directional().isEmpty()) return;
            lastShadowCasterDrawCount = requireGeneration().shadowCasterRecorder()
                    .recordDirectional(cmd, sceneFrame(), plan.directional().orElseThrow(),
                            currentShadowCasterPlan, this::recordHairShadowCaster);
        };
    }

    private PassExecutor pointShadowExecutor() {
        return (res, cmd) -> {
            ShadowFramePlan plan = shadowFramePlan();
            if (plan.points().isEmpty()) return;
            lastPointShadowCasterDrawCount = requireGeneration().shadowCasterRecorder()
                    .recordPoints(cmd, sceneFrame(), plan.points(), currentShadowCasterPlan);
        };
    }

    private PassExecutor spotShadowExecutor() {
        return (res, cmd) -> {
            ShadowFramePlan plan = shadowFramePlan();
            if (plan.spots().isEmpty()) return;
            lastSpotShadowCasterDrawCount = requireGeneration().shadowCasterRecorder()
                    .recordSpots(cmd, sceneFrame(), plan.spots(), currentShadowCasterPlan);
        };
    }

    private void renderScene(CommandBuffer cmd, int shadowTexture,
                             int pointShadowTexture, int spotShadowTexture,
                             int gtaoTexture, int segment) {
        requireGeneration().sceneRenderer().render(cmd, shadowTexture, pointShadowTexture,
                spotShadowTexture, gtaoTexture, segment);
    }

    /** Adapts the active generation to the geometry-owned draw recorder. */
    private final class GenerationDrawHost implements com.kaleblangley.haikalat
            .subsystems.render3d.geometry.SceneRenderer.Host {
        private final PipelineGeneration generation;

        private GenerationDrawHost(PipelineGeneration generation) {
            this.generation = generation;
        }

        @Override
        public SceneFrame frame() {
            return sceneFrame();
        }

        @Override
        public Camera camera() {
            return frameCamera();
        }

        @Override
        public int width() {
            return frameWidth();
        }

        @Override
        public int height() {
            return frameHeight();
        }

        @Override
        public int frameIndex() {
            return activeFrameIndex;
        }

        @Override
        public boolean gtaoEnabled() {
            return generation.topology().gtaoEnabled();
        }

        @Override
        public boolean sharedDepth() {
            return generation.topology().sceneSurface().hasSurface();
        }

        @Override
        public com.kaleblangley.haikalat.subsystems.render3d.geometry.InstancedRenderer instanced() {
            return instanced;
        }

        @Override
        public SceneDeformationDefaults deformationDefaults() {
            return generation.deformationDefaults();
        }

        @Override
        public void updateCameraUniforms(CommandBuffer cmd) {
            generation.cameraUniforms().update(cmd, frameCamera(), frameWidth(), frameHeight(),
                    settings.antiAliasingMode(), activeFrameIndex);
        }

        @Override
        public void renderBackground(CommandBuffer cmd, Camera camera, int width, int height,
                                     int segment) {
            if (generation.atmosphereSky() != null) {
                generation.atmosphereSky().record(cmd, camera, width, height,
                        Math.max(0.0f, camera.position().y), atmosphereSettings);
            } else if (generation.stylizedSky() != null) {
                generation.stylizedSky().render(cmd, camera, width, height, outdoorEnvironment.sky());
            } else if (generation.environmentBackground() != null) {
                generation.environmentBackground().render(cmd, camera, width, height);
            }
        }

        @Override
        public ShaderProgram transparentShader(Material template, ShaderProgram fallback) {
            return generation.volumetric() == null
                    ? fallback : generation.volumetric().transparentShader(template);
        }

        @Override
        public void bindVolumeQuery(CommandBuffer cmd, ShaderProgram shader) {
            if (generation.volumetric() != null) {
                generation.volumetric().bindQuery(cmd, shader);
            }
        }

        @Override
        public void bindFrameState(ShaderProgram shader, CommandBuffer cmd, int shadowTexture,
                                   int pointShadowTexture, int spotShadowTexture,
                                   int gtaoTexture) {
            RenderPipeline.this.bindFrameState(shader, cmd, shadowTexture, pointShadowTexture,
                    spotShadowTexture, gtaoTexture);
        }

        @Override
        public boolean invalidatesFrameState(MaterialInstance material) {
            return RenderPipeline.this.invalidatesFrameState(material);
        }

        @Override
        public void bindPbrMaterial(ShaderProgram shader, CommandBuffer cmd) {
            generation.pbrMaterialBinder().bind(shader, cmd);
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
                ? generation.shadowLightScheduler().plan(context.lightEntries(), currentLightTable,
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
        SceneFrame built = generation.sceneFrameBuilder().build(scene, context.camera(),
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
        currentShadowFramePlan = generation.shadowCache().prepare(selected, context, scene,
                localShadowSettings, directionalCascadeSettings, currentShadowCasterPlan);
        recordShadowCullingCacheOutcome(currentShadowFramePlan, currentShadowCasterPlan);
        if (generation.shadowSamplingBlock() != null) {
            generation.shadowSamplingBlock().update(currentShadowFramePlan, localShadowSettings);
        }
        lightPackStart = benchmarkCpuTimingEnabled ? System.nanoTime() : 0L;
        generation.clusteredLightingBinder().prepare(currentClusterGrid, currentLightTable,
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
        bindFrameState(shader, cmd, frameCamera(), shadowTexture, pointShadowTexture,
                spotShadowTexture, gtaoTexture);
    }

    private void bindFrameState(ShaderProgram shader, CommandBuffer cmd, Camera camera,
                                int shadowTexture, int pointShadowTexture,
                                int spotShadowTexture, int gtaoTexture) {
        requireGeneration().frameLightBinder().bind(shader, cmd, camera,
                shadowFramePlan(), lastDirectionalLightSpaceMatrix, lastDirectionalCascadeMatrices,
                lastDirectionalCascadeSplits, clusterDebugMode, shadowTexture, pointShadowTexture,
                spotShadowTexture, gtaoTexture);
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
        return unit == FrameLightBinder.SHADOW_TEXTURE_UNIT
                || unit == FrameLightBinder.POINT_SHADOW_TEXTURE_UNIT
                || unit == FrameLightBinder.SPOT_SHADOW_TEXTURE_UNIT
                || unit == PbrMaterialBinder.IRRADIANCE_UNIT
                || unit == PbrMaterialBinder.PREFILTERED_SPECULAR_UNIT
                || unit == PbrMaterialBinder.BRDF_LUT_UNIT
                || unit == FrameLightBinder.GTAO_TEXTURE_UNIT;
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
        if (requireGeneration().volumetric() == null) return stable;
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
