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
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.DirectionalCascadeSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureStatus;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL production acceptance for #9 screen-space contact shadows.
 *
 * <p>The fixture is a constant-colour, non-PBR receiver floor with a PBR wall
 * standing on it, a shadow-casting directional light and a PBR environment for
 * the wall. Because the floor material never executes {@code pbr-forward.frag},
 * the material-level contact-shadow uniforms cannot change floor pixels; any
 * floor darkening in this fixture is produced by the production
 * {@link ScreenSpaceContactShadowPass} consuming the camera scene depth.</p>
 *
 * <p>The test enables the pass through the public feature request, asserts the
 * contact pixel darkens, an open-floor pixel and a receiver whose light ray
 * leaves the raster stay byte-identical, then disables the request and asserts
 * the complete baseline is restored byte-for-byte. The pass must not attach the
 * shared depth, GlDebug must report no error and all resources must be released.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ScreenSpaceContactShadowsGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final Vector3f LIGHT_DIRECTION = new Vector3f(1.6f, -1.0f, 0.35f);
    private static final Vector3f FLOOR_COLOR = new Vector3f(0.12f, 0.18f, 0.24f);
    private static final float COLOR_TOLERANCE = 2.0e-3f;
    private static final float CONTACT_DARKENING = 2.0e-3f;
    private static final float FLOOR_HALF_EXTENT = 4.0f;
    private static final ContactShadowSettings ACTIVE_CONTACT =
            new ContactShadowSettings(true, 3.0f, 1.0f, 24, 1.0f);

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

    @Test
    void screenSpaceContactShadowDarkensTheContactAndFallsBackOffScreen() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Screen-Space Contact Shadow GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (var tracking = GlDebug.acquireResourceTracking();
                 ShaderProgram floorShader = ShaderProgram.fromSources(FLOOR_VERTEX,
                         FLOOR_FRAGMENT);
                 ShaderProgram wallShader = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh floorMesh = Mesh.from(BuiltinMeshData.coloredQuad(
                         "contact-shadow-floor"));
                 Mesh wallMesh = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("contact-shadow-wall")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material floorMaterial = Material.builder(floorShader).build();
                Material wallMaterial = PbrMaterials.create(wallShader,
                        new PbrMaterialProperties(new Vector4f(0.72f, 0.68f, 0.62f, 1.0f),
                                0.0f, 0.85f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(2.2f, 1.6f, 4.5f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -115.0f, -14.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(floorMesh, floorMaterial,
                            Transform.at(0.0f, 0.0f, 0.0f)
                                    .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                    .scale(8.0f, 8.0f, 1.0f)).withoutShadows());
                    scene.add(MeshRenderer.of(wallMesh, wallMaterial,
                            Transform.at(0.0f, 1.0f, -1.0f).scale(6.0f, 2.0f, 1.0f)));
                    scene.addLight(SceneLight.shadowedDirectional(LIGHT_DIRECTION,
                            new Vector3f(4.0f), 5.0f));
                    RenderSettings settings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            settings, environment);
                    try {
                        pipeline.directionalCascades(DirectionalCascadeSettings.disabled());
                        ShadowSettings disabled = new ShadowSettings(64, 6.0f, 0.1f, 40.0f,
                                0.05f);
                        submit(pipeline, disabled);
                        pipeline.build();
                        pipeline.execute(device, 1.0f / 60.0f);
                        assertFalse(pipeline.graph().hasPass(
                                        ScreenSpaceContactShadowPass.PASS_NAME),
                                "the disabled baseline must not declare the contact-shadow pass");
                        float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                        ShadowSettings enabled = new ShadowSettings(64, 6.0f, 0.1f, 40.0f,
                                0.05f, ACTIVE_CONTACT);
                        submit(pipeline, enabled);
                        pipeline.execute(device, 1.0f / 60.0f);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                                "the contact-shadow request must publish before readback");
                        assertTrue(pipeline.graph().hasPass(
                                        ScreenSpaceContactShadowPass.PASS_NAME),
                                "the production generation must declare the screen-space pass");
                        var pass = pipeline.graph().description().passes().stream()
                                .filter(entry -> entry.name().equals(
                                        ScreenSpaceContactShadowPass.PASS_NAME))
                                .findFirst().orElseThrow();
                        assertFalse(pass.depthAttachment() != null,
                                "the contact-shadow composite must not attach the shared depth");
                        float[] withContact = pipeline.captureLinearHdrRgbaFloat();

                        Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT,
                                new Matrix4f());
                        Matrix4f view = camera.getViewMatrix(new Matrix4f());
                        int[] contact = project(view, projection,
                                new Vector3f(0.5f, 0.02f, -0.7f));
                        assertTrue(contact != null, "the contact world point must project");
                        assertFloorPixel(baseline, contact, "baseline contact point");
                        float before = luminance(baseline, contact);
                        float after = luminance(withContact, contact);
                        assertTrue(after < before - CONTACT_DARKENING,
                                "the screen-space contact region must darken by more than "
                                        + CONTACT_DARKENING + ", before=" + before
                                        + " after=" + after);

                        int[] openFloor = findOpenFloorPixel(baseline, view, projection,
                                contact);
                        assertTrue(openFloor != null,
                                "the fixture must contain open floor far from the occluder");
                        assertFloorPixel(baseline, openFloor, "baseline open floor");
                        assertPixelEquals(baseline, withContact, openFloor,
                                "open floor far from the occluder must not change");

                        int[] offScreen = findOffScreenFloorPixel(baseline, view, projection);
                        assertTrue(offScreen != null,
                                "the fixture must contain a visible receiver whose whole "
                                        + "light ray leaves the raster");
                        assertFloorPixel(baseline, offScreen, "baseline off-screen receiver");
                        assertPixelEquals(baseline, withContact, offScreen,
                                "an off-screen light ray at (" + offScreen[0] + ","
                                        + offScreen[1] + ") must fall back to no occlusion");

                        int darker = 0;
                        int brighter = 0;
                        int softlyDarkened = 0;
                        for (int pixel = 0; pixel < baseline.length / 4; pixel++) {
                            float delta = luminance(withContact, pixel)
                                    - luminance(baseline, pixel);
                            if (delta < -1.0e-4f) darker++;
                            if (delta > 1.0e-4f) brighter++;
                            if (sameColor(baseline, pixel, FLOOR_COLOR, COLOR_TOLERANCE)) {
                                float baseLuminance = luminance(baseline, pixel);
                                float attenuation = -delta / Math.max(baseLuminance, 1.0e-6f);
                                if (attenuation > 0.01f && attenuation < 0.30f) {
                                    softlyDarkened++;
                                }
                            }
                        }
                        assertTrue(darker > 0,
                                "enabling the pass must darken at least one pixel");
                        assertEquals(0, brighter,
                                "the contact-shadow composite may only attenuate colour");
                        assertTrue(softlyDarkened > 0,
                                "the thickness boundary must include partial attenuation, "
                                        + "not only a binary shadow edge");

                        submit(pipeline, disabled, false);
                        pipeline.execute(device, 1.0f / 60.0f);
                        float[] unlitBaseline = pipeline.captureLinearHdrRgbaFloat();
                        submit(pipeline, enabled, false);
                        pipeline.execute(device, 1.0f / 60.0f);
                        assertFalse(pipeline.graph().hasPass(
                                        ScreenSpaceContactShadowPass.PASS_NAME),
                                "contact shadows attenuate direct light, so the pass must be "
                                        + "absent while direct lighting is disabled");
                        assertArrayEquals(unlitBaseline,
                                pipeline.captureLinearHdrRgbaFloat(), 0.0f,
                                "an unlit receiver must not be darkened by contact shadows");

                        submit(pipeline, disabled, true);
                        pipeline.execute(device, 1.0f / 60.0f);
                        assertFalse(pipeline.graph().hasPass(
                                        ScreenSpaceContactShadowPass.PASS_NAME),
                                "disabling the request must remove the pass");
                        assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(), 0.0f,
                                "disabling contact shadows must restore the exact baseline");
                        GlDebug.assertNoError("screen-space contact-shadow acceptance");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    wallMaterial.close();
                    floorMaterial.close();
                }
            }
            assertTrue(GlDebug.resources().liveResources().isEmpty(),
                    "the pipeline and fixture must release every tracked GL resource");
        }
    }

    @Test
    void disabledAndMultisampleSettingsAreRejectedBeforeAnyResourceIsCreated() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(16, 16)
                .title("Screen-Space Contact Shadow rejection")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            int liveBefore = GlDebug.resources().liveResources().size();
            assertThrows(IllegalArgumentException.class,
                    () -> new ScreenSpaceContactShadowPass(ContactShadowSettings.disabled(),
                            1, 16, 16),
                    "disabled settings must not create a pass");
            assertThrows(IllegalArgumentException.class,
                    () -> new ScreenSpaceContactShadowPass(ACTIVE_CONTACT, 2, 16, 16),
                    "a multisampled scene target must be rejected with a clear message");
            assertEquals(liveBefore, GlDebug.resources().liveResources().size(),
                    "rejected settings must not leave a shader, mesh or framebuffer behind");
            GlDebug.assertNoError("screen-space contact-shadow rejection");
        }
    }

    private static void submit(RenderPipeline pipeline, ShadowSettings settings) {
        submit(pipeline, settings, true);
    }

    private static void submit(RenderPipeline pipeline, ShadowSettings settings,
                               boolean directLighting) {
        pipeline.features().submit(pipeline.features().effective().toBuilder()
                .directionalShadowMap(new DirectionalShadowMap(settings))
                .directLighting(directLighting)
                .build());
    }

    private static int[] findOpenFloorPixel(float[] baseline, Matrix4f view,
                                             Matrix4f projection, int[] contact) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int pixel = y * WIDTH + x;
                if (!sameColor(baseline, pixel, FLOOR_COLOR, COLOR_TOLERANCE)) continue;
                if (Math.abs(x - contact[0]) < 24 && Math.abs(y - contact[1]) < 24) continue;
                Vector3f floor = floorPoint(view, projection, x, y);
                if (floor == null || Math.abs(floor.x) > FLOOR_HALF_EXTENT
                        || floor.z < 0.75f || floor.z > FLOOR_HALF_EXTENT) {
                    continue;
                }
                return new int[]{x, y};
            }
        }
        return null;
    }

    private static int[] findOffScreenFloorPixel(float[] baseline, Matrix4f view,
                                                  Matrix4f projection) {
        Vector3f toLight = new Vector3f(LIGHT_DIRECTION).negate().normalize();
        int steps = ACTIVE_CONTACT.steps();
        float distance = ACTIVE_CONTACT.distance();
        int best = -1;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int pixel = y * WIDTH + x;
                if (!sameColor(baseline, pixel, FLOOR_COLOR, COLOR_TOLERANCE)) continue;
                Vector3f floor = floorPoint(view, projection, x, y);
                if (floor == null || Math.abs(floor.x) > FLOOR_HALF_EXTENT
                        || Math.abs(floor.z) > FLOOR_HALF_EXTENT) {
                    continue;
                }
                boolean allOutside = true;
                for (int step = 1; step <= steps; step++) {
                    Vector3f sample = new Vector3f(floor).fma(
                            distance * step / steps, toLight);
                    Vector4f clip = new Matrix4f(projection).mul(view)
                            .transform(new Vector4f(sample, 1.0f));
                    if (clip.w <= 0.0f) continue;
                    float u = clip.x / clip.w * 0.5f + 0.5f;
                    float v = clip.y / clip.w * 0.5f + 0.5f;
                    if (u >= -0.01f && u <= 1.01f && v >= -0.01f && v <= 1.01f) {
                        allOutside = false;
                        break;
                    }
                }
                if (allOutside) {
                    best = pixel;
                    break;
                }
            }
            if (best >= 0) break;
        }
        if (best < 0) return null;
        return new int[]{best % WIDTH, best / WIDTH};
    }

    private static Vector3f floorPoint(Matrix4f view, Matrix4f projection, int x, int y) {
        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        float ndcX = (x + 0.5f) / WIDTH * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / HEIGHT * 2.0f - 1.0f;
        Vector4f near = inverse.transform(new Vector4f(ndcX, ndcY, -1.0f, 1.0f));
        near.div(near.w);
        Vector4f far = inverse.transform(new Vector4f(ndcX, ndcY, 1.0f, 1.0f));
        far.div(far.w);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y,
                far.z - near.z);
        if (!(Math.abs(direction.y) > 1.0e-6f)) return null;
        float t = -near.y / direction.y;
        if (!(t > 0.0f)) return null;
        return new Vector3f(near.x + direction.x * t, 0.0f, near.z + direction.z * t);
    }

    private static int[] project(Matrix4f view, Matrix4f projection, Vector3f world) {
        Vector4f clip = new Matrix4f(projection).mul(view)
                .transform(new Vector4f(world, 1.0f));
        if (clip.w <= 0.0f) return null;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f) return null;
        int x = Math.max(0, Math.min(WIDTH - 1,
                Math.round((ndcX * 0.5f + 0.5f) * WIDTH)));
        // RenderGraph readback is bottom-up, matching glReadPixels.
        int y = Math.max(0, Math.min(HEIGHT - 1,
                Math.round((ndcY * 0.5f + 0.5f) * HEIGHT)));
        return new int[]{x, y};
    }

    private static void assertFloorPixel(float[] rgba, int[] point, String message) {
        assertTrue(sameColor(rgba, point[1] * WIDTH + point[0], FLOOR_COLOR,
                        COLOR_TOLERANCE),
                message + " must be the constant-colour receiver floor");
    }

    private static void assertPixelEquals(float[] expected, float[] actual, int[] point,
                                          String message) {
        int offset = (point[1] * WIDTH + point[0]) * 4;
        for (int channel = 0; channel < 4; channel++) {
            assertEquals(expected[offset + channel], actual[offset + channel], 0.0f,
                    message + " channel " + channel);
        }
    }

    private static boolean sameColor(float[] rgba, int pixel, Vector3f expected,
                                     float tolerance) {
        int offset = pixel * 4;
        return Math.abs(rgba[offset] - expected.x) <= tolerance
                && Math.abs(rgba[offset + 1] - expected.y) <= tolerance
                && Math.abs(rgba[offset + 2] - expected.z) <= tolerance;
    }

    private static float luminance(float[] rgba, int[] point) {
        return luminance(rgba, point[1] * WIDTH + point[0]);
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }
}
