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
    private final RenderGraph.PassExecutor hdrVfx;
    private final SceneSurfaceInputs surfaceInputs;
    private final RenderGraph.PassExecutor reactiveRecorder;
    private final TaaSettings taaSettings = TaaSettings.defaults();
    private float manualExposure;
    private BloomSettings bloomSettings;
    private float currentJitterUvX;
    private float currentJitterUvY;
    private float previousJitterUvX;
    private float previousJitterUvY;
    private org.joml.Matrix4f taaInverseProjection = new org.joml.Matrix4f();
    private org.joml.Matrix4f previousTaaInverseProjection = new org.joml.Matrix4f();
    private final org.joml.Matrix4f previousTaaStableViewProjection = new org.joml.Matrix4f();
    private final org.joml.Matrix4f taaInverseView = new org.joml.Matrix4f();
    private boolean previousTaaFrameValid;
    enum TaaInvalidationReason { NONE, FIRST_FRAME, VOLUMETRIC_SETTINGS, VOLUMETRIC_TOPOLOGY, RESIZE, EXPLICIT_RESET, GTAO_SETTINGS, GTAO_TOPOLOGY }
    private TaaInvalidationReason pendingTaaInvalidation = TaaInvalidationReason.FIRST_FRAME;
    private TaaInvalidationReason completedTaaInvalidation = TaaInvalidationReason.NONE;
    private final Matrix4f fogInverseViewProjection = new Matrix4f();
    private final Matrix4f fogProjection = new Matrix4f();
    private final Matrix4f fogView = new Matrix4f();
    private final Vector3f fogCameraPosition = new Vector3f();
    private float deltaSeconds = 1.0f / 60.0f;
    private String finalPassName;
    private String linearHdrTextureName;
    private String sceneColorInput=PostProcessTargets.SCENE_COLOR;
    private String sceneColorProducer=PostProcessTargets.GEOMETRY_PASS;
    private boolean ownsResources = true;

    /** Independent assembly state over borrowed GPU owners; preparation never mutates active callbacks. */
    PostProcessPassBuilder replacementAssembly(RenderSettings replacement, PostProcessSettings replacementEffects, SceneSurfaceInputs surface,
                                                RenderGraph.PassExecutor vfx, RenderGraph.PassExecutor recorder) {
        var candidate = new PostProcessPassBuilder(replacement, replacementEffects, window, fxaa, taa, taaHistory,
                toneMapping, bloom, autoExposure, fog, vfx, surface, recorder);
        candidate.ownsResources = false;
        return candidate;
    }

    void validateTransferTo(PostProcessPassBuilder candidate) {
        if (!ownsResources || candidate.ownsResources || candidate.taaHistory != taaHistory
                || candidate.toneMapping != toneMapping
                || candidate.fxaa != fxaa || candidate.taa != taa || candidate.bloom != bloom
                || candidate.autoExposure != autoExposure || candidate.fog != fog)
            throw new IllegalStateException("invalid postprocess ownership transfer");
    }

    void transferResourcesTo(PostProcessPassBuilder candidate) {
        validateTransferTo(candidate);
        ownsResources = false;
        candidate.ownsResources = true;
    }

    void sceneColorInput(String texture,String producer) {
        sceneColorInput=Objects.requireNonNull(texture); sceneColorProducer=Objects.requireNonNull(producer);
    }

    private PostProcessPassBuilder(RenderSettings settings, PostProcessSettings effects,
                                   RenderWindow window,
                                   FxaaPostProcessor fxaa,
                                   TemporalAccumulationPass taa,
                                   TaaHistory taaHistory,
                                   ToneMappingPass toneMapping,
                                   BloomPass bloom,
                                   AutoExposurePass autoExposure,
                                   FogPass fog, RenderGraph.PassExecutor hdrVfx,
                                   SceneSurfaceInputs surfaceInputs,
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
        this.hdrVfx = hdrVfx;
        this.surfaceInputs = surfaceInputs;
        this.reactiveRecorder = reactiveRecorder;
        manualExposure = settings.exposure();
        bloomSettings = settings.bloomSettings();
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height) {
        return create(settings, effects, window, width, height, null, null, null);
    }

    static PostProcessPassBuilder create(RenderSettings settings, PostProcessSettings effects,
                                         RenderWindow window, int width, int height,
                                         RenderGraph.PassExecutor hdrVfx,
                                         SceneSurfaceInputs surfaceInputs,
                                         RenderGraph.PassExecutor reactiveRecorder) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(window, "window");
        FxaaPostProcessor fxaa = null;
        TemporalAccumulationPass taa = null;
        TaaHistory history = null;
        ToneMappingPass toneMapping = null;
        BloomPass bloom = null;
        AutoExposurePass autoExposure = null;
        FogPass fog = null;
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
            return new PostProcessPassBuilder(settings, effects, window, fxaa, taa, history, toneMapping,
                    bloom, autoExposure, fog, hdrVfx, surfaceInputs, reactiveRecorder);
        } catch (RuntimeException failure) {
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

    private boolean reactiveRequested() {
        return surfaceInputs != null && surfaceInputs.layout().reactive() != null;
    }

    private void addReactivePass(RenderGraph graph, String colorProducer) {
        var output = surfaceInputs.single(SceneBufferChannel.REACTIVE);
        var target = graph.addPass(output.producer()).dependsOn(colorProducer);
        if (surfaceInputs.layout().reactiveMode() == SceneSurfaceLayout.ReactiveMode.VOLUME_ALIAS) {
            target.shareColorAttachments(List.of(output.name()), List.of(output.format()),
                    VolumetricPassBuilder.COMPOSITE_PASS, List.of(1), 1).noClear();
        } else {
            target.createColor(output.name(), output.format()).clearColor(0, 0, 0, 0);
        }
        var recorder = Objects.requireNonNull(reactiveRecorder, "missing reactive surface recorder");
        target.execute((res, cmd) -> {
            var frame = surfaceInputs.frame();
            recorder.execute(res, cmd);
            frame.reactiveProduced(res);
        });
    }

    private void addTaaPass(RenderGraph graph, String inputTexture, String colorProducer) {
        var input = Objects.requireNonNull(surfaceInputs, "missing TAA surface inputs");
        var taaPass = graph.addPass(PostProcessTargets.TAA_PASS).writeToExternalTarget().noClear()
                .dependsOn(colorProducer);
        var producers = new java.util.LinkedHashSet<String>();
        producers.add(colorProducer);
        for (var channel : SceneBufferRequirements.taa(reactiveRequested()).channels()) {
            String producer = input.single(channel).producer();
            if (producers.add(producer)) taaPass.dependsOn(producer);
        }
        taaPass.execute((res, cmd) -> recordTaaResolve(res, cmd, inputTexture));
    }

    void validateSurfaceInputs() {
        if (taa != null) {
            for (var channel : SceneBufferRequirements.taa(reactiveRequested()).channels()) {
                surfaceInputs.validateDependency(PostProcessTargets.TAA_PASS, surfaceInputs.single(channel));
            }
        }
        if (fog != null) {
            surfaceInputs.validateDependency(PostProcessTargets.FOG_PASS, surfaceInputs.single(SceneBufferChannel.DEPTH));
        }
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
                if (reactiveRequested()) addReactivePass(graph, PostProcessTargets.GEOMETRY_PASS);
                addTaaPass(graph, PostProcessTargets.SCENE_COLOR, PostProcessTargets.GEOMETRY_PASS);
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
        var surface = surfaceInputs.frame();
        TaaResolveInputs inputs = new TaaResolveInputs(
                res.colorAttachment(inputTexture),
                historyValid ? taaHistory.readColorTexture() : 0,
                historyValid ? taaHistory.readDepthTexture() : 0,
                surface.require(res, surfaceInputs.single(SceneBufferChannel.DEPTH)),
                surface.require(res, surfaceInputs.single(SceneBufferChannel.VELOCITY)),
                surface.require(res, surfaceInputs.single(SceneBufferChannel.PREVIOUS_SURFACE_DEPTH)),
                surface.require(res, surfaceInputs.single(SceneBufferChannel.VALIDITY)),
                reactiveRequested() ? surface.require(res, surfaceInputs.single(SceneBufferChannel.REACTIVE)) : 0,
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
        String hdrTexture = sceneColorInput;
        String hdrProducer = sceneColorProducer;
        final String resolveProducer=hdrProducer;

        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            graph.addPass(PostProcessTargets.HDR_RESOLVE_PASS)
                    .createColor(PostProcessTargets.HDR_RESOLVED_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(resolveProducer)
                    .execute((res, cmd) -> {
                        Framebuffer geometry = res.framebufferOfPass(resolveProducer);
                        Framebuffer target = res.currentTarget();
                        if (geometry != null && target != null) {
                            cmd.blitColor(geometry, target);
                        }
                    });
            hdrTexture = PostProcessTargets.HDR_RESOLVED_COLOR;
            hdrProducer = PostProcessTargets.HDR_RESOLVE_PASS;
        }

        if (effects.fog().enabled()) {
            final String fogInputTexture = hdrTexture;
            final String fogInputPass = hdrProducer;
            var depth = surfaceInputs.single(SceneBufferChannel.DEPTH);
            graph.addPass(PostProcessTargets.FOG_PASS)
                    .createColor(PostProcessTargets.FOG_COLOR, RenderFormat.RGBA16F)
                    .noClear().dependsOn(fogInputPass).dependsOn(depth.producer())
                    .execute((res, cmd) -> fog.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(fogInputTexture),
                            surfaceInputs.frame().require(res, depth),
                            fogInverseViewProjection, fogCameraPosition, effects.fog()));
            hdrTexture = PostProcessTargets.FOG_COLOR;
            hdrProducer = PostProcessTargets.FOG_PASS;
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

        if (reactiveRequested()) addReactivePass(graph, hdrProducer);

        if (settings.antiAliasingMode() == AntiAliasingMode.TAA) {
            addTaaPass(graph, hdrTexture, hdrProducer);
            hdrTexture = PostProcessTargets.TAA_RESOLVED_COLOR;
            hdrProducer = PostProcessTargets.TAA_PASS;
        }

        final String exposureInput = hdrTexture;
        final String exposureProducer = hdrProducer;
        ExposureOutput exposureOutput = settings.exposureMode() == ExposureMode.AUTO
                ? addAutoExposurePasses(graph, exposureInput, exposureProducer)
                : ExposureOutput.MANUAL;

        final String gradedToneInput = hdrTexture;
        linearHdrTextureName = gradedToneInput;
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
                            manualExposure, exposureTexture, bloomSettings.intensity());
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

    String linearHdrTextureName() {
        if (linearHdrTextureName == null) {
            throw new IllegalStateException("postprocess HDR source is unavailable before graph build");
        }
        return linearHdrTextureName;
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
                        if (source == null && !PostProcessTargets.TAA_PASS.equals(inputPass)) {
                            throw new IllegalStateException("Bloom source pass has no color target: "
                                    + inputPass);
                        }
                        int sourceWidth = source == null ? graph.width() : source.width();
                        int sourceHeight = source == null ? graph.height() : source.height();
                        int sourceTextureId = res.colorAttachment(inputTexture);
                        if (sourceTextureId == 0) {
                            throw new IllegalStateException("Bloom source texture is unavailable: "
                                    + inputTexture);
                        }
                        if (currentLevel == 0) {
                            bloom.recordExtract(cmd, sourceTextureId,
                                    sourceWidth, sourceHeight,
                                    bloomSettings.threshold(), bloomSettings.softKnee());
                        } else {
                            bloom.recordDownsample(cmd, sourceTextureId,
                                    sourceWidth, sourceHeight);
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
                        res.colorAttachment(averageTexture), manualExposure,
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
        if (fog != null) {
            CameraProjection.stable(Objects.requireNonNull(camera, "camera"),
                    Math.max(1, width), Math.max(1, height), fogProjection);
            CameraUniforms.applyTemporalJitter(fogProjection, width, height,
                    settings.antiAliasingMode(), frameIndex);
            camera.getViewMatrix(fogView);
            fogInverseViewProjection.set(fogProjection).mul(fogView).invert();
            fogCameraPosition.set(camera.position());
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
        if (taaHistory != null) taaHistory.commitSuccessfulFrame();
        completedTaaInvalidation = taaHistory == null ? TaaInvalidationReason.NONE : pendingTaaInvalidation;
        pendingTaaInvalidation = TaaInvalidationReason.NONE;
        previousTaaFrameValid = true;
    }

    void frameFailed() {
        if (autoExposure != null) {
            autoExposure.discardFrame();
        }
        if (taaHistory != null) taaHistory.discardFrame();
    }



    void invalidateTemporalHistory() {
        invalidateTemporalHistory(TaaInvalidationReason.EXPLICIT_RESET);
    }

    void invalidateTemporalHistory(TaaInvalidationReason reason) {
        if (taaHistory != null) taaHistory.invalidate();
        pendingTaaInvalidation = Objects.requireNonNull(reason, "reason");
        previousTaaFrameValid = false;
    }

    String completedTaaInvalidationReason() {
        return completedTaaInvalidation == TaaInvalidationReason.NONE ? "" : "TAA_" + completedTaaInvalidation.name();
    }

    /** Test/diagnostic access to the TAA history owner. */
    TaaHistory taaHistoryForTest() {
        return taaHistory;
    }

    void updateVisualScalars(float exposure, BloomSettings bloom) {
        if (!Float.isFinite(exposure) || exposure <= 0.0f) {
            throw new IllegalArgumentException("manual exposure must be finite and positive");
        }
        BloomSettings requiredBloom = Objects.requireNonNull(bloom, "bloom");
        if (requiredBloom.enabled() != (this.bloom != null)
                || requiredBloom.enabled()
                && requiredBloom.maxLevels() != bloomSettings.maxLevels()) {
            throw new IllegalArgumentException("bloom topology differs from the active generation");
        }
        manualExposure = exposure;
        bloomSettings = requiredBloom;
    }





    ResizeCandidate prepareResize(int width, int height) {
        return new ResizeCandidate(this, taaHistory == null ? null : taaHistory.prepareResize(width, height));
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
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PostProcessPassBuilder owner,
                                TaaHistory.ResizeCandidate taaCandidate) {
            this.owner = owner;
            this.taaCandidate = taaCandidate;
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
            if (taaCandidate != null) {
                owner.taaHistory.commitResize(taaCandidate);
                owner.pendingTaaInvalidation = TaaInvalidationReason.RESIZE;
            }
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            if (taaCandidate != null) {
                try {
                    taaCandidate.close();
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
        if (!ownsResources) return;
        ownsResources = false;
        RuntimeException failure = null;
        failure = closeCollecting(fog, failure);
        failure = closeCollecting(autoExposure, failure);
        failure = closeCollecting(bloom, failure);
        failure = closeCollecting(toneMapping, failure);
        failure = closeCollecting(taaHistory, failure);
        failure = closeCollecting(taa, failure);
        failure = closeCollecting(fxaa, failure);
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
