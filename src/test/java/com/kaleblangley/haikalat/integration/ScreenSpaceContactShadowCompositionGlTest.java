package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
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
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowLightHints;
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
import com.kaleblangley.haikalat.subsystems.render3d.transparency.OitSurface;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitPass;
import com.kaleblangley.haikalat.subsystems.render3d.transparency.WeightedOitSettings;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfacePass;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-GL combination coverage for #9 screen-space contact shadows.
 *
 * <p>Covers three review gaps through public pipeline state and pixel readback:
 * the forward floor must keep its contact darkening while water and/or weighted
 * OIT own the transparent colour; only the selected directional shadow light may
 * contribute the contact factor when two directional lights are present; and a
 * derivative-normal receiver must not leak the contact factor across the thin
 * occluder silhouette or over the whole screen.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ScreenSpaceContactShadowCompositionGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final Vector3f LIGHT_DIRECTION = new Vector3f(1.6f, -1.0f, 0.35f);
    private static final ContactShadowSettings CONTACT =
            new ContactShadowSettings(true, 2.0f, 0.6f, 24, 1.0f);
    private static final float FLOOR_R = 0.12f;
    private static final float FLOOR_G = 0.18f;
    private static final float FLOOR_B = 0.24f;
    private static final float FLOOR_TOLERANCE = 2.0e-3f;
    private static final float DARKENING = 2.0e-3f;

    @Test
    void transparentCollectorsKeepTheContactDarkeningOnTheFloor() {
        assertCollectorPreservesContact(true, false);
        assertCollectorPreservesContact(false, true);
        assertCollectorPreservesContact(true, true);
    }

    @Test
    void onlyTheSelectedDirectionalShadowLightContributesTheContactFactor() {
        try (GlfwWindow window = window("multi-light contact shadows")) {
            bindContext(window);
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram floorShader = ShaderProgram.fromSources(FLOOR_VERTEX,
                         FLOOR_FRAGMENT);
                 ShaderProgram pbrShader = pbrShader();
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh floor = floorMesh();
                 Mesh quad = quadMesh();
                 PbrEnvironment environment = environment(device)) {
            Material floorMaterial = Material.builder(floorShader).build();
            Material receiverMaterial = pbrMaterial(pbrShader, fallbacks);
            try {
                Camera camera = standardCamera();
                Scene scene = new Scene(camera);
                scene.add(MeshRenderer.of(floor, floorMaterial, floorTransform())
                        .withoutShadows());
                scene.add(MeshRenderer.of(quad, receiverMaterial,
                        Transform.at(0.0f, 0.75f, 0.0f).scale(0.3f, 1.5f, 1.0f)));
                // The vertical panel faces +x: only the non-selected light can
                // irradiate it, so the contact factor must leave it untouched.
                scene.add(MeshRenderer.of(quad, receiverMaterial,
                        Transform.at(1.5f, 1.2f, -2.0f)
                                .rotationRadians(0.0f, (float) Math.PI / 2.0f, 0.0f)
                                .scale(1.5f, 1.5f, 1.0f)));
                // Non-selected light from +x, selected light from -x.  The
                // selected light owns uDirectionalShadowFrameLightIndex.
                scene.addLight(SceneLight.shadowedDirectional(
                        new Vector3f(-1.0f, -0.2f, 0.0f), new Vector3f(0.4f, 0.4f, 0.4f), 0.7f));
                scene.addLight(SceneLight.shadowedDirectional(
                        new Vector3f(1.0f, -0.2f, 0.0f), new Vector3f(1.0f, 0.8f, 0.6f), 1.2f),
                        ShadowLightHints.priority(1));
                RenderPipeline pipeline = pipeline(window, scene, environment);
                try {
                    pipeline.build();
                    submit(pipeline, false);
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    submit(pipeline, true);
                    pipeline.execute(device, 1.0f / 60.0f);
                    assertTrue(pipeline.graph().hasPass(
                                    ScreenSpaceContactShadowPass.PASS_NAME),
                            "the selected-light contact pass must be active");
                    Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
                    assertEquals(1, diagnostics.shadows().selectedLights().size(),
                            "exactly one directional light owns the shadow frame");
                    assertEquals(1, diagnostics.shadows().selectedLights().getFirst()
                                    .frameLightIndex(),
                            "the priority-1 light must be the selected shadow light");
                    assertTrue(diagnostics.shadows().rejectedLights().stream()
                                    .anyMatch(light -> light.frameLightIndex() == 0
                                            && light.reason().equals("LOWER_PRIORITY")),
                            "the first scene light must be rejected by priority");
                    float[] contact = pipeline.captureLinearHdrRgbaFloat();

                    Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT,
                            new Matrix4f());
                    Matrix4f view = camera.getViewMatrix(new Matrix4f());
                    int[] selectedSide = project(view, projection,
                            new Vector3f(0.5f, 0.02f, 0.0f));
                    int[] oppositeSide = project(view, projection,
                            new Vector3f(-0.5f, 0.02f, 0.0f));
                    int[] secondLightPanel = project(view, projection,
                            new Vector3f(1.5f, 1.2f, -2.0f));
                    assertNotNull(selectedSide);
                    assertNotNull(oppositeSide);
                    assertNotNull(secondLightPanel);

                    float selectedDelta = delta(baseline, contact, selectedSide);
                    float oppositeDelta = delta(baseline, contact, oppositeSide);
                    float panelDelta = delta(baseline, contact, secondLightPanel);
                    assertTrue(selectedDelta > 2.0e-2f,
                            "the selected light's contact side must darken, delta="
                                    + selectedDelta);
                    assertTrue(selectedDelta > oppositeDelta + 2.0e-2f,
                            "the selected light must dominate the contact march; selected="
                                    + selectedDelta + " opposite=" + oppositeDelta);
                    assertTrue(panelDelta <= 2.0e-3f,
                            "the second-light-only panel must stay at its lit baseline; delta="
                                    + panelDelta);

                    // Independent geometric check: the panel normal is +x, so
                    // the non-selected light contributes and the selected one
                    // cannot (n dot toLight < 0).
                    Vector3f panelNormal = new Vector3f(1.0f, 0.0f, 0.0f);
                    Vector3f toSelected = new Vector3f(1.0f, -0.2f, 0.0f).normalize().negate();
                    Vector3f toOther = new Vector3f(-1.0f, -0.2f, 0.0f).normalize().negate();
                    assertTrue(panelNormal.dot(toOther) > 0.9f);
                    assertTrue(panelNormal.dot(toSelected) < -0.9f);

                    int darker = 0;
                    int brighter = 0;
                    for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                        float change = luminance(contact, pixel) - luminance(baseline, pixel);
                        if (change < -1.0e-4f) darker++;
                        if (change > 1.0e-4f) brighter++;
                    }
                    assertTrue(darker > 0, "the selected contact must darken some pixels");
                    assertEquals(0, brighter, "contact shadows may only attenuate colour");
                    assertTrue(darker < WIDTH * HEIGHT / 10,
                            "an isolated occluder must not darken most of the screen, darker="
                                    + darker);

                    submit(pipeline, false);
                    pipeline.execute(device, 1.0f / 60.0f);
                    assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(), 0.0f,
                            "disabling contact shadows must restore the exact baseline");
                    GlDebug.assertNoError("multi-light contact shadows");
                } finally {
                    pipeline.close();
                }
            } finally {
                receiverMaterial.close();
                floorMaterial.close();
            }
            }
        }
    }

    @Test
    void derivativeNormalReceiverDoesNotLeakAcrossTheThinOccluderSilhouette() {
        // Independent geometry for the silhouette probe: 0.22 slope in x.
        float slope = 0.22f;
        try (GlfwWindow window = window("derivative-normal contact shadows")) {
            bindContext(window);
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram floorShader = ShaderProgram.fromSources(FLOOR_VERTEX,
                         FLOOR_FRAGMENT);
                 ShaderProgram pbrShader = pbrShader();
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh floor = slopedFloor(slope);
                 Mesh occluder = quadMesh();
                 PbrEnvironment environment = environment(device)) {
            Material floorMaterial = Material.builder(floorShader).build();
            Material occluderMaterial = pbrMaterial(pbrShader, fallbacks);
            try {
                Camera camera = new Camera(new Vector3f(2.2f, 1.7f, 4.5f),
                        new Vector3f(0.0f, 1.0f, 0.0f), -115.0f, -16.0f);
                Scene scene = new Scene(camera);
                scene.add(MeshRenderer.of(floor, floorMaterial, Transform.at(0f, 0f, 0f))
                        .withoutShadows());
                scene.add(MeshRenderer.of(occluder, occluderMaterial,
                        Transform.at(0.0f, 0.75f, 0.0f).scale(0.35f, 1.5f, 1.0f)));
                scene.addLight(SceneLight.shadowedDirectional(
                        new Vector3f(0.4f, -1.0f, 0.25f), new Vector3f(4.0f), 5.0f));
                RenderPipeline pipeline = pipeline(window, scene, environment);
                try {
                    pipeline.build();
                    submit(pipeline, false);
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();
                    submit(pipeline, true);
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] contact = pipeline.captureLinearHdrRgbaFloat();

                    Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT,
                            new Matrix4f());
                    Matrix4f view = camera.getViewMatrix(new Matrix4f());
                    int[] occluderPixel = project(view, projection,
                            new Vector3f(0.0f, 0.75f, 0.0f));
                    assertNotNull(occluderPixel);
                    int[] silhouetteProbe = findSilhouetteFloorProbe(baseline, view,
                            projection, slope, occluderPixel);
                    assertNotNull(silhouetteProbe,
                            "the fixture must contain a lit floor pixel adjacent to the "
                                    + "occluder silhouette");
                    float probeDelta = delta(baseline, contact, silhouetteProbe);
                    assertTrue(probeDelta <= 2.0e-3f,
                            "a floor pixel just beyond the derivative-normal silhouette must "
                                    + "stay at the lit baseline; delta=" + probeDelta
                                    + " at " + silhouetteProbe[0] + "," + silhouetteProbe[1]);

                    int[] contactProbe = project(view, projection,
                            new Vector3f(0.18f, 0.22f * 0.18f + 0.02f, 0.1f));
                    assertNotNull(contactProbe);
                    assertTrue(delta(baseline, contact, contactProbe) > 2.0e-3f,
                            "the sloped receiver must still receive a contact shadow");

                    int darker = 0;
                    for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                        if (luminance(baseline, pixel) - luminance(contact, pixel) > 5.0e-4f) {
                            darker++;
                        }
                    }
                    assertTrue(darker > 0, "the thin occluder must cast a contact shadow");
                    assertTrue(darker < WIDTH * HEIGHT / 10,
                            "an isolated thin occluder must not produce a full-screen "
                                    + "darkening, darker=" + darker);
                    GlDebug.assertNoError("derivative-normal contact shadows");
                } finally {
                    pipeline.close();
                }
            } finally {
                occluderMaterial.close();
                floorMaterial.close();
            }
            }
        }
    }

    private static void assertCollectorPreservesContact(boolean water, boolean oit) {
        try (GlfwWindow window = window("contact shadows with transparent collectors")) {
            bindContext(window);
            GlRenderDevice device = new GlRenderDevice();
            try (ShaderProgram floorShader = ShaderProgram.fromSources(FLOOR_VERTEX,
                         FLOOR_FRAGMENT);
                 ShaderProgram pbrShader = pbrShader();
                 PbrFallbackTextures fallbacks = new PbrFallbackTextures();
                 Mesh floor = floorMesh();
                 Mesh wall = quadMesh();
                 PbrEnvironment environment = environment(device)) {
            Material floorMaterial = Material.builder(floorShader).build();
            Material wallMaterial = pbrMaterial(pbrShader, fallbacks);
            try {
                Camera camera = new Camera(new Vector3f(2.2f, 1.8f, 4.5f),
                        new Vector3f(0.0f, 1.0f, 0.0f), -115.0f, -18.0f);
                Scene scene = new Scene(camera);
                scene.add(MeshRenderer.of(floor, floorMaterial, floorTransform())
                        .withoutShadows());
                scene.add(MeshRenderer.of(wall, wallMaterial,
                        Transform.at(0.0f, 1.0f, -1.0f).scale(6.0f, 2.0f, 1.0f)));
                scene.addLight(SceneLight.shadowedDirectional(LIGHT_DIRECTION,
                        new Vector3f(4.0f), 5.0f));
                RenderPipeline pipeline = pipeline(window, scene, environment);
                try {
                    if (water) {
                        pipeline.waterSurface(waterSettings());
                    }
                    if (oit) {
                        Vector3f right = camera.right().mul(5.0f);
                        Vector3f up = camera.up().mul(5.0f);
                        Vector3f center = camera.position().fma(1.2f, camera.front());
                        pipeline.weightedOit(WeightedOitSettings.defaults())
                                .weightedOitSurfaces(List.of(OitSurface.of(center, right, up,
                                        new Vector3f(0.05f, 0.08f, 0.02f), 0.25f)));
                    }
                    pipeline.build();
                    submit(pipeline, false);
                    pipeline.execute(device, 1.0f / 60.0f);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();
                    assertFalse(pipeline.graph().hasPass(
                                    ScreenSpaceContactShadowPass.PASS_NAME),
                            "the disabled baseline must not declare the contact pass");

                    submit(pipeline, true);
                    pipeline.execute(device, 1.0f / 60.0f);
                    assertTrue(pipeline.graph().hasPass(
                                    ScreenSpaceContactShadowPass.PASS_NAME),
                            "the contact pass must be active with " + collectorName(water, oit));
                    float[] contact = pipeline.captureLinearHdrRgbaFloat();

                    Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT,
                            new Matrix4f());
                    Matrix4f view = camera.getViewMatrix(new Matrix4f());
                    int[] point = project(view, projection,
                            new Vector3f(0.5f, 0.02f, -0.7f));
                    assertNotNull(point);
                    int pixel = point[1] * WIDTH + point[0];
                    float before = luminance(baseline, pixel);
                    float after = luminance(contact, pixel);
                    assertTrue(after < before - DARKENING,
                            "the floor under " + collectorName(water, oit)
                                    + " must keep the contact darkening; before=" + before
                                    + " after=" + after);

                    List<String> order = pipeline.graph().description().executionOrder();
                    int contactIndex = order.indexOf(ScreenSpaceContactShadowPass.PASS_NAME);
                    int waterIndex = order.indexOf(WaterSurfacePass.PASS_NAME);
                    int oitIndex = order.indexOf(WeightedOitPass.PASS_NAME);
                    assertTrue(contactIndex >= 0);
                    if (water) {
                        assertTrue(waterIndex > contactIndex,
                                "water must consume the contact-shadow colour, order=" + order);
                    }
                    if (oit) {
                        assertTrue(oitIndex > contactIndex,
                                "OIT must resolve after the contact-shadow composite, order="
                                        + order);
                    }
                    if (water && oit) {
                        assertTrue(waterIndex < oitIndex,
                                "the contact pass must not reorder water before OIT, order="
                                        + order);
                        var oitPass = pipeline.graph().description().passes().stream()
                                .filter(pass -> pass.name().equals(WeightedOitPass.PASS_NAME))
                                .findFirst().orElseThrow();
                        assertTrue(oitPass.directDependencies().contains(
                                        WaterSurfacePass.PASS_NAME),
                                "OIT must keep consuming the water colour producer, deps="
                                        + oitPass.directDependencies());
                    }

                    submit(pipeline, false);
                    pipeline.execute(device, 1.0f / 60.0f);
                    assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(), 0.0f,
                            "disabling contact shadows must restore the unshadowed floor for "
                                    + collectorName(water, oit));
                    GlDebug.assertNoError("contact shadows with " + collectorName(water, oit));
                } finally {
                    pipeline.close();
                }
            } finally {
                wallMaterial.close();
                floorMaterial.close();
            }
            }
        }
    }

    private static String collectorName(boolean water, boolean oit) {
        if (water && oit) return "water+OIT";
        return water ? "water" : oit ? "OIT" : "no collector";
    }

    private static RenderPipeline pipeline(GlfwWindow window, Scene scene,
                                           PbrEnvironment environment) {
        RenderSettings settings = RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
        RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment);
        pipeline.directionalCascades(DirectionalCascadeSettings.disabled());
        return pipeline;
    }

    private static void submit(RenderPipeline pipeline, boolean contact) {
        ShadowSettings shadow = new ShadowSettings(64, 6.0f, 0.1f, 40.0f, 0.05f,
                contact ? CONTACT : ContactShadowSettings.disabled());
        pipeline.features().submit(pipeline.features().effective().toBuilder()
                .directionalShadowMap(new DirectionalShadowMap(shadow))
                .build());
    }

    private static int[] findSilhouetteFloorProbe(float[] baseline, Matrix4f view,
                                                  Matrix4f projection, float slope,
                                                  int[] occluderPixel) {
        for (int y = 1; y < HEIGHT - 1; y++) {
            for (int x = 1; x < WIDTH - 1; x++) {
                int pixel = y * WIDTH + x;
                if (!sameFloor(baseline, pixel)) continue;
                if (Math.abs(x - occluderPixel[0]) > 14
                        || Math.abs(y - occluderPixel[1]) > 34) continue;
                boolean adjacentToOccluder = !sameFloor(baseline, pixel - 1)
                        || !sameFloor(baseline, pixel + 1)
                        || !sameFloor(baseline, pixel - WIDTH)
                        || !sameFloor(baseline, pixel + WIDTH);
                if (!adjacentToOccluder) continue;
                Vector3f floor = floorPoint(view, projection, x, y, slope);
                if (floor == null) continue;
                if (lightRayHitsOccluder(floor, new Vector3f(0.4f, -1.0f, 0.25f))) {
                    continue;
                }
                return new int[]{x, y};
            }
        }
        return null;
    }

    private static boolean lightRayHitsOccluder(Vector3f floor, Vector3f lightDirection) {
        Vector3f toLight = new Vector3f(lightDirection).normalize().negate();
        if (!(Math.abs(toLight.z) > 1.0e-6f)) return true;
        float t = -floor.z / toLight.z;
        if (!(t > 0.0f) || t > CONTACT.distance() + CONTACT.thickness()) return false;
        Vector3f hit = new Vector3f(floor).fma(t, toLight);
        return Math.abs(hit.x) <= 0.175f && hit.y >= 0.0f && hit.y <= 1.55f;
    }

    private static Vector3f floorPoint(Matrix4f view, Matrix4f projection, int x, int y,
                                       float slope) {
        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        float ndcX = (x + 0.5f) / WIDTH * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / HEIGHT * 2.0f - 1.0f;
        Vector4f near = inverse.transform(new Vector4f(ndcX, ndcY, -1.0f, 1.0f));
        near.div(near.w);
        Vector4f far = inverse.transform(new Vector4f(ndcX, ndcY, 1.0f, 1.0f));
        far.div(far.w);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z);
        float denominator = -slope * direction.x + direction.y;
        if (!(Math.abs(denominator) > 1.0e-6f)) return null;
        float t = -(-slope * near.x + near.y) / denominator;
        if (!(t > 0.0f)) return null;
        return new Vector3f(near.x + direction.x * t, near.y + direction.y * t,
                near.z + direction.z * t);
    }

    private static boolean sameFloor(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return Math.abs(rgba[offset] - FLOOR_R) <= FLOOR_TOLERANCE
                && Math.abs(rgba[offset + 1] - FLOOR_G) <= FLOOR_TOLERANCE
                && Math.abs(rgba[offset + 2] - FLOOR_B) <= FLOOR_TOLERANCE;
    }

    private static float delta(float[] baseline, float[] contact, int[] point) {
        int pixel = point[1] * WIDTH + point[0];
        return luminance(baseline, pixel) - luminance(contact, pixel);
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }

    private static int[] project(Matrix4f view, Matrix4f projection, Vector3f world) {
        Vector4f clip = new Matrix4f(projection).mul(view)
                .transform(new Vector4f(world, 1.0f));
        if (clip.w <= 0.0f) return null;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f) return null;
        return new int[]{
                Math.max(0, Math.min(WIDTH - 1,
                        Math.round((ndcX * 0.5f + 0.5f) * WIDTH))),
                Math.max(0, Math.min(HEIGHT - 1,
                        Math.round((ndcY * 0.5f + 0.5f) * HEIGHT)))};
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

    private static Camera standardCamera() {
        return new Camera(new Vector3f(2.2f, 1.7f, 4.5f),
                new Vector3f(0.0f, 1.0f, 0.0f), -115.0f, -16.0f);
    }

    private static Mesh floorMesh() {
        return Mesh.from(BuiltinMeshData.coloredQuad("contact-shadow-floor"));
    }

    private static Mesh quadMesh() {
        return Mesh.from(TangentGenerator.generate(
                BuiltinMeshData.texturedQuad("contact-shadow-quad")).mesh());
    }

    private static Mesh slopedFloor(float slope) {
        float size = 12.0f;
        float[] vertices = {
                -size, -slope * size, -size,
                -size, -slope * size, size,
                size, slope * size, size,
                size, slope * size, -size
        };
        VertexAttribute position = VertexAttribute.builder().index(0).size(3)
                .offsetBytes(0L).semantic(VertexSemantic.POSITION).build();
        return Mesh.builder().vertices(vertices, 3 * Float.BYTES, position)
                .indices(new int[]{0, 1, 2, 0, 2, 3}).build();
    }

    private static Transform floorTransform() {
        return Transform.at(0.0f, 0.0f, 0.0f)
                .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                .scale(12.0f, 12.0f, 1.0f);
    }

    private static ShaderProgram pbrShader() {
        return ShaderProgram.fromResource(ScreenSpaceContactShadowCompositionGlTest.class,
                "/shaders/render3d/pbr/pbr-forward.vert",
                "/shaders/render3d/pbr/pbr-forward.frag");
    }

    private static Material pbrMaterial(ShaderProgram shader, PbrFallbackTextures fallbacks) {
        return PbrMaterials.create(shader,
                new PbrMaterialProperties(new Vector4f(0.72f, 0.68f, 0.62f, 1.0f),
                        0.0f, 0.85f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                Map.of(), fallbacks);
    }

    private static PbrEnvironment environment(GlRenderDevice device) {
        return PbrEnvironmentLoader.load(device,
                ScreenSpaceContactShadowCompositionGlTest.class,
                "/environments/pbr/studio-small.hdr", PbrEnvironmentSettings.testQuality());
    }

    private static WaterSurfaceSettings waterSettings() {
        return WaterSurfaceSettings.builder()
                .enabled(true).level(0.12f).bounds(-12.0f, -12.0f, 12.0f, 12.0f)
                .waveWavelength(8.0f).waveSpeed(0.0f).waveAmplitude(0.0f)
                .waveDirectionRadians(0.0f)
                .waterColor(0.02f, 0.12f, 0.16f).absorptionDensity(0.2f)
                .shoreFadeDistance(0.0f).reflectionFallback(0.35f, 0.5f, 0.65f)
                .build();
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
