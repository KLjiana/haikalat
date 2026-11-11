package com.kaleblangley.haikalat.subsystems.render3d.planar;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanarReflectionMathTest {
    private static final double EPSILON = 1.0e-5;

    @Test
    void reflectsPointAndDirectionAcrossATiltedPlane() {
        Vector4f plane = new Vector4f(1.0f, 1.0f, 0.0f, 0.0f).normalize();
        Vector3f point = new Vector3f(1.0f, 3.0f, 4.0f);
        Vector3f mirroredd = PlanarReflectionMath.mirrorPosition(plane, point, new Vector3f());
        assertVector(-3.0, -1.0, 4.0, mirroredd);

        Vector3f direction = new Vector3f(2.0f, 0.0f, 1.0f);
        Vector3f mirroreddDirection = PlanarReflectionMath.mirrorDirection(
                plane, direction, new Vector3f());
        assertVector(0.0, -2.0, 1.0, mirroreddDirection);

        // A point already on the plane and a direction parallel to it are fixed.
        Vector3f onPlane = new Vector3f(1.0f, -1.0f, 7.0f);
        assertVector(1.0, -1.0, 7.0,
                PlanarReflectionMath.mirrorPosition(plane, onPlane, new Vector3f()));
        Vector3f parallel = new Vector3f(1.0f, -1.0f, 0.0f);
        assertVector(1.0, -1.0, 0.0,
                PlanarReflectionMath.mirrorDirection(plane, parallel, new Vector3f()));
    }

    @Test
    void reflectionMatrixMatchesTheHandComputedHouseholderMatrix() {
        Vector4f plane = new Vector4f(1.0f, 1.0f, 0.0f, 0.0f).normalize();
        Matrix4f matrix = PlanarReflectionMath.reflectionMatrix(plane, new Matrix4f());
        double[][] expected = {
                {0.0, -1.0, 0.0, 0.0},
                {-1.0, 0.0, 0.0, 0.0},
                {0.0, 0.0, 1.0, 0.0},
                {0.0, 0.0, 0.0, 1.0}
        };
        assertMatrix(expected, matrix);
        assertEquals(-1.0, matrix.determinant(), 1.0e-5);

        Vector4f transformed = matrix.transform(new Vector4f(1.0f, 3.0f, 4.0f, 1.0f));
        assertVector(-3.0, -1.0, 4.0, new Vector3f(transformed.x, transformed.y, transformed.z));
    }

    @Test
    void mirrorViewEqualsTheSceneViewTimesTheReflection() {
        Matrix4f view = new Matrix4f().lookAt(0.0f, 4.0f, 5.0f,
                0.0f, 4.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        Vector4f plane = new Vector4f(0.0f, 1.0f, 0.0f, -2.0f);

        Matrix4f mirrored = PlanarReflectionMath.mirrorView(plane, view, new Matrix4f());
        Matrix4f withScratch = PlanarReflectionMath.mirrorView(plane, view,
                new Matrix4f(), new Matrix4f());
        assertEquals(withScratch, mirrored);

        // R reflects y around y == 2: y -> 4 - y.
        double[][] reflection = {
                {1.0, 0.0, 0.0, 0.0},
                {0.0, -1.0, 0.0, 4.0},
                {0.0, 0.0, 1.0, 0.0},
                {0.0, 0.0, 0.0, 1.0}
        };
        double[][] expected = multiply(rows(view), reflection);
        assertMatrix(expected, mirrored);
        assertTrue(mirrored.determinant() < 0.0,
                "a reflected camera view must have negative determinant");

        Vector3f position = PlanarReflectionMath.mirrorPosition(plane,
                new Vector3f(0.0f, 4.0f, 5.0f), new Vector3f());
        assertVector(0.0, 0.0, 5.0, position);
    }

    @Test
    void planeToViewMatchesTheHandComputedViewSpacePlane() {
        Matrix4f view = new Matrix4f().lookAt(0.0f, 2.0f, 5.0f,
                0.0f, 2.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        Vector4f worldPlane = new Vector4f(0.0f, 1.0f, 0.0f, 0.0f);
        Vector4f viewPlane = PlanarReflectionMath.planeToView(worldPlane, view, new Vector4f());
        assertEquals(0.0f, viewPlane.x, 1.0e-6f);
        assertEquals(1.0f, viewPlane.y, 1.0e-6f);
        assertEquals(0.0f, viewPlane.z, 1.0e-6f);
        assertEquals(2.0f, viewPlane.w, 1.0e-6f);
    }

    @Test
    void obliqueProjectionMapsThePlaneToTheNearPlaneAndClipsTheFrontSide() {
        Matrix4f projection = perspective(60.0, 1.0, 0.1, 100.0);
        double near = 0.1;
        double far = 100.0;
        double f = 1.0 / Math.tan(Math.toRadians(60.0) * 0.5);
        double m22 = (far + near) / (near - far);
        double m32 = 2.0 * far * near / (near - far);
        double[][] reference = {
                {f, 0.0, 0.0, 0.0},
                {0.0, f, 0.0, 0.0},
                {0.0, 0.0, m22, m32},
                {0.0, 0.0, -1.0, 0.0}
        };
        assertMatrix(reference, projection);

        Vector4f viewPlane = new Vector4f(0.0f, 0.0f, -1.0f, -5.0f);
        Matrix4f oblique = PlanarReflectionMath.obliqueProjection(
                projection, viewPlane, new Matrix4f());

        Ndc onPlane = ndc(oblique, 0.2, 0.3, -5.0);
        Ndc onPlaneReference = ndc(projection, 0.2, 0.3, -5.0);
        assertEquals(onPlaneReference.x, onPlane.x, EPSILON);
        assertEquals(onPlaneReference.y, onPlane.y, EPSILON);
        assertEquals(-1.0, onPlane.z, EPSILON);
        assertEquals(onPlaneReference.w, onPlane.w, EPSILON);

        assertTrue(ndc(oblique, 0.0, 0.0, -4.0).z < -1.0,
                "geometry between the camera and the clip plane must be behind the near plane");
        for (double z : new double[]{-6.0, -8.0, -50.0}) {
            Ndc kept = ndc(oblique, 0.5, -0.25, z);
            assertTrue(kept.z >= -1.0 && kept.z <= 1.0,
                    "geometry beyond the clip plane must remain in the depth range at z=" + z);
        }

        Vector4f scaledPlane = new Vector4f(viewPlane).mul(4.0f);
        Matrix4f scaled = PlanarReflectionMath.obliqueProjection(
                projection, scaledPlane, new Matrix4f());
        assertMatrix(rows(scaled), oblique);
    }

    @Test
    void mirroreddViewAndObliqueProjectionAgreeOnAboveAndBelowPlaneGeometry() {
        Matrix4f view = new Matrix4f().lookAt(0.0f, 2.0f, 5.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
        Vector4f waterPlane = new Vector4f(0.0f, 1.0f, 0.0f, 0.0f);
        Matrix4f mirroreddView = PlanarReflectionMath.mirrorView(
                waterPlane, view, new Matrix4f());
        Vector4f clipPlaneWorld = new Vector4f(waterPlane);
        Vector4f clipPlaneView = PlanarReflectionMath.planeToView(
                clipPlaneWorld, mirroreddView, new Vector4f());
        Matrix4f projection = perspective(60.0, 1.0, 0.1, 100.0);
        Matrix4f oblique = PlanarReflectionMath.obliqueProjection(
                projection, clipPlaneView, new Matrix4f());
        Matrix4f mirroreddProjection = new Matrix4f(oblique).mul(mirroreddView);

        Ndc onPlane = transform(mirroreddProjection, 0.0, 0.0, 0.0);
        assertEquals(-1.0, onPlane.z, 1.0e-4,
                "the retained plane must become the mirroredd near plane");
        assertTrue(transform(mirroreddProjection, 0.0, 0.5, 0.0).z >= -1.0,
                "geometry above the water plane must be retained");
        assertTrue(transform(mirroreddProjection, 0.0, -0.5, 0.0).z < -1.0,
                "geometry below the water plane must be clipped");
    }

    @Test
    void rejectsReversedZAndAxisFlippedPerspectiveProjections() {
        // Reversed-Z: m22 < 0, m32 > 0.  It has the right m23/m33 and zero
        // cross terms, but its NDC z direction is inverted.
        double[][] reversedZ = {
                {1.732, 0.0, 0.0, 0.0},
                {0.0, 1.732, 0.0, 0.0},
                {0.0, 0.0, -0.001001, 0.1001},
                {0.0, 0.0, -1.0, 0.0}
        };
        Vector4f viewPlane = new Vector4f(0.0f, 0.0f, -1.0f, -5.0f);
        Matrix4f destination = new Matrix4f();
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.obliqueProjection(
                        matrixFromRows(reversedZ), viewPlane, destination));
        assertEquals(new Matrix4f(), destination,
                "a rejected reversed-Z projection must not modify the destination");

        // Negative m00 flips x_ndc and is not a standard OpenGL perspective.
        double[][] flippedX = {
                {-1.732, 0.0, 0.0, 0.0},
                {0.0, 1.732, 0.0, 0.0},
                {0.0, 0.0, -1.002002, -0.2002002},
                {0.0, 0.0, -1.0, 0.0}
        };
        Matrix4f flippedDestination = new Matrix4f();
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.obliqueProjection(
                        matrixFromRows(flippedX), viewPlane, flippedDestination));
        assertEquals(new Matrix4f(), flippedDestination,
                "a rejected x-flipped projection must not modify the destination");

        // Positive control: independently hand-written standard perspective is
        // still accepted and maps the plane to z_ndc == -1.
        Matrix4f perspective = perspective(60.0, 1.0, 0.1, 100.0);
        Matrix4f oblique = PlanarReflectionMath.obliqueProjection(
                perspective, viewPlane, new Matrix4f());
        assertEquals(-1.0, ndc(oblique, 0.2, 0.3, -5.0).z, EPSILON);
        assertTrue(ndc(oblique, 0.5, -0.25, -6.0).z > -1.0);
        assertTrue(ndc(oblique, 0.5, -0.25, -100.0).z <= 1.0);
    }

    @Test
    void rejectsOrthographicProjectionInsteadOfSilentlyProducingWrongNdc() {
        Matrix4f orthographic = new Matrix4f().ortho(-2.0f, 2.0f, -2.0f, 2.0f, 0.1f, 100.0f);
        Vector4f viewPlane = new Vector4f(0.0f, 0.0f, -1.0f, -5.0f);
        Matrix4f destination = new Matrix4f();
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.obliqueProjection(
                        orthographic, viewPlane, destination));
        assertEquals(new Matrix4f(), destination,
                "a rejected projection must not partially modify the destination");

        // The same plane with a standard perspective projection is accepted and
        // maps the plane to the near plane; orthographic would have produced
        // NDC z != -1 for the plane.
        Matrix4f perspective = perspective(60.0, 1.0, 0.1, 100.0);
        Matrix4f oblique = PlanarReflectionMath.obliqueProjection(
                perspective, viewPlane, new Matrix4f());
        assertEquals(-1.0, ndc(oblique, 0.2, 0.3, -5.0).z, EPSILON);
    }

    @Test
    void rejectsDegenerateInputs() {
        Vector4f zeroNormal = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.mirrorPosition(
                        zeroNormal, new Vector3f(), new Vector3f()));
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.reflectionMatrix(zeroNormal, new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.planeToView(zeroNormal, new Matrix4f(), new Vector4f()));

        Matrix4f projection = perspective(60.0, 1.0, 0.1, 100.0);
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.obliqueProjection(
                        new Matrix4f(), new Vector4f(0.0f, 0.0f, -1.0f, -5.0f), new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.obliqueProjection(projection,
                        new Vector4f(0.0f, 0.0f, 1.0f, 100.0f), new Matrix4f()),
                "a clip plane coincident with the far plane is degenerate");

        Matrix4f destination = new Matrix4f();
        assertThrows(IllegalArgumentException.class,
                () -> PlanarReflectionMath.mirrorView(new Vector4f(0.0f, 1.0f, 0.0f, 0.0f),
                        new Matrix4f(), destination, destination));
    }

    private static Matrix4f perspective(double fovDegrees, double aspect, double near, double far) {
        double f = 1.0 / Math.tan(Math.toRadians(fovDegrees) * 0.5);
        double m22 = (far + near) / (near - far);
        double m32 = 2.0 * far * near / (near - far);
        double[][] rows = {
                {f / aspect, 0.0, 0.0, 0.0},
                {0.0, f, 0.0, 0.0},
                {0.0, 0.0, m22, m32},
                {0.0, 0.0, -1.0, 0.0}
        };
        return matrixFromRows(rows);
    }

    private static Matrix4f matrixFromRows(double[][] rows) {
        float[] columnMajor = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                columnMajor[column * 4 + row] = (float) rows[row][column];
            }
        }
        return new Matrix4f().set(columnMajor);
    }

    private static Ndc ndc(Matrix4f projection, double x, double y, double z) {
        Vector4f clip = projection.transform(new Vector4f(
                (float) x, (float) y, (float) z, 1.0f));
        return new Ndc(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w, clip.w);
    }

    private static Ndc transform(Matrix4f matrix, double x, double y, double z) {
        return ndc(matrix, x, y, z);
    }

    private record Ndc(double x, double y, double z, double w) {
    }

    private static double[][] rows(Matrix4f matrix) {
        double[][] result = new double[4][4];
        Vector4f row = new Vector4f();
        for (int index = 0; index < 4; index++) {
            matrix.getRow(index, row);
            result[index][0] = row.x;
            result[index][1] = row.y;
            result[index][2] = row.z;
            result[index][3] = row.w;
        }
        return result;
    }

    private static double[][] multiply(double[][] left, double[][] right) {
        double[][] result = new double[4][4];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                double sum = 0.0;
                for (int inner = 0; inner < 4; inner++) {
                    sum += left[row][inner] * right[inner][column];
                }
                result[row][column] = sum;
            }
        }
        return result;
    }

    private static void assertMatrix(double[][] expected, Matrix4f actual) {
        assertMatrix(expected, rows(actual));
    }

    private static void assertMatrix(double[][] expected, double[][] actual) {
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                assertEquals(expected[row][column], actual[row][column], EPSILON,
                        "matrix element [" + row + "][" + column + "]");
            }
        }
    }

    private static void assertVector(double x, double y, double z, Vector3f actual) {
        assertEquals(x, actual.x, EPSILON);
        assertEquals(y, actual.y, EPSILON);
        assertEquals(z, actual.z, EPSILON);
    }
}
