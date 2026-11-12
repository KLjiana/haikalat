package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionPass;
import com.kaleblangley.haikalat.subsystems.render3d.planar.PlanarReflectionSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;

/**
 * Real-GL production path for the single-plane reflection view.
 *
 * <p>The reflection is an auxiliary subview: enabling it must fill its own
 * target without changing the main scene pixels, disabling it must remove the
 * pass, and the pass must never write the shared scene depth.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class PlanarReflectionPipelineGlTest {
    private static final int SIZE = 64;
    private static final String VERTEX = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(0.9, 0.4, 0.2, 1.0);
            }
            """;

    @Test
    void reflectionFillsItsOwnTargetAndLeavesTheMainViewByteIdentical() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("planar-wiring"));
                ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                Material material = Material.builder(shader).build();
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 2.0f, 5.0f)));
                scene.add(new SceneObject(mesh, material, (model, frame) ->
                        model.identity().translate(0.0f, 1.0f, 0.0f)));
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        hdrSettings());
                try {
                    pipeline.build();
                    assertFalse(pipeline.graph().hasPass(PlanarReflectionPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] baseline = pipeline.captureLinearHdrRgbaFloat();

                    PlanarReflectionSettings active = PlanarReflectionSettings.builder()
                            .enabled(true).planeY(0.0f).maxResolution(128).build();
                    pipeline.applyPlanarReflections(active);
                    assertTrue(pipeline.graph().hasPass(PlanarReflectionPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);

                    PlanarReflectionPass pass = pipeline.activeGenerationForTest()
                            .planarReflection();
                    assertNotNull(pass, "the active generation must own the reflection pass");
                    assertTrue(pass.hasValidContent(),
                            "one successful frame must publish reflection content");
                    int width = pass.width();
                    int height = pass.height();
                    float[] reflection = readTexture(pass.colorTexture(), width, height);
                    assertTrue(hasNonZero(reflection),
                            "the mirrored scene must visibly contribute inside the reflection target");
                    assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(),
                            "the auxiliary reflection view must not change the main scene pixels");

                    pipeline.applyPlanarReflections(PlanarReflectionSettings.disabled());
                    assertFalse(pipeline.graph().hasPass(PlanarReflectionPass.PASS_NAME));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertArrayEquals(baseline, pipeline.captureLinearHdrRgbaFloat(),
                            "disabling the reflection must restore the byte-identical main view");
                } finally {
                    pipeline.close();
                    material.close();
                    shader.close();
                    mesh.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("planar reflection pipeline composition");
        }
    }

    @Test
    void waterSurfaceSamplesThePlanarTargetAndFallsBackWhenDisabled() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("planar-water"));
                ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                Material material = Material.builder(shader).build();
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 3.0f, 6.0f)));
                scene.add(new SceneObject(mesh, material, (model, frame) ->
                        model.identity().translate(0.0f, 1.0f, 0.0f).scale(2.0f)));
                var water = com.kaleblangley.haikalat.subsystems.render3d.water
                        .WaterSurfaceSettings.builder().enabled(true).level(0.0f)
                        .bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                        // Freeze the wave animation so the disabled comparison
                        // is not confounded by the elapsed-frame phase.
                        .waveSpeed(0.0f).waveAmplitude(0.0f).build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        hdrSettings()).waterSurface(water);
                try {
                    pipeline.build();
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] environmentReflection = pipeline.captureLinearHdrRgbaFloat();

                    pipeline.applyPlanarReflections(PlanarReflectionSettings.builder()
                            .enabled(true).planeY(0.0f).maxResolution(128).build());
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    float[] planarReflection = pipeline.captureLinearHdrRgbaFloat();
                    assertTrue(different(environmentReflection, planarReflection),
                            "the water surface must sample the planar reflection target");

                    pipeline.applyPlanarReflections(PlanarReflectionSettings.disabled());
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertArrayEquals(environmentReflection,
                            pipeline.captureLinearHdrRgbaFloat(),
                            "disabling the planar target must restore the environment reflection");
                } finally {
                    pipeline.close();
                    material.close();
                    shader.close();
                    mesh.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("planar reflection water consumption");
        }
    }

    @Test
    void planarWaterConsumerSurvivesResize() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("planar-water-resize"));
                ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                Material material = Material.builder(shader).build();
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 3.0f, 6.0f)));
                scene.add(new SceneObject(mesh, material, (model, frame) ->
                        model.identity().translate(0.0f, 1.0f, 0.0f).scale(2.0f)));
                var water = com.kaleblangley.haikalat.subsystems.render3d.water
                        .WaterSurfaceSettings.builder().enabled(true).level(0.0f)
                        .bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                        .waveSpeed(0.0f).waveAmplitude(0.0f).build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        hdrSettings()).waterSurface(water);
                try {
                    PlanarReflectionSettings planar = PlanarReflectionSettings.builder()
                            .enabled(true).planeY(0.0f).maxResolution(128).build();
                    pipeline.build();
                    pipeline.applyPlanarReflections(planar);
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertTrue(pipeline.activeGenerationForTest().planarReflection()
                            .hasValidContent());

                    // Resize is applied at the frame boundary; executing again
                    // would resize back to the 64x64 window, so inspect the
                    // resized generation directly.
                    pipeline.resize(48, 36);
                    PlanarReflectionPass resized = pipeline.activeGenerationForTest()
                            .planarReflection();
                    assertNotNull(resized, "resize must keep the planar owner");
                    assertEquals(planar.renderWidth(48, 36), resized.width());
                    assertEquals(planar.renderHeight(48, 36), resized.height());
                    assertFalse(resized.hasValidContent(),
                            "a resized planar target is invalid until the next record, so the water "
                                    + "consumer must fall back to its environment path");
                } finally {
                    pipeline.close();
                    material.close();
                    shader.close();
                    mesh.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("planar reflection water resize");
        }
    }

    @Test
    void throttledPlanarTargetReusesTheLastValidReflection() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("planar-water-throttle"));
                ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                Material material = Material.builder(shader).build();
                Scene scene = new Scene(new Camera(new Vector3f(0.0f, 3.0f, 6.0f)));
                scene.add(new SceneObject(mesh, material, (model, frame) ->
                        model.identity().translate(0.0f, 1.0f, 0.0f).scale(2.0f)));
                var water = com.kaleblangley.haikalat.subsystems.render3d.water
                        .WaterSurfaceSettings.builder().enabled(true).level(0.0f)
                        .bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                        .waveSpeed(0.0f).waveAmplitude(0.0f).build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        hdrSettings()).waterSurface(water)
                        .planarReflections(PlanarReflectionSettings.builder().enabled(true)
                                .planeY(0.0f).maxResolution(128)
                                .updateIntervalFrames(2).build());
                try {
                    pipeline.build();
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    PlanarReflectionPass pass = pipeline.activeGenerationForTest()
                            .planarReflection();
                    long firstUpdate = pass.updateCount();
                    assertTrue(firstUpdate >= 1, "the first frame must record the reflection");
                    float[] first = pipeline.captureLinearHdrRgbaFloat();

                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertEquals(firstUpdate, pass.updateCount(),
                            "the throttled frame must not record the reflection");
                    assertArrayEquals(first, pipeline.captureLinearHdrRgbaFloat(),
                            "water must keep sampling the last valid throttled reflection");

                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertTrue(pass.updateCount() > firstUpdate,
                            "the next interval must record the reflection again");
                    assertTrue(pass.hasValidContent());
                } finally {
                    pipeline.close();
                    material.close();
                    shader.close();
                    mesh.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("planar reflection throttling");
        }
    }

    @Test
    void cameraCutRefreshesThePlanarReflectionUsedByWater() {
        try (var window = window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                Mesh mesh = Mesh.from(BuiltinMeshData.coloredTriangle("planar-water-cut"));
                ShaderProgram shader = ShaderProgram.fromSources(VERTEX, FRAGMENT);
                Material material = Material.builder(shader).build();
                Camera camera = new Camera(new Vector3f(0.0f, 3.0f, 6.0f));
                Scene scene = new Scene(camera);
                scene.add(new SceneObject(mesh, material, (model, frame) ->
                        model.identity().translate(0.0f, 1.0f, 0.0f).scale(2.0f)));
                var water = com.kaleblangley.haikalat.subsystems.render3d.water
                        .WaterSurfaceSettings.builder().enabled(true).level(0.0f)
                        .bounds(-20.0f, -20.0f, 20.0f, 20.0f)
                        .waveSpeed(0.0f).waveAmplitude(0.0f).build();
                RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                        hdrSettings()).waterSurface(water)
                        .planarReflections(PlanarReflectionSettings.builder().enabled(true)
                                .planeY(0.0f).maxResolution(128).build());
                try {
                    pipeline.build();
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    PlanarReflectionPass pass = pipeline.activeGenerationForTest()
                            .planarReflection();
                    long before = pass.updateCount();
                    float[] first = pipeline.captureLinearHdrRgbaFloat();

                    camera.setPosition(new Vector3f(1.5f, 3.5f, 5.0f));
                    pipeline.execute(new GlRenderDevice(), 1f / 60f);
                    assertTrue(pass.updateCount() > before,
                            "a camera cut must record the reflection again");
                    assertTrue(pass.hasValidContent());
                    assertTrue(different(first, pipeline.captureLinearHdrRgbaFloat()),
                            "the water reflection must follow the new camera view");
                } finally {
                    pipeline.close();
                    material.close();
                    shader.close();
                    mesh.close();
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
            GlDebug.assertNoError("planar reflection camera cut");
        }
    }

    private static boolean different(float[] first, float[] second) {
        if (first.length != second.length) return true;
        for (int i = 0; i < first.length; i++) {
            if (Float.floatToIntBits(first[i]) != Float.floatToIntBits(second[i])) return true;
        }
        return false;
    }

    private static RenderSettings hdrSettings() {
        return RenderSettings.builder()
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .vsync(false)
                .build();
    }

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE).visible(false)
                .title("Planar reflection wiring").build();
    }

    private static float[] readTexture(int texture, int width, int height) {
        int framebuffer = glGenFramebuffers();
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    texture, 0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, buffer);
            float[] result = new float[width * height * 4];
            buffer.get(result);
            return result;
        } finally {
            MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(framebuffer);
        }
    }

    private static boolean hasNonZero(float[] pixels) {
        for (float value : pixels) {
            if (value != 0.0f) return true;
        }
        return false;
    }
}
