package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
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
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
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
 * Real-consumer acceptance for the #32 PBR material input.
 *
 * <p>The fixture is a camera-facing PBR quad with a black base colour and no
 * IBL, so the forward image is exactly the direct term. Three independent
 * comparisons lock the material semantics:</p>
 * <ul>
 *   <li>a front light with and without subsurface enabled must produce the
 *       exact same bytes: the added transmission term uses only the back-facing
 *       cosine, so the front specular is untouched;</li>
 *   <li>a back light with a thin material must produce a positive tinted
 *       scatter term where the disabled material is black, and a thicker or
 *       shorter-mean-free-path material must produce less scatter;</li>
 *   <li>clearing the overrides must restore the disabled baseline exactly.</li>
 * </ul>
 *
 * <p>The screen-space pass is not involved here; the companion
 * {@code SubsurfaceScatteringPassGlTest} covers #33 and the pipeline wiring.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SubsurfaceScatteringForwardGlTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    private static final float CAMERA_Z = 3.4f;
    private static final float QUAD_HALF = 1.0f;
    private static final Vector3f CAMERA_POSITION = new Vector3f(0.0f, 0.0f, CAMERA_Z);
    private static final Vector3f FRONT_LIGHT_TRAVEL = new Vector3f(0.0f, 0.0f, -1.0f);
    private static final Vector3f BACK_LIGHT_TRAVEL = new Vector3f(0.0f, 0.0f, 1.0f);
    private static final Vector3f LIGHT_COLOR = new Vector3f(1.0f);
    private static final float LIGHT_INTENSITY = 2.5f;
    private static final Vector4f BASE_COLOR = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
    private static final float BASE_ROUGHNESS = 0.25f;

    @Test
    void thinBackLitMaterialScattersAndFrontSpecularStaysByteExact() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT)
                .title("Subsurface Forward GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("subsurface-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                PbrMaterialProperties properties = new PbrMaterialProperties(BASE_COLOR,
                        0.0f, BASE_ROUGHNESS, 0.0f, 1.0f, new Vector3f(), Map.of());
                Material material = PbrMaterials.createWithSubsurface(shader, properties,
                        Map.of(), fallbacks, SubsurfaceScatteringSettings.disabled());
                try {
                    MaterialInstance instance = material.createInstance();
                    RenderSettings renderSettings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();

                    float[] frontDisabled;
                    float[] frontEnabled;
                    RenderPipeline frontPipeline = pipeline(window, environment, quad, instance,
                            FRONT_LIGHT_TRAVEL, renderSettings);
                    try {
                        frontDisabled = render(frontPipeline, device);
                        enableForwardScatter(instance, 0.02f, 0.02f);
                        frontEnabled = render(frontPipeline, device);
                    } finally {
                        frontPipeline.close();
                    }
                    assertArrayEquals(frontDisabled, frontEnabled, 0.0f,
                            "a front-facing light must leave the specular image byte-exact");
                    assertTrue(maxLuminance(frontDisabled) > 0.01f,
                            "the front-light fixture must contain a real specular highlight");

                    float[] backDisabled;
                    float[] backThin;
                    float[] backThick;
                    float[] backShortScale;
                    RenderPipeline backPipeline = pipeline(window, environment, quad, instance,
                            BACK_LIGHT_TRAVEL, renderSettings);
                    try {
                        instance.clearOverrides();
                        backDisabled = render(backPipeline, device);
                        enableForwardScatter(instance, 0.02f, 0.02f);
                        backThin = render(backPipeline, device);
                        instance.setFloat("uSubsurfaceThickness", 0.20f);
                        backThick = render(backPipeline, device);
                        instance.setFloat("uSubsurfaceThickness", 0.02f)
                                .setFloat("uSubsurfaceProfileScale", 0.01f);
                        backShortScale = render(backPipeline, device);
                    } finally {
                        backPipeline.close();
                    }
                    int center = (HEIGHT / 2 * WIDTH + WIDTH / 2) * 4;
                    assertTrue(maxLuminance(backDisabled) < 1.0e-4f,
                            "the back-lit disabled fixture must be black");
                    assertTrue(backThin[center] > 0.1f,
                            "the thin back-lit material must add a positive red scatter term");
                    assertTrue(backThin[center] > backThin[center + 1] * 1.5f,
                            "the skin tint must stay red-dominant");
                    assertTrue(backThick[center] < backThin[center] * 0.8f,
                            "a thicker layer must attenuate the back-lit scatter");
                    assertTrue(backShortScale[center] < backThin[center] * 0.8f,
                            "a shorter mean free path must attenuate the back-lit scatter");

                    instance.clearOverrides();
                    RenderPipeline restorePipeline = pipeline(window, environment, quad, instance,
                            FRONT_LIGHT_TRAVEL, renderSettings);
                    try {
                        assertArrayEquals(frontDisabled, render(restorePipeline, device), 0.0f,
                                "clearing the overrides must restore the disabled baseline");
                    } finally {
                        restorePipeline.close();
                    }
                    GlDebug.checkError("subsurface forward acceptance");
                } finally {
                    material.close();
                }
            }
        }
    }

    /**
     * Real end-to-end consumer: an active PBR SSS material is rendered through
     * the normal pipeline with a back spot light, so the shared surface pass
     * writes the actual {@code SUBSURFACE} mask from the material settings. The
     * same frame is rendered with and without the post-process request; the
     * enabled frame must gain a diffusion halo outside the directly lit cone.
     */
    @Test
    void screenSpacePassConsumesTheRealSurfaceMaskThroughThePipeline() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT)
                .title("Subsurface Pipeline Consumer GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("subsurface-screen-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                PbrMaterialProperties properties = new PbrMaterialProperties(BASE_COLOR,
                        0.0f, BASE_ROUGHNESS, 0.0f, 1.0f, new Vector3f(), Map.of());
                SubsurfaceScatteringSettings materialSettings =
                        SubsurfaceScatteringSettings.builder()
                                .profile(SubsurfaceScatteringSettings.Profile.UNIFORM)
                                .scatterColor(1.0f, 0.2f, 0.1f)
                                .intensity(1.0f)
                                .profileScaleMetres(0.5f)
                                .thicknessSource(
                                        SubsurfaceScatteringSettings.ThicknessSource.CONSTANT)
                                .thicknessMetres(0.1f)
                                .diffusionRadiusMetres(0.5f)
                                .screenSpaceEnabled(true)
                                .maxDistanceMetres(5.0f)
                                .build();
                SubsurfaceScatteringSettings passSettings =
                        SubsurfaceScatteringSettings.builder()
                                .diffusionRadiusMetres(1.0f)
                                .maxDistanceMetres(10.0f)
                                .build();
                Material material = PbrMaterials.createWithSubsurface(shader, properties,
                        Map.of(), fallbacks, materialSettings);
                try {
                    RenderSettings renderSettings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    SceneLight spot = SceneLight.spot(new Vector3f(0.0f, 0.0f, -1.5f),
                            new Vector3f(0.0f, 0.0f, 1.0f), LIGHT_COLOR, 8.0f, 10.0f,
                            0.12f, 0.32f);
                    float[] withoutPass;
                    float[] withPass;
                    RenderPipeline baseline = screenPipeline(window, environment, quad,
                            material.createInstance(), spot, renderSettings,
                            PostProcessSettings.defaults());
                    try {
                        withoutPass = render(baseline, device);
                    } finally {
                        baseline.close();
                    }
                    RenderPipeline diffused = screenPipeline(window, environment, quad,
                            material.createInstance(), spot, renderSettings,
                            PostProcessSettings.defaults()
                                    .withSubsurfaceScattering(passSettings));
                    try {
                        withPass = render(diffused, device);
                    } finally {
                        diffused.close();
                    }

                    int halo = 0;
                    for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                        int offset = pixel * 4;
                        if (withoutPass[offset] < 0.02f
                                && withPass[offset] > withoutPass[offset] + 0.01f) {
                            halo++;
                        }
                    }
                    assertTrue(halo > 100,
                            "the real surface mask must create a visible diffusion halo, got "
                                    + halo + " pixels");
                    assertEquals(withoutPass[0], withPass[0], 1.0e-6f,
                            "a background corner outside the surface mask must be unchanged");
                    GlDebug.checkError("subsurface screen-space consumer");
                } finally {
                    material.close();
                }
            }
        }
    }

    private static RenderPipeline screenPipeline(GlfwWindow window, PbrEnvironment environment,
                                                 Mesh quad, MaterialInstance instance,
                                                 SceneLight light, RenderSettings renderSettings,
                                                 PostProcessSettings effects) {
        Camera camera = new Camera(CAMERA_POSITION, new Vector3f(0.0f, 1.0f, 0.0f),
                -90.0f, 0.0f);
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(quad, instance,
                Transform.at(0.0f, 0.0f, 0.0f)
                        .scale(QUAD_HALF * 2.0f, QUAD_HALF * 2.0f, 1.0f)));
        scene.addLight(light);
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings,
                environment).postProcessSettings(effects);
        RenderFeatureSettings direct = pipeline.features().effective().toBuilder()
                .imageBasedLighting(false)
                .directLighting(true)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .directionalCascades(DirectionalCascadeSettings.disabled())
                .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                .build();
        pipeline.features().submit(direct);
        pipeline.build();
        return pipeline;
    }

    private static RenderPipeline pipeline(GlfwWindow window, PbrEnvironment environment,
                                           Mesh quad, MaterialInstance instance,
                                           Vector3f lightTravel,
                                           RenderSettings renderSettings) {
        Camera camera = new Camera(CAMERA_POSITION, new Vector3f(0.0f, 1.0f, 0.0f),
                -90.0f, 0.0f);
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(quad, instance,
                Transform.at(0.0f, 0.0f, 0.0f)
                        .scale(QUAD_HALF * 2.0f, QUAD_HALF * 2.0f, 1.0f)));
        scene.addLight(SceneLight.directional(lightTravel, LIGHT_COLOR, LIGHT_INTENSITY));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings,
                environment);
        RenderFeatureSettings direct = pipeline.features().effective().toBuilder()
                .imageBasedLighting(false)
                .directLighting(true)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .directionalCascades(DirectionalCascadeSettings.disabled())
                .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                .build();
        pipeline.features().submit(direct);
        pipeline.build();
        return pipeline;
    }

    private static void enableForwardScatter(MaterialInstance instance,
                                             float thicknessMetres,
                                             float profileScaleMetres) {
        instance.clearOverrides();
        instance.setInt("uSubsurfaceEnabled", 1)
                .setVec3("uSubsurfaceScatterColor", new Vector3f(0.85f, 0.35f, 0.22f))
                .setFloat("uSubsurfaceIntensity", 1.0f)
                .setInt("uSubsurfaceProfile",
                        SubsurfaceScatteringSettings.Profile.SKIN.shaderIndex())
                .setFloat("uSubsurfaceProfileScale", profileScaleMetres)
                .setInt("uSubsurfaceThicknessSource",
                        SubsurfaceScatteringSettings.ThicknessSource.CONSTANT.shaderIndex())
                .setFloat("uSubsurfaceThickness", thicknessMetres)
                .setFloat("uSubsurfaceDiffusionRadius", 0.12f)
                .setInt("uSubsurfaceScreenSpace", 0)
                .setFloat("uSubsurfaceMaxDistance", 2.0f);
    }

    private static float[] render(RenderPipeline pipeline, GlRenderDevice device) {
        pipeline.execute(device);
        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                "the submitted feature request must publish before readback");
        float[] image = pipeline.captureLinearHdrRgbaFloat();
        assertEquals(WIDTH * HEIGHT * 4, image.length,
                "the fixture expects a " + WIDTH + "x" + HEIGHT + " framebuffer");
        return image;
    }

    private static float maxLuminance(float[] image) {
        float maximum = 0.0f;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            int offset = pixel * 4;
            maximum = Math.max(maximum, 0.2126f * image[offset]
                    + 0.7152f * image[offset + 1] + 0.0722f * image[offset + 2]);
        }
        return maximum;
    }
}
