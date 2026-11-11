package com.kaleblangley.haikalat.subsystems.postprocess.film;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.FilmGrainSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.ScreenQuad;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

/** Display-referred film-grain pass with a deterministic seed per recorded frame. */
public final class FilmGrainPass implements GlResource {
    private final ShaderProgram program;
    private final ScreenQuad quad;
    private final FilmGrainSettings settings;
    private long frameIndex;
    private boolean closed;

    public FilmGrainPass(FilmGrainSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (!settings.active()) {
            throw new IllegalArgumentException("FilmGrainPass requires active grain");
        }
        ShaderProgram createdProgram = null;
        ScreenQuad createdQuad = null;
        try {
            createdProgram = ShaderProgram.fromResource(FilmGrainPass.class,
                    "/shaders/postprocess/screen-quad.vert",
                    "/shaders/postprocess/film-grain.frag");
            createdQuad = new ScreenQuad();
        } catch (RuntimeException failure) {
            closeSuppressed(createdQuad, failure);
            closeSuppressed(createdProgram, failure);
            throw failure;
        }
        program = createdProgram;
        quad = createdQuad;
    }

    public FilmGrainSettings settings() {
        return settings;
    }

    public CommandBuffer recordIntoCurrentTarget(CommandBuffer cmd, int colorTexture,
                                                 int width, int height) {
        ensureOpen();
        if (colorTexture <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("grain input and viewport must be valid");
        }
        int seed = settings.seed() + (int) (frameIndex * 0x9E3779B9L);
        frameIndex++;
        return cmd.enableBlend(false)
                .enableDepthTest(false)
                .enableCullFace(false)
                .enableFramebufferSrgb(false)
                .bindShader(program)
                .bindTexture(0, colorTexture)
                .setUniformInt(program, "uColor", 0)
                .setUniformVec2(program, "uViewportSize", width, height)
                .setUniformFloat(program, "uIntensity", settings.intensity())
                .setUniformFloat(program, "uResponse", settings.response())
                .setUniformInt(program, "uSeed", seed)
                .bindVertexArray(quad.id())
                .drawArrays(GL_TRIANGLES, 0, 6)
                .enableDepthTest(true);
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
        RuntimeException failure = null;
        try {
            quad.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            program.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        closed = true;
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("FilmGrainPass is closed");
    }

    private static void closeSuppressed(AutoCloseable resource, RuntimeException primary) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
