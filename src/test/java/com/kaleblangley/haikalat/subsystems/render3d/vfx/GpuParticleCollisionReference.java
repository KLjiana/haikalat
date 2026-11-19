package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.joml.Vector3f;

/**
 * Independent CPU reference for the bounded depth-collision contract.
 *
 * <p>This is a plain Java re-statement of the documented response, not a call
 * into production code: a particle that lies behind the reconstructed surface
 * plane by at most {@code thickness} world units is pushed to one skin width in
 * front of the plane and its inward normal velocity is reflected with
 * {@code restitution}; {@code friction} removes the same fraction of the
 * tangential velocity. Double precision is used internally and the result is
 * returned as floats, so hand-computed expectations can be compared with GPU
 * reads within a small tolerance.</p>
 *
 * <p>{@link #horizontalPlaneSurface} models the screen-space reconstruction for
 * the fixed GL fixture: the camera ray through the particle intersects a
 * horizontal plane, which is exactly the surface the depth texture represents
 * for a large flat floor.</p>
 */
final class GpuParticleCollisionReference {
    /** Skin width used by the shader to keep the resolved particle in front. */
    static final float MINIMUM_SKIN = 1.0e-3f;

    private GpuParticleCollisionReference() {
    }

    /**
     * Resolves one particle against a surface plane.
     *
     * @param px particle x
     * @param py particle y
     * @param pz particle z
     * @param vx particle velocity x
     * @param vy particle velocity y
     * @param vz particle velocity z
     * @param sx surface point x
     * @param sy surface point y
     * @param sz surface point z
     * @param nx surface normal x (normalized here)
     * @param ny surface normal y (normalized here)
     * @param nz surface normal z (normalized here)
     * @param thickness world-space thickness band, must be positive
     * @param restitution normal-velocity scale in [0, 1]
     * @param friction tangential-velocity loss in [0, 1]
     * @return the resulting position, velocity and whether the particle collided
     */
    static Result resolve(float px, float py, float pz, float vx, float vy, float vz,
                          float sx, float sy, float sz, float nx, float ny, float nz,
                          float thickness, float restitution, float friction) {
        requireFinite(px, py, pz, vx, vy, vz, sx, sy, sz, nx, ny, nz);
        if (!(Float.isFinite(thickness) && thickness > 0.0f)) {
            throw new IllegalArgumentException("thickness must be finite and positive");
        }
        if (!(restitution >= 0.0f && restitution <= 1.0f)) {
            throw new IllegalArgumentException("restitution must be in [0, 1]");
        }
        if (!(friction >= 0.0f && friction <= 1.0f)) {
            throw new IllegalArgumentException("friction must be in [0, 1]");
        }
        double normalLength = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
        if (!(normalLength > 1.0e-12)) {
            throw new IllegalArgumentException("surface normal must be non-zero");
        }
        double ux = nx / normalLength;
        double uy = ny / normalLength;
        double uz = nz / normalLength;

        double penetration = ((double) px - sx) * ux
                + ((double) py - sy) * uy + ((double) pz - sz) * uz;
        if (!(penetration < 0.0) || -penetration > thickness) {
            return new Result(false, px, py, pz, vx, vy, vz);
        }
        double skin = Math.max(MINIMUM_SKIN, (double) thickness * 1.0e-3);
        double resolvedX = px + ux * (-penetration + skin);
        double resolvedY = py + uy * (-penetration + skin);
        double resolvedZ = pz + uz * (-penetration + skin);

        double normalVelocity = (double) vx * ux + (double) vy * uy + (double) vz * uz;
        double outX = vx;
        double outY = vy;
        double outZ = vz;
        if (normalVelocity < 0.0) {
            double tangentX = vx - ux * normalVelocity;
            double tangentY = vy - uy * normalVelocity;
            double tangentZ = vz - uz * normalVelocity;
            double tangentialScale = 1.0 - friction;
            outX = tangentX * tangentialScale - ux * (restitution * normalVelocity);
            outY = tangentY * tangentialScale - uy * (restitution * normalVelocity);
            outZ = tangentZ * tangentialScale - uz * (restitution * normalVelocity);
        }
        return new Result(true, (float) resolvedX, (float) resolvedY, (float) resolvedZ,
                (float) outX, (float) outY, (float) outZ);
    }

    /**
     * Models the depth reconstruction of the fixed floor fixture: the camera ray
     * through {@code point} intersects the horizontal plane {@code y = planeY}.
     *
     * @param cameraPosition camera world position
     * @param point          particle world position
     * @param planeY         horizontal receiver plane height
     * @return the surface point on that plane
     */
    static Vector3f horizontalPlaneSurface(Vector3f cameraPosition, Vector3f point,
                                           float planeY) {
        if (!(point.y - cameraPosition.y != 0.0f) || !Float.isFinite(point.y)) {
            throw new IllegalArgumentException("camera ray must not be parallel to the plane");
        }
        double directionY = (double) point.y - cameraPosition.y;
        double parameter = ((double) planeY - cameraPosition.y) / directionY;
        if (!(parameter > 0.0) || !Double.isFinite(parameter)) {
            throw new IllegalArgumentException("the plane must lie in front of the camera");
        }
        return new Vector3f(
                (float) (cameraPosition.x + ((double) point.x - cameraPosition.x) * parameter),
                planeY,
                (float) (cameraPosition.z + ((double) point.z - cameraPosition.z) * parameter));
    }

    private static void requireFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("collision reference inputs must be finite");
            }
        }
    }

    /**
     * @param collided     whether the particle was inside the thickness band
     * @param x            resolved world x
     * @param y            resolved world y
     * @param z            resolved world z
     * @param velocityX    resolved velocity x
     * @param velocityY    resolved velocity y
     * @param velocityZ    resolved velocity z
     */
    record Result(boolean collided, float x, float y, float z,
                  float velocityX, float velocityY, float velocityZ) {
    }
}
