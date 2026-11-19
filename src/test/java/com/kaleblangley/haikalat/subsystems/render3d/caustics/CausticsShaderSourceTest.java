package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM packaging contract for the caustics shader pair.
 *
 * <p>Compiling the shader needs a real GL context; this test only proves the
 * resources are on the runtime classpath and that the multisample variant adds
 * exactly the documented define after the version directive. It cannot replace
 * the guarded real-GL case.</p>
 */
class CausticsShaderSourceTest {
    @Test
    void shaderSourcesArePackagedAndMsaaVariantOnlyAddsTheDefine() {
        assertNotNull(CausticsPass.class.getResource(
                        "/shaders/render3d/caustics/caustics.vert"),
                "caustics vertex shader must be packaged");
        String base = CausticsPass.fragmentSource(false);
        assertTrue(base.startsWith("#version 460 core"), "fragment shader must start with the version");
        assertFalse(base.contains("#define CAUSTICS_MSAA"));
        assertTrue(base.contains("uniform sampler2D uSceneDepth;"));
        assertTrue(base.contains("uniform mat4 uInverseProjection;"));
        assertTrue(base.contains("uniform float uMaximumProjectionDistance;"),
                "the shader must declare every uniform the pass uploads");
        assertTrue(base.contains("travel <= uMaximumProjectionDistance"));
        assertTrue(base.contains("float causticPattern("));
        assertTrue(base.contains("ARTISTIC APPROXIMATION"));

        String multisample = CausticsPass.fragmentSource(true);
        assertEquals("#version 460 core\n#define CAUSTICS_MSAA\n",
                multisample.substring(0, "#version 460 core\n#define CAUSTICS_MSAA\n".length()));
        assertTrue(multisample.contains("uniform sampler2DMS uSceneDepth;"));
        assertEquals(1, occurrences(multisample, "#define CAUSTICS_MSAA"));
        assertEquals(base.length() + "#define CAUSTICS_MSAA\n".length(), multisample.length(),
                "the MSAA variant must only inject the define");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
