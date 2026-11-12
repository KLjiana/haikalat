package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure dependency and effective-state policy for the unified feature request.
 *
 * <p>Inputs are only configuration values and immutable scene facts; no GL
 * resource is touched and no topology is allocated here.  Illegal combinations
 * fail the whole request before candidate preparation.</p>
 */
final class RenderFeaturePolicy {
    /** Current scene and external-resource facts used to resolve a request. */
    record Facts(boolean pbrMaterials, boolean directionalCaster, boolean pointCaster,
                 boolean spotCaster, boolean pbrEnvironmentAvailable,
                 boolean hdrVfxRecorderPresent, boolean hdrVfxFogAware,
                 boolean hdrVfxFogOptOut, boolean previewSourceSelected) {
    }

    /** Effective switches and their observed participation. */
    record Resolved(boolean masterEnabled, boolean directLighting,
                    boolean imageBasedLighting, boolean directionalShadows,
                    boolean pointShadows, boolean spotShadows, boolean ambientOcclusion,
                    boolean volumetricFog, boolean sky, boolean bloom, boolean autoExposure,
                    boolean analyticFog, boolean antiAliasing, boolean toneMapping,
                    boolean colorGrading, boolean hdrVfx, boolean preview,
                    boolean waterSurface, boolean volumetricParticles,
                    boolean stochasticTransparency,
                    boolean volumetricClouds, boolean volumetricSmoke,
                    List<RenderFeatureActivity> activities) {
        Resolved {
            activities = List.copyOf(activities);
        }
    }

    private RenderFeaturePolicy() {
    }

    static Resolved resolve(RenderFeatureSettings settings, Facts facts) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(facts, "facts");
        validate(settings, facts);

        boolean master = settings.enabled();
        boolean direct = settings.directLighting();
        boolean ibl = settings.imageBasedLighting() && facts.pbrMaterials()
                && facts.pbrEnvironmentAvailable();
        boolean directionalShadows = settings.directionalShadows() && facts.directionalCaster();
        boolean pointShadows = settings.pointShadows() && facts.pointCaster();
        boolean spotShadows = settings.spotShadows() && facts.spotCaster();
        boolean ao = settings.postProcess().gtao().enabled();
        boolean volumetric = settings.volumetricFog().enabled();
        boolean sky = settings.outdoorEnvironment().enabled();
        boolean bloom = settings.render().bloomSettings().enabled();
        boolean autoExposure = settings.render().hdrEnabled()
                && settings.render().exposureMode()
                == com.kaleblangley.haikalat.runtime.ExposureMode.AUTO;
        boolean analyticFog = settings.postProcess().fog().enabled();
        boolean antiAliasing = settings.render().antiAliasingMode()
                != com.kaleblangley.haikalat.core.AntiAliasingMode.NONE;
        boolean toneMapping = settings.render().toneMappingMode()
                != com.kaleblangley.haikalat.runtime.ToneMappingMode.NONE;
        boolean colorGrading = settings.postProcess().colorGrading().enabled();
        boolean hdrVfx = settings.hdrVfx() && facts.hdrVfxRecorderPresent();
        boolean preview = settings.preview() && facts.previewSourceSelected();
        boolean waterSurface = settings.waterSurface().active();
        boolean particlesEligible = PipelineTopology.volumetricParticleCompositionActive(
                settings.render(), settings.volumetricFog(), settings.volumetricParticles());
        boolean stochasticEligible = PipelineTopology.stochasticTransparencyCompositionActive(
                settings.render(), settings.stochasticTransparency());
        boolean volumetricParticles = particlesEligible;
        boolean stochasticTransparency = stochasticEligible;
        boolean cloudsEligible = PipelineTopology.volumetricCloudCompositionActive(
                settings.render(), settings.volumetricClouds(), cloudShadowsActive(settings, facts),
                settings.directionalShadowMap().settings().cloudShadows());
        boolean smokeEligible = PipelineTopology.volumetricSmokeCompositionActive(
                settings.render(), settings.smokeSimulation(), settings.smokeRender());
        RenderFeatureReason particleReason = !settings.volumetricParticles().active()
                ? RenderFeatureReason.DISABLED
                : !particlesEligible ? RenderFeatureReason.UNAVAILABLE
                : RenderFeatureReason.DISABLED;
        RenderFeatureReason stochasticReason = !settings.stochasticTransparency().active()
                ? RenderFeatureReason.DISABLED
                : !stochasticEligible ? RenderFeatureReason.UNAVAILABLE
                : RenderFeatureReason.DISABLED;
        RenderFeatureReason cloudReason = !settings.volumetricClouds().active()
                ? RenderFeatureReason.DISABLED
                : !cloudsEligible ? RenderFeatureReason.UNAVAILABLE
                : RenderFeatureReason.DISABLED;
        RenderFeatureReason smokeReason = !settings.smokeRender().active()
                ? RenderFeatureReason.DISABLED
                : !smokeEligible ? RenderFeatureReason.UNAVAILABLE
                : RenderFeatureReason.DISABLED;

        List<RenderFeatureActivity> activities = new ArrayList<>();
        activities.add(activity(RenderFeature.MASTER, master,
                master ? RenderFeatureReason.ACTIVE : RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.DIRECT_LIGHTING, master, direct,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.IMAGE_BASED_LIGHTING, master, ibl, iblReason(settings, facts)));
        activities.add(shadowActivity(RenderFeature.SHADOW_DIRECTIONAL, master,
                settings.directionalShadows(), facts.directionalCaster()));
        activities.add(shadowActivity(RenderFeature.SHADOW_POINT, master,
                settings.pointShadows(), facts.pointCaster()));
        activities.add(shadowActivity(RenderFeature.SHADOW_SPOT, master,
                settings.spotShadows(), facts.spotCaster()));
        activities.add(child(RenderFeature.AMBIENT_OCCLUSION, master, ao,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.VOLUMETRIC_FOG, master, volumetric,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.SKY, master, sky, RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.BLOOM, master, bloom, RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.AUTO_EXPOSURE, master, autoExposure,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.ANALYTIC_FOG, master, analyticFog,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.ANTI_ALIASING, master, antiAliasing,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.TONE_MAPPING, master, toneMapping,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.COLOR_GRADING, master, colorGrading,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.HDR_VFX, master, hdrVfx,
                facts.hdrVfxRecorderPresent()
                        ? RenderFeatureReason.DISABLED : RenderFeatureReason.NO_RECORDER));
        activities.add(child(RenderFeature.PREVIEW, master, preview,
                facts.previewSourceSelected()
                        ? RenderFeatureReason.DISABLED : RenderFeatureReason.UNAVAILABLE));
        activities.add(child(RenderFeature.WATER_SURFACE, master, waterSurface,
                RenderFeatureReason.DISABLED));
        activities.add(child(RenderFeature.VOLUMETRIC_PARTICLES, master, volumetricParticles,
                particleReason));
        activities.add(child(RenderFeature.STOCHASTIC_TRANSPARENCY, master,
                stochasticTransparency, stochasticReason));
        activities.add(child(RenderFeature.VOLUMETRIC_CLOUDS, master, cloudsEligible,
                cloudReason));
        activities.add(child(RenderFeature.VOLUMETRIC_SMOKE, master, smokeEligible,
                smokeReason));
        return new Resolved(master, effective(master, direct),
                effective(master, ibl), effective(master, directionalShadows),
                effective(master, pointShadows), effective(master, spotShadows),
                effective(master, ao), effective(master, volumetric), effective(master, sky),
                effective(master, bloom), effective(master, autoExposure),
                effective(master, analyticFog), effective(master, antiAliasing),
                effective(master, toneMapping), effective(master, colorGrading),
                effective(master, hdrVfx), effective(master, preview),
                effective(master, waterSurface), effective(master, volumetricParticles),
                effective(master, stochasticTransparency), effective(master, cloudsEligible),
                effective(master, smokeEligible),
                List.copyOf(activities));
    }

    /**
     * The #10 cloud shadow is one authored field. While it is enabled on the
     * selected directional caster, the visible layer must use the same value
     * instead of silently creating a second cloud field.
     */
    private static boolean cloudShadowsActive(RenderFeatureSettings settings, Facts facts) {
        return settings.directionalShadows() && facts.directionalCaster();
    }

    private static boolean effective(boolean master, boolean requested) {
        return master && requested;
    }

    private static RenderFeatureActivity child(RenderFeature feature, boolean master,
                                               boolean requested, RenderFeatureReason reason) {
        if (!master) {
            return activity(feature, false, RenderFeatureReason.PAUSED_BY_MASTER);
        }
        return requested
                ? activity(feature, true, RenderFeatureReason.ACTIVE)
                : activity(feature, false, reason);
    }

    private static RenderFeatureActivity shadowActivity(RenderFeature feature, boolean master,
                                                        boolean requested, boolean eligible) {
        if (!master) {
            return activity(feature, false, RenderFeatureReason.PAUSED_BY_MASTER);
        }
        if (!requested) {
            return activity(feature, false, RenderFeatureReason.DISABLED);
        }
        return eligible
                ? activity(feature, true, RenderFeatureReason.ACTIVE)
                : activity(feature, false, RenderFeatureReason.NO_ELIGIBLE_LIGHT);
    }

    private static RenderFeatureActivity activity(RenderFeature feature, boolean active,
                                                  RenderFeatureReason reason) {
        return new RenderFeatureActivity(feature, active, reason);
    }

    private static RenderFeatureReason iblReason(RenderFeatureSettings settings, Facts facts) {
        if (!settings.imageBasedLighting()) return RenderFeatureReason.DISABLED;
        if (!facts.pbrMaterials()) return RenderFeatureReason.NO_PBR_MATERIAL;
        if (!facts.pbrEnvironmentAvailable()) return RenderFeatureReason.NO_ENVIRONMENT;
        return RenderFeatureReason.DISABLED;
    }

    private static void validate(RenderFeatureSettings settings, Facts facts) {
        boolean hdr = settings.render().hdrEnabled();
        if (!hdr && (settings.postProcess().colorGrading().enabled()
                || settings.postProcess().fog().enabled()
                || settings.volumetricFog().enabled())) {
            throw new IllegalArgumentException("Color grading and fog require HDR tone mapping");
        }
        if (!hdr && settings.render().bloomSettings().enabled()) {
            throw new IllegalArgumentException("Bloom requires HDR tone mapping");
        }
        if (!hdr && settings.hdrVfx() && facts.hdrVfxRecorderPresent()) {
            throw new IllegalArgumentException("HDR VFX requires HDR tone mapping");
        }
        if (!hdr && facts.pbrMaterials()) {
            throw new IllegalArgumentException("metallic-roughness PBR requires HDR/ACES output");
        }
        if (facts.pbrMaterials() && !facts.pbrEnvironmentAvailable()) {
            throw new IllegalArgumentException(
                    "PBR scene requires an explicit borrowed PbrEnvironment");
        }
        if (settings.postProcess().gtao().enabled() && !facts.pbrMaterials()) {
            throw new IllegalArgumentException(
                    "GTAO requires at least one metallic-roughness PBR material");
        }
        if (settings.postProcess().ssr().active()) {
            if (!hdr) {
                throw new IllegalArgumentException(
                        "screen-space reflections require HDR tone mapping");
            }
            if (!facts.pbrMaterials()) {
                throw new IllegalArgumentException(
                        "screen-space reflections require at least one metallic-roughness PBR material");
            }
            if (!settings.imageBasedLighting()) {
                throw new IllegalArgumentException(
                        "screen-space reflections require image-based lighting for the fallback contract");
            }
        }
        if (settings.directionalShadowMap().settings().contactShadows().active()) {
            if (!settings.directionalShadows() || !facts.directionalCaster()) {
                throw new IllegalArgumentException(
                        "contact shadows require an enabled directional shadow caster");
            }
            if (!facts.pbrMaterials()) {
                throw new IllegalArgumentException(
                        "contact shadows require at least one metallic-roughness PBR material");
            }
        }
        if (settings.directionalShadowMap().settings().cloudShadows().active()) {
            if (!settings.directionalShadows() || !facts.directionalCaster()) {
                throw new IllegalArgumentException(
                        "cloud shadows require an enabled directional shadow caster");
            }
            if (!facts.pbrMaterials()) {
                throw new IllegalArgumentException(
                        "cloud shadows require at least one metallic-roughness PBR material");
            }
        }
        if (settings.directionalShadowMap().settings().pcss().active()) {
            if (!settings.directionalShadows() || !facts.directionalCaster()) {
                throw new IllegalArgumentException(
                        "PCSS requires an enabled directional shadow caster");
            }
            if (!facts.pbrMaterials()) {
                throw new IllegalArgumentException(
                        "PCSS requires at least one metallic-roughness PBR material");
            }
        }
        if (settings.volumetricFog().enabled() && settings.postProcess().fog().enabled()) {
            throw new IllegalArgumentException(
                    "volumetric fog replaces analytic fog; disable the other fog path");
        }
        if (settings.waterSurface().active() && !hdr) {
            throw new IllegalArgumentException(
                    "water surface requires HDR tone mapping for linear absorption composition");
        }
        if (settings.waterSurface().active() && settings.volumetricFog().enabled()) {
            throw new IllegalArgumentException(
                    "water surface does not compose with volumetric fog in this version");
        }
        if (settings.volumetricFog().enabled() && settings.hdrVfx()
                && facts.hdrVfxRecorderPresent() && !facts.hdrVfxFogAware()
                && !facts.hdrVfxFogOptOut()) {
            throw new IllegalArgumentException(
                    "custom HDR VFX must use hdrVfxWithFog or explicitly opt out of fog");
        }
        if (settings.volumetricParticles().active() && settings.volumetricFog().enabled()) {
            // The particle pass consumes the fog-composited colour; declaring the same
            // body as a positive LocalFogVolume would composite that medium twice.
            settings.volumetricParticles().validateFogComposition(settings.volumetricFog());
        }
        if (settings.stochasticTransparency().active() && !hdr) {
            throw new IllegalArgumentException(
                    "stochastic transparency requires HDR tone mapping for linear compositing");
        }
    }
}
