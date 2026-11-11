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
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowPipelineSettings;
import com.kaleblangley.haikalat.subsystems.render3d.LocalShadowSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureStatus;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowFilterMode;
import com.kaleblangley.haikalat.subsystems.render3d.ShadowSelectionMode;
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
import com.kaleblangley.haikalat.subsystems.render3d.shadow.PcssSettings;
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
 * Real-consumer acceptance for the main directional light's bounded PCSS path.
 *
 * <p>The fixture is a horizontal PBR floor with a vertical blocker wall; the
 * shadowed directional light casts the wall's shadow onto a known floor strip.
 * The analytic shadow/penumbra region is derived from the wall plane, the
 * normalized light direction and the configured shadow-map texel size, not from
 * any previously captured image.  Against a hard ({@link ShadowFilterMode#HARD})
 * baseline the PCSS image must:</p>
 *
 * <ul>
 *   <li>darken the lit side and brighten the hard-shadow side of the known
 *       boundary, i.e. produce a real non-binary penumbra;</li>
 *   <li>stay inside the configured filter-radius bound in shadow texels;</li>
 *   <li>change no floor pixel outside the analytic shadow plus penumbra band,
 *       which is the tile-bleeding control (a tap that crossed into a
 *       neighbouring cascade tile would darken or brighten an unrelated lit
 *       region);</li>
 *   <li>restore the exact previous image when PCSS is disabled again.</li>
 * </ul>
 *
 * <p>The same comparison is repeated with four cascades so the directional
 * sampler runs through the atlas tile path.  This test is guarded by
 * {@code haikalat.glSmoke} and is not part of the no-desktop check.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class PcssGlTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final float WALL_Z = -2.0f;
    private static final float WALL_HEIGHT = 3.0f;
    private static final float WALL_HALF_WIDTH = 4.0f;
    private static final float FLOOR_HALF_SIZE = 12.0f;
    private static final float SHADOW_SCENE_RADIUS = 20.0f;
    private static final int SHADOW_RESOLUTION = 256;
    private static final int FILTER_RADIUS_TEXELS = 8;
    private static final float LIGHT_WORLD_SIZE = 8.0f;
    private static final Vector3f LIGHT_DIRECTION = new Vector3f(0.35f, -1.0f, 0.25f);

    @Test
    void pcssWidensKnownPenumbraAndDoesNotBleedAcrossAtlasTiles() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("PCSS GL Test")
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
                         BuiltinMeshData.texturedQuad("pcss-quad")).mesh());
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material material = PbrMaterials.create(shader,
                        new PbrMaterialProperties(new Vector4f(0.78f, 0.75f, 0.70f, 1.0f),
                                0.0f, 0.95f, 1.0f, 1.0f, new Vector3f(), Map.of()),
                        Map.of(), fallbacks);
                try {
                    Camera camera = new Camera(new Vector3f(0.0f, 7.0f, 9.0f),
                            new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -38.0f);
                    Scene scene = new Scene(camera);
                    SceneLight sun = SceneLight.shadowedDirectional(LIGHT_DIRECTION,
                            new Vector3f(3.0f), 3.0f);
                    scene.add(MeshRenderer.of(quad, material,
                            Transform.at(0.0f, 0.0f, 0.0f)
                                    .rotationRadians(-(float) Math.PI / 2.0f, 0.0f, 0.0f)
                                    .scale(FLOOR_HALF_SIZE * 2.0f, FLOOR_HALF_SIZE * 2.0f, 1.0f))
                            .withoutShadows());
                    scene.add(MeshRenderer.of(quad, material,
                            Transform.at(0.0f, WALL_HEIGHT * 0.5f, WALL_Z)
                                    .scale(WALL_HALF_WIDTH * 2.0f, WALL_HEIGHT, 1.0f)));
                    scene.addLight(sun);
                    RenderSettings renderSettings = RenderSettings.builder()
                            .antiAliasingMode(com.kaleblangley.haikalat.core.AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            renderSettings, environment);
                    try {
                        LocalShadowPipelineSettings hardFilter = hardFilterSettings();
                        PcssSettings disabled = PcssSettings.disabled();
                        PcssSettings enabled = new PcssSettings(true, LIGHT_WORLD_SIZE,
                                8, 16, FILTER_RADIUS_TEXELS, 24, 0.001f);
                        RenderFeatureSettings hardRequest = pipeline.features().effective()
                                .toBuilder()
                                .imageBasedLighting(false)
                                .directionalShadows(true)
                                .localShadows(hardFilter)
                                .directionalCascades(DirectionalCascadeSettings.disabled())
                                .directionalShadowMap(new DirectionalShadowMap(
                                        shadowSettings(disabled)))
                                .build();
                        RenderFeatureSettings pcssRequest = hardRequest.toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(
                                        shadowSettings(enabled)))
                                .build();

                        pipeline.features().submit(hardRequest);
                        pipeline.build();
                        pipeline.execute(device);
                        float[] singleHard = pipeline.captureLinearHdrRgbaFloat();

                        pipeline.features().submit(pcssRequest);
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status(),
                                "PCSS feature request must publish before readback");
                        float[] singlePcss = pipeline.captureLinearHdrRgbaFloat();

                        Matrix4f inverse = inverseViewProjection(camera);
                        Vector3f light = new Vector3f(LIGHT_DIRECTION).normalize();
                        float texelWorld = 2.0f * SHADOW_SCENE_RADIUS / SHADOW_RESOLUTION;
                        assertPenumbraAndNoBleed("single cascade", singleHard, singlePcss,
                                inverse, light, texelWorld, FILTER_RADIUS_TEXELS, 2.0f);
                        assertOnlyAttenuatesOrRelights("single cascade", singleHard,
                                singlePcss, inverse);

                        pipeline.features().submit(hardRequest);
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                        assertArrayEquals(singleHard, pipeline.captureLinearHdrRgbaFloat(), 0.0f,
                                "disabling PCSS must restore the exact previous output");

                        RenderFeatureSettings cascadeHard = hardRequest.toBuilder()
                                .directionalCascades(new DirectionalCascadeSettings(
                                        4, 1024, 0.6f, 0.08f, 14.0f))
                                .build();
                        RenderFeatureSettings cascadePcss = cascadeHard.toBuilder()
                                .directionalShadowMap(new DirectionalShadowMap(
                                        shadowSettings(enabled)))
                                .build();
                        pipeline.features().submit(cascadeHard);
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                        float[] cascadedHard = pipeline.captureLinearHdrRgbaFloat();
                        pipeline.features().submit(cascadePcss);
                        pipeline.execute(device);
                        assertEquals(RenderFeatureStatus.APPLIED, pipeline.features().status());
                        float[] cascadedPcss = pipeline.captureLinearHdrRgbaFloat();

                        // The four-cascade atlas tile size is 512, so the largest
                        // cascade keeps a texel footprint below the single-map
                        // one; the same world-space sanity band is conservative.
                        assertPenumbraAndNoBleed("cascaded atlas", cascadedHard, cascadedPcss,
                                inverse, light, texelWorld, FILTER_RADIUS_TEXELS, 2.0f);
                        assertOnlyAttenuatesOrRelights("cascaded atlas", cascadedHard,
                                cascadedPcss, inverse);

                        GlDebug.checkError("PCSS acceptance");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    material.close();
                }
            }
        }
    }

    private static LocalShadowPipelineSettings hardFilterSettings() {
        LocalShadowSettings defaults = LocalShadowSettings.defaults();
        return new LocalShadowPipelineSettings(defaults, defaults, 1, 1,
                ShadowSelectionMode.SCENE_ORDER, ShadowFilterMode.HARD, 0.0f, 1.15f, false);
    }

    private static ShadowSettings shadowSettings(PcssSettings pcss) {
        return new ShadowSettings(SHADOW_RESOLUTION, SHADOW_SCENE_RADIUS, 0.1f, 80.0f, 0.003f,
                ContactShadowSettings.disabled(), CloudShadowSettings.disabled(), pcss);
    }

    private static Matrix4f inverseViewProjection(Camera camera) {
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT, new Matrix4f());
        return new Matrix4f(projection).mul(view).invert();
    }

    private static void assertPenumbraAndNoBleed(String label, float[] hard, float[] pcss,
                                                  Matrix4f inverse, Vector3f light,
                                                  float texelWorld, int filterRadiusTexels,
                                                  float allowedBandWorld) {
        float[] columnHard = new float[HEIGHT];
        float[] columnPcss = new float[HEIGHT];
        boolean[] columnFloor = new boolean[HEIGHT];
        float[] columnWorldZ = new float[HEIGHT];
        int boundaryRow = -1;
        float lit = 0.0f;
        for (int y = 0; y < HEIGHT; y++) {
            Vector3f world = floorAt(inverse, WIDTH / 2, y);
            if (world == null || Math.abs(world.x) > 0.6f) continue;
            columnFloor[y] = true;
            columnWorldZ[y] = world.z;
            columnHard[y] = luminance(hard, y * WIDTH + WIDTH / 2);
            columnPcss[y] = luminance(pcss, y * WIDTH + WIDTH / 2);
            lit = Math.max(lit, columnHard[y]);
        }
        assertTrue(lit > 1.0e-4f, label + ": the fixture must contain a directly lit floor pixel");
        float edgeZ = shadowEdgeWorldZ(light);
        int hardShadowRows = 0;
        float bestEdgeDistance = Float.MAX_VALUE;
        for (int y = 0; y < HEIGHT; y++) {
            if (!columnFloor[y]) continue;
            if (columnHard[y] < lit * 0.02f) hardShadowRows++;
            float distance = Math.abs(columnWorldZ[y] - edgeZ);
            if (distance < bestEdgeDistance) {
                bestEdgeDistance = distance;
                boundaryRow = y;
            }
        }
        assertTrue(hardShadowRows > 0, label + ": the wall must cast a hard shadow onto the floor");
        assertTrue(boundaryRow >= 0, label + ": the analytic shadow boundary must be visible");
        assertTrue(bestEdgeDistance < 1.0f,
                label + ": the scanline must cross the analytic shadow boundary");

        float worldPerPixel = pixelsPerWorldZ(columnWorldZ, columnFloor, boundaryRow);
        assertTrue(worldPerPixel > 0.0f, label + ": the floor scan must have a usable scale");
        float maxPenumbraWorld = 2.0f * filterRadiusTexels * texelWorld
                / Math.max(Math.abs(light.y), 0.2f);
        int maxPenumbraPixels = (int) Math.ceil(maxPenumbraWorld / worldPerPixel) + 3;

        int pcssChanged = 0;
        int darker = 0;
        int brighter = 0;
        float changeThreshold = lit * 0.02f;
        for (int y = 0; y < HEIGHT; y++) {
            if (!columnFloor[y]) continue;
            float delta = columnPcss[y] - columnHard[y];
            if (Math.abs(delta) > changeThreshold) pcssChanged++;
            if (delta < -changeThreshold) darker++;
            if (delta > changeThreshold) brighter++;
        }
        assertTrue(pcssChanged >= 3,
                label + ": PCSS must produce a non-binary penumbra (" + pcssChanged + " px)");
        assertTrue(pcssChanged <= maxPenumbraPixels,
                label + ": penumbra " + pcssChanged + " px exceeds the configured bound "
                        + maxPenumbraPixels + " px");
        assertTrue(darker > 0 && brighter > 0,
                label + ": PCSS must darken the lit side and relight the shadow side ("
                        + darker + "/" + brighter + ")");

        int outside = 0;
        int floorSamples = 0;
        float worstDistance = 0.0f;
        for (int y = 0; y < HEIGHT; y += 2) {
            for (int x = 1; x < WIDTH; x += 2) {
                Vector3f world = floorAt(inverse, x, y);
                if (world == null || Math.abs(world.x) > 1.5f
                        || Math.abs(world.z) > FLOOR_HALF_SIZE) continue;
                floorSamples++;
                float delta = Math.abs(luminance(pcss, y * WIDTH + x)
                        - luminance(hard, y * WIDTH + x));
                if (delta <= 1.0e-4f) continue;
                float distance = Math.abs(world.z - edgeZ);
                if (distance <= allowedBandWorld) continue;
                outside++;
                worstDistance = Math.max(worstDistance, distance);
            }
        }
        assertTrue(floorSamples > 0, label + ": the fixture must sample the floor");
        assertEquals(0, outside,
                label + ": PCSS changed " + outside + " floor pixels outside the analytic "
                        + "shadow/penumbra band (worst " + worstDistance + " world units), "
                        + "which is the atlas-tile bleeding signature");
    }

    /**
     * PCSS may only redistribute the direct-light visibility of the known
     * shadow: no pixel may become brighter than the unshadowed floor or darker
     * than the fully shadowed floor beyond the hard baseline.
     */
    private static void assertOnlyAttenuatesOrRelights(String label, float[] hard,
                                                       float[] pcss, Matrix4f inverse) {
        float lit = 0.0f;
        float shadow = Float.MAX_VALUE;
        for (int y = 0; y < HEIGHT; y += 2) {
            for (int x = 1; x < WIDTH; x += 2) {
                if (floorAt(inverse, x, y) == null) continue;
                float value = luminance(hard, y * WIDTH + x);
                lit = Math.max(lit, value);
                shadow = Math.min(shadow, value);
            }
        }
        assertTrue(lit > shadow, label + ": the baseline must contain both lit and shadowed floor");
        for (int y = 0; y < HEIGHT; y += 2) {
            for (int x = 1; x < WIDTH; x += 2) {
                if (floorAt(inverse, x, y) == null) continue;
                float value = luminance(pcss, y * WIDTH + x);
                assertTrue(value <= lit + 1.0e-4f,
                        label + ": PCSS must not brighten a floor pixel above the unshadowed floor");
                assertTrue(value >= shadow - 1.0e-4f,
                        label + ": PCSS must not darken a floor pixel below the hard shadow");
            }
        }
    }

    private static float pixelsPerWorldZ(float[] worldZ, boolean[] valid, int row) {
        int above = row + 1;
        while (above < HEIGHT && !valid[above]) above++;
        int below = row - 1;
        while (below >= 0 && !valid[below]) below--;
        if (above >= HEIGHT || below < 0) return 0.0f;
        return Math.abs(worldZ[above] - worldZ[below]) / (above - below);
    }

    /** World Z of the wall's top-edge shadow line on the floor. */
    private static float shadowEdgeWorldZ(Vector3f light) {
        return WALL_Z + WALL_HEIGHT * light.z / Math.abs(light.y);
    }

    /**
     * World-space floor point seen by a pixel, or {@code null} when the pixel
     * does not show the floor (background, wall, or a ray that does not hit it).
     */
    private static Vector3f floorAt(Matrix4f inverseViewProjection, int x, int y) {
        float ndcX = (x + 0.5f) / WIDTH * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / HEIGHT * 2.0f - 1.0f;
        Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f);
        Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f);
        inverseViewProjection.transform(near);
        inverseViewProjection.transform(far);
        if (Math.abs(near.w) < 1.0e-6f || Math.abs(far.w) < 1.0e-6f) return null;
        near.div(near.w);
        far.div(far.w);
        Vector3f origin = new Vector3f(near.x, near.y, near.z);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z);
        if (Math.abs(direction.y) < 1.0e-6f) return null;
        float t = -origin.y / direction.y;
        if (t <= 0.0f) return null;
        Vector3f world = new Vector3f(origin).fma(t, direction);
        if (Math.abs(world.x) > FLOOR_HALF_SIZE || Math.abs(world.z) > FLOOR_HALF_SIZE) {
            return null;
        }
        // The wall is opaque; a pixel whose camera ray hits it does not show floor.
        if (Math.abs(direction.z) > 1.0e-6f) {
            float wallT = (WALL_Z - origin.z) / direction.z;
            if (wallT > 0.0f && wallT < t) {
                float hitX = origin.x + wallT * direction.x;
                float hitY = origin.y + wallT * direction.y;
                if (Math.abs(hitX) <= WALL_HALF_WIDTH && hitY >= 0.0f && hitY <= WALL_HEIGHT) {
                    return null;
                }
            }
        }
        return world;
    }

    private static float luminance(float[] rgba, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * rgba[offset] + 0.7152f * rgba[offset + 1]
                + 0.0722f * rgba[offset + 2];
    }
}
