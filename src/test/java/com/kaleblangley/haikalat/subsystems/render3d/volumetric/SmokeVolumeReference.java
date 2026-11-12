package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Independent double-precision reference for the smoke volume consumer.
 *
 * <p>It re-implements the documented march from
 * {@link SmokeRenderSettings}: world-ray reconstruction from the inverse
 * projection/view, slab intersection with the grid AABB, truncation at the
 * opaque surface, fixed target step with a hard budget, trilinear density
 * sampling of the read-back field (matching the linear {@code sampler3D}) and
 * the single-scatter integral
 * {@code sum(T_i * light * phase * albedo * (1 - exp(-extinction_i * delta)))}.
 * It never calls the production shader or a production helper.</p>
 */
final class SmokeVolumeReference {
    private static final double PI = 3.14159265358979323846;
    private static final double NO_SURFACE = 1.0e30;
    private static final double MAXIMUM_OPTICAL_DEPTH = 88.0;

    private SmokeVolumeReference() {
    }

    /** World-space view ray with an opaque-surface distance bound. */
    record Ray(double originX, double originY, double originZ,
               double directionX, double directionY, double directionZ,
               double maxDistance) {
    }

    /** A pixel's premultiplied contribution and transmittance. */
    record Contribution(double red, double green, double blue, double transmittance) {
    }

    /** Rebuilds the shader's view ray for one pixel entirely in double precision. */
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
     * Marches the read-back field and returns the premultiplied contribution.
     *
     * @param density flattened field indexed {@code x + nx * (y + ny * z)}
     */
    static Contribution contribution(SmokeRenderSettings settings, Vector3f center,
                                     Vector3f halfExtent, double[] density,
                                     int nx, int ny, int nz, Ray ray) {
        double[] interval = boxInterval(center, halfExtent, ray);
        double intervalStart = Math.max(interval[0], 0.0);
        double intervalEnd = Math.min(interval[1], ray.maxDistance());
        if (!(intervalEnd > intervalStart)) {
            return new Contribution(0.0, 0.0, 0.0, 1.0);
        }
        double span = intervalEnd - intervalStart;
        double stepLength = Math.max(span / settings.maximumSteps(), settings.stepSizeMetres());
        int steps = (int) Math.max(1.0, Math.min(settings.maximumSteps(),
                Math.ceil(span / stepLength)));
        double delta = span / steps;
        double coefficient = settings.densityScale()
                * (settings.absorption() + settings.scattering());
        double albedo = settings.scatteringAlbedo();
        double cosine = clamp(dot(settings.lightDirection(), -ray.directionX(),
                -ray.directionY(), -ray.directionZ()), -1.0, 1.0);
        double phase = phase(settings.anisotropy(), cosine);
        double transmittance = 1.0;
        double scatterRed = 0.0;
        double scatterGreen = 0.0;
        double scatterBlue = 0.0;
        for (int step = 0; step < steps; step++) {
            double distance = intervalStart + (step + 0.5) * delta;
            double worldX = ray.originX() + ray.directionX() * distance;
            double worldY = ray.originY() + ray.directionY() * distance;
            double worldZ = ray.originZ() + ray.directionZ() * distance;
            double sampled = sample(density, nx, ny, nz, center, halfExtent,
                    worldX, worldY, worldZ);
            double opticalDepth = coefficient * sampled * delta * settings.contributionWeight();
            double sampleTransmittance = Math.exp(-Math.min(opticalDepth,
                    MAXIMUM_OPTICAL_DEPTH));
            double weight = transmittance * phase * albedo * (1.0 - sampleTransmittance);
            scatterRed += settings.lightColor().x * weight;
            scatterGreen += settings.lightColor().y * weight;
            scatterBlue += settings.lightColor().z * weight;
            transmittance *= sampleTransmittance;
        }
        return new Contribution(scatterRed, scatterGreen, scatterBlue, transmittance);
    }

    /** Composites the contribution over a background exactly like the pass blend. */
    static double[] composited(SmokeRenderSettings settings, Contribution contribution,
                               double[] background) {
        if (settings.blend() == SmokeRenderSettings.Blend.OCCLUDING) {
            return new double[]{
                    contribution.red() + contribution.transmittance() * background[0],
                    contribution.green() + contribution.transmittance() * background[1],
                    contribution.blue() + contribution.transmittance() * background[2]};
        }
        return new double[]{contribution.red() + background[0],
                contribution.green() + background[1],
                contribution.blue() + background[2]};
    }

    /** Trilinear sampling with clamped edges, matching a linear {@code sampler3D}. */
    static double sample(double[] density, int nx, int ny, int nz,
                         Vector3f center, Vector3f halfExtent,
                         double worldX, double worldY, double worldZ) {
        double minX = center.x - halfExtent.x;
        double minY = center.y - halfExtent.y;
        double minZ = center.z - halfExtent.z;
        double u = clamp((worldX - minX) / (2.0 * halfExtent.x), 0.0, 1.0);
        double v = clamp((worldY - minY) / (2.0 * halfExtent.y), 0.0, 1.0);
        double w = clamp((worldZ - minZ) / (2.0 * halfExtent.z), 0.0, 1.0);
        double x = u * nx - 0.5;
        double y = v * ny - 0.5;
        double z = w * nz - 0.5;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;
        double result = 0.0;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dx = 0; dx <= 1; dx++) {
                    int ix = clampIndex(x0 + dx, nx);
                    int iy = clampIndex(y0 + dy, ny);
                    int iz = clampIndex(z0 + dz, nz);
                    double wx = dx == 0 ? 1.0 - fx : fx;
                    double wy = dy == 0 ? 1.0 - fy : fy;
                    double wz = dz == 0 ? 1.0 - fz : fz;
                    result += density[ix + nx * (iy + ny * iz)] * wx * wy * wz;
                }
            }
        }
        return result;
    }

    private static double[] boxInterval(Vector3f center, Vector3f halfExtent, Ray ray) {
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        double[][] axes = {
                slabAxis(ray.originX(), ray.directionX(),
                        center.x - halfExtent.x, center.x + halfExtent.x),
                slabAxis(ray.originY(), ray.directionY(),
                        center.y - halfExtent.y, center.y + halfExtent.y),
                slabAxis(ray.originZ(), ray.directionZ(),
                        center.z - halfExtent.z, center.z + halfExtent.z)};
        for (double[] axis : axes) {
            if (axis[0] > axis[1]) {
                return new double[]{1.0, -1.0};
            }
            near = Math.max(near, axis[0]);
            far = Math.min(far, axis[1]);
        }
        return new double[]{near, far};
    }

    private static double[] slabAxis(double origin, double direction, double minimum,
                                     double maximum) {
        if (Math.abs(direction) < 1.0e-15) {
            if (origin < minimum || origin > maximum) {
                return new double[]{Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY};
            }
            return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        }
        double first = (minimum - origin) / direction;
        double second = (maximum - origin) / direction;
        return new double[]{Math.min(first, second), Math.max(first, second)};
    }

    private static double phase(double anisotropy, double cosine) {
        double denominator = Math.max(0.01,
                1.0 + anisotropy * anisotropy - 2.0 * anisotropy * cosine);
        return (1.0 - anisotropy * anisotropy) / (4.0 * PI * denominator * Math.sqrt(denominator));
    }

    private static double dot(Vector3f direction, double x, double y, double z) {
        return direction.x * x + direction.y * y + direction.z * z;
    }

    private static int clampIndex(int value, int extent) {
        return Math.max(0, Math.min(extent - 1, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double[] transform(Matrix4fc matrix, double x, double y, double z, double w) {
        return new double[]{
                matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30() * w,
                matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31() * w,
                matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32() * w,
                matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33() * w};
    }

    private static double[] transformDirection(Matrix4fc matrix, double x, double y, double z) {
        return new double[]{
                matrix.m00() * x + matrix.m10() * y + matrix.m20() * z,
                matrix.m01() * x + matrix.m11() * y + matrix.m21() * z,
                matrix.m02() * x + matrix.m12() * y + matrix.m22() * z};
    }
}
