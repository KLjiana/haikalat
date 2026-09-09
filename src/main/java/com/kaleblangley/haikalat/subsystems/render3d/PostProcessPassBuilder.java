package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.AutoExposurePass;
import com.kaleblangley.haikalat.subsystems.postprocess.FxaaPostProcessor;
import com.kaleblangley.haikalat.subsystems.postprocess.FogPass;
import com.kaleblangley.haikalat.subsystems.postprocess.BloomPass;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.TemporalAccumulationPass;
import com.kaleblangley.haikalat.subsystems.postprocess.ToneMappingPass;
import com.kaleblangley.haikalat.subsystems.windowing.RenderWindow;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Stream;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class PostProcessPassBuilder implements AutoCloseable {
    @FunctionalInterface
    interface OutdoorVolumeRecorder {
        void execute(com.kaleblangley.haikalat.core.graph.PassResources resources,
                     com.kaleblangley.haikalat.core.command.CommandBuffer commands,
                     int sourceColorTexture, int sceneDepthTexture);
    }
    static final int AUTO_EXPOSURE_RELATIVE_PASS_COUNT = 13;
    static final int AUTO_EXPOSURE_REDUCTION_PASS_COUNT = AUTO_EXPOSURE_RELATIVE_PASS_COUNT + 1;
    private final RenderSettings settings;
    private final PostProcessSettings effects;
    private final RenderWindow window;
    private final FxaaPostProcessor fxaa;
    private final TemporalAccumulationPass taa;
    private final TaaHistory taaHistory;
    private final ToneMappingPass toneMapping;
    private final BloomPass bloom;
    private final AutoExposurePass autoExposure;
    private final FogPass fog;
    private final GtaoPasses gtao;
    private final RenderGraph.PassExecutor hdrVfx;
    private final OutdoorVolumeRecorder outdoorVolume;
    private final OutdoorVolumetricUpsamplePass outdoorUpsample;
    private final OutdoorVolumetricTemporalPass outdoorTemporal;
    private final OutdoorVolumetricDepthHistoryPass outdoorDepthHistoryPass;
    private final OutdoorHistory outdoorHistory;
    private final OutdoorHistory outdoorDepthHistory;
    private final int outdoorDownsample;
    private final boolean surfaceResolved;
    private final boolean reactiveRequested;
    private final RenderGraph.PassExecutor reactiveRecorder;
    private final TaaSettings taaSettings = TaaSettings.defaults();
    private float currentJitterUvX;
    private float currentJitterUvY;
    private float previousJitterUvX;
    private float previousJitterUvY;
    private org.joml.Matrix4f taaInverseProjection = new org.joml.Matrix4f();
    private org.joml.Matrix4f previousTaaInverseProjection = new org.joml.Matrix4f();
    private final org.joml.Matrix4f previousTaaStableViewProjection = new org.joml.Matrix4f();
    private final org.joml.Matrix4f taaInverseView = new org.joml.Matrix4f();
    private boolean previousTaaFrameValid;
    private float outdoorHistoryWeight;
    private float outdoorDepthReject;
    private final Matrix4f fogInverseViewProjection = new Matrix4f();
    private final Matrix4f fogProjection = new Matrix4f();
    private final Matrix4f fogView = new Matrix4f();
    private final Vector3f fogCameraPosition = new Vector3f();
    private final Matrix4f outdoorProjection = new Matrix4f();
    private final Matrix4f outdoorView = new Matrix4f();
    private final Matrix4f outdoorViewProjection = new Matrix4f();
    private final Matrix4f outdoorInverseViewProjection = new Matrix4f();
    private final Matrix4f pendingOutdoorViewProjection = new Matrix4f();
    private final Matrix4f previousOutdoorViewProjection = new Matrix4f();
    private boolean previousOutdoorCameraValid;
    private final Vector3f previousOutdoorPosition = new Vector3f();
    private final Vector3f pendingOutdoorPosition = new Vector3f();
    private final Vector3f previousOutdoorForward = new Vector3f();
    private final Vector3f pendingOutdoorForward = new Vector3f();
    private float deltaSeconds = 1.0f / 60.0f;
    private String finalPassName;

    private PostProcessPassBuilder(RenderSettings settings, PostProcessSettings effects,
                                   RenderWindow window,
                                   FxaaPostProcessor fxaa,
                                   TemporalAccumulationPass taa,
                                   TaaHistory taaHistory,
                                   ToneMappingPass toneMapping,
                                   BloomPass bloom,
                                   AutoExposurePass autoExposure,
                                   FogPass fog, GtaoPasses gtao, RenderGraph.PassExecutor hdrVfx,
                                   OutdoorVolumeRecorder outdoorVolume,
                                   OutdoorVolumetricUpsamplePass outdoorUpsample,
                                   OutdoorVolumetricTemporalPass outdoorTemporal,
                                   OutdoorVolumetricDepthHistoryPass outdoorDepthHistoryPass,
                                   OutdoorHistory outdoorHistory,
                                   OutdoorHistory outdoorDepthHistory,
                                   int outdoorDownsample,
                                   float outdoorHistoryWeight, float outdoorDepthReject,
                                   boolean surfaceResolved, boolean reactiveRequested,
                                   RenderGraph.PassExecutor reactiveRecorder) {
        this.settings = settings;
        this.effects = effects;
        this.window = window;
        this.fxaa = fxaa;
        this.taa = taa;
        this.taaHistory = taaHistory;
        this.toneMapping = toneMapping;
        this.bloom = bloom;
        this.autoExposure = autoExposure;
        this.fog = fog;
        this.gtao = gtao;
        this.hdrVfx = hdrVfx;
        this.outdoorVolume = outdoorVolume;
        this.outdoorUpsample = outdoorUpsample;
        this.outdoorTemporal = outdoorTemporal;
        this.outdoorDepthHistoryPass = outdoorDepthHistoryPass;
        this.outdoorHistory = outdoorHistory;
        this.outdoorDepthHistory = outdoorDepthHistory;
        this.outdoorDownsample = outdoorDownsample;
        this.outdoorHistoryWeight = outdoorHistoryWeight;
        this.outdoorDepthReject = outdoorDepthReject;
        this.surfaceResolved = surfaceResolved;
        this.reactiveRequested = reactiveRequested;
        this.reactiveRecorder = reactiveRecorder;
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height) {
        return create(settings, effects, window, width, height, null);
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height,
                                         RenderGraph.PassExecutor hdrVfx) {
        return create(settings, effects, window, width, height, hdrVfx, null);
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height,
                                         RenderGraph.PassExecutor hdrVfx,
                                         OutdoorVolumeRecorder outdoorVolume) {
        return create(settings, effects, window, width, height, hdrVfx, outdoorVolume,
                0.86f, 0.08f, 2, false);
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height,
                                         RenderGraph.PassExecutor hdrVfx,
                                         OutdoorVolumeRecorder outdoorVolume,
                                         float outdoorHistoryWeight, float outdoorDepthReject) {
        return create(settings, effects, window, width, height, hdrVfx, outdoorVolume,
                outdoorHistoryWeight, outdoorDepthReject, 2, false);
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height,
                                         RenderGraph.PassExecutor hdrVfx,
                                         OutdoorVolumeRecorder outdoorVolume,
                                         float outdoorHistoryWeight, float outdoorDepthReject,
                                         int outdoorDownsample, boolean surfaceResolved) {
        return create(settings, effects, window, width, height, hdrVfx, outdoorVolume,
                outdoorHistoryWeight, outdoorDepthReject, outdoorDownsample, surfaceResolved,
                false, null);
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height,
                                         RenderGraph.PassExecutor hdrVfx,
                                         OutdoorVolumeRecorder outdoorVolume,
                                         float outdoorHistoryWeight, float outdoorDepthReject,
                                         int outdoorDownsample, boolean surfaceResolved,
                                         boolean reactiveRequested,
                                         RenderGraph.PassExecutor reactiveRecorder) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(window, "window");
        if (outdoorDownsample != 1 && outdoorDownsample != 2 && outdoorDownsample != 4) {
            throw new IllegalArgumentException("outdoorDownsample must be 1, 2 or 4");
        }
        FxaaPostProcessor fxaa = null;
        TemporalAccumulationPass taa = null;
        TaaHistory history = null;
        ToneMappingPass toneMapping = null;
        BloomPass bloom = null;
        AutoExposurePass autoExposure = null;
        FogPass fog = null;
        GtaoPasses gtao = null;
        OutdoorVolumetricUpsamplePass outdoorUpsample = null;
        OutdoorVolumetricTemporalPass outdoorTemporal = null;
        OutdoorVolumetricDepthHistoryPass outdoorDepthHistoryPass = null;
        OutdoorHistory outdoorHistory = null;
        OutdoorHistory outdoorDepthHistory = null;
        try {
            fxaa = settings.antiAliasingMode() == AntiAliasingMode.FXAA
                    ? new FxaaPostProcessor() : null;
            taa = settings.antiAliasingMode() == AntiAliasingMode.TAA
                    ? new TemporalAccumulationPass() : null;
            history = settings.antiAliasingMode() == AntiAliasingMode.TAA
                    ? new TaaHistory(width, height, taaHistoryFormat(settings)) : null;            toneMapping = settings.hdrEnabled()
                    ? new ToneMappingPass(effects.colorGrading()) : null;
            bloom = settings.bloomSettings().enabled() ? new BloomPass() : null;
            autoExposure = settings.exposureMode() == ExposureMode.AUTO
                    ? new AutoExposurePass() : null;
            fog = effects.fog().enabled() ? new FogPass() : null;
            gtao = effects.gtao().enabled() ? new GtaoPasses(effects.gtao(), width, height) : null;
            outdoorUpsample = outdoorVolume == null ? null : new OutdoorVolumetricUpsamplePass();
            outdoorTemporal = outdoorVolume == null ? null : new OutdoorVolumetricTemporalPass();
            outdoorDepthHistoryPass = outdoorVolume == null ? null : new OutdoorVolumetricDepthHistoryPass();
            if (outdoorVolume != null) {
                injectOutdoorHistoryAllocationFailureIfRequested();
                outdoorHistory = new OutdoorHistory(divideCeil(width, outdoorDownsample),
                        divideCeil(height, outdoorDownsample),
                        RenderFormat.RGBA16F);
                injectOutdoorDepthHistoryAllocationFailureIfRequested();
                outdoorDepthHistory = new OutdoorHistory(divideCeil(width, outdoorDownsample),
                        divideCeil(height, outdoorDownsample),
                        RenderFormat.R16F);
            }
            return new PostProcessPassBuilder(settings, effects, window, fxaa, taa, history, toneMapping,
                    bloom, autoExposure, fog, gtao, hdrVfx, outdoorVolume, outdoorUpsample,
                    outdoorTemporal, outdoorDepthHistoryPass, outdoorHistory, outdoorDepthHistory,
                    outdoorDownsample, outdoorHistoryWeight, outdoorDepthReject, surfaceResolved,
                    reactiveRequested, reactiveRecorder);
        } catch (RuntimeException failure) {
            closeAfterFailure(gtao, failure);
            closeAfterFailure(outdoorUpsample, failure);
            closeAfterFailure(outdoorTemporal, failure);
            closeAfterFailure(outdoorDepthHistoryPass, failure);
            closeAfterFailure(outdoorHistory, failure);
            closeAfterFailure(outdoorDepthHistory, failure);
            closeAfterFailure(fog, failure);
            closeAfterFailure(autoExposure, failure);
            closeAfterFailure(bloom, failure);
            closeAfterFailure(toneMapping, failure);
            closeAfterFailure(history, failure);
            closeAfterFailure(taa, failure);
            closeAfterFailure(fxaa, failure);
            throw failure;
        }
    }

    void addGtaoPreGeometryPasses(RenderGraph graph, RenderGraph.PassExecutor depthExecutor,
                                  boolean sharedSurface) {
        if (gtao != null) gtao.addPreGeometryPasses(graph, depthExecutor, sharedSurface);
    }

    static List<String> passNamesFor(AntiAliasingMode mode) {
        return passNamesFor(mode, ToneMappingMode.NONE, false);
    }

    static List<String> passNamesFor(AntiAliasingMode mode, boolean directionalShadow) {
        return passNamesFor(mode, ToneMappingMode.NONE, directionalShadow);
    }

    static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        return passNamesFor(mode, toneMappingMode, false);
    }

    static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                     boolean directionalShadow) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(toneMappingMode, "toneMappingMode");
        List<String> forwardPasses = toneMappingMode == ToneMappingMode.NONE
                ? ldrPassNames(mode) : hdrPassNames(mode);
        if (!directionalShadow) {
            return forwardPasses;
        }
        return Stream.concat(Stream.of(DirectionalShadowMap.PASS_NAME), forwardPasses.stream()).toList();
    }

    static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                     BloomSettings bloomSettings, boolean directionalShadow) {
        Objects.requireNonNull(bloomSettings, "bloomSettings");
        List<String> passes = new ArrayList<>(passNamesFor(mode, toneMappingMode, directionalShadow));
        if (!bloomSettings.enabled()) {
            return List.copyOf(passes);
        }
        if (toneMappingMode == ToneMappingMode.NONE) {
            throw new IllegalArgumentException("Bloom requires HDR tone mapping");
        }
        int toneIndex = passes.indexOf(PostProcessTargets.TONE_MAPPING_PASS);
        passes.addAll(toneIndex, bloomPassNames(bloomSettings.maxLevels()));
        return List.copyOf(passes);
    }

    void addFinalPass(RenderGraph graph) {
        if (finalPassName != null) {
            throw new IllegalStateException("Final backbuffer pass has already been added: "
                    + finalPassName);
        }
        if (settings.hdrEnabled()) {
            addHdrFinalPasses(graph);
            return;
        }
        addLdrFinalPass(graph);
    }

    private void addLdrFinalPass(RenderGraph graph) {
        switch (settings.antiAliasingMode()) {
            case FXAA -> {
                graph.addPass(PostProcessTargets.FXAA_PASS)
                    .createColor(PostProcessTargets.FXAA_COLOR, RenderFormat.SRGB8_ALPHA8)
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer geoFb = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        if (geoFb != null) {
                            fxaa.recordIntoCurrentTarget(cmd, res.colorAttachment(PostProcessTargets.SCENE_COLOR),
                                    geoFb.width(), geoFb.height());
                        }
                    });
                addLdrPresentPass(graph, PostProcessTargets.FXAA_PASS);
            }
            case TAA -> {
                if (reactiveRequested) {
                    graph.addPass(PostProcessTargets.SCENE_REACTIVE_PASS)
                            .createColor(PostProcessTargets.SCENE_REACTIVE, RenderFormat.R8)
                            .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                            .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                            .execute((res, cmd) -> {
                                if (reactiveRecorder != null) reactiveRecorder.execute(res, cmd);
                            });
                }
                RenderGraph.PassBuilder taaPass = graph.addPass(PostProcessTargets.TAA_PASS)
                    .writeToExternalTarget()
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS);
                if (reactiveRequested) {
                    taaPass.dependsOn(PostProcessTargets.SCENE_REACTIVE_PASS);
                }
                taaPass.execute((res, cmd) -> recordTaaResolve(res, cmd,
                        PostProcessTargets.SCENE_COLOR));
                addTaaPresentPass(graph);
            }
            case NONE, MSAA -> addLdrPresentPass(graph, PostProcessTargets.GEOMETRY_PASS);
        }
    }

    private void addLdrPresentPass(RenderGraph graph, String sourcePass) {
        graph.addPass(PostProcessTargets.PRESENT_PASS)
                .writeToBackbuffer()
                .noClear()
                .dependsOn(sourcePass)
                .execute((res, cmd) -> {
                    Framebuffer source = res.framebufferOfPass(sourcePass);
                    PresentationTarget target = res.presentationTarget();
                    if (source != null && target != null) {
                        cmd.blitFramebuffer(source.id(), target.drawFramebufferId(),
                                source.width(), source.height(),
                                target.width(), target.height());
                    }
                });
        recordFinalPass(PostProcessTargets.PRESENT_PASS);
    }

    private void addTaaPresentPass(RenderGraph graph) {
        graph.addPass(PostProcessTargets.PRESENT_PASS)
                .writeToBackbuffer()
                .noClear()
                .dependsOn(PostProcessTargets.TAA_PASS)
                .execute((res, cmd) -> {
                    PresentationTarget target = res.presentationTarget();
                    if (target != null && taaHistory != null) {
                        Framebuffer candidate = taaHistory.writeFramebuffer();
                        cmd.blitFramebuffer(candidate.id(), target.drawFramebufferId(),
                                candidate.width(), candidate.height(),
                                target.width(), target.height());
                    }
                });
        recordFinalPass(PostProcessTargets.PRESENT_PASS);
    }

    /**
     * Resolves the temporal candidate into the history write slot.  The
     * candidate color is imported as {@code TAA_RESOLVED_COLOR} by
     * {@link #synchronizeTemporalImports(RenderGraph)} so downstream passes can
     * consume it without an extra full-screen copy.
     */
    private void recordTaaResolve(com.kaleblangley.haikalat.core.graph.PassResources res,
                                  CommandBuffer cmd, String inputTexture) {
        if (taa == null || taaHistory == null) return;
        Framebuffer candidate = taaHistory.writeFramebuffer();
        boolean historyValid = taaHistory.valid() && previousTaaFrameValid;
        TaaResolveInputs inputs = new TaaResolveInputs(
                res.colorAttachment(inputTexture),
                historyValid ? taaHistory.readColorTexture() : 0,
                historyValid ? taaHistory.readDepthTexture() : 0,
                res.depthAttachment(PostProcessTargets.SCENE_DEPTH),
                res.colorAttachment(PostProcessTargets.SCENE_VELOCITY),
                res.colorAttachment(PostProcessTargets.SCENE_PREVIOUS_DEPTH),
                res.colorAttachment(PostProcessTargets.SCENE_VALIDITY),
                res.colorAttachment(PostProcessTargets.SCENE_REACTIVE),
                historyValid,
                candidate.width(), candidate.height(),
                currentJitterUvX, currentJitterUvY, previousJitterUvX, previousJitterUvY,
                taaInverseProjection, previousTaaStableViewProjection, taaInverseView, taaSettings);
        taa.recordResolve(cmd, candidate, inputs);
    }

    /**
     * Publishes this frame's history write color as a borrowed logical texture
     * for passes that run after the TAA resolve.
     */
    void synchronizeTemporalImports(RenderGraph graph) {
        if (taaHistory == null) return;
        int texture = taaHistory.writeColorTexture();
        graph.importExternalColor(PostProcessTargets.TAA_RESOLVED_COLOR,
                com.kaleblangley.haikalat.core.presentation.ExternalAttachment.borrowedColor(
                        texture, taaHistoryFormat(settings),
                        taaHistory.writeFramebuffer().width(),
                        taaHistory.writeFramebuffer().height()));
    }

    private void addHdrFinalPasses(RenderGraph graph) {
        String hdrTexture = PostProcessTargets.SCENE_COLOR;
        String hdrProducer = PostProcessTargets.GEOMETRY_PASS;

        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            graph.addPass(PostProcessTargets.HDR_RESOLVE_PASS)
                    .createColor(PostProcessTargets.HDR_RESOLVED_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer geometry = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        Framebuffer target = res.currentTarget();
                        if (geometry != null && target != null) {
                            cmd.blitColor(geometry, target);
                        }
                    });
            hdrTexture = PostProcessTargets.HDR_RESOLVED_COLOR;
            hdrProducer = PostProcessTargets.HDR_RESOLVE_PASS;
            if ((effects.fog().enabled() || outdoorVolume != null) && !surfaceResolved) {
                graph.addPass(PostProcessTargets.DEPTH_RESOLVE_PASS)
                        .createDepthTexture(PostProcessTargets.RESOLVED_SCENE_DEPTH)
                        .noClear()
                        .dependsOn(PostProcessTargets.GEOMETRY_PASS)
                        .execute((res, cmd) -> {
                            Framebuffer geometry = res.framebufferOfPass(
                                    PostProcessTargets.GEOMETRY_PASS);
                            Framebuffer target = res.currentTarget();
                            if (geometry != null && target != null) cmd.blitDepth(geometry, target);
                        });
            }
        } else if (settings.antiAliasingMode() == AntiAliasingMode.TAA) {
            // Native TAA resolves after fog/volume compositing below so the
            // temporal history sees the final scene color, not a pre-fog frame.
        }

        if (effects.fog().enabled()) {
            final String fogInputTexture = hdrTexture;
            final String fogInputPass = hdrProducer;
            final String fogDepthTexture = surfaceResolved
                    ? PostProcessTargets.SCENE_DEPTH
                    : settings.antiAliasingMode() == AntiAliasingMode.MSAA
                    ? PostProcessTargets.RESOLVED_SCENE_DEPTH : PostProcessTargets.SCENE_DEPTH;
            RenderGraph.PassBuilder fogBuilder = graph.addPass(PostProcessTargets.FOG_PASS)
                    .createColor(PostProcessTargets.FOG_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(fogInputPass);
            if (settings.antiAliasingMode() == AntiAliasingMode.MSAA && !surfaceResolved) {
                fogBuilder.dependsOn(PostProcessTargets.DEPTH_RESOLVE_PASS);
            }
            fogBuilder
                    .execute((res, cmd) -> fog.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(fogInputTexture),
                            res.depthAttachment(fogDepthTexture),
                            fogInverseViewProjection, fogCameraPosition,
                            effects.fog()));
            hdrTexture = PostProcessTargets.FOG_COLOR;
            hdrProducer = PostProcessTargets.FOG_PASS;
        }
        if (outdoorVolume != null) {
            final String volumeInputTexture = hdrTexture;
            final String volumeInputPass = hdrProducer;
            final String volumeDepthTexture = surfaceResolved
                    ? PostProcessTargets.SCENE_DEPTH
                    : settings.antiAliasingMode() == AntiAliasingMode.MSAA
                    ? PostProcessTargets.RESOLVED_SCENE_DEPTH : PostProcessTargets.SCENE_DEPTH;
            RenderGraph.PassBuilder volumeBuilder = graph.addPass(PostProcessTargets.OUTDOOR_VOLUME_PASS)
                    .createColors(List.of(PostProcessTargets.OUTDOOR_VOLUME_COLOR,
                                    PostProcessTargets.OUTDOOR_VOLUME_SAMPLE_DEPTH),
                            List.of(RenderFormat.RGBA16F, RenderFormat.R16F))
                    .relativeSize(1.0f / outdoorDownsample)
                    .noClear().dependsOn(volumeInputPass);
            if (settings.antiAliasingMode() == AntiAliasingMode.MSAA && !surfaceResolved) {
                volumeBuilder.dependsOn(PostProcessTargets.DEPTH_RESOLVE_PASS);
            }
            volumeBuilder.execute((res, cmd) -> {
                if (res.currentTarget() != null) {
                    outdoorVolume.execute(res, cmd, res.colorAttachment(volumeInputTexture),
                            res.depthAttachment(volumeDepthTexture));
                }
            });
            graph.addPass(PostProcessTargets.OUTDOOR_VOLUME_TEMPORAL_PASS)
                    .createColor(PostProcessTargets.OUTDOOR_VOLUME_TEMPORAL_COLOR, RenderFormat.RGBA16F)
                    .relativeSize(1.0f / outdoorDownsample).noClear()
                    .dependsOn(PostProcessTargets.OUTDOOR_VOLUME_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer volume = res.framebufferOfPass(PostProcessTargets.OUTDOOR_VOLUME_PASS);
                        Framebuffer target = res.currentTarget();
                        if (volume != null && target != null && outdoorHistory != null
                                && outdoorDepthHistory != null) {
                            int historyTexture = outdoorHistory.valid()
                                    ? outdoorHistory.framebuffer().colorAttachment() : 0;
                            int historyDepthTexture = outdoorDepthHistory.valid()
                                    ? outdoorDepthHistory.framebuffer().colorAttachment() : 0;
                            outdoorTemporal.recordIntoCurrentTarget(cmd,
                                    res.colorAttachment(PostProcessTargets.OUTDOOR_VOLUME_COLOR),
                                    historyTexture, res.colorAttachment(PostProcessTargets.OUTDOOR_VOLUME_SAMPLE_DEPTH),
                                    historyDepthTexture, outdoorHistoryWeight, outdoorDepthReject,
                                    outdoorInverseViewProjection, previousOutdoorViewProjection,
                                    previousOutdoorCameraValid && outdoorHistory.valid()
                                            && outdoorDepthHistory.valid());
                            cmd.blitColor(target, outdoorHistory.framebuffer());
                        }
                    });
            graph.addPass(PostProcessTargets.OUTDOOR_VOLUME_DEPTH_HISTORY_PASS)
                    .createColor(PostProcessTargets.OUTDOOR_VOLUME_DEPTH_HISTORY_COLOR, RenderFormat.R16F)
                    .relativeSize(1.0f / outdoorDownsample).noClear()
                    .dependsOn(PostProcessTargets.OUTDOOR_VOLUME_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer target = res.currentTarget();
                        if (target != null && outdoorDepthHistoryPass != null) {
                            outdoorDepthHistoryPass.recordIntoCurrentTarget(cmd,
                                    res.colorAttachment(PostProcessTargets.OUTDOOR_VOLUME_SAMPLE_DEPTH));
                            cmd.blitColor(target, outdoorDepthHistory.framebuffer());
                        }
                    });
            graph.addPass(PostProcessTargets.OUTDOOR_VOLUME_UPSAMPLE_PASS)
                    .createColor(PostProcessTargets.OUTDOOR_VOLUME_UPSAMPLE_COLOR, RenderFormat.RGBA16F)
                    .noClear().dependsOn(volumeInputPass)
                    .dependsOn(PostProcessTargets.OUTDOOR_VOLUME_TEMPORAL_PASS)
                    .dependsOn(PostProcessTargets.OUTDOOR_VOLUME_DEPTH_HISTORY_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer volume = res.framebufferOfPass(PostProcessTargets.OUTDOOR_VOLUME_PASS);
                        if (volume != null) {
                            outdoorUpsample.recordIntoCurrentTarget(cmd,
                                    res.colorAttachment(volumeInputTexture),
                                    res.colorAttachment(PostProcessTargets.OUTDOOR_VOLUME_TEMPORAL_COLOR),
                                    res.depthAttachment(volumeDepthTexture),
                                    volume.width(), volume.height());
                        }
                    });
            hdrTexture = PostProcessTargets.OUTDOOR_VOLUME_UPSAMPLE_COLOR;
            hdrProducer = PostProcessTargets.OUTDOOR_VOLUME_UPSAMPLE_PASS;
        }

        if (hdrVfx != null) {
            final String baseTexture = hdrTexture;
            final String baseProducer = hdrProducer;
            RenderGraph.PassBuilder vfxComposite = graph.addPass(PostProcessTargets.VFX_COMPOSITE_PASS)
                    .createColor(PostProcessTargets.VFX_COMPOSITE_COLOR, RenderFormat.RGBA16F)
                    .createDepthTexture(PostProcessTargets.VFX_SCENE_DEPTH)
                    .noClear()
                    .dependsOn(baseProducer);
            if (!PostProcessTargets.GEOMETRY_PASS.equals(baseProducer)) {
                vfxComposite.dependsOn(PostProcessTargets.GEOMETRY_PASS);
            }
            vfxComposite.execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(baseProducer);
                        Framebuffer geometry = res.framebufferOfPass(PostProcessTargets.GEOMETRY_PASS);
                        Framebuffer target = res.currentTarget();
                        if (source != null && geometry != null && target != null) {
                            cmd.blitColor(source, target)
                                    .blitDepth(geometry, target)
                                    .bindFramebuffer(target)
                                    .viewport(0, 0, target.width(), target.height());
                            hdrVfx.execute(res, cmd);
                        }
                    });
            hdrTexture = PostProcessTargets.VFX_COMPOSITE_COLOR;
            hdrProducer = PostProcessTargets.VFX_COMPOSITE_PASS;
        }

        if (reactiveRequested) {
            final String reactiveProducer = hdrProducer;
            graph.addPass(PostProcessTargets.SCENE_REACTIVE_PASS)
                    .createColor(PostProcessTargets.SCENE_REACTIVE, RenderFormat.R8)
                    .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                    .dependsOn(reactiveProducer)
                    .execute((res, cmd) -> {
                        if (reactiveRecorder != null) reactiveRecorder.execute(res, cmd);
                    });
        }

        if (settings.antiAliasingMode() == AntiAliasingMode.TAA) {
            final String taaInput = hdrTexture;
            final String taaInputPass = hdrProducer;
            RenderGraph.PassBuilder taaPass = graph.addPass(PostProcessTargets.TAA_PASS)
                    .writeToExternalTarget()
                    .noClear()
                    .dependsOn(taaInputPass);
            if (reactiveRequested) {
                taaPass.dependsOn(PostProcessTargets.SCENE_REACTIVE_PASS);
            }
            taaPass.execute((res, cmd) -> recordTaaResolve(res, cmd, taaInput));
            hdrTexture = PostProcessTargets.TAA_RESOLVED_COLOR;
            hdrProducer = PostProcessTargets.TAA_PASS;
        }

        final String exposureInput = hdrTexture;
        final String exposureProducer = hdrProducer;
        ExposureOutput exposureOutput = settings.exposureMode() == ExposureMode.AUTO
                ? addAutoExposurePasses(graph, exposureInput, exposureProducer)
                : ExposureOutput.MANUAL;

        final String gradedToneInput = hdrTexture;
        BloomOutput bloomOutput = settings.bloomSettings().enabled()
                ? addBloomPasses(graph, hdrTexture, hdrProducer)
                : BloomOutput.DISABLED;
        String toneDependency = bloomOutput.enabled() ? bloomOutput.producerPass() : hdrProducer;
        RenderGraph.PassBuilder tonePass = graph.addPass(PostProcessTargets.TONE_MAPPING_PASS)
                .createColor(PostProcessTargets.TONE_MAPPED_COLOR, RenderFormat.RGBA8)
                .noClear()
                .dependsOn(toneDependency);
        if (exposureOutput.enabled()) {
            tonePass.dependsOn(exposureOutput.producerPass());
        }
        tonePass
                .execute((res, cmd) -> {
                    int bloomTexture = bloomOutput.enabled()
                            ? res.colorAttachment(bloomOutput.textureName()) : 0;
                    int exposureTexture = exposureOutput.enabled()
                            ? autoExposure.frameExposureTexture() : 0;
                    toneMapping.recordIntoCurrentTarget(cmd, res.colorAttachment(gradedToneInput), bloomTexture,
                            settings.exposure(), exposureTexture, settings.bloomSettings().intensity());
                });

        if (settings.antiAliasingMode() == AntiAliasingMode.FXAA) {
            graph.addPass(PostProcessTargets.FXAA_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.TONE_MAPPING_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(PostProcessTargets.TONE_MAPPING_PASS);
                        if (source != null) {
                            fxaa.recordIntoCurrentTarget(cmd,
                                    res.colorAttachment(PostProcessTargets.TONE_MAPPED_COLOR),
                                    source.width(), source.height());
                        }
                    });
            recordFinalPass(PostProcessTargets.FXAA_PASS);
        } else {
            graph.addPass(PostProcessTargets.PRESENT_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(PostProcessTargets.TONE_MAPPING_PASS)
                    .execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(PostProcessTargets.TONE_MAPPING_PASS);
                        PresentationTarget target = res.presentationTarget();
                        if (source != null && target != null) {
                            cmd.blitFramebuffer(source.id(), target.drawFramebufferId(),
                                    source.width(), source.height(),
                                    target.width(), target.height());
                        }
                    });
            recordFinalPass(PostProcessTargets.PRESENT_PASS);
        }
    }

    String finalPassName() {
        if (finalPassName == null) {
            throw new IllegalStateException("Final backbuffer pass has not been added");
        }
        return finalPassName;
    }

    static String finalPassNameFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(toneMappingMode, "toneMappingMode");
        return toneMappingMode != ToneMappingMode.NONE && mode == AntiAliasingMode.FXAA
                ? PostProcessTargets.FXAA_PASS : PostProcessTargets.PRESENT_PASS;
    }

    private void recordFinalPass(String passName) {
        if (finalPassName != null) {
            throw new IllegalStateException("Final backbuffer pass has already been recorded: "
                    + finalPassName);
        }
        finalPassName = Objects.requireNonNull(passName, "passName");
    }

    private BloomOutput addBloomPasses(RenderGraph graph, String sourceTexture, String sourcePass) {
        int levels = settings.bloomSettings().maxLevels();
        String previousTexture = sourceTexture;
        String previousPass = sourcePass;
        for (int level = 0; level < levels; level++) {
            final int currentLevel = level;
            final String inputTexture = previousTexture;
            final String inputPass = previousPass;
            String passName = PostProcessTargets.bloomDownPass(level);
            String outputTexture = PostProcessTargets.bloomDownColor(level);
            float scale = 0.5f / (1 << level);
            graph.addPass(passName)
                    .createColor(outputTexture, RenderFormat.RGBA16F)
                    .relativeSize(scale)
                    .noClear()
                    .dependsOn(inputPass)
                    .execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(inputPass);
                        if (source == null) {
                            return;
                        }
                        if (currentLevel == 0) {
                            bloom.recordExtract(cmd, res.colorAttachment(inputTexture),
                                    source.width(), source.height(),
                                    settings.bloomSettings().threshold(),
                                    settings.bloomSettings().softKnee());
                        } else {
                            bloom.recordDownsample(cmd, res.colorAttachment(inputTexture),
                                    source.width(), source.height());
                        }
                    });
            previousTexture = outputTexture;
            previousPass = passName;
        }

        for (int level = levels - 2; level >= 0; level--) {
            final String highTexture = PostProcessTargets.bloomDownColor(level);
            final String lowTexture = previousTexture;
            final String lowPass = previousPass;
            String passName = PostProcessTargets.bloomUpPass(level);
            String outputTexture = PostProcessTargets.bloomUpColor(level);
            float scale = 0.5f / (1 << level);
            graph.addPass(passName)
                    .createColor(outputTexture, RenderFormat.RGBA16F)
                    .relativeSize(scale)
                    .noClear()
                    .dependsOn(lowPass)
                    .execute((res, cmd) -> {
                        Framebuffer low = res.framebufferOfPass(lowPass);
                        if (low != null) {
                            bloom.recordUpsample(cmd, res.colorAttachment(highTexture),
                                    res.colorAttachment(lowTexture), low.width(), low.height());
                        }
                    });
            previousTexture = outputTexture;
            previousPass = passName;
        }
        return new BloomOutput(previousTexture, previousPass);
    }

    private ExposureOutput addAutoExposurePasses(RenderGraph graph, String sourceTexture, String sourcePass) {
        graph.addPass(PostProcessTargets.AUTO_EXPOSURE_LUMINANCE_PASS)
                .createColor(PostProcessTargets.AUTO_EXPOSURE_LUMINANCE, RenderFormat.R16F)
                .noClear()
                .dependsOn(sourcePass)
                .execute((res, cmd) -> {
                    Framebuffer source = res.framebufferOfPass(sourcePass);
                    if (source != null) {
                        autoExposure.recordLuminance(cmd, res.colorAttachment(sourceTexture),
                                source.width(), source.height());
                    }
                });

        String previousTexture = PostProcessTargets.AUTO_EXPOSURE_LUMINANCE;
        String previousPass = PostProcessTargets.AUTO_EXPOSURE_LUMINANCE_PASS;
        for (int level = 0; level < AUTO_EXPOSURE_REDUCTION_PASS_COUNT; level++) {
            final String inputTexture = previousTexture;
            final String inputPass = previousPass;
            final boolean inputHasWeights = level > 0;
            String passName = PostProcessTargets.autoExposureReducePass(level);
            String outputTexture = PostProcessTargets.autoExposureReduceColor(level);
            RenderGraph.PassBuilder reduction = graph.addPass(passName)
                    .createColor(outputTexture, RenderFormat.RG32F)
                    .noClear()
                    .dependsOn(inputPass);
            if (level < AUTO_EXPOSURE_RELATIVE_PASS_COUNT) {
                reduction.relativeSize(autoExposureRelativeScale(level));
            } else {
                reduction.fixedSize(1, 1);
            }
            reduction.execute((res, cmd) -> {
                Framebuffer input = res.framebufferOfPass(inputPass);
                Framebuffer output = res.currentTarget();
                if (input != null && output != null) {
                    autoExposure.recordReduction(cmd, res.colorAttachment(inputTexture),
                            input.width(), input.height(), output.width(), output.height(),
                            inputHasWeights);
                }
            });
            previousTexture = outputTexture;
            previousPass = passName;
        }

        final String averageTexture = previousTexture;
        final String averagePass = previousPass;
        graph.addPass(PostProcessTargets.AUTO_EXPOSURE_ADAPT_PASS)
                .writeToExternalTarget()
                .noClear()
                .dependsOn(averagePass)
                .execute((res, cmd) -> autoExposure.recordAdaptation(cmd,
                        res.colorAttachment(averageTexture), settings.exposure(),
                        settings.autoExposureSettings(), deltaSeconds));
        return new ExposureOutput(PostProcessTargets.AUTO_EXPOSURE_ADAPT_PASS);
    }

    void beginFrame(float frameDeltaSeconds, Camera camera, int width, int height,
                    int frameIndex, TemporalFrameState.FrameParameters currentFrame,
                    TemporalFrameState.FrameParameters previousFrame) {
        if (!Float.isFinite(frameDeltaSeconds) || frameDeltaSeconds < 0.0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        deltaSeconds = Math.min(frameDeltaSeconds, 0.1f);
        if (taaHistory != null) {
            taaHistory.prepareFrame();
        }
        if (currentFrame != null) {
            currentJitterUvX = currentFrame.jitterUvX();
            currentJitterUvY = currentFrame.jitterUvY();
            taaInverseProjection.set(currentFrame.inverseJitteredProjection());
            taaInverseView.set(currentFrame.view()).invert();
            if (!previousTaaFrameValid) {
                previousTaaStableViewProjection.set(currentFrame.stableViewProjection());
            }
        }
        if (previousFrame != null && previousTaaFrameValid) {
            previousJitterUvX = previousFrame.jitterUvX();
            previousJitterUvY = previousFrame.jitterUvY();
            previousTaaInverseProjection.set(previousFrame.inverseJitteredProjection());
            previousTaaStableViewProjection.set(previousFrame.stableViewProjection());
        }
        if (gtao != null) {
            gtao.beginFrame(camera, Math.max(1, width), Math.max(1, height), frameIndex,
                    settings.antiAliasingMode());
        }
        if (fog != null) {
            CameraProjection.stable(Objects.requireNonNull(camera, "camera"),
                    Math.max(1, width), Math.max(1, height), fogProjection);
            CameraUniforms.applyTemporalJitter(fogProjection, width, height,
                    settings.antiAliasingMode(), frameIndex);
            camera.getViewMatrix(fogView);
            fogInverseViewProjection.set(fogProjection).mul(fogView).invert();
            fogCameraPosition.set(camera.position());
        }
        if (outdoorVolume != null) {
            int outdoorWidth = Math.max(1, width);
            int outdoorHeight = Math.max(1, height);
            CameraProjection.stable(Objects.requireNonNull(camera, "camera"),
                    outdoorWidth, outdoorHeight, outdoorProjection);
            CameraUniforms.applyTemporalJitter(outdoorProjection, width, height,
                    settings.antiAliasingMode(), frameIndex);
            camera.getViewMatrix(outdoorView);
            pendingOutdoorPosition.set(camera.positionInternal());
            pendingOutdoorForward.set(outdoorView.m02(), outdoorView.m12(), outdoorView.m22()).normalize();
            if (previousOutdoorCameraValid && (pendingOutdoorPosition.distanceSquared(previousOutdoorPosition) > 16.0f
                    || pendingOutdoorForward.dot(previousOutdoorForward) < 0.8f)) {
                invalidateOutdoorHistory();
            }
            outdoorViewProjection.set(outdoorProjection).mul(outdoorView);
            if (!outdoorViewProjection.isFinite()
                    || Math.abs(outdoorViewProjection.determinant()) <= 1.0e-8f) {
                throw new IllegalArgumentException("outdoor camera view-projection must be invertible");
            }
            outdoorInverseViewProjection.set(outdoorViewProjection).invert();
            if (!outdoorInverseViewProjection.isFinite()) {
                throw new IllegalArgumentException("outdoor inverse camera matrix must be finite");
            }
            pendingOutdoorViewProjection.set(outdoorViewProjection);
        }
    }

    /**
     * Fallible pre-commit phase.  Runs before any temporal owner publishes
     * state so a failure cannot leave a partially advanced frame.
     */
    void prepareFrameSuccess() {
    }

    void frameSucceeded() {
        if (autoExposure != null) {
            autoExposure.commitFrame();
        }
        if (gtao != null) gtao.frameSucceeded();
        if (taaHistory != null) taaHistory.commitSuccessfulFrame();
        previousTaaFrameValid = true;
        if (outdoorHistory != null) outdoorHistory.markValid();
        if (outdoorDepthHistory != null) outdoorDepthHistory.markValid();
        if (outdoorVolume != null) {
            previousOutdoorViewProjection.set(pendingOutdoorViewProjection);
            previousOutdoorPosition.set(pendingOutdoorPosition);
            previousOutdoorForward.set(pendingOutdoorForward);
            previousOutdoorCameraValid = true;
        }
    }

    void frameFailed() {
        if (autoExposure != null) {
            autoExposure.discardFrame();
        }
        if (gtao != null) gtao.frameFailed();
        if (taaHistory != null) taaHistory.discardFrame();
        if (outdoorHistory != null) outdoorHistory.invalidate();
        if (outdoorDepthHistory != null) outdoorDepthHistory.invalidate();
        previousOutdoorCameraValid = false;
    }

    void invalidateGtaoHistory() {
        if (gtao != null) gtao.invalidateHistory();
    }

    void invalidateTemporalHistory() {
        if (taaHistory != null) taaHistory.invalidate();
        previousTaaFrameValid = false;
    }

    /** Test/diagnostic access to the TAA history owner. */
    TaaHistory taaHistoryForTest() {
        return taaHistory;
    }

    void invalidateOutdoorHistory() {
        if (outdoorHistory != null) outdoorHistory.invalidate();
        if (outdoorDepthHistory != null) outdoorDepthHistory.invalidate();
        previousOutdoorCameraValid = false;
    }

    void updateOutdoorSettings(VolumetricSunSettings settings) {
        outdoorHistoryWeight = settings.historyWeight();
        outdoorDepthReject = settings.depthRejectThreshold();
    }

    int outdoorDownsample() {
        return outdoorDownsample;
    }

    boolean outdoorHistoryValid() {
        return outdoorHistory != null && outdoorDepthHistory != null
                && outdoorHistory.valid() && outdoorDepthHistory.valid();
    }

    void recordGtaoDepthPrepassDraw() {
        if (gtao != null) gtao.recordDepthPrepassDraw();
    }

    Render3dDiagnostics.AmbientOcclusionSummary gtaoDiagnostics(int width, int height) {
        return gtao == null
                ? new Render3dDiagnostics.AmbientOcclusionSummary(false, "disabled",
                "disabled", 0.0f, 0.0f, 0.0f, width, height,
                Math.max(1, (width + 1) / 2), Math.max(1, (height + 1) / 2),
                false, false, 0, 0L)
                : gtao.diagnostics(width, height);
    }

    ResizeCandidate prepareResize(int width, int height) {
        TaaHistory.ResizeCandidate taaCandidate = taaHistory == null ? null
                : taaHistory.prepareResize(width, height);
        OutdoorHistory.ResizeCandidate outdoorHistoryCandidate = null;
        OutdoorHistory.ResizeCandidate outdoorDepthHistoryCandidate = null;
        try {
            if (outdoorHistory != null) {
                injectOutdoorHistoryAllocationFailureIfRequested();
                outdoorHistoryCandidate = outdoorHistory.prepareResize(divideCeil(width, outdoorDownsample),
                        divideCeil(height, outdoorDownsample));
                injectOutdoorDepthHistoryAllocationFailureIfRequested();
                outdoorDepthHistoryCandidate = outdoorDepthHistory.prepareResize(
                        divideCeil(width, outdoorDownsample), divideCeil(height, outdoorDownsample));
            }
            GtaoPasses.ResizeCandidate gtaoCandidate = gtao == null ? null
                    : gtao.prepareResize(width, height);
            return new ResizeCandidate(this, taaCandidate, gtaoCandidate, outdoorHistoryCandidate,
                    outdoorDepthHistoryCandidate);
        } catch (RuntimeException | Error failure) {
            if (taaCandidate != null) {
                try {
                    taaCandidate.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (outdoorHistoryCandidate != null) {
                try {
                    outdoorHistoryCandidate.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (outdoorDepthHistoryCandidate != null) {
                try {
                    outdoorDepthHistoryCandidate.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    void commitResize(ResizeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    void resize(int width, int height) {
        ResizeCandidate candidate = prepareResize(width, height);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    static final class ResizeCandidate implements AutoCloseable {
        private final PostProcessPassBuilder owner;
        private final TaaHistory.ResizeCandidate taaCandidate;
        private final GtaoPasses.ResizeCandidate gtaoCandidate;
        private final OutdoorHistory.ResizeCandidate outdoorHistoryCandidate;
        private final OutdoorHistory.ResizeCandidate outdoorDepthHistoryCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PostProcessPassBuilder owner,
                                TaaHistory.ResizeCandidate taaCandidate,
                                GtaoPasses.ResizeCandidate gtaoCandidate,
                                OutdoorHistory.ResizeCandidate outdoorHistoryCandidate,
                                OutdoorHistory.ResizeCandidate outdoorDepthHistoryCandidate) {
            this.owner = owner;
            this.taaCandidate = taaCandidate;
            this.gtaoCandidate = gtaoCandidate;
            this.outdoorHistoryCandidate = outdoorHistoryCandidate;
            this.outdoorDepthHistoryCandidate = outdoorDepthHistoryCandidate;
        }

        void validateFor(PostProcessPassBuilder expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("post-process resize candidate belongs to another builder");
            }
            if (closed) throw new IllegalStateException("post-process resize candidate is closed");
            if (committed) throw new IllegalStateException("post-process resize candidate already committed");
        }

        private void commitInto(PostProcessPassBuilder expectedOwner) {
            validateFor(expectedOwner);
            if (taaCandidate != null) owner.taaHistory.commitResize(taaCandidate);
            if (gtaoCandidate != null) owner.gtao.commitResize(gtaoCandidate);
            if (outdoorHistoryCandidate != null) owner.outdoorHistory.commitResize(outdoorHistoryCandidate);
            if (outdoorDepthHistoryCandidate != null) {
                owner.outdoorDepthHistory.commitResize(outdoorDepthHistoryCandidate);
            }
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            if (gtaoCandidate != null) {
                try {
                    gtaoCandidate.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                }
            }
            if (taaCandidate != null) {
                try {
                    taaCandidate.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (outdoorHistoryCandidate != null) {
                try {
                    outdoorHistoryCandidate.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (outdoorDepthHistoryCandidate != null) {
                try {
                    outdoorDepthHistoryCandidate.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        failure = closeCollecting(fog, failure);
        failure = closeCollecting(gtao, failure);
        failure = closeCollecting(autoExposure, failure);
        failure = closeCollecting(bloom, failure);
        failure = closeCollecting(toneMapping, failure);
        failure = closeCollecting(taaHistory, failure);
        failure = closeCollecting(taa, failure);
        failure = closeCollecting(fxaa, failure);
        failure = closeCollecting(outdoorUpsample, failure);
        failure = closeCollecting(outdoorTemporal, failure);
        failure = closeCollecting(outdoorDepthHistoryPass, failure);
        failure = closeCollecting(outdoorHistory, failure);
        failure = closeCollecting(outdoorDepthHistory, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static List<String> ldrPassNames(AntiAliasingMode mode) {
        return switch (mode) {
            case NONE, MSAA -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.PRESENT_PASS);
            case FXAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.FXAA_PASS,
                    PostProcessTargets.PRESENT_PASS);
            case TAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS,
                    PostProcessTargets.PRESENT_PASS);
        };
    }

    private static int divideCeil(int value, int divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }

    private static List<String> hdrPassNames(AntiAliasingMode mode) {
        return switch (mode) {
            case NONE -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS);
            case MSAA -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.HDR_RESOLVE_PASS, PostProcessTargets.TONE_MAPPING_PASS,
                    PostProcessTargets.PRESENT_PASS);
            case FXAA -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.FXAA_PASS);
            case TAA -> List.of(PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.TAA_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS, PostProcessTargets.PRESENT_PASS);
        };
    }

    private static List<String> bloomPassNames(int levels) {
        List<String> names = new ArrayList<>(levels * 2 - 1);
        for (int level = 0; level < levels; level++) {
            names.add(PostProcessTargets.bloomDownPass(level));
        }
        for (int level = levels - 2; level >= 0; level--) {
            names.add(PostProcessTargets.bloomUpPass(level));
        }
        return names;
    }

    private record BloomOutput(String textureName, String producerPass) {
        private static final BloomOutput DISABLED = new BloomOutput(null, null);

        boolean enabled() {
            return textureName != null;
        }
    }

    private record ExposureOutput(String producerPass) {
        private static final ExposureOutput MANUAL = new ExposureOutput(null);

        boolean enabled() {
            return producerPass != null;
        }
    }

    static float autoExposureRelativeScale(int level) {
        if (level < 0 || level >= AUTO_EXPOSURE_RELATIVE_PASS_COUNT) {
            throw new IllegalArgumentException("relative reduction level must be in [0, 12]");
        }
        return (float) Math.scalb(1.0, -(level + 1));
    }

    static RenderFormat taaHistoryFormat(RenderSettings settings) {
        return settings.hdrEnabled() ? RenderFormat.RGBA16F : RenderFormat.SRGB8_ALPHA8;
    }

    /** One-shot GL test hook used to prove generation-wide resize rollback. */
    private static void injectOutdoorHistoryAllocationFailureIfRequested() {
        String requested = System.getProperty("haikalat.test.failOutdoorHistoryAllocation", "");
        if (requested.equalsIgnoreCase("true") || requested.equalsIgnoreCase("color")) {
            System.clearProperty("haikalat.test.failOutdoorHistoryAllocation");
            throw new IllegalStateException("injected outdoor history allocation failure");
        }
    }

    private static void injectOutdoorDepthHistoryAllocationFailureIfRequested() {
        String requested = System.getProperty("haikalat.test.failOutdoorHistoryAllocation", "");
        if (requested.equalsIgnoreCase("depth")) {
            System.clearProperty("haikalat.test.failOutdoorHistoryAllocation");
            throw new IllegalStateException("injected outdoor depth history allocation failure");
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
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
                        ? runtime : new IllegalStateException("Failed to close postprocess resource", cleanupFailure);
            }
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }
}
