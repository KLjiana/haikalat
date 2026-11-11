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
import com.kaleblangley.haikalat.core.material.ParallaxOcclusionSettings;
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
import com.kaleblangley.haikalat.subsystems.render3d.pbr.ParallaxReferenceMath;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrTextureBinding;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Real-consumer acceptance for the bounded tangent-space parallax occlusion
 * mapping of the PBR material.
 *
 * <p>The fixture is one opaque textured quad rotated 45 degrees around Y, so
 * the camera-facing surface has a real tangent-space view slope and the
 * parallax march moves the sampled UV by several texels. The emissive role
 * carries a 64x64 nearest-filtered block palette and the height role carries a
 * 64x64 linear ramp; direct lighting, shadows and IBL are disabled, so the
 * captured linear HDR image is exactly the emissive sample at the material UV.
 * A second black quad sits one unit behind the front quad, which turns the
 * front coverage into a depth/occlusion A-B probe.</p>
 *
 * <p>Every variant shares the same pipeline, camera, geometry and material
 * template. {@link MaterialInstance} uniforms and the optional instance height
 * texture switch between: the disabled baseline, the active POM frame, the
 * zero-height-scale no-op, angular and distance faded frames, a full-height
 * out-of-range UV stress frame, and the depth A-B pair. The expected UVs come
 * from the independent double-precision
 * {@link ParallaxReferenceMath} oracle, which never calls production helpers.
 * The disabled frame additionally proves the block prediction over the plain
 * interpolated UV, and zero-height-scale/disabled plus both faded frames are
 * compared byte for byte.</p>
 *
 * <p>This test does not run in the default JVM suite; it requires
 * {@code -Dhaikalat.glSmoke=true} and a real OpenGL 4.6 context.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ParallaxGlTest {
    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;
    private static final float CAMERA_Z = 3.4f;
    private static final float QUAD_HALF_SCALE = 1.2f;
    private static final float BACK_QUAD_HALF_SCALE = 2.6f;
    private static final float BACK_QUAD_Z = -0.86f;
    private static final float ROTATION_Y = (float) (-Math.PI / 4.0);
    private static final Vector3f CAMERA_POSITION = new Vector3f(0.0f, 0.0f, CAMERA_Z);
    private static final Vector3f LIGHT_TRAVEL_DIRECTION = new Vector3f(-0.35f, -0.55f, -1.0f);
    private static final Vector3f LIGHT_COLOR = new Vector3f(1.0f, 0.97f, 0.92f);
    private static final float LIGHT_INTENSITY = 2.5f;
    private static final int MAP_SIZE = 64;
    private static final int BLOCK_SIZE = 4;
    private static final int BLOCKS = MAP_SIZE / BLOCK_SIZE;
    private static final float HEIGHT_SCALE = 0.1f;
    private static final int MIN_STEPS = 8;
    private static final int MAX_STEPS = 32;
    private static final float FADE_START = 60.0f;
    private static final float FADE_END = 85.0f;
    private static final float FADE_START_DISTANCE = 8.0f;
    private static final float MAX_DISTANCE = 20.0f;
    /** Oracle settings matching the template material (and the zero-scale/fade cases). */
    private static final OracleSettings ACTIVE_ORACLE = new OracleSettings(HEIGHT_SCALE,
            MIN_STEPS, MAX_STEPS, FADE_START, FADE_END, FADE_START_DISTANCE, MAX_DISTANCE);
    /** Oracle settings matching the instance-overridden out-of-range stress frame. */
    private static final OracleSettings STRESS_ORACLE = new OracleSettings(1.0, 1, 8,
            0.0, 89.9, 0.1, 1000.0);
    /** Every generated block is distinguishable within this linear tolerance. */
    private static final float COLOR_TOLERANCE = 0.01f;
    private static final int MAXIMUM_BOUNDED_LUMINANCE = 1000;

    @Test
    void parallaxMovesTheMaterialUvAsTheOracleAndKeepsEveryOtherContract() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Parallax GL Test")
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
                         BuiltinMeshData.texturedQuad("parallax-quad")).mesh());
                 Texture2D heightMap = heightMap();
                 Texture2D emissiveMap = emissiveMap();
                 Texture2D whiteHeight = whiteHeightMap();
                 Sampler linearHeightSampler = sampler(GL_LINEAR);
                 Sampler nearestColorSampler = sampler(GL_NEAREST);
                 PbrEnvironment environment = PbrEnvironmentLoader.load(device, getClass(),
                         "/environments/pbr/studio-small.hdr",
                         PbrEnvironmentSettings.testQuality())) {
                Material front = PbrMaterials.createWithParallax(shader,
                        emissiveProperties(), Map.of(
                                PbrTextureRole.HEIGHT,
                                new PbrTextureBinding(heightMap, linearHeightSampler),
                                PbrTextureRole.EMISSIVE,
                                new PbrTextureBinding(emissiveMap, nearestColorSampler)),
                        fallbacks, activeSettings());
                Material back = PbrMaterials.create(shader, blackProperties(), Map.of(),
                        fallbacks);
                try {
                    MaterialInstance instance = front.createInstance();
                    Matrix4f model = frontModel();
                    Matrix4f inverseModel = new Matrix4f(model).invert();
                    Camera camera = new Camera(CAMERA_POSITION,
                            new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, 0.0f);
                    Scene scene = new Scene(camera);
                    scene.add(MeshRenderer.of(quad, instance,
                            Transform.at(0.0f, 0.0f, 0.0f)
                                    .rotationRadians(0.0f, ROTATION_Y, 0.0f)
                                    .scale(QUAD_HALF_SCALE * 2.0f, QUAD_HALF_SCALE * 2.0f, 1.0f)));
                    scene.add(MeshRenderer.of(quad, back,
                            Transform.at(0.0f, 0.0f, BACK_QUAD_Z)
                                    .scale(BACK_QUAD_HALF_SCALE * 2.0f,
                                            BACK_QUAD_HALF_SCALE * 2.0f, 1.0f)));
                    scene.addLight(SceneLight.directional(LIGHT_TRAVEL_DIRECTION,
                            LIGHT_COLOR, LIGHT_INTENSITY));
                    RenderSettings renderSettings = RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.NONE)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build();
                    RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                            renderSettings, environment);
                    try {
                        pipeline.features().submit(noLightingSettings(
                                pipeline.features().effective()));
                        pipeline.build();
                        Matrix4f inverseViewProjection = inverseViewProjection(camera);
                        float[][][] palette = emissivePalette();

                        // Disabled baseline: the oracle must predict the plain
                        // interpolated UV before the POM comparison means anything.
                        instance.setInt("uParallaxEnabled", 0);
                        float[] disabled = render(pipeline, device);
                        assertEmissiveBlockReference("disabled baseline", disabled,
                                inverseViewProjection, model, inverseModel, palette,
                                false, ACTIVE_ORACLE,
                                ParallaxReferenceMath.constantZeroHeight());
                        assertFrontCoverage("disabled baseline", disabled, palette);

                        // Active frame: every sampled UV must follow the oracle,
                        // and the fixture must actually move the palette blocks.
                        instance.clearOverrides();
                        float[] active = render(pipeline, device);
                        assertEmissiveBlockReference("active parallax", active,
                                inverseViewProjection, model, inverseModel, palette,
                                true, ACTIVE_ORACLE, ParallaxGlTest::heightAt);
                        assertDiffers("active parallax vs disabled", active, disabled,
                                0.01f, 500);
                        assertOracleBlocksMoved(disabled, active, inverseViewProjection, model,
                                inverseModel, palette, "active parallax");
                        // Exact restore after dropping all instance overrides.
                        assertArrayEquals(active, render(pipeline, device), 0.0f,
                                "clearing the parallax overrides must restore the template state");

                        // Zero height scale is the exact original UV path.
                        instance.clearOverrides();
                        instance.setInt("uParallaxEnabled", 1)
                                .setFloat("uParallaxHeightScale", 0.0f);
                        assertArrayEquals(disabled, render(pipeline, device), 0.0f,
                                "a zero height scale must keep the exact original UV image");

                        // Angular and distance windows that exclude the fixture
                        // must also restore the exact original image.
                        instance.clearOverrides();
                        instance.setFloat("uParallaxFadeStart", 10.0f)
                                .setFloat("uParallaxFadeEnd", 20.0f);
                        float[] angleFaded = render(pipeline, device);
                        assertArrayEquals(disabled, angleFaded, 0.0f,
                                "the fixture view angles are beyond fadeEnd, so the angle fade "
                                        + "must restore the original UV exactly");
                        instance.clearOverrides();
                        instance.setFloat("uParallaxFadeStartDistance", 0.1f)
                                .setFloat("uParallaxMaxDistance", 0.5f);
                        float[] distanceFaded = render(pipeline, device);
                        assertArrayEquals(disabled, distanceFaded, 0.0f,
                                "the fixture distance is beyond maxDistance, so the distance "
                                        + "fade must restore the original UV exactly");

                        // Out-of-range stress: full height, one layer, a white
                        // height map and wide windows push the UV outside [0,1].
                        instance.clearOverrides();
                        instance.setFloat("uParallaxHeightScale", 1.0f)
                                .setInt("uParallaxMinSteps", 1)
                                .setInt("uParallaxMaxSteps", 8)
                                .setFloat("uParallaxFadeStart", 0.0f)
                                .setFloat("uParallaxFadeEnd", 89.9f)
                                .setFloat("uParallaxFadeStartDistance", 0.1f)
                                .setFloat("uParallaxMaxDistance", 1000.0f);
                        instance.texture(PbrMaterials.HEIGHT_UNIT, "uHeightMap",
                                whiteHeight, linearHeightSampler);
                        float[] stress = render(pipeline, device);
                        assertFiniteBounded("out-of-range UV stress", stress);
                        assertEmissiveBlockReference("out-of-range UV stress", stress,
                                inverseViewProjection, model, inverseModel, palette,
                                true, STRESS_ORACLE,
                                ParallaxReferenceMath.constantOneHeight());
                        assertTrue(differentPixels(stress, disabled, 0.01f) > 500,
                                "the full-height stress frame must move visible emissive blocks");

                        // Depth/silhouette A-B: the front quad covers exactly the
                        // same pixels with POM on and off. A fragment-depth write
                        // would let the black back quad win depth and change the mask.
                        instance.clearOverrides();
                        float[] activeFrame = render(pipeline, device);
                        instance.setInt("uParallaxEnabled", 0);
                        float[] flatFrame = render(pipeline, device);
                        assertSameFrontMask("depth A-B", activeFrame, flatFrame, palette);
                        GlDebug.checkError("parallax acceptance");
                    } finally {
                        pipeline.close();
                    }
                } finally {
                    front.close();
                    back.close();
                }
            }
        }
    }

    private static ParallaxOcclusionSettings activeSettings() {
        return ParallaxOcclusionSettings.builder()
                .heightScale(HEIGHT_SCALE)
                .steps(MIN_STEPS, MAX_STEPS)
                .fadeAngles(FADE_START, FADE_END)
                .distanceRange(FADE_START_DISTANCE, MAX_DISTANCE)
                .heightChannel(ParallaxOcclusionSettings.HeightChannel.RED)
                .flipHeight(false)
                .build();
    }

    private static PbrMaterialProperties emissiveProperties() {
        return new PbrMaterialProperties(new Vector4f(1.0f), 0.0f, 0.5f, 0.0f, 1.0f,
                new Vector3f(1.0f, 1.0f, 1.0f), Map.of());
    }

    private static PbrMaterialProperties blackProperties() {
        return new PbrMaterialProperties(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f), 0.0f, 0.5f,
                0.0f, 1.0f, new Vector3f(), Map.of());
    }

    private static RenderFeatureSettings noLightingSettings(RenderFeatureSettings base) {
        return base.toBuilder()
                .imageBasedLighting(false)
                .directLighting(false)
                .directionalShadows(false)
                .pointShadows(false)
                .spotShadows(false)
                .directionalCascades(DirectionalCascadeSettings.disabled())
                .localShadows(LocalShadowPipelineSettings.legacyDefaults())
                .build();
    }

    private static Matrix4f frontModel() {
        return new Transform()
                .rotationRadians(0.0f, ROTATION_Y, 0.0f)
                .scale(QUAD_HALF_SCALE * 2.0f, QUAD_HALF_SCALE * 2.0f, 1.0f)
                .matrix();
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

    /**
     * Predicted emissive block from the independent oracle at every pixel whose
     * camera ray crosses the known front-quad plane.
     *
     * @param active  whether the oracle applies the parallax march
     * @param oracle  the marched settings matching the material variant
     * @param sampler the height function; production uses {@link ParallaxGlTest#heightAt}
     *                and the stress case uses a constant one
     */
    private static void assertEmissiveBlockReference(String label, float[] image,
                                                     Matrix4f inverseViewProjection,
                                                     Matrix4f model, Matrix4f inverseModel,
                                                     float[][][] palette, boolean active,
                                                     OracleSettings oracle,
                                                     ParallaxReferenceMath.HeightSampler sampler) {
        Vector3f normal = rotatedVector(0.0f, 0.0f, 1.0f);
        Vector3f tangent = rotatedVector(1.0f, 0.0f, 0.0f);
        Vector3f bitangent = new Vector3f(0.0f, 1.0f, 0.0f);
        int compared = 0;
        int matched = 0;
        float worst = 0.0f;
        String worstPixel = "";
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                SurfacePoint point = frontSurfacePoint(inverseViewProjection, model,
                        inverseModel, x, y);
                if (point == null) continue;
                double u = point.u();
                double v = point.v();
                if (active) {
                    Vector3f view = new Vector3f(CAMERA_POSITION).sub(point.world()).normalize();
                    double viewT = view.dot(normal);
                    double weight = ParallaxReferenceMath.weight(viewT,
                            CAMERA_POSITION.distance(point.world()),
                            oracle.fadeStart(), oracle.fadeEnd(),
                            oracle.fadeStartDistance(), oracle.maxDistance());
                    ParallaxReferenceMath.Result result = ParallaxReferenceMath.march(u, v,
                            view.dot(tangent), view.dot(bitangent), viewT, oracle.heightScale(),
                            oracle.minSteps(), oracle.maxSteps(), weight, 1.0, false, sampler);
                    u = result.u();
                    v = result.v();
                }
                float[] expected = blockColor(palette, u, v);
                int offset = (y * WIDTH + x) * 4;
                float delta = Math.max(Math.abs(image[offset] - expected[0]),
                        Math.max(Math.abs(image[offset + 1] - expected[1]),
                                Math.abs(image[offset + 2] - expected[2])));
                if (delta <= COLOR_TOLERANCE) matched++;
                if (delta > worst) {
                    worst = delta;
                    worstPixel = "(" + x + "," + y + ") uv=(" + u + "," + v + ")"
                            + " expected=(" + expected[0] + "," + expected[1] + ","
                            + expected[2] + ") actual=(" + image[offset] + ","
                            + image[offset + 1] + "," + image[offset + 2] + ")";
                }
                compared++;
            }
        }
        if (compared == 0) {
            throw new AssertionError(label + ": the fixture frame does not show the front quad");
        }
        float ratio = matched / (float) compared;
        assertTrue(ratio >= 0.95f, label + ": only " + matched + "/" + compared
                + " covered pixels matched the independent oracle ("
                + String.format(java.util.Locale.ROOT, "%.4f", ratio)
                + "), worst " + worst + " at " + worstPixel);
    }

    /**
     * The parallax fixture must actually change which palette block is sampled;
     * otherwise a shader that silently returns vTexCoord would pass by producing
     * the disabled image.
     */
    private static void assertOracleBlocksMoved(float[] disabled, float[] active,
                                                Matrix4f inverseViewProjection,
                                                Matrix4f model, Matrix4f inverseModel,
                                                float[][][] palette, String label) {
        int covered = 0;
        int moved = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                SurfacePoint point = frontSurfacePoint(inverseViewProjection, model,
                        inverseModel, x, y);
                if (point == null) continue;
                covered++;
                float[] flat = blockColor(palette, point.u(), point.v());
                int offset = (y * WIDTH + x) * 4;
                if (Math.abs(flat[0] - active[offset]) > COLOR_TOLERANCE
                        || Math.abs(flat[1] - active[offset + 1]) > COLOR_TOLERANCE
                        || Math.abs(flat[2] - active[offset + 2]) > COLOR_TOLERANCE) {
                    moved++;
                }
            }
        }
        assertTrue(covered > WIDTH * HEIGHT / 5,
                label + ": the quad must cover a large part of the frame, got " + covered);
        assertTrue(moved > covered / 3, label + ": only " + moved + "/" + covered
                + " covered pixels sampled a different palette block; the fixture is not sensitive");
        assertTrue(differentPixels(active, disabled, 0.01f) > 500,
                label + ": the active and disabled frames must visibly differ");
    }

    private static void assertFrontCoverage(String label, float[] image, float[][][] palette) {
        int front = 0;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            if (isFrontColor(image, pixel, palette)) front++;
        }
        assertTrue(front > WIDTH * HEIGHT / 5,
                label + ": the emissive quad must cover a large part of the frame, got " + front);
    }

    private static void assertSameFrontMask(String label, float[] first, float[] second,
                                            float[][][] palette) {
        int firstCount = 0;
        for (int pixel = 0; pixel < first.length / 4; pixel++) {
            boolean firstFront = isFrontColor(first, pixel, palette);
            boolean secondFront = isFrontColor(second, pixel, palette);
            assertEquals(firstFront, secondFront, label + ": front coverage changed at pixel "
                    + pixel + " (" + (pixel % WIDTH) + "," + (pixel / WIDTH)
                    + "); POM must not change depth or the silhouette");
            if (firstFront) firstCount++;
        }
        assertTrue(firstCount > WIDTH * HEIGHT / 5,
                label + ": the front quad must cover a large part of the frame");
    }

    /** True when the pixel carries one of the generated emissive block colours. */
    private static boolean isFrontColor(float[] image, int pixel, float[][][] palette) {
        int offset = pixel * 4;
        for (int blockY = 0; blockY < BLOCKS; blockY++) {
            for (int blockX = 0; blockX < BLOCKS; blockX++) {
                float[] color = palette[blockY][blockX];
                if (Math.abs(image[offset] - color[0]) <= COLOR_TOLERANCE
                        && Math.abs(image[offset + 1] - color[1]) <= COLOR_TOLERANCE
                        && Math.abs(image[offset + 2] - color[2]) <= COLOR_TOLERANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertFiniteBounded(String label, float[] image) {
        float maximum = 0.0f;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            for (int channel = 0; channel < 3; channel++) {
                float value = image[pixel * 4 + channel];
                assertTrue(Float.isFinite(value),
                        label + ": pixel " + pixel + " channel " + channel + " is not finite");
                assertTrue(value >= 0.0f && value <= 1.0f + 1.0e-4f,
                        label + ": emissive output " + value + " left [0,1] at pixel " + pixel);
            }
            maximum = Math.max(maximum, luminance(image, pixel));
        }
        assertTrue(maximum <= MAXIMUM_BOUNDED_LUMINANCE,
                label + ": luminance " + maximum + " exceeded the bounded fixture limit");
    }

    private static void assertDiffers(String label, float[] image, float[] other,
                                      float threshold, int minimumPixels) {
        int changed = differentPixels(image, other, threshold);
        assertTrue(changed > minimumPixels, label + ": only " + changed
                + " pixels changed; the parallax offset must be visible");
    }

    private static int differentPixels(float[] image, float[] other, float threshold) {
        int changed = 0;
        for (int pixel = 0; pixel < image.length / 4; pixel++) {
            int offset = pixel * 4;
            float delta = Math.abs(image[offset] - other[offset])
                    + Math.abs(image[offset + 1] - other[offset + 1])
                    + Math.abs(image[offset + 2] - other[offset + 2]);
            if (delta > threshold) changed++;
        }
        return changed;
    }

    private static float luminance(float[] image, int pixel) {
        int offset = pixel * 4;
        return 0.2126f * image[offset] + 0.7152f * image[offset + 1]
                + 0.0722f * image[offset + 2];
    }

    /** World point, local coordinates and UV where a pixel ray crosses the front quad. */
    private record SurfacePoint(Vector3f world, double u, double v) {
    }

    /** Oracle inputs that mirror one material variant's POM uniforms. */
    private record OracleSettings(double heightScale, int minSteps, int maxSteps,
                                  double fadeStart, double fadeEnd,
                                  double fadeStartDistance, double maxDistance) {
    }

    private static SurfacePoint frontSurfacePoint(Matrix4f inverseViewProjection,
                                                  Matrix4f model, Matrix4f inverseModel,
                                                  int x, int y) {
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
        Vector3f localOrigin = inverseModel.transformPosition(origin);
        Vector3f localDirection = inverseModel.transformDirection(direction);
        if (Math.abs(localDirection.z) < 1.0e-8f) return null;
        double t = -localOrigin.z / localDirection.z;
        if (t <= 0.0) return null;
        Vector3f local = new Vector3f(localOrigin).fma((float) t, localDirection);
        float margin = 0.002f;
        if (Math.abs(local.x) > 0.5f - margin || Math.abs(local.y) > 0.5f - margin) {
            return null;
        }
        Vector3f world = model.transformPosition(local);
        if (world.distance(CAMERA_POSITION) < 0.01f) return null;
        return new SurfacePoint(world, local.x + 0.5, local.y + 0.5);
    }

    private static Vector3f rotatedVector(float x, float y, float z) {
        return new Matrix4f().rotationY(ROTATION_Y)
                .transformDirection(new Vector3f(x, y, z)).normalize();
    }

    private static Matrix4f inverseViewProjection(Camera camera) {
        Matrix4f view = camera.getViewMatrix(new Matrix4f());
        Matrix4f projection = CameraProjection.stable(camera, WIDTH, HEIGHT, new Matrix4f());
        return new Matrix4f(projection).mul(view).invert();
    }

    private static float[] blockColor(float[][][] palette, double u, double v) {
        int blockX = blockIndex(u);
        int blockY = blockIndex(v);
        return palette[blockY][blockX];
    }

    private static int blockIndex(double uv) {
        double clamped = Math.max(0.0, Math.min(1.0, uv));
        return Math.min(BLOCKS - 1, (int) Math.floor(clamped * BLOCKS));
    }

    /**
     * Exact CPU counterpart of the GL {@code GL_LINEAR + GL_CLAMP_TO_EDGE}
     * height sample of the generated 8-bit ramp. The texel values are the same
     * quantised bytes uploaded to the texture.
     */
    private static double heightAt(double u, double v) {
        double x = u * MAP_SIZE - 0.5;
        double y = v * MAP_SIZE - 0.5;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        double top = heightTexel(x0, y0) + (heightTexel(x0 + 1, y0) - heightTexel(x0, y0)) * fx;
        double bottom = heightTexel(x0, y0 + 1)
                + (heightTexel(x0 + 1, y0 + 1) - heightTexel(x0, y0 + 1)) * fx;
        return top + (bottom - top) * fy;
    }

    private static double heightTexel(int x, int y) {
        int clampedX = Math.max(0, Math.min(MAP_SIZE - 1, x));
        int clampedY = Math.max(0, Math.min(MAP_SIZE - 1, y));
        return heightByte(clampedX) / 255.0;
    }

    private static int heightByte(int texelX) {
        return (int) Math.round(255.0 * (0.25 + 0.5 * ((texelX + 0.5) / MAP_SIZE)));
    }

    /** Linear-light colour of one generated emissive block. */
    private static float[][][] emissivePalette() {
        float[][][] palette = new float[BLOCKS][BLOCKS][3];
        for (int blockY = 0; blockY < BLOCKS; blockY++) {
            for (int blockX = 0; blockX < BLOCKS; blockX++) {
                palette[blockY][blockX][0] = (float) srgbToLinear(paletteRed(blockX, blockY) / 255.0);
                palette[blockY][blockX][1] = (float) srgbToLinear(paletteGreen(blockX, blockY) / 255.0);
                palette[blockY][blockX][2] = (float) srgbToLinear(paletteBlue(blockX, blockY) / 255.0);
            }
        }
        return palette;
    }

    private static int paletteRed(int blockX, int blockY) {
        return 40 + (blockX * 37 + blockY * 11) % 200;
    }

    private static int paletteGreen(int blockX, int blockY) {
        return 40 + (blockX * 17 + blockY * 53 + 29) % 200;
    }

    private static int paletteBlue(int blockX, int blockY) {
        return 40 + (blockX * 97 + blockY * 7 + 61) % 200;
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static Texture2D heightMap() {
        ByteBuffer data = ByteBuffer.allocateDirect(MAP_SIZE * MAP_SIZE * 4);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                data.put((byte) heightByte(x)).put((byte) 0).put((byte) 0).put((byte) 255);
            }
        }
        data.flip();
        return Texture2D.fromRgba8(MAP_SIZE, MAP_SIZE, data, TextureColorSpace.LINEAR);
    }

    private static Texture2D emissiveMap() {
        ByteBuffer data = ByteBuffer.allocateDirect(MAP_SIZE * MAP_SIZE * 4);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                int blockX = x / BLOCK_SIZE;
                int blockY = y / BLOCK_SIZE;
                data.put((byte) paletteRed(blockX, blockY))
                        .put((byte) paletteGreen(blockX, blockY))
                        .put((byte) paletteBlue(blockX, blockY))
                        .put((byte) 255);
            }
        }
        data.flip();
        return Texture2D.fromRgba8(MAP_SIZE, MAP_SIZE, data, TextureColorSpace.SRGB);
    }

    private static Texture2D whiteHeightMap() {
        ByteBuffer data = ByteBuffer.allocateDirect(4)
                .put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255).flip();
        return Texture2D.fromRgba8(1, 1, data, TextureColorSpace.LINEAR);
    }

    private static Sampler sampler(int filter) {
        return Sampler.create(new Sampler.Descriptor(filter, filter,
                GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
    }
}
