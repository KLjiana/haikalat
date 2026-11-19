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
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
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
import com.kaleblangley.haikalat.subsystems.render3d.shadow.CloudShadowSettings;
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
 * Real-consumer acceptance for the PBR world-space cloud-shadow path.
 *
 * <p>The fixture lights a large PBR floor with the single shadowed directional
 * light, disables image-based lighting and loads no emissive map, so the
 * readback is direct lighting only.  A first camera frame renders disabled,
 * enabled and disabled again to prove the exact no-op.  The camera then pans
 * sideways and the same enable/disable pair is rendered.  Because the cloud
 * mask is projected from world position along the light direction, the
 * enabled/disabled ratio at a fixed world point must survive the pan, while the
 * ratio image at fixed screen pixels must move.  A screen-space mask would show
 * the opposite.  The test deliberately uses speed 0 so time cannot confound the
 * pan comparison; time scrolling is covered by the shader contract test.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class CloudShadowsGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;

    @Test
    void cloudShadowIsWorldLockedAcrossCameraPanAndDisablesExactly() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Cloud Shadow GL Test")
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
                         BuiltinMeshData.texturedQuad("cloud-shadow-floor")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.8f, 0.78f, 0.72f, 1.0f),
                                0.0f, 1.0f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(-2.5f, 7.0f, 9.0f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -40.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(quad, material,
                            Transform.at(0.0f, 0.0f, 0.0f)
                                    .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                    .scale(24.0f, 24.0f, 1.0f)).withoutShadows());
                    scene.addLight(SceneLight.shadowedDirectional(
                            new Vector3f(0.4f, -1.0f, 0.3f), new Vector3f(3.0f), 20.0f));
                    RenderSettings settings = RenderSettings.builder()
                            .antiAliasingMode(com.kaleblangley.haikalat.core.AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            settings, environment);
                    try {
                        ShadowSettings off = new ShadowSettings(128, 10.0f, 0.1f, 60.0f);
                        RenderFeatureSettings base = pipeline.features().effective().toBuilder()
                                .imageBasedLighting(false)
                                .directionalShadows(true)
                                .directionalShadowMap(new DirectionalShadowMap(off))
                                .build();
                        CloudShadowSettings clouds = new CloudShadowSettings(
                                true, 0.5f, 0.25f, 0.2f, 0.0f, 0.8f);
                        ShadowSettings on = new ShadowSettings(128, 10.0f, 0.1f, 60.0f,
                                0.003f, ContactShadowSettings.disabled(), clouds);

                        pipeline.features().submit(base);
                        pipeline.build();
                        pipeline.execute(device);
                        float[] baselineA = pipeline.captureLinearHdrRgbaFloat();

                        pipeline.features().submit(base.toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(on)).build());
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                                "cloud-shadow feature request must publish before readback");
                        float[] enabledA = pipeline.captureLinearHdrRgbaFloat();

                        pipeline.features().submit(base);
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                        assertArrayEquals(baselineA, pipeline.captureLinearHdrRgbaFloat(), 0.0f,
                                "disabling cloud shadows must restore the exact previous output");

                        Matrix4f viewA = camera.getViewMatrix(new Matrix4f());
                        Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT,
                                new Matrix4f());
                        camera.setPosition(new Vector3f(2.5f, 7.0f, 9.0f));
                        Matrix4f viewB = camera.getViewMatrix(new Matrix4f());

                        pipeline.execute(device);
                        float[] baselineB = pipeline.captureLinearHdrRgbaFloat();
                        pipeline.features().submit(base.toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(on)).build());
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                        float[] enabledB = pipeline.captureLinearHdrRgbaFloat();

                        float[] ratioA = ratio(enabledA, baselineA);
                        float[] ratioB = ratio(enabledB, baselineB);
                        assertTrue(darkerPixelCount(ratioA) > 0,
                                "enabled cloud shadows must attenuate at least one direct-light pixel");
                        assertTrue(maxAbsDifference(ratioA, ratioB) > 5.0e-3f,
                                "the cloud ratio image must move with the world under a camera pan; "
                                        + "a screen-space mask would stay identical");

                        Vector3f[] samplePoints = {
                                new Vector3f(-1.0f, 0.02f, 0.0f),
                                new Vector3f(0.0f, 0.02f, 0.5f),
                                new Vector3f(1.0f, 0.02f, -0.5f),
                                new Vector3f(0.5f, 0.02f, 1.5f),
                                new Vector3f(-1.5f, 0.02f, 1.0f),
                                new Vector3f(1.5f, 0.02f, 0.5f)
                        };
                        int compared = 0;
                        for (Vector3f point : samplePoints) {
                            float[] pixelA = project(viewA, projection, point);
                            float[] pixelB = project(viewB, projection, point);
                            if (pixelA == null || pixelB == null) continue;
                            float worldRatioA = sampleBilinear(ratioA, pixelA[0], pixelA[1]);
                            float worldRatioB = sampleBilinear(ratioB, pixelB[0], pixelB[1]);
                            assertEquals(worldRatioA, worldRatioB, 0.03f,
                                    "the same world point must keep its cloud attenuation across "
                                            + "the camera pan at " + point);
                            compared++;
                        }
                        assertTrue(compared >= 3,
                                "at least three sampled world points must be visible in both frames");

                        GlDebug.checkError("cloud shadow acceptance");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
        }
    }

    private static float[] ratio(float[] enabled, float[] baseline) {
        float[] ratio = new float[WIDTH * HEIGHT];
        for (int pixel = 0; pixel < ratio.length; pixel++) {
            float base = luminance(baseline, pixel);
            ratio[pixel] = base > 1.0e-4f ? luminance(enabled, pixel) / base : 1.0f;
        }
        return ratio;
    }

    private static int darkerPixelCount(float[] ratio) {
        int darker = 0;
        for (float value : ratio) {
            if (value < 0.995f) darker++;
        }
        return darker;
    }

    private static float maxAbsDifference(float[] a, float[] b) {
        float max = 0.0f;
        for (int index = 0; index < a.length; index++) {
            max = Math.max(max, Math.abs(a[index] - b[index]));
        }
        return max;
    }

    private static float[] project(Matrix4f view, Matrix4f projection, Vector3f world) {
        Vector4f clip = new Matrix4f(projection).mul(view)
                .transform(new Vector4f(world, 1.0f));
        if (Math.abs(clip.w) < 1.0e-6f) return null;
        float x = (clip.x / clip.w * 0.5f + 0.5f) * WIDTH - 0.5f;
        // RenderGraph readback is bottom-up, matching OpenGL glReadPixels.
        float y = (clip.y / clip.w * 0.5f + 0.5f) * HEIGHT - 0.5f;
        if (x < 1.0f || x > WIDTH - 2.0f || y < 1.0f || y > HEIGHT - 2.0f) return null;
        return new float[]{x, y};
    }

    private static float sampleBilinear(float[] values, float x, float y) {
        int x0 = Math.max(0, Math.min(WIDTH - 2, (int) Math.floor(x)));
        int y0 = Math.max(0, Math.min(HEIGHT - 2, (int) Math.floor(y)));
        float fx = x - x0;
        float fy = y - y0;
        float v00 = values[y0 * WIDTH + x0];
        float v10 = values[y0 * WIDTH + x0 + 1];
        float v01 = values[(y0 + 1) * WIDTH + x0];
        float v11 = values[(y0 + 1) * WIDTH + x0 + 1];
        return (v00 * (1.0f - fx) + v10 * fx) * (1.0f - fy)
                + (v01 * (1.0f - fx) + v11 * fx) * fy;
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }
}
