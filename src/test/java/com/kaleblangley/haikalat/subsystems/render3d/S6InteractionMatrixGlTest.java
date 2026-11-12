package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
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
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vct.VctSettings;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.VfxRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudPass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricCloudSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticlePass;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricPassBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfacePass;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.vfx.EffectAsset;
import com.kaleblangley.haikalat.subsystems.vfx.EffectInstance;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.MeshVfx;
import com.kaleblangley.haikalat.subsystems.vfx.ParticleEmitter;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
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
        }
    }

    // 2 ------------------------------------------------------------------
    /**
     * Material/shadow: thin/clearcoat/anisotropic/POM inputs in one forward
     * material plus contact and cloud shadows on the same directional light.
     */
    @Test
    void materialShadowGroupKeepsBoundedInputsWithContactAndCloudShadows() {
        try (GlfwWindow window = S6InteractionMatrixGlFixture.window("S6 material matrix")) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("s6-material-quad")).mesh());
                 Texture2D heightMap = heightMap();
                 Sampler heightSampler = Sampler.create(new Sampler.Descriptor(
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
            }
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
                    S6InteractionMatrixGlFixture.requireSameImage(lit, restored,
                            "re-enabling frozen caustics must restore the water image");
                    S6InteractionMatrixGlFixture.report("water", "causticsRestoreDifferingPixels",
                            S6InteractionMatrixGlFixture.differingPixels(lit, restored));

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
                                < order.indexOf(WeightedOitPass.PASS_NAME)
                                || order.indexOf(WeightedOitPass.PASS_NAME)
                                < order.indexOf(VolumetricParticlePass.PASS_NAME),
                        "the transparent strategy and the particle bridge share the frame: "
                                + order);

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
            try (SceneResources resources = SceneResources.unlit(0.32f, 0.38f, 0.52f)) {
                PostProcessSettings chain = PostProcessSettings.defaults()
                        .withDepthOfField(depthOfField())
                        .withMotionBlur(motionBlur());
                RenderPipeline taa = cameraPipeline(window, resources, chain,
                        AntiAliasingMode.TAA);
                RenderPipeline none = cameraPipeline(window, resources, chain,
                        AntiAliasingMode.NONE);
                try {
                    taa.build();
                    none.build();
                    assertTrue(taa.graph().hasPass(PostProcessTargets.TAA_PASS));
                    assertTrue(taa.graph().hasPass(PostProcessTargets.DEPTH_OF_FIELD_PASS));
                    assertTrue(taa.graph().hasPass(PostProcessTargets.MOTION_BLUR_PASS));
                    assertFalse(taa.graph().hasPass(PostProcessTargets.SMAA_PASS),
                            "TAA and SMAA cannot coexist in one AA mode");

                    S6InteractionMatrixGlFixture.execute(taa, device, 3);
                    S6InteractionMatrixGlFixture.execute(none, device, 3);
                    float[] taaPixels = S6InteractionMatrixGlFixture.capture(taa, "camera TAA");
                    float[] nonePixels = S6InteractionMatrixGlFixture.capture(none, "camera NONE");
                    S6InteractionMatrixGlFixture.requireCleanFrame(taaPixels, "camera TAA");
                    S6InteractionMatrixGlFixture.requireDifferentImage(taaPixels, nonePixels,
                            "the TAA jitter/history resolve must change the reconstructed pixels");
                    S6InteractionMatrixGlFixture.report("camera", "taaVsNoneChangedPixels",
                            S6InteractionMatrixGlFixture.differingPixels(taaPixels, nonePixels));
                    GlDebug.assertNoError("S6 camera TAA matrix");
                } finally {
                    none.close();
                    taa.close();
                }

                RenderPipeline smaa = cameraPipeline(window, resources, chain,
                        AntiAliasingMode.SMAA);
                try {
                    smaa.build();
                    assertTrue(smaa.graph().hasPass(PostProcessTargets.SMAA_PASS));
                    assertFalse(smaa.graph().hasPass(PostProcessTargets.TAA_PASS),
                            "the SMAA alternative must not declare TAA");
                    S6InteractionMatrixGlFixture.execute(smaa, device, 2);
                    int width = window.width();
                    int height = window.height();
                    byte[] withSmaa = S6InteractionMatrixGlFixture.capturePresented(
                            width, height, "camera SMAA");

                    RenderFeatureSettings effective = smaa.features().effective();
                    smaa.features().submit(effective.toBuilder()
                            .render(S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE))
                            .build());
                    S6InteractionMatrixGlFixture.execute(smaa, device, 2);
                    assertEquals(RenderFeatureStatus.APPLIED, smaa.features().status(),
                            smaa.features().failureReason());
                    assertFalse(smaa.graph().hasPass(PostProcessTargets.SMAA_PASS));
                    byte[] withoutSmaa = S6InteractionMatrixGlFixture.capturePresented(
                            width, height, "camera SMAA disabled");
                    S6InteractionMatrixGlFixture.requireDifferentBytes(withSmaa, withoutSmaa,
                            "switching SMAA off must change the presented pixels");

                    smaa.features().submit(effective);
                    S6InteractionMatrixGlFixture.execute(smaa, device, 2);
                    assertEquals(RenderFeatureStatus.APPLIED, smaa.features().status(),
                            smaa.features().failureReason());
                    assertTrue(smaa.graph().hasPass(PostProcessTargets.SMAA_PASS));
                    byte[] restored = S6InteractionMatrixGlFixture.capturePresented(
                            width, height, "camera SMAA restored");
                    S6InteractionMatrixGlFixture.requireSameBytes(withSmaa, restored,
                            "re-enabling SMAA must restore the deterministic post chain");
                    S6InteractionMatrixGlFixture.report("camera",
                            "smaaRestoreDifferingPresentedBytes", 0);
                    GlDebug.assertNoError("S6 camera SMAA matrix");
                } finally {
                    smaa.close();
                }
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
                            .atmosphere(AtmosphereSettings.earth(
                                            AtmosphereSettings.Quality.LOW)
                                    .withSunDirection(new Vector3f(-0.35f, -0.55f, -1.0f)
                                            .normalize())
                                    .withExposure(1.0f))
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
                        GlDebug.assertNoError("S6 sky/indirect matrix");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
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
                    Camera camera = new Camera(new Vector3f(0.0f, 0.4f, 4.0f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(quad, material.createInstance(),
                            Transform.at(0.0f, 0.2f, 0.0f).scale(2.4f, 2.0f, 1.0f)));
                    scene.addLight(SceneLight.directional(new Vector3f(0.2f, -0.6f, -1.0f),
                            new Vector3f(1.0f), 3.0f));
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            S6InteractionMatrixGlFixture.hdr(AntiAliasingMode.NONE), environment)
                            .hairStrands(hairSettings())
                            .hairStrandModel(new Matrix4f().translate(0.0f, 0.35f, 0.0f)
                                    .rotateZ((float) Math.toRadians(-18.0)))
                            .postProcessSettings(PostProcessSettings.defaults()
                                    .withSubsurfaceScattering(screenSpace));
                    try {
                        pipeline.build();
                        assertTrue(pipeline.graph().hasPass(HairStrandPass.PASS_NAME));
                        assertTrue(pipeline.graph().hasPass(
                                PostProcessTargets.SUBSURFACE_SCATTERING_PASS));
                        assertNotNull(pipeline.activeGenerationForTest().hairStrands(),
                                "the active generation must own the hair-strand pass");
                        S6InteractionMatrixGlFixture.execute(pipeline, device, 3);
                        float[] enabled = S6InteractionMatrixGlFixture.capture(
                                pipeline, "character hair+sss enabled");
                        S6InteractionMatrixGlFixture.requireCleanFrame(enabled,
                                "character hair+sss enabled");

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
                        S6InteractionMatrixGlFixture.requireDifferentImage(enabled, withoutHair,
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
                        S6InteractionMatrixGlFixture.requireSameImage(enabled, restoredHair,
                                "re-enabling the deterministic hair lock must restore the image");
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
                        S6InteractionMatrixGlFixture.requireDifferentImage(enabled, withoutSss,
                                "disabling screen-space SSS must change the character pixels");
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
                                "re-enabling SSS must restore the deterministic character image");
                        S6InteractionMatrixGlFixture.report("character",
                                "sssRestoreDifferingPixels",
                                S6InteractionMatrixGlFixture.differingPixels(enabled, restored));
                        GlDebug.assertNoError("S6 character matrix");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

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
                .particles(new ParticleEmitter(32, 30.0f, 1.5f,
                        new Vector3f(0.0f, 0.6f, 0.0f), 0.3f, 0.2f, 0.8f,
                        new Vector3f(0.0f, -0.2f, 0.0f), 0.1f, 0.16f, 0.04f,
                        new Vector4f(1.0f, 0.55f, 0.1f, 0.85f),
                        new Vector4f(1.0f, 0.1f, 0.05f, 0.0f)))
                .build();
    }

    private static RenderPipeline cameraPipeline(GlfwWindow window, SceneResources resources,
                                                 PostProcessSettings chain,
                                                 AntiAliasingMode mode) {
        Scene scene = new Scene(new Camera(new Vector3f(0.0f, 1.2f, 4.5f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -14.0f));
        scene.add(SceneObject.fixed(resources.mesh, resources.material,
                new Matrix4f().translate(0.0f, 0.6f, 0.0f).scale(2.6f, 2.0f, 1.0f)));
        scene.add(SceneObject.fixed(resources.mesh, resources.material,
                new Matrix4f().translate(0.95f, 0.05f, 0.4f)
                        .rotateZ(0.42f).scale(1.3f, 1.3f, 1.0f)));
        scene.addLight(SceneLight.directional(new Vector3f(0.3f, -1.0f, -0.2f),
                new Vector3f(1.0f), 2.0f));
        return new RenderPipeline(window, scene, null,
                S6InteractionMatrixGlFixture.hdr(mode)).postProcessSettings(chain);
    }

    private static DepthOfFieldSettings depthOfField() {
        return DepthOfFieldSettings.builder().enabled(true)
                .focusDistance(4.5f).focalLengthMm(50.0f).aperture(2.8f)
                .maxCocPixels(9.0f).build();
    }

    private static MotionBlurSettings motionBlur() {
        return MotionBlurSettings.builder().enabled(true)
                .shutterFraction(0.5f).maxRadiusPixels(16.0f).build();
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

    private static final OitSurface OIT_SURFACE = OitSurface.of(
            new Vector3f(0.0f, -0.35f, -2.0f), new Vector3f(1.6f, 0.0f, 0.0f),
            new Vector3f(0.0f, 1.0f, 0.0f), new Vector3f(0.9f, 0.35f, 0.2f), 0.85f);

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

        @Override
        public void close() {
            material.close();
            mesh.close();
            shader.close();
        }
    }
}
