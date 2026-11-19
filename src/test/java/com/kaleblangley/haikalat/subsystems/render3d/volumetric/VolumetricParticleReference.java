package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Matrix4fc;

/**
 * Independent double-precision reference for the local analytic particle medium.
 *
 * <p>The model is documented in {@link VolumetricParticleSettings} and in the
 * fragment shader: a quartic ellipsoid falloff, extinction
 * {@code density * (absorption + scattering)}, normalized Henyey-Greenstein
 * phase and single scatter {@code light * phase * albedo * (1 - T)} for a
 * constant light over the ray. This class deliberately implements the same
 * documented quadrature in double precision as a second implementation (the
 * shader is the first), and additionally an exact closed-form optical depth so
 * a shader/reference drift cannot silently move the expected values together.
 * The fixed expected values in {@code VolumetricParticleReferenceTest} were
 * computed from the formula in a separate scratch calculation, not by calling
 * this class.</p>
 *
 * <p>Ray origins are near-plane points, matching the shader's ray
 * reconstruction; directions are unit length and {@code maxDistance} is the
 * distance along the ray to the opaque surface (a very large value means "no
 * occluder").</p>
 */
final class VolumetricParticleReference {
    private static final double PI = 3.14159265358979323846;
    private static final double NO_SURFACE = 1.0e30;
    private static final double MAXIMUM_OPTICAL_DEPTH = 88.0;

    private VolumetricParticleReference() {
    }

    /** World-space view ray with an opaque-surface distance bound. */
    record Ray(double originX, double originY, double originZ,
               double directionX, double directionY, double directionZ,
               double maxDistance) {
    }

    /** A pixel's premultiplied contribution and transmittance. */
    record Contribution(double red, double green, double blue, double transmittance) {
    }

    /**
     * Rebuilds the shader's view ray for one pixel entirely in double precision.
     * The matrix transform uses the documented column-major JOML convention.
     */
    static Ray rayForPixel(Matrix4fc inverseProjection, Matrix4fc inverseView,
                           int width, int height, int pixelX, int pixelY, float rawDepth) {
        double ndcX = 2.0 * (pixelX + 0.5) / width - 1.0;
        double ndcY = 2.0 * (pixelY + 0.5) / height - 1.0;
        double[] near = transform(inverseProjection, ndcX, ndcY, -1.0, 1.0);
        double[] far = transform(inverseProjection, ndcX, ndcY, 1.0, 1.0);
        double nearX = near[0] / near[3];
        double nearY = near[1] / near[3];
        double nearZ = near[2] / near[3];
        double farX = far[0] / far[3];
        double farY = far[1] / far[3];
        double farZ = far[2] / far[3];
        double directionX = farX - nearX;
        double directionY = farY - nearY;
        double directionZ = farZ - nearZ;
        double directionLength = Math.sqrt(directionX * directionX + directionY * directionY
                + directionZ * directionZ);
        directionX /= directionLength;
        directionY /= directionLength;
        directionZ /= directionLength;
        double[] origin = transform(inverseView, nearX, nearY, nearZ, 1.0);
        double[] direction = transformDirection(inverseView, directionX, directionY, directionZ);
        double worldLength = Math.sqrt(direction[0] * direction[0]
                + direction[1] * direction[1] + direction[2] * direction[2]);
        direction[0] /= worldLength;
        direction[1] /= worldLength;
        direction[2] /= worldLength;
        double maxDistance = NO_SURFACE;
        if (rawDepth < 1.0f) {
            double[] surface = transform(inverseProjection, ndcX, ndcY, 2.0 * rawDepth - 1.0, 1.0);
            double surfaceX = surface[0] / surface[3];
            double surfaceY = surface[1] / surface[3];
            double surfaceZ = surface[2] / surface[3];
            maxDistance = Math.sqrt((surfaceX - nearX) * (surfaceX - nearX)
                    + (surfaceY - nearY) * (surfaceY - nearY)
                    + (surfaceZ - nearZ) * (surfaceZ - nearZ));
        }
        return new Ray(origin[0], origin[1], origin[2],
                direction[0], direction[1], direction[2], maxDistance);
    }

    /**
     * Exact optical depth before {@code fogWeight}, by closed-form quartic
     * integration. The ray parameter is shifted to the visible interval start
     * before expanding {@code (1 - q)^2}; expanding at the world origin loses
     * precision by many orders of magnitude when the camera is far away.
     */
    static double analyticOpticalDepth(VolumetricParticleSettings settings, Ray ray) {
        double[] interval = visibleInterval(settings, ray);
        if (interval == null) {
            return 0.0;
        }
        double start = interval[0];
        double end = interval[1];
        double[] origin = scaledPosition(settings,
                ray.originX() + ray.directionX() * start,
                ray.originY() + ray.directionY() * start,
                ray.originZ() + ray.directionZ() * start);
        double[] direction = scaledDirection(settings,
                ray.directionX(), ray.directionY(), ray.directionZ());
        double a = dot(direction, direction);
        double b = 2.0 * dot(origin, direction);
        double c = dot(origin, origin) - 1.0;
        // q(start + u) = 1 + c + b u + a u^2, so 1 - q = -c - b u - a u^2.
        double k0 = c * c;
        double k1 = 2.0 * c * b;
        double k2 = b * b + 2.0 * a * c;
        double k3 = 2.0 * a * b;
        double k4 = a * a;
        double span = end - start;
        double integral = k0 * span + k1 * span * span / 2.0 + k2 * span * span * span / 3.0
                + k3 * span * span * span * span / 4.0
                + k4 * span * span * span * span * span / 5.0;
        return settings.extinction() * integral;
    }

    /** The shader's midpoint quadrature, including the fixed step/budget selection. */
    static double quadratureOpticalDepth(VolumetricParticleSettings settings, Ray ray) {
        double[] interval = visibleInterval(settings, ray);
        if (interval == null) {
            return 0.0;
        }
        double start = interval[0];
        double end = interval[1];
        double span = end - start;
        double stepLength = Math.max(span / settings.maximumSteps(), settings.stepSizeMetres());
        int steps = (int) Math.max(1.0, Math.min(settings.maximumSteps(),
                Math.ceil(span / stepLength)));
        double delta = span / steps;
        double extinction = settings.extinction();
        double opticalDepth = 0.0;
        for (int step = 0; step < steps; step++) {
            double distance = start + (step + 0.5) * delta;
            double worldX = ray.originX() + ray.directionX() * distance;
            double worldY = ray.originY() + ray.directionY() * distance;
            double worldZ = ray.originZ() + ray.directionZ() * distance;
            opticalDepth += extinction * densityWeight(settings, worldX, worldY, worldZ) * delta;
        }
        return opticalDepth;
    }

    /** {@code exp(-min(fogWeight * opticalDepth, 88))}. */
    static double transmittance(VolumetricParticleSettings settings, double opticalDepth) {
        return Math.exp(-Math.min(settings.fogWeight() * opticalDepth, MAXIMUM_OPTICAL_DEPTH));
    }

    /** Normalized Henyey-Greenstein phase with the shader's {@code 0.01} denominator floor. */
    static double phase(VolumetricParticleSettings settings, double cosine) {
        double anisotropy = settings.anisotropy();
        double denominator = Math.max(0.01, 1.0 + anisotropy * anisotropy
                - 2.0 * anisotropy * cosine);
        return (1.0 - anisotropy * anisotropy)
                / (4.0 * PI * denominator * Math.sqrt(denominator));
    }

    /** Single-scatter contribution of the exact model for one ray. */
    static Contribution contribution(VolumetricParticleSettings settings, Ray ray,
                                     boolean exactOpticalDepth) {
        double opticalDepth = exactOpticalDepth
                ? analyticOpticalDepth(settings, ray) : quadratureOpticalDepth(settings, ray);
        return contributionFor(settings, ray, opticalDepth);
    }

    /** Single-scatter contribution for an explicit (already integrated) optical depth. */
    static Contribution contributionFor(VolumetricParticleSettings settings, Ray ray,
                                        double opticalDepth) {
        double transmittance = transmittance(settings, opticalDepth);
        double cosine = dot(settings.lightDirectionInternal().x,
                settings.lightDirectionInternal().y, settings.lightDirectionInternal().z,
                -ray.directionX(), -ray.directionY(), -ray.directionZ());
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        double scale = phase(settings, cosine) * settings.scatteringAlbedo()
                * (1.0 - transmittance);
        return new Contribution(settings.lightColorInternal().x * scale,
                settings.lightColorInternal().y * scale,
                settings.lightColorInternal().z * scale, transmittance);
    }

    /** OCCLUDING blend: premultiplied in-scatter plus transmittance-weighted background. */
    static double[] occluding(VolumetricParticleSettings settings, Ray ray,
                              double[] background, boolean exactOpticalDepth) {
        Contribution contribution = contribution(settings, ray, exactOpticalDepth);
        return new double[]{contribution.red() + contribution.transmittance() * background[0],
                contribution.green() + contribution.transmittance() * background[1],
                contribution.blue() + contribution.transmittance() * background[2]};
    }

    /** ADDITIVE blend: in-scatter over an unchanged background. */
    static double[] additive(VolumetricParticleSettings settings, Ray ray,
                             double[] background, boolean exactOpticalDepth) {
        Contribution contribution = contribution(settings, ray, exactOpticalDepth);
        return new double[]{contribution.red() + background[0],
                contribution.green() + background[1],
                contribution.blue() + background[2]};
    }

    private static double[] visibleInterval(VolumetricParticleSettings settings, Ray ray) {
        double[] origin = scaledPosition(settings, ray.originX(), ray.originY(), ray.originZ());
        double[] direction = scaledDirection(settings,
                ray.directionX(), ray.directionY(), ray.directionZ());
        double a = dot(direction, direction);
        double b = 2.0 * dot(origin, direction);
        double c = dot(origin, origin) - 1.0;
        double discriminant = b * b - 4.0 * a * c;
        if (!(a > 0.0) || !(discriminant > 0.0)) {
            return null;
        }
        double root = Math.sqrt(discriminant);
        double start = Math.max((-b - root) / (2.0 * a), 0.0);
        double end = Math.min((-b + root) / (2.0 * a), ray.maxDistance());
        return end > start ? new double[]{start, end} : null;
    }

    private static double densityWeight(VolumetricParticleSettings settings,
                                        double worldX, double worldY, double worldZ) {
        double[] scaled = scaledPosition(settings, worldX, worldY, worldZ);
        double radiusSquared = dot(scaled, scaled);
        if (radiusSquared >= 1.0) {
            return 0.0;
        }
        double falloff = 1.0 - radiusSquared;
        return falloff * falloff;
    }

    private static double[] scaledPosition(VolumetricParticleSettings settings,
                                           double worldX, double worldY, double worldZ) {
        return new double[]{
                (worldX - settings.boundsCenterInternal().x) / settings.boundsHalfExtentInternal().x,
                (worldY - settings.boundsCenterInternal().y) / settings.boundsHalfExtentInternal().y,
                (worldZ - settings.boundsCenterInternal().z) / settings.boundsHalfExtentInternal().z};
    }


    private static double[] scaledDirection(VolumetricParticleSettings settings,
                                            double directionX, double directionY, double directionZ) {
        return new double[]{directionX / settings.boundsHalfExtentInternal().x,
                directionY / settings.boundsHalfExtentInternal().y,
                directionZ / settings.boundsHalfExtentInternal().z};
    }

    private static double[] transform(Matrix4fc matrix, double x, double y, double z, double w) {
        return new double[]{
                matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30() * w,
                matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31() * w,
                matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32() * w,
                matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33() * w};
    }

    private static double[] transformDirection(Matrix4fc matrix,
                                               double x, double y, double z) {
        return new double[]{
                matrix.m00() * x + matrix.m10() * y + matrix.m20() * z,
                matrix.m01() * x + matrix.m11() * y + matrix.m21() * z,
                matrix.m02() * x + matrix.m12() * y + matrix.m22() * z};
    }

    private static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    private static double dot(double ax, double ay, double az,
                              double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz;
    }
}
