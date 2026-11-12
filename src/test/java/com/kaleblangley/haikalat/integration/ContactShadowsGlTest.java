package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-consumer acceptance for the PBR directional contact-shadow path.
 *
 * <p>The fixture places a wall on a floor and lights it from behind the wall so
 * the short-range contact darkening is visible from the camera.  The first
 * frame uses the same cascade topology with contact shadows disabled, the
 * second enables them, and the third disables them again to prove the exact
 * no-op contract.  Screen-border pixels must not change because the shader
 * falls back to cascaded shadows at the camera edge.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ContactShadowsGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;

    @Test
    void contactShadowDarkensTheGroundedContactAndFallsBackAtScreenEdges() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Contact Shadow GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("contact-shadow-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.72f, 0.68f, 0.62f, 1.0f),
                                0.0f, 0.85f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(2.2f, 1.6f, 4.5f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -115.0f, -14.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(quad, material,
                            Transform.at(0.0f, 0.0f, 0.0f)
                                    .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                    .scale(8.0f, 8.0f, 1.0f)).withoutShadows());
                    scene.add(MeshRenderer.of(quad, material,
                            Transform.at(0.0f, 1.0f, -1.0f).scale(3.0f, 2.0f, 1.0f)));
                    scene.addLight(SceneLight.shadowedDirectional(
                            new Vector3f(0.6f, -1.0f, 0.4f), new Vector3f(4.0f), 5.0f));
                    RenderSettings settings = RenderSettings.builder()
                            // Isolate the material-level forward term from the #9
                            // screen-space pass (which requires a single-sample
                            // target): the composite has its own real-GL acceptance.
                            .antiAliasingMode(
                                    com.kaleblangley.haikalat.core.AntiAliasingMode.MSAA)
                            .msaaSamples(4)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            settings, environment);
                    try {
                        pipeline.directionalCascades(DirectionalCascadeSettings.disabled());
                        // A deliberately coarse map and large cascade bias leave a
                        // broad near-contact band lit by the existing PCF; the
                        // contact term uses its own small bias to refine it.
                        ShadowSettings disabled = new ShadowSettings(64, 6.0f, 0.1f, 40.0f, 0.05f);
                        pipeline.features().submit(pipeline.features().effective().toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(disabled))
                                .build());
                        pipeline.build();
                        pipeline.execute(device);
                        float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                        ContactShadowSettings contact = new ContactShadowSettings(
                                true, 3.0f, 3.0f, 32, 1.0f);
                        ShadowSettings enabled = new ShadowSettings(64, 6.0f, 0.1f, 40.0f,
                                0.05f, contact);
                        pipeline.features().submit(pipeline.features().effective().toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(enabled))
                                .build());
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                                "contact-shadow feature request must publish before readback");
                        float[] withContact = pipeline.captureLinearHdrRgbaFloat();

                        int darker = 0;
                        int brighter = 0;
                        int groundedDarker = 0;
                        for (int pixel = 0; pixel < baseline.length / 4; pixel++) {
                            float delta = luminance(withContact, pixel) - luminance(baseline, pixel);
                            if (delta < -1.0e-4f) {
                                darker++;
                                if (pixel / WIDTH < HEIGHT / 2) groundedDarker++;
                            }
                            if (delta > 1.0e-4f) brighter++;
                        }
                        assertTrue(darker > 0,
                                "enabled contact shadows must darken at least one direct-light pixel");
                        assertTrue(groundedDarker > 0,
                                "contact refinement must reach the grounded lower half of the image");
                        assertEquals(0, brighter,
                                "contact shadows may only attenuate direct light");

                        int[] grounded = project(camera, new Vector3f(0.0f, 0.02f, -0.7f));
                        int[] openFloor = project(camera, new Vector3f(3.0f, 0.02f, 3.0f));
                        float groundedLuminance = luminance(withContact,
                                grounded[1] * WIDTH + grounded[0]);
                        float openFloorLuminance = luminance(withContact,
                                openFloor[1] * WIDTH + openFloor[0]);
                        assertTrue(groundedLuminance < openFloorLuminance - 1.0e-4f,
                                "the floor beside the grounded occluder must be darker than open floor");
                        assertTrue(luminance(withContact, grounded[1] * WIDTH + grounded[0])
                                        <= luminance(baseline, grounded[1] * WIDTH + grounded[0])
                                        + 1.0e-5f,
                                "grounded contact must not brighten the existing shadow");

                        assertArrayEquals(border(baseline, 0), border(withContact, 0), 1.0e-6f,
                                "left screen-edge pixels must fall back to the cascaded image");
                        assertArrayEquals(border(baseline, WIDTH - 1),
                                border(withContact, WIDTH - 1), 1.0e-6f,
                                "right screen-edge pixels must fall back to the cascaded image");

                        pipeline.features().submit(pipeline.features().effective().toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(disabled))
                                .build());
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                        assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(), 0.0f,
                                "disabling contact shadows must restore the exact previous output");

                        GlDebug.checkError("contact shadow acceptance");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
        }
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }

    private static float[] border(float[] rgba, int x) {
        float[] result = new float[HEIGHT * 4];
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(rgba, (y * WIDTH + x) * 4, result, y * 4, 4);
        }
        return result;
    }

    private static int[] project(Camera camera, Vector3f world) {
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT, new Matrix4f());
        Vector4f clip = new Matrix4f(projection).mul(view)
                .transform(new Vector4f(world, 1.0f));
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        int x = Math.max(0, Math.min(WIDTH - 1,
                Math.round((ndcX * 0.5f + 0.5f) * WIDTH)));
        // RenderGraph readback is bottom-up, matching OpenGL glReadPixels.
        int y = Math.max(0, Math.min(HEIGHT - 1,
                Math.round((ndcY * 0.5f + 0.5f) * HEIGHT)));
        return new int[]{x, y};
    }
}
