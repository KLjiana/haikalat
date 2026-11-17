package com.kaleblangley.haikalat.subsystems.render3d.hair;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL-free contracts of the #46 strand shaders and their Java upload set.
 *
 * <p>These checks catch the failure class that can only be seen on a GPU:
 * a uniform declared in GLSL but never referenced is optimized out of the
 * linked program, and the Java upload then fails at draw time. They also pin
 * the screen-space ribbon expansion, the Kajiya-Kay lobes and the shadow
 * light-space convention so the real-GL test exercises the intended branch.</p>
 */
class HairStrandShaderTest {
    private static final String FORWARD_VERTEX = "/shaders/render3d/hair/hair-forward.vert";
    private static final String FORWARD_FRAGMENT = "/shaders/render3d/hair/hair-forward.frag";
    private static final String SHADOW_VERTEX = "/shaders/render3d/hair/hair-shadow.vert";
    private static final String SHADOW_FRAGMENT = "/shaders/render3d/hair/hair-shadow.frag";
    private static final String VELOCITY_VERTEX = "/shaders/render3d/hair/hair-velocity.vert";
    private static final String VELOCITY_FRAGMENT = "/shaders/render3d/hair/hair-velocity.frag";
    private static final Pattern DECLARED_UNIFORM = Pattern.compile(
            "(?m)^\\s*uniform\\s+\\w+\\s+(u[A-Za-z0-9_]+)\\s*;");
    private static final Pattern UPLOADED_UNIFORM = Pattern.compile(
            "setUniform(?:Int|Float|Vec2|Vec3|Vec4|Mat4)\\(\\w+,\\s*\"(u[A-Za-z0-9_]+)\"");

    @Test
    void vertexStageExpandsAStrandRibbonInScreenSpaceAndClampsItsWidth() throws IOException {
        String source = resource(FORWARD_VERTEX);
        for (String token : new String[]{
                "layout(location = 0) in vec3 aPosition;",
                "layout(location = 1) in vec3 aTangent;",
                "layout(location = 2) in float aStrandParameter;",
                "layout(location = 3) in float aCorner;",
                "layout(location = 4) in float aRandom;",
                "uniform vec2 uViewportSize;",
                "uniform float uMinimumWidthPixels;",
                "uniform float uMaximumWidthPixels;",
                "uniform float uWidthScale;",
                "vec2 pixelDelta = (ndcProbe - ndcCenter) * 0.5 * uViewportSize;",
                "clamp(uWidthPixels * uWidthScale,",
                "aCorner * widthPixels / uViewportSize",
                "cross(tangent, viewDirection)"}) {
            assertTrue(source.contains(token), "missing ribbon-expansion token: " + token);
        }
        assertFalse(source.contains("aTexCoord"),
                "strand geometry must not be a textured camera-facing card");
        assertFalse(source.contains("uCameraRight") || source.contains("uCameraUp"),
                "the ribbon must follow the generated tangent, not a screen-aligned card basis");
        assertFalse(source.contains("billboard"), "no billboard/card path is allowed");
    }

    @Test
    void fragmentStageImplementsKajiyaKayLobesAndBoundedShadowPcf() throws IOException {
        String source = resource(FORWARD_FRAGMENT);
        for (String token : new String[]{
                "float sinTangentLight = sqrt(max(1.0 - tangentDotLight * tangentDotLight, 0.0));",
                "float sinTangentHalf = sqrt(max(1.0 - tangentDotHalf * tangentDotHalf, 0.0));",
                "float primary = pow(sinTangentHalf, uPrimaryExponent);",
                "float secondary = pow(sinTangentHalf, uSecondaryExponent) * uSecondaryWeight;",
                "vec3 specular = radiance * ((primary + secondary) * uSpecularStrength);",
                "float profile = mix(0.55, 1.0, smoothstep(0.0, 0.3, vStrandParameter));",
                "if (uTransparencyMode == 0 && alpha < uAlphaCutoff) {",
                "float directionalShadow() {",
                "uShadowTexelSize",
                "lit / 9.0",
                "vec3 lightDirection = normalize(-uLightTravelDirection);"}) {
            assertTrue(source.contains(token), "missing Kajiya-Kay/shadow token: " + token);
        }
        assertFalse(source.contains("sampler2D uBaseColorMap")
                        || source.contains("sampler2D uAlbedoMap"),
                "the first version is an analytic strand shader with no material texture");
        assertTrue(source.contains("uniform sampler2D uDirectionalShadow;"),
                "the receiver must sample the existing directional shadow map");
    }

    @Test
    void shadowStageReusesTheExistingLightSpaceConventionAndExpandsAWorldRibbon()
            throws IOException {
        String vertex = resource(SHADOW_VERTEX);
        assertTrue(vertex.contains("uniform mat4 uLightSpace;"));
        assertTrue(vertex.contains("uniform mat4 uModel;"));
        assertTrue(vertex.contains("gl_Position = uLightSpace * vec4(world, 1.0);"));
        assertTrue(vertex.contains("uniform vec3 uLightTravelDirection;"));
        assertTrue(vertex.contains("uniform float uStrandRadiusMetres;"));
        assertTrue(vertex.contains("world += side * (aCorner * uStrandRadiusMetres);"),
                "the caster must expand the same centreline into a light-facing ribbon");
        assertTrue(vertex.contains("cross(tangent, lightDirection)"));
        assertTrue(vertex.contains("out float vStrandParameter;"));
        String fragment = resource(SHADOW_FRAGMENT);
        assertTrue(fragment.contains("in float vStrandParameter;"));
        assertTrue(fragment.contains("uniform float uAlphaCutoff;"));
        assertTrue(fragment.contains("discard;"));
        assertTrue(fragment.contains("uAlpha * mix(0.55, 1.0, smoothstep(0.0, 0.3, vStrandParameter))"),
                "the caster must use the same strand alpha profile as the forward stage");
    }

    @Test
    void velocityStageDuplicatesTheRibbonExpansionAndEncodesTheStableReprojection()
            throws IOException {
        String vertex = resource(VELOCITY_VERTEX);
        for (String token : new String[]{
                "layout(location = 0) in vec3 aPosition;",
                "layout(location = 1) in vec3 aTangent;",
                "layout(location = 2) in float aStrandParameter;",
                "layout(location = 3) in float aCorner;",
                "layout(location = 4) in float aRandom;",
                "uniform mat4 uCurrentStableViewProjection;",
                "uniform mat4 uPreviousStableViewProjection;",
                "uniform mat4 uModel;",
                "uniform mat4 uPreviousModel;",
                "vec2 pixelDelta = (ndcProbe - ndcCenter) * 0.5 * uViewportSize;",
                "clamp(uWidthPixels * uWidthScale,",
                "aCorner * widthPixels / uViewportSize",
                "gl_Position = clip;",
                "vCurrentClip = clipCenter;",
                "vPreviousClip = previousClip;"}) {
            assertTrue(vertex.contains(token), "missing velocity ribbon token: " + token);
        }
        assertFalse(vertex.contains("uDirectionalLightSpace") || vertex.contains("aTexCoord"),
                "the velocity stage must expand the same centreline ribbon, not add a card or "
                        + "shadow dependency");
        assertFalse(vertex.contains("vWorldPosition"),
                "the velocity stage must not carry the forward shading varyings");

        String fragment = resource(VELOCITY_FRAGMENT);
        for (String token : new String[]{
                "layout(location = 0) out vec2 outVelocity;",
                "vec2 currentUv = (vCurrentClip.xy / vCurrentClip.w) * 0.5 + 0.5;",
                "vec2 previousUv = (vPreviousClip.xy / vPreviousClip.w) * 0.5 + 0.5;",
                "vec2 velocity = previousUv - currentUv;",
                "all(lessThan(abs(velocity), vec2(4.0)))",
                "outVelocity = finiteVelocity ? velocity : vec2(0.0);",
                "if (!(vCurrentClip.w > 0.0) || !(vPreviousClip.w > 0.0)) {",
                "alpha < uAlphaCutoff",
                "float profile = mix(0.55, 1.0, smoothstep(0.0, 0.3, vStrandParameter));"}) {
            assertTrue(fragment.contains(token), "missing velocity encoding token: " + token);
        }
        assertFalse(fragment.contains("FragColor"),
                "the velocity stage must have a single vec2 output and no colour attachment");
        assertFalse(fragment.contains("uRootColor") || fragment.contains("uTipColor"),
                "velocity must not duplicate strand shading");
    }

    @Test
    void everyDeclaredForwardUniformIsReferencedAndUploadedByThePass() throws Exception {
        assertUploadSetMatches("hair-forward", FORWARD_VERTEX, FORWARD_FRAGMENT,
                "public void record(");
    }

    @Test
    void everyDeclaredShadowUniformIsReferencedAndUploadedByThePass() throws Exception {
        assertUploadSetMatches("hair-shadow", SHADOW_VERTEX, SHADOW_FRAGMENT,
                "public void recordShadow(");
    }

    @Test
    void everyDeclaredVelocityUniformIsReferencedAndUploadedByThePass() throws Exception {
        assertUploadSetMatches("hair-velocity", VELOCITY_VERTEX, VELOCITY_FRAGMENT,
                "public void recordVelocity(");
    }

    @Test
    void geometryLayoutMatchesTheVertexAttributeLocations() throws IOException {
        List<VertexAttribute> attributes = HairStrandGeometry.LAYOUT.attributes();
        assertEquals(5, attributes.size());
        for (String path : List.of(FORWARD_VERTEX, VELOCITY_VERTEX)) {
            String vertex = resource(path);
            for (VertexAttribute attribute : attributes) {
                assertTrue(vertex.contains("layout(location = " + attribute.index() + ") in "),
                        path + " is missing attribute location " + attribute.index());
            }
        }
        assertEquals(0, attributes.get(0).index());
        assertEquals(3, attributes.get(0).size());
        assertEquals(0L, attributes.get(0).offsetBytes());
        assertEquals(1, attributes.get(1).index());
        assertEquals(3, attributes.get(1).size());
        assertEquals(2, attributes.get(2).index());
        assertEquals(1, attributes.get(2).size());
        assertEquals(3, attributes.get(3).index());
        assertEquals(1, attributes.get(3).size());
        assertEquals(4, attributes.get(4).index());
        assertEquals(1, attributes.get(4).size());
        assertEquals(HairStrandGeometry.VERTEX_STRIDE_BYTES,
                HairStrandGeometry.LAYOUT.strideBytes());
    }

    @Test
    void disabledSettingsCannotCreateThePassOrAnyGlResource() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new HairStrandPass(HairStrandSettings.disabled(), 128, 128));
        assertTrue(failure.getMessage().contains("disabled"));
        assertThrows(IllegalArgumentException.class,
                () -> new HairStrandPass(HairStrandSettings.builder().build(), 0, 128));
        assertThrows(IllegalArgumentException.class,
                () -> new HairStrandPass(HairStrandSettings.builder().build(), 128, -1));
    }

    /**
     * Pins one program's complete uniform contract: every declared uniform is
     * referenced (so GLSL cannot silently optimize it out) and the pass method
     * that records this program uploads exactly that set. Splitting the check
     * per program keeps the forward, shadow and velocity contracts independent.
     */
    private static void assertUploadSetMatches(String label, String vertexPath,
                                               String fragmentPath, String methodSignature)
            throws IOException {
        String vertex = resource(vertexPath);
        String fragment = resource(fragmentPath);
        Set<String> declared = new LinkedHashSet<>();
        collect(DECLARED_UNIFORM, vertex, declared);
        collect(DECLARED_UNIFORM, fragment, declared);
        assertFalse(declared.isEmpty(), label + " must declare uniforms");
        String combined = vertex + "\n" + fragment;
        Set<String> uploaded = new LinkedHashSet<>();
        collect(UPLOADED_UNIFORM, methodBody(passSource(), methodSignature), uploaded);
        for (String uniform : declared) {
            assertTrue(countIdentifier(combined, uniform) >= 2,
                    label + " uniform " + uniform + " is declared but never referenced; "
                            + "GLSL may drop it");
        }
        assertEquals(declared, uploaded,
                label + " upload set must exactly match its shader uniforms: declared="
                        + declared + " uploaded=" + uploaded);
    }

    private static String passSource() throws IOException {
        return Files.readString(Path.of("src", "main", "java", "com", "kaleblangley",
                "haikalat", "subsystems", "render3d", "hair", "HairStrandPass.java"));
    }

    /**
     * Extracts the body of the last overload whose declaration starts with the
     * given signature. The forward record has a delegating overload, so the
     * concrete upload body is the last definition.
     */
    private static String methodBody(String source, String signature) {
        int start = source.lastIndexOf(signature);
        assertTrue(start >= 0, "missing pass method " + signature);
        int open = source.indexOf('{', start + signature.length());
        assertTrue(open >= 0, "missing body for " + signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open + 1, index);
            }
        }
        throw new AssertionError("unterminated pass method " + signature);
    }

    private static void collect(Pattern pattern, String source, Set<String> destination) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            destination.add(matcher.group(1));
        }
    }

    private static int countIdentifier(String source, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = HairStrandShaderTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing shader " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
