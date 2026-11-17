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
import com.kaleblangley.haikalat.subsystems.postprocess.ssgi.SsgiPass;
import com.kaleblangley.haikalat.subsystems.postprocess.ssr.SsrPass;
import com.kaleblangley.haikalat.subsystems.postprocess.sss.SubsurfaceScatteringPass;
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
import com.kaleblangley.haikalat.subsystems.postprocess.upscale.TemporalUpscalingInputs;
import com.kaleblangley.haikalat.subsystems.postprocess.upscale.TemporalUpscalingPass;
import com.kaleblangley.haikalat.subsystems.postprocess.vrs.VariableRateShadingPass;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingCapability;
import com.kaleblangley.haikalat.subsystems.postprocess.vrs.VariableRateShadingSettings;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    /**
     * Number of phases in the internal reduced-raster jitter sequence.
     *
     * <p>Mirrors {@code TemporalJitter.REDUCED_PHASE_COUNT}; the postprocess
     * subsystem must not import render3d, so only this wrap constant is
     * repeated.  The sample pattern itself is owned by the render3d sequence.</p>
     */
    private static final int INTERNAL_RASTER_JITTER_PHASE_COUNT = 4;

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
    private final SsgiPass ssgi;
    private final SubsurfaceScatteringPass subsurfaceScattering;
    private final VariableRateShadingPass variableRateShading;
    private final String variableRateShadingStatus;
    private final TemporalDenoisePass temporalDenoise;
    private final TemporalUpscalingPass temporalUpscaling;
    private final TemporalUpscalingSource temporalUpscalingSource;
    private final boolean internalTemporalUpscaling;
    private SceneUpscalingInputs sceneUpscalingInputs;
    private RenderGraph internalUpscalingGraph;
    private boolean internalUpscalingAdded;
    private final RenderGraph.PassExecutor hdrVfx;
    private final SurfaceInputs surfaceInputs;
    private final SsrEnvironmentSource ssrEnvironment;
    private final SsgiEnvironmentSource ssgiEnvironment;
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
    private int internalRasterJitterPhase;
    private float internalRasterJitterUvX;
    private float internalRasterJitterUvY;
    private boolean vfxCompositeAdded;
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
                                SsgiPass ssgi,
                                SubsurfaceScatteringPass subsurfaceScattering,
                                VariableRateShadingPass variableRateShading,
                                String variableRateShadingStatus,
                                TemporalDenoisePass temporalDenoise,
                                TemporalUpscalingPass temporalUpscaling,
                                TemporalUpscalingSource temporalUpscalingSource,
                                boolean internalTemporalUpscaling,
                                RenderGraph.PassExecutor hdrVfx,
                                SurfaceInputs surfaceInputs,
                                SsrEnvironmentSource ssrEnvironment,
                                SsgiEnvironmentSource ssgiEnvironment,
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
        this.ssgi = ssgi;
        this.subsurfaceScattering = subsurfaceScattering;
        this.variableRateShading = variableRateShading;
        this.variableRateShadingStatus = variableRateShadingStatus;
        this.temporalDenoise = temporalDenoise;
        this.temporalUpscaling = temporalUpscaling;
        this.temporalUpscalingSource = temporalUpscalingSource;
        this.internalTemporalUpscaling = internalTemporalUpscaling;
        this.hdrVfx = hdrVfx;
        this.surfaceInputs = surfaceInputs;
        this.ssrEnvironment = ssrEnvironment;
        this.ssgiEnvironment = ssgiEnvironment;
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
        return create(settings, effects, width, height, hdrVfx, surfaceInputs, lateSurface, null,
                null);
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
        return create(settings, effects, width, height, hdrVfx, surfaceInputs, lateSurface,
                ssrEnvironment, null);
    }

    /**
     * Creates the colour chain with a borrowed low-resolution colour source for
     * temporal upscaling.
     *
     * <p>When {@link PostProcessSettings#temporalUpscaling()} is active the
     * source is required and the chain starts with one
     * {@link PostProcessTargets#TEMPORAL_UPSCALING_PASS} that magnifies it to
     * the supplied output extent.  When it is inactive the source must be
     * {@code null}; the baseline chain and every imported attachment stay
     * byte-for-byte untouched.  The source textures are generation-stable: a
     * different low-resolution target or extent requires a candidate
     * generation.</p>
     */
    public static PostProcessPipeline create(RenderSettings settings, PostProcessSettings effects,
                                             int width, int height,
                                             RenderGraph.PassExecutor hdrVfx,
                                             SurfaceInputs surfaceInputs,
                                             LateSurfaceProducer lateSurface,
                                             SsrEnvironmentSource ssrEnvironment,
                                             TemporalUpscalingSource temporalUpscalingSource) {
        return create(settings, effects, width, height, hdrVfx, surfaceInputs, lateSurface,
                ssrEnvironment, temporalUpscalingSource, null);
    }

    /**
     * Creates the colour chain with a borrowed irradiance environment for
     * screen-space indirect diffuse. The source is optional: without one the
     * pass uses the request's uniform ambient fallback and still contributes a
     * bounded, non-black offscreen approximation.
     */
    public static PostProcessPipeline create(RenderSettings settings, PostProcessSettings effects,
                                             int width, int height,
                                             RenderGraph.PassExecutor hdrVfx,
                                             SurfaceInputs surfaceInputs,
                                             LateSurfaceProducer lateSurface,
                                             SsrEnvironmentSource ssrEnvironment,
                                             TemporalUpscalingSource temporalUpscalingSource,
                                             SsgiEnvironmentSource ssgiEnvironment) {
        return assemble(settings, effects, width, height, hdrVfx, surfaceInputs, lateSurface,
                ssrEnvironment, temporalUpscalingSource, ssgiEnvironment, null);
    }

    private static PostProcessPipeline assemble(RenderSettings settings, PostProcessSettings effects,
                                                int width, int height, RenderGraph.PassExecutor hdrVfx,
                                                SurfaceInputs surfaceInputs, LateSurfaceProducer lateSurface,
                                                SsrEnvironmentSource ssrEnvironment,
                                                TemporalUpscalingSource temporalUpscalingSource,
                                                SsgiEnvironmentSource ssgiEnvironment,
                                                PostProcessPipeline donor) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(surfaceInputs, "surfaceInputs");
        boolean internalUpscaling = internalUpscaling(settings, effects, width, height);
        validateTemporalComposition(settings, effects, width, height,
                hdrVfx != null, lateSurface != null && lateSurface.enabled(),
                temporalUpscalingSource, internalUpscaling);
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
            validateAdaptiveSampling(settings, effects);
            if (effects.temporalDenoise().active() && !effects.ssr().active()) {
                throw new IllegalArgumentException(
                        "temporal denoising requires an active screen-space reflection signal");
            }
            if (effects.temporalDenoise().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "temporal denoising requires HDR tone mapping");
            }
            if (effects.subsurfaceScattering().screenSpaceActive() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "screen-space subsurface scattering requires HDR tone mapping");
            }
            if (effects.ssgi().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "screen-space indirect diffuse requires HDR tone mapping");
            }
            if (effects.variableRateShading().active()
                    && !effects.subsurfaceScattering().screenSpaceActive()) {
                throw new IllegalArgumentException(
                        "variable rate shading requires the declared screen-space subsurface "
                                + "scattering consumer");
            }
            if (effects.variableRateShading().active() && !settings.hdrEnabled()) {
                throw new IllegalArgumentException(
                        "variable rate shading requires HDR tone mapping");
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
                                    && donor.ssr.adaptiveSettings()
                                    .equals(effects.adaptiveSampling())
                                    && donor.ssr.reflectionSignal()
                                    == effects.temporalDenoise().active()
                                    ? donor.ssr : null,
                            () -> new SsrPass(effects.ssr(), effects.adaptiveSampling(),
                                    effects.temporalDenoise().active()),
                            owned, borrowed) : null;
            SsgiPass ssgi = effects.ssgi().active()
                    ? reuseOrCreate(donor != null && donor.ssgi != null
                                    && donor.ssgi.settings().equals(effects.ssgi())
                                    && donor.ssgi.compatibleExtent(width, height)
                                    ? donor.ssgi : null,
                            () -> new SsgiPass(effects.ssgi(), width, height),
                            owned, borrowed) : null;
            SubsurfaceScatteringPass subsurfaceScattering =
                    effects.subsurfaceScattering().screenSpaceActive()
                    ? reuseOrCreate(donor != null && donor.subsurfaceScattering != null
                                    && donor.subsurfaceScattering.settings()
                                    .equals(effects.subsurfaceScattering())
                                    ? donor.subsurfaceScattering : null,
                            () -> new SubsurfaceScatteringPass(
                                    effects.subsurfaceScattering()),
                            owned, borrowed) : null;
            VariableRateShadingPass variableRateShading = null;
            String variableRateShadingStatus = "disabled";
            VariableRateShadingSettings shadingRequest = effects.variableRateShading();
            if (shadingRequest.active()) {
                VariableRateShadingCapability capability =
                        VariableRateShadingCapability.inspectCurrent();
                VariableRateShadingSettings.Decision decision = shadingRequest.decide(capability);
                if (decision == VariableRateShadingSettings.Decision.ACTIVE_NV_IMAGE) {
                    variableRateShading = new VariableRateShadingPass(shadingRequest, capability,
                            width, height);
                    owned.add(variableRateShading);
                    variableRateShadingStatus = "active:" + shadingRequest.summary()
                            + ":" + capability.summary();
                } else {
                    // Fail closed: no rate image, no scope commands, no silent pass. The
                    // consumer keeps rendering at full rate and the diagnostics call it
                    // a fallback rather than an active VRS path.
                    variableRateShadingStatus = "fallback:" + shadingRequest.summary()
                            + ":" + capability.unsupportedReason();
                }
            }
            TemporalDenoisePass temporalDenoise = null;
            if (effects.temporalDenoise().active()) {
                temporalDenoise = new TemporalDenoisePass(effects.temporalDenoise(),
                        width, height);
                owned.add(temporalDenoise);
            }
            TemporalUpscalingPass temporalUpscaling = null;
            if (effects.temporalUpscaling().active() || internalUpscaling) {
                // The upscaler owns new history storage on every generation; it
                // is never borrowed from a donor because its extent relationship
                // to the low-resolution source may differ.  The internal
                // reduced-scene path is created here at the chain extent and its
                // input attachments stay owned by the scene/generation.
                TemporalUpscalingSettings upscalingSettings =
                        effects.temporalUpscaling().active()
                                ? effects.temporalUpscaling()
                                : TemporalUpscalingSettings.internalLowResolution(
                                        settings.renderScale());
                temporalUpscaling = new TemporalUpscalingPass(upscalingSettings, width, height);
                owned.add(temporalUpscaling);
            }
            return new PostProcessPipeline(settings, effects, fxaa, smaa, temporal, toneMapping, bloom,
                    autoExposure, fog, vignette, filmGrain, chromaticAberration, screenDistortion,
                    depthOfField, motionBlur, ssr, ssgi, subsurfaceScattering,
                    variableRateShading, variableRateShadingStatus, temporalDenoise,
                    temporalUpscaling,
                    effects.temporalUpscaling().active() ? temporalUpscalingSource : null,
                    internalUpscaling,
                    hdrVfx, surfaceInputs, ssrEnvironment, ssgiEnvironment, lateSurface, owned,
                    borrowed, donor);
        } catch (RuntimeException | Error failure) {
            for (var resource : owned.reversed()) closeAfterFailure(resource, failure);
            throw failure;
        }
    }

    /**
     * Validates the adaptive-sampling request against its real consumer.
     *
     * <p>Adaptive sampling only allocates the step budget of the screen-space
     * reflection march, so it requires an active {@link SsrSettings}; the
     * requested ceiling must not exceed the reflection hard bound
     * ({@link SsrSettings#maxSteps()}) so the configured budget stays the
     * ceiling, and HDR tone mapping is required for the same reason as SSR.
     * Disabled is the exact no-op value and passes every check without
     * allocating or changing anything.</p>
     */
    static void validateAdaptiveSampling(RenderSettings settings, PostProcessSettings effects) {
        AdaptiveSamplingSettings adaptive = effects.adaptiveSampling();
        if (!adaptive.active()) {
            return;
        }
        if (!effects.ssr().active()) {
            throw new IllegalArgumentException(
                    "adaptive sampling requires an active screen-space reflection sampler");
        }
        if (!settings.hdrEnabled()) {
            throw new IllegalArgumentException("adaptive sampling requires HDR tone mapping");
        }
        if (adaptive.maximumSteps() > effects.ssr().maxSteps()) {
            throw new IllegalArgumentException(
                    "adaptive sampling maximumSteps must not exceed the screen-space "
                            + "reflection maxSteps");
        }
    }

    /**
     * Pure temporal-effect composition contract shared by the assembly path and
     * the JVM tests.
     *
     * <p>Two temporal owners publish through a pass-owned external target:
     * {@code TemporalDenoisePass} and {@code TemporalUpscalingPass}.  Neither is
     * a managed graph pass, so {@code BloomChain} cannot size its extract source
     * from a colour target and automatic exposure cannot read a luminance
     * target.  Both are therefore rejected fail-closed while the external-target
     * owner is active; this is a documented first-version limitation, not a
     * silent fallback.</p>
     *
     * <p>An active upscaler additionally replaces the scene colour input, so
     * every effect that would consume a full-resolution shared surface after it
     * is rejected: the reduced-resolution scene render must own fog, VFX,
     * distortion, the reactive mask and depth-consuming effects itself.
     * Full-resolution TAA/MSAA are rejected because either would double-resolve
     * the temporal signal or bypass the single-sample low-resolution source.  A
     * supplied source without an active request is rejected instead of being
     * silently ignored.</p>
     */
    static void validateTemporalComposition(RenderSettings settings,
                                            PostProcessSettings effects,
                                            int width, int height,
                                            boolean hdrVfx,
                                            boolean lateSurfaceEnabled,
                                            TemporalUpscalingSource source) {
        validateTemporalComposition(settings, effects, width, height, hdrVfx,
                lateSurfaceEnabled, source, internalUpscaling(settings, effects, width, height));
    }

    /**
     * @return true when the colour chain must reconstruct the generation-owned
     *         reduced scene raster instead of an imported caller source
     */
    static boolean internalUpscaling(RenderSettings settings, PostProcessSettings effects,
                                     int width, int height) {
        if (effects.temporalUpscaling().active()
                || settings.renderScale() >= RenderSettings.MAX_RENDER_SCALE) {
            return false;
        }
        return TemporalUpscalingSettings.scaledExtent(width, settings.renderScale()) != width
                || TemporalUpscalingSettings.scaledExtent(height, settings.renderScale()) != height;
    }

    static void validateTemporalComposition(RenderSettings settings,
                                            PostProcessSettings effects,
                                            int width, int height,
                                            boolean hdrVfx,
                                            boolean lateSurfaceEnabled,
                                            TemporalUpscalingSource source,
                                            boolean internalUpscaling) {
        validateExternalTargetConsumers(settings, effects);
        TemporalUpscalingSettings upscaling = effects.temporalUpscaling();
        if (!upscaling.active()) {
            if (source != null) {
                throw new IllegalArgumentException(
                        "a temporal upscaling source was supplied while temporal upscaling is "
                                + "disabled");
            }
            if (internalUpscaling) {
                validateInternalTemporalComposition(settings, effects);
            }
            return;
        }
        if (source == null) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires a low-resolution colour/depth source");
        }
        if (settings.renderScale() < RenderSettings.MAX_RENDER_SCALE) {
            throw new IllegalArgumentException(
                    "a caller-supplied temporal upscaling source cannot compose with "
                            + "renderScale " + settings.renderScale()
                            + "; the reduced scene owns its internal source");
        }
        if (!settings.hdrEnabled()) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires HDR tone mapping");
        }
        if (settings.antiAliasingMode() == AntiAliasingMode.TAA
                || settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            throw new IllegalArgumentException(
                    "temporal upscaling is mutually exclusive with " + settings.antiAliasingMode()
                            + " on the full-resolution colour chain");
        }
        if (hdrVfx) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires the reduced-resolution scene to own the HDR VFX "
                            + "composite");
        }
        if (effects.fog().enabled()) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires the reduced-resolution scene to own fog");
        }
        if (effects.screenDistortion().active()) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires the reduced-resolution scene to own screen "
                            + "distortion");
        }
        if (lateSurfaceEnabled) {
            throw new IllegalArgumentException(
                    "temporal upscaling requires the reduced-resolution scene to own the reactive "
                            + "mask; a late full-resolution reactive producer is not supported");
        }
        if (settings.bloomSettings().enabled()) {
            throw new IllegalArgumentException(
                    "temporal upscaling does not yet support Bloom: the upscaler is an "
                            + "external-target producer without a managed colour target");
        }
        if (settings.exposureMode() == ExposureMode.AUTO) {
            throw new IllegalArgumentException(
                    "temporal upscaling does not yet support automatic exposure: the upscaler is "
                            + "an external-target producer without a managed luminance source");
        }
        if (effects.depthOfField().active() || effects.motionBlur().active()
                || effects.ssr().active() || effects.ssgi().active()
                || effects.temporalDenoise().active()
                || effects.subsurfaceScattering().screenSpaceActive()) {
            throw new IllegalArgumentException(
                    "temporal upscaling cannot consume a full-resolution surface input required by "
                            + "depth of field, motion blur, screen-space reflections, screen-space "
                            + "indirect diffuse, temporal denoising or screen-space subsurface scattering");
        }
        source.validateAgainst(upscaling, width, height);
    }

    /**
     * Rejects compositions that cannot feed the internal reduced-scene
     * reconstruction source.
     *
     * <p>The internal source is the generation-owned R colour/depth raster, and
     * the upscaler resolves at O before the HDR chain.  It therefore needs the
     * same linear HDR target and single-sample stable raster as the caller
     * source path, and it cannot consume a full-resolution surface producer.
     * Fog and screen distortion remain post-chain effects at O; they do not
     * change the reconstruction source.  Every rejection here is also enforced
     * earlier by {@code RenderScalePolicy} on the production path.</p>
     */
    private static void validateInternalTemporalComposition(RenderSettings settings,
                                                            PostProcessSettings effects) {
        if (!settings.hdrEnabled()) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source requires HDR tone mapping");
        }
        if (settings.antiAliasingMode() == AntiAliasingMode.TAA
                || settings.antiAliasingMode() == AntiAliasingMode.MSAA) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source is mutually exclusive with "
                            + settings.antiAliasingMode());
        }
        if (settings.bloomSettings().enabled()) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source does not yet support Bloom: the "
                            + "upscaler is an external-target producer without a managed colour "
                            + "target");
        }
        if (settings.exposureMode() == ExposureMode.AUTO) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source does not yet support automatic "
                            + "exposure: the upscaler is an external-target producer without a "
                            + "managed luminance source");
        }
        if (effects.depthOfField().active() || effects.motionBlur().active()
                || effects.ssr().active() || effects.ssgi().active()
                || effects.temporalDenoise().active()
                || effects.subsurfaceScattering().screenSpaceActive()) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source cannot consume a full-resolution "
                            + "surface input required by depth of field, motion blur, screen-space "
                            + "reflections, screen-space indirect diffuse, temporal denoising or "
                            + "screen-space subsurface scattering");
        }
    }

    /**
     * Rejects colour-chain effects that cannot consume an external-target
     * temporal producer.  {@code PostProcessTargets#TEMPORAL_DENOISE_COLOR} is
     * borrowed from a pass-owned framebuffer, so Bloom would have no managed
     * source target and automatic exposure would read no luminance source.
     */
    private static void validateExternalTargetConsumers(RenderSettings settings,
                                                        PostProcessSettings effects) {
        if (!effects.temporalDenoise().active()) {
            return;
        }
        if (settings.bloomSettings().enabled()) {
            throw new IllegalArgumentException(
                    "temporal denoising does not yet support Bloom: the denoiser is an "
                            + "external-target producer without a managed colour target");
        }
        if (settings.exposureMode() == ExposureMode.AUTO) {
            throw new IllegalArgumentException(
                    "temporal denoising does not yet support automatic exposure: the denoiser is "
                            + "an external-target producer without a managed luminance source");
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
        return replacementAssembly(replacement, replacementEffects, width, height,
                replacementSurface, vfx, replacementLateSurface, replacementSsrEnvironment, null);
    }

    /** Candidate assembly that also carries the borrowed low-resolution source. */
    public PostProcessPipeline replacementAssembly(RenderSettings replacement,
                                                   PostProcessSettings replacementEffects,
                                                   int width, int height,
                                                   SurfaceInputs replacementSurface,
                                                   RenderGraph.PassExecutor vfx,
                                                   LateSurfaceProducer replacementLateSurface,
                                                   SsrEnvironmentSource replacementSsrEnvironment,
                                                   TemporalUpscalingSource replacementUpscalingSource) {
        return replacementAssembly(replacement, replacementEffects, width, height,
                replacementSurface, vfx, replacementLateSurface, replacementSsrEnvironment,
                replacementUpscalingSource, null);
    }

    /**
     * Candidate assembly with the borrowed SSGI environment. A candidate that
     * keeps screen-space indirect diffuse active must carry the source it was
     * assembled with; a missing source simply selects the uniform ambient
     * fallback and is not an error.
     */
    public PostProcessPipeline replacementAssembly(RenderSettings replacement,
                                                   PostProcessSettings replacementEffects,
                                                   int width, int height,
                                                   SurfaceInputs replacementSurface,
                                                   RenderGraph.PassExecutor vfx,
                                                   LateSurfaceProducer replacementLateSurface,
                                                   SsrEnvironmentSource replacementSsrEnvironment,
                                                   TemporalUpscalingSource replacementUpscalingSource,
                                                   SsgiEnvironmentSource replacementSsgiEnvironment) {
        if (closed || donor != null) throw new IllegalStateException("invalid postprocess donor");
        return assemble(replacement, replacementEffects, width, height, vfx,
                replacementSurface, replacementLateSurface, replacementSsrEnvironment,
                replacementUpscalingSource, replacementSsgiEnvironment, this);
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

    /**
     * Baseline colour-chain pass names for a mode.
     *
     * <p>This is the static baseline contract; it does not model an active
     * temporal upscaler.  With {@link PostProcessSettings#temporalUpscaling()}
     * active the live chain additionally starts with
     * {@link PostProcessTargets#TEMPORAL_UPSCALING_PASS} and the actual order
     * must be read from {@link RenderGraph#description()} or
     * {@link #finalPassName()}.</p>
     */
    public static List<String> passNamesFor(AntiAliasingMode mode) {
        return passNamesFor(mode, ToneMappingMode.NONE);
    }

    /**
     * Baseline colour-chain pass names for a mode and tone-mapping stage.
     *
     * @see #passNamesFor(AntiAliasingMode)
     */
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

    /**
     * Baseline final backbuffer pass name.
     *
     * <p>Like {@link #passNamesFor(AntiAliasingMode)}, this does not model an
     * active temporal upscaler; the live final pass must be read through
     * {@link #finalPassName()} after {@link #addFinalPass(RenderGraph)}.</p>
     */
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
        if (internalTemporalUpscaling && !internalUpscalingAdded) {
            throw new IllegalStateException(
                    "the internal reduced-scene temporal pass must be added before the final "
                            + "colour-chain pass");
        }
        if (settings.hdrEnabled()) {
            addHdrFinalPasses(graph);
            return;
        }
        addLdrFinalPass(graph);
    }

    /**
     * Adds the temporal reconstruction of the generation-owned reduced scene
     * raster at the scene-to-chain boundary.
     *
     * <p>This is the internal counterpart of the caller-supplied
     * {@link TemporalUpscalingSource} path: the R colour and single-sample R
     * depth are graph-owned attachments of the scene producers
     * ({@code colorProducer}/{@code depthProducer}), so they are sampled through
     * their logical names for the duration of the frame and never imported as
     * external storage.  The upscaler and its O-sized history remain owned by
     * this colour chain and are destroyed with the generation.  No
     * {@code TemporalUpscalingSource} record is constructed and no caller source
     * is registered.</p>
     *
     * <p>The pass is created only for an assembled internal upscaler and is
     * placed before every HDR effect.  The R raster is jittered by the
     * generation-owned sequence (see {@link #internalRasterJitterPhase()}); the
     * exact per-frame displacement rendered into the R raster is supplied by
     * the frame's {@link #beginFrame(TemporalFramePose, float, float)} call and
     * forwarded to the resolve as {@code uCurrentJitter}, so the sample
     * position and the rasterisation projection cannot disagree.</p>
     *
     * <p>When the sequence is disabled or the phase is zeroed, the displacement
     * is zero and the resolve reproduces the previous jitter-0 spatial
     * magnifier geometry at stable output positions.</p>
     *
     * @param graph           graph receiving the pass
     * @param colorTexture    logical R scene colour texture (linear RGBA16F)
     * @param colorProducer   pass that produced it
     * @param depthTexture    logical single-sample R scene depth texture
     * @param depthProducer   pass that produced it
     * @param inputWidth      R width from the single documented rounding rule
     * @param inputHeight     R height from the single documented rounding rule
     */
    public void addInternalTemporalUpscalingPass(RenderGraph graph, String colorTexture,
                                                 String colorProducer, String depthTexture,
                                                 String depthProducer, int inputWidth,
                                                 int inputHeight) {
        if (!internalTemporalUpscaling || temporalUpscaling == null) {
            throw new IllegalStateException(
                    "this colour chain was not assembled for the internal reduced-scene temporal "
                            + "source");
        }
        if (internalUpscalingAdded) {
            throw new IllegalStateException(
                    "the internal reduced-scene temporal pass has already been added");
        }
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(colorTexture, "colorTexture");
        Objects.requireNonNull(colorProducer, "colorProducer");
        Objects.requireNonNull(depthTexture, "depthTexture");
        Objects.requireNonNull(depthProducer, "depthProducer");
        if (inputWidth <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source extent must be positive");
        }
        if (inputWidth > graph.width() || inputHeight > graph.height()) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source must not exceed the output "
                            + "extent");
        }
        TemporalUpscalingSettings upscaling = temporalUpscaling.settings();
        if (upscaling.inputWidth(graph.width()) != inputWidth
                || upscaling.inputHeight(graph.height()) != inputHeight) {
            throw new IllegalArgumentException(
                    "the internal reduced-scene temporal source extent " + inputWidth + "x"
                            + inputHeight + " disagrees with the documented render-scale rounding "
                            + "rule (" + upscaling.inputWidth(graph.width()) + "x"
                            + upscaling.inputHeight(graph.height()) + ")");
        }

        publishTemporalUpscalingOutput(graph);
        var pass = graph.addPass(PostProcessTargets.TEMPORAL_UPSCALING_PASS)
                .writeToExternalTarget()
                .noClear()
                .dependsOn(colorProducer);
        if (!depthProducer.equals(colorProducer)) {
            pass.dependsOn(depthProducer);
        }
        pass.execute((res, cmd) -> {
            TemporalFramePose frame = framePose;
            if (frame == null) {
                throw new IllegalStateException(
                        "internal temporal upscaling requires a started frame pose");
            }
            int color = res.colorAttachment(colorTexture);
            int depth = res.depthAttachment(depthTexture);
            if (color <= 0) {
                throw new IllegalStateException(
                        "internal temporal upscaling requires the scene colour attachment "
                                + colorTexture);
            }
            if (depth <= 0) {
                throw new IllegalStateException(
                        "internal temporal upscaling requires the single-sample scene depth "
                                + depthTexture);
            }
            temporalUpscaling.record(cmd, new TemporalUpscalingInputs(
                    color, depth, 0, 0, 0, 0, inputWidth, inputHeight,
                    internalRasterJitterUvX, internalRasterJitterUvY, manualExposure,
                    frame.inverseJitteredProjection(), frame.inverseView(),
                    frame.stableViewProjection()));
        });
        sceneUpscalingInputs = new SceneUpscalingInputs(colorTexture, colorProducer, depthTexture,
                depthProducer, inputWidth, inputHeight);
        internalUpscalingGraph = graph;
        internalUpscalingAdded = true;
        sceneColorInput = PostProcessTargets.TEMPORAL_UPSCALING_COLOR;
        sceneColorProducer = PostProcessTargets.TEMPORAL_UPSCALING_PASS;
    }

    private record SceneUpscalingInputs(String colorTexture, String colorProducer,
                                        String depthTexture, String depthProducer,
                                        int width, int height) {
    }

    private void validateInternalUpscalingInputs() {
        SceneUpscalingInputs input = sceneUpscalingInputs;
        RenderGraph.Description description = internalUpscalingGraph.description();
        RenderGraph.PassDescription temporalPass = description.passes().stream()
                .filter(pass -> pass.name().equals(PostProcessTargets.TEMPORAL_UPSCALING_PASS))
                .findFirst().orElse(null);
        if (temporalPass == null) {
            throw new IllegalStateException(
                    "the internal reduced-scene temporal pass is missing from the compiled graph");
        }
        if (temporalPass.targetKind() != RenderGraph.TargetKind.EXTERNAL) {
            throw new IllegalStateException(
                    "the internal reduced-scene temporal pass must write its owned history target");
        }
        if (!temporalPass.borrowedExternalStorage().isEmpty()) {
            throw new IllegalStateException(
                    "the internal reduced-scene temporal pass must not import a caller source");
        }
        if (!temporalPass.directDependencies().contains(input.colorProducer())) {
            throw new IllegalStateException(
                    "the internal reduced-scene temporal pass must depend on the scene colour "
                            + "producer " + input.colorProducer());
        }
        RenderGraph.PassDescription color = pass(description, input.colorProducer());
        if (color.width() != input.width() || color.height() != input.height()) {
            throw new IllegalStateException(
                    "the internal temporal source colour producer must render at the reduced "
                            + "scene extent");
        }
        boolean colorFound = color.colorAttachments().stream()
                .anyMatch(attachment -> attachment.logicalName().equals(input.colorTexture()));
        if (!colorFound) {
            throw new IllegalStateException(
                    "the scene colour producer does not declare " + input.colorTexture());
        }
        RenderGraph.PassDescription depth = pass(description, input.depthProducer());
        if (depth.depthAttachment() == null
                || !depth.depthAttachment().logicalName().equals(input.depthTexture)) {
            throw new IllegalStateException(
                    "the internal temporal source requires a depth texture produced by "
                            + input.depthProducer());
        }
        if (depth.width() != input.width() || depth.height() != input.height()) {
            throw new IllegalStateException(
                    "the internal temporal source depth producer must render at the reduced "
                            + "scene extent");
        }
    }

    private static RenderGraph.PassDescription pass(RenderGraph.Description description,
                                                    String name) {
        return description.passes().stream()
                .filter(pass -> pass.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "missing pass " + name + " in the internal temporal source graph"));
    }

    /** Validates the dependency edges that the colour chain declares on surface producers. */
    public void validateSurfaceInputs() {
        if (internalUpscalingAdded) {
            validateInternalUpscalingInputs();
        }
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
        if (ssgi != null) {
            surfaceInputs.validateDependency(PostProcessTargets.SSGI_PASS,
                    requireInput(SurfaceInputs.Channel.DEPTH));
            surfaceInputs.validateDependency(PostProcessTargets.SSGI_PASS,
                    requireInput(SurfaceInputs.Channel.NORMAL));
            surfaceInputs.validateDependency(PostProcessTargets.SSGI_PASS,
                    requireInput(SurfaceInputs.Channel.ROUGHNESS));
            surfaceInputs.validateDependency(PostProcessTargets.SSGI_PASS,
                    requireInput(SurfaceInputs.Channel.ALBEDO));
            if (ssgi.temporalActive()) {
                surfaceInputs.validateDependency(PostProcessTargets.SSGI_PASS,
                        requireInput(SurfaceInputs.Channel.VELOCITY));
                surfaceInputs.validateDependency(PostProcessTargets.SSGI_PASS,
                        requireInput(SurfaceInputs.Channel.PREVIOUS_SURFACE_DEPTH));
                surfaceInputs.validateDependency(PostProcessTargets.SSGI_PASS,
                        requireInput(SurfaceInputs.Channel.VALIDITY));
            }
        }
        if (subsurfaceScattering != null) {
            surfaceInputs.validateDependency(PostProcessTargets.SUBSURFACE_SCATTERING_PASS,
                    requireInput(SurfaceInputs.Channel.DEPTH));
            surfaceInputs.validateDependency(PostProcessTargets.SUBSURFACE_SCATTERING_PASS,
                    requireInput(SurfaceInputs.Channel.NORMAL));
            surfaceInputs.validateDependency(PostProcessTargets.SUBSURFACE_SCATTERING_PASS,
                    requireInput(SurfaceInputs.Channel.SUBSURFACE));
        }
    }

    private SurfaceInputs.Input requireInput(SurfaceInputs.Channel channel) {
        SurfaceInputs.Input input = surfaceInputs.single(channel);
        if (input == null) {
            throw new IllegalStateException("missing " + channel + " surface input for the colour chain");
        }
        return input;
    }

    /**
     * Unique surface producer passes for a consumer's dependency edge.
     *
     * <p>Depth/normal/roughness often share one producer; a RenderGraph pass
     * rejects duplicate dependency declarations, so the colour-chain assembler
     * must add each producer once and must not add the colour producer again.</p>
     */
    static List<String> uniqueSurfaceProducers(String consumerProducer,
                                               List<SurfaceInputs.Input> inputs) {
        Objects.requireNonNull(consumerProducer, "consumerProducer");
        Objects.requireNonNull(inputs, "inputs");
        var producers = new LinkedHashSet<String>();
        for (SurfaceInputs.Input input : inputs) {
            Objects.requireNonNull(input, "surface input");
            String producer = input.producerPass();
            if (!consumerProducer.equals(producer)) {
                producers.add(producer);
            }
        }
        return List.copyOf(producers);
    }

    void addTaaPass(RenderGraph graph, String inputTexture, String colorProducer) {
        temporal.addPass(graph, colorProducer, inputTexture, surfaceInputs, reactiveRequested());
    }

    /**
     * Imports the borrowed low-resolution source and adds the single upscale
     * pass.  The pass executes before every HDR effect and has no in-graph
     * colour dependency: its inputs are borrowed external attachments, so it
     * must be declared before the first downstream pass reads
     * {@link PostProcessTargets#TEMPORAL_UPSCALING_COLOR}.
     */
    private void addTemporalUpscalingPass(RenderGraph graph) {
        TemporalUpscalingSource source = Objects.requireNonNull(temporalUpscalingSource,
                "temporalUpscalingSource");
        var storage = new LinkedHashSet<String>();
        storage.add(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_COLOR);
        graph.importExternalColor(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_COLOR,
                ExternalAttachment.borrowedColor(source.colorTexture(), RenderFormat.RGBA16F,
                        source.width(), source.height()));
        storage.add(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_DEPTH);
        graph.importExternalDepth(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_DEPTH,
                ExternalAttachment.borrowedDepth(source.depthTexture(),
                        RenderFormat.DEPTH_COMPONENT24, source.width(), source.height()));
        if (source.usesVelocity()) {
            storage.add(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_VELOCITY);
            graph.importExternalColor(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_VELOCITY,
                    ExternalAttachment.borrowedColor(source.velocityTexture(),
                            RenderFormat.RG16F, source.width(), source.height()));
            storage.add(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_PREVIOUS_DEPTH);
            graph.importExternalColor(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_PREVIOUS_DEPTH,
                    ExternalAttachment.borrowedColor(source.previousDepthTexture(),
                            RenderFormat.R32F, source.width(), source.height()));
        }
        if (source.hasReactiveMask()) {
            storage.add(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_REACTIVE);
            graph.importExternalColor(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_REACTIVE,
                    ExternalAttachment.borrowedColor(source.reactiveTexture(),
                            RenderFormat.R8, source.width(), source.height()));
        }
        if (source.hasUiMask()) {
            storage.add(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_UI);
            graph.importExternalColor(PostProcessTargets.TEMPORAL_UPSCALING_INPUT_UI,
                    ExternalAttachment.borrowedColor(source.uiMaskTexture(),
                            RenderFormat.R8, source.width(), source.height()));
        }
        graph.addPass(PostProcessTargets.TEMPORAL_UPSCALING_PASS)
                .writeToExternalTarget()
                .noClear()
                .borrowsExternalStorage(storage.toArray(String[]::new))
                .execute((res, cmd) -> {
                    TemporalFramePose frame = framePose;
                    if (frame == null) {
                        throw new IllegalStateException(
                                "temporal upscaling requires a started frame pose");
                    }
                    temporalUpscaling.record(cmd, new TemporalUpscalingInputs(
                            source.colorTexture(),
                            source.depthTexture(),
                            source.velocityTexture(),
                            source.previousDepthTexture(),
                            source.reactiveTexture(),
                            source.uiMaskTexture(),
                            source.width(),
                            source.height(),
                            frame.jitterUvX(),
                            frame.jitterUvY(),
                            manualExposure,
                            frame.inverseJitteredProjection(),
                            frame.inverseView(),
                            frame.stableViewProjection()));
                });
    }

    /**
     * Publishes the current upscale output slot as a borrowed logical texture.
     * The history write slot alternates after a successful frame, so this runs
     * both at assembly time and in {@link #synchronizeTemporalImports(RenderGraph)}
     * on every frame.
     */
    private void publishTemporalUpscalingOutput(RenderGraph graph) {
        Framebuffer output = temporalUpscaling.outputFramebuffer();
        graph.importExternalColor(PostProcessTargets.TEMPORAL_UPSCALING_COLOR,
                ExternalAttachment.borrowedColor(temporalUpscaling.outputColorTexture(),
                        RenderFormat.RGBA16F, output.width(), output.height()));
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

    /**
     * Adds the caller-recorder VFX composite ahead of the transparent
     * collectors instead of after the fog pass.
     *
     * <p>Weighted OIT and stochastic transparency resolve over the scene colour
     * in place. When one of them is active, the particle draw must be composited
     * before that resolve; otherwise additive particles are added on top of a
     * surface they are geometrically behind. Render3D calls this immediately
     * before the selected collector and updates {@link #sceneColorInput(String,
     * String)} to the collector afterwards. The normal post-fog composite is
     * skipped when this method has been called.</p>
     *
     * @param graph        graph receiving the composite pass
     * @param baseProducer scene-colour producer copied into the composite target
     */
    public void addVfxCompositeBeforeCollectors(RenderGraph graph, String baseProducer) {
        if (hdrVfx == null) {
            throw new IllegalStateException(
                    "an early HDR VFX composite requires a configured recorder");
        }
        addVfxComposite(graph, Objects.requireNonNull(baseProducer, "baseProducer"));
    }

    /**
     * Declares the single VFX composite pass: copy the base colour and opaque
     * depth into the pass-owned target, then hand the target to the caller
     * recorder. Added either by {@link #addVfxCompositeBeforeCollectors} or by
     * the post-fog chain at most once.
     */
    private void addVfxComposite(RenderGraph graph, String baseProducer) {
        if (vfxCompositeAdded) {
            throw new IllegalStateException("the HDR VFX composite pass has already been added");
        }
        final String sourcePass = Objects.requireNonNull(baseProducer, "baseProducer");
        RenderGraph.PassBuilder vfxComposite = graph.addPass(PostProcessTargets.VFX_COMPOSITE_PASS)
                .createColor(PostProcessTargets.VFX_COMPOSITE_COLOR, RenderFormat.RGBA16F)
                .createDepthTexture(PostProcessTargets.VFX_SCENE_DEPTH)
                .noClear()
                .dependsOn(sourcePass);
        if (!PostProcessTargets.GEOMETRY_PASS.equals(sourcePass)) {
            vfxComposite.dependsOn(PostProcessTargets.GEOMETRY_PASS);
        }
        vfxComposite.execute((res, cmd) -> {
            Framebuffer source = res.framebufferOfPass(sourcePass);
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
        vfxCompositeAdded = true;
    }

    private void addHdrFinalPasses(RenderGraph graph) {
        String hdrTexture = sceneColorInput;
        String hdrProducer = sceneColorProducer;

        if (temporalUpscalingSource != null) {
            addTemporalUpscalingPass(graph);
            hdrTexture = PostProcessTargets.TEMPORAL_UPSCALING_COLOR;
            hdrProducer = PostProcessTargets.TEMPORAL_UPSCALING_PASS;
            publishTemporalUpscalingOutput(graph);
        }

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

        if (hdrVfx != null && !vfxCompositeAdded) {
            addVfxComposite(graph, hdrProducer);
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

        if (ssgi != null) {
            final String indirectInput = hdrTexture;
            final String indirectProducer = hdrProducer;
            final boolean temporalSsgi = ssgi.temporalActive();
            SurfaceInputs.Input giDepth = requireInput(SurfaceInputs.Channel.DEPTH);
            SurfaceInputs.Input giNormal = requireInput(SurfaceInputs.Channel.NORMAL);
            SurfaceInputs.Input giRoughness = requireInput(SurfaceInputs.Channel.ROUGHNESS);
            SurfaceInputs.Input giAlbedo = requireInput(SurfaceInputs.Channel.ALBEDO);
            SurfaceInputs.Input giVelocity = temporalSsgi
                    ? requireInput(SurfaceInputs.Channel.VELOCITY) : null;
            SurfaceInputs.Input giPreviousDepth = temporalSsgi
                    ? requireInput(SurfaceInputs.Channel.PREVIOUS_SURFACE_DEPTH) : null;
            SurfaceInputs.Input giValidity = temporalSsgi
                    ? requireInput(SurfaceInputs.Channel.VALIDITY) : null;
            var indirectPass = graph.addPass(PostProcessTargets.SSGI_PASS)
                    .noClear();
            if (temporalSsgi) {
                var target = ssgi.writeFramebuffer();
                graph.importExternalColor(PostProcessTargets.SSGI_COLOR,
                        ExternalAttachment.borrowedColor(ssgi.writeColorTexture(),
                                RenderFormat.RGBA16F, target.width(), target.height()));
                indirectPass.writeToExternalTarget();
            } else {
                indirectPass.createColor(PostProcessTargets.SSGI_COLOR,
                        RenderFormat.RGBA16F);
            }
            indirectPass.dependsOn(indirectProducer);
            var dependencyInputs = new ArrayList<SurfaceInputs.Input>();
            dependencyInputs.add(giDepth);
            dependencyInputs.add(giNormal);
            dependencyInputs.add(giRoughness);
            dependencyInputs.add(giAlbedo);
            if (temporalSsgi) {
                dependencyInputs.add(giVelocity);
                dependencyInputs.add(giPreviousDepth);
                dependencyInputs.add(giValidity);
            }
            for (String producer : uniqueSurfaceProducers(indirectProducer,
                    dependencyInputs)) {
                if (!graph.hasPass(producer)) {
                    throw new IllegalStateException(
                            "screen-space indirect diffuse requires surface producer "
                                    + producer);
                }
                indirectPass.dependsOn(producer);
            }
            indirectPass.execute((res, cmd) -> {
                TemporalFramePose frame = framePose;
                if (frame == null) {
                    throw new IllegalStateException(
                            "screen-space indirect diffuse requires a started frame pose");
                }
                Matrix4f projection = new Matrix4f(frame.inverseJitteredProjection()).invert();
                Matrix4f view = new Matrix4f(frame.inverseView()).invert();
                if (temporalSsgi) {
                    ssgi.recordTemporal(cmd, new SsgiPass.TemporalInputs(
                            res.colorAttachment(indirectInput),
                            surfaceInputs.texture(res, giDepth),
                            surfaceInputs.texture(res, giNormal),
                            surfaceInputs.texture(res, giRoughness),
                            surfaceInputs.texture(res, giAlbedo),
                            surfaceInputs.texture(res, giVelocity),
                            surfaceInputs.texture(res, giPreviousDepth),
                            surfaceInputs.texture(res, giValidity),
                            frame.inverseJitteredProjection(), projection, view,
                            ssgiEnvironment, graph.width(), graph.height(),
                            frame.jitterUvX(),
                            frame.jitterUvY()));
                } else {
                    ssgi.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(indirectInput),
                            surfaceInputs.texture(res, giDepth),
                            surfaceInputs.texture(res, giNormal),
                            surfaceInputs.texture(res, giRoughness),
                            surfaceInputs.texture(res, giAlbedo),
                            frame.inverseJitteredProjection(), projection,
                            view, ssgiEnvironment, graph.width(), graph.height());
                }
            });
            hdrTexture = PostProcessTargets.SSGI_COLOR;
            hdrProducer = PostProcessTargets.SSGI_PASS;
        }

        if (subsurfaceScattering != null) {
            final String diffusionInput = hdrTexture;
            final String diffusionProducer = hdrProducer;
            SurfaceInputs.Input diffusionDepth = requireInput(SurfaceInputs.Channel.DEPTH);
            SurfaceInputs.Input diffusionNormal = requireInput(SurfaceInputs.Channel.NORMAL);
            SurfaceInputs.Input diffusionMask = requireInput(SurfaceInputs.Channel.SUBSURFACE);
            var diffusionPass = graph.addPass(PostProcessTargets.SUBSURFACE_SCATTERING_PASS)
                    .createColor(PostProcessTargets.SUBSURFACE_SCATTERING_COLOR,
                            RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(diffusionProducer);
            for (String producer : uniqueSurfaceProducers(diffusionProducer,
                    List.of(diffusionDepth, diffusionNormal, diffusionMask))) {
                if (!graph.hasPass(producer)) {
                    throw new IllegalStateException(
                            "screen-space subsurface scattering requires surface producer "
                                    + producer);
                }
                diffusionPass.dependsOn(producer);
            }
            diffusionPass.execute((res, cmd) -> {
                TemporalFramePose frame = framePose;
                if (frame == null) {
                    throw new IllegalStateException(
                            "screen-space subsurface scattering requires a started frame pose");
                }
                VariableRateShadingPass shading = variableRateShading;
                boolean shadingBegan = false;
                if (shading != null) {
                    shading.recordBegin(cmd);
                    shadingBegan = true;
                }
                try {
                    subsurfaceScattering.recordIntoCurrentTarget(cmd,
                            res.colorAttachment(diffusionInput),
                            surfaceInputs.texture(res, diffusionDepth),
                            surfaceInputs.texture(res, diffusionNormal),
                            surfaceInputs.texture(res, diffusionMask),
                            frame.inverseJitteredProjection(), frame.inverseView(),
                            graph.width(), graph.height());
                } finally {
                    if (shadingBegan) {
                        shading.recordEnd(cmd);
                    }
                }
            });
            hdrTexture = PostProcessTargets.SUBSURFACE_SCATTERING_COLOR;
            hdrProducer = PostProcessTargets.SUBSURFACE_SCATTERING_PASS;
        }

        String reflectionBaseTexture = null;
        String reflectionBaseProducer = null;
        if (ssr != null) {
            final String reflectionInput = hdrTexture;
            final String reflectionProducer = hdrProducer;
            reflectionBaseTexture = reflectionInput;
            reflectionBaseProducer = reflectionProducer;
            SurfaceInputs.Input ssrDepth = requireInput(SurfaceInputs.Channel.DEPTH);
            SurfaceInputs.Input ssrNormal = requireInput(SurfaceInputs.Channel.NORMAL);
            SurfaceInputs.Input ssrRoughness = requireInput(SurfaceInputs.Channel.ROUGHNESS);
            var ssrPass = graph.addPass(PostProcessTargets.SSR_PASS)
                    .createColor(PostProcessTargets.SSR_COLOR, RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(reflectionProducer);
            for (String producer : uniqueSurfaceProducers(reflectionProducer,
                    List.of(ssrDepth, ssrNormal, ssrRoughness))) {
                if (!graph.hasPass(producer)) {
                    throw new IllegalStateException(
                            "screen-space reflections require surface producer "
                                    + producer);
                }
                ssrPass.dependsOn(producer);
            }
            ssrPass.execute((res, cmd) -> {
                TemporalFramePose frame = framePose;
                if (frame == null) {
                    throw new IllegalStateException(
                            "screen-space reflections require a started frame pose");
                }
                Matrix4f view = new Matrix4f(frame.inverseView()).invert();
                // Depth is reconstructed from the jittered raster, so the ray/sample
                // projection must be the inverse of that same inverse projection
                // composed with the view. Using the inverse projection alone would
                // treat world positions as view positions; the stable VP would shift
                // every projected tap by the jitter.
                Matrix4f jitteredViewProjection =
                        new Matrix4f(frame.inverseJitteredProjection()).invert().mul(view);
                ssr.recordIntoCurrentTarget(cmd,
                        res.colorAttachment(reflectionInput),
                        surfaceInputs.texture(res, ssrDepth),
                        surfaceInputs.texture(res, ssrNormal),
                        surfaceInputs.texture(res, ssrRoughness),
                        frame.inverseJitteredProjection(), frame.inverseView(), view,
                        jitteredViewProjection,
                        ssrEnvironment,
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
            // Velocity and previous depth are the frozen shared motion pair;
            // use them when the topology produced them, otherwise fall back to
            // the previous-stable-view-projection contract.
            SurfaceInputs.Input denoiseVelocity =
                    surfaceInputs.single(SurfaceInputs.Channel.VELOCITY);
            SurfaceInputs.Input denoisePreviousDepth =
                    surfaceInputs.single(SurfaceInputs.Channel.PREVIOUS_SURFACE_DEPTH);
            final boolean denoiseUsesVelocity =
                    denoiseVelocity != null && denoisePreviousDepth != null;
            var denoiseTarget = temporalDenoise.writeFramebuffer();
            graph.importExternalColor(PostProcessTargets.TEMPORAL_DENOISE_COLOR,
                    ExternalAttachment.borrowedColor(denoiseTarget.colorAttachment(0),
                            RenderFormat.RGBA16F, denoiseTarget.width(),
                            denoiseTarget.height()));
            var denoisePass = graph.addPass(PostProcessTargets.TEMPORAL_DENOISE_PASS)
                    .writeToExternalTarget()
                    .noClear()
                    .dependsOn(denoiseProducer);
            List<SurfaceInputs.Input> denoiseInputs = new ArrayList<>();
            denoiseInputs.add(denoiseDepth);
            denoiseInputs.add(denoiseNormal);
            if (denoiseUsesVelocity) {
                denoiseInputs.add(denoiseVelocity);
                denoiseInputs.add(denoisePreviousDepth);
            }
            for (String producer : uniqueSurfaceProducers(denoiseProducer,
                    denoiseInputs)) {
                if (!graph.hasPass(producer)) {
                    throw new IllegalStateException(
                            "temporal denoising requires surface producer "
                                    + producer);
                }
                denoisePass.dependsOn(producer);
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
                        denoiseUsesVelocity
                                ? surfaceInputs.texture(res, denoiseVelocity) : 0,
                        denoiseUsesVelocity
                                ? surfaceInputs.texture(res, denoisePreviousDepth) : 0,
                        frame.jitterUvX(), frame.jitterUvY(),
                        frame.inverseJitteredProjection(),
                        frame.inverseView(),
                        frame.stableViewProjection()));
            });
            hdrTexture = PostProcessTargets.TEMPORAL_DENOISE_COLOR;
            hdrProducer = PostProcessTargets.TEMPORAL_DENOISE_PASS;

            // The denoiser resolves the reflection contribution, not scene
            // colour. Composite it over the pre-SSR base with the same
            // `base + contribution` equation the spatial SSR pass uses; a
            // zero-confidence contribution is exactly zero, so base-colour
            // regions stay byte-identical.
            if (reflectionBaseTexture == null) {
                throw new IllegalStateException(
                        "temporal denoising requires an SSR reflection signal base colour");
            }
            final String compositeBase = reflectionBaseTexture;
            final String compositeBaseProducer = reflectionBaseProducer;
            var compositePass = graph.addPass(PostProcessTargets.TEMPORAL_DENOISE_COMPOSITE_PASS)
                    .createColor(PostProcessTargets.TEMPORAL_DENOISE_COMPOSITE_COLOR,
                            RenderFormat.RGBA16F)
                    .noClear()
                    .dependsOn(PostProcessTargets.TEMPORAL_DENOISE_PASS)
                    .dependsOn(compositeBaseProducer);
            compositePass.execute((res, cmd) -> temporalDenoise.recordComposite(cmd,
                    res.colorAttachment(compositeBase),
                    temporalDenoise.writeColorTexture(), graph.width(), graph.height()));
            hdrTexture = PostProcessTargets.TEMPORAL_DENOISE_COMPOSITE_COLOR;
            hdrProducer = PostProcessTargets.TEMPORAL_DENOISE_COMPOSITE_PASS;
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

    /** @return the requested selective shading-rate settings, active or not */
    public VariableRateShadingSettings variableRateShadingRequest() {
        return effects.variableRateShading();
    }

    /**
     * @return whether the NV per-tile shading-rate scope is actually applied to
     *         the declared consumer; false for a disabled request or a fallback
     */
    public boolean variableRateShadingActive() {
        return variableRateShading != null;
    }

    /**
     * Effective status of the shading-rate request.
     *
     * <p>Possible prefixes are {@code disabled:}, {@code active:} and
     * {@code fallback:}.  A fallback keeps the consumer at full rate and is
     * never evidence that variable rate shading is implemented on this driver.</p>
     *
     * @return a stable one-line status suitable for logs and acceptance reports
     */
    public String variableRateShadingStatus() {
        return variableRateShadingStatus;
    }

    private void recordFinalPass(String passName) {
        if (finalPassName != null) {
            throw new IllegalStateException("Final backbuffer pass has already been recorded: "
                    + finalPassName);
        }
        finalPassName = Objects.requireNonNull(passName, "passName");
    }

    /**
     * Starts a frame whose reduced scene raster carries no nonzero jitter.
     *
     * <p>This is the source-compatible entry point; it is correct for every
     * caller-source upscaling chain (the caller writes the jitter into the
     * pose) and for the internal reduced path when the sequence is disabled.</p>
     */
    public void beginFrame(TemporalFramePose pose) {
        beginFrame(pose, 0.0f, 0.0f);
    }

    /**
     * Starts the frame: clamps the delta, prepares the temporal write slot and
     * stores the camera pose used by later pass declarations.
     *
     * <p>{@code reducedRasterJitterUvX/Y} is the internal reduced-scene jitter
     * that the R raster passes actually rendered with: the geometry
     * displacement in <b>reduced-raster normalized UV</b> produced by the
     * jittered projection (the same value the R temporal frame pose stores in
     * {@code TemporalFrameState.FrameParameters.jitterUvX/Y}, and the value the
     * upscaler shader adds to the stable output UV as {@code uCurrentJitter}).
     * It is only consumed by the internal reconstruction pass added through
     * {@link #addInternalTemporalUpscalingPass}; every other pass keeps using
     * the stable output pose.  It must be zero when no internal reduced-scene
     * upscaler exists.</p>
     */
    public void beginFrame(TemporalFramePose pose, float reducedRasterJitterUvX,
                           float reducedRasterJitterUvY) {
        Objects.requireNonNull(pose, "pose");
        if (!Float.isFinite(reducedRasterJitterUvX)
                || !Float.isFinite(reducedRasterJitterUvY)) {
            throw new IllegalArgumentException(
                    "the internal reduced-raster jitter must be finite");
        }
        if (!internalTemporalUpscaling
                && (reducedRasterJitterUvX != 0.0f || reducedRasterJitterUvY != 0.0f)) {
            throw new IllegalArgumentException(
                    "a nonzero reduced-raster jitter requires the internal reduced-scene "
                            + "upscaler");
        }
        internalRasterJitterUvX = reducedRasterJitterUvX;
        internalRasterJitterUvY = reducedRasterJitterUvY;
        framePose = pose;
        deltaSeconds = Math.min(pose.deltaSeconds(), 0.1f);
        if (temporal != null) {
            temporal.beginFrame(pose);
        }
        if (ssgi != null) {
            ssgi.beginFrame();
        }
        if (temporalDenoise != null) {
            temporalDenoise.beginFrame();
        }
        if (temporalUpscaling != null) {
            temporalUpscaling.beginFrame();
        }
    }

    /**
     * @return the phase of the next internal reduced-raster jitter sample, or
     *         {@code -1} when this chain was not assembled for the internal
     *         reduced-scene source
     *
     * <p>The phase advances only through {@link #frameSucceeded()} and resets
     * to {@code 0} on construction and on every
     * {@link #invalidateTemporalHistory(TaaInvalidationReason)}, so the
     * sequence restarts together with the upscaling history it belongs to.</p>
     */
    public int internalRasterJitterPhase() {
        return internalTemporalUpscaling ? internalRasterJitterPhase : -1;
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
        if (ssgi != null) {
            ssgi.frameSucceeded();
        }
        if (temporalDenoise != null) {
            temporalDenoise.frameSucceeded();
        }
        if (temporalUpscaling != null) {
            temporalUpscaling.frameSucceeded();
        }
        if (internalTemporalUpscaling) {
            // The sequence advances with committed history only, so a failed
            // frame retries the same phase and a generation restart begins at 0.
            // A history invalidation sets the counter back to zero and therefore
            // takes effect on the next frame's capture.
            // The modulus mirrors TemporalJitter.REDUCED_PHASE_COUNT; the
            // postprocess subsystem must not import render3d, and the pattern
            // itself (not the wrap) stays owned there.
            internalRasterJitterPhase = (internalRasterJitterPhase + 1)
                    % INTERNAL_RASTER_JITTER_PHASE_COUNT;
        }
    }

    public void frameFailed() {
        if (autoExposure != null) {
            autoExposure.frameFailed();
        }
        if (temporal != null) {
            temporal.frameFailed();
        }
        if (ssgi != null) {
            ssgi.frameFailed();
        }
        if (temporalDenoise != null) {
            temporalDenoise.frameFailed();
        }
        if (temporalUpscaling != null) {
            temporalUpscaling.frameFailed();
        }
    }

    public void invalidateTemporalHistory() {
        invalidateTemporalHistory(TaaInvalidationReason.EXPLICIT_RESET);
    }

    public void invalidateTemporalHistory(TaaInvalidationReason reason) {
        if (temporal != null) {
            temporal.invalidate(reason);
        }
        if (ssgi != null) {
            ssgi.invalidate();
        }
        if (temporalDenoise != null) {
            temporalDenoise.invalidate();
        }
        if (temporalUpscaling != null) {
            temporalUpscaling.invalidate();
        }
        if (internalTemporalUpscaling) {
            // The sequence belongs to the invalidated history: the next frame is
            // a history-invalid current sample at phase 0.
            internalRasterJitterPhase = 0;
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

    /** @return true when the bound temporal-upscaling owner has a committed history */
    public boolean temporalUpscalingHistoryValid() {
        return temporalUpscaling != null && temporalUpscaling.historyValid();
    }

    /**
     * Publishes this frame's history write colour as a borrowed logical texture
     * for passes that run after the TAA resolve.
     */
    public void synchronizeTemporalImports(RenderGraph graph) {
        if (temporal != null) {
            temporal.synchronizeImports(graph);
        }
        if (ssgi != null && ssgi.temporalActive()) {
            var target = ssgi.writeFramebuffer();
            graph.importExternalColor(PostProcessTargets.SSGI_COLOR,
                    ExternalAttachment.borrowedColor(ssgi.writeColorTexture(),
                            RenderFormat.RGBA16F, target.width(), target.height()));
        }
        if (temporalDenoise != null) {
            var target = temporalDenoise.writeFramebuffer();
            graph.importExternalColor(PostProcessTargets.TEMPORAL_DENOISE_COLOR,
                    ExternalAttachment.borrowedColor(target.colorAttachment(0),
                            RenderFormat.RGBA16F, target.width(), target.height()));
        }
        if (temporalUpscaling != null) {
            publishTemporalUpscalingOutput(graph);
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
        if (temporalUpscaling != null) {
            // The low-resolution source is borrowed and generation-stable; an
            // in-place colour-chain resize would pair it with a different
            // output extent.  The owner must build a candidate generation with
            // the matching low-resolution target instead.
            throw new IllegalStateException(
                    "temporal upscaling requires a candidate generation rebuild instead of an "
                            + "in-place colour-chain resize");
        }
        TaaHistory.ResizeCandidate taa = temporal == null
                ? null : temporal.prepareResize(width, height);
        SsgiPass.ResizeCandidate ssgiCandidate = null;
        TemporalDenoisePass.ResizeCandidate denoiseCandidate = null;
        VariableRateShadingPass.ResizeCandidate shadingCandidate = null;
        try {
            ssgiCandidate = ssgi == null ? null : ssgi.prepareResize(width, height);
            denoiseCandidate = temporalDenoise == null
                    ? null : temporalDenoise.prepareResize(width, height);
            shadingCandidate = variableRateShading == null
                    ? null : variableRateShading.prepareResize(width, height);
            return new ResizeCandidate(this, taa, ssgiCandidate, denoiseCandidate,
                    shadingCandidate);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(taa, failure);
            closeAfterFailure(ssgiCandidate, failure);
            closeAfterFailure(denoiseCandidate, failure);
            closeAfterFailure(shadingCandidate, failure);
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
        private final SsgiPass.ResizeCandidate ssgiCandidate;
        private final TemporalDenoisePass.ResizeCandidate denoiseCandidate;
        private final VariableRateShadingPass.ResizeCandidate shadingCandidate;
        private boolean committed;
        private boolean closed;

        private ResizeCandidate(PostProcessPipeline owner,
                                TaaHistory.ResizeCandidate taaCandidate,
                                SsgiPass.ResizeCandidate ssgiCandidate,
                                TemporalDenoisePass.ResizeCandidate denoiseCandidate,
                                VariableRateShadingPass.ResizeCandidate shadingCandidate) {
            this.owner = owner;
            this.taaCandidate = taaCandidate;
            this.ssgiCandidate = ssgiCandidate;
            this.denoiseCandidate = denoiseCandidate;
            this.shadingCandidate = shadingCandidate;
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
            if (ssgiCandidate != null) {
                owner.ssgi.commitResize(ssgiCandidate);
            }
            if (denoiseCandidate != null) {
                owner.temporalDenoise.commitResize(denoiseCandidate);
            }
            if (shadingCandidate != null) {
                owner.variableRateShading.commitResize(shadingCandidate);
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
            if (ssgiCandidate != null) {
                try {
                    ssgiCandidate.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
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
            if (shadingCandidate != null) {
                try {
                    shadingCandidate.close();
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
