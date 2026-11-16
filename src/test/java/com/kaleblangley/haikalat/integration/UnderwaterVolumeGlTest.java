package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.water.UnderwaterVolumePass;
import com.kaleblangley.haikalat.subsystems.render3d.water.UnderwaterVolumeSettings;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glGetError;

/**
 * Real-GL production contract for the bounded underwater volume pass.
 *
 * <p>Covers the graph enabled/disabled handoff with a stable topology, the
 * byte-identical above-water path, the Beer-Lambert attenuation against an
 * independent analytic path length, density monotonicity, disable/restore,
 * the MSAA resolved colour/depth copies, and attachment reallocation across a
 * resize round trip.  Every case asserts {@code GL_NO_ERROR}.  Readback
 * indices use the window's actual framebuffer extent, never the requested
 * size.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class UnderwaterVolumeGlTest {
    private static final int REQUESTED_SIZE = 64;
    private static final int RESIZE_REQUESTED_WIDTH = 160;
    private static final int RESIZE_REQUESTED_HEIGHT = 120;
    private static final float FLOOR_Y = -4.0f;
    /** Untinted linear HDR floor colour authored by the fixture shader. */
    private static final float[] FLOOR_COLOR = {0.5f, 0.2f, 0.1f};
    private static final float[] SCATTER_COLOR = {0.02f, 0.15f, 0.30f};
    private static final Vector3f ABOVE_POSITION = new Vector3f(0.0f, 6.0f, 8.0f);
    private static final Vector3f BELOW_POSITION = new Vector3f(0.0f, -2.0f, 8.0f);
    private static final Vector3f LOOK_TARGET = new Vector3f(0.0f, -4.0f, 2.0f);

    @Test
    void cameraAboveWaterLeavesTheFrameByteIdentical() {
        try (Fixture fixture = new Fixture(REQUESTED_SIZE, REQUESTED_SIZE,
                ABOVE_POSITION, AntiAliasingMode.NONE, 1, settings(0.35f))) {
            assertTrue(fixture.pipeline.graph().hasPass(UnderwaterVolumePass.PASS_NAME),
                    "the active volume keeps a stable pass above the plane");
            fixture.frame();
            float[] aboveWithVolume = fixture.capture();
            assertEquals(fixture.width * fixture.height * 4, aboveWithVolume.length);
            for (float value : aboveWithVolume) {
                assertTrue(Float.isFinite(value), "above-water frame must stay finite");
            }
            assertEquals(GL_NO_ERROR, glGetError());

            fixture.pipeline.applyUnderwaterVolume(UnderwaterVolumeSettings.disabled());
            assertFalse(fixture.pipeline.graph().hasPass(UnderwaterVolumePass.PASS_NAME),
                    "disabling the volume must remove the pass");
            fixture.frame();
            float[] aboveWithoutVolume = fixture.capture();
            org.junit.jupiter.api.Assertions.assertArrayEquals(aboveWithoutVolume,
                    aboveWithVolume,
                    "enabled above water must be byte-identical to the disabled baseline");
            assertEquals(aboveWithoutVolume.length, aboveWithVolume.length);
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void belowWaterFloorIsAttenuatedByTheExpectedBeerLambertPath() {
        // Camera-above reference: the same scene seen without a water path.
        float[] aboveFrame;
        int aboveIndex;
        try (Fixture above = new Fixture(REQUESTED_SIZE, REQUESTED_SIZE,
                ABOVE_POSITION, AntiAliasingMode.NONE, 1, settings(0.35f))) {
            above.frame();
            aboveFrame = above.capture();
            aboveIndex = above.centrePixel();
            assertEquals(GL_NO_ERROR, glGetError());
        }
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(FLOOR_COLOR[channel], aboveFrame[aboveIndex + channel], 0.01f,
                    "the camera-above reference must stay the untinted floor (channel "
                            + channel + ")");
        }

        try (Fixture fixture = new Fixture(REQUESTED_SIZE, REQUESTED_SIZE,
                BELOW_POSITION, AntiAliasingMode.NONE, 1, settings(0.35f))) {
            int index = fixture.centrePixel();
            float path = fixture.centrePathLength();
            fixture.frame();
            float[] tinted = fixture.capture();

            fixture.pipeline.applyUnderwaterVolume(UnderwaterVolumeSettings.disabled());
            assertFalse(fixture.pipeline.graph().hasPass(UnderwaterVolumePass.PASS_NAME));
            fixture.frame();
            float[] baseline = fixture.capture();

            assertBeerLambert(baseline, tinted, index, path, settings(0.35f), 0.01f,
                    "single-sample floor pixel");
            assertTrue(tinted[index] < baseline[index] - 0.05f,
                    "red must fall toward the scatter tint, tinted=" + tinted[index]
                            + " baseline=" + baseline[index]);
            assertTrue(tinted[index + 2] > baseline[index + 2] + 0.05f,
                    "blue must rise toward the scatter tint, tinted=" + tinted[index + 2]
                            + " baseline=" + baseline[index + 2]);
            assertTrue(luminance(tinted, index) < luminance(baseline, index),
                    "the attenuated pixel must be darker");
            assertTrue(tinted[index] < aboveFrame[aboveIndex],
                    "the underwater red must be below the camera-above reference");
            assertTrue(tinted[index + 2] > aboveFrame[aboveIndex + 2],
                    "the underwater blue must be above the camera-above reference");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void higherDensityDarkensTheSamePixelFurther() {
        UnderwaterVolumeSettings low = settings(0.12f);
        UnderwaterVolumeSettings high = settings(0.45f);
        try (Fixture fixture = new Fixture(REQUESTED_SIZE, REQUESTED_SIZE,
                BELOW_POSITION, AntiAliasingMode.NONE, 1,
                UnderwaterVolumeSettings.disabled())) {
            int index = fixture.centrePixel();
            float path = fixture.centrePathLength();
            fixture.frame();
            float[] baseline = fixture.capture();

            fixture.pipeline.applyUnderwaterVolume(low);
            fixture.frame();
            float[] lowFrame = fixture.capture();
            fixture.pipeline.applyUnderwaterVolume(high);
            fixture.frame();
            float[] highFrame = fixture.capture();

            assertBeerLambert(baseline, lowFrame, index, path, low, 0.01f, "low density");
            assertBeerLambert(baseline, highFrame, index, path, high, 0.01f, "high density");
            assertTrue(luminance(highFrame, index) < luminance(lowFrame, index),
                    "a higher density must darken the same pixel further, low="
                            + luminance(lowFrame, index) + " high="
                            + luminance(highFrame, index));
            assertTrue(attenuation(high, path) < attenuation(low, path),
                    "the independent model must also predict a smaller attenuation");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void disablingTheVolumeRestoresTheExactBaseline() {
        UnderwaterVolumeSettings active = settings(0.35f);
        try (Fixture fixture = new Fixture(REQUESTED_SIZE, REQUESTED_SIZE,
                BELOW_POSITION, AntiAliasingMode.NONE, 1,
                UnderwaterVolumeSettings.disabled())) {
            assertFalse(fixture.pipeline.graph().hasPass(UnderwaterVolumePass.PASS_NAME));
            fixture.frame();
            float[] baseline = fixture.capture();

            fixture.pipeline.applyUnderwaterVolume(active);
            assertTrue(fixture.pipeline.graph().hasPass(UnderwaterVolumePass.PASS_NAME));
            fixture.frame();
            float[] tinted = fixture.capture();
            assertFalse(java.util.Arrays.equals(baseline, tinted),
                    "the enabled volume must change the below-water frame");

            fixture.pipeline.applyUnderwaterVolume(UnderwaterVolumeSettings.disabled());
            assertFalse(fixture.pipeline.graph().hasPass(UnderwaterVolumePass.PASS_NAME));
            fixture.frame();
            float[] restored = fixture.capture();
            org.junit.jupiter.api.Assertions.assertArrayEquals(baseline, restored,
                    "disabling the volume must restore the exact baseline");

            fixture.pipeline.applyUnderwaterVolume(active);
            fixture.frame();
            org.junit.jupiter.api.Assertions.assertArrayEquals(tinted, fixture.capture(),
                    "re-enabling the same settings must reproduce the tinted frame");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void msaaResolvesTheDepthAndColourCopiesBeforeSampling() {
        // Separate single-frame fixtures: the water surface pass has a
        // pre-existing second-consecutive-frame MSAA error independent of this
        // pass, so the MSAA contract is proven with one water frame per
        // fixture (documented as a limit).
        float[] baseline;
        int baselineWidth;
        int baselineHeight;
        int baselineIndex;
        try (Fixture baselineFixture = new Fixture(REQUESTED_SIZE, REQUESTED_SIZE,
                BELOW_POSITION, AntiAliasingMode.MSAA, 4,
                UnderwaterVolumeSettings.disabled())) {
            baselineFixture.frame();
            baseline = baselineFixture.capture();
            baselineWidth = baselineFixture.width;
            baselineHeight = baselineFixture.height;
            baselineIndex = baselineFixture.centrePixel();
            assertEquals(GL_NO_ERROR, glGetError(), "MSAA baseline frame");
        }
        try (Fixture fixture = new Fixture(REQUESTED_SIZE, REQUESTED_SIZE,
                BELOW_POSITION, AntiAliasingMode.MSAA, 4, settings(0.35f))) {
            assertEquals(baselineWidth, fixture.width,
                    "the two MSAA fixtures must share the actual framebuffer extent");
            assertEquals(baselineHeight, fixture.height);
            int index = fixture.centrePixel();
            float path = fixture.centrePathLength();
            RenderGraph.PassDescription volume = pass(fixture.pipeline,
                    UnderwaterVolumePass.PASS_NAME);
            assertEquals(4, volume.samples(),
                    "the volume target must keep the topology sample count");
            assertEquals(RenderGraph.StorageKind.RENDERBUFFER,
                    volume.colorAttachments().get(0).storageKind(),
                    "the MSAA target must keep the topology sample count");
            RenderGraph.PassDescription depthResolve = pass(fixture.pipeline,
                    UnderwaterVolumePass.DEPTH_RESOLVE_PASS);
            assertEquals(1, depthResolve.samples());
            assertNotNull(depthResolve.depthAttachment(),
                    "the depth resolve must own the single-sample depth texture");
            RenderGraph.PassDescription colorCopy = pass(fixture.pipeline,
                    UnderwaterVolumePass.COLOR_COPY_PASS);
            assertEquals(1, colorCopy.samples());
            assertEquals(UnderwaterVolumePass.SOURCE_COLOR,
                    colorCopy.colorAttachments().get(0).logicalName());
            assertTrue(volume.directDependencies().contains(
                            UnderwaterVolumePass.DEPTH_RESOLVE_PASS),
                    "the draw must consume the resolved depth, deps="
                            + volume.directDependencies());
            assertTrue(volume.directDependencies().contains(
                            UnderwaterVolumePass.COLOR_COPY_PASS),
                    "the draw must consume the resolved colour copy, deps="
                            + volume.directDependencies());
            assertTrue(volume.directDependencies().contains(WaterSurfacePass.PASS_NAME),
                    "the volume must follow the water colour producer, deps="
                            + volume.directDependencies());

            fixture.frame();
            float[] tinted = fixture.capture();
            assertEquals(GL_NO_ERROR, glGetError(), "MSAA enabled frame");
            assertBeerLambert(baseline, tinted, baselineIndex, path, settings(0.35f), 0.02f,
                    "MSAA resolved pixel (index=" + index + ")");
            assertEquals(fixture.width * fixture.height * 4, tinted.length);
            for (float value : tinted) {
                assertTrue(Float.isFinite(value), "MSAA volume frame must stay finite");
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void resizeRebuildsAttachmentsAndKeepsTheEffect() {
        try (Fixture fixture = new Fixture(RESIZE_REQUESTED_WIDTH, RESIZE_REQUESTED_HEIGHT,
                BELOW_POSITION, AntiAliasingMode.NONE, 1, settings(0.35f))) {
            int width = fixture.width;
            int height = fixture.height;
            assertTrue(width > 0 && height > 0);
            fixture.frame();
            float[] initial = fixture.capture();
            assertEquals(width * height * 4, initial.length);

            fixture.resize(width + 40, height + 40);
            int resizedWidth = fixture.width;
            int resizedHeight = fixture.height;
            assertTrue(resizedWidth != width || resizedHeight != height,
                    "the native framebuffer must accept the requested resize, got "
                            + resizedWidth + "x" + resizedHeight);
            fixture.frame();
            assertEquals(resizedWidth, fixture.pipeline.graph().width());
            assertEquals(resizedHeight, fixture.pipeline.graph().height());
            float[] resized = fixture.capture();
            assertEquals(resizedWidth * resizedHeight * 4, resized.length,
                    "the colour chain must reallocate at the resized extent");
            assertEquals(resizedWidth * resizedHeight * 4,
                    fixture.pipeline.graph().readColorAttachmentRgbaFloat(
                            UnderwaterVolumePass.COLOR).length,
                    "the pass-owned target must be rebuilt at the resized extent");
            assertEquals(1, passCount(fixture.pipeline, UnderwaterVolumePass.PASS_NAME),
                    "resize must not duplicate the volume pass");

            // Baseline at the resized extent isolates the pass effect.
            fixture.pipeline.applyUnderwaterVolume(UnderwaterVolumeSettings.disabled());
            fixture.frame();
            float[] resizedBaseline = fixture.capture();
            fixture.pipeline.applyUnderwaterVolume(settings(0.35f));
            fixture.frame();
            float[] resizedTinted = fixture.capture();
            assertBeerLambert(resizedBaseline, resizedTinted, fixture.centrePixel(),
                    fixture.centrePathLength(), settings(0.35f), 0.01f,
                    "resized floor pixel");
            assertEquals(GL_NO_ERROR, glGetError());

            fixture.resize(width, height);
            fixture.frame();
            assertEquals(width, fixture.pipeline.graph().width());
            assertEquals(height, fixture.pipeline.graph().height());
            float[] restored = fixture.capture();
            assertEquals(width * height * 4, restored.length);
            org.junit.jupiter.api.Assertions.assertArrayEquals(initial, restored,
                    "returning to the original extent must reproduce the original frame");
            assertEquals(1, passCount(fixture.pipeline, UnderwaterVolumePass.PASS_NAME));
            assertEquals(1, passCount(fixture.pipeline, WaterSurfacePass.PASS_NAME));
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static UnderwaterVolumeSettings settings(float density) {
        return UnderwaterVolumeSettings.builder()
                .enabled(true)
                .absorptionDensity(density)
                .scatterColor(SCATTER_COLOR[0], SCATTER_COLOR[1], SCATTER_COLOR[2])
                .maximumDistanceMetres(60.0f)
                .build();
    }

    private static WaterSurfaceSettings water() {
        return WaterSurfaceSettings.builder()
                .enabled(true).level(0.0f).bounds(-1.5f, -1.5f, 1.5f, 1.5f)
                .waveWavelength(8.0f).waveSpeed(0.0f).waveAmplitude(0.0f)
                .waveDirectionRadians(0.0f)
                .waterColor(0.05f, 0.35f, 0.55f)
                .absorptionDensity(0.05f).shoreFadeDistance(0.0f)
                .reflectionFallback(0.35f, 0.5f, 0.65f)
                .build();
    }

    /** Independent double-precision Beer-Lambert attenuation for the given path. */
    private static double attenuation(UnderwaterVolumeSettings settings, float pathMetres) {
        double path = Math.min(Math.max(pathMetres, 0.0f),
                settings.maximumDistanceMetres());
        return Math.exp(-(double) settings.absorptionDensity() * path);
    }

    private static void assertBeerLambert(float[] baseline, float[] tinted, int index,
                                          float pathMetres, UnderwaterVolumeSettings settings,
                                          float tolerance, String context) {
        double attenuation = attenuation(settings, pathMetres);
        float[] scatter = {settings.scatterColor().x, settings.scatterColor().y,
                settings.scatterColor().z};
        for (int channel = 0; channel < 3; channel++) {
            double expected = baseline[index + channel] * attenuation
                    + scatter[channel] * (1.0 - attenuation);
            assertEquals((float) expected, tinted[index + channel], tolerance,
                    context + " channel " + channel + " (path=" + pathMetres
                            + " attenuation=" + attenuation + ")");
        }
    }

    private static float luminance(float[] pixels, int index) {
        return 0.2126f * pixels[index] + 0.7152f * pixels[index + 1]
                + 0.0722f * pixels[index + 2];
    }

    private static RenderGraph.PassDescription pass(RenderPipeline pipeline, String name) {
        return pipeline.graph().description().passes().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static long passCount(RenderPipeline pipeline, String name) {
        return pipeline.graph().description().passes().stream()
                .filter(candidate -> candidate.name().equals(name))
                .count();
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
        int width;
        int height;
        final GlRenderDevice device = new GlRenderDevice();
        final Matrix4f view;
        final Matrix4f projection;
        final Vector3f cameraPosition;
        final ShaderProgram groundShader;
        final Mesh ground;
        final Material groundMaterial;
        final RenderPipeline pipeline;

        Fixture(int requestedWidth, int requestedHeight, Vector3f position,
                AntiAliasingMode antiAliasing, int samples, UnderwaterVolumeSettings volume) {
            window = new GlfwWindow.Builder().dimensions(requestedWidth, requestedHeight)
                    .title("Underwater volume pipeline test").visible(false).build();
            width = window.width();
            height = window.height();
            window.bindContext();
            org.lwjgl.opengl.GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);
            cameraPosition = new Vector3f(position);
            view = new Matrix4f().lookAt(cameraPosition, LOOK_TARGET,
                    new Vector3f(0.0f, 1.0f, 0.0f));
            projection = new Matrix4f().perspective((float) Math.toRadians(55.0),
                    width / (float) height, 0.1f, 50.0f);
            ExternalCamera camera = ExternalCamera.of(view, projection, cameraPosition, 0.0f);
            groundShader = ShaderProgram.fromSources(GROUND_VERTEX, GROUND_FRAGMENT);
            VertexAttribute attribute = VertexAttribute.builder()
                    .index(0).size(3).offsetBytes(0L)
                    .semantic(VertexSemantic.POSITION).build();
            float half = 200.0f;
            ground = Mesh.builder()
                    .vertices(new float[]{
                            -half, FLOOR_Y, -half,
                            -half, FLOOR_Y, half,
                            half, FLOOR_Y, half,
                            half, FLOOR_Y, -half
                    }, 3 * Float.BYTES, attribute)
                    .indices(new int[]{0, 1, 2, 0, 2, 3})
                    .build();
            groundMaterial = Material.builder(groundShader)
                    .setVec3("uGroundColor",
                            new Vector3f(FLOOR_COLOR[0], FLOOR_COLOR[1], FLOOR_COLOR[2]))
                    .build();
            Scene scene = new Scene(camera);
            scene.add(SceneObject.fixed(ground, groundMaterial, new Matrix4f()));
            RenderSettings settings = RenderSettings.builder()
                    .vsync(false)
                    .antiAliasingMode(antiAliasing)
                    .msaaSamples(samples)
                    .toneMappingMode(ToneMappingMode.ACES)
                    .build();
            pipeline = new RenderPipeline(window, scene, null, settings)
                    .waterSurface(water())
                    .underwaterVolume(volume);
            pipeline.build();
        }

        void frame() {
            pipeline.execute(device, 0.0f);
        }

        void resize(int newWidth, int newHeight) {
            window.resize(newWidth, newHeight);
            int attempts = 0;
            while ((window.width() != newWidth || window.height() != newHeight)
                    && attempts++ < 100) {
                org.lwjgl.glfw.GLFW.glfwPollEvents();
            }
            width = window.width();
            height = window.height();
            assertTrue(width > 0 && height > 0, "the resized framebuffer must be positive");
        }

        float[] capture() {
            return pipeline.captureLinearHdrRgbaFloat();
        }

        int centrePixel() {
            return ((height / 2) * width + width / 2) * 4;
        }

        /**
         * Independent analytic distance from the camera through the centre
         * pixel to the floor plane, using the same fixed view/projection the
         * pipeline consumes ({@link ExternalCamera} keeps its projection on
         * resize, so this stays valid after {@code pipeline.resize}).
         */
        float centrePathLength() {
            int centerX = width / 2;
            int centerY = height / 2;
            float ndcX = (centerX + 0.5f) * 2.0f / width - 1.0f;
            float ndcY = (centerY + 0.5f) * 2.0f / height - 1.0f;
            Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
            Vector4f near = new Vector4f(ndcX, ndcY, -1.0f, 1.0f).mul(inverse);
            near.div(near.w);
            Vector4f far = new Vector4f(ndcX, ndcY, 1.0f, 1.0f).mul(inverse);
            far.div(far.w);
            Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y,
                    far.z - near.z).normalize();
            assertTrue(direction.y < -1.0e-4f,
                    "the centre ray must look down at the floor, direction=" + direction);
            return (FLOOR_Y - cameraPosition.y) / direction.y;
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
