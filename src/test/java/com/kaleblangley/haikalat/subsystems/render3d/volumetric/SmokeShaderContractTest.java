package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM packaging and formula contract for the smoke shaders.
 *
 * <p>Compiling the programs needs a real GL context; this test proves the
 * resources are on the runtime classpath, pins the accepted SHA-256 of every
 * stage (also recorded in {@code docs/licenses/assets.tsv}) and locks the
 * closed-boundary/upwind/dissipation and single-medium/energy contracts a
 * reviewer can observe without a GPU.</p>
 */
class SmokeShaderContractTest {
    /** SHA-256 of the accepted {@code smoke-step.comp}, matching assets.tsv. */
    private static final String STEP_SHA256 =
            "f515583c093a78cde2390b5b516124a6c291f4060cc6e70f957b5bf57ea2bef4";
    /** SHA-256 of the accepted {@code smoke-clear.comp}, matching assets.tsv. */
    private static final String CLEAR_SHA256 =
            "dada52ea07fdbf60a9522843e47e519733fa372eafa94d2db3bad3c61653da26";
    /** SHA-256 of the accepted {@code smoke-volume.vert}, matching assets.tsv. */
    private static final String VERTEX_SHA256 =
            "33f1e751b5b5556feae1644344965c9be832646370573b5f5eadb4ce4a805363";
    /** SHA-256 of the accepted {@code smoke-volume.frag}, matching assets.tsv. */
    private static final String FRAGMENT_SHA256 =
            "6d8eb036d1f947ffc62bc4fbaf8dcb69f5447550bb75e00aaf25854d67740046";

    @Test
    void shaderStagesArePackagedAndPinned() {
        assertTrue(SmokeSimulation.stepSource().startsWith("#version 460 core"));
        assertTrue(SmokeSimulation.clearSource().startsWith("#version 460 core"));
        assertTrue(SmokeVolumePass.vertexSource().startsWith("#version 460 core"));
        assertTrue(SmokeVolumePass.fragmentSource().startsWith("#version 460 core"));
        assertEquals(STEP_SHA256, sha256(SmokeSimulation.stepSource()));
        assertEquals(CLEAR_SHA256, sha256(SmokeSimulation.clearSource()));
        assertEquals(VERTEX_SHA256, sha256(SmokeVolumePass.vertexSource()));
        assertEquals(FRAGMENT_SHA256, sha256(SmokeVolumePass.fragmentSource()));
        assertTrue(SmokeVolumePass.vertexSource().contains(
                "layout (location = 0) in vec2 aPosition;"),
                "the vertex stage consumes the screen-quad position only");
        assertFalse(SmokeVolumePass.vertexSource().contains("layout (location = 1)"));
    }

    @Test
    void stepStageDeclaresEveryUniformTheOwnerUploads() {
        String source = SmokeSimulation.stepSource();
        for (String uniform : List.of("uniform vec3 uGridSize", "uniform vec3 uGridMin",
                "uniform vec3 uCellSize", "uniform float uTimeStep", "uniform vec3 uWind",
                "uniform float uBuoyancy", "uniform float uMaximumTemperature",
                "uniform float uDensityDissipation", "uniform float uTemperatureDissipation",
                "uniform vec4 uSource0", "uniform vec4 uSource1", "uniform vec4 uSource2",
                "uniform vec4 uSource3", "uniform float uDensityRate0",
                "uniform float uDensityRate1", "uniform float uDensityRate2",
                "uniform float uDensityRate3", "uniform float uTemperatureRate0",
                "uniform float uTemperatureRate1", "uniform float uTemperatureRate2",
                "uniform float uTemperatureRate3")) {
            assertTrue(source.contains(uniform + ";"), "missing step uniform " + uniform);
        }
        assertTrue(source.contains("layout (binding = 0) uniform sampler3D uFieldIn;"));
        assertTrue(source.contains(
                "layout (rgba16f, binding = 0) writeonly uniform image3D uFieldOut;"));
        assertTrue(source.contains("texelFetch(uFieldIn"));
        assertTrue(source.contains("imageStore(uFieldOut, cell,"));
    }

    @Test
    void stepStageKeepsTheClosedBoundaryUpwindAndExponentialContracts() {
        String source = SmokeSimulation.stepSource();
        assertTrue(source.contains("cell.x > 0 ? uWind.x : 0.0"),
                "the -X outer face must carry zero normal velocity");
        assertTrue(source.contains("cell.x + 1 < extent.x ? uWind.x : 0.0"),
                "the +X outer face must carry zero normal velocity");
        assertTrue(source.contains("cell.z > 0 ? uWind.z : 0.0"));
        assertTrue(source.contains("cell.z + 1 < extent.z ? uWind.z : 0.0"));
        assertTrue(source.contains("if (cell.y > 0) {"),
                "the -Y outer face must be closed");
        assertTrue(source.contains("if (cell.y + 1 < extent.y) {"),
                "the +Y outer face must be closed");
        assertTrue(source.contains("velocityYLeft = 0.5 * (neighbour + velocityY);"),
                "an interior face carries the mean of its two cell velocities");
        assertTrue(source.contains("velocityYRight = 0.5 * (velocityY + neighbour);"));
        assertTrue(source.contains(
                "float densityFluxX = (velocityXRight > 0.0 ? velocityXRight * density"),
                "the density flux must be first-order upwind");
        assertTrue(source.contains(
                "* exp(-uDensityDissipation * uTimeStep);"),
                "density dissipation must be the exact exponential factor");
        assertTrue(source.contains(
                "* exp(-uTemperatureDissipation * uTimeStep);"));
        assertTrue(source.contains("temperatureNext = clamp(temperatureNext, 0.0, uMaximumTemperature);"));
        assertTrue(source.contains("float falloff = 1.0 - q;"));
        assertTrue(source.contains("return falloff * falloff;"));
        assertFalse(source.contains("texture("),
                "the step must sample exact cell values, never filtered textures");
    }

    @Test
    void clearStageZeroFillsTheWholeField() {
        String source = SmokeSimulation.clearSource();
        assertTrue(source.contains("layout (rgba16f, binding = 0) writeonly uniform image3D uFieldOut;"));
        assertTrue(source.contains("uniform vec3 uGridSize;"));
        assertTrue(source.contains("if (any(greaterThanEqual(cell, ivec3(uGridSize)))) {"));
        assertTrue(source.contains("imageStore(uFieldOut, cell, vec4(0.0));"));
    }

    @Test
    void volumeStageDeclaresEveryUniformThePassUploads() {
        String source = SmokeVolumePass.fragmentSource();
        for (String uniform : List.of("uniform sampler3D uSmokeField",
                "uniform sampler2D uSceneDepth", "uniform vec2 uViewportSize",
                "uniform mat4 uInverseProjection", "uniform mat4 uInverseView",
                "uniform vec3 uBoundsCenter", "uniform vec3 uBoundsHalfExtent",
                "uniform vec3 uLightDirection", "uniform vec3 uLightColor",
                "uniform float uDensityScale", "uniform float uAbsorption",
                "uniform float uScattering", "uniform float uAnisotropy",
                "uniform float uOpticalDepthScale", "uniform float uStepSizeMetres",
                "uniform int uMaximumSteps", "uniform int uOccluding")) {
            assertTrue(source.contains(uniform + ";"), "missing volume uniform " + uniform);
        }
        assertEquals(1, occurrences(source, "sampler3D"),
                "the consumer samples exactly one density field");
        assertEquals(1, occurrences(source, "sampler2D"),
                "the consumer samples exactly one depth texture");
        assertEquals(1, occurrences(source, "texelFetch(uSceneDepth"),
                "exactly one opaque-depth fetch per fragment");
        assertTrue(source.contains("if (!(rawDepth < 1.0))"),
                "a cleared depth must be treated as no occluder");
        assertFalse(source.contains("sampler2DMS"));
    }

    @Test
    void volumeStageKeepsTheSingleMediumAndCompositionContract() {
        String source = SmokeVolumePass.fragmentSource();
        assertFalse(source.contains("uSceneColor"),
                "the pass must not sample the colour it blends over");
        assertFalse(source.contains("uVolumePrefix") || source.contains("uVolumeSource")
                        || source.contains("uSmokeSource"),
                "the pass must not integrate a second volumetric medium");
        assertFalse(source.contains("volume-common.glsl") || source.contains("volumetric/volume"),
                "the smoke shader must not include the fog implementation");
        assertFalse(source.contains("imageStore"),
                "the consumer writes through its framebuffer, never an unrelated image");
        assertTrue(source.contains("int uOccluding;"),
                "the blend policy is an explicit uniform");
        assertEquals(2, occurrences(source, "uOpticalDepthScale"),
                "the contribution weight is declared once and applied to the optical depth once");
    }

    @Test
    void volumeStageKeepsTheDocumentedBoxStepDepthAndScatterFormulas() {
        String source = SmokeVolumePass.fragmentSource();
        assertTrue(source.contains("vec2 smokeBoxInterval(vec3 origin, vec3 direction)"));
        assertTrue(source.contains("if (abs(axisDirection) < 1.0e-9) {"),
                "a zero direction component must not divide by zero");
        assertTrue(source.contains("return vec2(1.0, -1.0);"));
        assertTrue(source.contains("nearDistance = max(nearDistance, min(first, second));"));
        assertTrue(source.contains("float stepLength = max(span / float(uMaximumSteps), uStepSizeMetres);"));
        assertTrue(source.contains(
                "int steps = int(clamp(ceil(span / stepLength), 1.0, float(uMaximumSteps)));"),
                "the step budget must clamp the actual march length");
        assertTrue(source.contains("float density = texture(uSmokeField, smokeUv(samplePosition)).r;"));
        assertTrue(source.contains(
                "float sampleTransmittance = exp(-min(sampleOpticalDepth, SMOKE_MAXIMUM_OPTICAL_DEPTH));"),
                "transmittance must stay finite and bounded");
        assertTrue(source.contains(
                "float albedo = (uAbsorption + uScattering) > 0.0"),
                "albedo must use the configured coefficients");
        assertTrue(source.contains(
                "scattering += transmittance * uLightColor"),
                "single scatter must stay energy-consistent with the transmittance");
        assertTrue(source.contains(
                "FragColor = vec4(max(scattering, vec3(0.0)), uOccluding != 0 ? 1.0 - transmittance : 0.0);"));
        assertTrue(source.contains("float cosine = clamp(dot(uLightDirection, -rayDirection), -1.0, 1.0);"),
                "the phase cosine follows the fog/particle light-travel convention");
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

    private static String sha256(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }
}
