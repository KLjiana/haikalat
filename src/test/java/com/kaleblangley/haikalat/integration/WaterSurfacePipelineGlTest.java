package com.kaleblangley.haikalat.integration;

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
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessTargets;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfacePass;
import com.kaleblangley.haikalat.subsystems.render3d.water.WaterSurfaceSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Pipeline-level real-GL contract for the finite water surface.
 *
 * <p>Covers the graph enabled/disabled handoff, finite world bounds, absorption
 * and shoreline response, time-varying normals, MSAA resolve and the grazing
 * view NaN bound.  Numeric shader values are proven independently in
 * {@code render3d.water.WaterSurfacePassGlTest}.  All readback indices use the
 * window's actual framebuffer extent, never the requested window size.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class WaterSurfacePipelineGlTest {
    private static final int REQUESTED_SIZE = 64;
    private static final float GROUND_Y = -4.0f;
    private static final float WATER_COLOR_RED = 0.02f;
    private static final float WATER_COLOR_GREEN = 0.12f;
    private static final float WATER_COLOR_BLUE = 0.16f;

    @Test
    void enabledDisabledFiniteBoundsAbsorptionAndShoreRespond() {
        WaterSurfaceSettings water = settings(0.35f, 0.75f);
        try (Fixture fixture = new Fixture(water, AntiAliasingMode.NONE, 1)) {
            int width = fixture.width;
            int height = fixture.height;
            assertTrue(fixture.pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME));
            fixture.frame(0.0f);
            float[] enabled = fixture.capture();
            assertEquals(width * height * 4, enabled.length,
                    "readback must match the actual framebuffer extent");
            float[] bounds = projectedBounds(fixture.camera, water, width, height);
            int center = pixel(width, width / 2, height / 2);

            fixture.pipeline.applyWaterSurface(water.withEnabled(false));
            assertFalse(fixture.pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME),
                    "disabled water must remove its pass");
            fixture.frame(0.0f);
            float[] disabled = fixture.capture();

            assertTrue(changed(enabled, disabled, center) > 2.0e-3f,
                    "enabled water must change the centre pixel");
            int outsideChecked = 0;
            int changedInside = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = pixel(width, x, y);
                    if (x < bounds[0] - 2 || x > bounds[2] + 2
                            || y < bounds[1] - 2 || y > bounds[3] + 2) {
                        assertRgbaEquals(disabled, enabled, index, 1.0e-6f,
                                "no water outside the finite rectangle at " + x + "," + y);
                        outsideChecked++;
                    } else if (changed(enabled, disabled, index) > 1.0e-4f) {
                        changedInside++;
                    }
                }
            }
            assertTrue(outsideChecked > 200, "the finite rectangle must leave screen area outside");
            assertTrue(changedInside > 16, "water must cover a visible screen region");
            assertEquals(GL_NO_ERROR, glGetError());

            fixture.pipeline.applyWaterSurface(water);
            fixture.frame(0.0f);
            float[] absorbed = fixture.capture();
            fixture.pipeline.applyWaterSurface(settings(0.0f, 0.75f));
            fixture.frame(0.0f);
            float[] unabsorbed = fixture.capture();
            assertTrue(distanceToWaterColor(absorbed, center)
                            < distanceToWaterColor(unabsorbed, center),
                    "higher absorption density must move the water body towards its colour");

            fixture.pipeline.applyWaterSurface(settings(0.35f, 0.0f));
            fixture.frame(0.0f);
            float[] hardShore = fixture.capture();
            fixture.pipeline.applyWaterSurface(settings(0.35f, 6.0f));
            fixture.frame(0.0f);
            float[] fadedShore = fixture.capture();
            assertTrue(distance(fadedShore, disabled, center)
                            < distance(hardShore, disabled, center),
                    "a longer shoreline fade must move the pixel towards the background");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void movingWavesChangeNormalsAndGrazingViewStaysFinite() {
        WaterSurfaceSettings water = WaterSurfaceSettings.builder()
                .enabled(true).level(0.0f).bounds(-6.0f, -6.0f, 6.0f, 6.0f)
                .waveWavelength(3.0f).waveSpeed(1.2f).waveAmplitude(0.1f)
                .waveDirectionRadians(0.7f)
                .waterColor(WATER_COLOR_RED, WATER_COLOR_GREEN, WATER_COLOR_BLUE)
                .absorptionDensity(0.35f).shoreFadeDistance(0.0f)
                .reflectionFallback(0.35f, 0.5f, 0.65f)
                .build();
        try (Fixture fixture = new Fixture(water, AntiAliasingMode.NONE, 1)) {
            int width = fixture.width;
            int height = fixture.height;
            fixture.frame(0.0f);
            float[] first = fixture.capture();
            fixture.frame(0.6f);
            fixture.frame(0.6f);
            float[] later = fixture.capture();
            float[] bounds = projectedBounds(fixture.camera, water, width, height);
            int changed = 0;
            for (int y = Math.max(0, (int) bounds[1]);
                 y <= Math.min(height - 1, (int) bounds[3]); y++) {
                for (int x = Math.max(0, (int) bounds[0]);
                     x <= Math.min(width - 1, (int) bounds[2]); x++) {
                    if (changed(first, later, pixel(width, x, y)) > 5.0e-4f) changed++;
                }
            }
            assertTrue(changed > 32, "moving waves must alter normals inside the finite rectangle");

            // A camera almost exactly on the plane: grazing fragments are rejected
            // and no pixel may become NaN/Inf.
            fixture.camera.setPosition(new Vector3f(0.0f, 0.002f, 10.0f));
            fixture.camera.setPitch(0.0f);
            fixture.frame(0.2f);
            float[] grazing = fixture.capture();
            for (int index = 0; index < grazing.length; index++) {
                assertTrue(Float.isFinite(grazing[index]),
                        "grazing capture must be finite at " + index);
                assertTrue(Math.abs(grazing[index]) < 100.0f,
                        "grazing capture must stay bounded");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void msaaWaterColourResolvesThroughTheHdrChain() {
        try (Fixture fixture = new Fixture(settings(0.35f, 0.75f),
                AntiAliasingMode.MSAA, 4)) {
            assertTrue(fixture.pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME));
            var description = fixture.pipeline.graph().description();
            var resolve = description.passes().stream()
                    .filter(pass -> pass.name().equals(PostProcessTargets.HDR_RESOLVE_PASS))
                    .findFirst().orElseThrow();
            assertTrue(resolve.directDependencies().contains(WaterSurfacePass.PASS_NAME),
                    "MSAA HDR resolve must consume the water pass output");

            fixture.frame(0.0f);
            float[] withWater = fixture.capture();
            assertEquals(fixture.width * fixture.height * 4, withWater.length);
            for (float value : withWater) {
                assertTrue(Float.isFinite(value), "MSAA water capture must be finite");
            }

            fixture.pipeline.applyWaterSurface(WaterSurfaceSettings.disabled());
            assertFalse(fixture.pipeline.graph().hasPass(WaterSurfacePass.PASS_NAME),
                    "disabling water at runtime must remove the pass");
            fixture.frame(0.0f);
            float[] withoutWater = fixture.capture();
            assertEquals(withWater.length, withoutWater.length);

            WaterSurfaceSettings bounds = settings(0.35f, 0.75f);
            float[] projected = projectedBounds(fixture.camera, bounds,
                    fixture.width, fixture.height);
            int changed = 0;
            for (int y = Math.max(0, (int) projected[1]);
                 y <= Math.min(fixture.height - 1, (int) projected[3]); y++) {
                for (int x = Math.max(0, (int) projected[0]);
                     x <= Math.min(fixture.width - 1, (int) projected[2]); x++) {
                    int index = (y * fixture.width + x) * 4;
                    if (Math.abs(withWater[index] - withoutWater[index]) > 1.0e-4f
                            || Math.abs(withWater[index + 1] - withoutWater[index + 1]) > 1.0e-4f
                            || Math.abs(withWater[index + 2] - withoutWater[index + 2]) > 1.0e-4f) {
                        changed++;
                    }
                }
            }
            assertTrue(changed > 32,
                    "MSAA water must change resolved pixels inside the finite rectangle, changed="
                            + changed);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static WaterSurfaceSettings settings(float absorptionDensity, float shoreFadeDistance) {
        return WaterSurfaceSettings.builder()
                .enabled(true).level(0.0f).bounds(-6.0f, -6.0f, 6.0f, 6.0f)
                .waveWavelength(8.0f).waveSpeed(0.6f).waveAmplitude(0.06f)
                .waveDirectionRadians(0.0f)
                .waterColor(WATER_COLOR_RED, WATER_COLOR_GREEN, WATER_COLOR_BLUE)
                .absorptionDensity(absorptionDensity)
                .shoreFadeDistance(shoreFadeDistance)
                .reflectionFallback(0.35f, 0.5f, 0.65f)
                .build();
    }

    /** Independent projection of the finite rectangle corners into pixel coordinates. */
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

    private static float changed(float[] left, float[] right, int index) {
        return Math.abs(left[index] - right[index])
                + Math.abs(left[index + 1] - right[index + 1])
                + Math.abs(left[index + 2] - right[index + 2]);
    }

    private static float distance(float[] left, float[] right, int index) {
        float x = left[index] - right[index];
        float y = left[index + 1] - right[index + 1];
        float z = left[index + 2] - right[index + 2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float distanceToWaterColor(float[] pixels, int index) {
        float x = pixels[index] - WATER_COLOR_RED;
        float y = pixels[index + 1] - WATER_COLOR_GREEN;
        float z = pixels[index + 2] - WATER_COLOR_BLUE;
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static void assertRgbaEquals(float[] expected, float[] actual, int index,
                                         float tolerance, String message) {
        assertEquals(expected[index], actual[index], tolerance, message + " red");
        assertEquals(expected[index + 1], actual[index + 1], tolerance, message + " green");
        assertEquals(expected[index + 2], actual[index + 2], tolerance, message + " blue");
    }

    private static int pixel(int width, int x, int y) {
        return (y * width + x) * 4;
    }

    private static final class Fixture implements AutoCloseable {
        private static final String GROUND_VERTEX = """
                #version 460 core
                layout(location = 0) in vec3 aPosition;
                layout(std140) uniform CameraBlock {
                    mat4 uProjection;
                    mat4 uView;
                };
                uniform mat4 uModel;
                void main() {
                    gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);
                }
                """;
        private static final String GROUND_FRAGMENT = """
                #version 460 core
                layout(location = 0) out vec4 fragColor;
                uniform vec3 uGroundColor;
                void main() {
                    fragColor = vec4(uGroundColor, 1.0);
                }
                """;

        final GlfwWindow window;
        final int width;
        final int height;
        final GlRenderDevice device = new GlRenderDevice();
        final Camera camera = new Camera(new Vector3f(0.0f, 6.0f, 10.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -40.0f);
        final ShaderProgram groundShader;
        final Mesh ground;
        final Material groundMaterial;
        final RenderPipeline pipeline;

        Fixture(WaterSurfaceSettings water, AntiAliasingMode antiAliasing, int samples) {
            window = new GlfwWindow.Builder().dimensions(REQUESTED_SIZE, REQUESTED_SIZE)
                    .title("Water surface pipeline test").visible(false).build();
            width = window.width();
            height = window.height();
            window.bindContext();
            org.lwjgl.opengl.GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);
            groundShader = ShaderProgram.fromSources(GROUND_VERTEX, GROUND_FRAGMENT);
            VertexAttribute position = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            float half = 200.0f;
            float[] vertices = {
                    -half, GROUND_Y, -half,
                    -half, GROUND_Y, half,
                    half, GROUND_Y, half,
                    half, GROUND_Y, -half
            };
            ground = Mesh.builder()
                    .vertices(vertices, 3 * Float.BYTES, position)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
            groundMaterial = Material.builder(groundShader)
                    .setVec3("uGroundColor", new Vector3f(0.2f, 0.4f, 0.6f))
                    .build();
            Scene scene = new Scene(camera);
            scene.add(SceneObject.fixed(ground, groundMaterial, new Matrix4f()));
            pipeline = new RenderPipeline(window, scene, null, RenderSettings.builder()
                    .vsync(false)
                    .antiAliasingMode(antiAliasing)
                    .msaaSamples(samples)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .build())
                    .waterSurface(water);
            pipeline.build();
        }

        void frame(float deltaSeconds) {
            pipeline.execute(device, deltaSeconds);
        }

        float[] capture() {
            return pipeline.captureLinearHdrRgbaFloat();
        }

        @Override
        public void close() {
            try {
                pipeline.close();
            } finally {
                groundMaterial.close();
                ground.close();
                groundShader.close();
                window.close();
            }
        }
    }
}
