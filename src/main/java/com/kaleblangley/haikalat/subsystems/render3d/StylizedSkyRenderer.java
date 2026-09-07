package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Full-screen procedural sky used by the outdoor sample; it has no texture ownership. */
final class StylizedSkyRenderer implements GlResource {
    private final ShaderProgram shader = ShaderProgram.fromResource(StylizedSkyRenderer.class,
            "/shaders/postprocess/screen-quad.vert", "/shaders/render3d/stylized-sky.frag");
    private final ScreenQuad quad = new ScreenQuad();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseViewRotation = new Matrix4f();
    private boolean closed;

    void render(CommandBuffer cmd, Camera camera, int width, int height,
                StylizedSkySettings settings) {
        inverseProjection.set(CameraProjection.stable(camera, Math.max(1, width), Math.max(1, height),
                new Matrix4f())).invert();
        inverseViewRotation.set(camera.getViewMatrix()).m30(0.0f).m31(0.0f).m32(0.0f).invert();
        Vector3f sunDirection = settings.sunDirection();
        cmd.bindShader(shader)
                .enableBlend(false).enableDepthTest(false).depthMask(false)
                .enableFramebufferSrgb(false)
                .setUniformMat4(shader, "uInverseProjection", inverseProjection)
                .setUniformMat4(shader, "uInverseViewRotation", inverseViewRotation)
                .setUniformVec3(shader, "uZenithColor", settings.zenithColor())
                .setUniformVec3(shader, "uHorizonColor", settings.horizonColor())
                .setUniformVec3(shader, "uNadirColor", settings.nadirColor())
                .setUniformVec3(shader, "uSunDirection", sunDirection)
                .setUniformVec3(shader, "uSunColor", settings.sunColor())
                .setUniformFloat(shader, "uSunIntensity", settings.sunIntensity())
                .setUniformFloat(shader, "uSunAngularRadius", settings.sunAngularRadius())
                .setUniformFloat(shader, "uHaloIntensity", settings.haloIntensity())
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6);
    }

    @Override public int id() { return shader.id(); }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        quad.close();
        shader.close();
        closed = true;
    }
}
