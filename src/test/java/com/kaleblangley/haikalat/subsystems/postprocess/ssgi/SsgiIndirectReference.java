package com.kaleblangley.haikalat.subsystems.postprocess.ssgi;

import com.kaleblangley.haikalat.subsystems.postprocess.SsgiSettings;
import org.joml.Matrix4d;

import java.util.Objects;

/**
 * Independent double-precision reference for the bounded screen-space indirect
 * diffuse model used by this test source set.
 *
 * <p>This class intentionally does not call any production helper. It evaluates
 * the documented equations of {@code ssgi.frag} from fixed synthetic inputs so a
 * drift in the shader, the sampling sequence, the fallback clamp or the
 * compositing weight can be detected without a GPU. The real-GL test compares
 * its pixels against {@link #renderPixel} for the same fixture; the JVM tests
 * pin the analytic invariants (uniform ambient no-op, offscreen budget clamp,
 * albedo/strength scaling and boundedness).</p>
 */
public final class SsgiIndirectReference {
    /** Mirrors {@code SSGI_PI} in ssgi.frag. */
    public static final double PI = 3.14159265359;
    /** Mirrors {@code SSGI_GOLDEN_RATIO}. */
    public static final double GOLDEN_RATIO = 0.61803398875;
    /** Mirrors {@code SSGI_DEPTH_BIAS}. */
    public static final double DEPTH_BIAS = 0.02;
    /** Mirrors {@code SSGI_MIN_HIT_DISTANCE_METRES}. */
    public static final double MINIMUM_HIT_DISTANCE = 0.05;
    /** Mirrors {@code SSGI_BACKFACE_EPSILON}. */
    public static final double BACKFACE_EPSILON = 0.05;

    private SsgiIndirectReference() {
    }

    /** Synthetic single-sample surface inputs in raster order (origin bottom-left). */
    public record Scene(int width, int height, double[] colorRgb, double[] depth,
                        double[] normalOct, double[] roughness, double[] albedoRgb) {
        public Scene {
            Objects.requireNonNull(colorRgb, "colorRgb");
            Objects.requireNonNull(depth, "depth");
            Objects.requireNonNull(normalOct, "normalOct");
            Objects.requireNonNull(roughness, "roughness");
            Objects.requireNonNull(albedoRgb, "albedoRgb");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("scene extent must be positive");
            }
            int pixels = width * height;
            if (colorRgb.length != pixels * 3 || albedoRgb.length != pixels * 3
                    || depth.length != pixels || normalOct.length != pixels * 2
                    || roughness.length != pixels) {
                throw new IllegalArgumentException("scene arrays do not match the extent");
            }
        }

        int pixel(double u, double v) {
            int x = clamp((int) Math.floor(u * width), 0, width - 1);
            int y = clamp((int) Math.floor(v * height), 0, height - 1);
            return y * width + x;
        }
    }

    /** Camera matrices used by the pass; world == view when the view is identity. */
    public record Camera(Matrix4d inverseProjection, Matrix4d projection, Matrix4d view) {
        public Camera {
            Objects.requireNonNull(inverseProjection, "inverseProjection");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(view, "view");
        }

        /** Identity camera for fixtures that work directly in view space. */
        public static Camera identity(Matrix4d projection) {
            return new Camera(new Matrix4d(projection).invert(), new Matrix4d(projection),
                    new Matrix4d());
        }
    }

    /** Directional irradiance lookup; called once per receiver pixel. */
    @FunctionalInterface
    public interface Irradiance {
        /** Writes the irradiance RGB in {@code outRgb} for a world-space normal. */
        void sample(double normalX, double normalY, double normalZ, double[] outRgb);
    }

    /** Borrowed environment description, or the uniform ambient fallback. */
    public record Environment(boolean available, double intensity, double rotationRadians,
                              Irradiance irradiance) {
        public static Environment uniform(double red, double green, double blue) {
            return new Environment(true, 1.0, 0.0,
                    (x, y, z, out) -> {
                        out[0] = red;
                        out[1] = green;
                        out[2] = blue;
                    });
        }

        public static Environment unavailable() {
            return new Environment(false, 0.0, 0.0, null);
        }
    }

    /**
     * Evaluates one pixel exactly as the shader does.
     *
     * @return the composited linear RGB
     */
    public static double[] renderPixel(Scene scene, Camera camera, SsgiSettings settings,
                                       Environment environment, int pixelX, int pixelY) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(environment, "environment");
        if (pixelX < 0 || pixelX >= scene.width() || pixelY < 0 || pixelY >= scene.height()) {
            throw new IllegalArgumentException("pixel outside the fixture");
        }
        double u = (pixelX + 0.5) / scene.width();
        double v = (pixelY + 0.5) / scene.height();
        int center = pixelY * scene.width() + pixelX;
        double[] base = colour(scene, center);
        double depth = scene.depth()[center];
        if (depth >= 1.0) {
            return base;
        }
        double[] albedo = albedo(scene, center);
        if (albedo[0] + albedo[1] + albedo[2] <= 0.0) {
            return base;
        }
        double[] oct = octAt(scene, center);
        double[] normal = octDecode(oct[0], oct[1]);

        double[] viewPosition = viewPosition(camera, u, v, depth);
        if (viewPosition == null) {
            return base;
        }

        double[] ambientIrradiance = new double[3];
        if (environment.available()) {
            double[] rotated = rotate(environment.rotationRadians(), normal);
            environment.irradiance().sample(rotated[0], rotated[1], rotated[2],
                    ambientIrradiance);
            for (int channel = 0; channel < 3; channel++) {
                ambientIrradiance[channel] = Math.max(0.0, ambientIrradiance[channel]
                        * Math.max(environment.intensity(), 0.0));
            }
        } else {
            ambientIrradiance[0] = settings.ambientFallbackRed() * PI;
            ambientIrradiance[1] = settings.ambientFallbackGreen() * PI;
            ambientIrradiance[2] = settings.ambientFallbackBlue() * PI;
        }
        double[] ambientRadiance = {
                ambientIrradiance[0] / PI, ambientIrradiance[1] / PI,
                ambientIrradiance[2] / PI};

        int sampleCount = clamp(settings.sampleCount(), 1, SsgiSettings.MAXIMUM_SAMPLES);
        int stepCount = clamp(settings.stepCount(), 1, SsgiSettings.MAXIMUM_STEPS);
        double maximumDistance = Math.max(settings.maxDistanceMetres(), 1.0e-4);
        double thickness = Math.max(1.0e-4,
                Math.min(settings.thicknessMetres(), maximumDistance));
        double rotation = interleavedGradientNoise(pixelX + 0.5, pixelY + 0.5);

        double[] hitRadiance = new double[3];
        int offscreenSamples = 0;

        for (int sample = 0; sample < sampleCount; sample++) {
            double[] worldDirection = cosineHemisphere(normal, sample, sampleCount, rotation);
            double[] viewDirection = transformDirection(camera.view(), worldDirection);
            viewDirection = normalize(viewDirection);
            boolean offscreen = false;

            for (int step = 0; step < stepCount; step++) {
                double distance = maximumDistance * (step + 0.5) / stepCount;
                double[] samplePosition = {
                        viewPosition[0] + viewDirection[0] * distance,
                        viewPosition[1] + viewDirection[1] * distance,
                        viewPosition[2] + viewDirection[2] * distance};
                double[] clip = transform(camera.projection(), samplePosition[0],
                        samplePosition[1], samplePosition[2], 1.0);
                if (clip[3] <= 0.0) {
                    offscreen = true;
                    break;
                }
                double sampleU = clip[0] / clip[3] * 0.5 + 0.5;
                double sampleV = clip[1] / clip[3] * 0.5 + 0.5;
                if (sampleU < 0.0 || sampleU > 1.0 || sampleV < 0.0 || sampleV > 1.0) {
                    offscreen = true;
                    break;
                }
                int samplePixel = scene.pixel(sampleU, sampleV);
                if (samplePixel == center) continue;
                double sceneDepth = scene.depth()[samplePixel];
                if (sceneDepth >= 1.0) break;
                double rayDepth = -samplePosition[2];
                double sceneViewDepth = viewDepthAt(camera, sampleU, sampleV, sceneDepth);
                double separation = rayDepth - sceneViewDepth;
                double bias = Math.max(DEPTH_BIAS, 0.002 * sceneViewDepth);
                if (separation <= bias) continue;
                if (separation > thickness) break;
                if (distance < MINIMUM_HIT_DISTANCE) continue;
                double[] hitOct = octAt(scene, samplePixel);
                double[] hitNormal = octDecode(hitOct[0], hitOct[1]);
                double facing = clamp(-dot(hitNormal, worldDirection), 0.0, 1.0);
                if (facing <= BACKFACE_EPSILON) break;
                double roughWeight = sampleRoughnessWeight(
                        scene.roughness()[samplePixel], settings.minimumSampleRoughness());
                double edge = borderFade(sampleU, sampleV, scene.width(), scene.height(),
                        settings.edgeFadePixels());
                double confidence = facing * roughWeight * edge;
                if (confidence > 0.0) {
                    double[] radiance = colour(scene, samplePixel);
                    for (int channel = 0; channel < 3; channel++) {
                        hitRadiance[channel] += radiance[channel] * confidence;
                    }
                }
                break;
            }
            if (offscreen) {
                offscreenSamples++;
            }
        }

        double[] gathered = new double[3];
        double estimator = PI / sampleCount;
        for (int channel = 0; channel < 3; channel++) {
            gathered[channel] = hitRadiance[channel] * estimator;
        }
        double offscreenFraction = (double) offscreenSamples / sampleCount;
        double offscreenBudget = Math.min(offscreenFraction,
                Math.max(settings.maximumOffscreenFraction(), 0.0));
        for (int channel = 0; channel < 3; channel++) {
            gathered[channel] += ambientRadiance[channel] * offscreenBudget * PI;
        }
        double strength = clamp(settings.strength(), 0.0, 1.0);
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double added = albedo[channel] * Math.max(gathered[channel], 0.0) * strength / PI;
            double value = base[channel] + added;
            result[channel] = Double.isFinite(value) ? value : base[channel];
        }
        return result;
    }

    /** Deterministic cosine-weighted hemisphere direction, shader order. */
    public static double[] cosineHemisphere(double[] normal, int index, int sampleCount,
                                            double rotation) {
        double count = sampleCount;
        double u1 = (index + 0.5) / count;
        double u2 = fractional((index + 0.5) * GOLDEN_RATIO + rotation);
        double radius = Math.sqrt(u1);
        double phi = 2.0 * PI * u2;
        double[] up = Math.abs(normal[2]) < 0.999
                ? new double[]{0.0, 0.0, 1.0} : new double[]{1.0, 0.0, 0.0};
        double[] tangent = normalize(cross(up, normal));
        double[] bitangent = cross(normal, tangent);
        double z = Math.sqrt(Math.max(0.0, 1.0 - u1));
        double[] direction = {
                tangent[0] * (radius * Math.cos(phi))
                        + bitangent[0] * (radius * Math.sin(phi)) + normal[0] * z,
                tangent[1] * (radius * Math.cos(phi))
                        + bitangent[1] * (radius * Math.sin(phi)) + normal[1] * z,
                tangent[2] * (radius * Math.cos(phi))
                        + bitangent[2] * (radius * Math.sin(phi)) + normal[2] * z};
        return normalize(direction);
    }

    /** Mirrors {@code interleavedGradientNoise}. */
    public static double interleavedGradientNoise(double pixelX, double pixelY) {
        return fractional(52.9829189 * fractional(
                pixelX * 0.06711056 + pixelY * 0.00583715));
    }

    /** Mirrors {@code borderFade}. */
    public static double borderFade(double u, double v, int width, int height,
                                    double edgeFadePixels) {
        if (edgeFadePixels <= 0.0) return 1.0;
        double px = u * width;
        double py = v * height;
        double border = Math.min(Math.min(px, width - px), Math.min(py, height - py));
        return smoothStep(0.0, edgeFadePixels, border);
    }

    /** Mirrors {@code sampleRoughnessWeight}. */
    public static double sampleRoughnessWeight(double roughness, double minimumRoughness) {
        double floor = clamp(minimumRoughness, 0.0, 0.99);
        return smoothStep(floor, Math.min(floor + 0.2, 1.0), clamp(roughness, 0.0, 1.0));
    }

    /** Mirrors {@code octDecode} in ssr.frag/ssgi.frag. */
    public static double[] octDecode(double octX, double octY) {
        double x = octX;
        double y = octY;
        double z = 1.0 - Math.abs(x) - Math.abs(y);
        if (z < 0.0) {
            double ox = x;
            double oy = y;
            x = (1.0 - Math.abs(oy)) * (ox >= 0.0 ? 1.0 : -1.0);
            y = (1.0 - Math.abs(ox)) * (oy >= 0.0 ? 1.0 : -1.0);
        }
        return normalize(new double[]{x, y, z});
    }

    private static double viewDepthAt(Camera camera, double u, double v, double depth) {
        double[] clip = transform(camera.inverseProjection(), u * 2.0 - 1.0,
                v * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        if (clip[3] <= 0.0) return 0.0;
        return -clip[2] / clip[3];
    }

    private static double[] viewPosition(Camera camera, double u, double v, double depth) {
        double[] clip = transform(camera.inverseProjection(), u * 2.0 - 1.0,
                v * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        if (clip[3] == 0.0) return null;
        return new double[]{clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3]};
    }

    private static double[] colour(Scene scene, int pixel) {
        return new double[]{scene.colorRgb()[pixel * 3], scene.colorRgb()[pixel * 3 + 1],
                scene.colorRgb()[pixel * 3 + 2]};
    }

    private static double[] albedo(Scene scene, int pixel) {
        return new double[]{clamp(scene.albedoRgb()[pixel * 3], 0.0, 1.0),
                clamp(scene.albedoRgb()[pixel * 3 + 1], 0.0, 1.0),
                clamp(scene.albedoRgb()[pixel * 3 + 2], 0.0, 1.0)};
    }

    private static double[] octAt(Scene scene, int pixel) {
        return new double[]{scene.normalOct()[pixel * 2], scene.normalOct()[pixel * 2 + 1]};
    }

    private static double[] rotate(double rotation, double[] direction) {
        double c = Math.cos(rotation);
        double s = Math.sin(rotation);
        return new double[]{c * direction[0] - s * direction[2], direction[1],
                s * direction[0] + c * direction[2]};
    }

    /** Column-major JOML matrix applied to a direction (w = 0). */
    private static double[] transformDirection(Matrix4d matrix, double[] direction) {
        double x = matrix.m00() * direction[0] + matrix.m10() * direction[1]
                + matrix.m20() * direction[2];
        double y = matrix.m01() * direction[0] + matrix.m11() * direction[1]
                + matrix.m21() * direction[2];
        double z = matrix.m02() * direction[0] + matrix.m12() * direction[1]
                + matrix.m22() * direction[2];
        return new double[]{x, y, z};
    }

    /** Column-major JOML matrix applied to a point. */
    private static double[] transform(Matrix4d matrix, double x, double y, double z, double w) {
        return new double[]{
                matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30() * w,
                matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31() * w,
                matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32() * w,
                matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33() * w};
    }

    private static double[] cross(double[] first, double[] second) {
        return new double[]{
                first[1] * second[2] - first[2] * second[1],
                first[2] * second[0] - first[0] * second[2],
                first[0] * second[1] - first[1] * second[0]};
    }

    private static double dot(double[] first, double[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    private static double[] normalize(double[] value) {
        double length = Math.sqrt(dot(value, value));
        if (!(length > 0.0) || !Double.isFinite(length)) {
            return new double[]{0.0, 0.0, 1.0};
        }
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }

    private static double smoothStep(double edge0, double edge1, double value) {
        if (!(edge1 > edge0)) return value >= edge1 ? 1.0 : 0.0;
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double fractional(double value) {
        return value - Math.floor(value);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
