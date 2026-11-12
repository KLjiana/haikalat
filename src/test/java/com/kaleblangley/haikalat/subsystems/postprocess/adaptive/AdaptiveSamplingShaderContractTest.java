package com.kaleblangley.haikalat.subsystems.postprocess.adaptive;

import com.kaleblangley.haikalat.subsystems.postprocess.AdaptiveSamplingSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.SsrSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-layer contract for the adaptive SSR shader variant.
 *
 * <p>The real-GL test proves pixels; this JVM case pins the bounded shader
 * shape, the documented allocation formula, the stability fallback, the
 * diagnostic switch and the fact that the fixed-budget {@code ssr.frag} is not
 * modified by the adaptive feature.  It also checks that every uniform the
 * adaptive shader declares is bound by {@link com.kaleblangley.haikalat.subsystems.postprocess.ssr.SsrPass},
 * so a missing uniform cannot reach a GPU run.</p>
 */
class AdaptiveSamplingShaderContractTest {
    private static final Pattern UNIFORM = Pattern.compile(
            "^\\s*uniform\\s+\\w+\\s+(\\w+)\\s*;", Pattern.MULTILINE);

    @Test
    void adaptiveShaderKeepsTheBoundedMeasuredBudgetShape() throws IOException {
        String source = resource("/shaders/postprocess/ssr-adaptive.frag");
        assertTrue(source.contains("#version 460 core"));
        assertTrue(source.contains("#define SSR_MAX_STEPS 64"),
                "the loop bound must match SsrSettings.MAXIMUM_STEPS");
        assertTrue(source.contains("#define SSR_REFINE_STEPS 4"),
                "the bisection refinement must stay bounded");
        assertTrue(source.contains("#define SSR_TILE_PROBE_COUNT 4"),
                "the tile probe count is a compile-time bound");
        for (String uniform : Set.of("uMinimumSteps", "uMaximumSteps", "uErrorThreshold",
                "uTileSizePixels", "uScreenStepScalePixels", "uDepthJumpScale",
                "uBudgetDebug")) {
            assertTrue(source.contains("uniform ") && source.contains(uniform),
                    "the adaptive shader must declare " + uniform);
        }
        assertTrue(source.contains("float tileErrorEstimate()"),
                "the per-tile variance estimate must be a named function");
        assertTrue(source.contains("int budgetForError(float error)"),
                "the allocation formula must be a named function");
        assertTrue(source.contains("void marchReflection("),
                "the coarse and final marches must share the bounded march function");
        assertTrue(source.contains("floor(normalized * span + 0.5)"),
                "rounding must match AdaptiveSamplingSettings.budgetFor");
        assertTrue(source.contains("refinedHitT > 0.0 || coarseHitT <= 0.0"),
                "extra budget must never discard a coarse hit");
        assertTrue(source.contains("if (budget > floorSteps)"),
                "the finer march must only run when the measured error earned it");
        assertTrue(source.contains("FragColor = vec4(base, 1.0);"),
                "the fallback must write the input colour exactly");
        assertTrue(source.contains("base + weight * (hitRadiance - environmentRadiance)"),
                "zero weight must leave the forward environment colour untouched");
        assertTrue(source.contains("uBudgetDebug != 0"),
                "the budget readback must be an explicit default-off diagnostic");
        assertFalse(source.contains("while ("),
                "an unbounded loop would break the cost contract");
        assertFalse(source.contains("imageStore"),
                "the adaptive shader must not add a second unordered write path");
        assertFalse(source.contains("gl_FragCoord.xy ="),
                "the shader must not write its fragment coordinate");
        assertEquals(1, occurrences(source, "step <= SSR_MAX_STEPS"),
                "each march must be clamped by the compile-time budget");
    }

    @Test
    void fixedBudgetShaderStaysTheExactDisabledAndControlPath() throws IOException {
        String baseline = resource("/shaders/postprocess/ssr.frag");
        assertTrue(baseline.contains("uniform int uStepCount;"),
                "the fixed path keeps its explicit step count");
        assertTrue(baseline.contains("step <= SSR_MAX_STEPS"));
        for (String adaptiveOnly : Set.of("uMinimumSteps", "uMaximumSteps",
                "uErrorThreshold", "uTileSizePixels", "uScreenStepScalePixels",
                "uDepthJumpScale", "uBudgetDebug", "tileErrorEstimate", "budgetForError")) {
            assertFalse(baseline.contains(adaptiveOnly),
                    "the fixed shader must not carry adaptive-only state: " + adaptiveOnly);
        }
    }

    @Test
    void everyAdaptiveUniformIsBoundByThePass() throws IOException {
        String shader = resource("/shaders/postprocess/ssr-adaptive.frag");
        String pass = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main",
                "java", "com", "kaleblangley", "haikalat", "subsystems", "postprocess",
                "ssr", "SsrPass.java"));
        Matcher matcher = UNIFORM.matcher(shader);
        Set<String> uniforms = new LinkedHashSet<>();
        while (matcher.find()) {
            uniforms.add(matcher.group(1));
        }
        assertTrue(uniforms.size() >= 25, "the adaptive shader must declare its full input set");
        for (String uniform : uniforms) {
            assertTrue(pass.contains("\"" + uniform + "\""),
                    "SsrPass must bind adaptive uniform " + uniform);
        }
    }

    @Test
    void settingsBoundsMatchTheShaderConstants() {
        assertEquals(64, SsrSettings.MAXIMUM_STEPS);
        assertEquals(SsrSettings.MAXIMUM_STEPS,
                AdaptiveSamplingSettings.MAXIMUM_STEP_CEILING);
        assertEquals(64, AdaptiveSamplingSettings.MAXIMUM_TILE_SIZE);
        assertEquals(8, AdaptiveSamplingSettings.disabled().minimumSteps());
        assertEquals(32, AdaptiveSamplingSettings.disabled().maximumSteps());
        assertTrue(AdaptiveSamplingSettings.disabled().maximumSteps()
                <= SsrSettings.disabled().maxSteps(),
                "the default adaptive ceiling stays inside the default SSR hard bound");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String resource(String path) throws IOException {
        try (var stream = AdaptiveSamplingShaderContractTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
