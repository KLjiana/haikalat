package com.kaleblangley.haikalat.subsystems.postprocess;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Reconstructs world position from scene depth and applies distance/height fog in HDR space. */
public final class FogPass implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final Vector3f fogColor = new Vector3f();
    private boolean closed;

    public FogPass() {
        program = ShaderProgram.fromResource(FogPass.class,
                "/postprocess/screen_quad.vert", "/postprocess/distance_height_fog.frag");
        quad = new ScreenQuad();
    }

    public CommandBuffer recordIntoCurrentTarget(CommandBuffer commands, int sceneTexture,
                                                  int depthTexture, Matrix4f inverseViewProjection,
                                                  Vector3f cameraPosition, FogSettings settings) {
        ensureOpen();
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(inverseViewProjection, "inverseViewProjection");
        Objects.requireNonNull(cameraPosition, "cameraPosition");
        Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            throw new IllegalArgumentException("fog pass requires enabled settings");
        }
        if (sceneTexture <= 0 || depthTexture <= 0) {
            throw new IllegalArgumentException("fog pass requires scene and depth textures");
        }
        commands.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, sceneTexture)
                .bindTexture(1, depthTexture)
                .setUniformInt(program, "uScene", 0)
                .setUniformInt(program, "uDepth", 1)
                .setUniformMat4(program, "uInverseViewProjection", inverseViewProjection)
                .setUniformVec3(program, "uCameraPosition", cameraPosition)
                .setUniformVec3(program, "uFogColor",
                        fogColor.set(settings.red(), settings.green(), settings.blue()))
                .setUniformFloat(program, "uDistanceDensity", settings.distanceDensity())
                .setUniformFloat(program, "uHeightDensity", settings.heightDensity())
                .setUniformFloat(program, "uHeightFalloff", settings.heightFalloff())
                .setUniformFloat(program, "uBaseHeight", settings.baseHeight())
                .setUniformFloat(program, "uMaximumOpacity", settings.maximumOpacity())
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
        return commands;
    }

    /** Pure-JVM reference for deterministic tuning and tests. */
    public static float fogAmount(float distance, float worldHeight, float cameraHeight,
                                  FogSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (!Float.isFinite(distance) || distance < 0.0f
                || !Float.isFinite(worldHeight) || !Float.isFinite(cameraHeight)) {
            throw new IllegalArgumentException("fog sample inputs must be finite and distance non-negative");
        }
        if (!settings.enabled() || distance == 0.0f) return 0.0f;
        double heightDelta = worldHeight - cameraHeight;
        double falloffDelta = settings.heightFalloff() * heightDelta;
        double densityExponent = Math.max(-80.0, Math.min(80.0,
                -settings.heightFalloff() * (cameraHeight - settings.baseHeight())));
        double cameraDensity = Math.exp(densityExponent);
        double averageHeightDensity = Math.abs(falloffDelta) < 1.0e-5
                ? cameraDensity
                : cameraDensity * -Math.expm1(-falloffDelta) / falloffDelta;
        double opticalDepth = settings.distanceDensity() * distance
                + settings.heightDensity() * distance * averageHeightDensity;
        return (float) Math.min(settings.maximumOpacity(), -Math.expm1(-opticalDepth));
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
        if (closed) throw new GlException("Fog pass is closed");
    }
}
