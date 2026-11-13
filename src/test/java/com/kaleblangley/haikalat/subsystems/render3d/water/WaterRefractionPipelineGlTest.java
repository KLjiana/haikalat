package com.kaleblangley.haikalat.subsystems.render3d.water;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Pipeline-level real-GL acceptance for #11 water refraction.
 *
 * <p>Builds the production {@link RenderPipeline} with the finite water surface
 * and a world-space gradient ground, then proves the main-scene refraction path:
 * an active request declares the companion copy pass, changes water pixels
 * against the no-refraction baseline, falls back when a foreground occluder is
 * on the shifted lookup, and disabling refraction restores the pre-#11 output
 * byte for byte.  The graph declarations are also checked for the absence of a
 * water/copy depth attachment.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class WaterRefractionPipelineGlTest {
    private static final int REQUESTED_SIZE = 64;
    private static final float GROUND_Y = -4.0f;
    private static final float WATER_LEVEL = 0.0f;
    private static final float THICKNESS = WATER_LEVEL - GROUND_Y;
    private static final Vector3f WATER_COLOR = new Vector3f(0.02f, 0.12f, 0.16f);

    @Test
    void refractionChangesWaterPixelsAndDisablingRestoresTheBaseline() {
        WaterSurfaceSettings water = water();
        WaterRefractionSettings refraction = refraction();
        try (Fixture fixture = new Fixture(water, refraction)) {
            fixture.frame(0.0f);
            float[] baseline = fixture.capture();
            assertEquals(fixture.width * fixture.height * 4, baseline.length);

            fixture.pipeline.applyWaterRefraction(refraction);
            assertTrue(fixture.pipeline.graph().hasPass(
                    WaterSurfacePass.REFRACTION_BACKGROUND_PASS),
                    "active refraction must declare the companion copy pass");
            assertTrue(fixture.pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME));
            var copyPass = pass(fixture, WaterSurfacePass.REFRACTION_BACKGROUND_PASS);
            var waterPass = pass(fixture, WaterSurfacePass.PASS_NAME);
            assertNull(copyPass.depthAttachment(),
                    "the refraction copy must not attach or write depth");
            assertNull(waterPass.depthAttachment(),
                    "the water pass samples the shared depth and must not attach its own");
            assertTrue(waterPass.directDependencies().contains(
                            WaterSurfacePass.REFRACTION_BACKGROUND_PASS),
                    "the water pass must consume the copy pass: "
                            + waterPass.directDependencies());
            assertTrue(copyPass.directDependencies().contains(
                            com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets
                                    .GEOMETRY_PASS),
                    "the copy pass must run after the scene colour producer: "
                            + copyPass.directDependencies());

            fixture.frame(0.0f);
            float[] refracted = fixture.capture();
            int[] best = bestChangedPixel(baseline, refracted, fixture.width, fixture.height);
            assertNotNull(best, "the gradient fixture must expose a changed water pixel");
            assertTrue(changed(baseline, refracted, index(fixture.width, best[0], best[1]))
                            > 5.0e-3f,
                    "refraction must change a water pixel against the no-refraction baseline");
            int changedPixels = countChangedInsideWater(fixture, baseline, refracted);
            assertTrue(changedPixels > 16,
                    "refraction must change a visible water region, changed=" + changedPixels);

            fixture.pipeline.applyWaterRefraction(WaterRefractionSettings.disabled());
            assertTrue(!fixture.pipeline.graph().hasPass(
                    WaterSurfacePass.REFRACTION_BACKGROUND_PASS),
                    "disabling refraction must remove the companion copy pass");
            fixture.frame(0.0f);
            float[] restored = fixture.capture();
            assertArrayEqualsExact(baseline, restored,
                    "disabled refraction must restore the pre-#11 main view byte for byte");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void foregroundOnTheShiftedLookupFallsBackToTheUnrefractedBackground() {
        WaterSurfaceSettings water = water();
        WaterRefractionSettings refraction = refraction();
        try (Fixture fixture = new Fixture(water, refraction)) {
            fixture.frame(0.0f);
            float[] baseline = fixture.capture();
            fixture.pipeline.applyWaterRefraction(refraction);
            fixture.frame(0.0f);
            float[] refracted = fixture.capture();
            int[] best = bestChangedPixel(baseline, refracted, fixture.width, fixture.height);
            assertNotNull(best);
            int pixel = index(fixture.width, best[0], best[1]);
            float refractedChange = changed(baseline, refracted, pixel);
            assertTrue(refractedChange > 5.0e-3f,
                    "the foreground fixture needs a visible refracted change first");

            Vector3f occluder = shiftedRayPoint(fixture, water, refraction, best[0], best[1],
                    WATER_LEVEL + 2.0f);
            fixture.addOccluder(occluder);
            fixture.frame(0.0f);
            float[] refractedWithForeground = fixture.capture();
            assertTrue(changed(baseline, refractedWithForeground, pixel) < 2.0e-3f,
                    "a foreground surface on the shifted lookup must reject refraction and "
                            + "keep the unrefracted background, change="
                            + changed(baseline, refractedWithForeground, pixel));
            assertTrue(changed(refracted, refractedWithForeground, pixel) > 2.0e-3f,
                    "the foreground occluder must be observable against the refracted frame");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static Vector3f shiftedRayPoint(Fixture fixture, WaterSurfaceSettings water,
                                            WaterRefractionSettings refraction, int x, int y,
                                            float height) {
        Matrix4f projection = CameraProjection.stable(fixture.camera, fixture.width,
                fixture.height, new Matrix4f());
        Matrix4f view = fixture.camera.getViewMatrix(new Matrix4f());
        Vector3d world = WaterRefractionReferenceMath.waterWorld(projection, view,
                fixture.width, fixture.height, x, y, water.level());
        assertNotNull(world);
        Vector3f normal = water.waveNormal((float) world.x, (float) world.z, 0.0f,
                new Vector3f());
        double centreU = (x + 0.5) / fixture.width;
        double centreV = (y + 0.5) / fixture.height;
        WaterRefractionReferenceMath.Offset offset = WaterRefractionReferenceMath.offset(
                projection, view, new Vector3d(world), new Vector3d(normal),
                fixture.camera.position(), centreU, centreV,
                refraction.ior(), THICKNESS, refraction.thicknessScale(), refraction.strength(),
                refraction.maximumScreenOffset());
        assertTrue(offset.length() > 0.02,
                "the chosen fixture pixel must have a measurable shifted lookup");
        return rayPointAtHeight(projection, view, fixture.camera.position(),
                (float) offset.shiftedX(), (float) offset.shiftedY(), height);
    }

    private static Vector3f rayPointAtHeight(Matrix4f projection, Matrix4f view,
                                             Vector3f cameraPosition, float u, float v,
                                             float height) {
        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        Vector4f near = new Vector4f(u * 2.0f - 1.0f, v * 2.0f - 1.0f, -1.0f, 1.0f)
                .mul(inverse);
        near.div(near.w);
        Vector4f far = new Vector4f(u * 2.0f - 1.0f, v * 2.0f - 1.0f, 1.0f, 1.0f)
                .mul(inverse);
        far.div(far.w);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z)
                .normalize();
        float distance = (height - cameraPosition.y) / direction.y;
        assertTrue(Float.isFinite(distance) && distance > 0.0f,
                "the shifted ray must cross the foreground height");
        return new Vector3f(cameraPosition).fma(distance, direction);
    }

    private static int[] bestChangedPixel(float[] left, float[] right, int width, int height) {
        int bestX = -1;
        int bestY = -1;
        float best = 0.0f;
        for (int y = height / 4; y < height * 3 / 4; y++) {
            for (int x = width / 4; x < width * 3 / 4; x++) {
                float difference = changed(left, right, index(width, x, y));
                if (difference > best) {
                    best = difference;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return best > 0.0f ? new int[]{bestX, bestY} : null;
    }

    private static int countChangedInsideWater(Fixture fixture, float[] left, float[] right) {
        float[] bounds = projectedBounds(fixture.camera, fixture.water, fixture.width,
                fixture.height);
        int changed = 0;
        for (int y = Math.max(0, (int) bounds[1]); y <= Math.min(fixture.height - 1,
                (int) bounds[3]); y++) {
            for (int x = Math.max(0, (int) bounds[0]); x <= Math.min(fixture.width - 1,
                    (int) bounds[2]); x++) {
                if (changed(left, right, index(fixture.width, x, y)) > 1.0e-3f) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static float[] projectedBounds(Camera camera, WaterSurfaceSettings water,
                                           int width, int height) {
        Matrix4f projection = CameraProjection.stable(camera, width, height, new Matrix4f());
        Matrix4f viewProjection = new Matrix4f(projection).mul(camera.getViewMatrix());
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float[][] corners = {
                {water.minX(), water.minZ()}, {water.minX(), water.maxZ()},
                {water.maxX(), water.minZ()}, {water.maxX(), water.maxZ()}
        };
        for (float[] corner : corners) {
            Vector4f clip = new Vector4f(corner[0], water.level(), corner[1], 1.0f)
                    .mul(viewProjection);
            float x = (clip.x / clip.w * 0.5f + 0.5f) * width;
            float y = (clip.y / clip.w * 0.5f + 0.5f) * height;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static WaterSurfaceSettings water() {
        return WaterSurfaceSettings.builder()
                .enabled(true).level(WATER_LEVEL).bounds(-6.0f, -6.0f, 6.0f, 6.0f)
                .waveWavelength(8.0f).waveSpeed(0.6f).waveAmplitude(0.06f)
                .waveDirectionRadians(0.0f).waterColor(WATER_COLOR.x, WATER_COLOR.y,
                        WATER_COLOR.z)
                .absorptionDensity(0.35f).shoreFadeDistance(0.0f)
                .reflectionFallback(0.35f, 0.5f, 0.65f).build();
    }

    private static WaterRefractionSettings refraction() {
        return WaterRefractionSettings.builder()
                .enabled(true).ior(1.333f).strength(1.5f).maximumScreenOffset(0.2f)
                .thicknessScale(2.0f).foregroundRejectionBias(0.05f)
                .offscreenFallback(WaterRefractionSettings.OffscreenFallback.UNREFRACTED)
                .build();
    }

    private static com.kaleblangley.haikalat.core.graph.RenderGraph.PassDescription pass(
            Fixture fixture, String name) {
        return fixture.pipeline.graph().description().passes().stream()
                .filter(pass -> pass.name().equals(name)).findFirst().orElseThrow();
    }

    private static int index(int width, int x, int y) {
        return (y * width + x) * 4;
    }

    private static float changed(float[] left, float[] right, int index) {
        return Math.abs(left[index] - right[index])
                + Math.abs(left[index + 1] - right[index + 1])
                + Math.abs(left[index + 2] - right[index + 2]);
    }

    private static void assertArrayEqualsExact(float[] expected, float[] actual, String message) {
        assertEquals(expected.length, actual.length, message + " length");
        for (int index = 0; index < expected.length; index++) {
            if (Float.floatToIntBits(expected[index]) != Float.floatToIntBits(actual[index])) {
                assertEquals(expected[index], actual[index], 0.0f,
                        message + " at component " + index);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final String SCENE_VERTEX = """
                #version 460 core
                layout(location = 0) in vec3 aPosition;
                layout(std140) uniform CameraBlock {
                    mat4 uProjection;
                    mat4 uView;
                };
                uniform mat4 uModel;
                out vec3 vWorld;
                void main() {
                    vec4 world = uModel * vec4(aPosition, 1.0);
                    vWorld = world.xyz;
                    gl_Position = uProjection * uView * world;
                }
                """;
        private static final String GROUND_FRAGMENT = """
                #version 460 core
                in vec3 vWorld;
                layout(location = 0) out vec4 fragColor;
                uniform vec3 uGroundColor;
                void main() {
                    float pattern = 0.5 + 0.5 * sin(vWorld.x * 10.0) * cos(vWorld.z * 10.0);
                    vec3 colour = mix(uGroundColor, vec3(0.85, 0.35, 0.15), pattern);
                    fragColor = vec4(colour, 1.0);
                }
                """;
        private static final String OCCLUDER_FRAGMENT = """
                #version 460 core
                in vec3 vWorld;
                layout(location = 0) out vec4 fragColor;
                uniform vec3 uOccluderColor;
                void main() {
                    fragColor = vec4(uOccluderColor, 1.0);
                }
                """;

        final GlfwWindow window;
        final int width;
        final int height;
        final GlRenderDevice device = new GlRenderDevice();
        final Camera camera = new Camera(new Vector3f(0.0f, 6.0f, 10.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -40.0f);
        final WaterSurfaceSettings water;
        final Scene scene = new Scene(camera);
        final ShaderProgram sceneShader;
        final ShaderProgram occluderShader;
        final Mesh ground;
        final Mesh cube;
        final Material groundMaterial;
        final Material occluderMaterial;
        final RenderPipeline pipeline;

        Fixture(WaterSurfaceSettings water, WaterRefractionSettings refraction) {
            this.water = water;
            window = new GlfwWindow.Builder().dimensions(REQUESTED_SIZE, REQUESTED_SIZE)
                    .title("Water refraction pipeline test").visible(false).build();
            width = window.width();
            height = window.height();
            window.bindContext();
            org.lwjgl.opengl.GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);
            sceneShader = ShaderProgram.fromSources(SCENE_VERTEX, GROUND_FRAGMENT);
            occluderShader = ShaderProgram.fromSources(SCENE_VERTEX, OCCLUDER_FRAGMENT);
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            float half = 200.0f;
            ground = Mesh.builder()
                    .vertices(new float[]{
                            -half, GROUND_Y, -half,
                            -half, GROUND_Y, half,
                            half, GROUND_Y, half,
                            half, GROUND_Y, -half
                    }, 3 * Float.BYTES, position)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
            cube = cubeMesh(position, 0.12f);
            groundMaterial = Material.builder(sceneShader)
                    .setVec3("uGroundColor", new Vector3f(0.15f, 0.35f, 0.55f))
                    .build();
            occluderMaterial = Material.builder(occluderShader)
                    .setVec3("uOccluderColor", new Vector3f(0.95f, 0.1f, 0.8f))
                    .build();
            scene.add(SceneObject.fixed(ground, groundMaterial, new Matrix4f()));
            pipeline = new RenderPipeline(window, scene, null, RenderSettings.builder()
                    .vsync(false)
                    .antiAliasingMode(AntiAliasingMode.NONE)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .build())
                    .waterSurface(water)
                    .waterRefraction(refraction.withEnabled(false));
            pipeline.build();
        }

        void addOccluder(Vector3f point) {
            scene.add(SceneObject.fixed(cube, occluderMaterial,
                    new Matrix4f().translate(point)));
        }

        void frame(float deltaSeconds) {
            pipeline.execute(device, deltaSeconds);
        }

        float[] capture() {
            return pipeline.captureLinearHdrRgbaFloat();
        }

        private static Mesh cubeMesh(VertexAttribute position, float half) {
            float[] vertices = {
                    -half, -half, -half, half, -half, -half,
                    half, half, -half, -half, half, -half,
                    -half, -half, half, half, -half, half,
                    half, half, half, -half, half, half
            };
            int[] indices = {
                    0, 1, 2, 0, 2, 3,
                    4, 6, 5, 4, 7, 6,
                    0, 4, 5, 0, 5, 1,
                    3, 2, 6, 3, 6, 7,
                    0, 3, 7, 0, 7, 4,
                    1, 5, 6, 1, 6, 2
            };
            return Mesh.builder()
                    .vertices(vertices, 3 * Float.BYTES, position)
                    .indices(indices)
                    .build();
        }

        @Override
        public void close() {
            try {
                pipeline.close();
            } finally {
                try {
                    occluderMaterial.close();
                    groundMaterial.close();
                    cube.close();
                    ground.close();
                    occluderShader.close();
                    sceneShader.close();
                } finally {
                    window.close();
                }
            }
        }
    }
}
