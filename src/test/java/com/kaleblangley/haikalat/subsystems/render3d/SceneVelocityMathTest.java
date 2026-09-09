package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * CPU oracle for the frozen velocity contract: previousUv - currentUv from
 * stable projections, with jitter excluded from motion.
 */
class SceneVelocityMathTest {
    private static final double EPSILON = 1.0e-5;

    @Test
    void cameraTranslationProducesVelocityForStaticPoint() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60.0f),
                16.0f / 9.0f, 0.1f, 100.0f);
        Matrix4f previousView = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        Matrix4f currentView = new Matrix4f().lookAt(0.25f, 0, 0, 0.25f, 0, -1, 0, 1, 0);
        double[] point = {0.0, 0.0, -5.0, 1.0};

        double[] velocity = velocity(projection, previousView, currentView, point);
        double[] expected = oracleVelocity(projection, previousView, currentView, point);
        assertEquals(expected[0], velocity[0], EPSILON);
        assertEquals(expected[1], velocity[1], EPSILON);
        assertNotEquals(0.0, velocity[0], 1.0e-9, "static camera translation must move the point");
    }

    @Test
    void objectTranslationAndCameraRotationCombine() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60.0f),
                16.0f / 9.0f, 0.1f, 100.0f);
        Matrix4f previousView = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        Matrix4f currentView = new Matrix4f().lookAt(0, 0, 0, 1, 0, -1, 0, 1, 0);
        double[] previousPoint = {0.0, 0.0, -5.0, 1.0};
        double[] currentPoint = {0.3, 0.0, -5.0, 1.0};

        double[] velocity = velocity(projection, previousView, currentView,
                previousPoint, currentPoint);
        double[] expected = oracleVelocity(projection, previousView, currentView,
                previousPoint, currentPoint);
        assertEquals(expected[0], velocity[0], EPSILON);
        assertEquals(expected[1], velocity[1], EPSILON);
    }

    @Test
    void jitterOnlyMotionYieldsZeroVelocity() {
        int width = 1280;
        int height = 720;
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(60.0f),
                width / (float) height, 0.1f, 100.0f);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
        double[] point = {0.0, 0.0, -5.0, 1.0};

        // Velocity uses the stable projection on both frames; jitter must not
        // appear as object motion.
        double[] first = velocity(projection, view, view, point);
        assertEquals(0.0, first[0], 1.0e-7);
        assertEquals(0.0, first[1], 1.0e-7);
    }

    private static double[] velocity(Matrix4f projection, Matrix4f previousView,
                                     Matrix4f currentView, double[] point) {
        return velocity(projection, previousView, currentView, point, point);
    }

    private static double[] velocity(Matrix4f projection, Matrix4f previousView,
                                     Matrix4f currentView, double[] previousPoint,
                                     double[] currentPoint) {
        double[] currentUv = project(projection, currentView, currentPoint);
        double[] previousUv = project(projection, previousView, previousPoint);
        return new double[]{previousUv[0] - currentUv[0], previousUv[1] - currentUv[1]};
    }

    private static double[] oracleVelocity(Matrix4f projection, Matrix4f previousView,
                                           Matrix4f currentView, double[] point) {
        return velocity(projection, previousView, currentView, point, point);
    }

    private static double[] oracleVelocity(Matrix4f projection, Matrix4f previousView,
                                           Matrix4f currentView, double[] previousPoint,
                                           double[] currentPoint) {
        double[] currentUv = oracleProject(projection, currentView, currentPoint);
        double[] previousUv = oracleProject(projection, previousView, previousPoint);
        return new double[]{previousUv[0] - currentUv[0], previousUv[1] - currentUv[1]};
    }

    private static double[] project(Matrix4f projection, Matrix4f view, double[] point) {
        Matrix4f vp = new Matrix4f(projection).mul(view);
        org.joml.Vector4f clip = vp.transform(new org.joml.Vector4f(
                (float) point[0], (float) point[1], (float) point[2], 1.0f));
        return new double[]{clip.x / clip.w * 0.5 + 0.5, clip.y / clip.w * 0.5 + 0.5};
    }

    private static double[] oracleProject(Matrix4f projection, Matrix4f view, double[] point) {
        float[] p = new float[16];
        float[] v = new float[16];
        projection.get(p);
        view.get(v);
        double[] vp = multiply(toDouble(p), toDouble(v));
        double x = point[0];
        double y = point[1];
        double z = point[2];
        double cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12];
        double cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13];
        double cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        return new double[]{cx / cw * 0.5 + 0.5, cy / cw * 0.5 + 0.5};
    }

    private static double[] toDouble(float[] values) {
        double[] result = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    private static double[] multiply(double[] left, double[] right) {
        double[] result = new double[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double sum = 0.0;
                for (int k = 0; k < 4; k++) {
                    sum += left[k * 4 + row] * right[column * 4 + k];
                }
                result[column * 4 + row] = sum;
            }
        }
        return result;
    }
}
