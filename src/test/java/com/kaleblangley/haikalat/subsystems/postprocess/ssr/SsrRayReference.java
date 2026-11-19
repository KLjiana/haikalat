package com.kaleblangley.haikalat.subsystems.postprocess.ssr;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Independent CPU reference for the screen-space reflection contract.
 *
 * <p>This class is deliberately test-only.  It repeats the documented march in
 * view space with its own data flow (projector callback, probe callback,
 * explicit smoothing helpers) instead of calling the production shader or pass,
 * so the JVM expectations are not derived from the implementation under test.
 * The real-GL test uses the mirror geometry directly instead of this class,
 * because a CPU float march cannot predict half-float raster rounding.</p>
 *
 * <p>Conventions: view space is right-handed with the camera at the origin and
 * forward along {@code -Z}; "view depth" is the positive distance
 * {@code -z}. A probe returns a non-positive depth for background pixels.</p>
 */
final class SsrRayReference {
    private SsrRayReference() {
    }

    /** One device-pixel projection of a view-space point. */
    interface Projector {
        /**
         * @return true when the point is in front of the camera and projected
         * inside {@code [0,1]^2}
         */
        boolean project(Vector3fc viewPosition, Vector2f destination);
    }

    /** Spatial surface lookup used by the march. */
    interface Probe {
        /** @return positive view-space depth, or a non-positive value for background */
        float viewDepth(float u, float v);

        /** @return unit view-space surface normal */
        Vector3fc viewNormal(float u, float v);
    }

    /** Bounded march parameters in view-space units. */
    record March(float maxDistance, int steps, float thickness, float depthBias) {
        March {
            if (!(maxDistance > 0.0f) || steps < 1 || thickness <= 0.0f) {
                throw new IllegalArgumentException("invalid march bounds");
            }
        }
    }

    /** Result of one bounded march. */
    record Result(boolean hit, float confidence, float weight, float u, float v,
                  float separation, int samples) {
        static Result missed(int samples) {
            return new Result(false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, samples);
        }
    }

    /**
     * The documented eligibility gate: rough, backfacing and grazing receivers
     * keep the environment fallback.
     */
    static boolean eligible(float roughness, float roughnessCutoff, float nDotV) {
        return roughness <= roughnessCutoff && nDotV > 0.05f;
    }

    /**
     * Marches one reflection ray in view space.
     *
     * @param origin     receiver position in view space
     * @param direction  unit reflected direction in view space
     * @param nDotV      clamped dot(receiver normal, toward camera)
     * @param width      raster width in pixels
     * @param height     raster height in pixels
     * @param edgeFadePixels  raster-border confidence band
     * @param minimumConfidence confidence floor below which the result is rejected
     */
    static Result trace(Vector3fc origin, Vector3fc direction, float nDotV,
                        Projector projector, Probe probe, March marchHandle,
                        int width, int height, float edgeFadePixels, float minimumConfidence) {
        Vector2f uv = new Vector2f();
        float hitT = -1.0f;
        float hitU = 0.0f;
        float hitV = 0.0f;
        int samples = 0;
        for (int step = 1; step <= marchHandle.steps(); step++) {
            samples++;
            float t = step / (float) marchHandle.steps();
            float distance = marchHandle.maxDistance() * t;
            float x = origin.x() + direction.x() * distance;
            float y = origin.y() + direction.y() * distance;
            float z = origin.z() + direction.z() * distance;
            float rayDepth = -z;
            if (rayDepth <= 0.0f) break;
            if (!projector.project(new Vector3f(x, y, z), uv)) break;
            float surfaceDepth = probe.viewDepth(uv.x, uv.y);
            if (surfaceDepth <= 0.0f) {
                continue;
            }
            float separation = rayDepth - surfaceDepth;
            if (separation > marchHandle.depthBias()) {
                if (separation <= marchHandle.thickness()) {
                    hitT = t;
                    hitU = uv.x;
                    hitV = uv.y;
                }
                break;
            }
        }
        if (hitT <= 0.0f) return Result.missed(samples);
        float x = origin.x() + direction.x() * (marchHandle.maxDistance() * hitT);
        float y = origin.y() + direction.y() * (marchHandle.maxDistance() * hitT);
        float z = origin.z() + direction.z() * (marchHandle.maxDistance() * hitT);
        float hitRayDepth = -z;
        float hitSurfaceDepth = probe.viewDepth(hitU, hitV);
        if (hitSurfaceDepth <= 0.0f) return Result.missed(samples);
        float separation = Math.max(hitRayDepth - hitSurfaceDepth, 0.0f);
        Vector3fc hitNormal = probe.viewNormal(hitU, hitV);
        float facing = clamp(-dot(hitNormal, direction), 0.0f, 1.0f);
        float edgePixels = edgeDistancePixels(hitU, hitV, width, height);
        float confidence = confidence(separation, marchHandle.thickness(), edgePixels,
                edgeFadePixels, facing, nDotV, hitT);
        float weight = hitWeight(confidence, minimumConfidence);
        return new Result(weight > 0.0f, confidence, weight, hitU, hitV, separation, samples);
    }

    /** Documented confidence composition; every term is in {@code [0,1]}. */
    static float confidence(float separation, float thickness, float edgePixels,
                            float edgeFadePixels, float facing, float nDotV, float along) {
        float depthConfidence = 1.0f
                - clamp(separation / Math.max(thickness, 1.0e-4f), 0.0f, 1.0f);
        float edgeConfidence = edgeFadePixels <= 0.0f ? 1.0f
                : smoothstep(0.0f, edgeFadePixels, edgePixels);
        float grazingConfidence = smoothstep(0.05f, 0.25f, nDotV);
        float distanceConfidence = 1.0f - smoothstep(0.75f, 1.0f, along);
        return depthConfidence * edgeConfidence * clamp(facing, 0.0f, 1.0f)
                * grazingConfidence * distanceConfidence;
    }

    /** Soft confidence floor; below the floor the environment fallback stays exact. */
    static float hitWeight(float confidence, float minimumConfidence) {
        if (confidence < minimumConfidence) return 0.0f;
        return confidence * smoothstep(minimumConfidence, 1.0f, confidence);
    }

    /**
     * Replacement composite: {@code environmentRadiance} is what the forward
     * PBR pass already added, so a zero weight returns the input bit-exactly.
     */
    static Vector3f composite(Vector3fc base, Vector3fc environmentRadiance,
                              Vector3fc hitRadiance, float fresnel, float strength,
                              float hitWeight) {
        float weight = clamp(strength * clamp(fresnel, 0.0f, 1.0f) * hitWeight, 0.0f, 1.0f);
        return new Vector3f(base).add(new Vector3f(hitRadiance)
                .sub(new Vector3f(environmentRadiance)).mul(weight));
    }

    static float edgeDistancePixels(float u, float v, int width, int height) {
        float x = u * width;
        float y = v * height;
        return Math.min(Math.min(x, width - x), Math.min(y, height - y));
    }

    static float fresnelSchlickRoughness(float nDotV, float f0, float roughness) {
        return f0 + (Math.max(1.0f - roughness, f0) - f0)
                * (float) Math.pow(clamp(1.0f - nDotV, 0.0f, 1.0f), 5.0);
    }

    static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x < edge0 ? 0.0f : 1.0f;
        float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    static float clamp(float value, float minimum, float maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    private static float dot(Vector3fc a, Vector3fc b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }
}
