package com.kaleblangley.haikalat.subsystems.postprocess.vrs;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingRate;
import com.kaleblangley.haikalat.backend.vrs.VariableRateShadingState;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessPipeline;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Shader and public-API contract for feature #42.
 *
 * <p>VRS is a raster-state feature: it must not require a shader extension or a
 * separate shader variant.  What the declared consumer shader must do instead
 * is avoid implicit-derivative sampling, because a coarse fragment footprint
 * changes the invocation and therefore the LOD basis.  This test freezes that
 * contract together with the API surface the pipeline and executor rely on.</p>
 */
class VariableRateShadingShaderContractTest {
    private static String resource(String path) throws IOException {
        try (InputStream stream = VariableRateShadingShaderContractTest.class
                .getResourceAsStream(path)) {
            assertNotNull(stream, "missing classpath resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void consumerShaderUsesExplicitLodAndNeedsNoVrsShaderExtension() throws IOException {
        String shader = resource("/shaders/postprocess/subsurface-scattering.frag");
        assertTrue(shader.contains("#version 460 core"), "declared consumer shader must exist");
        assertFalse(shader.contains("#extension"), "VRS must not require a shader extension");
        assertFalse(shader.contains("GL_NV_shading_rate_image"),
                "shader must not know the NV extension");
        assertFalse(shader.contains("GL_EXT_fragment_shading_rate"),
                "shader must not know the EXT extension");
        assertTrue(shader.contains("gl_FragCoord"),
                "the consumer documents its full-rate gl_FragCoord dependency");
        assertEquals(4, occurrences(shader, "textureLod("),
                "colour/depth centre and tap samples must use explicit LOD 0");
        assertEquals(0, implicitTextureSampling(shader),
                "implicit-derivative texture() calls are unsafe under coarse shading");
    }

    @Test
    void commandBufferApiExposesAPairedTypedScope() throws Exception {
        var begin = CommandBuffer.class.getMethod("beginVariableRateShading",
                Texture2D.class, int[].class);
        assertEquals(CommandBuffer.class, begin.getReturnType());
        assertNotNull(CommandBuffer.class.getMethod("endVariableRateShading"));
        assertTrue(begin.getParameterTypes()[1].isArray(),
                "palette must be carried as an int[] payload");
    }

    @Test
    void stateAdapterExposesCaptureRestoreAndPaletteQueries() throws Exception {
        assertNotNull(VariableRateShadingState.class.getMethod("boundImage"));
        assertNotNull(VariableRateShadingState.class.getMethod("palette", int.class, int.class));
        assertNotNull(VariableRateShadingState.class.getMethod("begin", int.class, int[].class));
        Class<?> snapshot = Class.forName(
                VariableRateShadingState.class.getName() + "$Snapshot");
        assertNotNull(snapshot.getMethod("restore"));
        assertNotNull(snapshot.getMethod("previousImage"));
        assertNotNull(snapshot.getMethod("isRestored"));
    }

    @Test
    void pipelineExposesEffectiveStatusAndTheRequest() throws Exception {
        assertNotNull(PostProcessPipeline.class.getMethod("variableRateShadingActive"));
        assertNotNull(PostProcessPipeline.class.getMethod("variableRateShadingStatus"));
        assertNotNull(PostProcessPipeline.class.getMethod("variableRateShadingRequest"));
        assertNotNull(PostProcessSettings.class.getMethod("withVariableRateShading",
                VariableRateShadingSettings.class));
        assertNotNull(PostProcessSettings.Builder.class.getMethod("variableRateShading",
                VariableRateShadingSettings.class));
    }

    @Test
    void passApiKeepsTheScopeAroundTheConsumerDraw() throws Exception {
        assertNotNull(VariableRateShadingPass.class.getMethod("recordBegin", CommandBuffer.class));
        assertNotNull(VariableRateShadingPass.class.getMethod("recordEnd", CommandBuffer.class));
        assertNotNull(VariableRateShadingPass.class.getMethod("prepareResize",
                int.class, int.class));
        assertNotNull(VariableRateShadingPass.class.getMethod("commitResize",
                VariableRateShadingPass.ResizeCandidate.class));
        assertEquals(Set.of("ONE_BY_ONE", "ONE_BY_TWO", "TWO_BY_ONE", "TWO_BY_TWO",
                        "TWO_BY_FOUR", "FOUR_BY_TWO", "FOUR_BY_FOUR"),
                Arrays.stream(VariableRateShadingRate.values())
                        .map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void probeShaderUploadsAreDeclaredAndReferenced() throws IOException {
        String shader = VariableRateShadingGlTest.VRS_PROBE_FRAGMENT;
        Set<String> declared = uniformNames(shader);
        Set<String> uploaded = uploadedUniforms(
                Files.readString(Path.of("src", "test", "java", "com", "kaleblangley",
                        "haikalat", "subsystems", "postprocess", "vrs",
                        "VariableRateShadingGlTest.java")));
        assertEquals(Set.of("uThinLineX", "uAlphaGradient"), declared,
                "the probe shader must declare exactly the uniforms it consumes");
        assertEquals(declared, uploaded,
                "every probe uniform upload must match a declared shader uniform");
        for (String name : uploaded) {
            assertTrue(countIdentifier(shader, name) >= 2,
                    name + " is uploaded but never referenced; GLSL will optimize it out");
        }
    }

    @Test
    void subsurfaceConsumerUploadsAreDeclaredAndReferenced() throws IOException {
        String shader = resource("/shaders/postprocess/subsurface-scattering.frag");
        String java = Files.readString(Path.of("src", "main", "java", "com",
                "kaleblangley", "haikalat", "subsystems", "postprocess", "sss",
                "SubsurfaceScatteringPass.java"));
        Set<String> declared = uniformNames(shader);
        Set<String> uploaded = uploadedUniforms(java);
        assertFalse(uploaded.isEmpty(), "the consumer must upload its uniforms");
        for (String name : uploaded) {
            assertTrue(declared.contains(name),
                    name + " is uploaded but not declared by subsurface-scattering.frag");
            assertTrue(countIdentifier(shader, name) >= 2,
                    name + " is declared but never referenced; GLSL will optimize it out");
        }
        assertEquals(declared, uploaded,
                "the consumer upload set and the shader uniform set must agree");
    }

    private static Set<String> uniformNames(String shader) {
        Matcher matcher = Pattern.compile("uniform\\s+\\w+\\s+([A-Za-z0-9_]+)\\s*;")
                .matcher(shader);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    private static Set<String> uploadedUniforms(String javaSource) {
        Matcher matcher = Pattern.compile(
                "setUniform\\w+\\((?:shader|program),\\s*\"([A-Za-z0-9_]+)\"")
                .matcher(javaSource);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    private static int countIdentifier(String source, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** Counts {@code texture(} calls while excluding {@code textureLod(}/{@code texelFetch(}. */
    private static int implicitTextureSampling(String shader) {
        int count = 0;
        int index = 0;
        while ((index = shader.indexOf("texture(", index)) >= 0) {
            count++;
            index += "texture(".length();
        }
        return count;
    }
}
