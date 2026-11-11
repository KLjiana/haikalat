package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.bloom.BloomChain;
import com.kaleblangley.haikalat.subsystems.postprocess.exposure.AutoExposureChain;
import com.kaleblangley.haikalat.subsystems.postprocess.fog.FogChain;
import com.kaleblangley.haikalat.subsystems.postprocess.fxaa.FxaaChain;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaHistory;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaInvalidationReason;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalChain;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;
import com.kaleblangley.haikalat.subsystems.postprocess.tonemapping.ToneMappingChain;
import com.kaleblangley.haikalat.subsystems.postprocess.vignette.VignettePass;
import com.kaleblangley.haikalat.subsystems.postprocess.film.FilmGrainPass;
import com.kaleblangley.haikalat.subsystems.postprocess.chromatic.ChromaticAberrationPass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Colour-chain assembly for the post-process subsystem.
 *
 * <p>This class owns the frame ordering, exposure/bloom/tone-mapping wiring,
 * AA presentation and the temporal transaction boundary.  It owns no Render3D
 * type: surface textures arrive through {@link SurfaceInputs}, the late
 * reactive pass through {@link LateSurfaceProducer}, and each effect owner is
 * a separate class whose private resources survive unrelated colour-chain
 * changes.</p>
 */
public final class PostProcessPipeline implements AutoCloseable {
    private final RenderSettings settings;
    private final PostProcessSettings effects;
    private final FxaaChain fxaa;
    private final TemporalChain temporal;
    private final ToneMappingChain toneMapping;
    private final BloomChain bloom;
    private final AutoExposureChain autoExposure;
    private final FogChain fog;
    private final VignettePass vignette;
    private final FilmGrainPass filmGrain;
    private final ChromaticAberrationPass chromaticAberration;
    private final RenderGraph.PassExecutor hdrVfx;
    private final SurfaceInputs surfaceInputs;
    private final LateSurfaceProducer lateSurface;
    private float manualExposure;
    private float deltaSeconds = 1.0f / 60.0f;
    private String finalPassName;
    private String linearHdrTextureName;
    private String sceneColorInput = PostProcessTargets.SCENE_COLOR;
    private String sceneColorProducer = PostProcessTargets.GEOMETRY_PASS;
    private final List<AutoCloseable> owned;
    private final List<AutoCloseable> borrowed;
    private PostProcessPipeline donor;
    private boolean closed;

    private PostProcessPipeline(RenderSettings settings, PostProcessSettings effects,
                                FxaaChain fxaa, TemporalChain temporal,
                                ToneMappingChain toneMapping, BloomChain bloom,
                                AutoExposureChain autoExposure, FogChain fog,
                                VignettePass vignette,
                                FilmGrainPass filmGrain,
                                ChromaticAberrationPass chromaticAberration,
                                RenderGraph.PassExecutor hdrVfx,
                                SurfaceInputs surfaceInputs,
                                LateSurfaceProducer lateSurface,
                                List<AutoCloseable> owned, List<AutoCloseable> borrowed,
                                PostProcessPipeline donor) {
        this.settings = settings;
        this.effects = effects;
        this.fxaa = fxaa;
        this.temporal = temporal;
        this.toneMapping = toneMapping;
        this.bloom = bloom;
        this.autoExposure = autoExposure;
        this.fog = fog;
        this.vignette = vignette;
        this.filmGrain = filmGrain;
        this.chromaticAberration = chromaticAberration;
        this.hdrVfx = hdrVfx;
        this.surfaceInputs = surfaceInputs;
        this.lateSurface = lateSurface;
        this.owned = owned;
        this.borrowed = borrowed;
        this.donor = donor;
        manualExposure = settings.exposure();
    }

    /**
     * Creates the colour chain and its effect owners.
     *
     * <p>A disabled effect allocates no owner and contributes no pass.  On a
     * construction failure every already-created owner is closed before the
     * exception escapes, matching the candidate-first publication rules.</p>
     */
    public static PostProcessPipeline create(RenderSettings settings, PostProcessSettings effects,
                                             int width, int height,
                                             RenderGraph.PassExecutor hdrVfx,
                                             SurfaceInputs surfaceInputs,
                                             LateSurfaceProducer lateSurface) {
        return assemble(settings, effects, width, height, hdrVfx, surfaceInputs, lateSurface, null);
    }

    private static PostProcessPipeline assemble(RenderSettings settings, PostProcessSettings effects,
                                                int width, int height, RenderGraph.PassExecutor hdrVfx,
                                                SurfaceInputs surfaceInputs, LateSurfaceProducer lateSurface,
                                                PostProcessPipeline donor) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(surfaceInputs, "surfaceInputs");
        var owned = new ArrayList<AutoCloseable>();
        var borrowed = new ArrayList<AutoCloseable>();
        try {
            var fxaa = settings.antiAliasingMode() == AntiAliasingMode.FXAA
                    ? reuseOrCreate(donor == null ? null : donor.fxaa, FxaaChain::new, owned, borrowed) : null;
            var format = TemporalChain.historyFormat(settings.hdrEnabled());
            var temporal = settings.antiAliasingMode() == AntiAliasingMode.TAA
                    ? reuseOrCreate(donor != null && donor.temporal != null
                                    && donor.temporal.compatibleStorage(width, height, format) ? donor.temporal : null,
                            () -> new TemporalChain(width, height, format), owned, borrowed) : null;
            var toneMapping = settings.hdrEnabled()
                    ? reuseOrCreate(donor != null && effects.colorGrading().equals(donor.effects.colorGrading())
                                    ? donor.toneMapping : null,
                            () -> new ToneMappingChain(effects.colorGrading()), owned, borrowed) : null;
            var bloomSettings = settings.bloomSettings();
            var bloom = bloomSettings.enabled()
                    ? reuseOrCreate(donor != null && donor.bloom != null && donor.bloom.levels() == bloomSettings.maxLevels()
                                    ? donor.bloom : null,
                            () -> BloomChain.create(bloomSettings), owned, borrowed) : null;
            var autoExposure = settings.exposureMode() == ExposureMode.AUTO
                    ? reuseOrCreate(donor == null ? null : donor.autoExposure, AutoExposureChain::new, owned, borrowed) : null;
            var fog = effects.fog().enabled()
                    ? reuseOrCreate(donor == null ? null : donor.fog, FogChain::new, owned, borrowed) : null;
            if ((effects.vignette().active() || effects.filmGrain().active()
                    || effects.chromaticAberration().active()) && !settings.hdrEnabled()) {
                throw new IllegalArgumentException("screen effects require HDR tone mapping");
            }
            VignettePass vignette = effects.vignette().active()
                    ? reuseOrCreate(donor != null && donor.vignette != null
                                    && donor.vignette.settings().equals(effects.vignette())
                                    ? donor.vignette : null,
                            () -> new VignettePass(effects.vignette()), owned, borrowed) : null;
            FilmGrainPass filmGrain = effects.filmGrain().active()
                    ? reuseOrCreate(donor != null && donor.filmGrain != null
                                    && donor.filmGrain.settings().equals(effects.filmGrain())
                                    ? donor.filmGrain : null,
                            () -> new FilmGrainPass(effects.filmGrain()), owned, borrowed) : null;
            ChromaticAberrationPass chromaticAberration = effects.chromaticAberration().active()
                    ? reuseOrCreate(donor != null && donor.chromaticAberration != null
                                    && donor.chromaticAberration.settings()
                                    .equals(effects.chromaticAberration())
                                    ? donor.chromaticAberration : null,
                            () -> new ChromaticAberrationPass(effects.chromaticAberration()),
                            owned, borrowed) : null;
            return new PostProcessPipeline(settings, effects, fxaa, temporal, toneMapping, bloom,
                    autoExposure, fog, vignette, filmGrain, chromaticAberration,
                    hdrVfx, surfaceInputs, lateSurface, owned, borrowed, donor);
        } catch (RuntimeException | Error failure) {
            for (var resource : owned.reversed()) closeAfterFailure(resource, failure);
            throw failure;
        }
    }

    private static <T extends AutoCloseable> T reuseOrCreate(T previous, Supplier<T> create,
                                                            List<AutoCloseable> owned, List<AutoCloseable> borrowed) {
        if (previous != null) {
            borrowed.add(previous);
            return previous;
        }
        T resource = create.get();
        owned.add(resource);
        return resource;
    }

    /** A candidate owns new effects and borrows only compatible effects from this generation. */
    public PostProcessPipeline replacementAssembly(RenderSettings replacement,
                                                   PostProcessSettings replacementEffects,
                                                   int width, int height,
                                                   SurfaceInputs replacementSurface,
                                                   RenderGraph.PassExecutor vfx,
                                                   LateSurfaceProducer replacementLateSurface) {
        if (closed || donor != null) throw new IllegalStateException("invalid postprocess donor");
        return assemble(replacement, replacementEffects, width, height, vfx,
                replacementSurface, replacementLateSurface, this);
    }

    public void validateTransferTo(PostProcessPipeline candidate) {
        if (closed || candidate.closed || candidate.donor != this || !owned.containsAll(candidate.borrowed)) {
            throw new IllegalStateException("invalid postprocess ownership transfer");
        }
    }

    public void transferResourcesTo(PostProcessPipeline candidate) {
        validateTransferTo(candidate);
        owned.removeAll(candidate.borrowed);
        candidate.owned.addAll(candidate.borrowed);
        candidate.borrowed.clear();
        candidate.donor = null;
        if (candidate.bloom != null) candidate.bloom.updateSettings(candidate.settings.bloomSettings());
    }

    public void sceneColorInput(String texture, String producer) {
        sceneColorInput = Objects.requireNonNull(texture);
        sceneColorProducer = Objects.requireNonNull(producer);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode) {
        return passNamesFor(mode, ToneMappingMode.NONE);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(toneMappingMode, "toneMappingMode");
        return toneMappingMode == ToneMappingMode.NONE ? ldrPassNames(mode) : hdrPassNames(mode);
    }

    public static List<String> passNamesFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode,
                                            BloomSettings bloomSettings) {
        Objects.requireNonNull(bloomSettings, "bloomSettings");
        List<String> passes = new ArrayList<>(passNamesFor(mode, toneMappingMode));
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

    public static String finalPassNameFor(AntiAliasingMode mode, ToneMappingMode toneMappingMode) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(toneMappingMode, "toneMappingMode");
        return toneMappingMode != ToneMappingMode.NONE && mode == AntiAliasingMode.FXAA
                ? PostProcessTargets.FXAA_PASS : PostProcessTargets.PRESENT_PASS;
    }

    private boolean reactiveRequested() {
        return lateSurface != null && lateSurface.enabled();
    }

    private void addReactivePass(RenderGraph graph, String colorProducer) {
        lateSurface.declare(graph, colorProducer);
    }

    public void addFinalPass(RenderGraph graph) {
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

    /** Validates the dependency edges that the colour chain declares on surface producers. */
    public void validateSurfaceInputs() {
        if (temporal != null) {
            temporal.validateDependencies(surfaceInputs, reactiveRequested());
        }
        if (fog != null) {
            surfaceInputs.validateDependency(PostProcessTargets.FOG_PASS,
                    requireInput(SurfaceInputs.Channel.DEPTH));
        }
    }

    private SurfaceInputs.Input requireInput(SurfaceInputs.Channel channel) {
        SurfaceInputs.Input input = surfaceInputs.single(channel);
        if (input == null) {
            throw new IllegalStateException("missing " + channel + " surface input for the colour chain");
        }
        return input;
    }

    void addTaaPass(RenderGraph graph, String inputTexture, String colorProducer) {
        temporal.addPass(graph, colorProducer, inputTexture, surfaceInputs, reactiveRequested());
    }

    private void addLdrFinalPass(RenderGraph graph) {
        switch (settings.antiAliasingMode()) {
            case FXAA -> {
                fxaa.addLdrPass(graph, PostProcessTargets.GEOMETRY_PASS, PostProcessTargets.SCENE_COLOR);
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
                    if (target != null && temporal != null) {
                        Framebuffer candidate = temporal.writeFramebuffer();
                        cmd.blitFramebuffer(candidate.id(), target.drawFramebufferId(),
                                candidate.width(), candidate.height(),
                                target.width(), target.height());
                    }
                });
        recordFinalPass(PostProcessTargets.PRESENT_PASS);
    }

    private void addHdrFinalPasses(RenderGraph graph) {
        String hdrTexture = sceneColorInput;
        String hdrProducer = sceneColorProducer;

        if (settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            final String resolveProducer = hdrProducer;
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

        if (fog != null) {
            final String fogInputTexture = hdrTexture;
            final String fogInputPass = hdrProducer;
            fog.addPass(graph, fogInputTexture, fogInputPass, surfaceInputs,
                    requireInput(SurfaceInputs.Channel.DEPTH), () -> framePose, effects.fog());
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
        AutoExposureChain.Output exposureOutput = autoExposure != null
                ? autoExposure.addPasses(graph, exposureInput, exposureProducer,
                        manualExposure, settings.autoExposureSettings(), deltaSeconds)
                : null;

        final String gradedToneInput = hdrTexture;
        linearHdrTextureName = gradedToneInput;
        BloomChain.Output bloomOutput = bloom != null
                ? bloom.addPasses(graph, hdrTexture, hdrProducer)
                : null;
        String bloomProducer = bloomOutput == null ? null : bloomOutput.producerPass();
        String bloomTexture = bloomOutput == null ? null : bloomOutput.textureName();
        toneMapping.addPass(graph, gradedToneInput, hdrProducer,
                bloomTexture, bloomProducer, manualExposure,
                autoExposure == null ? null : autoExposure::frameExposureTexture,
                exposureOutput == null ? null : exposureOutput.producerPass(),
                bloom == null ? 0.0f : bloom.intensity());

        String screenTexture = PostProcessTargets.TONE_MAPPED_COLOR;
        String screenProducer = PostProcessTargets.TONE_MAPPING_PASS;
        if (vignette != null) {
            final String input = screenTexture;
            graph.addPass(PostProcessTargets.VIGNETTE_PASS)
                    .createColor(PostProcessTargets.VIGNETTE_COLOR, RenderFormat.RGBA8)
                    .noClear()
                    .dependsOn(screenProducer)
                    .execute((res, cmd) -> vignette.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(input), graph.width(), graph.height()));
            screenTexture = PostProcessTargets.VIGNETTE_COLOR;
            screenProducer = PostProcessTargets.VIGNETTE_PASS;
        }
        if (filmGrain != null) {
            final String input = screenTexture;
            graph.addPass(PostProcessTargets.FILM_GRAIN_PASS)
                    .createColor(PostProcessTargets.FILM_GRAIN_COLOR, RenderFormat.RGBA8)
                    .noClear()
                    .dependsOn(screenProducer)
                    .execute((res, cmd) -> filmGrain.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(input), graph.width(), graph.height()));
            screenTexture = PostProcessTargets.FILM_GRAIN_COLOR;
            screenProducer = PostProcessTargets.FILM_GRAIN_PASS;
        }
        if (chromaticAberration != null) {
            final String input = screenTexture;
            graph.addPass(PostProcessTargets.CHROMATIC_ABERRATION_PASS)
                    .createColor(PostProcessTargets.CHROMATIC_ABERRATION_COLOR, RenderFormat.RGBA8)
                    .noClear()
                    .dependsOn(screenProducer)
                    .execute((res, cmd) -> chromaticAberration.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(input), graph.width(), graph.height()));
            screenTexture = PostProcessTargets.CHROMATIC_ABERRATION_COLOR;
            screenProducer = PostProcessTargets.CHROMATIC_ABERRATION_PASS;
        }

        if (settings.antiAliasingMode() == AntiAliasingMode.FXAA) {
            fxaa.addHdrPass(graph, screenProducer, screenTexture);
            recordFinalPass(PostProcessTargets.FXAA_PASS);
        } else {
            String presentSource = screenProducer;
            graph.addPass(PostProcessTargets.PRESENT_PASS)
                    .writeToBackbuffer()
                    .noClear()
                    .dependsOn(presentSource)
                    .execute((res, cmd) -> {
                        Framebuffer source = res.framebufferOfPass(presentSource);
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

    private TemporalFramePose framePose;

    public String finalPassName() {        if (finalPassName == null) {
            throw new IllegalStateException("Final backbuffer pass has not been added");
        }
        return finalPassName;
    }

    public String linearHdrTextureName() {
        if (linearHdrTextureName == null) {
            throw new IllegalStateException("postprocess HDR source is unavailable before graph build");
        }
        return linearHdrTextureName;
    }

    private void recordFinalPass(String passName) {
        if (finalPassName != null) {
            throw new IllegalStateException("Final backbuffer pass has already been recorded: "
                    + finalPassName);
        }
        finalPassName = Objects.requireNonNull(passName, "passName");
    }

    /**
     * Starts the frame: clamps the delta, prepares the temporal write slot and
     * stores the camera pose used by later pass declarations.
     */
    public void beginFrame(TemporalFramePose pose) {
        Objects.requireNonNull(pose, "pose");
        framePose = pose;
        deltaSeconds = Math.min(pose.deltaSeconds(), 0.1f);
        if (temporal != null) {
            temporal.beginFrame(pose);
        }
    }

    /**
     * Fallible pre-commit phase.  Runs before any temporal owner publishes
     * state so a failure cannot leave a partially advanced frame.
     */
    public void prepareFrameSuccess() {
    }

    public void frameSucceeded() {
        if (autoExposure != null) {
            autoExposure.frameSucceeded();
        }
        if (temporal != null) {
            temporal.frameSucceeded();
        }
    }

    public void frameFailed() {
        if (autoExposure != null) {
            autoExposure.frameFailed();
        }
        if (temporal != null) {
            temporal.frameFailed();
        }
    }

    public void invalidateTemporalHistory() {
        invalidateTemporalHistory(TaaInvalidationReason.EXPLICIT_RESET);
    }

    public void invalidateTemporalHistory(TaaInvalidationReason reason) {
        if (temporal != null) {
            temporal.invalidate(reason);
        }
    }

    public String completedTaaInvalidationReason() {
        return temporal == null ? "" : temporal.completedInvalidationReason();
    }

    /** Test/diagnostic access to the TAA history owner. */
    public TaaHistory taaHistoryForTest() {
        return temporal == null ? null : temporal.historyForTest();
    }

    /**
     * Publishes this frame's history write colour as a borrowed logical texture
     * for passes that run after the TAA resolve.
     */
    public void synchronizeTemporalImports(RenderGraph graph) {
        if (temporal != null) {
            temporal.synchronizeImports(graph);
        }
    }

    public void updateVisualScalars(float exposure, BloomSettings bloomSettings) {
        if (!Float.isFinite(exposure) || exposure <= 0.0f) {
            throw new IllegalArgumentException("manual exposure must be finite and positive");
        }
        BloomSettings requiredBloom = Objects.requireNonNull(bloomSettings, "bloom");
        if (requiredBloom.enabled() != (bloom != null)
                || requiredBloom.enabled() && requiredBloom.maxLevels() != bloom.levels()) {
            throw new IllegalArgumentException("bloom topology differs from the active generation");
        }
        manualExposure = exposure;
        if (bloom != null) {
            bloom.updateSettings(requiredBloom);
        }
    }

    public ResizeCandidate prepareResize(int width, int height) {
        return new ResizeCandidate(this, temporal == null ? null : temporal.prepareResize(width, height));
    }

    public void commitResize(ResizeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate").commitInto(this);
    }

    public void resize(int width, int height) {
        ResizeCandidate candidate = prepareResize(width, height);
        try {
            commitResize(candidate);
        } finally {
            candidate.close();
        }
    }

    public static final class ResizeCandidate implements AutoCloseable {
        private final PostProcessPipeline owner;
        private final TaaHistory.ResizeCandidate taaCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PostProcessPipeline owner,
                                TaaHistory.ResizeCandidate taaCandidate) {
            this.owner = owner;
            this.taaCandidate = taaCandidate;
        }

        void validateFor(PostProcessPipeline expectedOwner) {
            if (owner != expectedOwner) {
                throw new IllegalArgumentException("post-process resize candidate belongs to another builder");
            }
            if (closed) throw new IllegalStateException("post-process resize candidate is closed");
            if (committed) throw new IllegalStateException("post-process resize candidate already committed");
        }

        private void commitInto(PostProcessPipeline expectedOwner) {
            validateFor(expectedOwner);
            if (taaCandidate != null) {
                owner.temporal.commitResize(taaCandidate);
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
                    failure = closeFailure;
                }
            }
            if (failure != null) throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (var resource : owned.reversed()) failure = closeCollecting(resource, failure);
        owned.clear();
        borrowed.clear();
        donor = null;
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

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
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
            RuntimeException runtime = cleanupFailure instanceof RuntimeException existing
                    ? existing
                    : new IllegalStateException("Failed to close postprocess resource", cleanupFailure);
            if (failure == null) return runtime;
            failure.addSuppressed(runtime);
        }
        return failure;
    }
}
