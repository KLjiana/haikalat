package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;

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
                 boolean hdrVfxFogOptOut, boolean previewSourceSelected,
                 boolean directionalLightDownward, boolean weightedOitSetDeclared,
                 boolean transparentSetsOverlap, boolean directionalLightPresent) {
        /** Source-compatible shape used by pre-S3-single callers/tests. */
        Facts(boolean pbrMaterials, boolean directionalCaster, boolean pointCaster,
              boolean spotCaster, boolean pbrEnvironmentAvailable,
              boolean hdrVfxRecorderPresent, boolean hdrVfxFogAware,
              boolean hdrVfxFogOptOut, boolean previewSourceSelected) {
            this(pbrMaterials, directionalCaster, pointCaster, spotCaster,
                    pbrEnvironmentAvailable, hdrVfxRecorderPresent, hdrVfxFogAware,
                    hdrVfxFogOptOut, previewSourceSelected, false, false, false, false);
        }

        /** Source-compatible shape used by the S3 composition callers/tests. */
        Facts(boolean pbrMaterials, boolean directionalCaster, boolean pointCaster,
              boolean spotCaster, boolean pbrEnvironmentAvailable,
              boolean hdrVfxRecorderPresent, boolean hdrVfxFogAware,
              boolean hdrVfxFogOptOut, boolean previewSourceSelected,
              boolean directionalLightDownward, boolean weightedOitSetDeclared,
              boolean transparentSetsOverlap) {
            this(pbrMaterials, directionalCaster, pointCaster, spotCaster,
                    pbrEnvironmentAvailable, hdrVfxRecorderPresent, hdrVfxFogAware,
                    hdrVfxFogOptOut, previewSourceSelected, directionalLightDownward,
                    weightedOitSetDeclared, transparentSetsOverlap, false);
        }

        /** Source-compatible shape used by the direct-light (b3) callers/tests. */
        Facts(boolean pbrMaterials, boolean directionalCaster, boolean pointCaster,
              boolean spotCaster, boolean pbrEnvironmentAvailable,
              boolean hdrVfxRecorderPresent, boolean hdrVfxFogAware,
              boolean hdrVfxFogOptOut, boolean previewSourceSelected,
              boolean directionalLightPresent) {
            this(pbrMaterials, directionalCaster, pointCaster, spotCaster,
                    pbrEnvironmentAvailable, hdrVfxRecorderPresent, hdrVfxFogAware,
                    hdrVfxFogOptOut, previewSourceSelected, false, false, false,
                    directionalLightPresent);
        }
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
                    boolean weightedOit,
                    boolean planarReflections, boolean caustics,
                    boolean ddgi, boolean vct, boolean hairStrands,
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
        boolean weightedOitEligible = PipelineTopology.weightedOitCompositionActive(
                settings.render(), settings.weightedOit(), facts.weightedOitSetDeclared());
        boolean planarEligible = PipelineTopology.planarReflectionCompositionActive(
                settings.render(), settings.planarReflections());
        boolean causticsEligible = PipelineTopology.causticsCompositionActive(
                settings.render(), settings.waterSurface(), settings.caustics(),
                facts.directionalLightDownward());
        boolean cloudsEligible = PipelineTopology.volumetricCloudCompositionActive(
                settings.render(), settings.volumetricClouds(), cloudShadowsActive(settings, facts),
                settings.directionalShadowMap().settings().cloudShadows());
        boolean smokeEligible = PipelineTopology.volumetricSmokeCompositionActive(
                settings.render(), settings.smokeSimulation(), settings.smokeRender());
        boolean ddgiEligible = PipelineTopology.ddgiCompositionActive(settings.render(),
                settings.ddgi(), facts.directionalLightPresent());
        boolean vctEligible = PipelineTopology.vctCompositionActive(settings.render(),
                settings.vct());
        boolean hairEligible = PipelineTopology.hairStrandCompositionActive(settings.render(),
                settings.hairStrands(), facts.directionalLightPresent());
        boolean volumetricParticles = particlesEligible;
        boolean stochasticTransparency = stochasticEligible;
        boolean ddgi = ddgiEligible;
        boolean vct = vctEligible;
        boolean hairStrands = hairEligible;
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
        RenderFeatureReason ddgiReason = ddgiReason(settings.ddgi(), settings.render(),
                facts.directionalLightPresent(), ddgiEligible);
        RenderFeatureReason vctReason = vctReason(settings.vct(), settings.render(), vctEligible);
        RenderFeatureReason hairReason = hairReason(settings.hairStrands(), settings.render(),
                facts.directionalLightPresent(), hairEligible);

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
        activities.add(child(RenderFeature.WEIGHTED_OIT, master, weightedOitEligible,
                oitReason(settings, facts)));
        activities.add(child(RenderFeature.PLANAR_REFLECTIONS, master, planarEligible,
                planarReason(settings)));
        activities.add(child(RenderFeature.CAUSTICS, master, causticsEligible,
                causticsReason(settings, facts)));
        activities.add(child(RenderFeature.DDGI, master, ddgi, ddgiReason));
        activities.add(child(RenderFeature.VOXEL_CONE_TRACING, master, vct, vctReason));
        activities.add(child(RenderFeature.HAIR_STRANDS, master, hairStrands, hairReason));
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
                effective(master, weightedOitEligible),
                effective(master, planarEligible), effective(master, causticsEligible),
                effective(master, ddgi),
                effective(master, vct), effective(master, hairStrands),
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

    private static RenderFeatureReason oitReason(RenderFeatureSettings settings, Facts facts) {
        if (!settings.weightedOit().active()) return RenderFeatureReason.DISABLED;
        com.kaleblangley.haikalat.core.AntiAliasingMode aa =
                settings.render().antiAliasingMode();
        if (!settings.render().hdrEnabled()
                || aa == com.kaleblangley.haikalat.core.AntiAliasingMode.MSAA
                || aa == com.kaleblangley.haikalat.core.AntiAliasingMode.TAA) {
            return RenderFeatureReason.UNAVAILABLE;
        }
        if (!facts.weightedOitSetDeclared()) return RenderFeatureReason.NO_TRANSPARENT_SET;
        return RenderFeatureReason.DISABLED;
    }

    private static RenderFeatureReason planarReason(RenderFeatureSettings settings) {
        if (!settings.planarReflections().active()) return RenderFeatureReason.DISABLED;
        if (!settings.planarReflections().hasPlane()) return RenderFeatureReason.NO_PLANE;
        if (!settings.render().hdrEnabled()) return RenderFeatureReason.UNAVAILABLE;
        return RenderFeatureReason.DISABLED;
    }

    private static RenderFeatureReason causticsReason(RenderFeatureSettings settings,
                                                      Facts facts) {
        if (!settings.caustics().active()) return RenderFeatureReason.DISABLED;
        if (!settings.waterSurface().active()) return RenderFeatureReason.NO_WATER;
        if (!settings.render().hdrEnabled()) return RenderFeatureReason.UNAVAILABLE;
        if (settings.render().antiAliasingMode()
                == com.kaleblangley.haikalat.core.AntiAliasingMode.MSAA) {
            return RenderFeatureReason.UNAVAILABLE;
        }
        if (!facts.directionalLightDownward()) return RenderFeatureReason.NO_ELIGIBLE_LIGHT;
        return RenderFeatureReason.DISABLED;
    }

    private static RenderFeatureReason ddgiReason(DdgiSettings ddgi,
                                                  com.kaleblangley.haikalat.runtime.RenderSettings render,
                                                  boolean directionalLightPresent,
                                                  boolean eligible) {
        if (!ddgi.active()) return RenderFeatureReason.DISABLED;
        if (!render.hdrEnabled()) return RenderFeatureReason.UNAVAILABLE;
        if (!directionalLightPresent) return RenderFeatureReason.NO_ELIGIBLE_LIGHT;
        return eligible ? RenderFeatureReason.DISABLED : RenderFeatureReason.UNAVAILABLE;
    }

    private static RenderFeatureReason vctReason(VctSettings vct,
                                                 com.kaleblangley.haikalat.runtime.RenderSettings render,
                                                 boolean eligible) {
        if (!vct.enabled()) return RenderFeatureReason.DISABLED;
        if (!render.hdrEnabled()) return RenderFeatureReason.UNAVAILABLE;
        return eligible ? RenderFeatureReason.DISABLED : RenderFeatureReason.UNAVAILABLE;
    }

    private static RenderFeatureReason hairReason(HairStrandSettings hair,
                                                  com.kaleblangley.haikalat.runtime.RenderSettings render,
                                                  boolean directionalLightPresent,
                                                  boolean eligible) {
        if (!hair.active()) return RenderFeatureReason.DISABLED;
        if (!render.hdrEnabled()) return RenderFeatureReason.UNAVAILABLE;
        if (!directionalLightPresent) return RenderFeatureReason.NO_ELIGIBLE_LIGHT;
        return eligible ? RenderFeatureReason.DISABLED : RenderFeatureReason.UNAVAILABLE;
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
        if (settings.weightedOit().active() && !hdr) {
            throw new IllegalArgumentException(
                    "weighted OIT requires HDR tone mapping for linear compositing");
        }
        if (settings.planarReflections().active() && !hdr) {
            throw new IllegalArgumentException(
                    "planar reflections require HDR tone mapping");
        }
        if (settings.caustics().active()) {
            if (!settings.waterSurface().active()) {
                throw new IllegalArgumentException(
                        "caustics require an active finite water surface");
            }
            if (!hdr) {
                throw new IllegalArgumentException(
                        "caustics require HDR tone mapping for additive linear composition");
            }
        }
        if (settings.weightedOit().active() && settings.stochasticTransparency().active()
                && facts.transparentSetsOverlap()) {
            throw new IllegalArgumentException(
                    "weighted OIT and stochastic transparency claim the same transparent "
                            + "surface set; disable one strategy for that set");
        }
        if (settings.ddgi().active() && settings.vct().enabled()) {
            // Both techniques are independent indirect-light owners; running them
            // together would pay twice and double-count the same diffuse energy.
            throw new IllegalArgumentException(
                    "DDGI and voxel cone tracing are mutually exclusive in this version; "
                            + "select one indirect-light technique");
        }
        if (settings.ddgi().active()) {
            if (!hdr) {
                throw new IllegalArgumentException(
                        "DDGI requires HDR tone mapping for linear indirect compositing");
            }
            if (settings.postProcess().ssgi().active()) {
                throw new IllegalArgumentException(
                        "DDGI and screen-space indirect diffuse both produce indirect light; "
                                + "disable one to avoid double counting");
            }
        }
        if (settings.vct().enabled()) {
            if (!hdr) {
                throw new IllegalArgumentException(
                        "voxel cone tracing requires HDR tone mapping for linear compositing");
            }
            if (settings.postProcess().ssgi().active()) {
                throw new IllegalArgumentException(
                        "voxel cone tracing and screen-space indirect diffuse both produce "
                                + "indirect light; disable one to avoid double counting");
            }
        }
        if (settings.hairStrands().active() && !hdr) {
            throw new IllegalArgumentException(
                    "hair strands require HDR tone mapping for linear strand compositing");
        }
    }
}
