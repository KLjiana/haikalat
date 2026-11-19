package com.kaleblangley.haikalat.subsystems.render3d.water;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Independent double-precision reference for the screen-space water refraction
 * contract.  Lives in test sources and never calls production shaders or pass
 * helpers: it only reads the public settings values, then reproduces the
 * documented wave, Snell, thickness, projection, foreground-rejection and
 * off-screen fallback rules so fixed inputs can be recomputed by hand.
 */
final class WaterRefractionReferenceMath {
    /** Same slope limit as the shader's REFRACTION_MIN_DOWNWARD constant. */
    static final double MIN_DOWNWARD = 0.05;
    private static final double MAX_PATH = 24.0;
    private static final double COVERAGE_EPSILON = 1.0e-5;
    private static final double GRAZING_EPSILON = 1.0e-3;

    private WaterRefractionReferenceMath() {
    }

    enum FallbackReason {
        NONE,
        NON_FINITE,
        OUTSIDE_SCREEN,
        NO_BACKGROUND,
        FOREGROUND
    }

    /** Clamped screen-space lookup produced by the documented offset model. */
    record Offset(double x, double y, double shiftedX, double shiftedY,
                  double thickness, double travel, FallbackReason fallback) {
        double length() {
            return Math.hypot(x, y);
        }
    }

    /** One fragment evaluation, or {@code water == false} when the pass discards. */
    record Evaluation(boolean water, Vector3d world, Vector3d normal, double thickness,
                      double centreX, double centreY, Offset offset,
                      FallbackReason fallback, double centrePath, List<Tap> taps) {
        Evaluation(boolean water, Vector3d world, Vector3d normal, double thickness,
                   double centreX, double centreY, Offset offset, FallbackReason fallback) {
            this(water, world, normal, thickness, centreX, centreY, offset, fallback, 0, List.of());
        }
    }

    record Tap(int x, int y, double path, double weight) { }

    private record Footprint(List<Tap> taps, FallbackReason fallback) {
        double coverage() {
            return taps.stream().mapToDouble(Tap::weight).sum();
        }
    }

    /** Water shading inputs for the composited expectation. */
    record Shade(Vector3f colour, double alpha) {
    }

    /**
     * Core documented offset, matching {@code projectWorldToUv()} and the
     * refraction block of {@code water-surface.frag}.  {@code centreUv} is the
     * rasterised pixel centre, never the projected surface point.
     */
    static Offset offset(Matrix4f projection, Matrix4f view,
                         Vector3d world, Vector3d normal, Vector3f camera,
                         double centreUvX, double centreUvY,
                         double ior, double thickness, double thicknessScale,
                         double strength, double maximumScreenOffset) {
        return offset(projection, view, world, normal, camera, centreUvX, centreUvY, ior,
                thickness, thicknessScale, strength, maximumScreenOffset, null);
    }

    static Offset offset(Matrix4f projection, Matrix4f view, Vector3d world, Vector3d normal,
                         Vector3f camera, double centreUvX, double centreUvY, double ior,
                         double thickness, double thicknessScale, double strength,
                         double maximumScreenOffset, WaterSurfaceSettings water) {
        Vector3d incident = new Vector3d(world).sub(camera.x, camera.y, camera.z).normalize();
        Vector3d refractionNormal = new Vector3d(normal).normalize();
        if (refractionNormal.dot(incident) > 0.0) {
            refractionNormal.negate();
        }
        Vector3d refracted = refract(incident, refractionNormal, ior);
        if (refracted == null) {
            return new Offset(0.0, 0.0, centreUvX, centreUvY, thickness, 0.0,
                    FallbackReason.NON_FINITE);
        }
        double downward = Math.max(-refracted.y, MIN_DOWNWARD);
        double travel = Math.max(thickness, 0.0) * thicknessScale / downward;
        if (water != null) {
            if (refracted.y >= 0 || camera.y <= water.level()) {
                return new Offset(0, 0, centreUvX, centreUvY, thickness, 0, FallbackReason.NON_FINITE);
            }
            travel = Math.min(travel, exitDistance(water, world, refracted));
        }
        Vector3d refractedWorld = new Vector3d(world).fma(travel, refracted);
        Vector2f shifted = projectToUv(projection, view, refractedWorld);
        if (shifted == null) {
            return new Offset(0.0, 0.0, centreUvX, centreUvY, thickness, travel,
                    FallbackReason.NON_FINITE);
        }
        double offsetX = (shifted.x - centreUvX) * strength;
        double offsetY = (shifted.y - centreUvY) * strength;
        double length = Math.hypot(offsetX, offsetY);
        if (!Double.isFinite(length)) {
            return new Offset(0.0, 0.0, centreUvX, centreUvY, thickness, travel,
                    FallbackReason.NON_FINITE);
        }
        if (length > maximumScreenOffset) {
            double scale = maximumScreenOffset / length;
            offsetX *= scale;
            offsetY *= scale;
        }
        if (water != null) {
            double shore = shorelineWeight(water, thickness);
            offsetX *= shore;
            offsetY *= shore;
        }
        double shiftedX = centreUvX + offsetX;
        double shiftedY = centreUvY + offsetY;
        FallbackReason fallback = shiftedX < 0.0 || shiftedX > 1.0
                || shiftedY < 0.0 || shiftedY > 1.0
                ? FallbackReason.OUTSIDE_SCREEN : FallbackReason.NONE;
        return new Offset(offsetX, offsetY, shiftedX, shiftedY, thickness, travel, fallback);
    }

    /**
     * Evaluates one pixel against an opaque background depth buffer.
     *
     * @param depth bottom-up row-major device depth, one float per pixel
     */
    static Evaluation evaluate(WaterSurfaceSettings water, WaterRefractionSettings refraction,
                               Matrix4f projection, Matrix4f view, Vector3f camera,
                               int width, int height, float[] depth, int x, int y,
                               float farPlane, double timeSeconds) {
        double centreX = (x + 0.5) / width;
        double centreY = (y + 0.5) / height;
        Vector3d world = waterWorld(projection, view, width, height, x, y, water.level());
        if (world == null) {
            return new Evaluation(false, null, null, 0.0, centreX, centreY, null,
                    FallbackReason.NONE);
        }
        if (world.x < water.minX() || world.x > water.maxX()
                || world.z < water.minZ() || world.z > water.maxZ()) {
            return new Evaluation(false, world, null, 0.0, centreX, centreY, null,
                    FallbackReason.NONE);
        }
        double waterViewDepth = -viewPoint(view, world).z;
        if (!(waterViewDepth > 0.0) || waterViewDepth > farPlane) {
            return new Evaluation(false, world, null, 0.0, centreX, centreY, null,
                    FallbackReason.NONE);
        }
        double deviceDepth = depth[y * width + x];
        double sceneY;
        double sceneDistance = MAX_PATH;
        if (deviceDepth >= 1.0) {
            sceneY = water.level() - farPlane;
        } else {
            Vector3d sceneWorld = unproject(projection, view, centreX, centreY, deviceDepth);
            if (sceneWorld == null) {
                return new Evaluation(false, world, null, 0.0, centreX, centreY, null,
                        FallbackReason.NONE);
            }
            double sceneDepth = -viewPoint(view, sceneWorld).z;
            double epsilon = Math.max(1.0e-4, waterViewDepth * 1.0e-4);
            if (sceneDepth + epsilon < waterViewDepth) {
                return new Evaluation(false, world, null, 0.0, centreX, centreY, null,
                        FallbackReason.NONE);
            }
            sceneY = sceneWorld.y;
            sceneDistance = world.distance(sceneWorld);
        }
        double thickness = water.level() - sceneY;
        if (!(thickness > 0.0)) {
            return new Evaluation(false, world, null, 0.0, centreX, centreY, null,
                    FallbackReason.NONE);
        }
        Vector3d viewDirection = new Vector3d(world).sub(camera.x, camera.y, camera.z)
                .normalize();
        if (Math.abs(viewDirection.y) < GRAZING_EPSILON) {
            return new Evaluation(false, world, null, 0.0, centreX, centreY, null,
                    FallbackReason.NONE);
        }
        Vector3d normal = waveNormal(water, world.x, world.z, timeSeconds);
        Offset offset = offset(projection, view, world, normal, camera, centreX, centreY,
                refraction.ior(), thickness, refraction.thicknessScale(),
                refraction.strength(), refraction.maximumScreenOffset(), water);
        boolean edgeFadeTint = refraction.offscreenFallback() == WaterRefractionSettings.OffscreenFallback.EDGE_FADE_TINT;
        if (offset.fallback() != FallbackReason.NON_FINITE && offset.travel() > 0) {
            double wet = thickness / -viewDirection.y;
            double fade = tapConfidence(wet, refraction.foregroundRejectionBias());
            double dx = offset.x() * fade;
            double dy = offset.y() * fade;
            if (edgeFadeTint && refraction.maximumScreenOffset() > 0) {
                double band = 2 * refraction.maximumScreenOffset();
                dx *= smoothstep(0, band, (dx > 0 ? width - 1 - x : x) / (double) width);
                dy *= smoothstep(0, band, (dy > 0 ? height - 1 - y : y) / (double) height);
            }
            double shiftedX = centreX + dx;
            double shiftedY = centreY + dy;
            boolean onScreen = shiftedX >= 0 && shiftedX <= 1 && shiftedY >= 0 && shiftedY <= 1;
            offset = new Offset(dx, dy, shiftedX, shiftedY, thickness, offset.travel(),
                    onScreen ? FallbackReason.NONE : FallbackReason.OUTSIDE_SCREEN);
        }
        Footprint footprint = new Footprint(List.of(), offset.fallback());
        if (offset.fallback() == FallbackReason.NONE && offset.travel() > 0) {
            double shiftedX = offset.shiftedX();
            double shiftedY = offset.shiftedY();
            double borderInPixels = Math.min(Math.min(shiftedX, 1 - shiftedX) * width,
                    Math.min(shiftedY, 1 - shiftedY) * height);
            Footprint shifted = footprint(water, refraction, projection, view, camera,
                    width, height, depth, shiftedX, shiftedY);
            double edgeFade = edgeFadeTint ? 1 : smoothstep(0, 2, borderInPixels);
            if (shifted.coverage() <= COVERAGE_EPSILON || edgeFade <= COVERAGE_EPSILON) {
                footprint = new Footprint(List.of(), shifted.fallback() == FallbackReason.NONE
                        ? FallbackReason.NO_BACKGROUND : shifted.fallback());
            } else if (edgeFade < 1 - COVERAGE_EPSILON) {
                footprint = footprint(water, refraction, projection, view, camera, width, height, depth,
                        centreX + offset.x() * edgeFade, centreY + offset.y() * edgeFade);
            } else {
                footprint = shifted;
            }
        }
        double centrePath = Math.min(MAX_PATH, Math.min(sceneDistance, exitDistance(water, world, viewDirection)));
        return new Evaluation(true, world, normal, thickness, centreX, centreY,
                offset, footprint.fallback(), centrePath, footprint.taps());
    }

    private static Footprint footprint(WaterSurfaceSettings water, WaterRefractionSettings refraction,
                                       Matrix4f projection, Matrix4f view, Vector3f camera,
                                       int width, int height, float[] depth, double u, double v) {
        double px = u * width - 0.5;
        double py = v * height - 0.5;
        var taps = new ArrayList<Tap>(4);
        FallbackReason fallback = FallbackReason.NO_BACKGROUND;
        for (int j = (int) Math.floor(py); j <= (int) Math.floor(py) + 1; j++) {
            for (int i = (int) Math.floor(px); i <= (int) Math.floor(px) + 1; i++) {
                double weight = (1 - Math.abs(px - i)) * (1 - Math.abs(py - j));
                if (weight <= 0 || i < 0 || j < 0 || i >= width || j >= height) continue;
                double device = depth[j * width + i];
                if (device >= 1) continue;
                Vector3d receiver = unproject(projection, view, (i + 0.5) / width,
                        (j + 0.5) / height, device);
                if (receiver == null) continue;
                Vector3d eye = new Vector3d(camera);
                if (receiver.y >= water.level() || !inside(water, receiver, 0.001)) {
                    if (fallback != FallbackReason.NONE) fallback = FallbackReason.FOREGROUND;
                    continue;
                }
                double entry = (eye.y - water.level()) / (eye.y - receiver.y);
                Vector3d surface = eye.lerp(receiver, entry, new Vector3d());
                if (!(entry > 0 && entry < 1) || !inside(water, surface, 0)) continue;
                double wet = receiver.distance(surface);
                double accepted = weight * tapConfidence(wet, refraction.foregroundRejectionBias());
                taps.add(new Tap(i, j, Math.min(wet, MAX_PATH), accepted));
                fallback = FallbackReason.NONE;
            }
        }
        return new Footprint(taps, fallback);
    }

    private static boolean inside(WaterSurfaceSettings water, Vector3d p, double tolerance) {
        return p.x >= water.minX() - tolerance && p.x <= water.maxX() + tolerance
                && p.z >= water.minZ() - tolerance && p.z <= water.maxZ() + tolerance;
    }

    private static double exitDistance(WaterSurfaceSettings water, Vector3d origin, Vector3d ray) {
        return Math.min(MAX_PATH, Math.min(exitAxis(origin.x, ray.x, water.minX(), water.maxX()),
                exitAxis(origin.z, ray.z, water.minZ(), water.maxZ())));
    }

    private static double exitAxis(double origin, double direction, double min, double max) {
        if (Math.abs(direction) < 1.0e-7) return Double.POSITIVE_INFINITY;
        return Math.max(0, ((direction > 0 ? max : min) - origin) / direction);
    }

    static double tapConfidence(double wetPath, double transition) {
        return smoothstep(0, Math.max(transition, 1.0e-4), wetPath);
    }

    /** Bilinear expected background for an evaluation, including the fallback mode. */
    static Vector3f sampleBackground(WaterRefractionReferenceMath.Evaluation evaluation,
                                     WaterRefractionSettings refraction,
                                     float[] rgba, int width, int height) {
        if (!evaluation.water()) {
            return sampleBilinear(rgba, width, height, evaluation.centreX(),
                    evaluation.centreY());
        }
        if (evaluation.fallback() == FallbackReason.NONE) {
            return sampleBilinear(rgba, width, height, evaluation.offset().shiftedX(),
                    evaluation.offset().shiftedY());
        }
        if (refraction.offscreenFallback() != WaterRefractionSettings.OffscreenFallback.UNREFRACTED) {
            return new Vector3f(refraction.offscreenFallbackColor());
        }
        return sampleBilinear(rgba, width, height, evaluation.centreX(), evaluation.centreY());
    }

    /** Independent water shading reference for one covered fragment. */
    static Shade shade(WaterSurfaceSettings water, Vector3f world, Vector3f normal,
                       Vector3f camera, double thickness) {
        Vector3f viewDirection = new Vector3f(world).sub(camera).normalize();
        double nDotV = clamp((double) normal.dot(new Vector3f(viewDirection).negate()), 0.0, 1.0);
        double fresnel = 0.02 + 0.98 * Math.pow(1.0 - nDotV, 5.0);
        double absorb = 1.0 - Math.exp(-water.absorptionDensity() * thickness);
        double shore = water.shoreFadeDistance() <= 0.0 ? 1.0
                : smoothstep(0.0, water.shoreFadeDistance(), thickness);
        Vector3f colour = new Vector3f(water.waterColor())
                .lerp(new Vector3f(water.reflectionFallback()), (float) fresnel);
        double alpha = clamp(shore * (absorb + (1.0 - absorb) * fresnel), 0.0, 1.0);
        return new Shade(colour, alpha);
    }

    /** Composited expected pixel for a covered fragment, or the copied centre otherwise. */
    static Vector3f composite(WaterSurfaceSettings water, WaterRefractionSettings refraction,
                              Evaluation evaluation, Vector3f camera,
                              float[] rgba, int width, int height) {
        Vector3f centre = sampleBilinear(rgba, width, height, evaluation.centreX(), evaluation.centreY());
        if (!evaluation.water()) return centre;
        var viewDirection = new Vector3d(camera).sub(evaluation.world()).normalize();
        double cosine = clamp(viewDirection.dot(evaluation.normal()), 0, 1);
        double fresnel = 0.02 + 0.98 * Math.pow(1 - cosine, 5);
        double shore = shorelineWeight(water, evaluation.thickness());
        Vector3f transmitted = new Vector3f();
        double coverage = 0;
        for (Tap tap : evaluation.taps()) {
            int index = 4 * (tap.y() * width + tap.x());
            var sample = new Vector3f(rgba[index], rgba[index + 1], rgba[index + 2]);
            var shallow = transmit(water, sample, tap.path()).lerp(sample, (float) (1 - shore));
            transmitted.fma((float) tap.weight(), shallow);
            coverage += tap.weight();
        }
        if (coverage > COVERAGE_EPSILON) {
            return transmitted.div((float) coverage).lerp(water.reflectionFallback(), (float) (fresnel * shore));
        }
        Vector3f fallback = refraction.offscreenFallback() != WaterRefractionSettings.OffscreenFallback.UNREFRACTED
                ? refraction.offscreenFallbackColor() : centre;
        Vector3f shadedFallback = transmit(water, fallback, evaluation.centrePath())
                .lerp(water.reflectionFallback(), (float) fresnel);
        return fallback.lerp(shadedFallback, (float) shore);
    }

    private static double shorelineWeight(WaterSurfaceSettings water, double thickness) {
        return water.shoreFadeDistance() <= 0 ? 1 : smoothstep(0, water.shoreFadeDistance(), thickness);
    }

    private static Vector3f transmit(WaterSurfaceSettings water, Vector3f colour, double path) {
        return new Vector3f(water.waterColor()).lerp(colour,
                (float) Math.exp(-water.absorptionDensity() * path));
    }

    /** Independent two-train wave normal, matching the documented formula. */
    static Vector3d waveNormal(WaterSurfaceSettings water, double x, double z, double time) {
        double twoPi = Math.PI * 2.0;
        double k1 = twoPi / water.waveWavelength();
        double directionX = Math.cos(water.waveDirectionRadians());
        double directionZ = Math.sin(water.waveDirectionRadians());
        double k2 = k1 * 1.7;
        double omega1 = k1 * water.waveSpeed();
        double omega2 = k2 * water.waveSpeed();
        double phase1 = k1 * (directionX * x + directionZ * z) - omega1 * time;
        double phase2 = k2 * (-directionZ * x + directionX * z) - omega2 * time + 1.3;
        double gradientX = water.waveAmplitude() * (k1 * directionX * Math.cos(phase1)
                + 0.5 * k2 * -directionZ * Math.cos(phase2));
        double gradientZ = water.waveAmplitude() * (k1 * directionZ * Math.cos(phase1)
                + 0.5 * k2 * directionX * Math.cos(phase2));
        return new Vector3d(-gradientX, 1.0, -gradientZ).normalize();
    }

    /** Double Snell refraction; returns null when the transmitted vector degenerates. */
    static Vector3d refract(Vector3d incident, Vector3d normal, double ior) {
        Vector3d direction = new Vector3d(incident).normalize();
        Vector3d n = new Vector3d(normal).normalize();
        double cosIncident = -direction.dot(n);
        if (cosIncident < 0.0) {
            n.negate();
            cosIncident = -cosIncident;
        }
        double eta = 1.0 / ior;
        double k = 1.0 - eta * eta * (1.0 - cosIncident * cosIncident);
        if (k < 0.0) {
            return new Vector3d(direction);
        }
        Vector3d transmitted = direction.mul(eta)
                .add(n.mul(eta * cosIncident - Math.sqrt(k)));
        double length = transmitted.length();
        return length > 1.0e-12 ? transmitted.div(length) : null;
    }

    /** Projects a world point to bottom-left-origin UV; null behind the camera. */
    static Vector2f projectToUv(Matrix4f projection, Matrix4f view, Vector3d world) {
        Vector3d viewPoint = viewPoint(view, world);
        double x = projection.m00() * viewPoint.x + projection.m10() * viewPoint.y
                + projection.m20() * viewPoint.z + projection.m30();
        double y = projection.m01() * viewPoint.x + projection.m11() * viewPoint.y
                + projection.m21() * viewPoint.z + projection.m31();
        double w = projection.m03() * viewPoint.x + projection.m13() * viewPoint.y
                + projection.m23() * viewPoint.z + projection.m33();
        if (!(w > 1.0e-6)) return null;
        return new Vector2f((float) (x / w * 0.5 + 0.5), (float) (y / w * 0.5 + 0.5));
    }

    /** World point where the camera ray through a pixel meets the water plane. */
    static Vector3d waterWorld(Matrix4f projection, Matrix4f view, int width, int height,
                               int pixelX, int pixelY, double level) {
        Vector3d near = unproject(projection, view, (pixelX + 0.5) / width,
                (pixelY + 0.5) / height, 0.0);
        Vector3d far = unproject(projection, view, (pixelX + 0.5) / width,
                (pixelY + 0.5) / height, 1.0);
        if (near == null || far == null) return null;
        Vector3d direction = new Vector3d(far).sub(near).normalize();
        if (Math.abs(direction.y) < 1.0e-9) return null;
        double distance = (level - near.y) / direction.y;
        if (!(distance > 0.0)) return null;
        return new Vector3d(near).fma(distance, direction);
    }

    /** Unprojects a bottom-left-origin UV and device depth into world space. */
    static Vector3d unproject(Matrix4f projection, Matrix4f view,
                              double u, double v, double deviceDepth) {
        double ndcX = u * 2.0 - 1.0;
        double ndcY = v * 2.0 - 1.0;
        double ndcZ = deviceDepth * 2.0 - 1.0;
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Vector4d viewH = transform(inverseProjection, new Vector3d(ndcX, ndcY, ndcZ));
        if (Math.abs(viewH.w) < 1.0e-9) return null;
        Vector3d viewPoint = new Vector3d(viewH.x / viewH.w, viewH.y / viewH.w,
                viewH.z / viewH.w);
        Matrix4f inverseView = new Matrix4f(view).invert();
        Vector4d worldH = transform(inverseView, viewPoint);
        if (Math.abs(worldH.w) < 1.0e-9) return null;
        return new Vector3d(worldH.x / worldH.w, worldH.y / worldH.w, worldH.z / worldH.w);
    }

    /** Bilinear sample of a bottom-up RGBA float capture with clamped edges. */
    static Vector3f sampleBilinear(float[] rgba, int width, int height, double u, double v) {
        double x = u * width - 0.5;
        double y = v * height - 0.5;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double a = texel(rgba, width, height, x0, y0, channel);
            double b = texel(rgba, width, height, x0 + 1, y0, channel);
            double c = texel(rgba, width, height, x0, y0 + 1, channel);
            double d = texel(rgba, width, height, x0 + 1, y0 + 1, channel);
            result[channel] = (a * (1.0 - fx) + b * fx) * (1.0 - fy)
                    + (c * (1.0 - fx) + d * fx) * fy;
        }
        return new Vector3f((float) result[0], (float) result[1], (float) result[2]);
    }

    /**
     * OpenGL window depth in {@code [0,1]} for a view-space point, i.e. exactly
     * what {@code gl_FragDepth} and the depth attachment store.  This is not a
     * view distance or a world thickness: convert the view-space position through
     * the projection (with the {@code m32}/{@code m23} terms of the column-major
     * perspective matrix) before writing it.
     */
    static double deviceDepth(Matrix4f projection, double viewX, double viewY, double viewZ) {
        double z = projection.m02() * viewX + projection.m12() * viewY
                + projection.m22() * viewZ + projection.m32();
        double w = projection.m03() * viewX + projection.m13() * viewY
                + projection.m23() * viewZ + projection.m33();
        if (!(w > 1.0e-9)) return Double.NaN;
        double ndcZ = z / w;
        return ndcZ * 0.5 + 0.5;
    }

    private static Vector3d viewPoint(Matrix4f view, Vector3d world) {
        double x = view.m00() * world.x + view.m10() * world.y + view.m20() * world.z
                + view.m30();
        double y = view.m01() * world.x + view.m11() * world.y + view.m21() * world.z
                + view.m31();
        double z = view.m02() * world.x + view.m12() * world.y + view.m22() * world.z
                + view.m32();
        return new Vector3d(x, y, z);
    }

    private static Vector4d transform(Matrix4f matrix, Vector3d point) {
        double x = matrix.m00() * point.x + matrix.m10() * point.y + matrix.m20() * point.z
                + matrix.m30();
        double y = matrix.m01() * point.x + matrix.m11() * point.y + matrix.m21() * point.z
                + matrix.m31();
        double z = matrix.m02() * point.x + matrix.m12() * point.y + matrix.m22() * point.z
                + matrix.m32();
        double w = matrix.m03() * point.x + matrix.m13() * point.y + matrix.m23() * point.z
                + matrix.m33();
        return new Vector4d(x, y, z, w);
    }

    private static double texel(float[] rgba, int width, int height,
                                int x, int y, int channel) {
        int clampedX = clamp(x, 0, width - 1);
        int clampedY = clamp(y, 0, height - 1);
        return rgba[(clampedY * width + clampedX) * 4 + channel];
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private record Vector4d(double x, double y, double z, double w) {
    }
}
