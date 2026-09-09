package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;

/**
 * Real GPU velocity/validity oracle: moving opaque geometry must produce the
 * projected screen-space motion, MASK holes and background must report
 * validity 0 with zero velocity.
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SceneVelocityGlTest {
    private static final String VERTEX_SOURCE = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            layout (std140) uniform CameraBlock {
                mat4 uProjection;
                mat4 uView;
            };
            uniform mat4 uModel;
            out vec2 vUv;
            void main() {
                vUv = aTexCoord;
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
            }
            """;
    private static final String FRAGMENT_SOURCE = """
            #version 330 core
            in vec2 vUv;
            out vec4 FragColor;
            uniform sampler2D uBaseColorMap;
            uniform vec4 uBaseColorFactor;
            uniform int uAlphaMode;
            uniform float uAlphaCutoff;
            void main() {
                vec4 base = texture(uBaseColorMap, vUv) * uBaseColorFactor;
                if (uAlphaMode == 1 && base.a < uAlphaCutoff) {
                    discard;
                }
                FragColor = base;
            }
            """;

    @Test
    void movingOpaqueVelocityMatchesProjectionAndMaskHoleIsInvalid() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(96, 96)
                .title("Scene Velocity GL Test")
                .visible(false)
                .build()) {
            window.bindContext();
            GL.createCapabilities();

            ShaderProgram shader = ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
            Mesh mesh = Mesh.from(BuiltinMeshData.coloredQuad("velocity-quad"));
            Material opaque = Material.builder(shader).build();
            Material masked = Material.builder(shader)
                    .setInt("uAlphaMode", 1)
                    .setFloat("uAlphaCutoff", 0.5f)
                    .setVec4("uBaseColorFactor", new Vector4f(1.0f, 1.0f, 1.0f, 0.0f))
                    .build();
            Camera camera = new Camera(new Vector3f(0, 0, 5));
            Scene scene = new Scene(camera);
            int[] xStep = {0};
            scene.add(new SceneObject(mesh, opaque, (model, frame) -> model.identity()
                    .translate(-1.0f + xStep[0] * 0.5f, 0.0f, -3.0f).scale(1.0f), false));
            scene.add(new SceneObject(mesh, masked, (model, frame) -> model.identity()
                    .translate(1.0f, 0.0f, -3.0f).scale(1.0f), false));

            RenderPipeline pipeline = new RenderPipeline(window, scene, null,
                    RenderSettings.builder()
                            .antiAliasingMode(AntiAliasingMode.TAA)
                            .toneMappingMode(ToneMappingMode.ACES)
                            .vsync(false)
                            .build());
            int[] velocityTexture = {-1};
            int[] validityTexture = {-1};
            pipeline.build();
            pipeline.graph().addPass("TemporalVelocityReadback")
                    .createColor("temporalVelocityReadback",
                            com.kaleblangley.haikalat.backend.RenderFormat.RGBA8)
                    .noClear()
                    .dependsOn(com.kaleblangley.haikalat.subsystems.postprocess
                            .PostProcessTargets.SCENE_SURFACE_PASS)
                    .execute((resources, commands) -> {
                        velocityTexture[0] = resources.colorAttachment(com.kaleblangley.haikalat
                                .subsystems.postprocess.PostProcessTargets.SCENE_VELOCITY);
                        validityTexture[0] = resources.colorAttachment(com.kaleblangley.haikalat
                                .subsystems.postprocess.PostProcessTargets.SCENE_VALIDITY);
                    });
            try {
                GlRenderDevice device = new GlRenderDevice();
                pipeline.execute(device);
                // Frame 2 moves the opaque quad +0.5 world units; the first
                // frame has no previous model and therefore reports validity 0.
                xStep[0] = 1;
                pipeline.execute(device);
                float[] velocity = readFloats(velocityTexture[0], 96, 96, 4);
                float[] validity = readBytes(validityTexture[0], 96, 96);
                int validPixels = 0;
                for (float value : validity) {
                    if (value > 0.5f) validPixels++;
                }
                assertTrue(validPixels > 50, "opaque quad must produce valid surface pixels");
                assertEquals(0.0f, validity[48 * 96 + 48], 1.0e-3f,
                        "background between the quads must be invalid");
                assertEquals(0.0f, validity[48 * 96 + 58], 1.0e-3f,
                        "MASK hole must not produce a valid surface");

                double expected = expectedVelocityX();
                double sum = 0.0;
                int count = 0;
                for (int pixel = 0; pixel < 96 * 96; pixel++) {
                    if (validity[pixel] <= 0.5f) continue;
                    sum += velocity[pixel * 4];
                    count++;
                }
                assertTrue(count > 50, "moving quad must stay valid");
                double average = sum / count;
                assertEquals(expected, average, Math.abs(expected) * 0.35 + 2.0e-4,
                        "GPU velocity must match the projected screen-space motion");
            } finally {
                pipeline.close();
                masked.close();
                opaque.close();
                mesh.close();
                shader.close();
            }
        }
    }

    /** Stable projection (TAA jitter is excluded from motion) for x=-0.75, z=-3. */
    private static double expectedVelocityX() {
        int width = 96;
        int height = 96;
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60.0f),
                width / (float) height, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 5, 0, 0, 4, 0, 1, 0);
        double currentX = -1.0 + 0.5;
        double previousX = -1.0;
        double current = projectX(projection, view, currentX);
        double previous = projectX(projection, view, previousX);
        return previous - current;
    }

    private static double projectX(Matrix4f projection, Matrix4f view, double x) {
        Matrix4f vp = new Matrix4f(projection).mul(view);
        Vector4f clip = vp.transform(new Vector4f((float) x, 0.0f, -3.0f, 1.0f));
        return clip.x / clip.w * 0.5 + 0.5;
    }

    private static float[] readBytes(int texture, int width, int height) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
        float[] result = new float[width * height];
        for (int pixel = 0; pixel < width * height; pixel++) {
            result[pixel] = Byte.toUnsignedInt(pixels.get(pixel * 4)) / 255.0f;
        }
        return result;
    }

    private static float[] readFloats(int texture, int width, int height, int channels) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, pixels);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
        float[] result = new float[width * height * channels];
        for (int pixel = 0; pixel < width * height; pixel++) {
            for (int channel = 0; channel < channels; channel++) {
                result[pixel * channels + channel] = pixels.get(pixel * 4 + channel);
            }
        }
        return result;
    }
}
