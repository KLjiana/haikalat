package com.kaleblangley.haikalat.subsystems.render3d.water;

import com.kaleblangley.haikalat.backend.GlException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Composes the surface and volume stages with the same finite water domain. */
final class WaterShaderSource {
    private static final String DOMAIN = "/shaders/render3d/water/water-domain.glsl";

    private WaterShaderSource() { }

    static String fragment(String path, String defines) {
        String body = read(path);
        int versionEnd = body.indexOf('\n');
        if (!body.startsWith("#version") || versionEnd < 0) {
            throw new GlException("missing GLSL version in " + path);
        }
        return body.substring(0, versionEnd + 1) + defines + read(DOMAIN)
                + "\n" + body.substring(versionEnd + 1);
    }

    private static String read(String path) {
        try (var input = WaterShaderSource.class.getResourceAsStream(path)) {
            if (input == null) throw new GlException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new GlException("cannot read shader " + path, failure);
        }
    }
}
