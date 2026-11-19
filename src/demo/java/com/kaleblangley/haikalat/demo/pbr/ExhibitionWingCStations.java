package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.material.AnisotropicSettings;
import com.kaleblangley.haikalat.core.material.ClearcoatSettings;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.ParallaxOcclusionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSet;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PcssSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Map;

/**
 * Wing C - material and local-light stations.
 *
 * <p>All six stations are wired to their real production inputs: #8 thin
 * translucency, #16 clearcoat and #17 anisotropic shading through the bounded
 * PBR material settings, #18 through the assets-owned static reflection probes
 * bound per receiver each frame, #19 through the PCSS component of the
 * directional shadow settings, and #31 through the assets-owned procedural
 * brick height map and the POM material path.  The baseline phase keeps every
 * other value identical and only disables the observed feature (zero thin
 * controls, disabled clearcoat/anisotropic/parallax settings, disabled PCSS
 * component, no probe binding) so the A/B switch is a meaningful no-op
 * comparison, never a tinted placeholder.</p>
 */
final class ExhibitionWingCStations {
    private ExhibitionWingCStations() {
    }

    static ExhibitionStationBuilder builder(int id) {
        return switch (id) {
            case 8 -> ExhibitionWingCStations::thinTranslucency;
            case 16 -> ExhibitionWingCStations::clearcoat;
            case 17 -> ExhibitionWingCStations::anisotropic;
            case 18 -> ExhibitionWingCStations::reflectionProbes;
            case 19 -> ExhibitionWingCStations::pcss;
            // #31 parallax occlusion mapping: the base-colour role on the
            // supplied-binding material path is not used here (it rendered an
            // all-black display frame); the validated fixture-style contract is
            // the HEIGHT map plus an emissive pattern, so the station keeps the
            // emissive brick look while the real POM UV displacement is active.
            case 31 -> ExhibitionWingCStations::parallaxOcclusion;
            default -> null;
        };
    }

    private static ExhibitionStationView thinTranslucency(ExhibitionStationBuildContext context,
                                                          ExhibitionStation station,
                                                          ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets, stationCamera());
        ExhibitionSceneKit.addScaleProps(scene, assets);
        // Behind-the-panel key: its travel direction points at the camera, so the
        // thin panel's camera-facing normal is backlit (the front diffuse lobe
        // sees a negative N.L and stays dark).
        scene.addLight(SceneLight.directional(new Vector3f(0.15f, -0.20f, 1.0f),
                new Vector3f(1.0f, 0.74f, 0.42f), 3.0f));
        float[] thickness = {0.20f, 0.55f, 0.90f};
        for (int index = 0; index < thickness.length; index++) {
            PbrMaterialProperties properties = new PbrMaterialProperties(
                    new Vector4f(0.26f, 0.55f, 0.18f, 1.0f), 0.0f, 0.55f,
                    1.0f, 1.0f, new Vector3f(), Map.of(),
                    phase == ExhibitionPhase.EFFECT ? thickness[index] : 0.0f,
                    phase == ExhibitionPhase.EFFECT ? 0.85f : 0.0f,
                    phase == ExhibitionPhase.EFFECT ? 1.0f : 0.0f);
            // Double-sided raster is the documented thin-leaf path. The shared
            // assets factory only builds single-sided materials, so use the full
            // PBR factory with uDoubleSided; blending stays OPAQUE because no
            // alpha texture (MASK cutout) is supplied.
            Material material = PbrMaterials.createWithBindings(assets.pbrShader(), properties,
                    Map.of(), assets.fallbacks(), CullMode.NONE, true, false, 0.0f,
                    BlendMode.OPAQUE);
            scene.add(SceneObject.fixed(assets.quad, material,
                    new Matrix4f().identity().translation((index - 1) * 1.05f, 1.45f, -2.9f)
                            .scale(0.9f, 1.2f, 1.0f), false));
        }
        return view(scene, ExhibitionSettings.base(context.renderSettings()),
                phase == ExhibitionPhase.EFFECT
                        ? "transmission 0.85 / backlight 1.0 / thickness 0.20|0.55|0.90 on double-sided panels; warm directional backlight"
                        : "");
    }

    private static ExhibitionStationView clearcoat(ExhibitionStationBuildContext context,
                                                   ExhibitionStation station,
                                                   ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets, stationCamera());
        ExhibitionSceneKit.addScaleProps(scene, assets);
        float[] coatRoughness = {0.04f, 0.18f, 0.50f};
        for (int index = 0; index < coatRoughness.length; index++) {
            PbrMaterialProperties properties = new PbrMaterialProperties(
                    new Vector4f(0.09f, 0.11f, 0.15f, 1.0f), 0.0f, 0.62f,
                    1.0f, 1.0f, new Vector3f(), Map.of());
            ClearcoatSettings coat = phase == ExhibitionPhase.EFFECT
                    ? ClearcoatSettings.of(coatRoughness[index], 0.0f)
                    : ClearcoatSettings.disabled();
            Material material = assets.clearcoat(properties, Map.of(), coat);
            scene.add(SceneObject.fixed(assets.sphere, material,
                    new Matrix4f().identity().translation((index - 1) * 1.70f, 1.35f, -2.6f)
                            .scale(0.78f)));
        }
        return view(scene, ExhibitionSettings.base(context.renderSettings()),
                phase == ExhibitionPhase.EFFECT
                        ? "clearcoat F0 0.04 / coat roughness 0.04|0.18|0.50 / flat coat normal on dark dielectric spheres"
                        : "");
    }

    private static ExhibitionStationView anisotropic(ExhibitionStationBuildContext context,
                                                     ExhibitionStation station,
                                                     ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets, stationCamera());
        ExhibitionSceneKit.addScaleProps(scene, assets);
        float[] rotations = {0.0f, (float) (Math.PI / 2.0)};
        for (int index = 0; index < rotations.length; index++) {
            PbrMaterialProperties properties = new PbrMaterialProperties(
                    new Vector4f(0.62f, 0.66f, 0.72f, 1.0f), 1.0f, 0.28f,
                    1.0f, 1.0f, new Vector3f(), Map.of());
            AnisotropicSettings anisotropic = phase == ExhibitionPhase.EFFECT
                    ? AnisotropicSettings.of(0.90f, rotations[index])
                    : AnisotropicSettings.disabled();
            Material material = assets.anisotropic(properties, Map.of(), anisotropic);
            scene.add(SceneObject.fixed(assets.sphere, material,
                    new Matrix4f().identity().translation((index - 1) * 1.85f, 1.35f, -2.6f)
                            .scale(0.85f)));
        }
        return view(scene, ExhibitionSettings.base(context.renderSettings()),
                phase == ExhibitionPhase.EFFECT
                        ? "anisotropy strength 0.90 / rotation 0 and PI/2 on brushed-metal spheres (TANGENT sphere)"
                        : "");
    }

    private static ExhibitionStationView reflectionProbes(ExhibitionStationBuildContext context,
                                                          ExhibitionStation station,
                                                          ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        ReflectionProbeLab lab = reflectionProbeLab(assets);
        // The probes are a per-draw material binding, so the effect phase needs a
        // driver; the baseline uses the identical scene/materials unbound.
        ExhibitionStationDriver driver = phase == ExhibitionPhase.EFFECT
                ? new ReflectionProbeDriver(assets.reflectionProbes(),
                        lab.receiver(), lab.handover())
                : null;
        return new ExhibitionStationView(lab.scene(),
                ExhibitionSettings.base(context.renderSettings()),
                driver, phase == ExhibitionPhase.EFFECT
                        ? "2 static box probes: warm left bay / rotated cool right bay / neutral center handover sample"
                        : "");
    }

    private static ReflectionProbeLab reflectionProbeLab(ExhibitionAssets assets) {
        Scene scene = new Scene(stationCamera());
        Material floor = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.075f, 0.088f, 0.105f, 1.0f), 0.12f, 0.78f,
                1.0f, 1.0f, new Vector3f(), Map.of()));
        Material wall = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.055f, 0.069f, 0.090f, 1.0f), 0.08f, 0.88f,
                1.0f, 1.0f, new Vector3f(), Map.of()));
        scene.add(SceneObject.fixed(assets.groundQuad, floor,
                new Matrix4f().identity().translation(0.0f, 0.0f, 0.0f)
                        .rotateX((float) (-Math.PI / 2.0)).scale(16.0f, 16.0f, 1.0f), false));
        scene.add(SceneObject.fixed(assets.quad, wall,
                new Matrix4f().identity().translation(0.0f, 2.15f, -6.0f)
                        .scale(16.0f, 7.0f, 1.0f), false));

        Material darkMetal = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.12f, 0.145f, 0.17f, 1.0f), 0.72f, 0.30f,
                1.0f, 1.0f, new Vector3f(), Map.of()));
        Material warmTrim = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.36f, 0.115f, 0.045f, 1.0f), 0.62f, 0.28f,
                1.0f, 1.0f, new Vector3f(), Map.of()));
        Material coolTrim = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.045f, 0.22f, 0.38f, 1.0f), 0.58f, 0.26f,
                1.0f, 1.0f, new Vector3f(), Map.of()));
        Material warmSoftbox = assets.pbr(new PbrMaterialProperties(
                new Vector4f(1.0f, 0.32f, 0.095f, 1.0f), 0.0f, 0.34f,
                1.0f, 1.0f, new Vector3f(2.6f, 0.62f, 0.16f), Map.of()));
        Material coolSoftbox = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.10f, 0.54f, 1.0f, 1.0f), 0.0f, 0.34f,
                1.0f, 1.0f, new Vector3f(0.12f, 1.15f, 2.8f), Map.of()));
        Material receiver = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.74f, 0.77f, 0.81f, 1.0f), 1.0f, 0.075f,
                1.0f, 1.0f, new Vector3f(), Map.of()));

        addReflectionBay(scene, assets, -2.1f, warmTrim, warmSoftbox, darkMetal, receiver);
        addReflectionBay(scene, assets, 2.1f, coolTrim, coolSoftbox, darkMetal, receiver);

        Material handover = assets.pbr(new PbrMaterialProperties(
                new Vector4f(0.68f, 0.71f, 0.75f, 1.0f), 1.0f, 0.055f,
                1.0f, 1.0f, new Vector3f(), Map.of()));
        scene.add(SceneObject.fixed(assets.quad, handover,
                new Matrix4f().identity().translation(0.0f, 1.36f, -2.45f)
                        .scale(0.34f, 0.92f, 1.0f), false));
        addProbeBox(scene, assets, darkMetal, 0.0f, 1.36f, -2.52f,
                0.40f, 1.00f, 0.055f);

        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.45f, -1.0f, -0.55f),
                new Vector3f(0.90f, 0.94f, 1.0f), 2.2f));
        scene.addLight(SceneLight.point(new Vector3f(-3.1f, 2.55f, 2.1f),
                new Vector3f(1.0f, 0.43f, 0.20f), 4.0f, 8.0f));
        scene.addLight(SceneLight.point(new Vector3f(3.1f, 2.55f, 2.1f),
                new Vector3f(0.22f, 0.55f, 1.0f), 4.0f, 8.0f));
        return new ReflectionProbeLab(scene, receiver, handover);
    }

    private static void addReflectionBay(Scene scene, ExhibitionAssets assets, float centerX,
                                         Material trim, Material softbox, Material darkMetal,
                                         Material receiver) {
        addProbeBox(scene, assets, darkMetal, centerX, 0.10f, -1.8f,
                1.28f, 0.10f, 0.94f);
        addProbeBox(scene, assets, trim, centerX, 0.155f, -0.84f,
                1.10f, 0.025f, 0.035f);
        addProbeBox(scene, assets, darkMetal, centerX, 0.32f, -1.8f,
                0.22f, 0.22f, 0.22f);

        float frameZ = -2.82f;
        addProbeBox(scene, assets, trim, centerX - 1.02f, 1.67f, frameZ,
                0.065f, 1.48f, 0.065f);
        addProbeBox(scene, assets, trim, centerX + 1.02f, 1.67f, frameZ,
                0.065f, 1.48f, 0.065f);
        addProbeBox(scene, assets, trim, centerX, 3.16f, frameZ,
                1.08f, 0.075f, 0.065f);

        addProbeBox(scene, assets, darkMetal, centerX, 2.58f, frameZ + 0.07f,
                0.78f, 0.30f, 0.035f);
        scene.add(SceneObject.fixed(assets.quad, softbox,
                new Matrix4f().identity().translation(centerX, 2.58f, frameZ + 0.22f)
                        .scale(0.68f, 0.21f, 1.0f), false));
        // Keep the receiver centres stable for the linear-HDR readback contract.
        scene.add(SceneObject.fixed(assets.sphere, receiver,
                new Matrix4f().identity().translation(centerX, 1.20f, -1.8f).scale(0.62f)));
    }

    private static void addProbeBox(Scene scene, ExhibitionAssets assets, Material material,
                                    float x, float y, float z,
                                    float halfX, float halfY, float halfZ) {
        scene.add(SceneObject.fixed(assets.cube, material,
                new Matrix4f().identity().translation(x, y, z)
                        .scale(halfX, halfY, halfZ), false));
    }

    private record ReflectionProbeLab(Scene scene, Material receiver, Material handover) {
    }

    private static ExhibitionStationView pcss(ExhibitionStationBuildContext context,
                                              ExhibitionStation station,
                                              ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets, stationCamera());
        ExhibitionSceneKit.addScaleProps(scene, assets);
        // Occluders hang just in front of the studio back wall; the key light
        // throws their shadow onto the wall where the camera can see the edge.
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(0.35f, 2.30f, -5.60f)
                        .scale(0.62f, 0.62f, 0.22f)));
        scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                new Matrix4f().identity().translation(-1.65f, 2.35f, -5.55f)
                        .scale(0.52f, 0.52f, 0.20f)));
        ShadowSettings directional = ShadowSettings.directionalDefaults();
        PcssSettings pcss = phase == ExhibitionPhase.EFFECT
                ? new PcssSettings(true, 10.0f, 6, 24, 12, 24, 0.001f)
                : PcssSettings.disabled();
        ShadowSettings shadows = new ShadowSettings(directional.resolution(),
                directional.sceneRadius(), directional.nearPlane(), directional.farPlane(),
                directional.bias(), directional.contactShadows(), directional.cloudShadows(),
                pcss);
        RenderFeatureSettings settings = ExhibitionSettings.base(context.renderSettings())
                .toBuilder()
                .directionalShadowMap(new DirectionalShadowMap(shadows))
                .build();
        return view(scene, settings,
                phase == ExhibitionPhase.EFFECT
                        ? "PCSS light size 10 world / blocker disk 6 texels x24 taps / filter 12 texels x24 taps / bias 0.001"
                        : "");
    }

    private static ExhibitionStationView parallaxOcclusion(ExhibitionStationBuildContext context,
                                                           ExhibitionStation station,
                                                           ExhibitionPhase phase) {
        ExhibitionAssets assets = context.assets();
        Scene scene = ExhibitionSceneKit.studio(assets, stationCamera());
        ExhibitionSceneKit.addScaleProps(scene, assets);
        PbrMaterialProperties properties = new PbrMaterialProperties(
                new Vector4f(0.18f, 0.16f, 0.14f, 1.0f), 0.0f, 0.75f, 1.0f, 1.0f,
                new Vector3f(1.6f, 1.25f, 0.9f), Map.of());
        // Effect: authored brick height map + wall pattern through the POM path.
        // The emissive role carries the visible pattern because the base-colour
        // role on this fixture's supplied-binding map is not the contract under
        // test here; the parallax displacement moves the pattern's sampled UVs.
        // Baseline: the same pattern/geometry with no parallax.
        Material material = phase == ExhibitionPhase.EFFECT
                ? PbrMaterials.createWithParallax(assets.pbrShader(), properties,
                        Map.of(PbrTextureRole.HEIGHT,
                                        assets.brickParallaxBindings().get(PbrTextureRole.HEIGHT),
                                PbrTextureRole.EMISSIVE,
                                        assets.brickParallaxBindings().get(PbrTextureRole.BASE_COLOR)),
                        assets.fallbacks(),
                        ParallaxOcclusionSettings.of(0.06f, 8, 32))
                : PbrMaterials.create(assets.pbrShader(), properties,
                        Map.of(PbrTextureRole.EMISSIVE, assets.brickParallaxBindings()
                                .get(PbrTextureRole.BASE_COLOR).texture()),
                        assets.fallbacks());
        // Two brick walls seen at a grazing angle: a near and a far variant.
        float[] depths = {-2.6f, -4.8f};
        float[] xPositions = {0.4f, -1.7f};
        for (int index = 0; index < depths.length; index++) {
            scene.add(SceneObject.fixed(assets.quad, material,
                    new Matrix4f().identity()
                            .translation(xPositions[index], 1.50f, depths[index])
                            .rotateY((float) Math.toRadians(62.0))
                            .scale(2.2f, 2.0f, 1.0f), false));
        }
        return view(scene, ExhibitionSettings.base(context.renderSettings()),
                phase == ExhibitionPhase.EFFECT
                        ? "POM height 0.06 UV / 8-32 layers / 62deg grazing brick walls (procedural R8 brick height + wall colour), near and far"
                        : "");
    }

    private static ExhibitionStationView view(Scene scene, RenderFeatureSettings settings,
                                              String note) {
        return new ExhibitionStationView(scene, settings, null, note);
    }

    private static Camera stationCamera() {
        return ExhibitionSceneKit.camera(0.0f, 1.6f, 4.6f, -90.0f, -5.0f);
    }

    /** Per-draw local probe binding for #18; reused buffers, no per-frame allocation. */
    private static final class ReflectionProbeDriver implements ExhibitionStationDriver {
        private final ReflectionProbeSet probes;
        private final Material receiver;
        private final Material handover;
        private final Matrix4f model = new Matrix4f();
        private final Vector3f worldPosition = new Vector3f();

        private ReflectionProbeDriver(ReflectionProbeSet probes, Material receiver,
                                      Material handover) {
            this.probes = probes;
            this.receiver = receiver;
            this.handover = handover;
        }

        @Override
        public void onFrame(RenderPipeline pipeline, int frameIndex, float deltaSeconds,
                            ExhibitionPhase phase) {
            if (phase != ExhibitionPhase.EFFECT) {
                return;
            }
            for (MeshRenderer renderer : pipeline.scene().renderers()) {
                if (renderer.material().material() != receiver
                        && renderer.material().material() != handover) {
                    continue;
                }
                renderer.modelMatrix(model, frameIndex).getTranslation(worldPosition);
                PbrMaterials.bindReflectionProbes(renderer.material(), probes, worldPosition);
            }
        }
    }
}
