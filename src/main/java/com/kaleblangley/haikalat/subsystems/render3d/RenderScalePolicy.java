package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;

/**
 * Fail-closed policy for the advanced {@link RenderSettings#renderScale()}
 * request.
 *
 * <p>The reduced-resolution path rasterises the scene colour/depth at
 * {@code max(1, round(target * scale))} and reconstructs the target extent with
 * the internal temporal upscaler before the HDR colour chain.  Compositions
 * below still assume full-resolution scene data, so they would produce wrong
 * pixels if they ran against the reduced raster.  Rather than silently dropping
 * these features or rendering them against mismatched extents, each requested
 * composition is rejected while {@code renderScale < 1.0} with its own
 * documented reason code.  At {@code renderScale == 1.0} nothing is rejected:
 * every request keeps today's exact behaviour.</p>
 *
 * <p>The same codes and messages are used by the settings policy
 * ({@link RenderFeaturePolicy}), the topology policy
 * ({@link PipelineFeaturePolicy}) and the legacy
 * {@code RenderPipeline.validateCompositionDependencies()} entry, so all three
 * paths fail closed before a candidate generation is prepared.  The full
 * feature-to-code matrix is documented in
 * {@code docs/planning/v0.28.1-s4-temporal-upscaling.md}.</p>
 */
final class RenderScalePolicy {
    /** The exact full-resolution no-op ratio. */
    static final float FULL_RESOLUTION = RenderSettings.MAX_RENDER_SCALE;

    /**
     * Every composition that assumes full-resolution scene data.
     *
     * <p>The {@link #code()} string is the stable, documented rejection reason.
     * It appears in the exception message and must stay distinct per feature,
     * so a host can tell which request was rejected without parsing prose.</p>
     */
    enum Feature {
        MSAA("msaa", "MSAA multisample rasterisation"),
        TAA("taa", "TAA jitter and history"),
        SSR("ssr", "screen-space reflections"),
        SSGI("ssgi", "screen-space indirect diffuse"),
        GTAO("gtao", "ground-truth ambient occlusion"),
        TEMPORAL_DENOISE("temporal-denoise", "the temporal denoiser"),
        DEPTH_OF_FIELD("depth-of-field", "depth of field"),
        MOTION_BLUR("motion-blur", "motion blur"),
        SUBSURFACE_SCATTERING("subsurface-scattering", "screen-space subsurface scattering"),
        PROJECTED_DECALS("projected-decals", "the projected-decal batch"),
        WATER_SURFACE("water-surface", "the finite water surface"),
        WATER_REFRACTION("water-refraction", "screen-space water refraction"),
        UNDERWATER_VOLUME("underwater-volume", "the bounded underwater volume"),
        CONTACT_SHADOWS("contact-shadows", "screen-space contact shadows"),
        CAUSTICS("caustics", "water caustics"),
        PLANAR_REFLECTIONS("planar-reflections", "planar reflections"),
        DDGI("ddgi", "DDGI probe lighting"),
        VOXEL_CONE_TRACING("voxel-cone-tracing", "voxel cone tracing"),
        HAIR_STRANDS("hair-strands", "hair strands"),
        VOLUMETRIC_CLOUDS("volumetric-clouds", "the volumetric cloud layer"),
        VOLUMETRIC_SMOKE("volumetric-smoke", "the smoke simulation/volume"),
        VOLUMETRIC_PARTICLES("volumetric-particles", "the volumetric particle medium"),
        VOLUMETRIC_FOG("volumetric-fog", "volumetric fog"),
        WEIGHTED_OIT("weighted-oit", "weighted OIT"),
        STOCHASTIC_TRANSPARENCY("stochastic-transparency", "stochastic transparency"),
        ADAPTIVE_SAMPLING("adaptive-sampling", "adaptive SSR sampling"),
        VARIABLE_RATE_SHADING("vrs", "variable rate shading"),
        BLOOM("bloom", "the bloom chain"),
        AUTO_EXPOSURE("auto-exposure", "automatic exposure"),
        TEMPORAL_UPSCALING_SOURCE("temporal-upscaling-source", "a caller-supplied TemporalUpscalingSource"),
        LOW_RESOLUTION_LDR("low-resolution-ldr",
                "a non-HDR (sRGB) scene raster (the internal temporal source requires RGBA16F)"),
        EMBEDDED_TARGET("embedded-target", "embedded/borrowed host targets");

        private final String code;
        private final String label;

        Feature(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /** @return the stable documented reason code used in the rejection message */
        String code() {
            return code;
        }

        /** @return the feature name used in the rejection message */
        String label() {
            return label;
        }
    }

    private RenderScalePolicy() {
    }

    /**
     * @return the rejection message, or {@code null} when the request is a
     *         full-resolution no-op and must not be rejected
     */
    static String rejection(float renderScale, Feature feature) {
        if (renderScale >= FULL_RESOLUTION) {
            return null;
        }
        return "renderScale " + renderScale + " rejects " + feature.code() + ": "
                + feature.label() + " assumes full-resolution scene data and has no "
                + "reduced-resolution composition yet; set renderScale to 1.0";
    }

    /**
     * Rejects one requested composition when the scene is not full resolution.
     *
     * @throws IllegalArgumentException with the feature's distinct reason code
     */
    static void reject(float renderScale, Feature feature, boolean requested) {
        if (!requested) {
            return;
        }
        String rejection = rejection(renderScale, feature);
        if (rejection != null) {
            throw new IllegalArgumentException(rejection);
        }
    }

    /**
     * Assembly-time variant of {@link #reject} using the topology policy's
     * existing {@link IllegalStateException} contract.
     */
    static void rejectTopology(float renderScale, Feature feature, boolean requested) {
        if (!requested) {
            return;
        }
        String rejection = rejection(renderScale, feature);
        if (rejection != null) {
            throw new IllegalStateException(rejection);
        }
    }

    /**
     * Rejects the render-level and post-process compositions that assume
     * full-resolution scene colour/depth/normal/material data.  This is the
     * shared settings gate for the unified feature policy and the legacy
     * pipeline path.
     */
    static void rejectSettingsCompositions(RenderSettings render, PostProcessSettings effects) {
        float scale = render.renderScale();
        if (scale >= FULL_RESOLUTION) {
            return;
        }
        // The internal temporal source samples a linear RGBA16F scene colour and
        // writes a linear RGBA16F history.  An LDR chain rasterises an sRGB
        // scene colour and presents without tone mapping, so the reconstruction
        // cannot preserve the colour encoding; fail closed instead of silently
        // changing LDR output.
        reject(scale, Feature.LOW_RESOLUTION_LDR, !render.hdrEnabled());
        reject(scale, Feature.MSAA, render.antiAliasingMode() == AntiAliasingMode.MSAA);
        reject(scale, Feature.TAA, render.antiAliasingMode() == AntiAliasingMode.TAA);
        reject(scale, Feature.BLOOM, render.bloomSettings().enabled());
        reject(scale, Feature.AUTO_EXPOSURE, render.exposureMode() == ExposureMode.AUTO);
        reject(scale, Feature.SSR, effects.ssr().active());
        reject(scale, Feature.SSGI, effects.ssgi().active());
        reject(scale, Feature.GTAO, effects.gtao().enabled());
        reject(scale, Feature.TEMPORAL_DENOISE, effects.temporalDenoise().active());
        reject(scale, Feature.DEPTH_OF_FIELD, effects.depthOfField().active());
        reject(scale, Feature.MOTION_BLUR, effects.motionBlur().active());
        reject(scale, Feature.SUBSURFACE_SCATTERING,
                effects.subsurfaceScattering().screenSpaceActive());
        reject(scale, Feature.ADAPTIVE_SAMPLING, effects.adaptiveSampling().active());
        reject(scale, Feature.VARIABLE_RATE_SHADING, effects.variableRateShading().active());
    }
}
