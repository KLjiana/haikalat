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
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSet;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ReflectionProbeSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_RGBA8;

/**
 * Real-consumer acceptance for static local reflection probes (#18).
 *
 * <p>The fixture is one metallic-white PBR quad rendered through the
 * production {@link PbrMaterials} + {@code pbr-forward.frag} path. The global
 * environment and both probe environments are uniform test HDR fixtures, so
 * the linear HDR image isolates the probe weights: the BRDF factor cancels
 * when the same geometry is first rendered with the global environment only.
 * Probe A is warm, probe B is cool, their blend shells overlap, and the quad is
 * moved between representative world points. Each frame compares the
 * projected quad-centre pixel with an independent selection/weight reference,
 * checks the warm/cool identities, and verifies the exact global fallback
 * outside every probe and after clearing the overrides. The second case
 * injects a probe loading failure and closes a set twice under GL resource
 * tracking.</p>
 *
 * <p>This class is guarded by {@code -Dhaikalat.glSmoke=true} and is not part
 * of the default JVM test set.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ReflectionProbeGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final Vector3f CAMERA_POSITION = new Vector3f(0.0f, 0.0f, 4.0f);
    private static final float QUAD_SCALE = 0.24f;
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
             Texture2D metalRough = metallicRoughnessTexture()) {
            Material material = PbrMaterials.create(shader,
                    new PbrMaterialProperties(new Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                            1.0f, 0.1f, 0.0f, 1.0f, new Vector3f(), Map.of()),
                    Map.of(PbrTextureRole.METALLIC_ROUGHNESS, metalRough), fallbacks);
            try {
                MaterialInstance instance = material.createInstance();
                Camera camera = new Camera(CAMERA_POSITION,
                        new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                Scene scene = new Scene(camera);
                Transform transform = Transform.at(0.0f, 0.0f, 0.0f)
                        .scale(QUAD_SCALE, QUAD_SCALE, 1.0f);
                scene.add(MeshRenderer.of(quad, instance, transform));
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

                    // Probe identity: full A and full B radiance.
                    float[] probeA = probeFrame(pipeline, device, instance, probes,
                            transform, PROBE_A_X, WIDTH, HEIGHT);
                    assertChannelOrder("probe A", pixelAt(probeA, camera, PROBE_A_X,
                            WIDTH, HEIGHT), true);
                    float[] probeB = probeFrame(pipeline, device, instance, probes,
                            transform, PROBE_B_X, WIDTH, HEIGHT);
                    assertChannelOrder("probe B", pixelAt(probeB, camera, PROBE_B_X,
                            WIDTH, HEIGHT), false);

                    // The handover point must be an actual weighted blend.
                    float[] handoverGlobal = globalFrame(pipeline, device, instance,
                            transform, 0.0f, WIDTH, HEIGHT);
                    float[] handover = probeFrame(pipeline, device, instance, probes,
                            transform, 0.0f, WIDTH, HEIGHT);
                    assertSelectionMatch("handover", handover, handoverGlobal, 0.0f,
                            camera, WIDTH, HEIGHT);

                    // A sweep across both volumes must be continuous and
                    // match the independent weight reference at every point.
                    float previousLuminance = Float.NaN;
                    float worstStep = 0.0f;
                    for (float x = -1.8f; x <= 1.8f + 1.0e-4f; x += 0.06f) {
                        float[] global = globalFrame(pipeline, device, instance,
                                transform, x, WIDTH, HEIGHT);
                        float[] frame = probeFrame(pipeline, device, instance, probes,
                                transform, x, WIDTH, HEIGHT);
                        assertSelectionMatch("sweep x=" + x, frame, global, x, camera,
                                WIDTH, HEIGHT);
                        float luminance = luminance(pixelAt(frame, camera, x,
                                WIDTH, HEIGHT));
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
                    float[] globalOnly = globalFrame(pipeline, device, instance,
                            transform, 1.8f, WIDTH, HEIGHT);
                    float[] fallback = probeFrame(pipeline, device, instance, probes,
                            transform, 1.8f, WIDTH, HEIGHT);
                    assertArrayEquals(globalOnly, fallback, 0.0f,
                            "outside every probe the output must equal the global frame exactly");

                    // Clearing the overrides after probe use restores the
                    // original global image byte for byte.
                    transform.position(0.0f, 0.0f, 0.0f);
                    instance.clearOverrides();
                    float[] baseline = render(pipeline, device, WIDTH, HEIGHT);
                    PbrMaterials.bindReflectionProbes(instance, probes, new Vector3f());
                    float[] withProbes = render(pipeline, device, WIDTH, HEIGHT);
                    assertFalse(java.util.Arrays.equals(baseline, withProbes),
                            "the probe binding must change the image at the handover point");
                    instance.clearOverrides();
                    assertArrayEquals(baseline, render(pipeline, device, WIDTH, HEIGHT),
                            0.0f,
                            "clearing the probe overrides must restore the exact baseline");

                    // Probe resources are independent of render-target size.
                    pipeline.resize(96, 72);
                    transform.position(0.0f, 0.0f, 0.0f);
                    float[] resizedGlobal = globalFrame(pipeline, device, instance,
                            transform, 0.0f, 96, 72);
                    float[] resizedProbe = probeFrame(pipeline, device, instance, probes,
                            transform, 0.0f, 96, 72);
                    assertFalse(java.util.Arrays.equals(resizedGlobal, resizedProbe),
                            "local probes must still contribute after resize");
                    assertSelectionMatch("resized", resizedProbe, resizedGlobal, 0.0f,
                            camera, 96, 72);
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
                                      Transform transform, float x, int width, int height) {
        transform.position(x, 0.0f, 0.0f);
        instance.clearOverrides();
        PbrMaterials.bindReflectionProbes(instance, probes, new Vector3f(x, 0.0f, 0.0f));
        return render(pipeline, device, width, height);
    }

    private static float[] globalFrame(RenderPipeline pipeline, GlRenderDevice device,
                                       MaterialInstance instance, Transform transform,
                                       float x, int width, int height) {
        transform.position(x, 0.0f, 0.0f);
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
     * global-only frame at the same x supplies the material/BRDF factor, so
     * the expected colour is
     * {@code global * (globalWeight * GLOBAL_LEVEL + wA * warm + wB * cool)
     * / GLOBAL_LEVEL}.
     */
    private static void assertSelectionMatch(String label, float[] frame, float[] globalFrame,
                                             float x, Camera camera, int width, int height) {
        float[] weights = referenceWeights(x);
        float[] global = pixelAt(globalFrame, camera, x, width, height);
        float[] actual = pixelAt(frame, camera, x, width, height);
        for (int channel = 0; channel < 3; channel++) {
            float mixed = weights[0] * GLOBAL_LEVEL
                    + weights[1] * channel(WARM, channel)
                    + weights[2] * channel(COOL, channel);
            float expected = global[channel] * mixed / GLOBAL_LEVEL;
            float tolerance = TOLERANCE_ABSOLUTE + TOLERANCE_RELATIVE * Math.abs(expected);
            assertEquals(expected, actual[channel], tolerance,
                    label + " channel " + channel + " expected " + expected
                            + " actual " + actual[channel] + " weights="
                            + java.util.Arrays.toString(weights));
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

    /**
     * Reads the pixel containing the projected quad centre. The sweep moves
     * the quad along X, so the screen centre alone is not enough.
     */
    private static float[] pixelAt(float[] image, Camera camera, float x,
                                   int width, int height) {
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(camera, width, height,
                new Matrix4f());
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        Vector4f clip = viewProjection.transform(new Vector4f(x, 0.0f, 0.0f, 1.0f));
        assertTrue(clip.w > 0.0f, "the fixture point must be in front of the camera");
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        int pixelX = Math.max(1, Math.min(width - 2,
                (int) Math.floor((ndcX * 0.5f + 0.5f) * width)));
        int pixelY = Math.max(1, Math.min(height - 2,
                (int) Math.floor((ndcY * 0.5f + 0.5f) * height)));
        int offset = (pixelY * width + pixelX) * 4;
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
