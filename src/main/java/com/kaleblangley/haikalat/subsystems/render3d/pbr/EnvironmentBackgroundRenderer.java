package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.ExternalCamera;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/** 在 geometry pass 起点绘制 HDR environment，随后普通深度物体覆盖它。 */
public final class EnvironmentBackgroundRenderer implements AutoCloseable {
    private static final int UNIT = 8;
    private final PbrEnvironment environment;
    private final ShaderProgram shader = ShaderProgram.fromResource(EnvironmentBackgroundRenderer.class,
            "/shaders/render3d/pbr/environment-background.vert",
            "/shaders/render3d/pbr/environment-background.frag");
    private final VertexArray vertexArray = new VertexArray();
    private final Sampler sampler = Sampler.create(new Sampler.Descriptor(GL_LINEAR, GL_LINEAR,
            GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE));
    private float intensity;
    private float rotationRadians;

    public EnvironmentBackgroundRenderer(PbrEnvironment environment) {
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
        intensity = environment.intensity();
        rotationRadians = environment.rotationRadians();
    }

    public void render(CommandBuffer cmd, Camera camera, int width, int height) {
        Matrix4f inverseProjection = camera instanceof ExternalCamera external
                ? external.inverseProjection()
                : new Matrix4f().perspective((float) Math.toRadians(camera.zoom()),
                width / (float) Math.max(1, height), 0.1f, 100.0f).invert();
        Matrix4f inverseViewRotation = camera.getViewMatrix().m30(0.0f).m31(0.0f).m32(0.0f).invert();
        cmd.bindShader(shader)
                .enableBlend(false).enableDepthTest(false).depthMask(false)
                .bindTextureCube(UNIT, environment.radiance(), sampler)
                .setUniformInt(shader, "uEnvironment", UNIT)
                .setUniformFloat(shader, "uIntensity", intensity)
                .setUniformFloat(shader, "uRotation", rotationRadians)
                .setUniformMat4(shader, "uInverseProjection", inverseProjection)
                .setUniformMat4(shader, "uInverseViewRotation", inverseViewRotation)
                .bindVertexArray(vertexArray.id())
                .drawArrays(GL_TRIANGLES, 0, 3);
    }

    /** Per-pipeline values; the borrowed environment object is never mutated. */
    public void visualOverride(float intensity, float rotationRadians) {
        if (!Float.isFinite(intensity) || intensity < 0.0f) {
            throw new IllegalArgumentException("environment intensity must be finite and non-negative");
        }
        if (!Float.isFinite(rotationRadians)) {
            throw new IllegalArgumentException("environment rotation must be finite");
        }
        this.intensity = intensity;
        this.rotationRadians = rotationRadians;
    }

    @Override
    public void close() {
        sampler.close();
        vertexArray.close();
        shader.close();
    }
}
