package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the shader side of the displacement contract: one identical bounded
 * block in every scene vertex stage, a default-off gate before any arithmetic,
 * and the fixed morph -> displacement -> skinning order.
 */
class VertexDisplacementShaderTest {
    private static final String FORWARD = "/shaders/render3d/pbr/pbr-forward.vert";
    private static final String SURFACE = "/shaders/render3d/surface/scene-surface.vert";
    private static final String SHADOW = "/shaders/shadows/directional-depth.vert";
    private static final String MASKED_SHADOW = "/shaders/shadows/masked-directional-depth.vert";
    private static final String GTAO_DEPTH = "/shaders/render3d/gtao/gtao-depth.vert";
    private static final String GTAO_MASKED_DEPTH = "/shaders/render3d/gtao/gtao-masked-depth.vert";
    private static final List<String> VERTEX_SHADERS = List.of(FORWARD, SURFACE, SHADOW,
            MASKED_SHADOW, GTAO_DEPTH, GTAO_MASKED_DEPTH);

    @Test
    void everySceneVertexStageSharesTheSameDisplacementBlock() throws IOException {
        String expected = block(source(VERTEX_SHADERS.get(0)));
        for (String path : VERTEX_SHADERS) {
            String source = source(path);
            assertEquals(expected, block(source), "displacement block drifted in " + path);
            assertTrue(source.contains("vertexDisplacementPosition("),
                    "vertex stage must consume the displacement position: " + path);
            assertTrue(source.contains("uniform float uDisplacementPreviousTime"),
                    "shared block must expose the previous time: " + path);
        }
        assertTrue(expected.contains("uniform int uDisplacementEnabled"));
        assertTrue(expected.contains("uniform vec3 uDisplacementAxis"));
        assertTrue(expected.contains("uniform vec3 uDisplacementWaveDirection"));
        assertTrue(expected.contains("uDisplacementAmplitude <= 0.0"));
        assertEquals(3, occurrences(expected, "uDisplacementEnabled == 0 || uDisplacementAmplitude <= 0.0"),
                "offset, position and normal must each have an explicit no-op gate");
        assertFalse(expected.contains("texture("),
                "first vertex-displacement version must not sample a texture");
        assertFalse(expected.contains("gl_FragCoord"),
                "displacement must not depend on the fragment screen position");
    }

    @Test
    void forwardAndSurfaceApplyMorphBeforeDisplacementBeforeSkinning() throws IOException {
        String forward = source(FORWARD);
        int morph = forward.indexOf("applyMorph(position, localNormal, localTangent);");
        int displace = forward.indexOf("position = vertexDisplacementPosition(restPosition");
        int skin = forward.indexOf("mat4 modelSkin = uModel * skinMatrix();");
        assertTrue(morph >= 0 && displace > morph && skin > displace,
                "forward order must be morph -> displacement -> skinning");

        String surface = source(SURFACE);
        int surfaceMorph = surface.indexOf("applyMorph(position, localNormal, localTangent, false);");
        int surfaceDisplace = surface.indexOf("position = vertexDisplacementPosition(restPosition");
        int surfaceSkin = surface.indexOf("mat4 currentModelSkin = uModel * skinMatrixCurrent();");
        assertTrue(surfaceMorph >= 0 && surfaceDisplace > surfaceMorph && surfaceSkin > surfaceDisplace,
                "surface order must be morph -> displacement -> skinning");
        assertTrue(surface.indexOf("vertexDisplacementPosition(previousRestPosition, uDisplacementPreviousTime)")
                        > surface.indexOf("if (hasPrevious)"),
                "previous displacement must be evaluated only with committed history");
    }

    @Test
    void depthStagesDisplaceTheSameMorphedPosition() throws IOException {
        for (String path : List.of(SHADOW, MASKED_SHADOW, GTAO_DEPTH, GTAO_MASKED_DEPTH)) {
            String source = source(path);
            assertTrue(source.contains("vertexDisplacementPosition(morphPosition(), uDisplacementTime)"),
                    "depth stage must displace the morphed local position: " + path);
            assertTrue(source.contains("skinMatrix() * vec4(position, 1.0)"),
                    "depth stage must skin after displacement: " + path);
        }
    }

    private static String block(String source) {
        int start = source.indexOf("// BEGIN VERTEX_DISPLACEMENT");
        int end = source.indexOf("// END VERTEX_DISPLACEMENT");
        assertTrue(start >= 0 && end > start, "missing displacement block");
        return source.substring(start, end + "// END VERTEX_DISPLACEMENT".length());
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

    private static String source(String path) throws IOException {
        try (InputStream input = VertexDisplacementShaderTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing shader " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
