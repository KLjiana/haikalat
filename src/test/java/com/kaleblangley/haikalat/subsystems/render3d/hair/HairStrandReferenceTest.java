package com.kaleblangley.haikalat.subsystems.render3d.hair;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent double-precision acceptance for the #46 strand generator,
 * ribbon geometry, LOD selection and Kajiya-Kay shading math.
 *
 * <p>The reference implementation in {@link HairStrandReference} re-derives the
 * documented equations and is compared against production geometry for several
 * deterministic seeds. The shading test pins the positive control (tangent
 * perpendicular to the half vector) against the geometric negative control
 * (tangent parallel to the half vector); the real-GL test verifies the same
 * contrast in pixels.</p>
 */
class HairStrandReferenceTest {
    private static final float TOLERANCE = 2.0e-5f;

    @Test
    void generatedControlPointsMatchTheIndependentDoubleReference() {
        HairStrandSettings settings = settings().build();
        for (int strand = 0; strand < settings.strandCount(); strand++) {
            float[] actual = HairStrandGenerator.controlPoints(settings, strand,
                    settings.strandCount(), settings.segmentsPerStrand());
            double[] expected = HairStrandReference.controlPoints(settings, strand,
                    settings.strandCount(), settings.segmentsPerStrand());
            assertEquals(expected.length, actual.length);
            for (int index = 0; index < actual.length; index++) {
                assertEquals(expected[index], actual[index], TOLERANCE,
                        "strand " + strand + " point component " + index);
            }
        }
    }

    @Test
    void generationIsDeterministicAndSeedDependent() {
        HairStrandSettings first = settings().build();
        HairStrandSettings second = settings().seed(first.seed()).build();
        HairStrandSettings other = settings().seed(first.seed() + 1L).build();
        float[] left = HairStrandGenerator.controlPoints(first, 3, first.strandCount(),
                first.segmentsPerStrand());
        float[] right = HairStrandGenerator.controlPoints(second, 3, second.strandCount(),
                second.segmentsPerStrand());
        float[] changed = HairStrandGenerator.controlPoints(other, 3, other.strandCount(),
                other.segmentsPerStrand());
        assertArrayEquals(left, right, "the same seed must reproduce the same points exactly");
        assertNotEquals(left[0], changed[0], "a different seed must change the lock");
    }

    @Test
    void ribbonMeshHasContiguousPerStrandRangesAndTheDocumentedAttributes() {
        HairStrandSettings settings = settings().build();
        HairStrandGeometry.StrandMesh level = HairStrandGeometry.build(settings,
                HairStrandSettings.Lod.HIGH);
        int segments = settings.segmentsPerStrand();
        int verticesPerStrand = segments * HairStrandGeometry.VERTICES_PER_SEGMENT;
        assertEquals(settings.strandCount(), level.strandCount());
        assertEquals(segments, level.segmentCount());
        assertEquals(verticesPerStrand, level.verticesPerStrand());
        float[] vertices = level.meshData().vertices();
        assertEquals(level.strandCount() * verticesPerStrand * HairStrandGeometry.VERTEX_FLOATS,
                vertices.length);

        for (int strand = 0; strand < level.strandCount(); strand++) {
            double[] points = HairStrandReference.controlPoints(settings, strand,
                    level.strandCount(), segments);
            double random = HairStrandReference.randomValue(settings, strand);
            int strandBase = strand * verticesPerStrand * HairStrandGeometry.VERTEX_FLOATS;
            for (int segment = 0; segment < segments; segment++) {
                int segmentBase = strandBase
                        + segment * HairStrandGeometry.VERTICES_PER_SEGMENT
                        * HairStrandGeometry.VERTEX_FLOATS;
                // Vertex order: current-, current+, next-, next-, current+, next+.
                int[] sourcePoints = {segment, segment, segment + 1, segment + 1,
                        segment, segment + 1};
                float[] corners = {-1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f};
                for (int vertex = 0; vertex < 6; vertex++) {
                    int base = segmentBase
                            + vertex * HairStrandGeometry.VERTEX_FLOATS;
                    int pointBase = sourcePoints[vertex] * 3;
                    for (int axis = 0; axis < 3; axis++) {
                        assertEquals(points[pointBase + axis], vertices[base + axis],
                                TOLERANCE, "position strand " + strand + " segment " + segment
                                        + " vertex " + vertex + " axis " + axis);
                    }
                    float tangentX = vertices[base + 3];
                    float tangentY = vertices[base + 4];
                    float tangentZ = vertices[base + 5];
                    assertEquals(1.0f, Math.sqrt(tangentX * tangentX + tangentY * tangentY
                            + tangentZ * tangentZ), 2.0e-5f, "tangent must be normalized");
                    float parameter = vertices[base + 6];
                    float expectedParameter = sourcePoints[vertex] == segment
                            ? segment / (float) segments : (segment + 1) / (float) segments;
                    assertEquals(expectedParameter, parameter, 0.0f);
                    assertEquals(corners[vertex], vertices[base + 7], 0.0f);
                    assertEquals(random, vertices[base + 8], 1.0e-7f);
                }
            }
            int center = strand * 3;
            assertEquals((points[0] + points[points.length - 3]) * 0.5,
                    level.strandCenters()[center], TOLERANCE);
            assertEquals((points[1] + points[points.length - 2]) * 0.5,
                    level.strandCenters()[center + 1], TOLERANCE);
            assertEquals((points[2] + points[points.length - 1]) * 0.5,
                    level.strandCenters()[center + 2], TOLERANCE);
        }

        assertTrue(level.bounds().isFinite());
        for (int strand = 0; strand < level.strandCount(); strand++) {
            double[] points = HairStrandReference.controlPoints(settings, strand,
                    level.strandCount(), segments);
            for (int index = 0; index < points.length; index += 3) {
                assertTrue(points[index] >= level.bounds().minX() - TOLERANCE
                        && points[index] <= level.bounds().maxX() + TOLERANCE);
                assertTrue(points[index + 1] >= level.bounds().minY() - TOLERANCE
                        && points[index + 1] <= level.bounds().maxY() + TOLERANCE);
                assertTrue(points[index + 2] >= level.bounds().minZ() - TOLERANCE
                        && points[index + 2] <= level.bounds().maxZ() + TOLERANCE);
            }
        }
    }

    @Test
    void everyLodLevelRebuildsOnlyItsDeclaredBudget() {
        HairStrandSettings settings = settings().strandCount(8).segmentsPerStrand(8).build();
        HairStrandGeometry.StrandMesh high = HairStrandGeometry.build(settings,
                HairStrandSettings.Lod.HIGH);
        HairStrandGeometry.StrandMesh medium = HairStrandGeometry.build(settings,
                HairStrandSettings.Lod.MEDIUM);
        HairStrandGeometry.StrandMesh low = HairStrandGeometry.build(settings,
                HairStrandSettings.Lod.LOW);
        assertEquals(8, high.strandCount());
        assertEquals(4, medium.strandCount());
        assertEquals(2, low.strandCount());
        assertEquals(8, high.segmentCount());
        assertEquals(6, medium.segmentCount());
        assertEquals(4, low.segmentCount());
        assertEquals(medium.strandCount() * medium.verticesPerStrand()
                * HairStrandGeometry.VERTEX_FLOATS, medium.meshData().vertices().length);
        assertEquals(low.strandCount() * low.verticesPerStrand()
                * HairStrandGeometry.VERTEX_FLOATS, low.meshData().vertices().length);
    }

    @Test
    void lodDecisionMatchesTheDoubleReferenceAcrossThresholds() {
        HairStrandSettings settings = settings().build();
        double[][] cases = {
                {0.0, 0.0}, {3.9, 1_000.0}, {4.0, 48.0}, {4.0, 47.999},
                {12.0, 24.0}, {12.0, 23.999}, {12.001, 1_000.0}, {1_000.0, 1_000.0}
        };
        for (double[] entry : cases) {
            assertEquals(HairStrandReference.selectLod(settings, entry[0], entry[1]),
                    settings.selectLod(entry[0], entry[1]),
                    "distance=" + entry[0] + " pixels=" + entry[1]);
        }
    }

    @Test
    void projectedRadiusProbeMatchesTheAnalyticPerspectiveRadius() {
        float fieldOfView = (float) Math.toRadians(55.0);
        int width = 800;
        int height = 600;
        Matrix4f projection = new Matrix4f().perspective(fieldOfView, width / (float) height,
                0.1f, 100.0f);
        Matrix4f view = new Matrix4f();
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        double distance = 5.0;
        double radius = 0.5;
        double[] center = {0.0, 0.0, -distance};
        double[] camera = {0.0, 0.0, 0.0};
        double actual = HairStrandReference.projectedRadiusPixels(viewProjection, center,
                camera, radius, width, height);
        double expected = radius / (distance * Math.tan(fieldOfView * 0.5)) * (height * 0.5);
        assertEquals(expected, actual, Math.max(1.0e-6, expected * 1.0e-6));

        assertEquals(HairStrandSettings.Lod.HIGH,
                HairStrandSettings.builder().nearDistanceMetres(6.0f).build()
                        .selectLod(distance, actual));
        assertEquals(HairStrandSettings.Lod.MEDIUM,
                HairStrandSettings.builder().nearDistanceMetres(4.0f)
                        .farDistanceMetres(6.0f).build().selectLod(distance, actual));
    }

    @Test
    void kajiyaKayPositiveControlDominatesTheParallelTangentAndMatchesTheReference() {
        double[] lightDirection = HairStrandReference.normalize(new double[]{-1.0, -1.0, -1.0});
        double[] viewDirection = {0.0, 0.0, 1.0};
        double[] half = HairStrandReference.normalize(new double[]{
                lightDirection[0] + viewDirection[0], lightDirection[1] + viewDirection[1],
                lightDirection[2] + viewDirection[2]});
        double[] perpendicular = HairStrandReference.normalize(
                HairStrandReference.cross(half, new double[]{0.0, 1.0, 0.0}));
        double[] baseColor = {0.5, 0.3, 0.2};
        double[] radiance = {1.0, 1.0, 1.0};
        double[] ambient = {0.0, 0.0, 0.0};
        double strength = 2.5;
        double weight = 0.5;

        double[] highlight = HairStrandReference.kajiyaKay(perpendicular, lightDirection,
                viewDirection, baseColor, radiance, ambient, 0.0, strength, 48.0, 128.0, weight);
        double[] negative = HairStrandReference.kajiyaKay(half, lightDirection, viewDirection,
                baseColor, radiance, ambient, 0.0, strength, 48.0, 128.0, weight);
        double expectedHighlight = strength * (1.0 + weight);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expectedHighlight, highlight[channel], 1.0e-9);
            assertEquals(0.0, negative[channel], 1.0e-9);
        }
        assertTrue(highlight[0] > negative[0] * 1_000.0,
                "perpendicular tangent must be the positive highlight control");

        double angle = Math.toRadians(80.0);
        double[] mixed = HairStrandReference.normalize(new double[]{
                half[0] * Math.cos(angle) + perpendicular[0] * Math.sin(angle),
                half[1] * Math.cos(angle) + perpendicular[1] * Math.sin(angle),
                half[2] * Math.cos(angle) + perpendicular[2] * Math.sin(angle)});
        double[] intermediate = HairStrandReference.kajiyaKay(mixed, lightDirection,
                viewDirection, baseColor, radiance, ambient, 0.0, strength, 48.0, 128.0, weight);
        double sin = Math.sin(angle);
        double expectedIntermediate = strength * (Math.pow(sin, 48.0)
                + weight * Math.pow(sin, 128.0));
        assertEquals(expectedIntermediate, intermediate[0], 1.0e-9);
        assertTrue(intermediate[0] > 0.0 && intermediate[0] < highlight[0]);
    }

    @Test
    void alphaProfileIsMonotoneAndShadowBiasRejectsSelfShadowing() {
        double previous = HairStrandReference.alphaProfile(0.0);
        assertEquals(0.55, previous, 1.0e-9);
        for (int step = 1; step <= 30; step++) {
            double value = HairStrandReference.alphaProfile(step / 30.0);
            assertTrue(value >= previous, "alpha profile must be monotone");
            previous = value;
        }
        assertEquals(1.0, previous, 1.0e-9);
        assertEquals(0.0, HairStrandReference.shadowFactor(0.50, 0.40, 0.01), 0.0);
        assertEquals(1.0, HairStrandReference.shadowFactor(0.50, 0.60, 0.01), 0.0);
        assertEquals(1.0, HairStrandReference.shadowFactor(0.50, 0.495, 0.01), 0.0,
                "a receiver within the configured bias must remain lit");
    }

    private static HairStrandSettings.Builder settings() {
        return HairStrandSettings.builder()
                .strandCount(5)
                .segmentsPerStrand(6)
                .strandLengthMetres(0.6f)
                .rootSpreadMetres(0.1f)
                .flowDirection(new Vector3f(0.0f, -1.0f, 0.0f))
                .curlMetres(0.2f)
                .widthPixels(3.0f)
                .minimumWidthPixels(1.5f)
                .maximumWidthPixels(8.0f)
                .nearDistanceMetres(4.0f)
                .farDistanceMetres(12.0f)
                .lodMinimumPixels(48.0f)
                .lodWidthScale(0.5f)
                .alpha(0.85f)
                .alphaCutoff(0.35f)
                .transparency(HairStrandSettings.Transparency.SORTED_ALPHA)
                .rootColor(new Vector3f(0.1f, 0.07f, 0.05f))
                .tipColor(new Vector3f(0.42f, 0.30f, 0.18f))
                .diffuseStrength(0.9f)
                .specularStrength(2.5f)
                .primaryExponent(48.0f)
                .secondaryExponent(128.0f)
                .secondaryWeight(0.5f)
                .colorVariation(0.25f)
                .castsShadow(true)
                .seed(1234L);
    }
}
