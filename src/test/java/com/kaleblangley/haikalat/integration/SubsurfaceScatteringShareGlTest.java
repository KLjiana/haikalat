package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
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
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL acceptance for the S7 #32/#33 alpha diffuse-share contract, from PBR
 * forward through the shared surface alpha to the screen-space diffusion pass.
 *
 * <p>Two camera-facing PBR quads are rendered side by side with the same
 * luminance but opposite contribution composition. The specular quad sits at
 * the screen centre, where the directional light's half-vector is exactly the
 * geometric normal, so its tuned roughness reproduces the diffuse quad's
 * luminance:</p>
 * <ul>
 *   <li>a black-base, roughness-tuned mirror-like quad whose direct term is
 *       pure specular (alpha {@code 0});</li>
 *   <li>a white-base, fully rough quad with a two-texel base-colour map whose
 *       bright side exceeds the dark side by a hard texture edge (alpha
 *       {@code 1} diffuse share).</li>
 * </ul>
 *
 * <p>The fixture deliberately has the same luminance at the two sample points,
 * so the retired {@code [2,8]} luminance-band heuristic cannot separate them;
 * only the alpha share can. Assertions:</p>
 * <ul>
 *   <li>the disabled/no-pass frame writes the legacy opaque alpha {@code 1.0}
 *       at both samples (byte-compatible legacy path);</li>
 *   <li>the enabled frame writes alpha {@code 0} on the specular quad and a
 *       diffuse share near {@code 1} on the diffuse quad;</li>
 *   <li>every specular-quad pixel is bit-identical to the no-pass frame (the
 *       specular contribution is never diffused and receives no halo), while
 *       the diffuse quad changes because its texture edge diffuses.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SubsurfaceScatteringShareGlTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    private static final float CAMERA_Z = 3.4f;
    private static final float VERTICAL_FOV_DEGREES = 45.0f;
    private static final Vector3f CAMERA_POSITION = new Vector3f(0.0f, 0.0f, CAMERA_Z);
    private static final Vector3f LIGHT_TRAVEL = new Vector3f(0.0f, 0.0f, -1.0f);
    private static final Vector3f LIGHT_COLOR = new Vector3f(1.0f);
    private static final float LIGHT_INTENSITY = 2.5f;
    private static final float SPECULAR_ROUGHNESS = 0.3186f;
    private static final float DIFFUSE_ROUGHNESS = 1.0f;
    private static final float QUAD_HALF = 0.45f;
    private static final float SPECULAR_CENTRE_X = 0.0f;
    private static final float DIFFUSE_CENTRE_X = -1.2f;
    private static final float DIFFUSE_SAMPLE_X = -0.85f;

    private static final SubsurfaceScatteringSettings MATERIAL_SETTINGS =
            SubsurfaceScatteringSettings.builder()
                    .profile(SubsurfaceScatteringSettings.Profile.UNIFORM)
                    .scatterColor(1.0f, 0.2f, 0.1f)
                    .intensity(1.0f)
                    .profileScaleMetres(1.0f)
                    .thicknessSource(SubsurfaceScatteringSettings.ThicknessSource.CONSTANT)
                    .thicknessMetres(0.02f)
                    .diffusionRadiusMetres(0.35f)
                    .screenSpaceEnabled(true)
                    .maxDistanceMetres(20.0f)
                    .build();

    @Test
    void diffuseShareSeparatesEqualLuminanceSpecularFromDiffuse() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(WIDTH, HEIGHT)
                .title("Subsurface Diffuse Share GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram shader = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("subsurface-share-quad")).mesh());
                 Texture2D baseColor = Texture2D.fromRgba8(2, 1, new byte[]{
                         (byte) 51, (byte) 51, (byte) 51, (byte) 255,
                         (byte) 255, (byte) 255, (byte) 255, (byte) 255},
                         TextureColorSpace.SRGB);
                 Sampler sampler = Sampler.nearestRepeat();
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material diffuse = PbrMaterials.createWithSubsurface(shader,
                        new PbrMaterialProperties(new Vector4f(1.0f), 0.0f,
                                DIFFUSE_ROUGHNESS, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(PbrTextureRole.BASE_COLOR,
                                new PbrTextureBinding(baseColor, sampler)),
                        fallbacks, MATERIAL_SETTINGS);
                Material specular = PbrMaterials.createWithSubsurface(shader,
                        new PbrMaterialProperties(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
                                0.0f, SPECULAR_ROUGHNESS, 1.0f, 1.0f,
                                new Vector3f(), Map.of()),
                        Map.of(), fallbacks, MATERIAL_SETTINGS);
                try {
                    RenderSettings renderSettings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    PostProcessSettings passSettings = PostProcessSettings.defaults()
                            .withSubsurfaceScattering(SubsurfaceScatteringSettings.builder()
                                    .diffusionRadiusMetres(1.0f)
                                    .maxDistanceMetres(20.0f)
                                    .build());

                    float[] legacy;
                    RenderPipeline baseline = pipeline(window, environment, quad,
                            diffuse.createInstance(), specular.createInstance(),
                            renderSettings, PostProcessSettings.defaults());
                    try {
                        legacy = render(baseline, device);
                    } finally {
                        baseline.close();
                    }
                    float[] diffused;
                    RenderPipeline shared = pipeline(window, environment, quad,
                            diffuse.createInstance(), specular.createInstance(),
                            renderSettings, passSettings);
                    try {
                        diffused = render(shared, device);
                    } finally {
                        shared.close();
                    }

                    int diffusePixel = pixelAtX(DIFFUSE_SAMPLE_X);
                    int specularPixel = pixelAtX(SPECULAR_CENTRE_X);
                    int diffuseOffset = pixelOffset(diffusePixel, HEIGHT / 2);
                    int specularOffset = pixelOffset(specularPixel, HEIGHT / 2);
                    float diffuseLum = luminance(legacy, diffuseOffset);
                    float specularLum = luminance(legacy, specularOffset);
                    assertTrue(diffuseLum > 0.02f && specularLum > 0.02f,
                            "both sample quads must be shaded");
                    assertTrue(Math.abs(diffuseLum - specularLum)
                                    / Math.max(diffuseLum, specularLum) < 0.04f,
                            "the fixture must have equal luminance: diffuse=" + diffuseLum
                                    + " specular=" + specularLum);

                    assertEquals(1.0f, legacy[diffuseOffset + 3], 0.0f,
                            "the disabled path must keep the legacy opaque alpha");
                    assertEquals(1.0f, legacy[specularOffset + 3], 0.0f);
                    assertTrue(diffused[specularOffset + 3] < 0.02f,
                            "a fully specular PBR pixel must write alpha 0, got "
                                    + diffused[specularOffset + 3]);
                    assertTrue(diffused[diffuseOffset + 3] > 0.9f,
                            "a diffuse PBR pixel must write a near-1 diffuse share, got "
                                    + diffused[diffuseOffset + 3]);

                    int specularChanged = 0;
                    int diffuseChanged = 0;
                    for (int y = HEIGHT / 2 - 24; y <= HEIGHT / 2 + 24; y++) {
                        for (int x = pixelAtX(SPECULAR_CENTRE_X - QUAD_HALF);
                                x <= pixelAtX(SPECULAR_CENTRE_X + QUAD_HALF); x++) {
                            if (rgbChanged(legacy, diffused, pixelOffset(x, y))) {
                                specularChanged++;
                            }
                        }
                        for (int x = pixelAtX(DIFFUSE_CENTRE_X - QUAD_HALF);
                                x <= pixelAtX(DIFFUSE_CENTRE_X + QUAD_HALF); x++) {
                            if (rgbChanged(legacy, diffused, pixelOffset(x, y))) {
                                diffuseChanged++;
                            }
                        }
                    }
                    assertEquals(0, specularChanged,
                            "no specular-quad pixel may be diffused or receive a halo");
                    assertTrue(diffuseChanged > 100,
                            "the diffuse quad's texture edge must diffuse, changed="
                                    + diffuseChanged);
                    assertEquals(legacy[pixelOffset(2, 2)], diffused[pixelOffset(2, 2)], 0.0f,
                            "a background corner outside the mask must stay byte-identical");
                    GlDebug.checkError("subsurface diffuse-share acceptance");
                } finally {
                    diffuse.close();
                    specular.close();
                }
            }
        }
    }

    private static RenderPipeline pipeline(GlfwWindow window, PbrEnvironment environment,
                                           Mesh quad, MaterialInstance diffuse,
                                           MaterialInstance specular,
                                           RenderSettings renderSettings,
                                           PostProcessSettings effects) {
        Camera camera = new Camera(CAMERA_POSITION, new Vector3f(0.0f, 1.0f, 0.0f),
                -90.0f, 0.0f);
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(quad, diffuse,
                Transform.at(DIFFUSE_CENTRE_X, 0.0f, 0.0f)
                        .scale(QUAD_HALF * 2.0f, QUAD_HALF * 2.0f, 1.0f)));
        scene.add(MeshRenderer.of(quad, specular,
                Transform.at(SPECULAR_CENTRE_X, 0.0f, 0.0f)
                        .scale(QUAD_HALF * 2.0f, QUAD_HALF * 2.0f, 1.0f)));
        scene.addLight(SceneLight.directional(LIGHT_TRAVEL, LIGHT_COLOR, LIGHT_INTENSITY));
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings,
                environment).postProcessSettings(effects);
        RenderFeatureSettings direct = pipeline.features().effective().toBuilder()
                // features.submit() replaces the pipeline's post-process settings
                // with this request; carry the screen-space request explicitly.
                .postProcess(effects)
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

    private static float[] render(RenderPipeline pipeline, GlRenderDevice device) {
        pipeline.execute(device);
        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                "the submitted feature request must publish before readback");
        float[] image = pipeline.captureLinearHdrRgbaFloat();
        assertEquals(WIDTH * HEIGHT * 4, image.length,
                "the fixture expects a " + WIDTH + "x" + HEIGHT + " framebuffer");
        return image;
    }

    private static int pixelAtX(float worldX) {
        float halfWidth = CAMERA_Z * (float) Math.tan(
                Math.toRadians(VERTICAL_FOV_DEGREES * 0.5f)) * WIDTH / HEIGHT;
        return Math.round((worldX / halfWidth * 0.5f + 0.5f) * WIDTH);
    }

    private static int pixelOffset(int x, int y) {
        int clampedX = Math.max(0, Math.min(WIDTH - 1, x));
        int clampedY = Math.max(0, Math.min(HEIGHT - 1, y));
        return (clampedY * WIDTH + clampedX) * 4;
    }

    private static boolean rgbChanged(float[] before, float[] after, int offset) {
        return Math.abs(before[offset] - after[offset]) > 0.0f
                || Math.abs(before[offset + 1] - after[offset + 1]) > 0.0f
                || Math.abs(before[offset + 2] - after[offset + 2]) > 0.0f;
    }

    private static float luminance(float[] image, int offset) {
        return 0.2126f * image[offset] + 0.7152f * image[offset + 1]
                + 0.0722f * image[offset + 2];
    }
}
