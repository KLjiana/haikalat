package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.UniformKey;
import com.kaleblangley.haikalat.core.material.UniformValue;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureStatus;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSelector;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSet;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RGBA8;

/**
 * Real-consumer acceptance for static local reflection probes (#18).
 *
 * <p>The fixture is one metallic-grey PBR quad at the screen centre rendered
 * through the production {@link PbrMaterials} + {@code pbr-forward.frag} path.
 * The global environment and both probe environments are uniform test HDR
 * fixtures, so the linear HDR image isolates the probe weights: the BRDF
 * factor cancels when the same geometry is first rendered with the global
 * environment only. Probe A is warm and probe B is cool; their blend shells
 * overlap. The quad itself stays fixed (guaranteeing a covered centre pixel)
 * while each frame binds the production selector for a different
 * representative world point; the shader still performs its local-correction
 * lookup at the fixed receiver, but uniform probe colours make that direction
 * irrelevant to the expected weight colour. This separates the selection /
 * binding contract from object-transform updates. An independent CPU
 * reference recomputes every weight and also checks the selector's selected
 * probe identity/count before each frame. The second case injects a probe
 * loading failure and closes a set twice under GL resource tracking.</p>
 *
 * <p>This class is guarded by {@code -Dhaikalat.glSmoke=true} and is not part
 * of the default JVM test set.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ReflectionProbeGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final Vector3f CAMERA_POSITION = new Vector3f(0.0f, 0.0f, 4.0f);
    private static final float QUAD_SCALE = 1.6f;
    private static final float PROBE_HALF_EXTENT = 0.5f;
    private static final float PROBE_BLEND = 0.5f;
    private static final float PROBE_A_X = -0.5f;
    private static final float PROBE_B_X = 0.5f;
    private static final float GLOBAL_LEVEL = 0.25f;
    private static final Vector3f WARM = new Vector3f(1.0f, 10.0f / 128.0f, 5.0f / 128.0f);
    private static final Vector3f COOL = new Vector3f(5.0f / 128.0f, 10.0f / 128.0f, 1.0f);
    private static final float TOLERANCE_ABSOLUTE = 0.01f;
    private static final float TOLERANCE_RELATIVE = 0.02f;

    @Test
    void localProbesBlendContinuouslyAndFallBackToTheGlobalEnvironment() {
        try (GlfwWindow window = hiddenWindow(WIDTH, HEIGHT)) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            try (GlDebug.ResourceTrackingLease ignored = GlDebug.acquireResourceTracking()) {
                runAcceptance(window);
            }
        }
    }

    @Test
    void failedLoadRollsBackAndCloseIsIdempotent() {
        try (GlfwWindow window = hiddenWindow(64, 48)) {
            window.bindContext();
            GL.createCapabilities();
            try (GlDebug.ResourceTrackingLease ignored = GlDebug.acquireResourceTracking()) {
                runLifecycle();
            }
        }
    }

    /** Runs the probe acceptance with resource tracking active. */
    private static void runAcceptance(GlfwWindow window) {
        GlRenderDevice device = new GlRenderDevice();
        try (ShaderProgram shader = ShaderProgram.fromResource(ReflectionProbeGlTest.class,
                "/shaders/render3d/pbr/pbr-forward.vert",
                "/shaders/render3d/pbr/pbr-forward.frag");
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             Mesh quad = Mesh.from(TangentGenerator.generate(
                     BuiltinMeshData.texturedQuad("reflection-probe-quad")).mesh());
             PbrEnvironment environment = PbrEnvironmentLoader.load(device,
                     ReflectionProbeGlTest.class,
                     "/environments/pbr/probe-neutral.hdr",
                     PbrEnvironmentSettings.testQuality());
             ReflectionProbeSet probes = ReflectionProbeSet.load(device,
                     ReflectionProbeGlTest.class, List.of(probeA(), probeB()));
             Texture2D metalRough = metallicRoughnessTexture();
             ShaderProgram unlitShader = ShaderProgram.fromResource(ReflectionProbeGlTest.class,
                     "/shaders/reflection-probe-unlit.vert",
                     "/shaders/reflection-probe-unlit.frag");
             Material unlitMaterial = Material.builder(unlitShader)
                     .setInt("uUnlitVisible", 0)
                     .setInt("uClipSpace", 0)
                     .build()) {
            Material material = PbrMaterials.create(shader,
                    new PbrMaterialProperties(new Vector4f(0.5f, 0.5f, 0.5f, 1.0f),
                            1.0f, 0.1f, 0.0f, 1.0f, new Vector3f(), Map.of()),
                    Map.of(PbrTextureRole.METALLIC_ROUGHNESS, metalRough), fallbacks);
            try {
                MaterialInstance instance = material.createInstance();
                Camera camera = new Camera(CAMERA_POSITION,
                        new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                Transform identityTransform = Transform.at(0.0f, 0.0f, 0.0f)
                        .scale(QUAD_SCALE, QUAD_SCALE, 1.0f);

                // Minimal PBR-only control: same quad/material factory, a fresh
                // instance with only an emissive override, no probes, no IBL
                // and no direct light. A red centre proves the PBR material,
                // shader and draw state can render at all.
                MaterialInstance pbrOnlyInstance = material.createInstance();
                pbrOnlyInstance.setVec3("uEmissiveFactor", new Vector3f(1.0f, 0.0f, 0.0f));
                Scene pbrOnlyScene = new Scene(camera);
                pbrOnlyScene.add(MeshRenderer.of(quad, pbrOnlyInstance, identityTransform));
                pbrOnlyScene.addLight(SceneLight.directional(new Vector3f(-0.3f, -0.5f, -1.0f),
                        new Vector3f(1.0f), 1.0f));
                RenderPipeline pbrOnlyPipeline = new RenderPipeline(window, pbrOnlyScene,
                        null, RenderSettings.builder()
                                .antiAliasingMode(AntiAliasingMode.NONE)
                                .toneMappingMode(ToneMappingMode.ACES)
                                .vsync(false)
                                .build(), environment);
                try {
                    RenderFeatureSettings pbrOnlyFeatures = pbrOnlyPipeline.features()
                            .effective().toBuilder()
                            .imageBasedLighting(false)
                            .directLighting(false)
                            .directionalShadows(false)
                            .pointShadows(false)
                            .spotShadows(false)
                            .directionalCascades(DirectionalCascadeSettings.disabled())
                            .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                            .build();
                    pbrOnlyPipeline.features().submit(pbrOnlyFeatures);
                    pbrOnlyPipeline.build();
                    float[] pbrOnlyImage = render(pbrOnlyPipeline, device, WIDTH, HEIGHT);
                    assertQuadCoverage("PBR-only emissive control", pbrOnlyImage,
                            WIDTH, HEIGHT);
                    assertTrue(centrePixel(pbrOnlyImage, WIDTH, HEIGHT)[0] > 0.5f,
                            "the PBR-only emissive control must produce red at the centre; "
                                    + "if this fails, PbrMaterials/pbr-forward.frag cannot render "
                                    + "the fixture at all");
                } finally {
                    pbrOnlyPipeline.close();
                }

                Scene scene = new Scene(camera);
                Transform transform = identityTransform;
                scene.add(MeshRenderer.of(quad, instance, transform));
                // Known-good unlit control geometry behind the PBR receiver.
                // It is discarded unless the control frame explicitly enables
                // it; when the PBR receiver is discarded, red centre pixels
                // prove the geometry pass/camera/transform are working.
                MaterialInstance unlitInstance = unlitMaterial.createInstance();
                scene.add(MeshRenderer.of(quad, unlitInstance,
                        Transform.at(0.0f, 0.0f, 0.5f)
                                .scale(QUAD_SCALE, QUAD_SCALE, 1.0f)));
                // Present but disabled by the feature request; keeps the
                // fixture independent of any empty-light assumptions.
                scene.addLight(SceneLight.directional(new Vector3f(-0.3f, -0.5f, -1.0f),
                        new Vector3f(1.0f), 1.0f));
                RenderSettings renderSettings = RenderSettings.builder()
                        .antiAliasingMode(AntiAliasingMode.NONE)
                        .toneMappingMode(ToneMappingMode.ACES)
                        .vsync(false)
                        .build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        renderSettings, environment);
                try {
                    RenderFeatureSettings ibl = pipeline.features().effective().toBuilder()
                            .imageBasedLighting(true)
                            .directLighting(false)
                            .directionalShadows(false)
                            .pointShadows(false)
                            .spotShadows(false)
                            .directionalCascades(DirectionalCascadeSettings.disabled())
                            .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                            .build();
                    pipeline.features().submit(ibl);
                    pipeline.build();

                    // Geometry/camera/transform control: discard every PBR
                    // receiver fragment, enable the known unlit red quad
                    // behind it, and require red at the centre. This separates
                    // a pipeline/visibility failure from a PBR material one.
                    // The unlit quad sits in front of the receiver, so this
                    // control does not mutate the PBR instance state.
                    unlitInstance.clearOverrides();
                    unlitInstance.setInt("uUnlitVisible", 1)
                            .setInt("uClipSpace", 1);
                    float[] clipControl = render(pipeline, device, WIDTH, HEIGHT);
                    assertQuadCoverage("clip-space unlit control", clipControl,
                            WIDTH, HEIGHT);
                    assertTrue(centrePixel(clipControl, WIDTH, HEIGHT)[0] > 0.5f,
                            "the camera-independent unlit control must produce red at the "
                                    + "centre; if this fails, the geometry pass/final capture is "
                                    + "not drawing the receiver");
                    unlitInstance.setInt("uClipSpace", 0);
                    float[] cameraControl = render(pipeline, device, WIDTH, HEIGHT);
                    assertQuadCoverage("camera-space unlit control", cameraControl,
                            WIDTH, HEIGHT);
                    assertTrue(centrePixel(cameraControl, WIDTH, HEIGHT)[0] > 0.5f,
                            "the camera/model unlit control must produce red at the centre; "
                                    + "if clip-space passed, the camera/projection/model path "
                                    + "is wrong");
                    unlitInstance.clearOverrides();

                    // Verify the fixture's metallic-roughness texel is really
                    // the bound unit-2 map, not the shared fallback (which
                    // would leave metallic at zero and hide the probe lobe
                    // behind the global diffuse term).
                    Material.TextureBinding metallicBinding = material.defaultTextures()
                            .stream()
                            .filter(binding -> binding.unit() == 2)
                            .findFirst()
                            .orElseThrow();
                    assertEquals("uMetallicRoughnessMap", metallicBinding.samplerName());
                    assertSame(metalRough, metallicBinding.texture(),
                            "the fixture metallic-roughness texture must be the bound map");

                    // Emissive red proves the quad itself covers the centre
                    // pixel independent of lighting/metallic state.
                    instance.clearOverrides();
                    instance.setVec3("uEmissiveFactor", new Vector3f(1.0f, 0.0f, 0.0f));
                    float[] coverageFrame = render(pipeline, device, WIDTH, HEIGHT);
                    assertQuadCoverage("emissive coverage", coverageFrame, WIDTH, HEIGHT);
                    assertTrue(centrePixel(coverageFrame, WIDTH, HEIGHT)[0] > 0.5f,
                            "the emissive coverage frame must show the quad's red output");
                    instance.clearOverrides();

                    // With diffuse IBL disabled, a working metallic map leaves
                    // a visible specular lobe; a fallback dielectric map would
                    // leave almost nothing. This catches a silently unbound
                    // metallic-roughness texture before the probe assertions.
                    instance.clearOverrides();
                    instance.setInt("uEnableDiffuseIbl", 0);
                    float[] specularOnly = render(pipeline, device, WIDTH, HEIGHT);
                    float specularLuminance = luminance(centrePixel(specularOnly,
                            WIDTH, HEIGHT));
                    assertTrue(specularLuminance > 0.04f,
                            "the metallic map must leave a specular lobe when diffuse IBL is "
                                    + "disabled, got luminance " + specularLuminance);
                    instance.clearOverrides();

                    float[] baseline = globalFrame(pipeline, device, instance, WIDTH, HEIGHT);
                    assertQuadCoverage("baseline", baseline, WIDTH, HEIGHT);

                    // The selector itself must report the expected identity
                    // before the GPU path is exercised.
                    assertFixtureSelection(probes, PROBE_A_X, 1, 0, 1.0f);
                    assertFixtureSelection(probes, PROBE_B_X, 1, 1, 1.0f);
                    assertFixtureSelection(probes, 0.0f, 2, -1, 0.0f);
                    assertFixtureSelection(probes, 1.8f, 0, -1, 0.0f);

                    // Probe identity: full A and full B radiance.
                    float[] probeA = probeFrame(pipeline, device, instance, probes,
                            PROBE_A_X, WIDTH, HEIGHT);
                    assertChannelOrder("probe A", centrePixel(probeA, WIDTH, HEIGHT), true);
                    GlDebug.checkError("probe A binding/render");
                    float[] probeB = probeFrame(pipeline, device, instance, probes,
                            PROBE_B_X, WIDTH, HEIGHT);
                    assertChannelOrder("probe B", centrePixel(probeB, WIDTH, HEIGHT), false);

                    // The handover point must be an actual weighted blend.
                    float[] handover = probeFrame(pipeline, device, instance, probes,
                            0.0f, WIDTH, HEIGHT);
                    assertSelectionMatch("handover", handover, baseline, 0.0f,
                            WIDTH, HEIGHT);

                    // A sweep across both volumes must be continuous and match
                    // the independent weight reference at every point.
                    float previousLuminance = Float.NaN;
                    float worstStep = 0.0f;
                    for (float x = -1.8f; x <= 1.8f + 1.0e-4f; x += 0.06f) {
                        float[] frame = probeFrame(pipeline, device, instance, probes,
                                x, WIDTH, HEIGHT);
                        assertSelectionMatch("sweep x=" + x, frame, baseline, x,
                                WIDTH, HEIGHT);
                        float luminance = luminance(centrePixel(frame, WIDTH, HEIGHT));
                        if (!Float.isNaN(previousLuminance)) {
                            worstStep = Math.max(worstStep,
                                    Math.abs(luminance - previousLuminance));
                        }
                        previousLuminance = luminance;
                    }
                    assertTrue(worstStep < 0.05f,
                            "probe blend produced a visible jump of " + worstStep);

                    // Outside both probes: the shader must take the exact
                    // global path even with stale probe bindings present.
                    float[] fallback = probeFrame(pipeline, device, instance, probes,
                            1.8f, WIDTH, HEIGHT);
                    assertArrayEquals(baseline, fallback, 0.0f,
                            "outside every probe the output must equal the global frame exactly");

                    // Clearing the overrides after probe use restores the
                    // original global image byte for byte.
                    PbrMaterials.bindReflectionProbes(instance, probes, new Vector3f());
                    float[] withProbes = render(pipeline, device, WIDTH, HEIGHT);
                    assertFalse(Arrays.equals(baseline, withProbes),
                            "the probe binding must change the image at the handover point");
                    instance.clearOverrides();
                    assertArrayEquals(baseline, render(pipeline, device, WIDTH, HEIGHT),
                            0.0f,
                            "clearing the probe overrides must restore the exact baseline");

                    // Probe resources are independent of render-target size.
                    pipeline.resize(96, 72);
                    float[] resizedBaseline = globalFrame(pipeline, device, instance,
                            96, 72);
                    assertQuadCoverage("resized baseline", resizedBaseline, 96, 72);
                    float[] resizedProbe = probeFrame(pipeline, device, instance, probes,
                            0.0f, 96, 72);
                    assertFalse(Arrays.equals(resizedBaseline, resizedProbe),
                            "local probes must still contribute after resize");
                    assertSelectionMatch("resized", resizedProbe, resizedBaseline, 0.0f,
                            96, 72);
                    GlDebug.checkError("reflection probe acceptance");
                } finally {
                    pipeline.close();
                }
            } finally {
                material.close();
            }
        }
        assertTrue(GlDebug.resources().liveResources().isEmpty(),
                "reflection probe acceptance leaked resources: "
                        + GlDebug.resources().liveResources());
    }

    /** Runs the probe loading-failure/lifecycle case with resource tracking active. */
    private static void runLifecycle() {
        GlRenderDevice device = new GlRenderDevice();
        List<ReflectionProbeSettings> failing = List.of(probeA(),
                ReflectionProbeSettings.box("broken", new Vector3f(),
                        new Vector3f(1.0f), 0.5f, ReflectionProbeSettings.Quality.TEST,
                        1.0f, 0.0f, "/environments/pbr/does-not-exist.hdr"));
        assertThrows(RuntimeException.class,
                () -> ReflectionProbeSet.load(device, ReflectionProbeGlTest.class, failing),
                "a missing probe HDR must fail the whole set");
        assertTrue(GlDebug.resources().liveResources().isEmpty(),
                "failed probe loading leaked resources: "
                        + GlDebug.resources().liveResources());

        ReflectionProbeSet set = ReflectionProbeSet.load(device, ReflectionProbeGlTest.class,
                List.of(probeA()));
        assertEquals(1, set.activeProbeCount());
        assertEquals(1, set.size());
        assertFalse(set.select(new Vector3f()).isEmpty());
        set.close();
        set.close();
        assertTrue(set.isClosed());
        assertThrows(GlException.class, () -> set.select(new Vector3f()));
        assertTrue(GlDebug.resources().liveResources().isEmpty(),
                "closing a probe set must release every owned resource: "
                        + GlDebug.resources().liveResources());
        GlDebug.checkError("reflection probe lifecycle");
    }

    private static ReflectionProbeSettings probeA() {
        return ReflectionProbeSettings.box("warm", new Vector3f(PROBE_A_X, 0.0f, 0.0f),
                new Vector3f(PROBE_HALF_EXTENT), PROBE_BLEND,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f,
                "/environments/pbr/probe-warm.hdr");
    }

    private static ReflectionProbeSettings probeB() {
        return ReflectionProbeSettings.box("cool", new Vector3f(PROBE_B_X, 0.0f, 0.0f),
                new Vector3f(PROBE_HALF_EXTENT), PROBE_BLEND,
                ReflectionProbeSettings.Quality.TEST, 1.0f, 0.0f,
                "/environments/pbr/probe-cool.hdr");
    }

    private static float[] probeFrame(RenderPipeline pipeline, GlRenderDevice device,
                                      MaterialInstance instance, ReflectionProbeSet probes,
                                      float selectorPointX, int width, int height) {
        int expectedCount = probes.select(new Vector3f(selectorPointX, 0.0f, 0.0f)).count();
        instance.clearOverrides();
        PbrMaterials.bindReflectionProbes(instance, probes,
                new Vector3f(selectorPointX, 0.0f, 0.0f));
        assertEquals(expectedCount, probeCountOverride(instance),
                "the Java-side probe count must match the selector at x=" + selectorPointX);
        if (expectedCount > 0) {
            assertTrue(instance.uniformOverrides().containsKey(
                            UniformKey.int1("uReflectionProbe0")),
                    "the probe sampler uniform must be uploaded before the draw");
        }
        return render(pipeline, device, width, height);
    }

    private static int probeCountOverride(MaterialInstance instance) {
        UniformValue value = instance.uniformOverrides().get(
                UniformKey.int1("uReflectionProbeCount"));
        assertNotNull(value, "uReflectionProbeCount override must exist after probe binding");
        assertTrue(value instanceof UniformValue.IntVal,
                "uReflectionProbeCount must be an int override");
        return ((UniformValue.IntVal) value).value();
    }

    private static float[] globalFrame(RenderPipeline pipeline, GlRenderDevice device,
                                       MaterialInstance instance, int width, int height) {
        instance.clearOverrides();
        return render(pipeline, device, width, height);
    }

    private static float[] render(RenderPipeline pipeline, GlRenderDevice device,
                                  int width, int height) {
        pipeline.execute(device);
        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                "the submitted feature request must publish before readback");
        float[] image = pipeline.captureLinearHdrRgbaFloat();
        assertEquals(width * height * 4, image.length,
                "the fixture expects a " + width + "x" + height + " framebuffer");
        return image;
    }

    private static void assertFixtureSelection(ReflectionProbeSet probes, float x,
                                               int expectedCount, int expectedFirstIndex,
                                               float expectedFirstWeight) {
        ReflectionProbeSelector.Selection selection = probes.select(
                new Vector3f(x, 0.0f, 0.0f));
        assertEquals(expectedCount, selection.count(),
                "selector count at x=" + x + " (selection=" + selection + ")");
        if (expectedCount == 0) {
            assertEquals(1.0f, selection.globalWeight(), 1.0e-5f,
                    "outside every probe the selector must return the global fallback");
            return;
        }
        assertEquals(expectedFirstIndex, selection.probes().getFirst().probeIndex(),
                "selector identity at x=" + x);
        assertEquals(expectedFirstWeight, selection.probes().getFirst().weight(), 1.0e-4f,
                "selector weight at x=" + x);
        if (expectedCount == 2) {
            assertEquals(0.0f, selection.globalWeight(), 1.0e-5f,
                    "two full-overlap probes must leave no global weight");
            assertEquals(1.0f, selection.totalProbeWeight(), 1.0e-5f);
        }
    }

    /**
     * Verifies that the fixed quad actually covers the sampled centre pixel.
     * A background-only pixel would make every later comparison vacuous.
     */
    private static void assertQuadCoverage(String label, float[] image, int width, int height) {
        float[] centre = centrePixel(image, width, height);
        float[] background = pixelAt(image, width, height, 2, 2);
        float delta = Math.max(Math.abs(centre[0] - background[0]),
                Math.max(Math.abs(centre[1] - background[1]),
                        Math.abs(centre[2] - background[2])));
        assertTrue(delta > 0.005f, label + ": the fixed quad must cover the centre pixel; "
                + "centre=" + describe(centre) + " background=" + describe(background));
    }

    private static void assertChannelOrder(String label, float[] pixel, boolean warmDominant) {
        assertTrue(pixel[0] + pixel[1] + pixel[2] > 0.0f, label + " is black");
        if (warmDominant) {
            assertTrue(pixel[0] > pixel[2] * 4.0f,
                    label + " must be warm (red-dominant), got " + describe(pixel));
        } else {
            assertTrue(pixel[2] > pixel[0] * 4.0f,
                    label + " must be cool (blue-dominant), got " + describe(pixel));
        }
    }

    /**
     * Compares one probe frame with the independent weight reference. The
     * global-only frame supplies the material/BRDF factor, so the expected
     * colour is
     * {@code global * (globalWeight * GLOBAL_LEVEL + wA * warm + wB * cool)
     * / GLOBAL_LEVEL}.
     */
    private static void assertSelectionMatch(String label, float[] frame, float[] globalFrame,
                                             float x, int width, int height) {
        float[] weights = referenceWeights(x);
        float[] global = centrePixel(globalFrame, width, height);
        float[] actual = centrePixel(frame, width, height);
        for (int channel = 0; channel < 3; channel++) {
            float mixed = weights[0] * GLOBAL_LEVEL
                    + weights[1] * channel(WARM, channel)
                    + weights[2] * channel(COOL, channel);
            float expected = global[channel] * mixed / GLOBAL_LEVEL;
            float tolerance = TOLERANCE_ABSOLUTE + TOLERANCE_RELATIVE * Math.abs(expected);
            assertEquals(expected, actual[channel], tolerance,
                    label + " channel " + channel + " expected " + expected
                            + " actual " + actual[channel] + " weights="
                            + Arrays.toString(weights));
        }
    }

    /** Independent double-precision selection reference for the two box probes. */
    private static float[] referenceWeights(float x) {
        double first = referenceWeight(x, PROBE_A_X);
        double second = referenceWeight(x, PROBE_B_X);
        double total = first + second;
        if (total <= 0.0) return new float[]{1.0f, 0.0f, 0.0f};
        double global = total >= 1.0 ? 0.0 : 1.0 - total;
        double scale = total > 1.0 ? 1.0 / total : 1.0;
        return new float[]{(float) global, (float) (first * scale),
                (float) (second * scale)};
    }

    private static double referenceWeight(double x, double probeX) {
        double local = Math.abs(x - probeX) - PROBE_HALF_EXTENT;
        double outside = Math.max(local, 0.0);
        if (local <= 0.0) {
            double inset = Math.max(0.0, Math.min(1.0, -local / PROBE_HALF_EXTENT));
            return 0.5 + 0.5 * inset * inset * (3.0 - 2.0 * inset);
        }
        double t = Math.max(0.0, Math.min(1.0, outside / PROBE_BLEND));
        return 0.5 * (1.0 - t * t * (3.0 - 2.0 * t));
    }

    private static float[] centrePixel(float[] image, int width, int height) {
        return pixelAt(image, width, height, width / 2, height / 2);
    }

    private static float[] pixelAt(float[] image, int width, int height, int x, int y) {
        int offset = (Math.max(0, Math.min(height - 1, y)) * width
                + Math.max(0, Math.min(width - 1, x))) * 4;
        return new float[]{image[offset], image[offset + 1], image[offset + 2],
                image[offset + 3]};
    }

    private static float channel(Vector3f color, int channel) {
        return channel == 0 ? color.x : channel == 1 ? color.y : color.z;
    }

    private static float luminance(float[] pixel) {
        return 0.2126f * pixel[0] + 0.7152f * pixel[1] + 0.0722f * pixel[2];
    }

    private static String describe(float[] pixel) {
        return "(" + pixel[0] + ", " + pixel[1] + ", " + pixel[2] + ")";
    }

    /**
     * One fully metallic, maximally rough texel. The material factors scale it
     * to roughness 0.1 and metallic 1, so the fixture has no diffuse IBL and
     * the probe colour mixing is isolated in the specular term.
     */
    private static Texture2D metallicRoughnessTexture() {
        Texture2D texture = Texture2D.createEmpty(1, 1, GL_RGBA8);
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(4)
                    .put((byte) 0).put((byte) 255).put((byte) 255).put((byte) 255)
                    .flip();
            texture.uploadRegion(0, 0, 1, 1, data);
            return texture;
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
    }

    private static GlfwWindow hiddenWindow(int width, int height) {
        return new GlfwWindow.Builder().dimensions(width, height)
                .title("Reflection probe GL test").visible(false).build();
    }
}
