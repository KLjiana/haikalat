package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.ExternalAttachment;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.bloom.BloomChain;
import com.kaleblangley.haikalat.subsystems.postprocess.exposure.AutoExposureChain;
import com.kaleblangley.haikalat.subsystems.postprocess.fog.FogChain;
import com.kaleblangley.haikalat.subsystems.postprocess.fxaa.FxaaChain;
import com.kaleblangley.haikalat.subsystems.postprocess.smaa.SmaaPass;
import com.kaleblangley.haikalat.subsystems.postprocess.ssr.SsrPass;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaHistory;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TaaInvalidationReason;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalChain;
import com.kaleblangley.haikalat.subsystems.postprocess.temporal.TemporalFramePose;
import com.kaleblangley.haikalat.subsystems.postprocess.tonemapping.ToneMappingChain;
import com.kaleblangley.haikalat.subsystems.postprocess.vignette.VignettePass;
import com.kaleblangley.haikalat.subsystems.postprocess.film.FilmGrainPass;
import com.kaleblangley.haikalat.subsystems.postprocess.chromatic.ChromaticAberrationPass;
import com.kaleblangley.haikalat.subsystems.postprocess.depthoffield.DepthOfFieldPass;
import com.kaleblangley.haikalat.subsystems.postprocess.denoise.TemporalDenoiseInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.denoise.TemporalDenoisePass;
import com.kaleblangley.haikalat.subsystems.postprocess.distortion.ScreenDistortionPass;
import com.kaleblangley.haikalat.subsystems.postprocess.motionblur.MotionBlurPass;

import org.joml.Matrix4f;

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
    private final SmaaPass smaa;
    private final TemporalChain temporal;
    private final ToneMappingChain toneMapping;
    private final BloomChain bloom;
    private final AutoExposureChain autoExposure;
    private final FogChain fog;
    private final VignettePass vignette;
    private final FilmGrainPass filmGrain;
    private final ChromaticAberrationPass chromaticAberration;
    private final ScreenDistortionPass screenDistortion;
    private final DepthOfFieldPass depthOfField;
    private final MotionBlurPass motionBlur;
    private final SsrPass ssr;
    private final TemporalDenoisePass temporalDenoise;
    private final RenderGraph.PassExecutor hdrVfx;
    private final SurfaceInputs surfaceInputs;
    private final SsrEnvironmentSource ssrEnvironment;
    private final LateSurfaceProducer lateSurface;
    private float manualExposure;
    private float deltaSeconds = 1.0f / 60.0f;
    private String finalPassName;
    private String linearHdrTextureName;
    private String sceneColorInput = PostProcessTargets.SCENE_COLOR;
    private String sceneColorProducer = PostProcessTargets.GEOMETRY_PASS;
    private String hdrResolveSource = PostProcessTargets.GEOMETRY_PASS;
    private final List<AutoCloseable> owned;
    private final List<AutoCloseable> borrowed;
    private PostProcessPipeline donor;
    private boolean closed;

    private PostProcessPipeline(RenderSettings settings, PostProcessSettings effects,
                                FxaaChain fxaa, SmaaPass smaa, TemporalChain temporal,
                                ToneMappingChain toneMapping, BloomChain bloom,
                                AutoExposureChain autoExposure, FogChain fog,
                                VignettePass vignette,
                                FilmGrainPass filmGrain,
                                ChromaticAberrationPass chromaticAberration,
                                ScreenDistortionPass screenDistortion,
                                DepthOfFieldPass depthOfField,
                                MotionBlurPass motionBlur,
                                SsrPass ssr,
                                TemporalDenoisePass temporalDenoise,
                                RenderGraph.PassExecutor hdrVfx,
                                SurfaceInputs surfaceInputs,
                                SsrEnvironmentSource ssrEnvironment,
                                LateSurfaceProducer lateSurface,
                                List<AutoCloseable> owned, List<AutoCloseable> borrowed,
                                PostProcessPipeline donor) {
        this.settings = settings;
        this.effects = effects;
        this.fxaa = fxaa;
        this.smaa = smaa;
        this.temporal = temporal;
        this.toneMapping = toneMapping;
        this.bloom = bloom;
        this.autoExposure = autoExposure;
        this.fog = fog;
        this.vignette = vignette;
        this.filmGrain = filmGrain;
        this.chromaticAberration = chromaticAberration;
        this.screenDistortion = screenDistortion;
        this.depthOfField = depthOfField;
        this.motionBlur = motionBlur;
        this.ssr = ssr;
        this.temporalDenoise = temporalDenoise;
        this.hdrVfx = hdrVfx;
        this.surfaceInputs = surfaceInputs;
        this.ssrEnvironment = ssrEnvironment;
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
        return create(settings, effects, width, height, hdrVfx, surfaceInputs, lateSurface, null);
    }

    /**
     * Creates the colour chain with the borrowed environment/IBL source used by
     * screen-space reflections.  The source is required exactly when the
     * request enables SSR and is ignored otherwise.
     */
    public static PostProcessPipeline create(RenderSettings settings, PostProcessSettings effects,
                                             int width, int height,
                                             RenderGraph.PassExecutor hdrVfx,
                                             SurfaceInputs surfaceInputs,
                                             LateSurfaceProducer lateSurface,
                                             SsrEnvironmentSource ssrEnvironment) {
        return assemble(settings, effects, width, height, hdrVfx, surfaceInputs, lateSurface,
                ssrEnvironment, null);
    }

    private static PostProcessPipeline assemble(RenderSettings settings, PostProcessSettings effects,
                                                int width, int height, RenderGraph.PassExecutor hdrVfx,
                                                SurfaceInputs surfaceInputs, LateSurfaceProducer lateSurface,
                                                SsrEnvironmentSource ssrEnvironment,
                                                PostProcessPipeline donor) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(surfaceInputs, "surfaceInputs");
        var owned = new ArrayList<AutoCloseable>();
        var borrowed = new ArrayList<AutoCloseable>();
        try {
            var fxaa = settings.antiAliasingMode() == AntiAliasingMode.FXAA
                    ? reuseOrCreate(donor == null ? null : donor.fxaa, FxaaChain::new, owned, borrowed) : null;
            if (settings.antiAliasingMode() == AntiAliasingMode.SMAA && !settings.hdrEnabled()) {
                throw new IllegalArgumentException("SMAA requires HDR tone mapping");
            }
            var smaa = settings.antiAliasingMode() == AntiAliasingMode.SMAA
                    ? reuseOrCreate(donor == null ? null : donor.smaa, SmaaPass::new, owned, borrowed) : null;
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
            if (effects.depthOfField().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "depth of field requires HDR tone mapping");
            }
            if (effects.motionBlur().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "motion blur requires HDR tone mapping");
            }
            if (effects.ssr().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "screen-space reflections require HDR tone mapping");
            }
            if (effects.ssr().active() && ssrEnvironment == null) {
                throw new IllegalArgumentException(
                        "screen-space reflections require a bound PBR environment");
            }
            if (effects.temporalDenoise().active() && !effects.ssr().active()) {
                throw new IllegalArgumentException(
                        "temporal denoising requires an active screen-space reflection signal");
            }
            if (effects.temporalDenoise().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "temporal denoising requires HDR tone mapping");
            }
            if ((effects.vignette().active() || effects.filmGrain().active()
                    || effects.chromaticAberration().active()) && !settings.hdrEnabled()) {
                throw new IllegalArgumentException("screen effects require HDR tone mapping");
            }
            if (effects.screenDistortion().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "screen distortion requires HDR tone mapping");
            }
            if (effects.screenDistortion().active() && hdrVfx == null) {
                throw new IllegalArgumentException(
                        "screen distortion requires an HDR VFX composite");
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
            ScreenDistortionPass screenDistortion = effects.screenDistortion().active()
                    ? reuseOrCreate(donor != null && donor.screenDistortion != null
                                    && donor.screenDistortion.settings()
                                    .equals(effects.screenDistortion())
                                    ? donor.screenDistortion : null,
                            () -> new ScreenDistortionPass(effects.screenDistortion()),
                            owned, borrowed) : null;
            DepthOfFieldPass depthOfField = effects.depthOfField().active()
                    ? reuseOrCreate(donor != null && donor.depthOfField != null
                                    && donor.depthOfField.settings().equals(effects.depthOfField())
                                    ? donor.depthOfField : null,
                            () -> new DepthOfFieldPass(effects.depthOfField()),
                            owned, borrowed) : null;
            MotionBlurPass motionBlur = effects.motionBlur().active()
                    ? reuseOrCreate(donor != null && donor.motionBlur != null
                                    && donor.motionBlur.settings().equals(effects.motionBlur())
                                    ? donor.motionBlur : null,
                            () -> new MotionBlurPass(effects.motionBlur()),
                            owned, borrowed) : null;
            SsrPass ssr = effects.ssr().active()
                    ? reuseOrCreate(donor != null && donor.ssr != null
                                    && donor.ssr.settings().equals(effects.ssr())
                                    ? donor.ssr : null,
                            () -> new SsrPass(effects.ssr()),
                            owned, borrowed) : null;
            TemporalDenoisePass temporalDenoise = null;
            if (effects.temporalDenoise().active()) {
                temporalDenoise = new TemporalDenoisePass(effects.temporalDenoise(),
                        width, height);
                owned.add(temporalDenoise);
            }
            return new PostProcessPipeline(settings, effects, fxaa, smaa, temporal, toneMapping, bloom,
                    autoExposure, fog, vignette, filmGrain, chromaticAberration, screenDistortion,
                    depthOfField, motionBlur, ssr, temporalDenoise, hdrVfx, surfaceInputs,
                    ssrEnvironment, lateSurface, owned, borrowed, donor);
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
                                                   LateSurfaceProducer replacementLateSurface,
                                                   SsrEnvironmentSource replacementSsrEnvironment) {
        if (closed || donor != null) throw new IllegalStateException("invalid postprocess donor");
        return assemble(replacement, replacementEffects, width, height, vfx,
                replacementSurface, replacementLateSurface, replacementSsrEnvironment, this);
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

    /**
     * Overrides the multisample colour source copied into the single-sample HDR
     * resolve.  Render3D uses this when a late scene-colour producer (for
     * example the water surface pass) replaces {@code GeometryPass} as the
     * source of the HDR chain.
     */
    public void hdrResolveSource(String producer) {
        hdrResolveSource = Objects.requireNonNull(producer);
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
        if (mode == AntiAliasingMode.SMAA) {
            if (toneMappingMode == ToneMappingMode.NONE) {
                throw new IllegalArgumentException("SMAA requires HDR tone mapping");
            }
            return PostProcessTargets.SMAA_PASS;
        }
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
        if (depthOfField != null) {
            surfaceInputs.validateDependency(PostProcessTargets.DEPTH_OF_FIELD_PASS,
                    requireInput(SurfaceInputs.Channel.DEPTH));
        }
        if (motionBlur != null) {
            surfaceInputs.validateDependency(PostProcessTargets.MOTION_BLUR_PASS,
                    requireInput(SurfaceInputs.Channel.VELOCITY));
            surfaceInputs.validateDependency(PostProcessTargets.MOTION_BLUR_PASS,
                    requireInput(SurfaceInputs.Channel.VALIDITY));
        }
        if (ssr != null) {
            surfaceInputs.validateDependency(PostProcessTargets.SSR_PASS,
                    requireInput(SurfaceInputs.Channel.DEPTH));
            surfaceInputs.validateDependency(PostProcessTargets.SSR_PASS,
                    requireInput(SurfaceInputs.Channel.NORMAL));
            surfaceInputs.validateDependency(PostProcessTargets.SSR_PASS,
                    requireInput(SurfaceInputs.Channel.ROUGHNESS));
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
                fxaa.addLdrPass(graph, sceneColorProducer, PostProcessTargets.SCENE_COLOR);
                addLdrPresentPass(graph, PostProcessTargets.FXAA_PASS);
            }
            case TAA -> {
                if (reactiveRequested()) addReactivePass(graph, sceneColorProducer);
                addTaaPass(graph, PostProcessTargets.SCENE_COLOR, sceneColorProducer);
                addTaaPresentPass(graph);
            }
            case NONE, MSAA -> addLdrPresentPass(graph, sceneColorProducer);
            case SMAA -> throw new IllegalStateException("SMAA requires HDR tone mapping");
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
            final String resolveProducer = hdrResolveSource;
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

        if (screenDistortion != null) {
            final String distortionInput = hdrTexture;
            final String distortionProducer = hdrProducer;
            graph.addPass(PostProcessTargets.SCREEN_DISTORTION_PASS)
                    .createColor(PostProcessTargets.SCREEN_DISTORTION_COLOR,
                            RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(distortionProducer)
                    .execute((res, cmd) -> screenDistortion.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(distortionInput),
                            res.depthAttachment(PostProcessTargets.VFX_SCENE_DEPTH),
                            screenDistortion.settings().contributions(),
                            graph.width(), graph.height()));
            hdrTexture = PostProcessTargets.SCREEN_DISTORTION_COLOR;
            hdrProducer = PostProcessTargets.SCREEN_DISTORTION_PASS;
        }

        if (reactiveRequested()) addReactivePass(graph, hdrProducer);

        if (settings.antiAliasingMode() == AntiAliasingMode.TAA) {
            addTaaPass(graph, hdrTexture, hdrProducer);
            hdrTexture = PostProcessTargets.TAA_RESOLVED_COLOR;
            hdrProducer = PostProcessTargets.TAA_PASS;
        }

        if (ssr != null) {
            final String reflectionInput = hdrTexture;
            final String reflectionProducer = hdrProducer;
            SurfaceInputs.Input ssrDepth = requireInput(SurfaceInputs.Channel.DEPTH);
            SurfaceInputs.Input ssrNormal = requireInput(SurfaceInputs.Channel.NORMAL);
            SurfaceInputs.Input ssrRoughness = requireInput(SurfaceInputs.Channel.ROUGHNESS);
            var ssrPass = graph.addPass(PostProcessTargets.SSR_PASS)
                    .createColor(PostProcessTargets.SSR_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(reflectionProducer);
            for (SurfaceInputs.Input input : List.of(ssrDepth, ssrNormal, ssrRoughness)) {
                if (!reflectionProducer.equals(input.producerPass())
                        && !graph.hasPass(input.producerPass())) {
                    throw new IllegalStateException(
                            "screen-space reflections require surface producer "
                                    + input.producerPass());
                }
                if (!reflectionProducer.equals(input.producerPass())) {
                    ssrPass.dependsOn(input.producerPass());
                }
            }
            ssrPass.execute((res, cmd) -> {
                TemporalFramePose frame = framePose;
                if (frame == null) {
                    throw new IllegalStateException(
                            "screen-space reflections require a started frame pose");
                }
                Matrix4f view = new Matrix4f(frame.inverseView()).invert();
                ssr.recordIntoCurrentTarget(cmd,
                        res.colorAttachment(reflectionInput),
                        surfaceInputs.texture(res, ssrDepth),
                        surfaceInputs.texture(res, ssrNormal),
                        surfaceInputs.texture(res, ssrRoughness),
                        frame.inverseJitteredProjection(), frame.inverseView(), view,
                        frame.stableViewProjection(), ssrEnvironment,
                        graph.width(), graph.height());
            });
            hdrTexture = PostProcessTargets.SSR_COLOR;
            hdrProducer = PostProcessTargets.SSR_PASS;
        }

        if (temporalDenoise != null) {
            final String denoiseInput = hdrTexture;
            final String denoiseProducer = hdrProducer;
            SurfaceInputs.Input denoiseDepth = requireInput(SurfaceInputs.Channel.DEPTH);
            SurfaceInputs.Input denoiseNormal = requireInput(SurfaceInputs.Channel.NORMAL);
            var denoiseTarget = temporalDenoise.writeFramebuffer();
            graph.importExternalColor(PostProcessTargets.TEMPORAL_DENOISE_COLOR,
                    ExternalAttachment.borrowedColor(denoiseTarget.colorAttachment(0),
                            RenderFormat.RGBA16F, denoiseTarget.width(),
                            denoiseTarget.height()));
            var denoisePass = graph.addPass(PostProcessTargets.TEMPORAL_DENOISE_PASS)
                    .writeToExternalTarget()
                    .noClear()
                    .dependsOn(denoiseProducer);
            for (SurfaceInputs.Input input : List.of(denoiseDepth, denoiseNormal)) {
                if (!denoiseProducer.equals(input.producerPass())
                        && !graph.hasPass(input.producerPass())) {
                    throw new IllegalStateException(
                            "temporal denoising requires surface producer "
                                    + input.producerPass());
                }
                if (!denoiseProducer.equals(input.producerPass())) {
                    denoisePass.dependsOn(input.producerPass());
                }
            }
            denoisePass.execute((res, cmd) -> {
                TemporalFramePose frame = framePose;
                if (frame == null) {
                    throw new IllegalStateException(
                            "temporal denoising requires a started frame pose");
                }
                temporalDenoise.record(cmd, new TemporalDenoiseInputs(
                        res.colorAttachment(denoiseInput),
                        surfaceInputs.texture(res, denoiseDepth),
                        surfaceInputs.texture(res, denoiseNormal),
                        0, 0,
                        frame.jitterUvX(), frame.jitterUvY(),
                        frame.inverseJitteredProjection(),
                        frame.inverseView(),
                        frame.stableViewProjection()));
            });
            hdrTexture = PostProcessTargets.TEMPORAL_DENOISE_COLOR;
            hdrProducer = PostProcessTargets.TEMPORAL_DENOISE_PASS;
        }

        if (depthOfField != null) {
            final String focusInput = hdrTexture;
            final String focusProducer = hdrProducer;
            SurfaceInputs.Input depth = requireInput(SurfaceInputs.Channel.DEPTH);
            var depthOfFieldPass = graph.addPass(PostProcessTargets.DEPTH_OF_FIELD_PASS)
                    .createColor(PostProcessTargets.DEPTH_OF_FIELD_COLOR,
                            RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(focusProducer);
            if (!focusProducer.equals(depth.producerPass())) {
                depthOfFieldPass.dependsOn(depth.producerPass());
            }
            depthOfFieldPass.execute((res, cmd) -> {
                        TemporalFramePose frame = framePose;
                        if (frame == null) {
                            throw new IllegalStateException(
                                    "depth of field requires a started frame pose");
                        }
                        depthOfField.recordIntoCurrentTarget(cmd,
                                res.colorAttachment(focusInput),
                                surfaceInputs.texture(res, depth),
                                frame.inverseJitteredProjection(),
                                graph.width(), graph.height());
                    });
            hdrTexture = PostProcessTargets.DEPTH_OF_FIELD_COLOR;
            hdrProducer = PostProcessTargets.DEPTH_OF_FIELD_PASS;
        }

        if (motionBlur != null) {
            final String blurInput = hdrTexture;
            final String blurProducer = hdrProducer;
            SurfaceInputs.Input velocity = requireInput(SurfaceInputs.Channel.VELOCITY);
            SurfaceInputs.Input validity = requireInput(SurfaceInputs.Channel.VALIDITY);
            var blurPass = graph.addPass(PostProcessTargets.MOTION_BLUR_PASS)
                    .createColor(PostProcessTargets.MOTION_BLUR_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(blurProducer);
            if (!blurProducer.equals(velocity.producerPass())) {
                blurPass.dependsOn(velocity.producerPass());
            }
            if (!blurProducer.equals(validity.producerPass())
                    && !velocity.producerPass().equals(validity.producerPass())) {
                blurPass.dependsOn(validity.producerPass());
            }
            blurPass.execute((res, cmd) -> {
                TemporalFramePose frame = framePose;
                if (frame == null) {
                    throw new IllegalStateException("motion blur requires a started frame pose");
                }
                motionBlur.recordIntoCurrentTarget(cmd,
                        res.colorAttachment(blurInput),
                        surfaceInputs.texture(res, velocity),
                        surfaceInputs.texture(res, validity),
                        graph.width(), graph.height());
            });
            hdrTexture = PostProcessTargets.MOTION_BLUR_COLOR;
            hdrProducer = PostProcessTargets.MOTION_BLUR_PASS;
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

        if (settings.antiAliasingMode() == AntiAliasingMode.SMAA) {
            addSmaaPasses(graph, screenTexture, screenProducer);
        } else if (settings.antiAliasingMode() == AntiAliasingMode.FXAA) {
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

    /**
     * Adds the SMAA 1x chain: edge detection, blending weights and
     * neighbourhood blending.  Edge and weight targets are cleared like the
     * reference temporal targets because the edge shader discards flat pixels.
     */
    private void addSmaaPasses(RenderGraph graph, String colorTexture, String colorProducer) {
        graph.addPass(PostProcessTargets.SMAA_EDGE_DETECTION_PASS)
                .createColor(PostProcessTargets.SMAA_EDGE_COLOR, RenderFormat.RGBA8)
                .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                .dependsOn(colorProducer)
                .execute((res, cmd) -> {
                    Framebuffer source = res.framebufferOfPass(colorProducer);
                    if (source != null) {
                        smaa.recordEdgeDetection(cmd, res.colorAttachment(colorTexture),
                                source.width(), source.height());
                    }
                });
        graph.addPass(PostProcessTargets.SMAA_BLENDING_WEIGHT_PASS)
                .createColor(PostProcessTargets.SMAA_BLEND_COLOR, RenderFormat.RGBA8)
                .clearColor(0.0f, 0.0f, 0.0f, 0.0f)
                .dependsOn(PostProcessTargets.SMAA_EDGE_DETECTION_PASS)
                .execute((res, cmd) -> smaa.recordBlendingWeights(cmd,
                        res.colorAttachment(PostProcessTargets.SMAA_EDGE_COLOR),
                        graph.width(), graph.height()));
        graph.addPass(PostProcessTargets.SMAA_PASS)
                .writeToBackbuffer()
                .noClear()
                .dependsOn(PostProcessTargets.SMAA_BLENDING_WEIGHT_PASS)
                .execute((res, cmd) -> {
                    Framebuffer source = res.framebufferOfPass(colorProducer);
                    if (source != null) {
                        smaa.recordNeighborhoodBlending(cmd,
                                res.colorAttachment(colorTexture),
                                res.colorAttachment(PostProcessTargets.SMAA_BLEND_COLOR),
                                source.width(), source.height());
                    }
                });
        recordFinalPass(PostProcessTargets.SMAA_PASS);
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
        if (temporalDenoise != null) {
            temporalDenoise.beginFrame();
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
        if (temporalDenoise != null) {
            temporalDenoise.frameSucceeded();
        }
    }

    public void frameFailed() {
        if (autoExposure != null) {
            autoExposure.frameFailed();
        }
        if (temporal != null) {
            temporal.frameFailed();
        }
        if (temporalDenoise != null) {
            temporalDenoise.frameFailed();
        }
    }

    public void invalidateTemporalHistory() {
        invalidateTemporalHistory(TaaInvalidationReason.EXPLICIT_RESET);
    }

    public void invalidateTemporalHistory(TaaInvalidationReason reason) {
        if (temporal != null) {
            temporal.invalidate(reason);
        }
        if (temporalDenoise != null) {
            temporalDenoise.invalidate();
        }
    }

    public String completedTaaInvalidationReason() {
        return temporal == null ? "" : temporal.completedInvalidationReason();
    }

    /** Test/diagnostic access to the TAA history owner. */
    public TaaHistory taaHistoryForTest() {
        return temporal == null ? null : temporal.historyForTest();
    }

    /** @return true when the bound temporal-denoise owner has a committed history */
    public boolean temporalDenoiseHistoryValid() {
        return temporalDenoise != null && temporalDenoise.historyValid();
    }

    /**
     * Publishes this frame's history write colour as a borrowed logical texture
     * for passes that run after the TAA resolve.
     */
    public void synchronizeTemporalImports(RenderGraph graph) {
        if (temporal != null) {
            temporal.synchronizeImports(graph);
        }
        if (temporalDenoise != null) {
            var target = temporalDenoise.writeFramebuffer();
            graph.importExternalColor(PostProcessTargets.TEMPORAL_DENOISE_COLOR,
                    ExternalAttachment.borrowedColor(target.colorAttachment(0),
                            RenderFormat.RGBA16F, target.width(), target.height()));
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
        TaaHistory.ResizeCandidate taa = temporal == null
                ? null : temporal.prepareResize(width, height);
        TemporalDenoisePass.ResizeCandidate denoiseCandidate = null;
        try {
            denoiseCandidate = temporalDenoise == null
                    ? null : temporalDenoise.prepareResize(width, height);
            return new ResizeCandidate(this, taa, denoiseCandidate);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(taa, failure);
            closeAfterFailure(denoiseCandidate, failure);
            throw failure;
        }
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
        private final TemporalDenoisePass.ResizeCandidate denoiseCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PostProcessPipeline owner,
                                TaaHistory.ResizeCandidate taaCandidate,
                                TemporalDenoisePass.ResizeCandidate denoiseCandidate) {
            this.owner = owner;
            this.taaCandidate = taaCandidate;
            this.denoiseCandidate = denoiseCandidate;
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
            if (denoiseCandidate != null) {
                owner.temporalDenoise.commitResize(denoiseCandidate);
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
            if (denoiseCandidate != null) {
                try {
                    denoiseCandidate.close();
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
            case SMAA -> throw new IllegalArgumentException("SMAA requires HDR tone mapping");
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
            case SMAA -> List.of(PostProcessTargets.GEOMETRY_PASS,
                    PostProcessTargets.TONE_MAPPING_PASS,
                    PostProcessTargets.SMAA_EDGE_DETECTION_PASS,
                    PostProcessTargets.SMAA_BLENDING_WEIGHT_PASS,
                    PostProcessTargets.SMAA_PASS);
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
