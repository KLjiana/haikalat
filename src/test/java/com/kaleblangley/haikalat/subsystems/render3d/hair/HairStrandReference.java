package com.kaleblangley.haikalat.subsystems.render3d.hair;

import org.joml.Matrix4f;

/**
 * Independent double-precision reference for the #46 strand set.
 *
 * <p>This test-source class does not call production helpers. It re-derives the
 * documented control-point equations, the inclusive LOD decision, the width
 * scale, the projected-radius probe and the Kajiya-Kay shading equation with
 * {@code double} arithmetic. The geometry and shader tests compare against these
 * values, so a drift in the procedural generator, the LOD policy or the shader
 * constants is detectable without a GPU.</p>
 */
final class HairStrandReference {
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long STRAND_SALT = 0xD1B54A32D192ED03L;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double CURVE_SWIRL = 1.7;
    private static final double LATERAL_SCALE = 0.25;
    private static final double POINT_JITTER_SCALE = 0.04;

    private HairStrandReference() {
    }

    static double[] controlPoints(HairStrandSettings settings, int strandIndex, int strandCount,
                                  int segments) {
        double[] basis = basis(settings.flowDirection());
        double flowX = settings.flowDirection().x;
        double flowY = settings.flowDirection().y;
        double flowZ = settings.flowDirection().z;
        double phase = TWO_PI * unit(hash(settings.seed(), strandIndex, 0x101));
        double rootRadius = settings.rootSpreadMetres()
                * Math.sqrt((strandIndex + 0.5) / strandCount);
        double rootX = basis[0] * Math.cos(phase) * rootRadius
                + basis[3] * Math.sin(phase) * rootRadius;
        double rootY = basis[1] * Math.cos(phase) * rootRadius
                + basis[4] * Math.sin(phase) * rootRadius;
        double rootZ = basis[2] * Math.cos(phase) * rootRadius
                + basis[5] * Math.sin(phase) * rootRadius;
        double curveAngle = phase * CURVE_SWIRL;
        double curveX = basis[0] * Math.cos(curveAngle) + basis[3] * Math.sin(curveAngle);
        double curveY = basis[1] * Math.cos(curveAngle) + basis[4] * Math.sin(curveAngle);
        double curveZ = basis[2] * Math.cos(curveAngle) + basis[5] * Math.sin(curveAngle);
        double length = settings.strandLengthMetres();
        double curl = settings.curlMetres();
        double[] points = new double[(segments + 1) * 3];
        for (int point = 0; point <= segments; point++) {
            double t = point / (double) segments;
            double arch = Math.sin(Math.PI * t);
            double lateral = curl * length * LATERAL_SCALE * arch;
            double jitter = curl * length * POINT_JITTER_SCALE * arch;
            int seedBase = 0x1000 + point * 3;
            int offset = point * 3;
            points[offset] = rootX + flowX * length * t + curveX * lateral
                    + (unit(hash(settings.seed(), strandIndex, seedBase)) * 2.0 - 1.0) * jitter;
            points[offset + 1] = rootY + flowY * length * t + curveY * lateral
                    + (unit(hash(settings.seed(), strandIndex, seedBase + 1)) * 2.0 - 1.0) * jitter;
            points[offset + 2] = rootZ + flowZ * length * t + curveZ * lateral
                    + (unit(hash(settings.seed(), strandIndex, seedBase + 2)) * 2.0 - 1.0) * jitter;
        }
        return points;
    }

    static double randomValue(HairStrandSettings settings, int strandIndex) {
        return unit(hash(settings.seed(), strandIndex, 0x51A7));
    }

    static double[] basis(org.joml.Vector3f flow) {
        double fx = flow.x;
        double fy = flow.y;
        double fz = flow.z;
        double length = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= length;
        fy /= length;
        fz /= length;
        double hintX = Math.abs(fy) < 0.9 ? 0.0 : 1.0;
        double hintY = Math.abs(fy) < 0.9 ? 1.0 : 0.0;
        double rightX = fy * 0.0 - fz * hintY;
        double rightY = fz * hintX - fx * 0.0;
        double rightZ = fx * hintY - fy * hintX;
        double rightLength = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
        rightX /= rightLength;
        rightY /= rightLength;
        rightZ /= rightLength;
        double upX = rightY * fz - rightZ * fy;
        double upY = rightZ * fx - rightX * fz;
        double upZ = rightX * fy - rightY * fx;
        return new double[]{rightX, rightY, rightZ, upX, upY, upZ};
    }

    static long hash(long seed, int strandIndex, int pointIndex) {
        long value = seed + GOLDEN_GAMMA * (strandIndex + 1L)
                + STRAND_SALT * (pointIndex + 1L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    static double unit(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }

    static HairStrandSettings.Lod selectLod(HairStrandSettings settings, double distance,
                                            double projectedPixels) {
        if (distance <= settings.nearDistanceMetres()
                && projectedPixels >= settings.lodMinimumPixels()) {
            return HairStrandSettings.Lod.HIGH;
        }
        if (distance <= settings.farDistanceMetres()
                && projectedPixels >= settings.lodMinimumPixels() * 0.5) {
            return HairStrandSettings.Lod.MEDIUM;
        }
        return HairStrandSettings.Lod.LOW;
    }

    static double widthScale(HairStrandSettings settings, double distance) {
        double span = settings.farDistanceMetres() - settings.nearDistanceMetres();
        double t = Math.max(0.0, Math.min(1.0,
                (distance - settings.nearDistanceMetres()) / span));
        return 1.0 + (settings.lodWidthScale() - 1.0) * t;
    }

    /**
     * Projects a world-space radius probe and returns its screen-pixel length.
     * The matrix elements are read row-major from JOML's {@link Matrix4f}.
     */
    static double projectedRadiusPixels(Matrix4f viewProjection, double[] center,
                                        double[] camera, double radius, int width, int height) {
        double[] centerNdc = project(viewProjection, center);
        double dx = center[0] - camera[0];
        double dy = center[1] - camera[1];
        double dz = center[2] - camera[2];
        double axisLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ax = dx / axisLength;
        double ay = dy / axisLength;
        double az = dz / axisLength;
        double px = 0.0;
        double py = 1.0;
        double pz = 0.0;
        if (Math.abs(ay) > 0.99) {
            px = 1.0;
            py = 0.0;
        }
        // right = normalize(cross(upHint, axis))
        double rx = py * az - pz * ay;
        double ry = pz * ax - px * az;
        double rz = px * ay - py * ax;
        double rightLength = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rightLength;
        ry /= rightLength;
        rz /= rightLength;
        double[] probe = {center[0] + rx * radius, center[1] + ry * radius,
                center[2] + rz * radius};
        double[] probeNdc = project(viewProjection, probe);
        double deltaX = (probeNdc[0] - centerNdc[0]) * 0.5 * width;
        double deltaY = (probeNdc[1] - centerNdc[1]) * 0.5 * height;
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    }

    static double[] project(Matrix4f matrix, double[] point) {
        // JOML matrices are column-major: mXY is column X, row Y.
        double x = matrix.m00() * point[0] + matrix.m10() * point[1]
                + matrix.m20() * point[2] + matrix.m30();
        double y = matrix.m01() * point[0] + matrix.m11() * point[1]
                + matrix.m21() * point[2] + matrix.m31();
        double w = matrix.m03() * point[0] + matrix.m13() * point[1]
                + matrix.m23() * point[2] + matrix.m33();
        if (!(w > 1.0e-6)) {
            throw new IllegalArgumentException("reference point is behind the eye");
        }
        return new double[]{x / w, y / w};
    }

    /** Double Kajiya-Kay evaluation used by the shading contrast test. */
    static double[] kajiyaKay(double[] tangent, double[] lightDirection, double[] viewDirection,
                              double[] baseColor, double[] radiance, double[] ambient,
                              double diffuseStrength, double specularStrength,
                              double primaryExponent, double secondaryExponent,
                              double secondaryWeight) {
        double[] tangentUnit = normalize(tangent);
        double[] lightUnit = normalize(lightDirection);
        double[] viewUnit = normalize(viewDirection);
        double[] half = normalize(new double[]{
                lightUnit[0] + viewUnit[0], lightUnit[1] + viewUnit[1],
                lightUnit[2] + viewUnit[2]});
        double tangentDotLight = dot(tangentUnit, lightUnit);
        double sinTangentLight = Math.sqrt(Math.max(1.0 - tangentDotLight * tangentDotLight, 0.0));
        double tangentDotHalf = dot(tangentUnit, half);
        double sinTangentHalf = Math.sqrt(Math.max(1.0 - tangentDotHalf * tangentDotHalf, 0.0));
        double primary = Math.pow(sinTangentHalf, primaryExponent);
        double secondary = Math.pow(sinTangentHalf, secondaryExponent) * secondaryWeight;
        double specular = (primary + secondary) * specularStrength;
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double diffuse = baseColor[channel]
                    * (ambient[channel] + radiance[channel] * diffuseStrength * sinTangentLight);
            result[channel] = diffuse + radiance[channel] * specular;
        }
        return result;
    }

    /** Root-to-tip alpha profile shared by the forward and shadow fragment stages. */
    static double alphaProfile(double parameter) {
        double t = Math.max(0.0, Math.min(1.0, (parameter) / 0.3));
        double smooth = t * t * (3.0 - 2.0 * t);
        return 0.55 + 0.45 * smooth;
    }

    /** Single-sample normalized shadow comparison with a positive bias. */
    static double shadowFactor(double receiverDepth, double storedDepth, double bias) {
        return receiverDepth - bias > storedDepth ? 0.0 : 1.0;
    }

    static double[] normalize(double[] value) {
        double length = Math.sqrt(dot(value, value));
        if (!(length > 1.0e-12)) {
            throw new IllegalArgumentException("reference vector must be non-zero");
        }
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }

    static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    static double[] cross(double[] left, double[] right) {
        return new double[]{left[1] * right[2] - left[2] * right[1],
                left[2] * right[0] - left[0] * right[2],
                left[0] * right[1] - left[1] * right[0]};
    }
}
