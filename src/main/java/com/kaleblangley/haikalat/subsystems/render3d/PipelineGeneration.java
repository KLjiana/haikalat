package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.EnvironmentBackgroundRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewRenderer;

import java.util.concurrent.atomic.AtomicLong;

/** Exclusive owner of one complete RenderGraph/pass-resource generation. */
final class PipelineGeneration implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    final long id = NEXT_ID.getAndIncrement();
    PipelineTopology topology;
    RenderGraph graph;
    CameraUniforms cameraUniforms;
    SceneDeformationDefaults deformationDefaults;
    ShadowSamplingBlock shadowSamplingBlock;
    ShadowLightScheduler shadowLightScheduler = new ShadowLightScheduler();
    ShadowCacheState shadowCache = new ShadowCacheState();
    ShadowFrameBinder shadowFrameBinder = new ShadowFrameBinder();
    ClusteredLightingResources clusteredResources;
    ClusteredLightingBinder clusteredLightingBinder;
    PostProcessPassBuilder postProcess;
    VolumetricPassBuilder volumetric;
    ShaderProgram shadowShader;
    ShaderProgram maskedShadowShader;
    ShaderProgram instancedShadowShader;
    GtaoPasses gtao;
    GtaoSurfaceInputs gtaoInputs;
    SceneSurfacePass sceneSurfacePass;
    SceneSurfaceResolvePass sceneSurfaceResolvePass;
    SceneReactivePass sceneReactivePass;
    String finalPassName;
    PbrMaterialBinder pbrMaterialBinder;
    EnvironmentBackgroundRenderer environmentBackground;
    StylizedSkyRenderer stylizedSky;
    SceneFrameBuilder sceneFrameBuilder = new SceneFrameBuilder();
    GraphPreviewRenderer previewRenderer;
    private boolean closed;
    private boolean retired;
    boolean submitted;

    void retire() { retired = true; }
    private final java.util.Set<AutoCloseable> borrowed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    PipelineGeneration reuseSource;
    VolumetricAllocationPlan volumeAllocation;
    GtaoAllocationPlan gtaoAllocation;
    int maximumTextureDimension;
    VolumetricFogSettings volumeSettings;

    <T extends AutoCloseable> T borrow(T resource) {
        if (resource != null) borrowed.add(resource);
        return resource;
    }

    /** The graph and all private owners are validated before publication; no GL deletion occurs here. */
    void adoptBorrowedResources(PipelineGeneration source) {
        if (reuseSource == null) return;
        if (source != reuseSource || source.closed) throw new IllegalStateException("stale pipeline donor");
        source.postProcess.validateTransferTo(postProcess);
        graph.commitReplacement();
        source.postProcess.transferResourcesTo(postProcess);
        source.borrowed.addAll(borrowed);
        borrowed.clear();
        reuseSource = null;
    }

    void inheritUnrelatedState(PipelineGeneration source) {
        if (source == null) return;
        shadowLightScheduler = source.shadowLightScheduler;
        shadowCache = source.shadowCache;
        shadowFrameBinder = source.shadowFrameBinder;
        sceneFrameBuilder = source.sceneFrameBuilder;
    }

    void assembleVolumetric(VolumetricFogSettings settings, Scene scene, PipelineGeneration source,
                            VolumetricPassBuilder.LightingInputs shadows) {
        if (!settings.enabled()) return;
        boolean reuse = source != null && source.volumetric != null
                && source.topology.volumetricQuality() == topology.volumetricQuality()
                && source.topology.volumetricEmission() == topology.volumetricEmission()
                && source.volumetric.compatibleScene(scene, topology);
        volumetric = reuse ? borrow(source.volumetric) : new VolumetricPassBuilder(id,
                topology.width(), topology.height(), settings, clusteredResources.maxTotalLights());
        if (!reuse) volumetric.configureColorBudget(topology);
        volumetric.addPasses(graph, clusteredLightingBinder.volumetricInputs(),
                graph.hasPass(ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS)
                        ? ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS : ClusteredLightingPassBuilder.LIGHT_UPLOAD_PASS,
                shadows);
    }

    void assembleGtao(com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings settings,
                      PipelineGeneration source, boolean instances, boolean shared) {
        if (!settings.enabled()) return;
        gtaoInputs = shared ? GtaoSurfaceInputs.shared(id, graph, topology, settings.temporal())
                : GtaoSurfaceInputs.dedicated(id, graph, topology.antiAliasingMode(), settings.temporal());
        gtao = source != null && source.gtao != null && source.gtao.compatible(settings, shared) ? borrow(source.gtao)
                : new GtaoPasses(settings, topology.width(), topology.height(), shared, instances);
        gtao.addPreGeometryPasses(graph, gtaoInputs);
    }

    PipelineGeneration(PipelineTopology topology) {
        this.topology = topology;
    }

    boolean isClosed() {
        return closed || retired;
    }

    void resize(int width, int height) {
        if (isClosed()) throw new IllegalStateException("PipelineGeneration is closed");
        if (width <= 0 || height <= 0
                || graph.width() == width && graph.height() == height) return;
        ResizeCandidate candidate = null;
        Throwable primaryFailure = null;
        try {
            candidate = prepareResize(width, height);
            commitResize(candidate);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (RuntimeException failure) {
                    cleanupFailure = failure;
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Allocates all resize resources without changing the active generation. */
    private ResizeCandidate prepareResize(int width, int height) {
        RenderGraph.ResizeCandidate graphCandidate = null;
        PostProcessPassBuilder.ResizeCandidate postProcessCandidate = null;
        ClusteredLightingResources.ResizeCandidate clusteredCandidate = null;
        VolumetricResources.ResizeCandidate volumeCandidate = null;
        GtaoPasses.ResizeCandidate gtaoCandidate = null;
        try {
            GtaoAllocationPlan.validateDeviceExtent(width, height, maximumTextureDimension);
            var allocation = VolumetricAllocationPlan.prepare(width, height, volumeSettings,
                    clusteredResources.maxTotalLights(), topology.sampleCount(), topology.hdrVfx(),
                    volumeAllocation == null ? 0 : volumeAllocation.activeBytes());
            var gtaoPlan = GtaoAllocationPlan.prepare(width, height, gtao == null
                    ? com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings.disabled() : gtao.settings(),
                    gtaoInputs == null || gtaoInputs.shared(), gtaoAllocation == null ? 0 : gtaoAllocation.activeBytes(), false);
            if (volumetric != null) volumetric.validateExtentBudget(width,height);
            graphCandidate = graph.prepareResize(width, height);
            postProcessCandidate = postProcess.prepareResize(width, height);
            clusteredCandidate = clusteredResources.prepareResize(width, height);
            if (volumetric != null) volumeCandidate = volumetric.prepareResize(width, height);
            if (gtao != null) gtaoCandidate = gtao.prepareResize(width, height);
            return new ResizeCandidate(this, width, height, topology.withExtent(width, height),
                    graphCandidate, postProcessCandidate, clusteredCandidate, volumeCandidate, gtaoCandidate, allocation, gtaoPlan);
        } catch (RuntimeException | Error failure) {
            closeCandidate(gtaoCandidate, failure);
            closeCandidate(volumeCandidate, failure);
            closeCandidate(clusteredCandidate, failure);
            closeCandidate(postProcessCandidate, failure);
            closeCandidate(graphCandidate, failure);
            throw failure;
        }
    }

    private static void closeCandidate(AutoCloseable candidate, Throwable failure) {
        if (candidate == null) {
            return;
        }
        try {
            candidate.close();
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void commitResize(ResizeCandidate candidate) {
        if (candidate == null) throw new NullPointerException("candidate");
        candidate.commitInto(this);
    }

    /** One generation-wide resize transaction for graph, TAA, GTAO, clustering and topology. */
    private static final class ResizeCandidate implements AutoCloseable {
        private final PipelineGeneration owner;
        private final int width;
        private final int height;
        private final PipelineTopology candidateTopology;
        private final RenderGraph.ResizeCandidate graphCandidate;
        private final PostProcessPassBuilder.ResizeCandidate postProcessCandidate;
        private final ClusteredLightingResources.ResizeCandidate clusteredCandidate;
        private final VolumetricResources.ResizeCandidate volumeCandidate;
        private final GtaoPasses.ResizeCandidate gtaoCandidate;
        private final VolumetricAllocationPlan allocation;
        private final GtaoAllocationPlan gtaoPlan;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PipelineGeneration owner, int width, int height,
                                PipelineTopology candidateTopology,
                                RenderGraph.ResizeCandidate graphCandidate,
                                PostProcessPassBuilder.ResizeCandidate postProcessCandidate,
                                ClusteredLightingResources.ResizeCandidate clusteredCandidate,
                                VolumetricResources.ResizeCandidate volumeCandidate, GtaoPasses.ResizeCandidate gtaoCandidate, VolumetricAllocationPlan allocation, GtaoAllocationPlan gtaoPlan) {
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.candidateTopology = candidateTopology;
            this.graphCandidate = graphCandidate;
            this.postProcessCandidate = postProcessCandidate;
            this.clusteredCandidate = clusteredCandidate;
            this.volumeCandidate = volumeCandidate;
            this.gtaoCandidate = gtaoCandidate;
            this.allocation = allocation;
            this.gtaoPlan = gtaoPlan;
        }

        private void validateFor(PipelineGeneration expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("resize candidate belongs to another pipeline generation");
            }
            if (closed) throw new IllegalStateException("pipeline resize candidate is closed");
            if (committed) throw new IllegalStateException("pipeline resize candidate already committed");
            // The child candidates are private to this generation and were
            // prepared from these exact owners.  Their public commit methods
            // perform the final ownership/state checks; after preparation they
            // only swap already-allocated handles and do not allocate.
        }

        private void commitInto(PipelineGeneration expectedOwner) {
            validateFor(expectedOwner);
            owner.graph.commitResize(graphCandidate);
            owner.postProcess.commitResize(postProcessCandidate);
            owner.clusteredResources.commitResize(clusteredCandidate);
            if (volumeCandidate != null) owner.volumetric.commitResize(volumeCandidate);
            if (gtaoCandidate != null) owner.gtao.commitResize(gtaoCandidate);
            owner.topology = candidateTopology;
            owner.volumeAllocation = allocation;
            owner.gtaoAllocation = gtaoPlan;
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            failure = closeOne(gtaoCandidate, failure);
            failure = closeOne(volumeCandidate, failure);
            failure = closeOne(clusteredCandidate, failure);
            failure = closeOne(postProcessCandidate, failure);
            failure = closeOne(graphCandidate, failure);
            if (failure != null) throw failure;
        }

        private static RuntimeException closeOne(AutoCloseable candidate, RuntimeException failure) {
            if (candidate == null) return failure;
            try {
                candidate.close();
            } catch (Exception closeFailure) {
                RuntimeException runtime = closeFailure instanceof RuntimeException existing
                        ? existing
                        : new IllegalStateException("Failed to close resize candidate", closeFailure);
                if (failure == null) return runtime;
                failure.addSuppressed(runtime);
            }
            return failure;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GraphPreviewRenderer localPreview = previewRenderer;
        ShaderProgram localInstancedShadow = instancedShadowShader;
        ShaderProgram localShadow = shadowShader;
        ShaderProgram localMaskedShadow = maskedShadowShader;
        GtaoPasses localGtao = gtao;
        PostProcessPassBuilder localPostProcess = postProcess;
        VolumetricPassBuilder localVolume = volumetric;
        CameraUniforms localCameraUniforms = cameraUniforms;
        SceneDeformationDefaults localDeformationDefaults = deformationDefaults;
        SceneSurfacePass localSceneSurface = sceneSurfacePass;
        SceneSurfaceResolvePass localSceneSurfaceResolve = sceneSurfaceResolvePass;
        SceneReactivePass localSceneReactive = sceneReactivePass;
        ShadowSamplingBlock localShadowSamplingBlock = shadowSamplingBlock;
        ClusteredLightingResources localClusteredResources = clusteredResources;
        ClusteredLightingBinder localClusteredBinder = clusteredLightingBinder;
        PbrMaterialBinder localPbrBinder = pbrMaterialBinder;
        EnvironmentBackgroundRenderer localBackground = environmentBackground;
        StylizedSkyRenderer localStylizedSky = stylizedSky;
        RenderGraph localGraph = graph;
        previewRenderer = null;
        instancedShadowShader = null;
        shadowShader = null;
        maskedShadowShader = null;
        gtao = null;
        gtaoInputs = null;
        postProcess = null;
        volumetric = null;
        cameraUniforms = null;
        deformationDefaults = null;
        sceneSurfacePass = null;
        sceneSurfaceResolvePass = null;
        sceneReactivePass = null;
        shadowSamplingBlock = null;
        clusteredResources = null;
        clusteredLightingBinder = null;
        pbrMaterialBinder = null;
        environmentBackground = null;
        stylizedSky = null;
        graph = null;
        finalPassName = null;

        RuntimeException failure = null;
        failure = closeCollecting(localPreview, failure);
        failure = closeCollecting(localInstancedShadow, failure);
        failure = closeCollecting(localShadow, failure);
        failure = closeCollecting(localMaskedShadow, failure);
        failure = closeCollecting(localGtao, failure);
        failure = closeCollecting(localPostProcess, failure);
        failure = closeCollecting(localVolume, failure);
        failure = closeCollecting(localCameraUniforms, failure);
        failure = closeCollecting(localSceneSurface, failure);
        failure = closeCollecting(localSceneSurfaceResolve, failure);
        failure = closeCollecting(localSceneReactive, failure);
        failure = closeCollecting(localDeformationDefaults, failure);
        failure = closeCollecting(localShadowSamplingBlock, failure);
        failure = closeCollecting(localClusteredBinder, failure);
        failure = closeCollecting(localClusteredResources, failure);
        failure = closeCollecting(localPbrBinder, failure);
        failure = closeCollecting(localBackground, failure);
        failure = closeCollecting(localStylizedSky, failure);
        failure = closeCollecting(localGraph, failure);
        if (failure != null) throw failure;
    }

    private RuntimeException closeCollecting(AutoCloseable resource,
                                                     RuntimeException failure) {
        if (resource == null || borrowed.contains(resource)) return failure;
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            RuntimeException runtime = cleanupFailure instanceof RuntimeException existing
                    ? existing
                    : new IllegalStateException("Failed to close pipeline generation resource",
                    cleanupFailure);
            if (failure == null) return runtime;
            failure.addSuppressed(runtime);
        }
        return failure;
    }
}
