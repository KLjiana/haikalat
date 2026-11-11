package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.RefractionSettings;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.RefractionPass;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.RefractionReferenceMath;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-consumer acceptance for the bounded single-layer refraction path.
 *
 * <p>Fixtures: a screen-filling emissive ramp wall, a tilted glass quad
 * with a known IOR/thickness and a deterministic camera. The reference
 * projection/refraction math lives in test sources and never calls the
 * production shader or pass. The disabled case proves the graph has no
 * refraction pass and a material without enabled settings follows the ordinary
 * transparent stream.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class RefractionGlTest {
    /** Requested window size; the OS may clamp the actual framebuffer width. */
    private static final int SIZE = 128;
    private static final float TILT = (float) Math.toRadians(30.0);
    private static final Vector3f GLASS_NORMAL =
            new Vector3f((float) Math.sin(TILT), 0.0f, (float) Math.cos(TILT));
    private static final RefractionSettings GLASS_SETTINGS =
            RefractionSettings.of(1.5f, 1.0f, 0.0f, 1.0f, 0.0f);
    private static final float FALLBACK_TILT = (float) Math.toRadians(45.0);
    private static final Vector3f FALLBACK_NORMAL =
            new Vector3f((float) Math.sin(FALLBACK_TILT), 0.0f,
                    (float) Math.cos(FALLBACK_TILT));
    private static final RefractionSettings FALLBACK_SETTINGS =
            RefractionSettings.of(2.5f, 2.0f, 0.0f, 1.0f, 0.0f);

    @Test
    void refractionFollowsTheIorOffsetAndFallsBackAtTheScreenEdge() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Refraction GL Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            int width = window.width();
            int height = window.height();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram pbr = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 ShaderProgram glass = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/refraction-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("refraction-quad")).mesh());
                 Texture2D bars = emissiveRamp();
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material background = backgroundMaterial(pbr, fallbacks, bars);
                Material glassMaterial = PbrMaterials.createGlass(glass,
                        PbrMaterialProperties.defaults(), Map.of(), fallbacks,
                        GLASS_SETTINGS);
                Material fallbackMaterial = PbrMaterials.createGlass(glass,
                        PbrMaterialProperties.defaults(), Map.of(), fallbacks,
                        FALLBACK_SETTINGS);
                try {
                    Camera camera = camera();
                    Matrix4f projection = CameraProjection.stable(camera, width, height,
                            new Matrix4f());
                    Matrix4f view = camera.getViewMatrix(new Matrix4f());

                    RenderResult baseline = render(window, device, backgroundScene(camera,
                            quad, background), environment);
                    RenderResult refracted = render(window, device, refractedScene(camera,
                            quad, background, glassMaterial, 0.0f), environment);
                    RenderResult fallback = render(window, device, fallbackScene(camera,
                            quad, background, fallbackMaterial), environment);

                    assertTrue(hasPass(refracted.graph(), RefractionPass.SOURCE_COPY_PASS));
                    assertTrue(hasPass(refracted.graph(), RefractionPass.PASS_NAME));
                    assertFalse(hasPass(baseline.graph(), RefractionPass.PASS_NAME),
                            "a scene without refraction materials must not add the glass pass");
                    assertNoFeedbackTopology(refracted.graph());

                    int x = width / 2;
                    int y = height / 2;
                    Vector3f world = RefractionReferenceMath.worldOnPlane(projection, view,
                            x, y, width, height, new Vector3f(), GLASS_NORMAL);
                    assertNotNull(world);
                    Vector2f centre = RefractionReferenceMath.projectToUv(projection, view, world);
                    Vector2f offset = RefractionReferenceMath.screenOffsetUv(projection, view,
                            world, GLASS_NORMAL, camera.position(), GLASS_SETTINGS.ior(),
                            GLASS_SETTINGS.thickness(), width, height);
                    // The authored normal points toward +X, so the transmitted
                    // ray leaves toward -X and the known emissive ramp must be
                    // sampled from the left.
                    assertTrue(offset.x * width < -2.0f,
                            "IOR/thickness must move the sample by at least two pixels, got "
                                    + offset.x * width);
                    Vector2f shifted = new Vector2f(centre).add(offset);
                    assertTrue(shifted.x >= 0.0f && shifted.x <= 1.0f,
                            "the centre refraction lookup must stay on screen");
                    float[] expected = RefractionReferenceMath.sampleBilinear(
                            baseline.hdr(), width, height, shifted.x, shifted.y);
                    assertPixel(expected, refracted.hdr(), width, x, y, 2.0e-3f);

                    float[] unrefracted = RefractionReferenceMath.sampleBilinear(
                            baseline.hdr(), width, height, centre.x, centre.y);
                    assertTrue(maxChannelDifference(expected, unrefracted) > 1.0e-3f,
                            "the fixture ramp must make the offset observable");

                    // A 45 degree, high-IOR/thickness glass pushes the left-edge
                    // lookup off screen; that pixel must copy the untouched
                    // background rather than clamp or smear the border.
                    int edgeX = 0;
                    int edgeY = height / 2;
                    Vector3f edgeWorld = RefractionReferenceMath.worldOnPlane(projection, view,
                            edgeX, edgeY, width, height, new Vector3f(), FALLBACK_NORMAL);
                    assertNotNull(edgeWorld);
                    Vector2f edgeCentre = RefractionReferenceMath.projectToUv(
                            projection, view, edgeWorld);
                    Vector2f edgeOffset = RefractionReferenceMath.screenOffsetUv(
                            projection, view, edgeWorld, FALLBACK_NORMAL, camera.position(),
                            FALLBACK_SETTINGS.ior(), FALLBACK_SETTINGS.thickness(),
                            width, height);
                    assertTrue(edgeCentre.x + edgeOffset.x < 0.0f,
                            "edge fixture must actually leave the snapshot");
                    assertPixel(RefractionReferenceMath.sampleBilinear(baseline.hdr(),
                                    width, height, edgeCentre.x, edgeCentre.y),
                            fallback.hdr(), width, edgeX, edgeY, 1.0e-5f);

                    // The same fallback glass must still be visible where its
                    // lookup stays inside the snapshot.
                    Vector3f probeWorld = RefractionReferenceMath.worldOnPlane(projection, view,
                            width / 2, height / 2, width, height, new Vector3f(),
                            FALLBACK_NORMAL);
                    assertNotNull(probeWorld);
                    Vector2f probeCentre = RefractionReferenceMath.projectToUv(
                            projection, view, probeWorld);
                    Vector2f probeOffset = RefractionReferenceMath.screenOffsetUv(
                            projection, view, probeWorld, FALLBACK_NORMAL, camera.position(),
                            FALLBACK_SETTINGS.ior(), FALLBACK_SETTINGS.thickness(),
                            width, height);
                    Vector2f probeShifted = new Vector2f(probeCentre).add(probeOffset);
                    assertTrue(probeShifted.x >= 0.0f && probeShifted.x <= 1.0f,
                            "the fallback fixture must refract inside the image at its centre");
                    assertTrue(maxChannelDifference(
                                    pixel(fallback.hdr(), width, width / 2, height / 2),
                                    pixel(baseline.hdr(), width, width / 2, height / 2))
                                    > 1.0e-3f,
                            "the fallback glass must be drawn at its centre");
                    assertPixel(RefractionReferenceMath.sampleBilinear(baseline.hdr(),
                                    width, height, probeShifted.x, probeShifted.y),
                            fallback.hdr(), width, width / 2, height / 2, 2.0e-3f);
                    GlDebug.checkError("refraction acceptance");
                } finally {
                    fallbackMaterial.close();
                    glassMaterial.close();
                    background.close();
                }
            }
        }
    }

    @Test
    void overlappingGlassDoesNotRefractOtherGlassAndDisabledSettingsSkipThePass() {
        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("Refraction Layer Test").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            // Windows may clamp the requested 128px width (observed 152x128);
            // all reference math and pixel indexing use the actual framebuffer.
            int width = window.width();
            int height = window.height();
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram pbr = ShaderProgram.fromResource(getClass(),
                    "/shaders/render3d/pbr/pbr-forward.vert",
                    "/shaders/render3d/pbr/pbr-forward.frag");
                 ShaderProgram glass = ShaderProgram.fromResource(getClass(),
                         "/shaders/render3d/pbr/pbr-forward.vert",
                         "/shaders/render3d/pbr/refraction-forward.frag");
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh quad = Mesh.from(TangentGenerator.generate(
                         BuiltinMeshData.texturedQuad("refraction-layer-quad")).mesh());
                 Texture2D bars = emissiveRamp();
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material background = backgroundMaterial(pbr, fallbacks, bars);
                Material front = PbrMaterials.createGlass(glass,
                        PbrMaterialProperties.defaults(), Map.of(), fallbacks, GLASS_SETTINGS);
                Material back = PbrMaterials.createGlass(glass,
                        new PbrMaterialProperties(new Vector4f(0.15f, 0.85f, 0.3f, 1.0f),
                                0.0f, 1.0f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks,
                        RefractionSettings.of(1.4f, 0.6f, 0.0f, 1.0f, 0.0f));
                Material ordinaryTransparent = PbrMaterials.createWithBindings(pbr,
                        new PbrMaterialProperties(new Vector4f(0.9f, 0.1f, 0.1f, 1.0f),
                                0.0f, 0.35f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks, CullMode.NONE, false, false, 0.0f,
                        BlendMode.ALPHA);
                try {
                    Camera camera = camera();
                    RenderResult baseline = render(window, device, backgroundScene(camera,
                            quad, background), environment);
                    RenderResult frontOnly = render(window, device, refractedScene(camera,
                            quad, background, front, 0.0f), environment);
                    RenderResult both = render(window, device, sceneTwoLayers(camera, quad,
                            background, front, back), environment);
                    RenderResult disabled = render(window, device, ordinaryScene(camera,
                            quad, background, ordinaryTransparent), environment);

                    assertFalse(hasPass(disabled.graph(), RefractionPass.PASS_NAME),
                            "disabled refraction settings must keep the ordinary transparent path");
                    assertFalse(hasPass(disabled.graph(), RefractionPass.SOURCE_COPY_PASS));

                    int x = width / 2;
                    int y = height / 2;

                    assertTrue(maxChannelDifference(pixel(frontOnly.hdr(), width, x, y),
                            pixel(baseline.hdr(), width, x, y)) > 1.0e-3f,
                            "the single-layer fixture must visibly refract the background");
                    for (int offsetY = -4; offsetY <= 4; offsetY += 2) {
                        for (int offsetX = -4; offsetX <= 4; offsetX += 2) {
                            assertArrayEquals(pixel(frontOnly.hdr(), width, x + offsetX, y + offsetY),
                                    pixel(both.hdr(), width, x + offsetX, y + offsetY), 1.0e-6f,
                                    "overlapping glass must not refract the other glass layer at "
                                            + (x + offsetX) + "," + (y + offsetY));
                        }
                    }
                    assertTrue(maxChannelDifference(pixel(frontOnly.hdr(), width, x, y),
                            pixel(disabled.hdr(), width, x, y)) > 4.0e-3f,
                            "enabled refraction must differ from the disabled transparent path");
                    assertNoFeedbackTopology(frontOnly.graph());
                    GlDebug.checkError("refraction layer acceptance");
                } finally {
                    ordinaryTransparent.close();
                    back.close();
                    front.close();
                    background.close();
                }
            }
        }
    }

    private static boolean hasPass(RenderGraph.Description graph, String name) {
        return graph.passes().stream().anyMatch(pass -> pass.name().equals(name));
    }

    private static void assertNoFeedbackTopology(RenderGraph.Description graph) {
        var order = graph.executionOrder();
        int copy = order.indexOf(RefractionPass.SOURCE_COPY_PASS);
        int glass = order.indexOf(RefractionPass.PASS_NAME);
        int geometry = order.indexOf(PostProcessTargets.GEOMETRY_PASS);
        assertTrue(geometry >= 0 && copy > geometry && glass > copy,
                "snapshot/glass passes must run after geometry in order");

        RenderGraph.PassDescription copyPass = graph.passes().stream()
                .filter(pass -> pass.name().equals(RefractionPass.SOURCE_COPY_PASS))
                .findFirst().orElseThrow();
        RenderGraph.PassDescription glassPass = graph.passes().stream()
                .filter(pass -> pass.name().equals(RefractionPass.PASS_NAME))
                .findFirst().orElseThrow();
        assertTrue(copyPass.colorAttachments().stream().anyMatch(attachment ->
                attachment.logicalName().equals(RefractionPass.SOURCE_TEXTURE)));
        assertTrue(glassPass.colorAttachments().stream().anyMatch(attachment ->
                attachment.logicalName().equals(PostProcessTargets.SCENE_COLOR)));
        assertFalse(RefractionPass.SOURCE_TEXTURE.equals(PostProcessTargets.SCENE_COLOR),
                "the glass pass writes sceneColor and samples a distinct snapshot");
        assertTrue(glassPass.directDependencies().contains(RefractionPass.SOURCE_COPY_PASS));
        assertTrue(glassPass.directDependencies().contains(PostProcessTargets.GEOMETRY_PASS));
    }

    private static RenderResult render(GlfwWindow window, GlRenderDevice device, Scene scene,                                       PbrEnvironment environment) {
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, renderSettings(),
                environment);
        try {
            pipeline.build();
            pipeline.execute(device);
            return new RenderResult(pipeline.captureLinearHdrRgbaFloat(),
                    pipeline.graph().description());
        } finally {
            pipeline.close();
        }
    }

    private static RenderSettings renderSettings() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static Camera camera() {
        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 3.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
        camera.setZoom(60.0f);
        return camera;
    }

    private static Scene backgroundScene(Camera camera, Mesh quad, Material background) {
        Scene scene = new Scene(camera);
        scene.add(MeshRenderer.of(quad, background,
                Transform.at(0.0f, 0.0f, -5.0f).scale(16.0f, 16.0f, 1.0f))
                .withoutShadows());
        return scene;
    }

    private static Scene refractedScene(Camera camera, Mesh quad, Material background,
                                        Material glass, float z) {
        Scene scene = backgroundScene(camera, quad, background);
        scene.add(MeshRenderer.of(quad, glass,
                Transform.at(0.0f, 0.0f, z)
                        .rotationRadians(0.0f, TILT, 0.0f)
                        .scale(6.0f, 6.0f, 1.0f))
                .withoutShadows());
        return scene;
    }

    private static Scene fallbackScene(Camera camera, Mesh quad, Material background,
                                       Material fallback) {
        Scene scene = backgroundScene(camera, quad, background);
        scene.add(MeshRenderer.of(quad, fallback,
                Transform.at(0.0f, 0.0f, 0.0f)
                        .rotationRadians(0.0f, FALLBACK_TILT, 0.0f)
                        .scale(6.0f, 6.0f, 1.0f))
                .withoutShadows());
        return scene;
    }

    private static Scene sceneTwoLayers(Camera camera, Mesh quad, Material background,
                                        Material front, Material back) {
        Scene scene = backgroundScene(camera, quad, background);
        scene.add(MeshRenderer.of(quad, back,
                Transform.at(0.0f, 0.0f, -1.0f)
                        .rotationRadians(0.0f, (float) Math.toRadians(-15.0), 0.0f)
                        .scale(6.0f, 6.0f, 1.0f))
                .withoutShadows());
        scene.add(MeshRenderer.of(quad, front,
                Transform.at(0.0f, 0.0f, 0.0f)
                        .rotationRadians(0.0f, TILT, 0.0f)
                        .scale(6.0f, 6.0f, 1.0f))
                .withoutShadows());
        return scene;
    }

    private static Scene ordinaryScene(Camera camera, Mesh quad, Material background,
                                       Material transparent) {
        Scene scene = backgroundScene(camera, quad, background);
        scene.add(MeshRenderer.of(quad, transparent,
                Transform.at(0.0f, 0.0f, 0.0f)
                        .rotationRadians(0.0f, TILT, 0.0f)
                        .scale(6.0f, 6.0f, 1.0f))
                .withoutShadows());
        return scene;
    }

    private static Material backgroundMaterial(ShaderProgram pbr,
                                               PbrFallbackTextures fallbacks,
                                               Texture2D ramp) {
        // A bright emissive ramp keeps the background independent of
        // environment lighting, so a five-pixel refraction shift produces an
        // unambiguous, deterministic colour difference.
        PbrMaterialProperties properties = new PbrMaterialProperties(
                new Vector4f(0.0f, 0.0f, 0.0f, 1.0f), 0.0f, 1.0f, 1.0f, 1.0f,
                new Vector3f(4.0f), Map.of(PbrTextureRole.EMISSIVE, "generated-ramp"));
        return PbrMaterials.create(pbr, properties,
                Map.of(PbrTextureRole.EMISSIVE, ramp),
                fallbacks);
    }

    private static Texture2D emissiveRamp() {
        byte[] pixels = new byte[64 * 4];
        for (int x = 0; x < 64; x++) {
            pixels[x * 4] = (byte) (x * 4);
            pixels[x * 4 + 1] = (byte) 96;
            pixels[x * 4 + 2] = (byte) 32;
            pixels[x * 4 + 3] = (byte) 255;
        }
        return Texture2D.fromRgba8(64, 1, pixels, TextureColorSpace.SRGB);
    }

    private static float[] pixel(float[] rgba, int width, int x, int y) {
        int offset = (y * width + x) * 4;
        return new float[]{rgba[offset], rgba[offset + 1], rgba[offset + 2], rgba[offset + 3]};
    }

    private static float maxChannelDifference(float[] left, float[] right) {
        float difference = 0.0f;
        for (int channel = 0; channel < 3; channel++) {
            difference = Math.max(difference, Math.abs(left[channel] - right[channel]));
        }
        return difference;
    }

    private static void assertPixel(float[] expected, float[] actual, int width,
                                    int x, int y, float tolerance) {
        int offset = (y * width + x) * 4;
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected[channel], actual[offset + channel], tolerance,
                    "unexpected channel " + channel + " at " + x + "," + y);
        }
    }

    private record RenderResult(float[] hdr, RenderGraph.Description graph) {
    }
}
