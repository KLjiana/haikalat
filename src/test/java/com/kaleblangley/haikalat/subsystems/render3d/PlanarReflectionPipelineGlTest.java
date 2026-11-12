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
