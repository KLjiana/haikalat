package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** 固定采样预算的屏幕空间体积聚光受控实验。 */
public final class VolumetricLightPass implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private Statistics statistics = new Statistics(0, 0L);
    private boolean closed;

    public VolumetricLightPass() {
        program = ShaderProgram.fromResource(VolumetricLightPass.class,
                "/postprocess/screen_quad.vert", "/postprocess/volumetric_light.frag");
        quad = new ScreenQuad();
    }

    public Statistics recordIntoCurrentTarget(CommandBuffer commands,
                                               Matrix4fc inverseViewProjection,
                                               Vector3fc cameraPosition,
                                               VolumetricLightSettings settings) {
        ensureOpen();
        CommandBuffer cmd = Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(inverseViewProjection, "inverseViewProjection");
        Objects.requireNonNull(cameraPosition, "cameraPosition");
        VolumetricLightSettings value = Objects.requireNonNull(settings, "settings");
        Vector3f camera = new Vector3f(cameraPosition);
        if (!Float.isFinite(camera.x) || !Float.isFinite(camera.y) || !Float.isFinite(camera.z)) {
            throw new IllegalArgumentException("cameraPosition must contain finite components");
        }

        cmd.materialState(BlendMode.ADDITIVE, false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .setUniformMat4(program, "uInverseViewProjection",
                        new Matrix4f(inverseViewProjection))
                .setUniformVec3(program, "uCameraPosition", camera)
                .setUniformVec3(program, "uLightPosition", value.lightPosition())
                .setUniformVec3(program, "uLightDirection", value.lightDirection())
                .setUniformVec3(program, "uLightColor", value.lightColor())
                .setUniformInt(program, "uSteps", value.steps())
                .setUniformFloat(program, "uMaximumDistance", value.maximumDistance())
                .setUniformFloat(program, "uDensity", value.density())
                .setUniformFloat(program, "uAnisotropy", value.anisotropy())
                .setUniformFloat(program, "uRange", value.range())
                .setUniformFloat(program, "uInnerConeCosine", value.innerConeCosine())
                .setUniformFloat(program, "uOuterConeCosine", value.outerConeCosine())
                .setUniformFloat(program, "uIntensity", value.intensity())
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .materialState(BlendMode.OPAQUE, true);
        statistics = new Statistics(value.steps(),
                Math.incrementExact(statistics.framesRecorded()));
        return statistics;
    }

    public Statistics statistics() {
        ensureOpen();
        return statistics;
    }

    /** 与 shader 相同的 Henyey-Greenstein 相函数，供调参与纯 JVM 测试。 */
    public static float phase(float cosine, float anisotropy) {
        if (!Float.isFinite(cosine) || cosine < -1.0f || cosine > 1.0f
                || !Float.isFinite(anisotropy) || anisotropy <= -0.95f || anisotropy >= 0.95f) {
            throw new IllegalArgumentException("phase inputs are outside supported ranges");
        }
        double g2 = anisotropy * anisotropy;
        double denominator = Math.pow(Math.max(1.0e-4,
                1.0 + g2 - 2.0 * anisotropy * cosine), 1.5);
        return (float) ((1.0 - g2) / (4.0 * Math.PI * denominator));
    }

    @Override
    public int id() {
        return program.id();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        quad.close();
        program.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Volumetric light pass is closed");
    }

    public record Statistics(int samplesPerPixel, long framesRecorded) {
    }
}
