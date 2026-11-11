package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Independent CPU reference for the refraction contract.  This class lives in
 * test sources and deliberately does not call production helpers: it is the
 * oracle for the offset direction/magnitude assertions and for sampling an
 * independently rendered background together with the real-GL test.
 */
public final class RefractionReferenceMath {
    private RefractionReferenceMath() {
    }

    /** Hand-coded Snell refraction; returns {@code incident} for total internal reflection. */
    public static Vector3f refract(Vector3f incident, Vector3f normal, float ior) {
        Vector3f i = new Vector3f(incident).normalize();
        Vector3f n = new Vector3f(normal).normalize();
        float cosIncident = -i.dot(n);
        if (cosIncident < 0.0f) {
            n.negate();
            cosIncident = -cosIncident;
        }
        float eta = 1.0f / ior;
        float k = 1.0f - eta * eta * (1.0f - cosIncident * cosIncident);
        if (k < 0.0f) return new Vector3f(i);
        Vector3f transmitted = new Vector3f(i).mul(eta)
                .add(new Vector3f(n).mul(eta * cosIncident - (float) Math.sqrt(k)));
        return transmitted.normalize();
    }

    /** Projects a world point into bottom-left-origin UV; returns null behind the camera. */
    public static Vector2f projectToUv(Matrix4f projection, Matrix4f view, Vector3f world) {
        Vector4f clip = new Matrix4f(projection).mul(view).transform(new Vector4f(world, 1.0f));
        if (clip.w <= 1.0e-6f) return null;
        return new Vector2f(clip.x / clip.w * 0.5f + 0.5f,
                clip.y / clip.w * 0.5f + 0.5f);
    }

    /**
     * Screen-space offset produced by the documented single-layer model:
     * refract the camera-to-surface direction, advance by {@code thickness},
     * and subtract the original pixel centre UV.
     */
    public static Vector2f screenOffsetUv(Matrix4f projection, Matrix4f view,
                                          Vector3f world, Vector3f normal,
                                          Vector3f camera, float ior, float thickness,
                                          int width, int height) {
        // The caller supplies the fragment's world position; its projection is
        // the pixel-centre UV used by gl_FragCoord in the shader.
        Vector2f centre = projectToUv(projection, view, world);
        if (centre == null) return new Vector2f();
        Vector3f incident = new Vector3f(world).sub(camera).normalize();
        Vector3f refracted = refract(incident, normal, ior);
        Vector3f refractedWorld = new Vector3f(world)
                .add(new Vector3f(refracted).mul(thickness));
        Vector2f shifted = projectToUv(projection, view, refractedWorld);
        if (shifted == null) return new Vector2f();
        return shifted.sub(centre);
    }

    /** World-space point where the camera ray through a pixel meets a Z plane. */
    public static Vector3f worldOnZPlane(Matrix4f projection, Matrix4f view,
                                         float pixelX, float pixelY,
                                         int width, int height, float planeZ) {
        return worldOnPlane(projection, view, pixelX, pixelY, width, height,
                new Vector3f(0.0f, 0.0f, planeZ), new Vector3f(0.0f, 0.0f, 1.0f));
    }

    /** World-space point where the camera ray through a pixel meets an arbitrary plane. */
    public static Vector3f worldOnPlane(Matrix4f projection, Matrix4f view,
                                        float pixelX, float pixelY,
                                        int width, int height,
                                        Vector3f planePoint, Vector3f planeNormal) {
        float ndcX = (pixelX + 0.5f) / width * 2.0f - 1.0f;
        float ndcY = (pixelY + 0.5f) / height * 2.0f - 1.0f;
        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        Vector4f near = inverse.transform(new Vector4f(ndcX, ndcY, -1.0f, 1.0f));
        Vector4f far = inverse.transform(new Vector4f(ndcX, ndcY, 1.0f, 1.0f));
        near.div(near.w);
        far.div(far.w);
        Vector3f origin = new Vector3f(near.x, near.y, near.z);
        Vector3f direction = new Vector3f(far.x, far.y, far.z).sub(origin).normalize();
        Vector3f normal = new Vector3f(planeNormal).normalize();
        float denominator = direction.dot(normal);
        if (Math.abs(denominator) < 1.0e-8f) return null;
        float distance = new Vector3f(planePoint).sub(origin).dot(normal) / denominator;
        if (distance <= 0.0f) return null;
        return origin.add(direction.mul(distance));
    }

    /**
     * Bilinear sample of a bottom-up RGBA float capture with clamped edges,
     * matching the graph colour attachment's linear/clamp texture contract.
     */
    public static float[] sampleBilinear(float[] rgba, int width, int height,
                                         float u, float v) {
        float x = u * width - 0.5f;
        float y = v * height - 0.5f;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float[] result = new float[4];
        for (int channel = 0; channel < 4; channel++) {
            float a = texel(rgba, width, height, x0, y0, channel);
            float b = texel(rgba, width, height, x0 + 1, y0, channel);
            float c = texel(rgba, width, height, x0, y0 + 1, channel);
            float d = texel(rgba, width, height, x0 + 1, y0 + 1, channel);
            result[channel] = (a * (1.0f - fx) + b * fx) * (1.0f - fy)
                    + (c * (1.0f - fx) + d * fx) * fy;
        }
        return result;
    }

    private static float texel(float[] rgba, int width, int height,
                               int x, int y, int channel) {
        int clampedX = Math.max(0, Math.min(width - 1, x));
        int clampedY = Math.max(0, Math.min(height - 1, y));
        return rgba[(clampedY * width + clampedX) * 4 + channel];
    }
}
