package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.HostGlState;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
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
import org.joml.Matrix4f;
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
    private static final String HOST_COLOR_IMPORT = "HaikalatHostColor";
    private static final String HOST_DEPTH_IMPORT = "HaikalatHostDepth";
    private static final String HOST_STENCIL_IMPORT = "HaikalatHostStencil";
    private static final int SHADOW_TEXTURE_UNIT = 7;
    private static final int POINT_SHADOW_TEXTURE_UNIT = 11;
    private static final int SPOT_SHADOW_TEXTURE_UNIT = 12;
    private static final AtomicLong PREVIEW_GENERATIONS = new AtomicLong();
    
    // O2: Replace 26 string comparisons with O(1) HashSet lookup
    private static final Set<String> FRAME_OWNED_UNIFORMS = Set.of(
        "uDirectionalLightCount",
        "uPointLightCount",
        "uSpotLightCount",
        "uCameraPosition",
        "uDirectionalLightSpace",
        "uDirectionalShadowLightIndex",
        "uPointShadowLightIndex",
        "uSpotShadowLightIndex",
        "uHasDirectionalShadow",
        "uShadowMap",
        "uShadowBias",
        "uHasPointShadow",
        "uPointShadowMap",
        "uPointShadowBias",
        "uHasSpotShadow",
        "uSpotShadowMap",
        "uSpotShadowBias",
        "uSpotShadowMatrix",
        "uIrradianceMap",
        "uPrefilteredMap",
        "uBrdfLut",
        "uEnvironmentIntensity",
        "uEnvironmentRotation",
        "uPrefilterMaxLod"
    );
    private static final Set<String> FRAME_OWNED_UNIFORM_PREFIXES = Set.of(
        "uDirectionalLights[",
        "uPointLights[",
        "uSpotLights[",
        "uPointShadowMatrices["
    );
    private final RenderWindow window;
    private Scene scene;
    private final InstancedRenderer instanced;
    private final RenderSettings settings;
    private DirectionalShadowMap directionalShadowMap = DirectionalShadowMap.defaults();
    private DirectionalCascadeSettings directionalCascadeSettings =
            DirectionalCascadeSettings.disabled();
    private LocalShadowPipelineSettings localShadowSettings =
            LocalShadowPipelineSettings.legacyDefaults();
    private PointShadowAtlas pointShadowAtlas = PointShadowAtlas.defaults();
    private SpotShadowAtlas spotShadowAtlas = SpotShadowAtlas.defaults();
    private PipelineGeneration activeGeneration;
    private Matrix4f lastDirectionalLightSpaceMatrix = new Matrix4f();
    private List<Matrix4f> lastDirectionalCascadeMatrices = List.of();
    private float[] lastDirectionalCascadeSplits = new float[0];
    private List<Matrix4f> lastPointLightSpaceMatrices = List.of();
    private Matrix4f lastSpotLightSpaceMatrix = new Matrix4f();
    private ShadowFramePlan currentShadowFramePlan;
    private ShadowFramePlan lastShadowFramePlan = ShadowFramePlan.EMPTY;
    private int lastShadowCasterDrawCount;
    private int lastPointShadowCasterDrawCount;
    private int lastSpotShadowCasterDrawCount;
    private final PbrEnvironment pbrEnvironment;
    private long sceneFastPathReplacementCount;
    private long sceneGraphRebuildCount;
    private SceneFrame currentSceneFrame;
    private int activeFrameIndex;
    private int pipelineFrameIndex;
    private VisibilityStatistics lastVisibilityStatistics = VisibilityStatistics.UNAVAILABLE;
    private GraphPreviewController previewController = new GraphPreviewController();
    private final Set<RenderDevice> usedDevices = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Material, Boolean> frameStateInvalidationByMaterial = new IdentityHashMap<>();
    private PostProcessSettings postProcessSettings = PostProcessSettings.defaults();
    private PassExecutor hdrVfxRecorder;
    private CameraPassExecutor cameraAwareHdrVfxRecorder;
    private RenderFrameContext activeFrameContext;
    private RenderFrameContext lastFrameContext;
    private RenderFrameContext lastFailedFrameContext;
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
                directionalCascadeSettings, localShadowSettings);
        PipelineGeneration candidate = createGeneration(scene, candidateTopology);
        activateGeneration(candidate);
    }

    private PipelineGeneration createGeneration(Scene generationScene,
                                                PipelineTopology topology) {
        PipelineGeneration candidate = new PipelineGeneration(topology);
        lastCandidateGenerationId = candidate.id;
        try {
            new PipelineFeaturePolicy(topology, pbrEnvironment != null).validate();
            validatePbrVertexLayouts(generationScene);
            candidate.graph = new RenderGraph(topology.width(), topology.height());
            candidate.cameraUniforms = new CameraUniforms();
            if ((topology.directionalShadow() || topology.pointShadow() || topology.spotShadow())
                    && topology.pbrMaterials() && !localShadowSettings.legacySamplingContract()) {
                candidate.shadowSamplingBlock = new ShadowSamplingBlock();
            }
            if (topology.pbrMaterials()) {
                candidate.pbrMaterialBinder = new PbrMaterialBinder(pbrEnvironment);
                candidate.environmentBackground = new EnvironmentBackgroundRenderer(pbrEnvironment);
            }
            candidate.postProcess = PostProcessPassBuilder.create(
                    settings, postProcessSettings, window, topology.width(), topology.height(),
                    hdrVfxRecorder);
            boolean hasDirectionalShadow = topology.directionalShadow();
            boolean hasPointShadow = topology.pointShadow();
            boolean hasSpotShadow = topology.spotShadow();
            if (hasDirectionalShadow || hasPointShadow || hasSpotShadow) {
                candidate.shadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                        "/shaders/shadows/directional-depth.vert", "/shaders/shadows/directional-depth.frag");
                candidate.maskedShadowShader = ShaderProgram.fromResource(RenderPipeline.class,
                        "/shaders/shadows/masked-directional-depth.vert",
                        "/shaders/shadows/masked-directional-depth.frag");
                if (hasDirectionalShadow && instanced != null && instanced.castShadows()) {
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
                    geometryExecutor());
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
                hdrVfxRecorder != null, embedded, directionalCascadeSettings,
                localShadowSettings);
        if (generation.topology.equals(candidateTopology)) {
            scene = candidateScene;
            currentSceneFrame = null;
            activeFrameContext = null;
            frameStateInvalidationByMaterial.clear();
            sceneFastPathReplacementCount++;
            return;
        }
        PipelineGeneration candidate = createGeneration(candidateScene, candidateTopology);
        scene = candidateScene;
        currentSceneFrame = null;
        currentShadowFramePlan = null;
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
                    Render3dDiagnostics.CacheSummary.EMPTY, lastFailureStage);
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
        int cascadeCount = lastDirectionalCascadeMatrices.size();
        List<Float> splits = new java.util.ArrayList<>(lastDirectionalCascadeSplits.length);
        for (float split : lastDirectionalCascadeSplits) splits.add(split);
        List<Integer> cascadeCasters = cascadeCount == 0 ? List.of()
                : java.util.Collections.nCopies(cascadeCount,
                        cascadeCount == 0 ? 0 : lastShadowCasterDrawCount / cascadeCount);
        ShadowFramePlan shadowPlan = lastShadowFramePlan;
        List<Render3dDiagnostics.SelectedShadowLight> selectedLights = shadowPlan.decisions()
                .stream().filter(value -> value.status() == ShadowDecision.Status.SELECTED)
                .map(value -> new Render3dDiagnostics.SelectedShadowLight(value.stableId(),
                        value.type().name(), value.shaderIndex(), value.slot(),
                        value.priority(), value.score())).toList();
        List<Render3dDiagnostics.RejectedShadowLight> rejectedLights = shadowPlan.decisions()
                .stream().filter(value -> value.status() != ShadowDecision.Status.SELECTED)
                .map(value -> new Render3dDiagnostics.RejectedShadowLight(value.stableId(),
                        value.type().name(), value.shaderIndex(), value.status().name(),
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
        boolean depthResolved = generation.topology.fog()
                && generation.topology.antiAliasingMode() == AntiAliasingMode.MSAA;
        var depth = new Render3dDiagnostics.DepthResolveSummary(depthResolved,
                depthResolved ? generation.topology.sampleCount() : 1, 1,
                generation.topology.width(), generation.topology.height());
        var caches = new Render3dDiagnostics.CacheSummary(visibility.forwardQueueReused(),
                visibility.shadowQueueReused(), visibility.modelCacheHits(),
                visibility.modelCacheMisses(), visibility.boundsCacheHits(),
                visibility.boundsCacheMisses(), generationBuildCount, generationFailureCount,
                generationReuseCount, generationBuildCount + generationFailureCount);
        return new Render3dDiagnostics(visibility.available(), revisions,
                context.invalidation().bits(), reasons,
                generation.id, lastCandidateGenerationId, lastRetiredGenerationId,
                generation.topology.toString(), lastFrameTopologyRebuilt, queues, visible,
                shadows, depth, caches, lastFailureStage);
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
            generation.postProcess.beginFrame(
                    activeFrameContext.deltaSeconds(), activeFrameContext.camera(),
                    activeFrameContext.width(), activeFrameContext.height(),
                    activeFrameContext.frameIndex());
            postProcessFrameStarted = true;
            if (generation.previewRenderer != null) {
                generation.previewRenderer.prepare(device, previewFrameSequence);
            }
            // Freeze renderer membership, model matrices, bounds and queues before any graph
            // callback can observe mutable Scene inputs.
            frameStage = "frame-snapshot";
            sceneFrame();
            activeFrameContext.verifySceneStable(scene, topologySettingsRevision);
            frameStage = "pass-callback";
            PresentationResult result = generation.graph.execute(device, target);
            frameStage = "frame-finalize";
            long commandRecordNanos = 0L;
            for (var pass : generation.graph.lastFrameProfile().passes()) {
                commandRecordNanos = Math.addExact(commandRecordNanos, pass.cpuRecordNanos());
            }
            if (currentSceneFrame != null) {
                lastVisibilityStatistics = VisibilityStatistics.from(currentSceneFrame.statistics,
                        commandRecordNanos, generation.graph.lastRecordedCommandCount(),
                        generation.graph.lastRecordedMatrixSnapshots(),
                        generation.graph.lastRecordedObjectPayloads());
            }
            generation.postProcess.frameSucceeded();
            generation.shadowCache.frameSucceeded();
            if (currentShadowFramePlan != null) lastShadowFramePlan = currentShadowFramePlan;
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
            lastFailureStage = frameStage;
            lastFailedFrameContext = activeFrameContext;
            if (postProcessFrameStarted) generation.postProcess.frameFailed();
            generation.shadowCache.frameFailed();
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

    private PassExecutor geometryExecutor() {
        return (res, cmd) -> {
            copyHostAttachments(res, cmd);
            ShadowFramePlan plan = shadowFramePlan();
            renderScene(cmd,
                plan.directional().isPresent()
                        ? res.depthAttachment(DirectionalShadowMap.TEXTURE_NAME) : 0,
                !plan.points().isEmpty()
                        ? res.depthAttachment(PointShadowAtlas.TEXTURE_NAME) : 0,
                !plan.spots().isEmpty()
                        ? res.depthAttachment(SpotShadowAtlas.TEXTURE_NAME) : 0);
        };
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
                if (preserveTiles) {
                    cmd.enableScissor(true)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                            .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                            .clear(false, true);
                } else {
                    cmd.enableScissor(false)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height());
                }
                count += recordDirectionalShadowCasters(cmd, frame, matrices.get(cascade));
                if (instanced != null && instanced.castShadows()) {
                    ShaderProgram instancedShadowShader = generation.instancedShadowShader;
                    cmd.bindShader(instancedShadowShader)
                            .setUniformMat4(instancedShadowShader, "uLightSpace", matrices.get(cascade));
                    instanced.renderShadow(cmd);
                }
            }
            if (preserveTiles) cmd.enableScissor(false);
            lastShadowCasterDrawCount = count;
        });
    }

    private int recordDirectionalShadowCasters(CommandBuffer cmd, SceneFrame frame,
                                                Matrix4f lightSpace) {
        ShaderProgram boundShader = requireGeneration().shadowShader;
        cmd.bindShader(boundShader).setUniformMat4(boundShader, "uLightSpace", lightSpace);
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        for (int queueIndex = 0; queueIndex < frame.shadowCount; queueIndex++) {
            int entry = frame.shadowEntry(queueIndex);
            MeshRenderer renderer = frame.renderer(entry);
            ShaderProgram shader = shadowShaderFor(renderer);
            if (shader != boundShader) {
                cmd.bindShader(shader).setUniformMat4(shader, "uLightSpace", lightSpace);
                boundShader = shader;
            }
            bindMaskedShadowMaterial(cmd, shader, renderer.material());
            cmd.setUniformMat4(shader, "uModel", frame.model(entry));
            SceneDrawBinding binding = renderer.drawBinding();
            cmd.trySetUniformInt(shader, "uSkinningEnabled", binding.skinningEnabled() ? 1 : 0)
                    .trySetUniformInt(shader, "uMorphTargetCount", binding.morphTargetCount());
            binding.record(cmd, shader, (int) frame.frameIndex, SceneDrawBinding.Pass.SHADOW);
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }
        return frame.shadowCount;
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
                    ShadowTileRect tile = slot.faceTiles().get(face);
                    Matrix4f matrix = slot.faceMatrices().get(face);
                    if (preserveTiles) {
                        cmd.enableScissor(true)
                                .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                                .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                                .clear(false, true);
                    } else {
                        cmd.enableScissor(false)
                                .viewport(tile.x(), tile.y(), tile.width(), tile.height());
                    }
                    cmd.setUniformMat4(shadowShader, "uLightSpace", matrix);
                    draws += recordAllShadowCasters(cmd, frame, matrix);
                }
            }
            if (preserveTiles) cmd.enableScissor(false);
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
                if (preserveTiles) {
                    cmd.enableScissor(true)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height())
                            .scissor(tile.x(), tile.y(), tile.width(), tile.height())
                            .clear(false, true);
                } else {
                    cmd.enableScissor(false)
                            .viewport(tile.x(), tile.y(), tile.width(), tile.height());
                }
                cmd.setUniformMat4(shadowShader, "uLightSpace", slot.lightSpaceMatrix());
                draws += recordAllShadowCasters(cmd, frame, slot.lightSpaceMatrix());
            }
            if (preserveTiles) cmd.enableScissor(false);
            lastSpotShadowCasterDrawCount = draws;
        };
    }

    private int recordAllShadowCasters(CommandBuffer cmd, SceneFrame frame, Matrix4f lightSpace) {
        ShaderProgram boundShader = requireGeneration().shadowShader;
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        int draws = 0;
        for (int entry = 0; entry < frame.rendererCount(); entry++) {
            MeshRenderer renderer = frame.renderer(entry);
            if (!renderer.castShadows()
                    || !RenderQueueClass.classify(renderer.material()).castsOpaqueShadow()) {
                continue;
            }
            ShaderProgram entryShader = shadowShaderFor(renderer);
            if (entryShader != boundShader) {
                cmd.bindShader(entryShader).setUniformMat4(entryShader, "uLightSpace", lightSpace);
                boundShader = entryShader;
            }
            bindMaskedShadowMaterial(cmd, entryShader, renderer.material());
            cmd.setUniformMat4(entryShader, "uModel", frame.model(entry));
            SceneDrawBinding drawBinding = renderer.drawBinding();
            cmd.trySetUniformInt(entryShader, "uSkinningEnabled",
                    drawBinding.skinningEnabled() ? 1 : 0)
                    .trySetUniformInt(entryShader, "uMorphTargetCount",
                            drawBinding.morphTargetCount());
            drawBinding.record(cmd, entryShader, (int) frame.frameIndex,
                    SceneDrawBinding.Pass.SHADOW);
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
                             int pointShadowTexture, int spotShadowTexture) {
        PipelineGeneration generation = requireGeneration();
        SceneFrame frame = sceneFrame();
        Camera camera = frameCamera();
        generation.cameraUniforms.update(cmd, camera, frameWidth(), frameHeight(),
                settings.antiAliasingMode(), activeFrameIndex);
        if (generation.environmentBackground != null) {
            generation.environmentBackground.render(cmd, camera, frameWidth(), frameHeight());
        }

        ShaderProgram boundShader = null;
        MaterialInstance boundMaterial = null;
        Material boundMaterialTemplate = null;
        boolean boundMaterialHasOverrides = false;
        com.kaleblangley.haikalat.core.mesh.Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean boundMirrored = false;
        for (int queueIndex = 0; queueIndex < frame.forwardCount; queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            MeshRenderer renderer = frame.renderer(entry);
            Matrix4f model = frame.model(entry);
            MaterialInstance material = renderer.material();
            Material materialTemplate = material.material();
            ShaderProgram shader = materialTemplate.shader();
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
                material.bind(cmd);
            }
            boundMaterial = material;
            boundMaterialTemplate = materialTemplate;
            boundMaterialHasOverrides = materialHasOverrides;
            if (shader != boundShader
                    || materialBindingChanged && invalidatesFrameState(material)) {
                bindFrameState(shader, cmd, shadowTexture, pointShadowTexture, spotShadowTexture);
                if (material.material().model() == MaterialModel.METALLIC_ROUGHNESS) {
                    generation.pbrMaterialBinder.bind(shader, cmd);
                }
                boundShader = shader;
            }
            cmd.setUniformMat4(shader, "uModel", model);
            SceneDrawBinding drawBinding = renderer.drawBinding();
            cmd.trySetUniformInt(shader, "uSkinningEnabled",
                    drawBinding.skinningEnabled() ? 1 : 0)
                    .trySetUniformInt(shader, "uMorphTargetCount",
                            drawBinding.morphTargetCount());
            drawBinding.record(cmd, shader, (int) frame.frameIndex,
                    SceneDrawBinding.Pass.FORWARD);
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }

        if (instanced != null) {
            cmd.bindShader(instanced.shader());
            cmd.enableBlend(false).depthMask(true).enableDepthTest(true);
            bindFrameState(instanced.shader(), cmd, shadowTexture,
                    pointShadowTexture, spotShadowTexture);
            instanced.render(cmd);
        }
    }

    private SceneFrame sceneFrame() {
        if (currentSceneFrame != null) return currentSceneFrame;

        RenderFrameContext context = requireFrameContext();
        PipelineGeneration generation = requireGeneration();
        FrameInvalidation invalidation = context.invalidation();
        boolean reschedule = lastShadowFramePlan == ShadowFramePlan.EMPTY
                || invalidation.invalidated(FrameInvalidation.Domain.LIGHTING)
                || invalidation.invalidated(FrameInvalidation.Domain.CAMERA)
                || invalidation.invalidated(FrameInvalidation.Domain.TOPOLOGY_SETTINGS);
        ShadowFramePlan selected = reschedule
                ? generation.shadowLightScheduler.plan(context.lightEntries(),
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
        SceneFrame built = generation.sceneFrameBuilder.build(scene, context.camera(),
                context.width(), context.height(), lastDirectionalCascadeMatrices.isEmpty()
                        ? lastDirectionalLightSpaceMatrix
                        : lastDirectionalCascadeMatrices.get(lastDirectionalCascadeMatrices.size() - 1),
                selected.directional().isPresent(), settings.sceneVisibility(),
                settings.sceneVisibility() && !directionalCascadeSettings.enabled(),
                context.frameIndex());
        currentShadowFramePlan = generation.shadowCache.prepare(selected, context, scene,
                localShadowSettings, directionalCascadeSettings);
        if (generation.shadowSamplingBlock != null) {
            generation.shadowSamplingBlock.update(currentShadowFramePlan, localShadowSettings);
        }
        currentSceneFrame = built;
        return built;
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
                                int pointShadowTexture, int spotShadowTexture) {
        PipelineGeneration generation = requireGeneration();
        generation.cameraUniforms.bind(shader);
        RenderFrameContext context = requireFrameContext();
        ShadowFramePlan shadowPlan = shadowFramePlan();
        boolean useSamplingBlock = generation.shadowSamplingBlock != null;
        generation.lightingBinder.bind(shader, cmd, lastDirectionalLightSpaceMatrix,
                lastDirectionalCascadeMatrices, lastDirectionalCascadeSplits,
                directionalCascadeSettings, context.camera(), context.lights(), shadowPlan);
        boolean hasShadow = shadowTexture != 0;
        if (useSamplingBlock) {
            cmd.trySetUniformInt(shader, "uUseShadowSamplingBlock", 1);
        }
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
            for (int face = 0; face < lastPointLightSpaceMatrices.size(); face++) {
                cmd.trySetUniformMat4(shader, "uPointShadowMatrices[" + face + "]",
                        lastPointLightSpaceMatrices.get(face));
            }
            cmd.bindTexture(POINT_SHADOW_TEXTURE_UNIT, pointShadowTexture);
        }
        boolean hasSpotShadow = spotShadowTexture != 0;
        cmd.trySetUniformInt(shader, "uHasSpotShadow", hasSpotShadow ? 1 : 0)
                .trySetUniformInt(shader, "uSpotShadowMap", SPOT_SHADOW_TEXTURE_UNIT)
                .trySetUniformFloat(shader, "uSpotShadowBias", localShadowSettings.spot().bias())
                .trySetUniformMat4(shader, "uSpotShadowMatrix", lastSpotLightSpaceMatrix);
        if (hasSpotShadow) cmd.bindTexture(SPOT_SHADOW_TEXTURE_UNIT, spotShadowTexture);
        if (generation.shadowSamplingBlock != null) generation.shadowSamplingBlock.bind(cmd);
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
                                                  int recordedObjectPayloads) {
            return new VisibilityStatistics(true, source.cullingEnabled(), source.sceneRevision(),
                    source.candidateRenderers(), source.finiteBoundsRenderers(),
                    source.unboundedRenderers(), source.forwardVisible(), source.forwardCulled(),
                    source.shadowCandidates(), source.shadowVisible(), source.shadowCulled(),
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
                || unit == PbrMaterialBinder.BRDF_LUT_UNIT;
    }

    private Camera frameCamera() {
        RenderFrameContext context = activeFrameContext;
        return context == null ? scene.camera() : context.camera();
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
