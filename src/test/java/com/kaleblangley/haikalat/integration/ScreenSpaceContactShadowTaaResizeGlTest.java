package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ContactShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.DirectionalShadowMap;
import com.kaleblangley.haikalat.subsystems.render3d.shadow.ScreenSpaceContactShadowPass;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL temporal and resize acceptance for #9 screen-space contact shadows.
 *
 * <p>TAA records the pass with its actual four-phase projection jitter.  The
 * contact darkening is measured both before the TAA resolve (the pass output,
 * per jittered frame) and after the resolve (the displayed colour), and compared
 * against the stable NONE-mode result.  Resize drives 160x120 to 200x160 and back
 * through the full pipeline, asserting the graph and the pass-owned target are
 * rebuilt, the contact point still darkens and resource tracking does not grow.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ScreenSpaceContactShadowTaaResizeGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final Vector3f LIGHT = new Vector3f(0.3f, -1.0f, 0.2f);
    private static final ContactShadowSettings CONTACT =
            new ContactShadowSettings(true, 2.0f, 0.6f, 24, 1.0f);
    private static final float DARKENING = 2.0e-3f;
    private static final float COLOR_TOLERANCE = 2.0e-3f;

    @Test
    void taaJitterKeepsTheContactDarkeningAnchoredToTheWorldContact() {
        try (GlfwWindow window = window("TAA contact-shadow stability")) {
            bindContext(window);
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram floorShader = ShaderProgram.fromSources(FLOOR_VERTEX,
                         FLOOR_FRAGMENT);
                 ShaderProgram pbrShader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh floor = Mesh.from(BuiltinMeshData.coloredQuad("taa-contact-floor"));
                 Mesh wall = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("taa-contact-wall")).mesh());
                 PbrEnvironment environment = environment(device)) {
                Material floorMaterial = Material.builder(floorShader).build();
                Material wallMaterial = pbrMaterial(pbrShader, fallbacks);
                try {
                    Camera camera = standardCamera();
                    RenderPipeline noneBaseline = build(window, scene(camera, floor,
                            floorMaterial, wall, wallMaterial), environment,
                            AntiAliasingMode.NONE, false);
                    RenderPipeline noneContact = build(window, scene(camera, floor,
                            floorMaterial, wall, wallMaterial), environment,
                            AntiAliasingMode.NONE, true);
                    RenderPipeline taaBaseline = build(window, scene(camera, floor,
                            floorMaterial, wall, wallMaterial), environment,
                            AntiAliasingMode.TAA, false);
                    RenderPipeline taaContact = build(window, scene(camera, floor,
                            floorMaterial, wall, wallMaterial), environment,
                            AntiAliasingMode.TAA, true);
                    try {
                        noneBaseline.execute(device, 1.0f / 60.0f);
                        noneContact.execute(device, 1.0f / 60.0f);
                        float[] stableBaseline = noneBaseline.captureLinearHdrRgbaFloat();
                        float[] stableContact = noneContact.captureLinearHdrRgbaFloat();
                        Darkening stable = darkening(stableBaseline, stableContact);
                        assertTrue(stable.total > 1.0e-3f,
                                "the stable NONE reference must contain contact darkening");

                        List<Darkening> preFrames = new ArrayList<>();
                        List<Darkening> postFrames = new ArrayList<>();
                        for (int frame = 0; frame < 8; frame++) {
                            taaBaseline.execute(device, 1.0f / 60.0f);
                            taaContact.execute(device, 1.0f / 60.0f);
                            if (frame < 4) continue;
                            float[] preBaseline = taaBaseline.graph()
                                    .readColorAttachmentRgbaFloat(
                                            PostProcessTargets.SCENE_COLOR);
                            float[] preContact = taaContact.graph()
                                    .readColorAttachmentRgbaFloat(
                                            PostProcessTargets.SCENE_COLOR);
                            preFrames.add(darkening(preBaseline, preContact));
                            postFrames.add(darkening(
                                    taaBaseline.captureLinearHdrRgbaFloat(),
                                    taaContact.captureLinearHdrRgbaFloat()));
                        }
                        assertEquals(4, preFrames.size());

                        double maxPostOffset = 0.0;
                        double maxPreOffset = 0.0;
                        for (Darkening frame : preFrames) {
                            assertTrue(frame.total > 1.0e-3f,
                                    "every jittered frame must keep the contact darkening");
                            maxPreOffset = Math.max(maxPreOffset,
                                    frame.distanceTo(stable));
                        }
                        for (Darkening frame : postFrames) {
                            assertTrue(frame.total > 1.0e-3f,
                                    "the resolved frame must keep the contact darkening");
                            maxPostOffset = Math.max(maxPostOffset,
                                    frame.distanceTo(stable));
                        }
                        assertTrue(maxPreOffset <= 1.25,
                                "the per-jitter pass output must stay within 1.25 px of the "
                                        + "stable world contact, max offset=" + maxPreOffset);
                        assertTrue(maxPostOffset <= 1.0,
                                "the TAA-resolved darkening must stay within 1 px of the "
                                        + "stable world contact, max offset=" + maxPostOffset);

                        double maxSpread = spread(postFrames);
                        assertTrue(maxSpread <= 1.0,
                                "the resolved contact centroid must not shift by more than 1 px "
                                        + "across jitter phases, spread=" + maxSpread);
                        double minTotal = Double.MAX_VALUE;
                        double maxTotal = 0.0;
                        for (Darkening frame : postFrames) {
                            minTotal = Math.min(minTotal, frame.total);
                            maxTotal = Math.max(maxTotal, frame.total);
                        }
                        assertTrue(maxTotal <= minTotal * 1.15,
                                "the contact darkening must not flicker across frames, min="
                                        + minTotal + " max=" + maxTotal);

                        // Counterfactual: a stable projection places the darkening at
                        // `stable`; the jittered frames remain within 1.25 px, so no
                        // whole-texel displacement is observable at 0.25 px jitter
                        // through the public API.  Report the measured offsets.
                        assertTrue(maxPreOffset > 0.0,
                                "the jittered pass output must move at least sub-texel; "
                                        + "max offset=" + maxPreOffset
                                        + " (a stable projection would stay at 0)");
                        GlDebug.assertNoError("TAA contact-shadow stability");
                    } finally {
                        taaContact.close();
                        taaBaseline.close();
                        noneContact.close();
                        noneBaseline.close();
                    }
                } finally {
                    wallMaterial.close();
                    floorMaterial.close();
                }
            }
        }
    }

    @Test
    void resizeRebuildsThePipelineAndPassTargetAndKeepsTheContactShadow() {
        try (GlfwWindow window = window("contact-shadow resize")) {
            bindContext(window);
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking();
                 ShaderProgram floorShader = ShaderProgram.fromSources(FLOOR_VERTEX,
                         FLOOR_FRAGMENT);
                 ShaderProgram pbrShader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh floor = Mesh.from(BuiltinMeshData.coloredQuad("resize-contact-floor"));
                 Mesh wall = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("resize-contact-wall")).mesh());
                 PbrEnvironment environment = environment(device)) {
                Material floorMaterial = Material.builder(floorShader).build();
                Material wallMaterial = pbrMaterial(pbrShader, fallbacks);
                try {
                    Camera camera = standardCamera();
                    RenderPipeline baseline = build(window, scene(camera, floor,
                            floorMaterial, wall, wallMaterial), environment,
                            AntiAliasingMode.NONE, false);
                    RenderPipeline contact = build(window, scene(camera, floor,
                            floorMaterial, wall, wallMaterial), environment,
                            AntiAliasingMode.NONE, true);
                    try {
                        assertContactAtExtent(window, device, baseline, contact, camera,
                                WIDTH, HEIGHT, "160x120 initial");
                        int liveAtStart = liveResourceCount();

                        window.resize(200, 160);
                        window.pollEvents();
                        assertEquals(200, window.width());
                        assertEquals(160, window.height());
                        baseline.resize(200, 160);
                        contact.resize(200, 160);
                        assertContactAtExtent(window, device, baseline, contact, camera,
                                200, 160, "200x160 resized");
                        assertEquals(liveAtStart, liveResourceCount(),
                                "resize must replace the old targets without leaking resources");

                        window.resize(160, 120);
                        window.pollEvents();
                        assertEquals(160, window.width());
                        assertEquals(120, window.height());
                        baseline.resize(160, 120);
                        contact.resize(160, 120);
                        assertContactAtExtent(window, device, baseline, contact, camera,
                                160, 120, "160x120 restored");
                        assertEquals(liveAtStart, liveResourceCount(),
                                "resize back must replace the targets without leaking resources");
                        GlDebug.assertNoError("contact-shadow resize");
                    } finally {
                        contact.close();
                        baseline.close();
                    }
                } finally {
                    wallMaterial.close();
                    floorMaterial.close();
                }
            }
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the resize acceptance must release every tracked GL resource");
        }
    }

    private static void assertContactAtExtent(GlfwWindow window, GlRenderDevice device,
                                              RenderPipeline baseline, RenderPipeline contact,
                                              Camera camera, int width, int height,
                                              String label) {
        baseline.execute(device, 1.0f / 60.0f);
        contact.execute(device, 1.0f / 60.0f);
        assertEquals(width, contact.graph().width(), label + " graph width");
        assertEquals(height, contact.graph().height(), label + " graph height");
        var pass = contact.graph().description().passes().stream()
                .filter(entry -> entry.name().equals(ScreenSpaceContactShadowPass.PASS_NAME))
                .findFirst().orElseThrow();
        assertEquals(width, pass.width(), label + " contact pass width");
        assertEquals(height, pass.height(), label + " contact pass height");
        assertEquals(0, pass.depthAttachment() == null ? 0 : 1,
                label + " contact pass must not attach depth");

        float[] baselinePixels = baseline.captureLinearHdrRgbaFloat();
        float[] contactPixels = contact.captureLinearHdrRgbaFloat();
        Matrix4f projection = CameraProjection.stable(camera, width, height, new Matrix4f());
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        int[] occluderBase = project(view, projection, new Vector3f(0.0f, 0.02f, 0.0f),
                width, height);
        assertNotNull(occluderBase, label + " occluder base projection");
        float bestDelta = 0.0f;
        int bestPixel = -1;
        int darker = 0;
        for (int sample = 0; sample < width * height; sample++) {
            float delta = luminance(baselinePixels, sample)
                    - luminance(contactPixels, sample);
            if (delta > 5.0e-4f) darker++;
            if (delta <= bestDelta) continue;
            int offset = sample * 4;
            boolean floor = Math.abs(baselinePixels[offset] - 0.12f) <= COLOR_TOLERANCE
                    && Math.abs(baselinePixels[offset + 1] - 0.18f) <= COLOR_TOLERANCE
                    && Math.abs(baselinePixels[offset + 2] - 0.24f) <= COLOR_TOLERANCE;
            if (!floor) continue;
            bestDelta = delta;
            bestPixel = sample;
        }
        assertTrue(darker > 0, label + " must contain a visible contact shadow");
        assertTrue(bestPixel >= 0 && bestDelta > DARKENING,
                label + " the receiver floor must darken, best delta=" + bestDelta);
        int bestX = bestPixel % width;
        int bestY = bestPixel / width;
        double distance = Math.hypot(bestX - occluderBase[0], bestY - occluderBase[1]);
        assertTrue(distance <= 30.0,
                label + " the strongest floor darkening must stay at the contact, pixel="
                        + bestX + "," + bestY + " base=" + occluderBase[0] + ","
                        + occluderBase[1] + " distance=" + distance);
    }

    private static RenderPipeline build(GlfwWindow window, Scene scene,
                                        PbrEnvironment environment,
                                        AntiAliasingMode mode, boolean contact) {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(mode)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
        pipeline.directionalCascades(DirectionalCascadeSettings.disabled());
        ShadowSettings shadow = new ShadowSettings(64, 6.0f, 0.1f, 40.0f, 0.05f,
                contact ? CONTACT : ContactShadowSettings.disabled());
        pipeline.features().submit(pipeline.features().effective().toBuilder()
                .directionalShadowMap(new DirectionalShadowMap(shadow))
                .build());
        pipeline.build();
        return pipeline;
    }

    private static Scene scene(Camera camera, Mesh floor, Material floorMaterial,
                               Mesh wall, Material wallMaterial) {
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(floor, floorMaterial, Transform.at(0f, 0f, 0f)
                .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                .scale(12.0f, 12.0f, 1.0f)).withoutShadows());
        scene.add(MeshRenderer.of(wall, wallMaterial,
                Transform.at(0.0f, 0.75f, 0.0f).scale(0.4f, 1.5f, 1.0f)));
        scene.addLight(SceneLight.shadowedDirectional(LIGHT, new Vector3f(4.0f), 5.0f));
        return scene;
    }

    private static Camera standardCamera() {
        return new Camera(new Vector3f(2.2f, 1.7f, 4.5f),
                new Vector3f(0.0f, 1.0f, 0.0f), -115.0f, -16.0f);
    }

    private static Darkening darkening(float[] baseline, float[] contact) {
        double total = 0.0;
        double sumX = 0.0;
        double sumY = 0.0;
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
            float delta = luminance(baseline, pixel) - luminance(contact, pixel);
            if (delta <= 5.0e-4f) continue;
            total += delta;
            sumX += delta * ((pixel % WIDTH) + 0.5);
            sumY += delta * ((pixel / WIDTH) + 0.5);
        }
        if (total <= 0.0) return new Darkening(0.0, Double.NaN, Double.NaN);
        return new Darkening(total, sumX / total, sumY / total);
    }

    private static double spread(List<Darkening> frames) {
        double max = 0.0;
        for (int left = 0; left < frames.size(); left++) {
            for (int right = left + 1; right < frames.size(); right++) {
                max = Math.max(max, frames.get(left).distanceTo(frames.get(right)));
            }
        }
        return max;
    }

    private record Darkening(double total, double x, double y) {
        double distanceTo(Darkening other) {
            if (!Double.isFinite(x) || !Double.isFinite(other.x)) return Double.MAX_VALUE;
            return Math.hypot(x - other.x, y - other.y);
        }
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }

    private static int[] project(Matrix4f view, Matrix4f projection, Vector3f world,
                                 int width, int height) {
        Vector4f clip = new Matrix4f(projection).mul(view)
                .transform(new Vector4f(world, 1.0f));
        if (clip.w <= 0.0f) return null;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f) return null;
        return new int[]{
                Math.max(0, Math.min(width - 1,
                        Math.round((ndcX * 0.5f + 0.5f) * width))),
                Math.max(0, Math.min(height - 1,
                        Math.round((ndcY * 0.5f + 0.5f) * height)))};
    }

    private static int liveResourceCount() {
        return GlDebug.resources().liveResources().size();
    }

    private static GlfwWindow window(String title) {
        return new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT).title(title)
                .visible(false).build();
    }

    private static void bindContext(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
    }

    private static Material pbrMaterial(ShaderProgram shader, PbrFallbackTextures fallbacks) {
        return PbrMaterials.create(shader,
                new PbrMaterialProperties(new Vector4f(0.72f, 0.68f, 0.62f, 1.0f),
                        0.0f, 0.85f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                Map.of(), fallbacks);
    }

    private static PbrEnvironment environment(GlRenderDevice device) {
        return PbrEnvironmentLoader.load(device,
                ScreenSpaceContactShadowTaaResizeGlTest.class,
                "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
    }

    private static final String FLOOR_VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            void main() {
                // Keep the shared-depth contract order: world first, then P*V.
                vec4 world = uModel * vec4(aPos, 1.0);
                gl_Position = uProjection * uView * world;
            }
            """;
    private static final String FLOOR_FRAGMENT = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(0.12, 0.18, 0.24, 1.0);
            }
            """;
}
