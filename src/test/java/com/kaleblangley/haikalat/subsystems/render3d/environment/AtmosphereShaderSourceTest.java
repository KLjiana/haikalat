package com.kaleblangley.haikalat.subsystems.render3d.environment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression contract for the real-GL shader composition failure.
 *
 * <p>The first Orchestrator {@code glSmoke} run failed with
 * {@code C0204: version directive must be first statement and may not be repeated}
 * because the Java composer prepended {@code #version 460 core} to body files
 * that still declared their own version.  These JVM cases pin the composed
 * source to exactly one leading version directive without needing a GL context.</p>
 */
class AtmosphereShaderSourceTest {
    private static final String COMMON = "/shaders/render3d/atmosphere/atmosphere-common.glsl";
    private static final List<String> BODIES = List.of(
            "/shaders/render3d/atmosphere/atmosphere-transmittance.comp",
            "/shaders/render3d/atmosphere/atmosphere-sky-view.comp",
            "/shaders/render3d/atmosphere/atmosphere-sky.frag");

    @Test
    void composedSourcesHaveExactlyOneLeadingVersionDirective() {
        for (String path : BODIES) {
            String body = resource(path);
            assertTrue(body.startsWith("#version 460 core"),
                    "body must keep its readable version for standalone inspection: " + path);
            String composed = AtmosphereSkyPass.composeShaderSource(path);
            assertTrue(composed.startsWith("#version 460 core\n"),
                    "composed source must start with the sole version: " + path);
            assertEquals(1, occurrences(composed, "#version"),
                    "composed source must contain exactly one version: " + path);
            assertTrue(composed.contains("void main()"),
                    "composed source must keep the shader body: " + path);
            assertTrue(composed.contains("ATMOSPHERE_PI"),
                    "composed source must include the common helpers: " + path);
        }
    }

    @Test
    void commonIncludeDoesNotDeclareItsOwnVersion() {
        String common = resource(COMMON);
        assertFalse(common.contains("#version"),
                "the shared include is concatenated after the version line");
    }

    @Test
    void versionStrippingToleratesLineEndingsBlankPreludeAndBom() {
        assertEquals("void main() {}\n", AtmosphereSkyPass.stripVersionDirective(
                "#version 460 core\nvoid main() {}\n"));
        assertEquals("void main() {}\r\n", AtmosphereSkyPass.stripVersionDirective(
                "#version 460 core\r\nvoid main() {}\r\n"));
        assertEquals("void main() {}\n", AtmosphereSkyPass.stripVersionDirective(
                "\n#version 460 core\nvoid main() {}\n"));
        assertEquals("void main() {}\n", AtmosphereSkyPass.stripVersionDirective(
                "\uFEFF#version 460 core\nvoid main() {}\n"));
        assertEquals("void main() {}", AtmosphereSkyPass.stripVersionDirective(
                "void main() {}"));
        assertEquals("", AtmosphereSkyPass.stripVersionDirective("#version 460 core"));
    }

    private static String resource(String path) {
        try (InputStream stream = AtmosphereSkyPass.class.getResourceAsStream(path)) {
            if (stream == null) throw new AssertionError("missing shader resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read shader resource " + path, failure);
        }
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
