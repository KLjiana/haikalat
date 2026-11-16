package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.AnisotropicSettings;
import com.kaleblangley.haikalat.core.material.ClearcoatSettings;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.ParallaxOcclusionSettings;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.DepthOfFieldSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.MotionBlurSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsPass;
import com.kaleblangley.haikalat.subsystems.render3d.caustics.CausticsSettings;
import com.kaleblangley.haikalat.subsystems.render3d.ddgi.DdgiSettings;
import com.kaleblangley.haikalat.subsystems.render3d.environment.AtmosphereSettings;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandPass;
import com.kaleblangley.haikalat.subsystems.render3d.hair.HairStrandSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionPass;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.scene.RenderQueueClass;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ScreenSpaceContactShadowPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencyPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.StochasticTransparencySettings;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecal;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeRenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulation;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeSimulationSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.SmokeVolumePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterRefractionSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfacePass;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.MeshVfx;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.vfx.VfxDissolve;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * S6 representative interaction matrix executed on a real OpenGL context.
 *
 * <p>This class closes the gap left by the policy-level
 * {@code RenderFeatureInteractionMatrixTest}: the seven representative
 * combinations from the delivery plan §7.2 are assembled into real
 * {@link RenderPipeline} generations, executed for a fixed number of frames with
 * a fixed camera, fixed time step, fixed manual exposure and fixed seeds, and
 * read back as a real pixel matrix. Every case asserts that the pipeline builds
 * and executes with zero OpenGL errors, that all readback components are
 * finite/bounded and the image is not a uniform clear, that the expected
 * feature passes exist, and - where the combination is deterministic - that
 * disabling the group's key feature changes the pixels and re-enabling restores
 * them byte-for-byte.</p>
 *
 * <p>Automatic pixel evidence is deliberately separated from human visual
 * review: this class makes no "looks correct" claim, and the S6 report records
 * human visual as {@code NOT_EVALUATED}. Performance is likewise
 * {@code NOT_EVALUATED}; the 96x96 extent is chosen to stay bounded and is not a
 * frame-time measurement.</p>
 *
 * <p>Documented unsupported combinations are asserted through their production
 * rejection rather than forced: SSR with the finite water surface is rejected by
 * {@code PipelineFeaturePolicy.validateSsrComposition} and by the unified feature
 * request, and SSGI with DDGI/VCT is rejected by
 * {@code RenderFeaturePolicy}. The water/glass case therefore also exercises SSR
 * post-only (no water) and records the exclusion.</p>
 *
 * <p>Guarded by {@code haikalat.glSmoke=true}; executed only by the serialized
 * GPU queue.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class S6InteractionMatrixGlTest {
    private static final int SIZE = S6InteractionMatrixGlFixture.SIZE;

    private static final String UNLIT_VERTEX = """
            #version 460 core
            layout (location = 0) in vec3 aPosition;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
            }
            """;
    private static final String UNLIT_FRAGMENT = """
            #version 460 core
            layout (location = 0) out vec4 fragColor;
            uniform vec3 uColor;
            void main() {
                fragColor = vec4(uColor, 1.0);
            }
            """;
    /**
     * Screen-space checker used by the reconstruction cases: a uniform colour
     * has no edges for DOF/TAA/motion blur to redistribute, so a flat fixture
     * would report a false zero delta.
     */
    private static final String PATTERN_FRAGMENT = """
            #version 460 core
            layout (location = 0) out vec4 fragColor;
            uniform vec3 uColorA;
            uniform vec3 uColorB;
            void main() {
                float checker = mod(floor(gl_FragCoord.x / 6.0)
                        + floor(gl_FragCoord.y / 6.0), 2.0);
                fragColor = vec4(mix(uColorA, uColorB, checker), 1.0);
            }
            """;

    // 1 ------------------------------------------------------------------
    /**
     * Battle: HDR VFX composition + bloom with NONE and TAA.
     *
     * <p>Contact/cloud shadows require a PBR material and are covered by the
     * material/shadow case; projected decals need their own color-only target
     * view and are covered by {@code ProjectedDecalGlTest}. This feasible
     * sub-combination keeps the battle-critical VFX -> bloom -> AA chain.</p>
     */
    @Test
    void battleGroupRunsVfxBloomUnderNoneAndTaa() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 battle matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
            try (EffectAsset asset = battleAsset();
                 EffectInstance instance = asset.instantiate(7L);
                 VfxRenderer vfx = new VfxRenderer();
                 SceneResources resources = SceneResources.unlit(0.34f, 0.30f, 0.46f)) {
                Scene scene = battleScene(resources);
                // Fixed spawn/update sequence: the snapshot is built once and
                // replayed for every frame, so the pixel evidence is deterministic.
                instance.spawnMesh(new Matrix4f().translate(0.0f, 0.55f, 0.0f)
                        .scale(1.9f));
                for (int frame = 0; frame < 8; frame++) {
                    instance.update(0.05f, new Vector3f(0.0f, 0.6f, 0.0f));
                }
                EffectSnapshot snapshot = instance.snapshot(new Vector3f(0.0f, 1.4f, 4.2f))
                        .withFrameValues(0.4f, 0.0f, List.of());
                AtomicInteger recorderCalls = new AtomicInteger();
                int width = window.width();
                int height = window.height();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        S6InteractionMatrixGlFixture.hdrWithBloom(AntiAliasingMode.NONE))
                        .hdrVfxWithCamera((passResources, commands, camera) -> {
                            recorderCalls.incrementAndGet();
                            vfx.record(commands, snapshot,
                                    CameraProjection.stable(camera, width, height,
                                            new Matrix4f()),
                                    camera.getViewMatrix(new Matrix4f()));
                        });
                try {
                    pipeline.build();
                    assertTrue(pipeline.graph().hasPass(PostProcessTargets.VFX_COMPOSITE_PASS),
                            "HDR VFX must declare its composite pass");
                    assertTrue(pipeline.graph().hasPass(PostProcessTargets.BLOOM_EXTRACT_PASS),
                            "bloom must declare its extract pass");
                    assertFalse(pipeline.graph().hasPass(PostProcessTargets.TAA_PASS),
                            "the NONE sub-case must not declare TAA");

                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    float[] enabled = S6InteractionMatrixGlFixture.capture(
                            pipeline, "battle VFX enabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(enabled, "battle VFX enabled");
                    assertTrue(recorderCalls.get() > 0,
                            "the production VFX recorder must run in the HDR VFX pass");

                    RenderFeatureSettings effective = pipeline.features().effective();
                    pipeline.features().submit(effective.toBuilder().hdrVfx(false).build());
                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                            pipeline.features().failureReason());
                    float[] disabled = S6InteractionMatrixGlFixture.capture(
                            pipeline, "battle VFX disabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(disabled, "battle VFX disabled");
                    S6InteractionMatrixGlFixture.requireDifferentImage(enabled, disabled,
                            "disabling HDR VFX must change the battle pixels");
                    S6InteractionMatrixGlFixture.report("battle", "vfxDisabledChangedPixels",
                            S6InteractionMatrixGlFixture.differingPixels(enabled, disabled));

                    pipeline.features().submit(effective.toBuilder().hdrVfx(true).build());
                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                            pipeline.features().failureReason());
                    float[] restored = S6InteractionMatrixGlFixture.capture(
                            pipeline, "battle VFX restored");
                    S6InteractionMatrixGlFixture.requireCleanFrame(restored,
                            "battle VFX restored");
                    S6InteractionMatrixGlFixture.requireSameImage(enabled, restored,
                            "re-enabling HDR VFX must restore the deterministic battle image");
                    S6InteractionMatrixGlFixture.report("battle", "vfxRestoreDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(enabled, restored));
                    GlDebug.assertNoError("S6 battle NONE matrix");
                } finally {
                    pipeline.close();
                }
            }

            // TAA sub-case: the same VFX -> bloom chain under temporal AA.
            try (EffectAsset asset = battleAsset();
                 EffectInstance instance = asset.instantiate(7L);
                 VfxRenderer vfx = new VfxRenderer();
                 SceneResources resources = SceneResources.unlit(0.34f, 0.30f, 0.46f)) {
                instance.spawnMesh(new Matrix4f().translate(0.0f, 0.55f, 0.0f)
                        .scale(1.9f));
                for (int frame = 0; frame < 8; frame++) {
                    instance.update(0.05f, new Vector3f(0.0f, 0.6f, 0.0f));
                }
                EffectSnapshot snapshot = instance.snapshot(new Vector3f(0.0f, 1.4f, 4.2f))
                        .withFrameValues(0.4f, 0.0f, List.of());
                int width = window.width();
                int height = window.height();
                RenderPipeline withVfx = new RenderPipeline(window, battleScene(resources), null,
                        S6InteractionMatrixGlFixture.hdrWithBloom(AntiAliasingMode.TAA))
                        .hdrVfxWithCamera((passResources, commands, camera) ->
                                vfx.record(commands, snapshot,
                                        CameraProjection.stable(camera, width, height,
                                                new Matrix4f()),
                                        camera.getViewMatrix(new Matrix4f())));
                RenderPipeline withoutVfx = new RenderPipeline(window, battleScene(resources), null,
                        S6InteractionMatrixGlFixture.hdrWithBloom(AntiAliasingMode.TAA));
                try {
                    withVfx.build();
                    withoutVfx.build();
                    assertTrue(withVfx.graph().hasPass(PostProcessTargets.TAA_PASS),
                            "the TAA sub-case must declare the temporal resolve");
                    assertTrue(withVfx.graph().hasPass(PostProcessTargets.VFX_COMPOSITE_PASS));

                    S6InteractionMatrixGlFixture.execute(withVfx, device, 3);
                    S6InteractionMatrixGlFixture.execute(withoutVfx, device, 3);
                    float[] vfxPixels = S6InteractionMatrixGlFixture.capture(
                            withVfx, "battle TAA VFX");
                    float[] plainPixels = S6InteractionMatrixGlFixture.capture(
                            withoutVfx, "battle TAA plain");
                    S6InteractionMatrixGlFixture.requireCleanFrame(vfxPixels, "battle TAA VFX");
                    S6InteractionMatrixGlFixture.requireCleanFrame(plainPixels,
                            "battle TAA plain");
                    S6InteractionMatrixGlFixture.requireDifferentImage(vfxPixels, plainPixels,
                            "VFX must still contribute under TAA");
                    S6InteractionMatrixGlFixture.report("battle", "taaVfxVsPlainChangedPixels",
                            S6InteractionMatrixGlFixture.differingPixels(vfxPixels, plainPixels));
                    GlDebug.assertNoError("S6 battle TAA matrix");
                } finally {
                    withoutVfx.close();
                    withVfx.close();
                }
            }
            S6InteractionMatrixGlFixture.requireNoLiveResources("battle VFX matrix");
            }
        }
    }

    // 2 ------------------------------------------------------------------
    /**
     * Battle, extended composition: the same HDR VFX + bloom chain under TAA,
     * now with projected decals on a PBR receiver and the main directional
     * shadow carrying both contact and cloud shadows.
     *
     * <p>The decal batch is recorded inside the production
     * {@code VfxCompositePass} against the pass-owned single-sample scene-depth
     * copy, so no extra target or pass is invented. Each feature is switched
     * off on a fresh pipeline with the same fixed camera/time/seed/taa inputs;
     * the fully enabled pipeline is then rebuilt to prove the combined result is
     * deterministic rather than history-dependent. Per-feature pixel deltas are
     * reported as evidence, and the contact-shadow pass is asserted to exist
     * only when contact shadows are enabled.</p>
     */
    @Test
    void battleGroupComposesDecalsAndContactCloudShadowsWithVfxBloomTaa() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window(
                "S6 battle decal and shadow matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
              try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("s6-battle-pbr-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality());
                 EffectAsset asset = battleAsset();
                 EffectInstance instance = asset.instantiate(7L);
                 VfxRenderer vfx = new VfxRenderer()) {
                Material material = PbrMaterials.createWithBindings(shader,
                        new PbrMaterialProperties(new Vector4f(0.58f, 0.5f, 0.42f, 1.0f),
                                0.0f, 0.7f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks, CullMode.NONE, false, false, 0.0f,
                        BlendMode.OPAQUE, ClearcoatSettings.disabled(), null,
                        AnisotropicSettings.disabled(),
                        ParallaxOcclusionSettings.disabled(),
                        SubsurfaceScatteringSettings.disabled());
                try {
                    Scene scene = battlePbrScene(quad, material);
                    instance.spawnMesh(new Matrix4f().translate(0.0f, 0.55f, 0.0f)
                            .scale(1.9f));
                    for (int frame = 0; frame < 8; frame++) {
                        instance.update(0.05f, new Vector3f(0.0f, 0.6f, 0.0f));
                    }
                    EffectSnapshot snapshot = instance
                            .snapshot(new Vector3f(0.0f, 1.4f, 4.2f))
                            .withFrameValues(0.4f, 0.0f, List.of());
                    vfx.prepare(snapshot, List.of(BATTLE_DECAL));
                    int width = window.width();
                    int height = window.height();

                    BattleVariant all = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(true, true, true, true, true, true), "battle all");
                    BattleVariant noVfx = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(true, true, true, true, true, false),
                            "battle vfx off");
                    BattleVariant noDecals = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(false, true, true, true, true, true),
                            "battle decals off");
                    BattleVariant noContact = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(true, false, true, true, true, true),
                            "battle contact shadows off");
                    BattleVariant noCloud = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(true, true, false, true, true, true),
                            "battle cloud shadows off");
                    BattleVariant noBloom = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(true, true, true, false, true, true),
                            "battle bloom off");
                    BattleVariant noTaa = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(true, true, true, true, false, true),
                            "battle taa off");
                    BattleVariant dissolved = runBattleVariant(window, device, scene,
                            environment, snapshot.withFrameValues(0.4f, 0.65f, List.of()),
                            vfx, width, height,
                            new BattleFlags(true, true, true, true, true, true),
                            "battle dissolve 0.65");
                    BattleVariant allAgain = runBattleVariant(window, device, scene,
                            environment, snapshot, vfx, width, height,
                            new BattleFlags(true, true, true, true, true, true),
                            "battle all rebuilt");

                    assertTrue(all.vfxPass(), "the battle composition must keep HDR VFX");
                    assertTrue(all.bloomPass(), "the battle composition must keep bloom");
                    assertTrue(all.taaPass(), "the battle composition must keep TAA");
                    assertTrue(all.directionalShadowPass(),
                            "contact/cloud shadows must keep the directional shadow pass");
                    assertTrue(all.contactShadowPass(),
                            "enabled contact shadows must declare their production pass");
                    assertTrue(noVfx.vfxPass(),
                            "the shared VFX composite pass must remain for the decal batch");
                    assertTrue(noVfx.decalDraws() > 0,
                            "disabling VFX must not disable the decal batch");
                    assertTrue(noVfx.bloomPass() && noVfx.taaPass()
                                    && noVfx.contactShadowPass(),
                            "the other battle features must survive VFX being off");
                    assertFalse(noContact.contactShadowPass(),
                            "disabled contact shadows must not declare the contact pass");
                    assertTrue(noContact.directionalShadowPass(),
                            "cloud shadows still need the directional shadow pass without contact");
                    assertTrue(noCloud.contactShadowPass(),
                            "contact shadows must remain independent of cloud shadows");
                    assertFalse(noBloom.bloomPass(),
                            "disabled bloom must not declare its extract pass");
                    assertTrue(noBloom.vfxPass() && noBloom.taaPass(),
                            "VFX and TAA must remain independent of bloom");
                    assertFalse(noTaa.taaPass(),
                            "the NONE battle variant must not declare the TAA pass");
                    assertTrue(noTaa.vfxPass() && noTaa.bloomPass()
                                    && noTaa.directionalShadowPass(),
                            "VFX/bloom/shadows must remain independent of the AA mode");
                    assertTrue(all.decalDraws() > 0,
                            "the enabled battle batch must record a projected decal");
                    assertEquals(0, noDecals.decalDraws(),
                            "the disabled decal batch must record no draw");
                    assertTrue(dissolved.decalDraws() > 0,
                            "the dissolve variant must keep the decal batch");
                    assertTrue(dissolved.vfxPass(),
                            "the dissolve variant must keep the HDR VFX pass");

                    int vfxChanged = S6InteractionMatrixGlFixture.requireDifferentImage(
                            all.pixels(), noVfx.pixels(),
                            "disabling HDR VFX must change the composed battle pixels");
                    S6InteractionMatrixGlFixture.report("battle",
                            "extendedVfxDisabledChangedPixels", vfxChanged);
                    int decalChanged = S6InteractionMatrixGlFixture.requireDifferentImage(
                            all.pixels(), noDecals.pixels(),
                            "disabling projected decals must change the battle pixels");
                    S6InteractionMatrixGlFixture.report("battle", "decalsDisabledChangedPixels",
                            decalChanged);
                    int contactChanged = S6InteractionMatrixGlFixture.requireDifferentImage(
                            all.pixels(), noContact.pixels(),
                            "disabling contact shadows must change the battle pixels");
                    S6InteractionMatrixGlFixture.report("battle",
                            "contactShadowsDisabledChangedPixels", contactChanged);
                    int cloudChanged = S6InteractionMatrixGlFixture.requireDifferentImage(
                            all.pixels(), noCloud.pixels(),
                            "disabling cloud shadows must change the battle pixels");
                    S6InteractionMatrixGlFixture.report("battle",
                            "cloudShadowsDisabledChangedPixels", cloudChanged);
                    int bloomChanged = S6InteractionMatrixGlFixture.differingBytes(
                            all.presented(), noBloom.presented());
                    assertTrue(bloomChanged > 0,
                            "disabling bloom must change the presented battle pixels; "
                                    + "bloom is composited during tone mapping, after the "
                                    + "linear HDR capture");
                    S6InteractionMatrixGlFixture.report("battle",
                            "bloomDisabledChangedPresentedBytes", bloomChanged);
                    int taaChanged = S6InteractionMatrixGlFixture.requireDifferentImage(
                            all.pixels(), noTaa.pixels(),
                            "switching TAA off must change the reconstructed battle pixels");
                    S6InteractionMatrixGlFixture.report("battle",
                            "taaDisabledChangedPixels", taaChanged);
                    int dissolveChanged = S6InteractionMatrixGlFixture.requireDifferentImage(
                            all.pixels(), dissolved.pixels(),
                            "dissolve progress 0.65 must erode the battle VFX pixels");
                    S6InteractionMatrixGlFixture.report("battle",
                            "dissolveProgressChangedPixels", dissolveChanged);
                    S6InteractionMatrixGlFixture.requireSameImage(all.pixels(),
                            allAgain.pixels(),
                            "rebuilding the deterministic battle composition must restore "
                                    + "the exact image");
                    S6InteractionMatrixGlFixture.report("battle",
                            "decalShadowRebuildDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(all.pixels(),
                                    allAgain.pixels()));
                } finally {
                    material.close();
                }
              }
              S6InteractionMatrixGlFixture.requireNoLiveResources(
                      "battle decal/shadow matrix");
            }
        }
    }

    // 2 ------------------------------------------------------------------
    /** Material/shadow: thin/clearcoat/anisotropic/POM inputs in one forward
     * material plus contact and cloud shadows on the same directional light. */
    @Test
    void materialShadowGroupKeepsBoundedInputsWithContactAndCloudShadows() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 material matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking();
                 ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("s6-material-quad")).mesh());
                 Texture2D heightMap = heightMap();
                 Sampler heightSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                         GL_CLAMP_TO_EDGE));
                 Texture2D detailMap = detailMap();
                 Sampler detailSampler = Sampler.create(new Sampler.Descriptor(
                         GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                         GL_CLAMP_TO_EDGE));
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                ParallaxOcclusionSettings parallax = ParallaxOcclusionSettings.builder()
                        .enabled(true).heightScale(0.06f).steps(4, 16)
                        .heightChannel(ParallaxOcclusionSettings.HeightChannel.RED)
                        .build();
                Material material = PbrMaterials.createWithBindings(shader,
                        thinProperties(),
                        Map.of(PbrTextureRole.HEIGHT,
                                new PbrTextureBinding(heightMap, heightSampler)),
                        fallbacks, CullMode.NONE, true, false, 0.0f, BlendMode.OPAQUE,
                        new ClearcoatSettings(true, 0.18f, 1.0f), null,
                        new AnisotropicSettings(0.65f, 0.35f), parallax,
                        SubsurfaceScatteringSettings.disabled());
                assertTrue(material.clearcoat().active(),
                        "the fixed material template must carry an active clearcoat");
                assertTrue(material.anisotropic().active(),
                        "the fixed material template must carry active anisotropy");
                assertTrue(material.parallaxOcclusion().active(),
                        "the fixed material template must carry active parallax occlusion");
                assertTrue(hasUniform(material, "uClearcoatEnabled")
                                && hasUniform(material, "uAnisotropicEnabled")
                                && hasUniform(material, "uParallaxEnabled")
                                && hasUniform(material, "uTransmissionFactor"),
                        "every toggled material feature must own its production uniform");
                try {
                    MaterialInstance instance = material.createInstance();
                    Camera camera = new Camera(new Vector3f(-2.5f, 7.0f, 9.0f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -40.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(quad, instance,
                                    Transform.at(0.0f, 0.0f, 0.0f)
                                            .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                            .scale(10.0f, 10.0f, 1.0f))
                            .withoutShadows());
                    scene.add(MeshRenderer.of(quad, instance,
                            Transform.at(0.0f, 1.0f, -1.0f).scale(3.0f, 2.0f, 1.0f)));
                    scene.addLight(SceneLight.shadowedDirectional(
                            new Vector3f(0.5f, -1.0f, 0.35f), new Vector3f(3.0f), 5.0f));
                    ShadowSettings withoutContact = new ShadowSettings(
                            64, 6.0f, 0.1f, 40.0f, 0.05f);
                    ShadowSettings withContact = new ShadowSettings(
                            64, 6.0f, 0.1f, 40.0f, 0.05f,
                            new ContactShadowSettings(true, 3.0f, 3.0f, 32, 1.0f),
                            new CloudShadowSettings(true, 0.85f, 0.3f, 0.12f, 0.0f, 0.65f));
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE), environment);
                    RenderFeatureSettings active = pipeline.features().effective().toBuilder()
                            .imageBasedLighting(false)
                            .directionalShadows(true)
                            .directionalShadowMap(new DirectionalShadowMap(withContact))
                            .build();
                    try {
                        pipeline.features().submit(active);
                        pipeline.build();
                        assertTrue(pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME),
                                "contact/cloud shadows require the directional shadow pass");
                        ShadowSettings effective =
                                pipeline.features().effective().directionalShadowMap().settings();
                        assertTrue(effective.contactShadows().active(),
                                "the combined shadow settings must carry contact shadows");
                        assertTrue(effective.cloudShadows().active(),
                                "the combined shadow settings must carry cloud shadows");

                        S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                        float[] shadows = S6InteractionMatrixGlFixture.capture(
                                pipeline, "material shadows enabled");
                        S6InteractionMatrixGlFixture.requireCleanFrame(shadows,
                                "material shadows enabled");

                        pipeline.features().submit(active.toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(
                                        withoutContact)).build());
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                                pipeline.features().failureReason());
                        float[] plain = S6InteractionMatrixGlFixture.capture(
                                pipeline, "material shadows disabled");
                        S6InteractionMatrixGlFixture.requireCleanFrame(plain,
                                "material shadows disabled");
                        S6InteractionMatrixGlFixture.requireDifferentImage(shadows, plain,
                                "disabling contact/cloud shadows must change the pixels");
                        S6InteractionMatrixGlFixture.report("material",
                                "contactCloudDisabledChangedPixels",
                                S6InteractionMatrixGlFixture.differingPixels(shadows, plain));

                        pipeline.features().submit(active.toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(
                                        withContact)).build());
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                                pipeline.features().failureReason());
                        float[] restored = S6InteractionMatrixGlFixture.capture(
                                pipeline, "material shadows restored");
                        S6InteractionMatrixGlFixture.requireCleanFrame(restored,
                                "material shadows restored");
                        S6InteractionMatrixGlFixture.requireSameImage(shadows, restored,
                                "re-enabling the fixed contact/cloud shadows must restore the image");
                        S6InteractionMatrixGlFixture.report("material",
                                "shadowRestoreDifferingPixels",
                                S6InteractionMatrixGlFixture.differingPixels(shadows, restored));
                        GlDebug.assertNoError("S6 material/shadow matrix");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }

                // Independent toggles: every feature is disabled on its own
                // while the other three stay active, so the variant delta
                // isolates that feature instead of a combined on/off request.
                PbrTextureBinding heightBinding =
                        new PbrTextureBinding(heightMap, heightSampler);
                PbrTextureBinding detailBinding =
                        new PbrTextureBinding(detailMap, detailSampler);
                ClearcoatSettings clearcoat = new ClearcoatSettings(true, 0.18f, 1.0f);
                AnisotropicSettings anisotropic = new AnisotropicSettings(0.65f, 0.35f);
                ShadowSettings combinedShadows = new ShadowSettings(
                        64, 6.0f, 0.1f, 40.0f, 0.05f,
                        new ContactShadowSettings(true, 3.0f, 3.0f, 32, 1.0f),
                        new CloudShadowSettings(true, 0.85f, 0.3f, 0.12f, 0.0f, 0.65f));
                MaterialVariant allFeatures = runMaterialVariant(window, device, shader,
                        fallbacks, quad, heightBinding, detailBinding, environment, thinProperties(),
                        clearcoat, anisotropic, parallax, combinedShadows,
                        "material all features");
                MaterialVariant noClearcoat = runMaterialVariant(window, device, shader,
                        fallbacks, quad, heightBinding, detailBinding, environment, thinProperties(),
                        ClearcoatSettings.disabled(), anisotropic, parallax, combinedShadows,
                        "material clearcoat off");
                MaterialVariant noAnisotropic = runMaterialVariant(window, device, shader,
                        fallbacks, quad, heightBinding, detailBinding, environment, thinProperties(),
                        clearcoat, AnisotropicSettings.disabled(), parallax, combinedShadows,
                        "material anisotropic off");
                MaterialVariant noParallax = runMaterialVariant(window, device, shader,
                        fallbacks, quad, heightBinding, detailBinding, environment, thinProperties(),
                        clearcoat, anisotropic, ParallaxOcclusionSettings.disabled(),
                        combinedShadows, "material POM off");
                MaterialVariant noThin = runMaterialVariant(window, device, shader,
                        fallbacks, quad, heightBinding, detailBinding, environment, opaqueProperties(),
                        clearcoat, anisotropic, parallax, combinedShadows,
                        "material thin off");
                MaterialVariant allFeaturesAgain = runMaterialVariant(window, device, shader,
                        fallbacks, quad, heightBinding, detailBinding, environment, thinProperties(),
                        clearcoat, anisotropic, parallax, combinedShadows,
                        "material all features rebuilt");

                assertTrue(allFeatures.clearcoat() && allFeatures.anisotropic()
                                && allFeatures.parallax()
                                && allFeatures.transmission() > 0.0f,
                        "the baseline material must carry every toggled feature");
                assertFalse(noClearcoat.clearcoat(),
                        "the clearcoat-off variant must report an inactive clearcoat");
                assertTrue(noClearcoat.anisotropic() && noClearcoat.parallax()
                                && noClearcoat.transmission() > 0.0f,
                        "disabling clearcoat must not disable anisotropy/POM/thin");
                assertFalse(noAnisotropic.anisotropic(),
                        "the anisotropic-off variant must report inactive anisotropy");
                assertTrue(noAnisotropic.clearcoat() && noAnisotropic.parallax()
                                && noAnisotropic.transmission() > 0.0f,
                        "disabling anisotropy must not disable clearcoat/POM/thin");
                assertFalse(noParallax.parallax(),
                        "the POM-off variant must report inactive parallax occlusion");
                assertTrue(noParallax.clearcoat() && noParallax.anisotropic()
                                && noParallax.transmission() > 0.0f,
                        "disabling POM must not disable clearcoat/anisotropy/thin");
                assertEquals(0.0f, noThin.transmission(),
                        "the thin-off variant must carry a zero transmission factor");
                assertTrue(noThin.clearcoat() && noThin.anisotropic() && noThin.parallax(),
                        "disabling thin transmission must not disable the other features");
                for (MaterialVariant variant : List.of(allFeatures, noClearcoat,
                        noAnisotropic, noParallax, noThin, allFeaturesAgain)) {
                    assertTrue(variant.directionalShadowPass(),
                            "every material variant must keep the directional shadow pass");
                    assertTrue(variant.contactShadowPass(),
                            "every material variant must declare the contact-shadow pass");
                }

                S6InteractionMatrixGlFixture.report("material",
                        "clearcoatDisabledChangedPixels",
                        S6InteractionMatrixGlFixture.requireDifferentImage(
                                allFeatures.pixels(), noClearcoat.pixels(),
                                "disabling clearcoat must change the material pixels"));
                S6InteractionMatrixGlFixture.report("material",
                        "anisotropicDisabledChangedPixels",
                        S6InteractionMatrixGlFixture.requireDifferentImage(
                                allFeatures.pixels(), noAnisotropic.pixels(),
                                "disabling anisotropy must change the material pixels"));
                S6InteractionMatrixGlFixture.report("material",
                        "parallaxDisabledChangedPixels",
                        S6InteractionMatrixGlFixture.requireDifferentImage(
                                allFeatures.pixels(), noParallax.pixels(),
                                "disabling POM must change the material pixels"));
                S6InteractionMatrixGlFixture.report("material",
                        "thinDisabledChangedPixels",
                        S6InteractionMatrixGlFixture.requireDifferentImage(
                                allFeatures.pixels(), noThin.pixels(),
                                "disabling thin transmission must change the material pixels"));
                S6InteractionMatrixGlFixture.requireSameImage(allFeatures.pixels(),
                        allFeaturesAgain.pixels(),
                        "rebuilding the same material toggles must restore the exact image");
                S6InteractionMatrixGlFixture.report("material",
                        "materialRebuildDifferingPixels",
                        S6InteractionMatrixGlFixture.differingPixels(allFeatures.pixels(),
                                allFeaturesAgain.pixels()));
            }
            S6InteractionMatrixGlFixture.requireNoLiveResources("material/shadow matrix");
        }
    }

    // 3 ------------------------------------------------------------------
    /**
     * Water/glass: water + planar reflection + caustics order in one frame, the
     * documented SSR+water rejection, and SSR post-only (no water) presence.
     */
    @Test
    void waterGlassGroupKeepsTheWaterChainAndRejectsSsrComposition() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 water matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
            try (SceneResources ground = SceneResources.unlit(0.16f, 0.22f, 0.3f)) {
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 5.0f, 9.0f),
                        new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -32.0f));
                scene.add(SceneObject.fixed(ground.mesh, ground.material,
                        new Matrix4f().translate(0.0f, -2.0f, 0.0f)
                                .rotateX((float) -Math.PI / 2.0f).scale(28.0f, 28.0f, 1.0f)));
                scene.addLight(SceneLight.directional(new Vector3f(0.15f, -1.0f, 0.25f),
                        new Vector3f(1.0f), 1.0f));
                WaterSurfaceSettings water = WaterSurfaceSettings.builder().enabled(true)
                        .level(0.0f).bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                        // Frozen waves: the disable/restore comparison renders
                        // later frames, so an animated surface would change.
                        .waveSpeed(0.0f).waveAmplitude(0.0f).build();
                CausticsSettings caustics = CausticsSettings.builder().enabled(true)
                        .waterPlane(0.0f).waterRegion(-20.0f, -20.0f, 20.0f, 20.0f)
                        .receiverHeight(-8.0f, -0.5f).intensity(3.0f)
                        .edgeFadeDistance(0.0f)
                        // Frozen caustics pattern so the restore is byte-exact.
                        .animationSpeed(0.0f).build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE))
                        .waterSurface(water)
                        // Refraction is part of the same finite-water chain; the
                        // first version rejects it together with a glass material.
                        .waterRefraction(WaterRefractionSettings.disabled())
                        .planarReflections(PlanarReflectionSettings.builder().enabled(true)
                                .planeY(0.0f).maxResolution(128).build())
                        .caustics(caustics);
                try {
                    pipeline.build();
                    assertTrue(pipeline.graph().hasPass(PlanarReflectionPass.PASS_NAME));
                    assertTrue(pipeline.graph().hasPass(CausticsPass.PASS_NAME));
                    assertTrue(pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME));
                    List<String> order = pipeline.graph().description().executionOrder();
                    assertTrue(order.indexOf(PlanarReflectionPass.PASS_NAME)
                                    < order.indexOf(CausticsPass.PASS_NAME),
                            "planar reflections must record before the caustics composite: " + order);
                    assertTrue(order.indexOf(CausticsPass.PASS_NAME)
                                    < order.indexOf(WaterSurfacePass.PASS_NAME),
                            "the caustics composite must run before the water consumer: " + order);

                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    float[] lit = S6InteractionMatrixGlFixture.capture(
                            pipeline, "water caustics enabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(lit, "water caustics enabled");
                    assertNotNull(pipeline.activeGenerationForTest().planarReflection(),
                            "the water group must own the planar reflection pass");
                    assertTrue(pipeline.activeGenerationForTest().planarReflection()
                                    .hasValidContent(),
                            "one executed frame must publish planar reflection content");

                    pipeline.applyCaustics(CausticsSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(CausticsPass.PASS_NAME));
                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    float[] withoutCaustics = S6InteractionMatrixGlFixture.capture(
                            pipeline, "water caustics disabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(withoutCaustics,
                            "water caustics disabled");
                    S6InteractionMatrixGlFixture.requireDifferentImage(lit, withoutCaustics,
                            "disabling caustics must change the water region pixels");
                    S6InteractionMatrixGlFixture.report("water",
                            "causticsDisabledChangedPixels",
                            S6InteractionMatrixGlFixture.differingPixels(lit, withoutCaustics));

                    pipeline.applyCaustics(caustics);
                    assertTrue(pipeline.graph().hasPass(CausticsPass.PASS_NAME));
                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    float[] restored = S6InteractionMatrixGlFixture.capture(
                            pipeline, "water caustics restored");
                    S6InteractionMatrixGlFixture.requireCleanFrame(restored,
                            "water caustics restored");
                    S6InteractionMatrixGlFixture.requireSameImage(lit, restored,
                            "re-enabling frozen caustics must restore the water image");
                    S6InteractionMatrixGlFixture.report("water", "causticsRestoreDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(lit, restored));

                    // One surface, one reflection strategy: the finite water
                    // surface owns planar reflections and must not also declare
                    // the opaque-only SSR pass.
                    assertFalse(pipeline.graph().hasPass(PostProcessTargets.SSR_PASS),
                            "finite water must select planar reflections, not SSR");

                    // Transparent ordering: the refraction snapshot is captured
                    // after the opaque scene colour and consumed by the water
                    // pass, which itself follows planar reflections and caustics.
                    WaterRefractionSettings refraction = waterRefraction();
                    pipeline.applyWaterRefraction(refraction);
                    assertTrue(pipeline.waterRefraction().active(),
                            "the water refraction request must be published");
                    assertTrue(pipeline.graph().hasPass(
                                    WaterSurfacePass.REFRACTION_BACKGROUND_PASS),
                            "active water refraction must declare its background copy pass");
                    List<String> refractionOrder =
                            pipeline.graph().description().executionOrder();
                    assertTrue(refractionOrder.indexOf(
                                            WaterSurfacePass.REFRACTION_BACKGROUND_PASS)
                                    > refractionOrder.indexOf(
                                            PostProcessTargets.GEOMETRY_PASS),
                            "the refraction snapshot must follow the opaque scene colour: "
                                    + refractionOrder);
                    assertTrue(refractionOrder.indexOf(
                                            WaterSurfacePass.REFRACTION_BACKGROUND_PASS)
                                    < refractionOrder.indexOf(WaterSurfacePass.PASS_NAME),
                            "the water pass must consume the refraction snapshot: "
                                    + refractionOrder);
                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    float[] refracted = S6InteractionMatrixGlFixture.capture(
                            pipeline, "water refraction enabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(refracted,
                            "water refraction enabled");
                    S6InteractionMatrixGlFixture.report("water",
                            "refractionEnabledChangedPixels",
                            S6InteractionMatrixGlFixture.requireDifferentImage(
                                    restored, refracted,
                                    "enabling water refraction must change the water pixels"));
                    S6InteractionMatrixGlFixture.report("water", "foregroundRejectionBias",
                            Float.toString(refraction.foregroundRejectionBias()));

                    pipeline.applyWaterRefraction(WaterRefractionSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(
                                    WaterSurfacePass.REFRACTION_BACKGROUND_PASS),
                            "disabling water refraction must remove the copy pass");
                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    float[] refractionRestored = S6InteractionMatrixGlFixture.capture(
                            pipeline, "water refraction restored");
                    S6InteractionMatrixGlFixture.requireCleanFrame(refractionRestored,
                            "water refraction restored");
                    S6InteractionMatrixGlFixture.requireSameImage(restored, refractionRestored,
                            "disabling refraction must restore the frozen water image");
                    S6InteractionMatrixGlFixture.report("water",
                            "refractionRestoreDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(restored,
                                    refractionRestored));

                    // The documented SSR+water exclusion is enforced by the
                    // production policy before a frame can be assembled.
                    PipelineFeaturePolicy policy = new PipelineFeaturePolicy(
                            pipeline.activeGenerationForTest().topology(), false);
                    IllegalStateException rejection = assertThrows(
                            IllegalStateException.class,
                            () -> policy.validateSsrComposition(true, false),
                            "SSR with a finite water surface must be rejected, not forced");
                    assertTrue(rejection.getMessage().contains("water"),
                            "the rejection must name the water composition: "
                                    + rejection.getMessage());
                    IllegalStateException refractionWater = assertThrows(
                            IllegalStateException.class,
                            () -> policy.validateRefractionWater(true),
                            "single-layer refraction with finite water must be rejected");
                    assertTrue(refractionWater.getMessage().contains("mutually exclusive"),
                            "the rejection must name the refraction/water exclusion: "
                                    + refractionWater.getMessage());

                    PostProcessSettings ssrRequest = pipeline.features().effective().postProcess()
                            .withSsr(SsrSettings.defaults());
                    pipeline.features().submit(pipeline.features().effective().toBuilder()
                            .postProcess(ssrRequest).build());
                    S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                    assertEquals(RenderFeatureStatus.FAILED, pipeline.features().status(),
                            "the unified request must fail closed for SSR+water");
                    assertTrue(pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME),
                            "a rejected request must keep the water generation active");
                    S6InteractionMatrixGlFixture.requireCleanFrame(
                            S6InteractionMatrixGlFixture.capture(pipeline,
                                    "water after rejected SSR"),
                            "water after rejected SSR");
                    GlDebug.assertNoError("S6 water matrix");
                } finally {
                    pipeline.close();
                }
            }

            // SSR post-only: no finite water, PBR metal receiver, static camera.
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("s6-ssr-quad")).mesh())) {
                try (PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                        "/environments/pbr/studio-small.hdr",
                        PbrEnvironmentSettings.testQuality())) {
                    Material material = PbrMaterials.create(shader,
                            new PbrMaterialProperties(new Vector4f(0.55f, 0.58f, 0.62f, 1.0f),
                                    0.9f, 0.12f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                            Map.of(), fallbacks);
                    try {
                        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 4.0f)));
                        scene.add(MeshRenderer.of(quad, material,
                                Transform.at(0.0f, 0.0f, 0.0f).scale(5.0f, 4.0f, 1.0f)));
                        scene.addLight(SceneLight.directional(new Vector3f(0.2f, -1.0f, -0.3f),
                                new Vector3f(1.0f), 3.0f));
                        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                                S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE),
                                environment);
                        try {
                            pipeline.build();
                            assertFalse(pipeline.graph().hasPass(PostProcessTargets.SSR_PASS));
                            PipelineFeaturePolicy ssrPolicy = new PipelineFeaturePolicy(
                                    pipeline.activeGenerationForTest().topology(), true);
                            IllegalStateException ssrRefraction = assertThrows(
                                    IllegalStateException.class,
                                    () -> ssrPolicy.validateSsrComposition(true, true),
                                    "SSR with a refraction material must be rejected");
                            assertTrue(ssrRefraction.getMessage().contains("refraction"),
                                    "the rejection must name the refraction composition: "
                                            + ssrRefraction.getMessage());
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                            float[] baseline = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "ssr post-only baseline");
                            S6InteractionMatrixGlFixture.requireCleanFrame(baseline,
                                    "ssr post-only baseline");

                            PostProcessSettings withSsr = pipeline.features().effective()
                                    .postProcess().withSsr(SsrSettings.defaults());
                            pipeline.features().submit(pipeline.features().effective()
                                    .toBuilder().postProcess(withSsr).build());
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                            assertEquals(RenderFeatureStatus.APPLIED,
                                    pipeline.features().status(),
                                    pipeline.features().failureReason());
                            assertTrue(pipeline.graph().hasPass(PostProcessTargets.SSR_PASS),
                                    "post-only SSR must be declarable without water");
                            assertFalse(pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME));
                            S6InteractionMatrixGlFixture.requireCleanFrame(
                                    S6InteractionMatrixGlFixture.capture(pipeline,
                                            "ssr post-only enabled"),
                                    "ssr post-only enabled");

                            pipeline.features().submit(pipeline.features().effective()
                                    .toBuilder().postProcess(PostProcessSettings.defaults())
                                    .build());
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                            assertEquals(RenderFeatureStatus.APPLIED,
                                    pipeline.features().status(),
                                    pipeline.features().failureReason());
                            assertFalse(pipeline.graph().hasPass(PostProcessTargets.SSR_PASS));
                            float[] restored = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "ssr post-only restored");
                            S6InteractionMatrixGlFixture.requireCleanFrame(restored,
                                    "ssr post-only restored");
                            S6InteractionMatrixGlFixture.requireSameImage(baseline, restored,
                                    "disabling post-only SSR must restore the exact baseline");
                            S6InteractionMatrixGlFixture.report("water",
                                    "ssrPostOnlyRestoreDifferingPixels",
                                    S6InteractionMatrixGlFixture.differingPixels(baseline,
                                            restored));
                            GlDebug.assertNoError("S6 SSR post-only matrix");
                        } finally {
                            pipeline.close();
                        }
                    } finally {
                        material.close();
                    }
                }
            }
            }
            S6InteractionMatrixGlFixture.requireNoLiveResources("water/glass matrix");
        }
    }

    // 4 ------------------------------------------------------------------
    /**
     * Particles/volumetrics: weighted OIT + volumetric particles + cloud layer
     * over the volumetric-fog colour composite, all on the single-sample path.
     */
    @Test
    void particleVolumetricGroupExecutesOitParticlesAndCloudsTogether() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 particle matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
            Scene scene = new Scene(new Camera(new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f));
            scene.addLight(SceneLight.directional(new Vector3f(0.3f, -1.0f, 0.2f),
                    new Vector3f(1.0f), 1.0f));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE))
                    .volumetricFog(vacuumFog())
                    .volumetricParticles(particles())
                    .volumetricClouds(clouds());
            try {
                pipeline.build();
                pipeline.weightedOitSurfaces(List.of(OIT_SURFACE));
                pipeline.applyWeightedOit(WeightedOitSettings.defaults());
                assertTrue(pipeline.graph().hasPass(VolumetricPassBuilder.COMPOSITE_PASS),
                        "the volumetric fog colour composite must exist");
                assertTrue(pipeline.graph().hasPass(VolumetricParticlePass.PASS_NAME),
                        "the volumetric particle bridge must exist");
                assertTrue(pipeline.graph().hasPass(VolumetricCloudPass.PASS_NAME),
                        "the cloud layer bridge must exist");
                assertTrue(pipeline.graph().hasPass(WeightedOitPass.PASS_NAME),
                        "the weighted-OIT resolve must exist");
                List<String> order = pipeline.graph().description().executionOrder();
                assertTrue(order.indexOf(VolumetricPassBuilder.COMPOSITE_PASS)
                                < order.indexOf(VolumetricParticlePass.PASS_NAME),
                        "particles must compose after the fog composite: " + order);
                assertTrue(order.indexOf(VolumetricParticlePass.PASS_NAME)
                                < order.indexOf(WeightedOitPass.PASS_NAME),
                        "the volumetric particle bridge must composite before the OIT resolve "
                                + "consumes the scene colour: " + order);

                S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                float[] enabled = S6InteractionMatrixGlFixture.capture(
                        pipeline, "particles/volumetrics enabled");
                S6InteractionMatrixGlFixture.requireCleanFrame(enabled,
                        "particles/volumetrics enabled");

                RenderFeatureSettings effective = pipeline.features().effective();
                pipeline.features().submit(effective.toBuilder()
                        .volumetricParticles(VolumetricParticleSettings.disabled()).build());
                S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                        pipeline.features().failureReason());
                assertFalse(pipeline.graph().hasPass(VolumetricParticlePass.PASS_NAME));
                float[] disabled = S6InteractionMatrixGlFixture.capture(
                        pipeline, "particles disabled");
                S6InteractionMatrixGlFixture.requireDifferentImage(enabled, disabled,
                        "disabling volumetric particles must change the composed pixels");
                S6InteractionMatrixGlFixture.report("particles",
                        "particlesDisabledChangedPixels",
                        S6InteractionMatrixGlFixture.differingPixels(enabled, disabled));

                pipeline.features().submit(effective);
                S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                        pipeline.features().failureReason());
                assertTrue(pipeline.graph().hasPass(VolumetricParticlePass.PASS_NAME));
                float[] restored = S6InteractionMatrixGlFixture.capture(
                        pipeline, "particles restored");
                S6InteractionMatrixGlFixture.requireCleanFrame(restored, "particles restored");
                S6InteractionMatrixGlFixture.requireDifferentImage(disabled, restored,
                        "re-enabling volumetric particles must re-publish their contribution");
                S6InteractionMatrixGlFixture.report("particles",
                        "particlesRestoredVsDisabledChangedPixels",
                        S6InteractionMatrixGlFixture.differingPixels(disabled, restored));
                GlDebug.assertNoError("S6 particles/volumetrics matrix");
            } finally {
                pipeline.close();
            }

            // Weighted OIT and stochastic transparency are the same
            // transparent-collection boundary: disjoint surface sets coexist,
            // while any overlap is rejected before a frame is assembled.
            try (SceneResources ground = SceneResources.unlit(0.18f, 0.24f, 0.32f)) {
                Scene stochasticScene = new Scene(new Camera(
                        new Vector3f(0.0f, 1.2f, 4.5f), new Vector3f(0.0f, 1.0f, 0.0f),
                        -90.0f, -12.0f));
                stochasticScene.add(SceneObject.fixed(ground.mesh, ground.material,
                        new Matrix4f().translate(0.0f, -1.0f, 0.0f)
                                .rotateX((float) -Math.PI / 2.0f).scale(10.0f, 10.0f, 1.0f)));
                stochasticScene.addLight(SceneLight.directional(
                        new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(1.0f), 2.0f));
                RenderPipeline stochastic = new RenderPipeline(window, stochasticScene, null,
                        S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE))
                        .stochasticTransparency(StochasticTransparencySettings.defaults());
                try {
                    stochastic.build();
                    stochastic.weightedOitSurfaces(List.of(OIT_SURFACE));
                    stochastic.applyWeightedOit(WeightedOitSettings.defaults());
                    stochastic.stochasticTransparencySurfaces(List.of(STOCHASTIC_SURFACE));
                    assertTrue(stochastic.graph().hasPass(WeightedOitPass.PASS_NAME),
                            "the disjoint OIT collection must stay active next to stochastic");
                    assertTrue(stochastic.graph().hasPass(
                                    StochasticTransparencyPass.PASS_NAME),
                            "the stochastic collection must stay active next to OIT");

                    IllegalArgumentException overlap = assertThrows(
                            IllegalArgumentException.class,
                            () -> stochastic.stochasticTransparencySurfaces(
                                    List.of(OIT_SURFACE)),
                            "a surface claimed by OIT must be rejected by the stochastic set");
                    assertTrue(overlap.getMessage().contains("weighted OIT")
                                    && overlap.getMessage().contains("stochastic"),
                            "the overlap rejection must name both collections: "
                                    + overlap.getMessage());
                    IllegalArgumentException reverseOverlap = assertThrows(
                            IllegalArgumentException.class,
                            () -> stochastic.weightedOitSurfaces(List.of(STOCHASTIC_SURFACE)),
                            "a stochastic surface must not be re-claimed by OIT");
                    assertTrue(reverseOverlap.getMessage().contains("stochastic"),
                            "the reverse overlap rejection must name the stochastic set: "
                                    + reverseOverlap.getMessage());

                    S6InteractionMatrixGlFixture.execute(stochastic, device, 3);
                    float[] stochasticEnabled = S6InteractionMatrixGlFixture.capture(
                            stochastic, "OIT + stochastic enabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(stochasticEnabled,
                            "OIT + stochastic enabled");

                    stochastic.applyStochasticTransparency(
                            StochasticTransparencySettings.disabled());
                    assertFalse(stochastic.graph().hasPass(
                                    StochasticTransparencyPass.PASS_NAME),
                            "disabling stochastic transparency must remove its pass");
                    S6InteractionMatrixGlFixture.execute(stochastic, device, 3);
                    float[] stochasticDisabled = S6InteractionMatrixGlFixture.capture(
                            stochastic, "OIT + stochastic disabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(stochasticDisabled,
                            "OIT + stochastic disabled");
                    S6InteractionMatrixGlFixture.report("particles",
                            "stochasticDisabledChangedPixels",
                            S6InteractionMatrixGlFixture.requireDifferentImage(
                                    stochasticEnabled, stochasticDisabled,
                                    "disabling stochastic transparency must change the "
                                            + "transparent pixels"));

                    stochastic.applyStochasticTransparency(
                            StochasticTransparencySettings.defaults());
                    assertTrue(stochastic.graph().hasPass(
                                    StochasticTransparencyPass.PASS_NAME),
                            "re-enabling stochastic transparency must re-declare its pass");
                    S6InteractionMatrixGlFixture.execute(stochastic, device, 3);
                    float[] stochasticRestored = S6InteractionMatrixGlFixture.capture(
                            stochastic, "OIT + stochastic restored");
                    S6InteractionMatrixGlFixture.requireCleanFrame(stochasticRestored,
                            "OIT + stochastic restored");
                    S6InteractionMatrixGlFixture.requireSameImage(stochasticEnabled,
                            stochasticRestored,
                            "the fixed-seed stochastic collection must restore its exact image");
                    S6InteractionMatrixGlFixture.report("particles",
                            "stochasticRestoreDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(stochasticEnabled,
                                    stochasticRestored));
                    GlDebug.assertNoError("S6 OIT/stochastic matrix");
                } finally {
                    stochastic.close();
                }
            }

            // Smoke: the fixed-step simulation publishes before its volume
            // consumer, and both passes are removed/restored transactionally.
            try (SceneResources ground = SceneResources.unlit(0.14f, 0.2f, 0.28f)) {
                Scene smokeScene = new Scene(new Camera(
                        new Vector3f(0.0f, 1.4f, 6.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                        -90.0f, -8.0f));
                smokeScene.add(SceneObject.fixed(ground.mesh, ground.material,
                        new Matrix4f().translate(0.0f, -1.0f, 0.0f)
                                .rotateX((float) -Math.PI / 2.0f).scale(12.0f, 12.0f, 1.0f)));
                RenderPipeline smoke = new RenderPipeline(window, smokeScene, null,
                        S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE))
                        .smokeSimulation(smokeSimulation())
                        .smokeRender(smokeRender());
                try {
                    smoke.build();
                    assertTrue(smoke.graph().hasPass(SmokeSimulation.PASS_NAME),
                            "the smoke simulation stage must exist");
                    assertTrue(smoke.graph().hasPass(SmokeVolumePass.PASS_NAME),
                            "the smoke volume consumer must exist");
                    List<String> smokeOrder = smoke.graph().description().executionOrder();
                    assertTrue(smokeOrder.indexOf(SmokeSimulation.PASS_NAME)
                                    < smokeOrder.indexOf(SmokeVolumePass.PASS_NAME),
                            "the smoke simulation must advance before its consumer: "
                                    + smokeOrder);
                    assertTrue(smokeOrder.indexOf(SmokeVolumePass.PASS_NAME)
                                    < smokeOrder.indexOf(smoke.finalPassName()),
                            "the smoke volume must compose before the final pass: "
                                    + smokeOrder);

                    S6InteractionMatrixGlFixture.execute(smoke, device, 4);
                    assertTrue(smoke.activeGenerationForTest().smokeSimulation()
                                    .stepCount() > 0,
                            "the fixed-step smoke simulation must commit a step");
                    float[] smokeEnabled = S6InteractionMatrixGlFixture.capture(
                            smoke, "smoke enabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(smokeEnabled,
                            "smoke enabled");

                    RenderFeatureSettings smokeEffective = smoke.features().effective();
                    smoke.features().submit(smokeEffective.toBuilder()
                            .smokeSimulation(SmokeSimulationSettings.disabled())
                            .smokeRender(SmokeRenderSettings.disabled())
                            .build());
                    S6InteractionMatrixGlFixture.execute(smoke, device, 4);
                    assertEquals(RenderFeatureStatus.APPLIED, smoke.features().status(),
                            smoke.features().failureReason());
                    assertFalse(smoke.graph().hasPass(SmokeSimulation.PASS_NAME),
                            "disabling smoke must remove the simulation pass");
                    assertFalse(smoke.graph().hasPass(SmokeVolumePass.PASS_NAME),
                            "disabling smoke must remove the volume consumer");
                    float[] smokeDisabled = S6InteractionMatrixGlFixture.capture(
                            smoke, "smoke disabled");
                    S6InteractionMatrixGlFixture.requireCleanFrame(smokeDisabled,
                            "smoke disabled");
                    S6InteractionMatrixGlFixture.report("particles",
                            "smokeDisabledChangedPixels",
                            S6InteractionMatrixGlFixture.requireDifferentImage(
                                    smokeEnabled, smokeDisabled,
                                    "disabling smoke must change the composed pixels"));

                    smoke.features().submit(smokeEffective);
                    S6InteractionMatrixGlFixture.execute(smoke, device, 4);
                    assertEquals(RenderFeatureStatus.APPLIED, smoke.features().status(),
                            smoke.features().failureReason());
                    assertTrue(smoke.graph().hasPass(SmokeSimulation.PASS_NAME)
                                    && smoke.graph().hasPass(SmokeVolumePass.PASS_NAME),
                            "re-enabling smoke must restore both production passes");
                    float[] smokeRestored = S6InteractionMatrixGlFixture.capture(
                            smoke, "smoke restored");
                    S6InteractionMatrixGlFixture.requireCleanFrame(smokeRestored,
                            "smoke restored");
                    S6InteractionMatrixGlFixture.requireSameImage(smokeEnabled, smokeRestored,
                            "the fixed-step smoke scene must restore its exact image");
                    S6InteractionMatrixGlFixture.report("particles",
                            "smokeRestoreDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(smokeEnabled,
                                    smokeRestored));
                    GlDebug.assertNoError("S6 smoke matrix");
                } finally {
                    smoke.close();
                }
            }
            S6InteractionMatrixGlFixture.requireNoLiveResources("particles/volumetrics matrix");
            }
        }
    }

    // 5 ------------------------------------------------------------------
    /**
     * Camera/reconstruction: TAA + motion blur + DOF, plus the SMAA alternative
     * (TAA and SMAA are mutually exclusive by the single-valued AA mode).
     */
    @Test
    void cameraReconstructionGroupCoversTaaMotionBlurDofAndSmaa() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 camera matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
            try (SceneResources resources = SceneResources.patterned(
                    0.82f, 0.2f, 0.12f, 0.1f, 0.35f, 0.9f)) {
                PostProcessSettings chain = PostProcessSettings.defaults()
                        .withDepthOfField(depthOfField())
                        .withMotionBlur(motionBlur());
                CameraRig all = cameraRig(window, resources, chain, AntiAliasingMode.TAA);
                CameraRig noMotionBlur = cameraRig(window, resources,
                        PostProcessSettings.defaults().withDepthOfField(depthOfField()),
                        AntiAliasingMode.TAA);
                CameraRig noDepthOfField = cameraRig(window, resources,
                        PostProcessSettings.defaults().withMotionBlur(motionBlur()),
                        AntiAliasingMode.TAA);
                CameraRig none = cameraRig(window, resources, chain, AntiAliasingMode.NONE);
                CameraRig allAgain = cameraRig(window, resources, chain, AntiAliasingMode.TAA);
                try {
                    all.pipeline().build();
                    noMotionBlur.pipeline().build();
                    noDepthOfField.pipeline().build();
                    none.pipeline().build();
                    allAgain.pipeline().build();
                    assertTrue(all.pipeline().graph().hasPass(PostProcessTargets.TAA_PASS));
                    assertTrue(all.pipeline().graph().hasPass(
                            PostProcessTargets.DEPTH_OF_FIELD_PASS));
                    assertTrue(all.pipeline().graph().hasPass(
                            PostProcessTargets.MOTION_BLUR_PASS));
                    assertFalse(all.pipeline().graph().hasPass(PostProcessTargets.SMAA_PASS),
                            "TAA and SMAA cannot coexist in one AA mode");
                    assertFalse(noMotionBlur.pipeline().graph()
                                    .hasPass(PostProcessTargets.MOTION_BLUR_PASS),
                            "the motion-blur-off variant must not declare MB");
                    assertTrue(noMotionBlur.pipeline().graph()
                                    .hasPass(PostProcessTargets.DEPTH_OF_FIELD_PASS),
                            "DOF must remain independent of motion blur");
                    assertFalse(noDepthOfField.pipeline().graph()
                                    .hasPass(PostProcessTargets.DEPTH_OF_FIELD_PASS),
                            "the DOF-off variant must not declare DOF");
                    assertTrue(noDepthOfField.pipeline().graph()
                                    .hasPass(PostProcessTargets.MOTION_BLUR_PASS),
                            "motion blur must remain independent of DOF");
                    assertFalse(none.pipeline().graph().hasPass(PostProcessTargets.TAA_PASS),
                            "the NONE variant must not declare TAA");
                    assertTrue(none.pipeline().graph().hasPass(
                                    PostProcessTargets.DEPTH_OF_FIELD_PASS)
                                    && none.pipeline().graph().hasPass(
                                    PostProcessTargets.MOTION_BLUR_PASS),
                            "the NONE variant must keep DOF and motion blur");

                    executeCameraPath(all.pipeline(), device, all.camera());
                    executeCameraPath(noMotionBlur.pipeline(), device,
                            noMotionBlur.camera());
                    executeCameraPath(noDepthOfField.pipeline(), device,
                            noDepthOfField.camera());
                    executeCameraPath(none.pipeline(), device, none.camera());
                    executeCameraPath(allAgain.pipeline(), device, allAgain.camera());
                    float[] allPixels = S6InteractionMatrixGlFixture.capture(
                            all.pipeline(), "camera all");
                    float[] noMbPixels = S6InteractionMatrixGlFixture.capture(
                            noMotionBlur.pipeline(), "camera MB off");
                    float[] noDofPixels = S6InteractionMatrixGlFixture.capture(
                            noDepthOfField.pipeline(), "camera DOF off");
                    float[] nonePixels = S6InteractionMatrixGlFixture.capture(
                            none.pipeline(), "camera NONE");
                    float[] allAgainPixels = S6InteractionMatrixGlFixture.capture(
                            allAgain.pipeline(), "camera all rebuilt");
                    S6InteractionMatrixGlFixture.requireCleanFrame(allPixels, "camera all");
                    S6InteractionMatrixGlFixture.requireCleanFrame(noMbPixels, "camera MB off");
                    S6InteractionMatrixGlFixture.requireCleanFrame(noDofPixels,
                            "camera DOF off");
                    S6InteractionMatrixGlFixture.requireCleanFrame(nonePixels, "camera NONE");
                    S6InteractionMatrixGlFixture.report("camera",
                            "taaVsNoneChangedPixels",
                            S6InteractionMatrixGlFixture.requireDifferentImage(allPixels,
                                    nonePixels,
                                    "the TAA jitter/history resolve must change the "
                                            + "reconstructed pixels"));
                    S6InteractionMatrixGlFixture.report("camera",
                            "motionBlurDisabledChangedPixels",
                            S6InteractionMatrixGlFixture.requireDifferentImage(allPixels,
                                    noMbPixels,
                                    "the fixed camera path must expose motion blur"));
                    S6InteractionMatrixGlFixture.report("camera",
                            "dofDisabledChangedPixels",
                            S6InteractionMatrixGlFixture.requireDifferentImage(allPixels,
                                    noDofPixels,
                                    "disabling DOF must change the reconstructed pixels"));
                    S6InteractionMatrixGlFixture.requireSameImage(allPixels, allAgainPixels,
                            "replaying the fixed camera path must restore the exact image");
                    S6InteractionMatrixGlFixture.report("camera",
                            "reconstructionRebuildDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(allPixels,
                                    allAgainPixels));
                    GlDebug.assertNoError("S6 camera TAA/MB/DOF matrix");
                } finally {
                    allAgain.pipeline().close();
                    none.pipeline().close();
                    noDepthOfField.pipeline().close();
                    noMotionBlur.pipeline().close();
                    all.pipeline().close();
                }

                CameraRig smaa = cameraRig(window, resources, chain, AntiAliasingMode.SMAA);
                try {
                    smaa.pipeline().build();
                    assertTrue(smaa.pipeline().graph().hasPass(PostProcessTargets.SMAA_PASS));
                    assertFalse(smaa.pipeline().graph().hasPass(PostProcessTargets.TAA_PASS),
                            "the SMAA alternative must not declare TAA");
                    executeCameraPath(smaa.pipeline(), device, smaa.camera());
                    int width = window.width();
                    int height = window.height();
                    byte[] withSmaa = S6InteractionMatrixGlFixture.capturePresented(
                            width, height, "camera SMAA");

                    RenderFeatureSettings effective = smaa.pipeline().features().effective();
                    smaa.pipeline().features().submit(effective.toBuilder()
                            .render(S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE))
                            .build());
                    executeCameraPath(smaa.pipeline(), device, smaa.camera());
                    assertEquals(RenderFeatureStatus.APPLIED,
                            smaa.pipeline().features().status(),
                            smaa.pipeline().features().failureReason());
                    assertFalse(smaa.pipeline().graph().hasPass(PostProcessTargets.SMAA_PASS));
                    byte[] withoutSmaa = S6InteractionMatrixGlFixture.capturePresented(
                            width, height, "camera SMAA disabled");
                    S6InteractionMatrixGlFixture.requireDifferentBytes(withSmaa, withoutSmaa,
                            "switching SMAA off must change the presented pixels");

                    smaa.pipeline().features().submit(effective);
                    executeCameraPath(smaa.pipeline(), device, smaa.camera());
                    assertEquals(RenderFeatureStatus.APPLIED,
                            smaa.pipeline().features().status(),
                            smaa.pipeline().features().failureReason());
                    assertTrue(smaa.pipeline().graph().hasPass(PostProcessTargets.SMAA_PASS));
                    byte[] restored = S6InteractionMatrixGlFixture.capturePresented(
                            width, height, "camera SMAA restored");
                    S6InteractionMatrixGlFixture.requireSameBytes(withSmaa, restored,
                            "re-enabling SMAA must restore the deterministic post chain");
                    S6InteractionMatrixGlFixture.report("camera",
                            "smaaRestoreDifferingPresentedBytes", 0);
                    GlDebug.assertNoError("S6 camera SMAA matrix");
                } finally {
                    smaa.pipeline().close();
                }
            }
            S6InteractionMatrixGlFixture.requireNoLiveResources("camera/reconstruction matrix");
            }
        }
    }

    // 6 ------------------------------------------------------------------
    /**
     * Sky/indirect: atmosphere + SSGI + cloud layer, with the documented
     * SSGI/DDGI/VCT exclusion asserted as a production rejection.
     */
    @Test
    void skyIndirectGroupCombinesAtmosphereSsgiAndClouds() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 sky matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("s6-sky-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.4f, 0.42f, 0.38f, 1.0f),
                                0.0f, 0.9f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(0.0f, 1.5f, 7.0f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 8.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(quad, material,
                                    Transform.at(0.0f, 0.0f, 0.0f)
                                            .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                            .scale(14.0f, 14.0f, 1.0f))
                            .withoutShadows());
                    scene.addLight(SceneLight.directional(new Vector3f(0.2f, -1.0f, -0.3f),
                            new Vector3f(1.0f), 2.5f));
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE),
                            environment)
                            .atmosphere(atmosphere())
                            .volumetricClouds(clouds())
                            .postProcessSettings(PostProcessSettings.defaults()
                                    .withSsgi(SsgiSettings.defaults()));
                    try {
                        pipeline.build();
                        assertNotNull(pipeline.activeGenerationForTest().atmosphereSky(),
                                "the atmosphere must publish its sky pass owner");
                        assertTrue(pipeline.graph().hasPass(PostProcessTargets.SSGI_PASS));
                        assertTrue(pipeline.graph().hasPass(VolumetricCloudPass.PASS_NAME));

                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        float[] enabled = S6InteractionMatrixGlFixture.capture(
                                pipeline, "sky SSGI enabled");
                        S6InteractionMatrixGlFixture.requireCleanFrame(enabled,
                                "sky SSGI enabled");

                        RenderFeatureSettings withSsgi = pipeline.features().effective();
                        pipeline.features().submit(withSsgi.toBuilder()
                                .postProcess(withSsgi.postProcess()
                                        .withSsgi(SsgiSettings.disabled()))
                                .build());
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        assertEquals(RenderFeatureStatus.APPLIED,
                                pipeline.features().status(),
                                pipeline.features().failureReason());
                        assertFalse(pipeline.graph().hasPass(PostProcessTargets.SSGI_PASS));
                        float[] disabled = S6InteractionMatrixGlFixture.capture(
                                pipeline, "sky SSGI disabled");
                        S6InteractionMatrixGlFixture.requireCleanFrame(disabled,
                                "sky SSGI disabled");
                        S6InteractionMatrixGlFixture.requireDifferentImage(enabled, disabled,
                                "disabling SSGI must change the indirect pixels");
                        S6InteractionMatrixGlFixture.report("sky", "ssgiDisabledChangedPixels",
                                S6InteractionMatrixGlFixture.differingPixels(enabled, disabled));

                        // SSGI + DDGI/VCT both produce indirect diffuse: the
                        // documented exclusion must fail closed at request time.
                        pipeline.features().submit(withSsgi.toBuilder()
                                .ddgi(DdgiSettings.builder().enabled(true).gridOrigin(
                                        -4.0f, 0.0f, -4.0f).spacingMetres(4.0f)
                                        .probes(2, 1, 2).build())
                                .build());
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 1);
                        assertEquals(RenderFeatureStatus.FAILED, pipeline.features().status(),
                                "SSGI + DDGI must be rejected, not forced");
                        assertTrue(pipeline.features().failureReason().contains("indirect"),
                                "the rejection must name the indirect-light conflict: "
                                        + pipeline.features().failureReason());
                        pipeline.features().submit(withSsgi.toBuilder()
                                .vct(VctSettings.enabled(32, 8.0f)).build());
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 1);
                        assertEquals(RenderFeatureStatus.FAILED, pipeline.features().status(),
                                "SSGI + VCT must be rejected, not forced");
                        assertTrue(pipeline.features().failureReason().contains("indirect"),
                                "the VCT rejection must name the indirect-light conflict: "
                                        + pipeline.features().failureReason());

                        pipeline.features().submit(withSsgi);
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        assertEquals(RenderFeatureStatus.APPLIED,
                                pipeline.features().status(),
                                pipeline.features().failureReason());
                        float[] restored = S6InteractionMatrixGlFixture.capture(
                                pipeline, "sky SSGI restored");
                        S6InteractionMatrixGlFixture.requireCleanFrame(restored,
                                "sky SSGI restored");
                        S6InteractionMatrixGlFixture.requireDifferentImage(disabled, restored,
                                "re-enabling SSGI must re-publish the indirect contribution");
                        S6InteractionMatrixGlFixture.report("sky",
                                "ssgiRestoredVsDisabledChangedPixels",
                                S6InteractionMatrixGlFixture.differingPixels(disabled, restored));

                        // Atmosphere pixel evidence: the sky owner is removed and
                        // restored on the same fixed camera/exposure.
                        pipeline.applyAtmosphere(null);
                        assertTrue(pipeline.activeGenerationForTest().atmosphereSky() == null,
                                "clearing the atmosphere must remove its sky owner");
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        float[] withoutAtmosphere = S6InteractionMatrixGlFixture.capture(
                                pipeline, "sky atmosphere disabled");
                        S6InteractionMatrixGlFixture.requireCleanFrame(withoutAtmosphere,
                                "sky atmosphere disabled");
                        S6InteractionMatrixGlFixture.report("sky",
                                "atmosphereDisabledChangedPixels",
                                S6InteractionMatrixGlFixture.requireDifferentImage(restored,
                                        withoutAtmosphere,
                                        "disabling the atmosphere must change the sky pixels"));
                        pipeline.applyAtmosphere(atmosphere());
                        assertNotNull(pipeline.activeGenerationForTest().atmosphereSky(),
                                "re-enabling the atmosphere must republish its sky owner");
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        float[] withAtmosphere = S6InteractionMatrixGlFixture.capture(
                                pipeline, "sky atmosphere restored");
                        S6InteractionMatrixGlFixture.requireCleanFrame(withAtmosphere,
                                "sky atmosphere restored");
                        S6InteractionMatrixGlFixture.report("sky",
                                "atmosphereRestoredVsDisabledChangedPixels",
                                S6InteractionMatrixGlFixture.requireDifferentImage(
                                        withoutAtmosphere, withAtmosphere,
                                        "restoring the atmosphere must republish the sky"));

                        // Cloud pixel evidence inside the atmosphere+SSGI
                        // composition; the temporal indirect history makes the
                        // restored frame a delta, not a byte-identity claim.
                        RenderFeatureSettings skyEffective = pipeline.features().effective();
                        pipeline.features().submit(skyEffective.toBuilder()
                                .volumetricClouds(VolumetricCloudSettings.disabled())
                                .build());
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        assertEquals(RenderFeatureStatus.APPLIED,
                                pipeline.features().status(),
                                pipeline.features().failureReason());
                        assertFalse(pipeline.graph().hasPass(VolumetricCloudPass.PASS_NAME),
                                "disabling clouds must remove the cloud bridge");
                        float[] withoutClouds = S6InteractionMatrixGlFixture.capture(
                                pipeline, "sky clouds disabled");
                        S6InteractionMatrixGlFixture.requireCleanFrame(withoutClouds,
                                "sky clouds disabled");
                        S6InteractionMatrixGlFixture.report("sky",
                                "cloudsDisabledChangedPixels",
                                S6InteractionMatrixGlFixture.requireDifferentImage(
                                        withAtmosphere, withoutClouds,
                                        "disabling clouds must change the sky pixels"));
                        pipeline.features().submit(skyEffective);
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        assertEquals(RenderFeatureStatus.APPLIED,
                                pipeline.features().status(),
                                pipeline.features().failureReason());
                        assertTrue(pipeline.graph().hasPass(VolumetricCloudPass.PASS_NAME),
                                "re-enabling clouds must republish the cloud bridge");
                        float[] withCloudsAgain = S6InteractionMatrixGlFixture.capture(
                                pipeline, "sky clouds restored");
                        S6InteractionMatrixGlFixture.requireCleanFrame(withCloudsAgain,
                                "sky clouds restored");
                        S6InteractionMatrixGlFixture.report("sky",
                                "cloudsRestoredVsDisabledChangedPixels",
                                S6InteractionMatrixGlFixture.requireDifferentImage(
                                        withoutClouds, withCloudsAgain,
                                        "restoring clouds must republish their contribution"));
                        GlDebug.assertNoError("S6 sky/indirect matrix");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
            S6InteractionMatrixGlFixture.requireNoLiveResources("sky/indirect matrix");
            }
        }
    }

    // 7 ------------------------------------------------------------------
    /**
     * Character: hair strands + thin/SSS PBR material on the same forward chain.
     */
    @Test
    void characterGroupCombinesHairWithThinAndSubsurfaceMaterial() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 character matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking()) {
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("s6-character-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                SubsurfaceScatteringSettings materialSubsurface =
                        SubsurfaceScatteringSettings.builder()
                                .profile(SubsurfaceScatteringSettings.Profile.UNIFORM)
                                .scatterColor(1.0f, 0.28f, 0.16f)
                                .intensity(1.2f)
                                .profileScaleMetres(0.4f)
                                .thicknessSource(
                                        SubsurfaceScatteringSettings.ThicknessSource.CONSTANT)
                                .thicknessMetres(0.12f)
                                .diffusionRadiusMetres(0.6f)
                                .screenSpaceEnabled(true)
                                .maxDistanceMetres(6.0f)
                                .build();
                Material material = PbrMaterials.createWithBindings(shader,
                        thinProperties(), Map.of(), fallbacks, CullMode.NONE, true, false,
                        0.0f, BlendMode.OPAQUE,
                        new ClearcoatSettings(true, 0.2f, 1.0f), null,
                        new AnisotropicSettings(0.5f, 0.2f),
                        ParallaxOcclusionSettings.disabled(), materialSubsurface);
                try {
                    SubsurfaceScatteringSettings screenSpace =
                            SubsurfaceScatteringSettings.builder()
                                    .profile(SubsurfaceScatteringSettings.Profile.UNIFORM)
                                    .scatterColor(1.0f, 0.3f, 0.2f)
                                    .intensity(1.0f)
                                    .profileScaleMetres(0.4f)
                                    .thicknessSource(
                                            SubsurfaceScatteringSettings.ThicknessSource.CONSTANT)
                                    .thicknessMetres(0.12f)
                                    .diffusionRadiusMetres(0.8f)
                                    .screenSpaceEnabled(true)
                                    .maxDistanceMetres(6.0f)
                                    .build();
                    PostProcessSettings sssPost = PostProcessSettings.defaults()
                            .withSubsurfaceScattering(screenSpace);

                    // MASK/dissolve: first the material alpha-cutout/queue
                    // contract, then the VFX erosion layer on the same fixed
                    // character close-up.
                    try (Texture2D maskTexture = alphaMaskMap();
                         Sampler maskSampler = Sampler.create(new Sampler.Descriptor(
                                 GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                                 GL_CLAMP_TO_EDGE))) {
                        PbrMaterialProperties maskProperties = new PbrMaterialProperties(
                                new Vector4f(1.0f, 0.92f, 0.78f, 1.0f), 0.0f, 0.5f,
                                1.0f, 1.0f, new Vector3f(), Map.of());
                        Map<PbrTextureRole, PbrTextureBinding> maskBindings = Map.of(
                                PbrTextureRole.BASE_COLOR,
                                new PbrTextureBinding(maskTexture, maskSampler));
                        Material maskMaterial = PbrMaterials.createWithBindings(shader,
                                maskProperties, maskBindings, fallbacks, CullMode.NONE,
                                false, false, 0.5f, BlendMode.OPAQUE,
                                ClearcoatSettings.disabled(), null,
                                AnisotropicSettings.disabled(),
                                ParallaxOcclusionSettings.disabled(),
                                SubsurfaceScatteringSettings.disabled());
                        Material opaqueMaterial = PbrMaterials.createWithBindings(shader,
                                maskProperties, maskBindings, fallbacks, CullMode.NONE,
                                false, false, 0.0f, BlendMode.OPAQUE,
                                ClearcoatSettings.disabled(), null,
                                AnisotropicSettings.disabled(),
                                ParallaxOcclusionSettings.disabled(),
                                SubsurfaceScatteringSettings.disabled());
                        try {
                            assertEquals(RenderQueueClass.MASKED, RenderQueueClass.classify(
                                            maskMaterial.createInstance()),
                                    "alphaCutoff must classify the material as MASKED");
                            assertEquals(RenderQueueClass.OPAQUE, RenderQueueClass.classify(
                                            opaqueMaterial.createInstance()),
                                    "a zero cutoff must stay on the opaque queue");
                            Camera maskCamera = new Camera(new Vector3f(0.0f, 0.4f, 4.0f),
                                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                            Scene maskScene = new Scene(maskCamera);
                            maskScene.add(MeshRenderer.of(quad, maskMaterial.createInstance(),
                                    Transform.at(0.0f, 0.2f, 0.0f)
                                            .scale(2.4f, 2.0f, 1.0f)));
                            maskScene.addLight(SceneLight.directional(
                                    new Vector3f(0.2f, -0.6f, -1.0f),
                                    new Vector3f(1.0f), 3.0f));
                            Camera opaqueCamera = new Camera(new Vector3f(0.0f, 0.4f, 4.0f),
                                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                            Scene opaqueScene = new Scene(opaqueCamera);
                            opaqueScene.add(MeshRenderer.of(quad,
                                    opaqueMaterial.createInstance(),
                                    Transform.at(0.0f, 0.2f, 0.0f)
                                            .scale(2.4f, 2.0f, 1.0f)));
                            opaqueScene.addLight(SceneLight.directional(
                                    new Vector3f(0.2f, -0.6f, -1.0f),
                                    new Vector3f(1.0f), 3.0f));
                            RenderPipeline maskPipeline = new RenderPipeline(window,
                                    maskScene, null,
                                    S6InteractionMatrixGlFixture.hdr(
                                            AntiAliasingMode.NONE), environment);
                            RenderPipeline opaquePipeline = new RenderPipeline(window,
                                    opaqueScene, null,
                                    S6InteractionMatrixGlFixture.hdr(
                                            AntiAliasingMode.NONE), environment);
                            try {
                                maskPipeline.build();
                                opaquePipeline.build();
                                S6InteractionMatrixGlFixture.execute(maskPipeline, device, 3);
                                S6InteractionMatrixGlFixture.execute(opaquePipeline, device, 3);
                                float[] maskedPixels = S6InteractionMatrixGlFixture.capture(
                                        maskPipeline, "character MASK");
                                float[] opaquePixels = S6InteractionMatrixGlFixture.capture(
                                        opaquePipeline, "character OPAQUE");
                                S6InteractionMatrixGlFixture.requireCleanFrame(maskedPixels,
                                        "character MASK");
                                S6InteractionMatrixGlFixture.report("character",
                                        "maskCutoffChangedPixels",
                                        S6InteractionMatrixGlFixture.requireDifferentImage(
                                                opaquePixels, maskedPixels,
                                                "alpha MASK must discard the transparent half"));
                                GlDebug.assertNoError("S6 character MASK matrix");
                            } finally {
                                opaquePipeline.close();
                                maskPipeline.close();
                            }
                        } finally {
                            opaqueMaterial.close();
                            maskMaterial.close();
                        }
                    }

                    try (EffectAsset dissolveAsset = characterDissolveAsset();
                         EffectInstance dissolveInstance = dissolveAsset.instantiate(11L);
                         VfxRenderer dissolveVfx = new VfxRenderer()) {
                        dissolveInstance.spawnMesh(new Matrix4f()
                                .translate(0.0f, 0.35f, 0.6f).scale(1.4f));
                        for (int frame = 0; frame < 4; frame++) {
                            dissolveInstance.update(0.05f,
                                    new Vector3f(0.0f, 0.4f, 0.0f));
                        }
                        EffectSnapshot baseSnapshot = dissolveInstance
                                .snapshot(new Vector3f(0.0f, 0.4f, 4.0f))
                                .withFrameValues(0.4f, 0.0f, List.of());
                        EffectSnapshot erodedSnapshot = baseSnapshot
                                .withFrameValues(0.4f, 0.55f, List.of());
                        dissolveVfx.prepare(baseSnapshot);
                        dissolveVfx.prepare(erodedSnapshot);

                        Camera camera = new Camera(new Vector3f(0.0f, 0.4f, 4.0f),
                                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                        Scene scene = characterScene(quad, material.createInstance(), camera);
                        RenderPipeline pipeline = characterPipeline(window, scene,
                                environment, sssPost, baseSnapshot, dissolveVfx);
                        try {
                            pipeline.build();
                            assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                            assertTrue(pipeline.graph().hasPass(
                                    PostProcessTargets.SUBSURFACE_SCATTERING_PASS));
                            assertTrue(pipeline.graph().hasPass(
                                    PostProcessTargets.VFX_COMPOSITE_PASS),
                                    "the character dissolve layer must declare HDR VFX");
                            assertNotNull(pipeline.activeGenerationForTest().hairStrands(),
                                    "the active generation must own the hair-strand pass");
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                            float[] enabled = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "character hair+sss+vfx enabled");
                            S6InteractionMatrixGlFixture.requireCleanFrame(enabled,
                                    "character hair+sss+vfx enabled");

                            RenderFeatureSettings effective = pipeline.features().effective();
                            pipeline.features().submit(effective.toBuilder()
                                    .hairStrands(HairStrandSettings.disabled()).build());
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                            assertEquals(RenderFeatureStatus.APPLIED,
                                    pipeline.features().status(),
                                    pipeline.features().failureReason());
                            assertFalse(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                            float[] withoutHair = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "character hair disabled");
                            S6InteractionMatrixGlFixture.requireCleanFrame(withoutHair,
                                    "character hair disabled");
                            S6InteractionMatrixGlFixture.requireDifferentImage(enabled,
                                    withoutHair,
                                    "disabling hair strands must change the character pixels");
                            S6InteractionMatrixGlFixture.report("character",
                                    "hairDisabledChangedPixels",
                                    S6InteractionMatrixGlFixture.differingPixels(enabled,
                                            withoutHair));

                            pipeline.features().submit(effective);
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                            assertEquals(RenderFeatureStatus.APPLIED,
                                    pipeline.features().status(),
                                    pipeline.features().failureReason());
                            assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                            float[] restoredHair = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "character hair restored");
                            S6InteractionMatrixGlFixture.requireCleanFrame(restoredHair,
                                    "character hair restored");
                            S6InteractionMatrixGlFixture.requireSameImage(enabled, restoredHair,
                                    "re-enabling the deterministic hair lock must restore "
                                            + "the image");
                            S6InteractionMatrixGlFixture.report("character",
                                    "hairRestoreDifferingPixels",
                                    S6InteractionMatrixGlFixture.differingPixels(enabled,
                                            restoredHair));

                            pipeline.features().submit(effective.toBuilder()
                                    .postProcess(effective.postProcess()
                                            .withSubsurfaceScattering(
                                                    SubsurfaceScatteringSettings.disabled()))
                                    .build());
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                            assertEquals(RenderFeatureStatus.APPLIED,
                                    pipeline.features().status(),
                                    pipeline.features().failureReason());
                            assertFalse(pipeline.graph().hasPass(
                                    PostProcessTargets.SUBSURFACE_SCATTERING_PASS));
                            float[] withoutSss = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "character SSS disabled");
                            S6InteractionMatrixGlFixture.requireCleanFrame(withoutSss,
                                    "character SSS disabled");
                            S6InteractionMatrixGlFixture.requireDifferentImage(enabled,
                                    withoutSss,
                                    "disabling screen-space SSS must change the character "
                                            + "pixels");
                            S6InteractionMatrixGlFixture.report("character",
                                    "sssDisabledChangedPixels",
                                    S6InteractionMatrixGlFixture.differingPixels(enabled,
                                            withoutSss));

                            pipeline.features().submit(effective);
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                            assertEquals(RenderFeatureStatus.APPLIED,
                                    pipeline.features().status(),
                                    pipeline.features().failureReason());
                            float[] restored = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "character SSS restored");
                            S6InteractionMatrixGlFixture.requireSameImage(enabled, restored,
                                    "re-enabling SSS must restore the deterministic "
                                            + "character image");
                            S6InteractionMatrixGlFixture.report("character",
                                    "sssRestoreDifferingPixels",
                                    S6InteractionMatrixGlFixture.differingPixels(enabled,
                                            restored));

                            // Thin + SSS interplay: the same SSS/hair composition
                            // with only the thin transmission inputs zeroed.
                            Material noThinMaterial = PbrMaterials.createWithBindings(shader,
                                    opaqueProperties(), Map.of(), fallbacks, CullMode.NONE,
                                    true, false, 0.0f, BlendMode.OPAQUE,
                                    new ClearcoatSettings(true, 0.2f, 1.0f), null,
                                    new AnisotropicSettings(0.5f, 0.2f),
                                    ParallaxOcclusionSettings.disabled(),
                                    materialSubsurface);
                            try {
                                assertTrue(noThinMaterial.subsurfaceScattering().active(),
                                        "the thin-off variant must keep active SSS");
                                Camera noThinCamera = new Camera(
                                        new Vector3f(0.0f, 0.4f, 4.0f),
                                        new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                                Scene noThinScene = characterScene(quad,
                                        noThinMaterial.createInstance(), noThinCamera);
                                RenderPipeline noThinPipeline = characterPipeline(window,
                                        noThinScene, environment, sssPost, baseSnapshot,
                                        dissolveVfx);
                                try {
                                    noThinPipeline.build();
                                    assertTrue(noThinPipeline.graph().hasPass(
                                                    HairStrandPass.PASS_NAME)
                                                    && noThinPipeline.graph().hasPass(
                                                    PostProcessTargets
                                                            .SUBSURFACE_SCATTERING_PASS),
                                            "the thin-off variant must keep hair and SSS");
                                    S6InteractionMatrixGlFixture.execute(noThinPipeline,
                                            device, 3);
                                    float[] noThinPixels = S6InteractionMatrixGlFixture.capture(
                                            noThinPipeline, "character thin off");
                                    S6InteractionMatrixGlFixture.requireCleanFrame(
                                            noThinPixels, "character thin off");
                                    S6InteractionMatrixGlFixture.report("character",
                                            "thinDisabledChangedPixels",
                                            S6InteractionMatrixGlFixture.requireDifferentImage(
                                                    enabled, noThinPixels,
                                                    "zero thin transmission must change the "
                                                            + "SSS composition"));
                                } finally {
                                    noThinPipeline.close();
                                }
                            } finally {
                                noThinMaterial.close();
                            }

                            // VFX dissolve/erosion (#3) on the character layer.
                            RenderPipeline dissolvePipeline = characterPipeline(window, scene,
                                    environment, sssPost, erodedSnapshot, dissolveVfx);
                            try {
                                dissolvePipeline.build();
                                assertTrue(dissolvePipeline.graph().hasPass(
                                                PostProcessTargets.VFX_COMPOSITE_PASS),
                                        "the dissolve variant must keep the VFX pass");
                                S6InteractionMatrixGlFixture.execute(dissolvePipeline,
                                        device, 3);
                                float[] dissolved = S6InteractionMatrixGlFixture.capture(
                                        dissolvePipeline, "character dissolve 0.55");
                                S6InteractionMatrixGlFixture.requireCleanFrame(dissolved,
                                        "character dissolve 0.55");
                                S6InteractionMatrixGlFixture.report("character",
                                        "dissolveProgressChangedPixels",
                                        S6InteractionMatrixGlFixture.requireDifferentImage(
                                                enabled, dissolved,
                                                "dissolve progress 0.55 must erode the "
                                                        + "character VFX layer"));
                            } finally {
                                dissolvePipeline.close();
                            }

                            // Hair camera cut: a fixed abrupt pose change must
                            // re-render the lock, and returning to the original
                            // pose must restore the deterministic image.
                            camera.setPosition(new Vector3f(0.55f, 0.45f, 3.6f));
                            camera.setYaw(-84.0f);
                            camera.setPitch(2.0f);
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                            float[] cut = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "character hair camera cut");
                            S6InteractionMatrixGlFixture.requireCleanFrame(cut,
                                    "character hair camera cut");
                            S6InteractionMatrixGlFixture.report("character",
                                    "hairCameraCutChangedPixels",
                                    S6InteractionMatrixGlFixture.requireDifferentImage(restored,
                                            cut,
                                            "the hair camera cut must change the character "
                                                    + "pixels"));
                            camera.setPosition(new Vector3f(0.0f, 0.4f, 4.0f));
                            camera.setYaw(-90.0f);
                            camera.setPitch(0.0f);
                            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                            float[] cutRestored = S6InteractionMatrixGlFixture.capture(
                                    pipeline, "character hair camera cut restored");
                            S6InteractionMatrixGlFixture.requireCleanFrame(cutRestored,
                                    "character hair camera cut restored");
                            S6InteractionMatrixGlFixture.requireSameImage(enabled, cutRestored,
                                    "returning to the fixed camera pose must restore the "
                                            + "deterministic character image");
                            S6InteractionMatrixGlFixture.report("character",
                                    "hairCameraCutRestoreDifferingPixels",
                                    S6InteractionMatrixGlFixture.differingPixels(enabled,
                                            cutRestored));
                            GlDebug.assertNoError("S6 character matrix");
                        } finally {
                            pipeline.close();
                        }
                    }
                } finally {
                    material.close();
                }
            }
            S6InteractionMatrixGlFixture.requireNoLiveResources("character matrix");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final AssetRef BATTLE_DECAL_TEXTURE =
            AssetRef.of("/vfx/decals/kenney/particle_pack/scorch_01.png");
    private static final ProjectedDecal BATTLE_DECAL = new ProjectedDecal(41L,
            BATTLE_DECAL_TEXTURE, new Vector3f(0.0f, 0.01f, -0.6f),
            new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.0f, 0.0f, -1.0f),
            new Vector2f(3.2f, 3.2f), new Vector4f(1.0f, 1.0f, 1.0f, 1.0f), 1.5f);
    private static final ProjectedDecalSettings BATTLE_DECAL_SETTINGS =
            new ProjectedDecalSettings(true, 0.6f, 0.02f, 0.5f, false, true, 8);
    /**
     * The battle VFX mesh owns the dissolve/erosion path (#3). Progress zero
     * leaves the sampled colour untouched (the documented endpoint), and the
     * zero edge width keeps the glow term out of every variant.
     */
    private static final VfxMaterial BATTLE_DISSOLVE_MATERIAL =
            VfxMaterial.builder("s6-battle-dissolve")
                    .dissolve(VfxDissolve.explicit(0.0f, new Vector3f(1.0f, 0.35f, 0.08f),
                            0.0f, 8.0f, 911L))
                    .build();

    /** Feature switches for one battle composition variant. */
    private record BattleFlags(boolean decals, boolean contact, boolean cloud,
                               boolean bloom, boolean taa, boolean vfx) {
    }

    private record BattleVariant(float[] pixels, byte[] presented, boolean vfxPass,
                                 boolean bloomPass, boolean taaPass,
                                 boolean directionalShadowPass, boolean contactShadowPass,
                                 int decalDraws) {
    }

    /** Fixed camera/scene used by every character sub-case. */
    private static Scene characterScene(Mesh quad, MaterialInstance material, Camera camera) {
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(quad, material,
                Transform.at(0.0f, 0.2f, 0.0f).scale(2.4f, 2.0f, 1.0f)));
        // Light travels toward the camera so the thin transmission/backlight
        // term contributes on the character quad.
        scene.addLight(SceneLight.directional(new Vector3f(0.2f, -0.4f, 0.95f),
                new Vector3f(1.0f), 3.0f));
        return scene;
    }

    /**
     * Character forward chain: hair strands, optional HDR VFX dissolve layer
     * and the screen-space SSS post pass on one fixed camera.
     */
    private static RenderPipeline characterPipeline(GlfwWindow window, Scene scene,
                                                    PbrEnvironment environment,
                                                    PostProcessSettings postProcess,
                                                    EffectSnapshot dissolveSnapshot,
                                                    VfxRenderer vfx) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE), environment)
                .hairStrands(hairSettings())
                .hairStrandModel(new Matrix4f().translate(0.0f, 0.35f, 0.0f)
                        .rotateZ((float) Math.toRadians(-18.0)))
                .postProcessSettings(postProcess);
        if (dissolveSnapshot != null) {
            pipeline.hdrVfxWithCamera((resources, commands, camera) ->
                    vfx.record(commands, dissolveSnapshot, camera.projection(),
                            camera.view()));
        }
        return pipeline;
    }

    private static final VfxMaterial CHARACTER_DISSOLVE_MATERIAL =
            VfxMaterial.builder("s6-character-dissolve")
                    .dissolve(VfxDissolve.explicit(0.0f, new Vector3f(1.0f, 0.3f, 0.1f),
                            0.0f, 6.0f, 77L))
                    .build();

    private static EffectAsset characterDissolveAsset() {
        return EffectAsset.builder("s6-character-dissolve")
                .meshes(new MeshVfx(1, 100.0f,
                        BuiltinMeshData.texturedQuad("s6-character-dissolve-mesh"),
                        1.4f, 1.4f, new Vector4f(1.0f, 0.42f, 0.16f, 0.9f),
                        new Vector4f(0.9f, 0.2f, 0.05f, 0.0f)))
                .meshMaterial(CHARACTER_DISSOLVE_MATERIAL)
                .build();
    }

    /** Left half transparent, right half opaque: exercises MASK cutout/queueing. */
    private static Texture2D alphaMaskMap() {
        int mapSize = 16;
        ByteBuffer data = BufferUtils.createByteBuffer(mapSize * mapSize * 4);
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                data.put((byte) 230).put((byte) 210).put((byte) 180)
                        .put((byte) (x < mapSize / 2 ? 0 : 255));
            }
        }
        data.flip();
        return Texture2D.fromRgba8(mapSize, mapSize, data, TextureColorSpace.SRGB);
    }

    private static Scene battlePbrScene(Mesh quad, Material material) {
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 2.35f, 6.2f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -17.0f));
        scene.add(MeshRenderer.of(quad, material,
                        Transform.at(0.0f, 0.0f, 0.0f)
                                .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                .scale(11.0f, 11.0f, 1.0f))
                .withoutShadows());
        scene.add(MeshRenderer.of(quad, material,
                Transform.at(0.0f, 1.5f, -3.4f).scale(5.0f, 3.0f, 1.0f)));
        scene.add(MeshRenderer.of(quad, material,
                Transform.at(1.75f, 1.1f, -1.2f).scale(1.2f, 2.2f, 1.0f)));
        scene.addLight(SceneLight.shadowedDirectional(
                new Vector3f(0.45f, -1.0f, 0.35f), new Vector3f(12.0f), 6.0f));
        return scene;
    }

    /**
     * Builds one fixed battle variant. The only inputs that differ are the
     * three feature flags under test, so a pixel difference is attributable to
     * that feature rather than a scene or camera change.
     */
    private static BattleVariant runBattleVariant(GlfwWindow window, GlRenderDevice device,
                                                  Scene scene, PbrEnvironment environment,
                                                  EffectSnapshot snapshot, VfxRenderer vfx,
                                                  int width, int height, BattleFlags flags,
                                                  String label) {
        ShadowSettings shadowSettings = new ShadowSettings(64, 8.0f, 0.1f, 40.0f, 0.05f,
                new ContactShadowSettings(flags.contact(), 3.0f, 3.0f, 32, 1.0f),
                new CloudShadowSettings(flags.cloud(), 0.85f, 0.3f, 0.12f, 0.0f, 0.65f));
        AtomicInteger decalDraws = new AtomicInteger();
        AntiAliasingMode antiAliasing = flags.taa()
                ? AntiAliasingMode.TAA : AntiAliasingMode.NONE;
        RenderSettings renderSettings = flags.bloom()
                ? S6InteractionMatrixGlFixture.hdrWithBloom(antiAliasing)
                : S6InteractionMatrixGlFixture.hdr(antiAliasing);
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                renderSettings, environment);
        if (flags.vfx() || flags.decals()) {
            pipeline.hdrVfxWithCamera((resources, commands, camera) -> {
                if (flags.vfx()) {
                    vfx.record(commands, snapshot, camera.projection(), camera.view());
                }
                if (flags.decals()) {
                    decalDraws.set(vfx.recordProjectedDecals(commands,
                            BATTLE_DECAL_SETTINGS, List.of(BATTLE_DECAL),
                            camera.projection(), camera.view(),
                            resources.depthAttachment(PostProcessTargets.VFX_SCENE_DEPTH),
                            width, height).drawn());
                }
            });
        }
        try {
            RenderFeatureSettings requested = pipeline.features().effective().toBuilder()
                    .directionalShadows(true)
                    .directionalShadowMap(new DirectionalShadowMap(shadowSettings))
                    .build();
            pipeline.features().submit(requested);
            pipeline.build();
            assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                    label + ": " + pipeline.features().failureReason());
            boolean vfxPass = pipeline.graph().hasPass(PostProcessTargets.VFX_COMPOSITE_PASS);
            boolean bloomPass = pipeline.graph().hasPass(PostProcessTargets.BLOOM_EXTRACT_PASS);
            boolean taaPass = pipeline.graph().hasPass(PostProcessTargets.TAA_PASS);
            boolean shadowPass = pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME);
            boolean contactPass = pipeline.graph()
                    .hasPass(ScreenSpaceContactShadowPass.PASS_NAME);
            S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
            float[] pixels = S6InteractionMatrixGlFixture.capture(pipeline, label);
            S6InteractionMatrixGlFixture.requireCleanFrame(pixels, label);
            byte[] presented = S6InteractionMatrixGlFixture.capturePresented(width, height,
                    label + " presented");
            return new BattleVariant(pixels, presented, vfxPass, bloomPass, taaPass,
                    shadowPass, contactPass, decalDraws.get());
        } finally {
            pipeline.close();
        }
    }

    private static Camera battleCamera() {
        return new Camera(new Vector3f(0.0f, 1.4f, 4.2f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -12.0f);
    }

    private static Scene battleScene(SceneResources resources) {
        Scene scene = new Scene(battleCamera());
        scene.add(SceneObject.fixed(resources.mesh, resources.material,
                new Matrix4f().translate(0.0f, -0.5f, 0.0f)
                        .rotateX((float) -Math.PI / 2.0f).scale(9.0f, 9.0f, 1.0f)));
        scene.add(SceneObject.fixed(resources.mesh, resources.material,
                new Matrix4f().translate(0.0f, 1.25f, -2.6f).scale(3.2f, 3.2f, 1.0f)));
        scene.addLight(SceneLight.directional(new Vector3f(0.2f, -1.0f, 0.1f),
                new Vector3f(1.0f), 2.0f));
        return scene;
    }

    private static EffectAsset battleAsset() {
        return EffectAsset.builder("s6-battle-vfx")
                .meshes(new MeshVfx(1, 100.0f,
                        BuiltinMeshData.texturedQuad("s6-battle-vfx-mesh"), 2.0f, 2.0f,
                        new Vector4f(1.0f, 0.45f, 0.12f, 1.0f),
                        new Vector4f(0.9f, 0.2f, 0.05f, 1.0f)))
                .meshMaterial(BATTLE_DISSOLVE_MATERIAL)
                .particles(new ParticleEmitter(32, 30.0f, 1.5f,
                        new Vector3f(0.0f, 0.6f, 0.0f), 0.3f, 0.2f, 0.8f,
                        new Vector3f(0.0f, -0.2f, 0.0f), 0.1f, 0.16f, 0.04f,
                        new Vector4f(1.0f, 0.55f, 0.1f, 0.85f),
                        new Vector4f(1.0f, 0.1f, 0.05f, 0.0f)))
                .build();
    }

    /** True when the fixed PBR program declares the named production uniform. */
    private static boolean hasUniform(Material material, String name) {
        return material.shader().hasUniform(name);
    }

    private record CameraRig(RenderPipeline pipeline, Camera camera) {
    }

    private static CameraRig cameraRig(GlfwWindow window, SceneResources resources,
                                       PostProcessSettings chain, AntiAliasingMode mode) {
        Camera camera = new Camera(new Vector3f(0.0f, 1.2f, 4.5f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -14.0f);
        Scene scene = new Scene(camera);
        scene.add(SceneObject.fixed(resources.mesh, resources.material,
                new Matrix4f().translate(0.0f, 0.6f, 0.0f).scale(2.6f, 2.0f, 1.0f)));
        scene.add(SceneObject.fixed(resources.mesh, resources.material,
                new Matrix4f().translate(0.95f, 0.05f, 0.4f)
                        .rotateZ(0.42f).scale(1.3f, 1.3f, 1.0f)));
        scene.addLight(SceneLight.directional(new Vector3f(0.3f, -1.0f, -0.2f),
                new Vector3f(1.0f), 2.0f));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                S6InteractionMatrixGlFixture.hdr(mode)).postProcessSettings(chain);
        return new CameraRig(pipeline, camera);
    }

    /**
     * Fixed two-frame camera path. The same deterministic pose sequence is
     * replayed by every reconstruction variant and by the SMAA disable/restore
     * segments, so motion-blur velocity and TAA history are identical inputs.
     */
    private static void executeCameraPath(RenderPipeline pipeline, GlRenderDevice device,
                                          Camera camera) {
        camera.setPosition(new Vector3f(0.0f, 1.2f, 4.5f));
        camera.setYaw(-90.0f);
        camera.setPitch(-14.0f);
        pipeline.execute(device, S6InteractionMatrixGlFixture.DELTA_SECONDS);
        camera.setPosition(new Vector3f(0.4f, 1.28f, 4.32f));
        camera.setYaw(-86.0f);
        camera.setPitch(-14.5f);
        pipeline.execute(device, S6InteractionMatrixGlFixture.DELTA_SECONDS);
    }

    private static DepthOfFieldSettings depthOfField() {
        // The scene objects sit at ~4 m while focus is deliberately at 0.3 m,
        // producing a measurable CoC radius at the fixed 96x96 extent.
        return DepthOfFieldSettings.builder().enabled(true)
                .focusDistance(0.3f).focalLengthMm(50.0f).aperture(2.8f)
                .maxCocPixels(9.0f).build();
    }

    private static MotionBlurSettings motionBlur() {
        return MotionBlurSettings.builder().enabled(true)
                .shutterFraction(0.5f).maxRadiusPixels(16.0f).build();
    }

    /** Bounded water refraction used by the ordered water/glass chain. */
    private static WaterRefractionSettings waterRefraction() {
        return WaterRefractionSettings.builder().enabled(true)
                .ior(1.333f).strength(1.5f).maximumScreenOffset(0.2f)
                .thicknessScale(2.0f).foregroundRejectionBias(0.05f)
                .offscreenFallback(WaterRefractionSettings.OffscreenFallback.UNREFRACTED)
                .build();
    }

    private static VolumetricFogSettings vacuumFog() {
        return new VolumetricFogSettings(true, 80, VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.vacuum(), List.of(), 0, true, 0.9f, 1, new Vector3f());
    }

    private static VolumetricParticleSettings particles() {
        return new VolumetricParticleSettings(true, 1.5f, 0.2f, 0.8f,
                new Vector3f(0.0f, 0.0f, -3.0f), new Vector3f(1.0f, 1.0f, 1.0f),
                48, 0.05f, new Vector3f(0.3f, -1.0f, 0.2f),
                new Vector3f(3.5f, 3.2f, 3.0f),
                0.2f, VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
    }

    private static VolumetricCloudSettings clouds() {
        return VolumetricCloudSettings.disabled().withEnabled(true)
                .withCoverageField(new CloudShadowSettings(true, 0.85f, 0.3f, 0.12f, 1.2f,
                        0.65f));
    }

    /** Fixed earth sky: one sun direction and one manual exposure for the matrix. */
    private static AtmosphereSettings atmosphere() {
        return AtmosphereSettings.earth(AtmosphereSettings.Quality.LOW)
                .withSunDirection(new Vector3f(-0.35f, -0.55f, -1.0f).normalize())
                .withExposure(1.0f);
    }

    /** Bounded 16^3 smoke source in front of the fixed camera. */
    private static SmokeSimulationSettings smokeSimulation() {
        return new SmokeSimulationSettings(true, 16, 16, 16,
                new Vector3f(0.0f, 0.0f, -2.0f), new Vector3f(1.5f),
                0.02f, new Vector3f(), 1.0f, 4.0f, 1.0f, 1.0f, 4,
                List.of(new SmokeSimulationSettings.Source(new Vector3f(0.0f, 0.0f, -2.0f),
                        1.0f, 4.0f, 2.0f, 0.0f, 0.4f)));
    }

    private static SmokeRenderSettings smokeRender() {
        return new SmokeRenderSettings(true, 0.3f, 0.7f, 2.0f,
                new Vector3f(0.3f, -1.0f, 0.2f), new Vector3f(3.0f, 2.5f, 2.0f), 0.2f,
                32, 0.05f, SmokeRenderSettings.Blend.OCCLUDING, 1.0f, 1.0f);
    }

    private static HairStrandSettings hairSettings() {
        return HairStrandSettings.builder()
                .strandCount(32).segmentsPerStrand(8).strandLengthMetres(0.9f)
                .rootSpreadMetres(0.15f).curlMetres(0.1f)
                .flowDirection(new Vector3f(1.0f, 0.0f, 0.0f))
                .widthPixels(4.0f).minimumWidthPixels(1.5f).maximumWidthPixels(10.0f)
                .nearDistanceMetres(3.0f).farDistanceMetres(8.0f)
                .lodMinimumPixels(20.0f).lodWidthScale(0.5f)
                .alpha(1.0f).alphaCutoff(0.3f)
                .transparency(HairStrandSettings.Transparency.CUTOUT)
                .rootColor(new Vector3f(0.12f, 0.08f, 0.06f))
                .tipColor(new Vector3f(0.65f, 0.5f, 0.36f))
                .diffuseStrength(0.8f).specularStrength(0.6f)
                .primaryExponent(60.0f).secondaryExponent(20.0f).secondaryWeight(0.3f)
                .colorVariation(0.0f).castsShadow(false).seed(4242L).build();
    }

    private static PbrMaterialProperties thinProperties() {
        return new PbrMaterialProperties(new Vector4f(0.72f, 0.64f, 0.56f, 1.0f),
                0.0f, 0.55f, 1.0f, 1.0f, new Vector3f(), Map.of(),
                0.35f, 0.5f, 0.35f);
    }

    /** Same surface inputs as {@link #thinProperties()} with thin transmission zeroed. */
    private static PbrMaterialProperties opaqueProperties() {
        return new PbrMaterialProperties(new Vector4f(0.72f, 0.64f, 0.56f, 1.0f),
                0.0f, 0.55f, 1.0f, 1.0f, new Vector3f(), Map.of(),
                0.0f, 0.0f, 0.0f);
    }

    private record MaterialVariant(float[] pixels, boolean clearcoat, boolean anisotropic,
                                   boolean parallax, float transmission,
                                   boolean directionalShadowPass,
                                   boolean contactShadowPass) {
    }

    /**
     * Builds one material feature variant in the fixed material/shadow scene.
     * Only the material settings differ, so a pixel delta is attributable to
     * the single toggled feature.
     */
    private static MaterialVariant runMaterialVariant(GlfwWindow window, GlRenderDevice device,
                                                      ShaderProgram shader,
                                                      PbrFallbackTextures fallbacks, Mesh quad,
                                                      PbrTextureBinding heightBinding,
                                                      PbrTextureBinding baseColorBinding,
                                                      PbrEnvironment environment,
                                                      PbrMaterialProperties properties,
                                                      ClearcoatSettings clearcoat,
                                                      AnisotropicSettings anisotropic,
                                                      ParallaxOcclusionSettings parallax,
                                                      ShadowSettings shadowSettings,
                                                      String label) {
        Material material = PbrMaterials.createWithBindings(shader, properties,
                Map.of(PbrTextureRole.HEIGHT, heightBinding,
                        PbrTextureRole.BASE_COLOR, baseColorBinding),
                fallbacks, CullMode.NONE,
                true, false, 0.0f, BlendMode.OPAQUE, clearcoat, null, anisotropic, parallax,
                SubsurfaceScatteringSettings.disabled());
        try {
            Camera camera = new Camera(new Vector3f(-2.5f, 7.0f, 9.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -40.0f);
            Scene scene = new Scene(camera);
            scene.add(MeshRenderer.of(quad, material,
                            Transform.at(0.0f, 0.0f, 0.0f)
                                    .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                    .scale(10.0f, 10.0f, 1.0f))
                    .withoutShadows());
            scene.add(MeshRenderer.of(quad, material,
                    Transform.at(0.0f, 1.0f, -1.0f).scale(3.0f, 2.0f, 1.0f)));
            // Back-lit vertical quad plus top-lit ground: the thin
            // transmission term needs light travelling toward the camera while
            // the coat/anisotropy highlights stay visible on the ground.
            scene.addLight(SceneLight.shadowedDirectional(
                    new Vector3f(0.2f, -0.4f, 0.95f), new Vector3f(3.0f), 5.0f));
            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE), environment);
            try {
                RenderFeatureSettings requested = pipeline.features().effective().toBuilder()
                        .imageBasedLighting(false)
                        .directionalShadows(true)
                        .directionalShadowMap(new DirectionalShadowMap(shadowSettings))
                        .build();
                pipeline.features().submit(requested);
                pipeline.build();
                assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                        label + ": " + pipeline.features().failureReason());
                boolean shadowPass = pipeline.graph().hasPass(DirectionalShadowMap.PASS_NAME);
                boolean contactPass = pipeline.graph()
                        .hasPass(ScreenSpaceContactShadowPass.PASS_NAME);
                S6InteractionMatrixGlFixture.execute(pipeline, device, 2);
                float[] pixels = S6InteractionMatrixGlFixture.capture(pipeline, label);
                S6InteractionMatrixGlFixture.requireCleanFrame(pixels, label);
                return new MaterialVariant(pixels, material.clearcoat().active(),
                        material.anisotropic().active(),
                        material.parallaxOcclusion().active(),
                        properties.transmissionFactor(), shadowPass, contactPass);
            } finally {
                pipeline.close();
            }
        } finally {
            material.close();
        }
    }

    private static Texture2D heightMap() {
        int mapSize = 32;
        ByteBuffer data = BufferUtils.createByteBuffer(mapSize * mapSize * 4);
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                int value = ((x / 4) + (y / 4)) % 2 == 0 ? 96 : 160;
                data.put((byte) value).put((byte) 0).put((byte) 0).put((byte) 255);
            }
        }
        data.flip();
        return Texture2D.fromRgba8(mapSize, mapSize, data, TextureColorSpace.LINEAR);
    }

    /**
     * High-frequency base-colour detail used only by the material-toggle
     * variants: a flat fallback colour cannot expose POM's UV displacement.
     */
    private static Texture2D detailMap() {
        int mapSize = 32;
        ByteBuffer data = BufferUtils.createByteBuffer(mapSize * mapSize * 4);
        for (int y = 0; y < mapSize; y++) {
            for (int x = 0; x < mapSize; x++) {
                int value = ((x / 2) + (y / 2)) % 2 == 0 ? 220 : 55;
                data.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
            }
        }
        data.flip();
        return Texture2D.fromRgba8(mapSize, mapSize, data, TextureColorSpace.SRGB);
    }

    private static final OitSurface OIT_SURFACE = OitSurface.of(
            new Vector3f(0.0f, -0.35f, -2.0f), new Vector3f(1.6f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.9f, 0.35f, 0.2f), 0.85f);

    /** Disjoint from {@link #OIT_SURFACE}; the two collections may coexist. */
    private static final OitSurface STOCHASTIC_SURFACE = OitSurface.of(
            new Vector3f(1.4f, -0.25f, -2.6f), new Vector3f(1.1f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.05f, 0.0f), new Vector3f(0.25f, 0.6f, 0.95f), 0.65f);

    /** Owns a small unlit mesh/material pair used by the simple matrix scenes. */
    private static final class SceneResources implements AutoCloseable {
        final Mesh mesh;
        final Material material;
        final ShaderProgram shader;

        private SceneResources(Mesh mesh, Material material, ShaderProgram shader) {
            this.mesh = mesh;
            this.material = material;
            this.shader = shader;
        }

        static SceneResources unlit(float red, float green, float blue) {
            ShaderProgram shader = ShaderProgram.fromSources(UNLIT_VERTEX, UNLIT_FRAGMENT);
            try {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("s6-unlit-quad"));
                try {
                    Material material = Material.builder(shader)
                            .setVec3("uColor", new Vector3f(red, green, blue)).build();
                    return new SceneResources(mesh, material, shader);
                } catch (RuntimeException | Error failure) {
                    mesh.close();
                    throw failure;
                }
            } catch (RuntimeException | Error failure) {
                shader.close();
                throw failure;
            }
        }

        /** Two-colour screen-space checker for reconstruction edge contrast. */
        static SceneResources patterned(float redA, float greenA, float blueA,
                                        float redB, float greenB, float blueB) {
            ShaderProgram shader = ShaderProgram.fromSources(UNLIT_VERTEX, PATTERN_FRAGMENT);
            try {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("s6-pattern-quad"));
                try {
                    Material material = Material.builder(shader)
                            .setVec3("uColorA", new Vector3f(redA, greenA, blueA))
                            .setVec3("uColorB", new Vector3f(redB, greenB, blueB)).build();
                    return new SceneResources(mesh, material, shader);
                } catch (RuntimeException | Error failure) {
                    mesh.close();
                    throw failure;
                }
            } catch (RuntimeException | Error failure) {
                shader.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            material.close();
            mesh.close();
            shader.close();
        }
    }
}
