package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.FogSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PcssSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure dependency matrix for the unified feature request; no GL context is required. */
class RenderFeatureControlTest {
    private static final RenderFeaturePolicy.Facts ALL_TRUE = facts(true, true, true, true,
            true, false, true, false, false);
    private static final RenderFeaturePolicy.Facts NO_PBR = facts(false, true, true, true,
            false, true, true, false, false);

    @Test
    void defaultsKeepOnlyConfiguredEffectsActive() {
        var resolved = RenderFeaturePolicy.resolve(hdrBuilder().build(), NO_PBR);

        assertTrue(resolved.masterEnabled());
        assertTrue(resolved.directLighting());
        assertFalse(resolved.imageBasedLighting(), "no PBR material consumes IBL");
        assertTrue(resolved.directionalShadows());
        assertTrue(resolved.pointShadows());
        assertTrue(resolved.spotShadows());
        assertFalse(resolved.volumetricFog());
        assertFalse(resolved.sky());
        assertFalse(resolved.bloom());
        assertFalse(resolved.colorGrading());
        assertTrue(resolved.antiAliasing(), "hdrBuilder enables TAA");
        assertTrue(resolved.toneMapping(), "hdrBuilder enables ACES");
        assertEquals(RenderFeatureReason.NO_PBR_MATERIAL, activity(resolved,
                RenderFeature.IMAGE_BASED_LIGHTING).reason());
        assertEquals(RenderFeatureReason.DISABLED, activity(resolved,
                RenderFeature.VOLUMETRIC_FOG).reason());
    }

    @Test
    void masterPauseKeepsRequestedChildrenButMakesEverythingInactive() {
        RenderFeatureSettings requested = hdrBuilder()
                .enabled(false)
                .directionalShadows(true)
                .pointShadows(true)
                .volumetricFog(volumetric())
                .build();

        var resolved = RenderFeaturePolicy.resolve(requested, ALL_TRUE);

        assertFalse(resolved.masterEnabled());
        assertFalse(resolved.directLighting());
        assertFalse(resolved.imageBasedLighting());
        assertFalse(resolved.directionalShadows());
        assertFalse(resolved.pointShadows());
        assertFalse(resolved.spotShadows());
        assertFalse(resolved.volumetricFog());
        assertFalse(resolved.sky());
        assertTrue(requested.directionalShadows(), "requested children are preserved");
        assertEquals(RenderFeatureReason.DISABLED, activity(resolved, RenderFeature.MASTER).reason());
        for (RenderFeature feature : List.of(RenderFeature.DIRECT_LIGHTING,
                RenderFeature.SHADOW_DIRECTIONAL, RenderFeature.VOLUMETRIC_FOG)) {
            assertEquals(RenderFeatureReason.PAUSED_BY_MASTER, activity(resolved, feature).reason(),
                    feature.name());
            assertFalse(activity(resolved, feature).active(), feature.name());
        }
    }

    @Test
    void waterSurfaceActivityFollowsMasterPauseAndRejectsUnsupportedModes() {
        WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true).build();
        var resolved = RenderFeaturePolicy.resolve(
                hdrBuilder().waterSurface(water).build(), NO_PBR);
        assertTrue(resolved.waterSurface());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(resolved, RenderFeature.WATER_SURFACE).reason());

        var paused = RenderFeaturePolicy.resolve(
                hdrBuilder().enabled(false).waterSurface(water).build(), NO_PBR);
        assertFalse(paused.waterSurface());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.WATER_SURFACE).reason());

        RenderFeatureSettings ldr = RenderFeatureSettings.builder().waterSurface(water).build();
        assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(ldr, NO_PBR),
                "linear water absorption requires HDR tone mapping");

        RenderFeatureSettings volumetric = hdrBuilder()
                .waterSurface(water).volumetricFog(volumetric()).build();
        assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(volumetric, ALL_TRUE),
                "volumetric fog composition is not part of the first water version");
    }

    @Test
    void shadowRequestWithoutEligibleCasterIsSuppressedNotFailed() {
        var facts = facts(false, true, false, true, true, true, true, false, false);
        var requested = hdrBuilder().pointShadows(true).build();

        var resolved = RenderFeaturePolicy.resolve(requested, facts);

        assertFalse(resolved.pointShadows());
        assertEquals(RenderFeatureReason.NO_ELIGIBLE_LIGHT,
                activity(resolved, RenderFeature.SHADOW_POINT).reason());
        assertTrue(resolved.directionalShadows());
        assertTrue(resolved.spotShadows());
    }

    @Test
    void iblReportsMissingPbrMaterialAndMissingEnvironmentSeparately() {
        var noMaterial = RenderFeaturePolicy.resolve(hdrBuilder().build(), NO_PBR);
        assertEquals(RenderFeatureReason.NO_PBR_MATERIAL,
                activity(noMaterial, RenderFeature.IMAGE_BASED_LIGHTING).reason());

        // A PBR scene must have a borrowed environment, so the request is
        // rejected before IBL can be resolved; NO_ENVIRONMENT is defensive for
        // future PBR-less producers.
        assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(hdrBuilder().build(),
                        facts(true, true, true, true, false, false, true, false, false)));
    }

    @Test
    void invalidCombinationsFailBeforeCandidatePreparation() {
        assertMessage("volumetric fog replaces analytic fog", hdrBuilder()
                .volumetricFog(volumetric())
                .postProcess(PostProcessSettings.builder()
                        .fog(FogSettings.builder().enabled(true).build()).build())
                .build(), ALL_TRUE);
        assertMessage("GTAO requires", hdrBuilder()
                .postProcess(PostProcessSettings.builder()
                        .gtao(GtaoSettings.defaults().withEnabled(true)).build())
                .build(), NO_PBR);
        assertMessage("requires an explicit borrowed PbrEnvironment", hdrBuilder().build(),
                facts(true, true, true, true, false, false, true, false, false));
        assertThrows(IllegalStateException.class, () -> RenderSettings.builder()
                .toneMappingMode(ToneMappingMode.NONE)
                .bloomSettings(BloomSettings.builder().enabled(true).build()).build());
        assertMessage("custom HDR VFX must use hdrVfxWithFog", hdrBuilder()
                .volumetricFog(volumetric())
                .hdrVfx(true)
                .build(), facts(true, true, true, true, true, true, false, false, false));
        assertMessage("contact shadows require at least one metallic-roughness PBR material",
                contactRequest(true), NO_PBR);
        assertMessage("contact shadows require an enabled directional shadow caster",
                contactRequest(false), ALL_TRUE);
        assertMessage("contact shadows require an enabled directional shadow caster",
                contactRequest(true), facts(true, false, true, true, true, true, true, false, false));
        assertMessage("cloud shadows require at least one metallic-roughness PBR material",
                cloudRequest(true), NO_PBR);
        assertMessage("cloud shadows require an enabled directional shadow caster",
                cloudRequest(false), ALL_TRUE);
        assertMessage("cloud shadows require an enabled directional shadow caster",
                cloudRequest(true), facts(true, false, true, true, true, true, true, false, false));
        assertMessage("PCSS requires at least one metallic-roughness PBR material",
                pcssRequest(true), NO_PBR);
        assertMessage("PCSS requires an enabled directional shadow caster",
                pcssRequest(false), ALL_TRUE);
        assertMessage("PCSS requires an enabled directional shadow caster",
                pcssRequest(true), facts(true, false, true, true, true, true, true, false, false));
    }

    @Test
    void screenSpaceReflectionsRequireHdrAndPbrMaterials() {
        PostProcessSettings ssr = PostProcessSettings.builder()
                .ssr(SsrSettings.defaults()).build();

        assertMessage("screen-space reflections require HDR tone mapping",
                RenderFeatureSettings.builder().hdrVfx(false).postProcess(ssr).build(),
                NO_PBR);
        assertMessage("screen-space reflections require at least one metallic-roughness PBR material",
                hdrBuilder().postProcess(ssr).build(), NO_PBR);
        var resolved = RenderFeaturePolicy.resolve(
                hdrBuilder().postProcess(ssr).build(), ALL_TRUE);
        assertTrue(resolved.masterEnabled(),
                "a valid SSR request must resolve with HDR, PBR materials and an environment");
    }

    @Test
    void smaaIsAnHdrOnlyAntiAliasingRequestInTheUnifiedControl() {
        RenderFeatureSettings settings = hdrBuilder().render(RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.SMAA)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build()).build();

        var resolved = RenderFeaturePolicy.resolve(settings, NO_PBR);
        assertTrue(resolved.antiAliasing());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(resolved, RenderFeature.ANTI_ALIASING).reason());

        assertThrows(IllegalStateException.class, () -> RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.SMAA)
                .toneMappingMode(ToneMappingMode.NONE)
                .build());
    }

    @Test
    void projectedDecalRequestIsOptionalAndReportsWhyItCannotRun() {
        ProjectedDecalSettings request = new ProjectedDecalSettings(
                true, 0.6f, 0.02f, 0.5f, false, true, 8);
        assertEquals(ProjectedDecalSettings.disabled(),
                RenderFeatureSettings.defaults().projectedDecals(),
                "the default request must stay an exact no-op");
        assertThrows(NullPointerException.class,
                () -> RenderFeatureSettings.builder().projectedDecals(null));

        assertFalse(RenderFeaturePolicy.resolve(hdrBuilder().build(),
                        decalFacts(true, true)).projectedDecals());
        assertEquals(RenderFeatureReason.DISABLED,
                activity(RenderFeaturePolicy.resolve(hdrBuilder().build(),
                        decalFacts(true, true)), RenderFeature.PROJECTED_DECALS).reason());

        var active = RenderFeaturePolicy.resolve(
                hdrBuilder().projectedDecals(request).build(), decalFacts(true, true));
        assertTrue(active.projectedDecals());
        assertEquals(RenderFeatureReason.ACTIVE,
                activity(active, RenderFeature.PROJECTED_DECALS).reason());
        assertEquals(request, hdrBuilder().projectedDecals(request).build().projectedDecals(),
                "the request stays in the effective snapshot");

        var paused = RenderFeaturePolicy.resolve(
                hdrBuilder().enabled(false).projectedDecals(request).build(),
                decalFacts(true, true));
        assertFalse(paused.projectedDecals());
        assertEquals(RenderFeatureReason.PAUSED_BY_MASTER,
                activity(paused, RenderFeature.PROJECTED_DECALS).reason());

        var missingSource = RenderFeaturePolicy.resolve(
                hdrBuilder().projectedDecals(request).build(), decalFacts(false, true));
        assertFalse(missingSource.projectedDecals());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(missingSource, RenderFeature.PROJECTED_DECALS).reason());
        assertTrue(activity(missingSource, RenderFeature.PROJECTED_DECALS).detail()
                        .contains("registered projected-decal renderer/source"),
                activity(missingSource, RenderFeature.PROJECTED_DECALS).detail());

        var missingDepth = RenderFeaturePolicy.resolve(
                hdrBuilder().projectedDecals(request).build(), decalFacts(true, false));
        assertFalse(missingDepth.projectedDecals());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(missingDepth, RenderFeature.PROJECTED_DECALS).reason());
        assertTrue(activity(missingDepth, RenderFeature.PROJECTED_DECALS).detail()
                        .contains("single-sample scene depth"),
                activity(missingDepth, RenderFeature.PROJECTED_DECALS).detail());

        var ldr = RenderFeaturePolicy.resolve(
                RenderFeatureSettings.builder().projectedDecals(request).build(),
                decalFacts(false, true, true));
        assertFalse(ldr.projectedDecals());
        assertEquals(RenderFeatureReason.UNAVAILABLE,
                activity(ldr, RenderFeature.PROJECTED_DECALS).reason());
        assertTrue(activity(ldr, RenderFeature.PROJECTED_DECALS).detail()
                        .contains("HDR tone mapping"),
                activity(ldr, RenderFeature.PROJECTED_DECALS).detail());
    }

    private static RenderFeaturePolicy.Facts decalFacts(boolean sourcePresent,
                                                        boolean depthAvailable) {
        return decalFacts(true, sourcePresent, depthAvailable);
    }

    private static RenderFeaturePolicy.Facts decalFacts(boolean pbr, boolean sourcePresent,
                                                        boolean depthAvailable) {
        return new RenderFeaturePolicy.Facts(pbr, true, true, true, true,
                false, false, false, false, true, false, false, true, false, false,
                sourcePresent, depthAvailable);
    }

    @Test
    void builderRoundTripKeepsEveryComponent() {
        RenderFeatureSettings original = hdrBuilder()
                .enabled(false)
                .directLighting(false)
                .imageBasedLighting(false)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .hdrVfx(false)
                .preview(false)
                .projectedDecals(new ProjectedDecalSettings(
                        true, 0.6f, 0.02f, 0.5f, false, true, 8))
                .build();

        assertEquals(original, original.toBuilder().build());
        assertEquals(original.render(), original.toBuilder().build().render());
        assertEquals(original.projectedDecals(),
                original.toBuilder().build().projectedDecals());
    }

    private static void assertMessage(String fragment, RenderFeatureSettings settings,
                                      RenderFeaturePolicy.Facts facts) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RenderFeaturePolicy.resolve(settings, facts));
        assertTrue(failure.getMessage().contains(fragment),
                () -> "expected message containing '" + fragment + "' but got: "
                        + failure.getMessage());
    }

    private static RenderFeatureActivity activity(RenderFeaturePolicy.Resolved resolved,
                                                 RenderFeature feature) {
        return resolved.activities().stream().filter(a -> a.feature() == feature).findFirst()
                .orElseThrow();
    }

    private static RenderFeaturePolicy.Facts facts(boolean pbr, boolean directional,
                                                   boolean point, boolean spot, boolean env,
                                                   boolean vfxRecorder, boolean fogAware,
                                                   boolean fogOptOut, boolean previewSelected) {
        return new RenderFeaturePolicy.Facts(pbr, directional, point, spot, env, vfxRecorder,
                fogAware, fogOptOut, previewSelected, directional);
    }

    private static RenderFeatureSettings.Builder hdrBuilder() {
        return RenderFeatureSettings.builder().render(RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.TAA)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build());
    }

    private static RenderFeatureSettings contactRequest(boolean directionalShadows) {
        ContactShadowSettings contact = new ContactShadowSettings(true, 1.0f, 0.5f, 8, 0.8f);
        return hdrBuilder()
                .directionalShadows(directionalShadows)
                .directionalShadowMap(new DirectionalShadowMap(
                        new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.003f, contact)))
                .build();
    }

    private static RenderFeatureSettings cloudRequest(boolean directionalShadows) {
        CloudShadowSettings clouds = new CloudShadowSettings(true, 0.6f, 0.3f, 0.08f, 1.5f, 0.7f);
        return hdrBuilder()
                .directionalShadows(directionalShadows)
                .directionalShadowMap(new DirectionalShadowMap(
                        new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.003f,
                                ContactShadowSettings.disabled(), clouds)))
                .build();
    }

    private static RenderFeatureSettings pcssRequest(boolean directionalShadows) {
        PcssSettings pcss = PcssSettings.disabled().withEnabled(true);
        return hdrBuilder()
                .directionalShadows(directionalShadows)
                .directionalShadowMap(new DirectionalShadowMap(
                        new ShadowSettings(512, 12.0f, 0.1f, 40.0f, 0.003f,
                                ContactShadowSettings.disabled(), CloudShadowSettings.disabled(),
                                pcss)))
                .build();
    }

    private static VolumetricFogSettings volumetric() {
        return new VolumetricFogSettings(true, 80,
                VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.vacuum(), List.of(), 0, true, 0.9f, 1, new Vector3f());
    }
}
