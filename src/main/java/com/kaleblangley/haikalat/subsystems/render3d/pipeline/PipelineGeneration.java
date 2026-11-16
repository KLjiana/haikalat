package com.kaleblangley.haikalat.subsystems.render3d.pipeline;

import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.FrameLightBinder;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowCasterRecorder;

import com.kaleblangley.haikalat.subsystems.render3d.PipelineTopology;

import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.VolumetricFogSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ClusteredLightingSettings;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewController;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PointShadowAtlas;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ScreenSpaceContactShadowPass;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowPrograms;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.SpotShadowAtlas;

import com.kaleblangley.haikalat.subsystems.render3d.ao.GtaoAllocationPlan;
import com.kaleblangley.haikalat.subsystems.render3d.ao.GtaoPasses;
import com.kaleblangley.haikalat.subsystems.render3d.ao.GtaoSurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsPass;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiIndirectPass;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiProbeVolume;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.environment.AtmosphereSettings;
import com.kaleblangley.haikalat.subsystems.render3d.environment.AtmosphereSkyPass;
import com.kaleblangley.haikalat.subsystems.render3d.environment.StylizedSkyRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraUniforms;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDeformationDefaults;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionPass;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrameBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneReactivePass;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceInputs;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceLayout;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfacePass;
import com.kaleblangley.haikalat.subsystems.render3d.surface.SceneSurfaceResolvePass;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctPass;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.UnderwaterVolumePass;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfacePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricAllocationPlan;
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
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencyPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;

import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;

import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingBinder;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.lighting.ClusteredLightingResources;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowCacheState;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowFrameBinder;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowLightScheduler;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ShadowSamplingBlock;

import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrEnvironmentSource;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.EnvironmentBackgroundRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterialBinder;
import com.kaleblangley.haikalat.subsystems.render3d.preview.GraphPreviewRenderer;

import java.util.concurrent.atomic.AtomicLong;

/** Exclusive owner of one complete RenderGraph/pass-resource generation. */
public final class PipelineGeneration implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    private final long id = NEXT_ID.getAndIncrement();
    private PipelineTopology topology;
    private RenderGraph graph;
    private CameraUniforms cameraUniforms;
    private SceneDeformationDefaults deformationDefaults;
    private ShadowSamplingBlock shadowSamplingBlock;
    private ShadowLightScheduler shadowLightScheduler = new ShadowLightScheduler();
    private ShadowCacheState shadowCache = new ShadowCacheState();
    private ShadowFrameBinder shadowFrameBinder = new ShadowFrameBinder();
    private ClusteredLightingResources clusteredResources;
    private ClusteredLightingBinder clusteredLightingBinder;
    private PostProcessPipeline postProcess;
    private ShadowCasterRecorder shadowCasterRecorder;
    private com.kaleblangley.haikalat.subsystems.render3d.lighting.FrameLightBinder frameLightBinder;
    private com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneRenderer sceneRenderer;
    private VolumetricPassBuilder volumetric;
    private VolumetricParticlePass volumetricParticles;
    private VolumetricCloudPass volumetricClouds;
    private SmokeSimulation smokeSimulation;
    private SmokeVolumePass smokeVolume;
    private StochasticTransparencyPass stochasticTransparency;
    private DdgiProbeVolume ddgiVolume;
    private DdgiIndirectPass ddgiIndirect;
    private VctPass vct;
    private HairStrandPass hairStrands;
    private StochasticTransparencySettings stochasticSettings =
            StochasticTransparencySettings.disabled();
    private WeightedOitPass weightedOit;
    private WeightedOitSettings weightedOitSettings = WeightedOitSettings.disabled();
    private PlanarReflectionPass planarReflection;
    private PlanarReflectionSettings planarReflectionSettings = PlanarReflectionSettings.disabled();
    private CausticsPass caustics;
    private CausticsSettings causticsSettings = CausticsSettings.disabled();
    private ScreenSpaceContactShadowPass screenSpaceContactShadows;
    private ContactShadowSettings screenSpaceContactShadowSettings =
            ContactShadowSettings.disabled();
    private ShadowPrograms shadowPrograms;
    private GtaoPasses gtao;
    private GtaoSurfaceInputs gtaoInputs;
    private SceneSurfaceInputs surfaceInputs;
    private SceneSurfacePass sceneSurfacePass;
    private SceneSurfaceResolvePass sceneSurfaceResolvePass;
    private SceneReactivePass sceneReactivePass;
    private WaterSurfacePass waterSurfacePass;
    private UnderwaterVolumePass underwaterVolumePass;
    private boolean waterRefraction;
    private String finalPassName;
    private PbrMaterialBinder pbrMaterialBinder;
    private EnvironmentBackgroundRenderer environmentBackground;
    private StylizedSkyRenderer stylizedSky;
    private AtmosphereSkyPass atmosphereSky;
    private SceneFrameBuilder sceneFrameBuilder = new SceneFrameBuilder();
    private GraphPreviewRenderer previewRenderer;
    private boolean closed;
    private boolean retired;
    private boolean submitted;
    private long cloudFrameSequence = Long.MIN_VALUE;
    private double cloudTimeSeconds;

    public void retire() {
        retired = true;
        if (surfaceInputs != null) surfaceInputs.retire();
    }
    private final java.util.Set<AutoCloseable> borrowed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private PipelineGeneration reuseSource;
    private final boolean sameScene;
    private VolumetricAllocationPlan volumeAllocation;
    private GtaoAllocationPlan gtaoAllocation;
    private SceneSurfaceLayout.Storage surfaceStorage = SceneSurfaceLayout.Storage.NONE;
    private SceneSurfaceLayout.Storage borrowedSurfaceStorage = SceneSurfaceLayout.Storage.NONE;
    private long surfacePeakBytes;
    private long borrowedVolumeBytes;
    private long transferredVolumeBytes;
    private long borrowedGtaoBytes;
    private long transferredGtaoBytes;
    private final int maximumTextureDimension;
    private final VolumetricFogSettings volumeSettings;
    private PbrEnvironment environment;

    private <T extends AutoCloseable> T borrow(T resource) {
        if (resource != null) borrowed.add(resource);
        return resource;
    }

    /** The graph and all private owners are validated before publication; no GL deletion occurs here. */
    public void adoptBorrowedResources(PipelineGeneration source) {
        if (reuseSource == null) return;
        if (source != reuseSource || source.closed) throw new IllegalStateException("stale pipeline donor");
        source.postProcess.validateTransferTo(postProcess);
        var donorSurfaceStorage = source.surfaceStorage.minus(borrowedSurfaceStorage);
        graph.commitReplacement();
        source.postProcess.transferResourcesTo(postProcess);
        source.surfaceStorage = donorSurfaceStorage;
        source.transferredVolumeBytes = borrowedVolumeBytes;
        source.transferredGtaoBytes = borrowedGtaoBytes;
        borrowedSurfaceStorage = SceneSurfaceLayout.Storage.NONE;
        source.borrowed.addAll(borrowed);
        borrowed.clear();
        reuseSource = null;
    }

    private void inheritUnrelatedState(PipelineGeneration source) {
        if (source == null || !sameScene) return;
        if (topology.sameShadowShape(source.topology)) {
            shadowLightScheduler = source.shadowLightScheduler;
            shadowCache = source.shadowCache;
            shadowFrameBinder = source.shadowFrameBinder;
        }
        sceneFrameBuilder = source.sceneFrameBuilder;
    }

    public void assembleGraph(ClusteredLightingSettings lighting, boolean fullScan) {
        var source = reuseSource;
        graph = source == null ? new RenderGraph(topology.width(), topology.height()) : RenderGraph.replacementOf(source.graph);
        surfaceInputs = new SceneSurfaceInputs(id, graph, topology.sceneSurface());
        cameraUniforms = source == null ? new CameraUniforms() : borrow(source.cameraUniforms);
        deformationDefaults = source == null ? new SceneDeformationDefaults() : borrow(source.deformationDefaults);
        if ((topology.directionalShadow() || topology.pointShadow() || topology.spotShadow())
                && (topology.pbrMaterials() || topology.volumetricFog())) {
            shadowSamplingBlock = source != null && source.shadowSamplingBlock != null
                    ? borrow(source.shadowSamplingBlock) : new ShadowSamplingBlock();
        }
        clusteredResources = source == null ? new ClusteredLightingResources(topology.width(), topology.height(), lighting)
                : borrow(source.clusteredResources);
        clusteredLightingBinder = source == null ? new ClusteredLightingBinder(clusteredResources, lighting)
                : borrow(source.clusteredLightingBinder);
        ClusteredLightingPassBuilder.addPasses(graph, topology, clusteredLightingBinder, fullScan);
    }

    public void assembleEnvironment(PbrEnvironment environment, boolean stylized,
                                    AtmosphereSettings atmosphere) {
        var source = reuseSource;
        this.environment = environment;
        boolean reuseEnvironment = source != null && source.environment == environment;
        if (topology.pbrMaterials()) {
            pbrMaterialBinder = !reuseEnvironment || source.pbrMaterialBinder == null
                    ? new PbrMaterialBinder(environment) : borrow(source.pbrMaterialBinder);
            environmentBackground = !reuseEnvironment || source.environmentBackground == null
                    ? new EnvironmentBackgroundRenderer(environment) : borrow(source.environmentBackground);
        }
        if (atmosphere != null) {
            // The atmosphere pass owns LUTs and programs; a candidate generation
            // always builds a fresh pair and retires the previous one with the
            // generation, matching the stylized-sky ownership rule.
            atmosphereSky = new AtmosphereSkyPass(atmosphere);
        } else if (stylized) {
            stylizedSky = source == null || source.stylizedSky == null
                    ? new StylizedSkyRenderer() : borrow(source.stylizedSky);
        }
    }

    public void assemblePostProcess(RenderSettings settings, PostProcessSettings effects,
                                    RenderGraph.PassExecutor vfx, RenderGraph.PassExecutor reactive,
                                    com.kaleblangley.haikalat.subsystems.postprocess.TemporalUpscalingSource
                                            temporalUpscalingSource) {
        var inputs = new SceneSurfacePostProcessAdapter(surfaceInputs, reactive);
        SsrEnvironmentSource ssrEnvironment = pbrMaterialBinder == null
                ? null : new PbrSsrEnvironment(pbrMaterialBinder);
        SsgiEnvironmentSource ssgiEnvironment = pbrMaterialBinder == null
                ? null : new PbrSsgiEnvironment(pbrMaterialBinder);
        postProcess = reuseSource == null
                ? PostProcessPipeline.create(settings, effects, topology.width(), topology.height(), vfx, inputs, inputs,
                        ssrEnvironment, temporalUpscalingSource, ssgiEnvironment)
                : reuseSource.postProcess.replacementAssembly(settings, effects, topology.width(), topology.height(),
                        inputs, vfx, inputs, ssrEnvironment, temporalUpscalingSource, ssgiEnvironment);
    }

    public void assembleDrawing(SceneRenderer.Host host, DirectionalShadowMap directionalShadowMap,
                                PointShadowAtlas pointShadowAtlas, SpotShadowAtlas spotShadowAtlas,
                                DirectionalCascadeSettings cascades, LocalShadowPipelineSettings localShadows,
                                InstancedRenderer instanced, boolean directLighting,
                                boolean imageBasedLighting) {
        ShadowPrograms donor = reuseSource == null ? null : reuseSource.shadowPrograms;
        shadowPrograms = ShadowPrograms.prepare(donor,
                topology.directionalShadow() || topology.pointShadow() || topology.spotShadow(),
                instanced != null && instanced.castShadows());
        if (donor != null && shadowPrograms == donor) borrow(donor);
        sceneRenderer = new SceneRenderer(host);
        frameLightBinder = new FrameLightBinder(cameraUniforms, shadowFrameBinder, shadowSamplingBlock,
                clusteredLightingBinder, directionalShadowMap, cascades, localShadows,
                topology.gtaoEnabled(), directLighting, imageBasedLighting);
        shadowCasterRecorder = new ShadowCasterRecorder(shadowPrograms,
                deformationDefaults, shadowCache, directionalShadowMap,
                pointShadowAtlas == null ? PointShadowAtlas.defaults() : pointShadowAtlas,
                spotShadowAtlas == null ? SpotShadowAtlas.defaults() : spotShadowAtlas, cascades, localShadows, instanced);
    }

    public void finishAssembly(GraphPreviewController controller, PbrEnvironment environment,
                               long previewId, boolean previewEnabled) {
        postProcess.addFinalPass(graph);
        finalPassName = postProcess.finalPassName();
        if (previewEnabled) {
            previewRenderer = new GraphPreviewRenderer(controller, graph, environment, previewId);
        }
    }

    public void assembleVolumetric(VolumetricFogSettings settings, Scene scene, PipelineGeneration source,
                            VolumetricPassBuilder.LightingInputs shadows) {
        if (!settings.enabled()) return;
        boolean reuse = canBorrowVolumetricOwner(scene);
        volumetric = reuse ? borrow(source.volumetric) : new VolumetricPassBuilder(id,
                topology.width(), topology.height(), settings, clusteredResources.maxTotalLights());
        if (!reuse) volumetric.configureColorBudget(topology);
        volumetric.addPasses(graph, clusteredLightingBinder.volumetricInputs(),
                graph.hasPass(ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS)
                        ? ClusteredLightingPassBuilder.CLUSTER_ASSIGN_PASS : ClusteredLightingPassBuilder.LIGHT_UPLOAD_PASS,
                shadows);
    }

    private boolean canBorrowVolumetricOwner(Scene scene) {
        var source = reuseSource;
        return source != null && source.volumetric != null && topology.volumetricFog()
                && source.topology.volumetricQuality() == topology.volumetricQuality()
                && source.topology.volumetricEmission() == topology.volumetricEmission()
                && source.volumetric.compatibleScene(scene, topology);
    }

    /**
     * Owns the optional local particle pass. A compatible donor with identical
     * settings is borrowed so an unrelated topology rebuild preserves its target;
     * any settings change allocates a complete candidate before publication.
     */
    public void assembleVolumetricParticles(VolumetricParticleSettings settings,
                                            PipelineGeneration source) {
        if (!topology.volumetricParticles()) return;
        boolean reuse = source != null && source.volumetricParticles != null
                && source.topology.sameManagedExtent(topology)
                && source.volumetricParticles.settings().equals(settings);
        volumetricParticles = reuse ? borrow(source.volumetricParticles)
                : new VolumetricParticlePass(settings, topology.width(), topology.height());
    }

    /**
     * Owns the optional visible cloud layer. The pass bakes its settings and
     * holds the temporal history, so a candidate generation always allocates a
     * fresh owner; a resize candidate replaces the pass together with the rest
     * of the generation.
     */
    public void assembleVolumetricClouds(VolumetricCloudSettings settings) {
        if (!topology.volumetricClouds()) return;
        volumetricClouds = new VolumetricCloudPass(settings, topology.width(), topology.height());
    }

    /**
     * Owns the optional fixed-step smoke simulation and its volume pass. The
     * simulation is stateful, so a donor with identical settings is borrowed to
     * preserve the deterministic step sequence; the volume pass is borrowed only
     * when both its settings and the managed extent are unchanged.
     */
    public void assembleSmoke(SmokeSimulationSettings simulationSettings,
                              SmokeRenderSettings renderSettings, PipelineGeneration source) {
        if (!topology.volumetricSmoke()) return;
        boolean reuseSimulation = source != null && source.smokeSimulation != null
                && source.smokeSimulation.settings().equals(simulationSettings);
        smokeSimulation = reuseSimulation ? borrow(source.smokeSimulation)
                : new SmokeSimulation(simulationSettings);
        boolean reuseVolume = source != null && source.smokeVolume != null
                && source.topology.sameManagedExtent(topology)
                && source.smokeVolume.settings().equals(renderSettings);
        smokeVolume = reuseVolume ? borrow(source.smokeVolume)
                : new SmokeVolumePass(renderSettings, topology.width(), topology.height());
    }

    /**
     * Owns the optional stochastic-transparency collection. The pass allocates
     * its quad capacity at construction, so only an equal capacity donor is
     * borrowed; per-frame scalars remain arguments of record/resolve.
     */
    public void assembleStochasticTransparency(StochasticTransparencySettings settings,
                                               PipelineGeneration source) {
        if (!topology.stochasticTransparency()) return;
        boolean reuse = source != null && source.stochasticTransparency != null
                && source.topology.sameManagedExtent(topology)
                && source.stochasticTransparency.maximumSurfaces() == settings.maximumSurfaces();
        stochasticTransparency = reuse ? borrow(source.stochasticTransparency)
                : new StochasticTransparencyPass(topology.width(), topology.height(), settings);
        stochasticSettings = settings;
    }

    /**
     * Owns the optional weighted-OIT collection.  The pass bakes its quad
     * capacity at construction and receives the per-frame settings/scalars as
     * record/resolve arguments, so only a compatible capacity/extent donor is
     * borrowed.
     */
    public void assembleWeightedOit(WeightedOitSettings settings, PipelineGeneration source) {
        if (!topology.weightedOit()) return;
        boolean reuse = source != null && source.weightedOit != null
                && source.topology.sameManagedExtent(topology)
                && source.weightedOit.maximumSurfaces() == settings.maximumSurfaces();
        weightedOit = reuse ? borrow(source.weightedOit)
                : new WeightedOitPass(topology.width(), topology.height(), settings);
        weightedOitSettings = settings;
    }

    /**
     * Owns the optional single-plane reflection view.  The pass and its
     * attachments bake the plane/resolution/interval settings, so an exact
     * settings match at the same managed extent is the only reusable donor.
     */
    public void assemblePlanarReflection(PlanarReflectionSettings settings,
                                         PipelineGeneration source) {
        if (!topology.planarReflections()) return;
        boolean reuse = source != null && source.planarReflection != null
                && source.topology.sameManagedExtent(topology)
                && source.planarReflection.settings().equals(settings);
        planarReflection = reuse ? borrow(source.planarReflection)
                : new PlanarReflectionPass(settings, topology.width(), topology.height());
        planarReflectionSettings = settings;
    }

    /**
     * Owns the optional water-caustics colour target.  The pass bakes its
     * sample count and settings, so only an exact match at the same managed
     * extent is borrowed; closing the generation retires the target.
     */
    public void assembleCaustics(CausticsSettings settings, PipelineGeneration source) {
        if (!topology.caustics()) return;
        boolean reuse = source != null && source.caustics != null
                && source.topology.sameManagedExtent(topology)
                && source.caustics.samples() == topology.sampleCount()
                && source.caustics.settings().equals(settings);
        caustics = reuse ? borrow(source.caustics)
                : new CausticsPass(settings, topology.sampleCount(), topology.width(),
                topology.height());
        causticsSettings = settings;
    }

    /**
     * Owns the optional screen-space contact-shadow colour target. The pass
     * bakes its single-sample/configuration state, so only an exact match at
     * the same managed extent is borrowed; closing the generation retires the
     * target, shader and mesh.
     */
    public void assembleScreenSpaceContactShadows(ContactShadowSettings settings,
                                                  PipelineGeneration source) {
        if (!topology.screenSpaceContactShadows()) return;
        boolean reuse = source != null && source.screenSpaceContactShadows != null
                && source.topology.sameManagedExtent(topology)
                && source.screenSpaceContactShadows.samples() == topology.sampleCount()
                && source.screenSpaceContactShadows.settings().equals(settings);
        screenSpaceContactShadows = reuse ? borrow(source.screenSpaceContactShadows)
                : new ScreenSpaceContactShadowPass(settings, topology.sampleCount(),
                topology.width(), topology.height());
        screenSpaceContactShadowSettings = settings;
    }

    /**
     * Owns the DDGI probe volume and its screen-space indirect pass. Both bake
     * the settings at construction, so only an equal-settings donor is borrowed.
     * The volume is camera-independent; the indirect target follows the extent.
     */
    public void assembleDdgi(DdgiSettings settings, PipelineGeneration source) {
        if (!topology.ddgi()) return;
        boolean reuseVolume = source != null && source.ddgiVolume != null
                && source.ddgiVolume.settings().equals(settings);
        ddgiVolume = reuseVolume ? borrow(source.ddgiVolume) : new DdgiProbeVolume(settings);
        boolean reuseIndirect = reuseVolume && source.ddgiIndirect != null
                && source.topology.sameManagedExtent(topology);
        ddgiIndirect = reuseIndirect ? borrow(source.ddgiIndirect)
                : new DdgiIndirectPass(ddgiVolume, topology.width(), topology.height());
    }

    /** Owns one bounded VCT runtime; the pass is extent-independent. */
    public void assembleVct(VctSettings settings, PipelineGeneration source) {
        if (!topology.voxelConeTracing()) return;
        boolean reuse = source != null && source.vct != null
                && source.vct.settings().equals(settings);
        vct = reuse ? borrow(source.vct) : new VctPass(settings);
    }

    /** Owns the procedural hair meshes and programs; geometry is baked per settings. */
    public void assembleHairStrands(HairStrandSettings settings, PipelineGeneration source) {
        if (!topology.hairStrands()) return;
        boolean reuse = source != null && source.hairStrands != null
                && source.topology.sameManagedExtent(topology)
                && source.hairStrands.settings().equals(settings);
        hairStrands = reuse ? borrow(source.hairStrands)
                : new HairStrandPass(settings, topology.width(), topology.height());
    }

    public void assembleGtao(com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings settings,
                      PipelineGeneration source, boolean instances, boolean shared) {
        if (!settings.enabled()) return;
        gtaoInputs = shared ? GtaoSurfaceInputs.shared(surfaceInputs, topology.antiAliasingMode(), settings.temporal())
                : GtaoSurfaceInputs.dedicated(id, graph, topology.antiAliasingMode(), settings.temporal());
        gtao = source != null && source.gtao != null && source.gtao.compatible(settings, shared) ? borrow(source.gtao)
                : new GtaoPasses(settings, topology.width(), topology.height(), shared, instances);
        gtao.addPreGeometryPasses(graph, gtaoInputs);
    }

    public void assembleSurfacePasses(PipelineGeneration source) {
        var layout = topology.sceneSurface();
        var previous = source == null ? null : source.topology.sceneSurface();
        if (layout.hasSurface()) {
            sceneSurfacePass = source != null && source.sceneSurfacePass != null
                    && layout.rasterShaderDefines().equals(previous.rasterShaderDefines())
                    ? borrow(source.sceneSurfacePass) : new SceneSurfacePass(layout);
        }
        if (layout.hasResolve()) {
            sceneSurfaceResolvePass = source != null && source.sceneSurfaceResolvePass != null
                    && layout.resolveShaderDefines().equals(previous.resolveShaderDefines())
                    ? borrow(source.sceneSurfaceResolvePass) : new SceneSurfaceResolvePass(layout);
        }
        if (layout.reactive() != null) {
            sceneReactivePass = source != null && source.sceneReactivePass != null
                    ? borrow(source.sceneReactivePass) : new SceneReactivePass();
        }
    }

    /**
     * Declares the finite water colour pass after geometry.  The pass owns only
     * its shader/mesh/sampler; scene colour, scene depth and the IBL environment
     * are borrowed per frame, so a compatible donor can be reused.
     */
    public void assembleWaterSurface(RenderGraph.PassExecutor executor) {
        assembleWaterSurface(executor, java.util.List.of());
    }

    /**
     * Declares the finite water colour pass with extra producer dependencies.
     * Caustics and planar reflections record before the water consumer, so the
     * graph must order them first even though they do not own the water target.
     */
    public void assembleWaterSurface(RenderGraph.PassExecutor executor,
                                     java.util.List<String> extraDependencies) {
        assembleWaterSurface(executor, extraDependencies, topology.waterRefraction(),
                com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS);
    }

    /**
     * Declares the finite water colour pass and, when refraction is active, the
     * companion single-sample background copy that the refraction shader samples.
     *
     * <p>Order is {@code scene colour producer -> WaterSurfaceRefractionCopy ->
     * WaterSurfacePass}.  The copy pass owns {@link WaterSurfacePass#REFRACTION_BACKGROUND}
     * and is a dependency of the water pass; the water pass itself still owns its
     * colour target and never samples the attachment it writes.</p>
     *
     * @param refractionSupport   whether the water pass compiles the refraction variant
     * @param sceneColorProducer  last graph producer of the colour the water sees through
     */
    public void assembleWaterSurface(RenderGraph.PassExecutor executor,
                                     java.util.List<String> extraDependencies,
                                     boolean refractionSupport,
                                     String sceneColorProducer) {
        var source = reuseSource;
        var depth = topology.sceneSurface().raw(
                com.kaleblangley.haikalat.subsystems.render3d.surface.SceneBufferChannel.DEPTH);
        if (depth == null) {
            throw new IllegalStateException("water surface requires the shared raster depth");
        }
        waterSurfacePass = source != null && source.waterSurfacePass != null
                && source.waterSurfacePass.samples() == topology.sampleCount()
                && source.waterSurfacePass.refractionSupported() == refractionSupport
                ? borrow(source.waterSurfacePass) : new WaterSurfacePass(topology.sampleCount(),
                refractionSupport);
        waterRefraction = refractionSupport;
        RenderGraph.PassBuilder pass = graph.addPass(WaterSurfacePass.PASS_NAME)
                .noClear()
                .dependsOn(com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets.GEOMETRY_PASS)
                .dependsOn(depth.producer());
        if (refractionSupport) {
            graph.addPass(WaterSurfacePass.REFRACTION_BACKGROUND_PASS)
                    .createColor(WaterSurfacePass.REFRACTION_BACKGROUND, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(sceneColorProducer)
                    .execute((res, cmd) -> WaterSurfacePass.recordBackgroundCopy(cmd,
                            res.framebufferOfPass(sceneColorProducer), res.currentTarget()));
            pass.dependsOn(WaterSurfacePass.REFRACTION_BACKGROUND_PASS);
        }
        for (String dependency : extraDependencies) {
            if (!dependency.equals(WaterSurfacePass.PASS_NAME)) pass.dependsOn(dependency);
        }
        if (topology.sampleCount() > 1) {
            pass.createColorMS(WaterSurfacePass.COLOR, RenderFormat.RGBA16F,
                    topology.sampleCount());
        } else {
            pass.createColor(WaterSurfacePass.COLOR, RenderFormat.RGBA16F);
        }
        pass.execute(executor);
    }

    /** @return true when this generation declares the water refraction companion pass */
    public boolean waterRefraction() {
        return waterRefraction;
    }

    /**
     * Declares the bounded underwater volume producer after the current scene
     * colour producer and before the post-process consumers.
     *
     * <p>The pass owns only its program and screen mesh; the colour target and,
     * under MSAA, the single-sample colour copy/depth resolve are graph-owned.
     * Under MSAA the draw samples the resolved single-sample copies instead of
     * the multisampled scene attachments; with a single-sample chain it samples
     * the current scene-colour texture and the shared raster depth directly. A
     * compatible donor pass is borrowed because the program is
     * sample-count-independent.</p>
     *
     * @param executor          draw executor for {@link UnderwaterVolumePass#PASS_NAME}
     * @param extraDependencies additional ordering edges
     * @param colorProducer     current scene-colour producer
     * @param colorTexture      logical texture name of that producer's colour
     * @param depthTexture      logical name of the shared raster depth
     * @param depthProducer     pass that produces the shared raster depth
     * @param format            scene colour format
     */
    public void assembleUnderwaterVolume(RenderGraph.PassExecutor executor,
                                         java.util.List<String> extraDependencies,
                                         String colorProducer, String colorTexture,
                                         String depthTexture, String depthProducer,
                                         RenderFormat format) {
        var source = reuseSource;
        underwaterVolumePass = source != null && source.underwaterVolumePass != null
                ? borrow(source.underwaterVolumePass) : new UnderwaterVolumePass();
        int samples = topology.sampleCount();
        boolean multisample = samples > 1;
        if (multisample) {
            graph.addPass(UnderwaterVolumePass.COLOR_COPY_PASS)
                    .createColor(UnderwaterVolumePass.SOURCE_COLOR, format)
                    .noClear()
                    .dependsOn(colorProducer)
                    .execute((res, cmd) -> UnderwaterVolumePass.recordColorCopy(cmd,
                            res.framebufferOfPass(colorProducer), res.currentTarget()));
            graph.addPass(UnderwaterVolumePass.DEPTH_RESOLVE_PASS)
                    .createDepthTexture(UnderwaterVolumePass.DEPTH_TEXTURE)
                    .noClear()
                    .dependsOn(depthProducer)
                    .execute((res, cmd) -> UnderwaterVolumePass.recordDepthResolve(cmd,
                            res.framebufferOfPass(depthProducer), res.currentTarget()));
        }
        var pass = graph.addPass(UnderwaterVolumePass.PASS_NAME).noClear();
        if (multisample) {
            pass.createColorMS(UnderwaterVolumePass.COLOR, format, samples);
        } else {
            pass.createColor(UnderwaterVolumePass.COLOR, format);
        }
        java.util.Set<String> dependencies = new java.util.LinkedHashSet<>();
        dependencies.add(colorProducer);
        dependencies.add(depthProducer);
        if (multisample) {
            dependencies.add(UnderwaterVolumePass.COLOR_COPY_PASS);
            dependencies.add(UnderwaterVolumePass.DEPTH_RESOLVE_PASS);
        }
        dependencies.addAll(extraDependencies);
        dependencies.remove(UnderwaterVolumePass.PASS_NAME);
        for (String dependency : dependencies) pass.dependsOn(dependency);
        pass.execute(executor);
    }


    public PipelineGeneration(PipelineTopology topology) {
        this(topology, null, false, 0, VolumetricFogSettings.disabled());
    }

    public PipelineGeneration(PipelineTopology topology, PipelineGeneration source, boolean sameScene, int maximumTextureDimension,
                              VolumetricFogSettings volumeSettings) {
        this.topology = topology;
        this.reuseSource = source;
        this.sameScene = sameScene;
        this.maximumTextureDimension = maximumTextureDimension;
        this.volumeSettings = volumeSettings;
        inheritUnrelatedState(source);
    }

    public void prepareModuleBudgets(Scene scene, GtaoSettings gtaoSettings, boolean shared, long retainedGtaoBytes,
                                     long retainedVolumeBytes, ClusteredLightingSettings lighting) {
        var source = reuseSource;
        if (source != null && source.gtao != null) {
            borrowedGtaoBytes = GtaoAllocationPlan.compatibleBytes(topology.width(), topology.height(),
                    gtaoSettings, shared, source.gtao.settings(), source.gtaoInputs.shared());
        }
        gtaoAllocation = GtaoAllocationPlan.prepare(topology.width(), topology.height(), gtaoSettings, shared,
                retainedGtaoBytes, borrowedGtaoBytes);
        if (source != null && source.volumetric != null && topology.volumetricFog()) {
            if (canBorrowVolumetricOwner(scene)) borrowedVolumeBytes = source.volumeAllocation.privateBytes();
            if (source.topology.sampleCount() == topology.sampleCount()
                    && source.topology.hdrVfx() == topology.hdrVfx()
                    && (topology.sampleCount() == 1 || (source.topology.sceneSurface().reactive() != null)
                            == (topology.sceneSurface().reactive() != null))) {
                borrowedVolumeBytes += source.volumeAllocation.colorBytes();
            }
        }
        volumeAllocation = VolumetricAllocationPlan.prepare(topology.width(), topology.height(), volumeSettings,
                lighting.maxLocalLights() + lighting.maxDirectionalLights(), topology.sampleCount(), topology.hdrVfx(),
                retainedVolumeBytes, borrowedVolumeBytes);
    }

    public long id() { return id; }
    public PipelineTopology topology() { return topology; }
    public RenderGraph graph() { return graph; }
    public CameraUniforms cameraUniforms() { return cameraUniforms; }
    public SceneDeformationDefaults deformationDefaults() { return deformationDefaults; }
    public ShadowSamplingBlock shadowSamplingBlock() { return shadowSamplingBlock; }
    public ShadowLightScheduler shadowLightScheduler() { return shadowLightScheduler; }
    public ShadowCacheState shadowCache() { return shadowCache; }
    public ShadowFrameBinder shadowFrameBinder() { return shadowFrameBinder; }
    public ClusteredLightingResources clusteredResources() { return clusteredResources; }
    public ClusteredLightingBinder clusteredLightingBinder() { return clusteredLightingBinder; }
    public PostProcessPipeline postProcess() { return postProcess; }
    public ShadowCasterRecorder shadowCasterRecorder() { return shadowCasterRecorder; }
    public FrameLightBinder frameLightBinder() { return frameLightBinder; }
    public SceneRenderer sceneRenderer() { return sceneRenderer; }
    public VolumetricPassBuilder volumetric() { return volumetric; }
    public VolumetricParticlePass volumetricParticles() { return volumetricParticles; }
    public VolumetricCloudPass volumetricClouds() { return volumetricClouds; }
    public SmokeSimulation smokeSimulation() { return smokeSimulation; }
    public SmokeVolumePass smokeVolume() { return smokeVolume; }
    public StochasticTransparencyPass stochasticTransparency() { return stochasticTransparency; }
    public DdgiProbeVolume ddgiVolume() { return ddgiVolume; }
    public DdgiIndirectPass ddgiIndirect() { return ddgiIndirect; }
    public VctPass vct() { return vct; }
    public HairStrandPass hairStrands() { return hairStrands; }
    public StochasticTransparencySettings stochasticSettings() { return stochasticSettings; }
    public WeightedOitPass weightedOit() { return weightedOit; }
    public WeightedOitSettings weightedOitSettings() { return weightedOitSettings; }
    public PlanarReflectionPass planarReflection() { return planarReflection; }
    public PlanarReflectionSettings planarReflectionSettings() { return planarReflectionSettings; }
    public CausticsPass caustics() { return caustics; }
    public CausticsSettings causticsSettings() { return causticsSettings; }
    public ScreenSpaceContactShadowPass screenSpaceContactShadows() {
        return screenSpaceContactShadows;
    }
    public ContactShadowSettings screenSpaceContactShadowSettings() {
        return screenSpaceContactShadowSettings;
    }
    public ShadowPrograms shadowPrograms() { return shadowPrograms; }
    public GtaoPasses gtao() { return gtao; }
    public GtaoSurfaceInputs gtaoInputs() { return gtaoInputs; }
    public SceneSurfaceInputs surfaceInputs() { return surfaceInputs; }
    public SceneSurfacePass sceneSurfacePass() { return sceneSurfacePass; }
    public SceneSurfaceResolvePass sceneSurfaceResolvePass() { return sceneSurfaceResolvePass; }
    public SceneReactivePass sceneReactivePass() { return sceneReactivePass; }
    public WaterSurfacePass waterSurfacePass() { return waterSurfacePass; }
    public UnderwaterVolumePass underwaterVolumePass() { return underwaterVolumePass; }
    public String finalPassName() { return finalPassName; }
    public PbrMaterialBinder pbrMaterialBinder() { return pbrMaterialBinder; }
    public EnvironmentBackgroundRenderer environmentBackground() { return environmentBackground; }
    public StylizedSkyRenderer stylizedSky() { return stylizedSky; }
    public AtmosphereSkyPass atmosphereSky() { return atmosphereSky; }
    public SceneFrameBuilder sceneFrameBuilder() { return sceneFrameBuilder; }
    public GraphPreviewRenderer previewRenderer() { return previewRenderer; }
    public boolean submitted() { return submitted; }
    public VolumetricAllocationPlan volumeAllocation() { return volumeAllocation; }
    public long retainedGtaoBytes() { return gtaoAllocation == null ? 0 : gtaoAllocation.activeBytes() - transferredGtaoBytes; }
    public long retainedVolumeBytes() { return volumeAllocation == null ? 0 : volumeAllocation.activeBytes() - transferredVolumeBytes; }
    public long surfacePeakBytes() { return surfacePeakBytes; }
    public VolumetricFogSettings volumeSettings() { return volumeSettings; }

    public boolean reusesSceneState() { return reuseSource != null && sameScene; }

    public void markSubmitted() { submitted = true; }

    /**
     * Advances the cloud-wind clock once per distinct frame sequence. The rule
     * matches the #10 cloud-shadow clock: the first observed sequence reports
     * zero and a delta is capped at 0.1 s. The clock lives with the generation
     * (and therefore with the cloud pass history).
     *
     * @param frameSequence the frame's monotonic sequence
     * @param deltaSeconds  the frame delta, clamped to a bounded effect time
     * @return the accumulated cloud time in seconds
     */
    public double advanceCloudTime(long frameSequence, float deltaSeconds) {
        if (cloudFrameSequence == Long.MIN_VALUE) {
            cloudFrameSequence = frameSequence;
            return cloudTimeSeconds;
        }
        if (frameSequence < cloudFrameSequence) {
            cloudFrameSequence = frameSequence;
            cloudTimeSeconds = 0.0;
            return 0.0;
        }
        if (frameSequence == cloudFrameSequence) {
            return cloudTimeSeconds;
        }
        cloudFrameSequence = frameSequence;
        if (Float.isFinite(deltaSeconds) && deltaSeconds > 0.0f) {
            cloudTimeSeconds += Math.min(deltaSeconds, 0.1f);
            if (!Double.isFinite(cloudTimeSeconds)) cloudTimeSeconds = 0.0;
        }
        return cloudTimeSeconds;
    }

    public double cloudTimeSeconds() { return cloudTimeSeconds; }

    private SceneSurfaceLayout.Storage surfaceStorageAt(int width, int height) {
        return topology.sceneSurface().storage(width, height);
    }

    public long surfaceBytes() { return surfaceStorage.bytes(); }

    public void prepareSurfaceBudget(PipelineGeneration source, long retainedBytes) {
        surfaceStorage = surfaceStorageAt(topology.width(), topology.height());
        if (source != null) {
            borrowedSurfaceStorage = topology.sceneSurface().compatibleStorageFrom(source.topology.sceneSurface(),
                    source.surfaceStorage, surfaceStorage);
        }
        surfacePeakBytes = Math.addExact(retainedBytes, surfaceStorage.minus(borrowedSurfaceStorage).bytes());
    }

    public boolean isClosed() {
        return closed || retired;
    }

    public void resize(int width, int height) {
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
        PostProcessPipeline.ResizeCandidate postProcessCandidate = null;
        ClusteredLightingResources.ResizeCandidate clusteredCandidate = null;
        VolumetricResources.ResizeCandidate volumeCandidate = null;
        GtaoPasses.ResizeCandidate gtaoCandidate = null;
        VolumetricParticlePass particleCandidate = null;
        StochasticTransparencyPass stochasticCandidate = null;
        VolumetricCloudPass cloudCandidate = null;
        SmokeVolumePass smokeVolumeCandidate = null;
        WeightedOitPass weightedOitCandidate = null;
        PlanarReflectionPass planarCandidate = null;
        CausticsPass causticsCandidate = null;
        ScreenSpaceContactShadowPass contactShadowCandidate = null;
        DdgiIndirectPass ddgiCandidate = null;
        HairStrandPass hairCandidate = null;
        try {
            GtaoAllocationPlan.validateDeviceExtent(width, height, maximumTextureDimension);
            var newSurfaceStorage = surfaceStorageAt(width, height);
            long newSurfacePeak = Math.addExact(surfaceBytes(), newSurfaceStorage.bytes());
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
            if (volumetricParticles != null) {
                // The pass target is transactional only inside resize(); allocate a
                // complete second owner here so a failed commit cannot mix extents.
                particleCandidate = new VolumetricParticlePass(
                        volumetricParticles.settings(), width, height);
            }
            if (stochasticTransparency != null) {
                stochasticCandidate = new StochasticTransparencyPass(width, height,
                        stochasticSettings);
            }
            if (volumetricClouds != null) {
                cloudCandidate = new VolumetricCloudPass(volumetricClouds.settings(), width, height);
            }
            if (smokeVolume != null) {
                smokeVolumeCandidate = new SmokeVolumePass(smokeVolume.settings(), width, height);
            }
            if (weightedOit != null) {
                weightedOitCandidate = new WeightedOitPass(width, height, weightedOitSettings);
            }
            if (planarReflection != null) {
                planarCandidate = new PlanarReflectionPass(planarReflectionSettings, width, height);
            }
            if (caustics != null) {
                causticsCandidate = new CausticsPass(causticsSettings, topology.sampleCount(),
                        width, height);
            }
            if (screenSpaceContactShadows != null) {
                contactShadowCandidate = new ScreenSpaceContactShadowPass(
                        screenSpaceContactShadowSettings, topology.sampleCount(), width, height);
            }
            if (ddgiIndirect != null) {
                ddgiCandidate = new DdgiIndirectPass(ddgiVolume, width, height);
            }
            if (hairStrands != null) {
                hairCandidate = new HairStrandPass(hairStrands.settings(), width, height);
            }
            return new ResizeCandidate(this, width, height, topology.withExtent(width, height),
                    graphCandidate, postProcessCandidate, clusteredCandidate, volumeCandidate,
                    gtaoCandidate, particleCandidate, stochasticCandidate,
                    cloudCandidate, smokeVolumeCandidate, weightedOitCandidate,
                    planarCandidate, causticsCandidate, contactShadowCandidate, ddgiCandidate,
                    hairCandidate, allocation, gtaoPlan, newSurfaceStorage, newSurfacePeak);
        } catch (RuntimeException | Error failure) {
            closeCandidate(hairCandidate, failure);
            closeCandidate(ddgiCandidate, failure);
            closeCandidate(contactShadowCandidate, failure);
            closeCandidate(causticsCandidate, failure);
            closeCandidate(planarCandidate, failure);
            closeCandidate(weightedOitCandidate, failure);
            closeCandidate(smokeVolumeCandidate, failure);
            closeCandidate(cloudCandidate, failure);
            closeCandidate(stochasticCandidate, failure);
            closeCandidate(particleCandidate, failure);
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
        private final PostProcessPipeline.ResizeCandidate postProcessCandidate;
        private final ClusteredLightingResources.ResizeCandidate clusteredCandidate;
        private final VolumetricResources.ResizeCandidate volumeCandidate;
        private final GtaoPasses.ResizeCandidate gtaoCandidate;
        private VolumetricParticlePass particleCandidate;
        private StochasticTransparencyPass stochasticCandidate;
        private VolumetricCloudPass cloudCandidate;
        private SmokeVolumePass smokeVolumeCandidate;
        private WeightedOitPass weightedOitCandidate;
        private PlanarReflectionPass planarCandidate;
        private CausticsPass causticsCandidate;
        private ScreenSpaceContactShadowPass contactShadowCandidate;
        private DdgiIndirectPass ddgiCandidate;
        private HairStrandPass hairCandidate;
        private final VolumetricAllocationPlan allocation;
        private final GtaoAllocationPlan gtaoPlan;
        private final SceneSurfaceLayout.Storage surfaceStorage;
        private final long surfacePeak;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PipelineGeneration owner, int width, int height,
                                PipelineTopology candidateTopology,
                                RenderGraph.ResizeCandidate graphCandidate,
                                PostProcessPipeline.ResizeCandidate postProcessCandidate,
                                ClusteredLightingResources.ResizeCandidate clusteredCandidate,
                                VolumetricResources.ResizeCandidate volumeCandidate, GtaoPasses.ResizeCandidate gtaoCandidate,
                                VolumetricParticlePass particleCandidate,
                                StochasticTransparencyPass stochasticCandidate,
                                VolumetricCloudPass cloudCandidate,
                                SmokeVolumePass smokeVolumeCandidate,
                                WeightedOitPass weightedOitCandidate,
                                PlanarReflectionPass planarCandidate,
                                CausticsPass causticsCandidate,
                                ScreenSpaceContactShadowPass contactShadowCandidate,
                                DdgiIndirectPass ddgiCandidate,
                                HairStrandPass hairCandidate,
                                VolumetricAllocationPlan allocation, GtaoAllocationPlan gtaoPlan,
                                SceneSurfaceLayout.Storage surfaceStorage, long surfacePeak) {
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.candidateTopology = candidateTopology;
            this.graphCandidate = graphCandidate;
            this.postProcessCandidate = postProcessCandidate;
            this.clusteredCandidate = clusteredCandidate;
            this.volumeCandidate = volumeCandidate;
            this.gtaoCandidate = gtaoCandidate;
            this.particleCandidate = particleCandidate;
            this.stochasticCandidate = stochasticCandidate;
            this.cloudCandidate = cloudCandidate;
            this.smokeVolumeCandidate = smokeVolumeCandidate;
            this.weightedOitCandidate = weightedOitCandidate;
            this.planarCandidate = planarCandidate;
            this.causticsCandidate = causticsCandidate;
            this.contactShadowCandidate = contactShadowCandidate;
            this.ddgiCandidate = ddgiCandidate;
            this.hairCandidate = hairCandidate;
            this.allocation = allocation;
            this.gtaoPlan = gtaoPlan;
            this.surfaceStorage = surfaceStorage;
            this.surfacePeak = surfacePeak;
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
            VolumetricParticlePass previousParticle = owner.volumetricParticles;
            StochasticTransparencyPass previousStochastic = owner.stochasticTransparency;
            VolumetricCloudPass previousCloud = owner.volumetricClouds;
            SmokeVolumePass previousSmoke = owner.smokeVolume;
            WeightedOitPass previousWeightedOit = owner.weightedOit;
            PlanarReflectionPass previousPlanar = owner.planarReflection;
            CausticsPass previousCaustics = owner.caustics;
            ScreenSpaceContactShadowPass previousContactShadow =
                    owner.screenSpaceContactShadows;
            DdgiIndirectPass previousDdgi = owner.ddgiIndirect;
            HairStrandPass previousHair = owner.hairStrands;
            owner.volumetricParticles = particleCandidate;
            owner.stochasticTransparency = stochasticCandidate;
            owner.volumetricClouds = cloudCandidate;
            owner.smokeVolume = smokeVolumeCandidate;
            owner.weightedOit = weightedOitCandidate;
            owner.planarReflection = planarCandidate;
            owner.caustics = causticsCandidate;
            owner.screenSpaceContactShadows = contactShadowCandidate;
            owner.ddgiIndirect = ddgiCandidate;
            owner.hairStrands = hairCandidate;
            particleCandidate = null;
            stochasticCandidate = null;
            cloudCandidate = null;
            smokeVolumeCandidate = null;
            weightedOitCandidate = null;
            planarCandidate = null;
            causticsCandidate = null;
            contactShadowCandidate = null;
            ddgiCandidate = null;
            hairCandidate = null;
            owner.topology = candidateTopology;
            owner.volumeAllocation = allocation;
            owner.gtaoAllocation = gtaoPlan;
            owner.surfaceStorage = surfaceStorage;
            owner.surfacePeakBytes = surfacePeak;
            committed = true;
            RuntimeException failure = null;
            failure = closeOne(previousHair, failure);
            failure = closeOne(previousDdgi, failure);
            failure = closeOne(previousContactShadow, failure);
            failure = closeOne(previousCaustics, failure);
            failure = closeOne(previousPlanar, failure);
            failure = closeOne(previousWeightedOit, failure);
            failure = closeOne(previousSmoke, failure);
            failure = closeOne(previousCloud, failure);
            failure = closeOne(previousStochastic, failure);
            failure = closeOne(previousParticle, failure);
            if (failure != null) throw failure;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            failure = closeOne(hairCandidate, failure);
            failure = closeOne(ddgiCandidate, failure);
            failure = closeOne(contactShadowCandidate, failure);
            failure = closeOne(causticsCandidate, failure);
            failure = closeOne(planarCandidate, failure);
            failure = closeOne(weightedOitCandidate, failure);
            failure = closeOne(smokeVolumeCandidate, failure);
            failure = closeOne(cloudCandidate, failure);
            failure = closeOne(stochasticCandidate, failure);
            failure = closeOne(particleCandidate, failure);
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
        if (surfaceInputs != null) surfaceInputs.retire();
        GraphPreviewRenderer localPreview = previewRenderer;
        ShadowPrograms localShadowPrograms = shadowPrograms;
        GtaoPasses localGtao = gtao;
        PostProcessPipeline localPostProcess = postProcess;
        VolumetricPassBuilder localVolume = volumetric;
        VolumetricParticlePass localParticles = volumetricParticles;
        VolumetricCloudPass localClouds = volumetricClouds;
        SmokeSimulation localSmokeSimulation = smokeSimulation;
        SmokeVolumePass localSmokeVolume = smokeVolume;
        StochasticTransparencyPass localStochastic = stochasticTransparency;
        WeightedOitPass localWeightedOit = weightedOit;
        PlanarReflectionPass localPlanar = planarReflection;
        CausticsPass localCaustics = caustics;
        ScreenSpaceContactShadowPass localContactShadow = screenSpaceContactShadows;
        DdgiProbeVolume localDdgiVolume = ddgiVolume;
        DdgiIndirectPass localDdgiIndirect = ddgiIndirect;
        VctPass localVct = vct;
        HairStrandPass localHair = hairStrands;
        CameraUniforms localCameraUniforms = cameraUniforms;
        SceneDeformationDefaults localDeformationDefaults = deformationDefaults;
        SceneSurfacePass localSceneSurface = sceneSurfacePass;
        SceneSurfaceResolvePass localSceneSurfaceResolve = sceneSurfaceResolvePass;
        SceneReactivePass localSceneReactive = sceneReactivePass;
        WaterSurfacePass localWaterSurface = waterSurfacePass;
        UnderwaterVolumePass localUnderwaterVolume = underwaterVolumePass;
        ShadowSamplingBlock localShadowSamplingBlock = shadowSamplingBlock;
        ClusteredLightingResources localClusteredResources = clusteredResources;
        ClusteredLightingBinder localClusteredBinder = clusteredLightingBinder;
        PbrMaterialBinder localPbrBinder = pbrMaterialBinder;
        EnvironmentBackgroundRenderer localBackground = environmentBackground;
        StylizedSkyRenderer localStylizedSky = stylizedSky;
        AtmosphereSkyPass localAtmosphereSky = atmosphereSky;
        RenderGraph localGraph = graph;
        previewRenderer = null;
        shadowPrograms = null;
        shadowCasterRecorder = null;
        gtao = null;
        gtaoInputs = null;
        postProcess = null;
        volumetric = null;
        volumetricParticles = null;
        volumetricClouds = null;
        smokeSimulation = null;
        smokeVolume = null;
        stochasticTransparency = null;
        weightedOit = null;
        planarReflection = null;
        caustics = null;
        screenSpaceContactShadows = null;
        ddgiVolume = null;
        ddgiIndirect = null;
        vct = null;
        hairStrands = null;
        cameraUniforms = null;
        deformationDefaults = null;
        sceneSurfacePass = null;
        sceneSurfaceResolvePass = null;
        sceneReactivePass = null;
        waterSurfacePass = null;
        underwaterVolumePass = null;
        shadowSamplingBlock = null;
        clusteredResources = null;
        clusteredLightingBinder = null;
        pbrMaterialBinder = null;
        environmentBackground = null;
        stylizedSky = null;
        atmosphereSky = null;
        graph = null;
        finalPassName = null;

        RuntimeException failure = null;
        failure = closeCollecting(localPreview, failure);
        failure = closeCollecting(localShadowPrograms, failure);
        failure = closeCollecting(localGtao, failure);
        failure = closeCollecting(localPostProcess, failure);
        failure = closeCollecting(localVolume, failure);
        failure = closeCollecting(localParticles, failure);
        failure = closeCollecting(localSmokeVolume, failure);
        // The volume pass borrows the field; close the consumer before the owner.
        failure = closeCollecting(localSmokeSimulation, failure);
        failure = closeCollecting(localClouds, failure);
        failure = closeCollecting(localStochastic, failure);
        failure = closeCollecting(localWeightedOit, failure);
        failure = closeCollecting(localPlanar, failure);
        failure = closeCollecting(localCaustics, failure);
        failure = closeCollecting(localContactShadow, failure);
        failure = closeCollecting(localDdgiIndirect, failure);
        failure = closeCollecting(localDdgiVolume, failure);
        failure = closeCollecting(localVct, failure);
        failure = closeCollecting(localHair, failure);
        failure = closeCollecting(localCameraUniforms, failure);
        failure = closeCollecting(localSceneSurface, failure);
        failure = closeCollecting(localSceneSurfaceResolve, failure);
        failure = closeCollecting(localSceneReactive, failure);
        failure = closeCollecting(localWaterSurface, failure);
        failure = closeCollecting(localUnderwaterVolume, failure);
        failure = closeCollecting(localDeformationDefaults, failure);
        failure = closeCollecting(localShadowSamplingBlock, failure);
        failure = closeCollecting(localClusteredBinder, failure);
        failure = closeCollecting(localClusteredResources, failure);
        failure = closeCollecting(localPbrBinder, failure);
        failure = closeCollecting(localBackground, failure);
        failure = closeCollecting(localStylizedSky, failure);
        failure = closeCollecting(localAtmosphereSky, failure);
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
