package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import com.kaleblangley.haikalat.backend.GlException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the two DDGI fragment programs with the shared atlas/grid helper source
 * injected after their {@code #version} line.
 *
 * <p>The engine has no general {@code #include} preprocessor, and the probe
 * atlas layout must stay identical between the relight producer and the
 * indirect query consumer. Keeping the layout, face basis and sampling helpers
 * in one resource file gives a single frozen shader contract instead of two
 * hand-copied blocks. The injected snippet declares no {@code #version} line of
 * its own, so it is never compiled standalone.</p>
 */
final class DdgiShaderSource {
    static final String ROOT = "/shaders/render3d/ddgi/";
    static final String COMMON = "ddgi-common.glsl";

    private DdgiShaderSource() {
    }

    /** @return the shared DDGI GLSL helper source */
    static String commonSource() {
        return read(COMMON);
    }

    /**
     * @param name fragment file name below {@code /shaders/render3d/ddgi/}
     * @return the version line followed by the shared helpers and the shader body
     */
    static String fragmentSource(String name) {
        String body = read(name);
        if (!body.startsWith("#version")) {
            throw new GlException("missing GLSL version in DDGI shader " + name);
        }
        int versionEnd = body.indexOf('\n');
        if (versionEnd < 0) {
            throw new GlException("DDGI shader " + name + " has no body");
        }
        return body.substring(0, versionEnd + 1)
                + "// BEGIN DDGI SHARED ATLAS CONTRACT\n"
                + commonSource()
                + "\n// END DDGI SHARED ATLAS CONTRACT\n"
                + body.substring(versionEnd + 1);
    }

    private static String read(String name) {
        try (InputStream input = DdgiShaderSource.class.getResourceAsStream(ROOT + name)) {
            if (input == null) {
                throw new GlException("missing DDGI shader resource " + ROOT + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new GlException("cannot read DDGI shader resource " + ROOT + name, failure);
        }
    }
}
