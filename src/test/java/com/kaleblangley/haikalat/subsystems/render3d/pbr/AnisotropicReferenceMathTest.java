package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the independent double-precision anisotropic oracle with fixed,
 * hand-computable samples. The expected numbers were derived from the published
 * Burley equations (not from production code or the shader). They make the
 * shader formula reproducible and catch drift in the reference before the
 * real-GL oracle is run.
 */
class AnisotropicReferenceMathTest {
    private static final double[] NORMAL = {0.0, 0.0, 1.0};
    private static final double[] TANGENT = {1.0, 0.0, 0.0};
    private static final double[] BITANGENT = {0.0, 1.0, 0.0};
    private static final double[] VIEW = {0.0, 0.0, 1.0};
    private static final double SIN_60 = Math.sin(Math.toRadians(60.0));
    private static final double COS_60 = Math.cos(Math.toRadians(60.0));
    private static final double COS_30 = Math.cos(Math.toRadians(30.0));
    private static final double[] LIGHT_ALONG_TANGENT = {SIN_60, 0.0, COS_60};
    private static final double[] HALF_ALONG_TANGENT = {0.5, 0.0, COS_30};
    private static final double ROUGHNESS = 0.5;
    private static final double STRENGTH = 0.5;
    private static final double TOLERANCE = 1.0e-9;

    @Test
    void axisAlphasFollowTheRoughnessMappingAndFloor() {
        assertArrayEquals(new double[]{0.375, 0.125},
                AnisotropicReferenceMath.axisAlphas(0.5, 0.5), TOLERANCE);
        assertArrayEquals(new double[]{0.25, 0.25},
                AnisotropicReferenceMath.axisAlphas(0.5, 0.0), TOLERANCE);
        assertArrayEquals(new double[]{0.5, 0.002025},
                AnisotropicReferenceMath.axisAlphas(0.5, 1.0), TOLERANCE);
        assertArrayEquals(new double[]{0.00405, 0.002025},
                AnisotropicReferenceMath.axisAlphas(0.0, 1.0), TOLERANCE,
                "zero authored roughness must still use the per-axis alpha floor");
    }

    @Test
    void distributionMatchesFixedBurleySamples() {
        assertEquals(6.7906109052542005,
                AnisotropicReferenceMath.distribution(NORMAL, NORMAL, TANGENT, BITANGENT,
                        ROUGHNESS, STRENGTH), TOLERANCE,
                "normal-incidence D is 1 / (PI * at * ab)");
        assertEquals(1.062749877214038,
                AnisotropicReferenceMath.distribution(NORMAL, HALF_ALONG_TANGENT,
                        TANGENT, BITANGENT, ROUGHNESS, STRENGTH), TOLERANCE,
                "30-degree half vector along the stretch direction");
        assertEquals(0.024203558584109434,
                AnisotropicReferenceMath.distribution(NORMAL,
                        new double[]{0.0, 0.5, COS_30}, TANGENT, BITANGENT,
                        ROUGHNESS, STRENGTH), TOLERANCE,
                "the perpendicular axis must produce a much narrower lobe");
    }

    @Test
    void zeroStrengthDistributionReducesToTheIsotropicGgxFormula() {
        double a = ROUGHNESS * ROUGHNESS;
        double a2 = a * a;
        double denominator = HALF_ALONG_TANGENT[2] * HALF_ALONG_TANGENT[2] * (a2 - 1.0) + 1.0;
        double isotropic = a2 / (Math.PI * denominator * denominator);
        assertEquals(isotropic,
                AnisotropicReferenceMath.distribution(NORMAL, HALF_ALONG_TANGENT,
                        TANGENT, BITANGENT, ROUGHNESS, 0.0), TOLERANCE,
                "equal axes must reproduce the base GGX distribution");
    }

    @Test
    void visibilityAndSpecularMatchFixedSamples() {
        assertEquals(0.4561161523465056,
                AnisotropicReferenceMath.visibility(NORMAL, VIEW, LIGHT_ALONG_TANGENT,
                        TANGENT, BITANGENT, ROUGHNESS, STRENGTH), TOLERANCE);
        assertEquals(0.04004143654313937,
                AnisotropicReferenceMath.fresnelSchlick(HALF_ALONG_TANGENT[2],
                        AnisotropicReferenceMath.DIELECTRIC_F0), TOLERANCE,
                "h.v equals 0.8660254 for the fixed light");
        assertEquals(0.019409581237624273,
                AnisotropicReferenceMath.specularFactor(NORMAL, VIEW, LIGHT_ALONG_TANGENT,
                        TANGENT, BITANGENT, ROUGHNESS, STRENGTH,
                        AnisotropicReferenceMath.DIELECTRIC_F0), TOLERANCE);
    }

    @Test
    void zeroStrengthIsNotTheAnisotropicEvaluatorButTheOriginalIsotropicPath() {
        double anisotropicAtZero = AnisotropicReferenceMath.specularFactor(NORMAL, VIEW,
                LIGHT_ALONG_TANGENT, TANGENT, BITANGENT, ROUGHNESS, 0.0,
                AnisotropicReferenceMath.DIELECTRIC_F0);
        double isotropic = AnisotropicReferenceMath.isotropicSpecularFactor(NORMAL, VIEW,
                LIGHT_ALONG_TANGENT, ROUGHNESS, AnisotropicReferenceMath.DIELECTRIC_F0);
        assertEquals(0.007054376948201099, isotropic, TOLERANCE);
        assertTrue(Math.abs(anisotropicAtZero - isotropic) > 1.0e-4,
                "the shader must branch to the original Schlick-GGX path at strength zero; "
                        + "the anisotropic visibility has a different form");
    }

    @Test
    void fullDirectBrdfMatchesFixedChannels() {
        double[] result = AnisotropicReferenceMath.anisotropicDirectBrdf(NORMAL, VIEW,
                LIGHT_ALONG_TANGENT, new double[]{1.0, 1.0, 1.0},
                new double[]{0.5, 0.4, 0.3}, 0.0, ROUGHNESS, STRENGTH,
                TANGENT, BITANGENT);
        assertEquals(0.08609586588758927, result[0], TOLERANCE);
        assertEquals(0.07081765083383386, result[1], TOLERANCE);
        assertEquals(0.05553943578007843, result[2], TOLERANCE);
    }

    @Test
    void rotationSwapsTheStretchAndSqueezeAxes() {
        double[] quarterTurn = AnisotropicReferenceMath.frame(NORMAL, TANGENT, 1.0,
                Math.PI / 2.0);
        assertEquals(0.0, quarterTurn[0], TOLERANCE);
        assertEquals(1.0, quarterTurn[1], TOLERANCE);
        assertEquals(-1.0, quarterTurn[3], TOLERANCE);
        assertEquals(0.0, quarterTurn[4], TOLERANCE);
        assertEquals(0.024203558584109434,
                AnisotropicReferenceMath.distribution(NORMAL, HALF_ALONG_TANGENT,
                        new double[]{quarterTurn[0], quarterTurn[1], quarterTurn[2]},
                        new double[]{quarterTurn[3], quarterTurn[4], quarterTurn[5]},
                        ROUGHNESS, STRENGTH), TOLERANCE,
                "a quarter turn must put the half-vector lobe on the minor axis");
        assertEquals(0.4942740583265332,
                AnisotropicReferenceMath.visibility(NORMAL, VIEW, LIGHT_ALONG_TANGENT,
                        new double[]{quarterTurn[0], quarterTurn[1], quarterTurn[2]},
                        new double[]{quarterTurn[3], quarterTurn[4], quarterTurn[5]},
                        ROUGHNESS, STRENGTH), TOLERANCE,
                "the rotated frame must also swap the visibility sample");
    }

    @Test
    void frameUsesTheDocumentedFallbackAndMirroredHandedness() {
        assertArrayEquals(new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0},
                AnisotropicReferenceMath.frame(NORMAL, null, 1.0, 0.0), TOLERANCE,
                "a missing tangent falls back to the deterministic frame");
        assertArrayEquals(new double[]{1.0, 0.0, 0.0, 0.0, -1.0, 0.0},
                AnisotropicReferenceMath.frame(NORMAL, new double[]{0.0, 0.0, 0.0}, -1.0, 0.0),
                TOLERANCE, "degenerate tangents use the fallback and negative handedness flips B");
        double diagonal = Math.sqrt(0.5);
        assertArrayEquals(new double[]{diagonal, -diagonal, 0.0, -diagonal, -diagonal, 0.0},
                AnisotropicReferenceMath.frame(NORMAL, TANGENT, -1.0, Math.PI / 4.0),
                TOLERANCE, "mirrored handedness rotates in the opposite tangent-space sense");
        assertArrayEquals(new double[]{-1.0, 0.0, 0.0, 0.0, 0.0, 1.0},
                AnisotropicReferenceMath.frame(new double[]{0.0, 1.0, 0.0}, null, 1.0, 0.0),
                TOLERANCE, "fallback reference selection must stay usable for +Y normals");
    }

    @Test
    void referenceStaysFiniteOverTheBoundedParameterGrid() {
        double[][] views = {
                {0.0, 0.0, 1.0},
                {Math.sin(1.2), 0.0, Math.cos(1.2)},
                {0.3, -0.4, 0.8660254}};
        double[][] lights = {
                {0.0, 0.0, 1.0},
                {0.5, 0.5, 0.7071068},
                {-0.5, 0.2, 0.841}};
        for (double roughness : new double[]{0.0, 0.045, 0.2, 0.5, 1.0}) {
            for (double strength : new double[]{0.0, 0.25, 0.5, 1.0}) {
                for (double[] view : views) {
                    for (double[] light : lights) {
                        double d = AnisotropicReferenceMath.distribution(NORMAL,
                                new double[]{0.5, 0.0, COS_30}, TANGENT, BITANGENT,
                                roughness, strength);
                        double v = AnisotropicReferenceMath.visibility(NORMAL, view, light,
                                TANGENT, BITANGENT, roughness, strength);
                        double specular = AnisotropicReferenceMath.specularFactor(NORMAL,
                                view, light, TANGENT, BITANGENT, roughness, strength,
                                AnisotropicReferenceMath.DIELECTRIC_F0);
                        assertTrue(Double.isFinite(d) && d >= 0.0,
                                "D must stay finite at r=" + roughness + " s=" + strength);
                        assertTrue(Double.isFinite(v) && v >= 0.0,
                                "V must stay finite at r=" + roughness + " s=" + strength);
                        assertTrue(Double.isFinite(specular) && specular >= 0.0,
                                "specular must stay finite at r=" + roughness + " s=" + strength);
                    }
                }
            }
        }
    }
}
