package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;

import java.util.function.BiFunction;

/** Owns the caster programs shared by the directional, point and spot passes. */
public final class ShadowPrograms implements AutoCloseable {
    private ShaderProgram opaque;
    private ShaderProgram masked;
    private ShaderProgram instanced;
    private boolean closed;

    private ShadowPrograms() {}

    /** Returns the compatible donor for the generation to borrow, or a new program group. */
    public static ShadowPrograms prepare(ShadowPrograms donor, boolean enabled, boolean useInstancing) {
        if (!enabled) return null;
        if (donor != null && !donor.closed && (donor.instanced != null) == useInstancing) return donor;
        return create(useInstancing, ShadowPrograms::load);
    }

    static ShadowPrograms create(boolean useInstancing, BiFunction<String, String, ShaderProgram> load) {
        var programs = new ShadowPrograms();
        try {
            programs.opaque = load.apply("directional-depth.vert", "directional-depth.frag");
            programs.masked = load.apply("masked-directional-depth.vert", "masked-directional-depth.frag");
            if (useInstancing) programs.instanced = load.apply("instanced-directional-depth.vert", "directional-depth.frag");
            return programs;
        } catch (RuntimeException | Error failure) {
            try { programs.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static ShaderProgram load(String vertex, String fragment) {
        return ShaderProgram.fromResource(ShadowPrograms.class, "/shaders/shadows/" + vertex,
                "/shaders/shadows/" + fragment);
    }

    ShaderProgram opaque() { return opaque; }
    ShaderProgram masked() { return masked; }
    ShaderProgram instanced() { return instanced; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = close(instanced, null);
        failure = close(masked, failure);
        failure = close(opaque, failure);
        if (failure != null) throw failure;
    }

    private static RuntimeException close(ShaderProgram program, RuntimeException failure) {
        if (program == null) return failure;
        try {
            program.close();
        } catch (RuntimeException cleanup) {
            if (failure == null) return cleanup;
            failure.addSuppressed(cleanup);
        }
        return failure;
    }
}
