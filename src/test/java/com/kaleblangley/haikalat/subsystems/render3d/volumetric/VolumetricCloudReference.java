package com.kaleblangley.haikalat.subsystems.render3d.volumetric;

import org.joml.Matrix4fc;

/**
 * Independent double-precision reference for the bounded volumetric cloud
 * layer and its reconstruction.
 *
 * <p>This class is a deliberate second implementation of the documented
 * formulas (the GLSL programs are the first): the shared #10 cloud mask,
 * the vertical profile, the front-to-back single-scatter march with its bounded
 * light march, the depth-aware spatial reconstruction, the history acceptance
 * rules and the history clamp/blend. It also contains exact closed-form
 * integrals (vertical profile and optical depth) so that a shader/reference
 * drift cannot silently move the expected values together.</p>
 *
 * <p>{@code VolumetricCloudReferenceTest} pins fixed values that were computed
 * from the formulas in a separate scratch calculation, not by calling this
 * class. The GL test uses the same model to derive per-pixel expectations from
 * read-back pixels. Values here use double arithmetic; the GLSL stages use
 * binary32, so pixel comparisons keep a documented tolerance.</p>
 */
final class VolumetricCloudReference {
    static final double PROFILE_EDGE = 0.25;
    static final double CLOUD_SHADOW_OCTAVES = 3.0;
    static final double NO_SURFACE = 1.0e30;
    static final double MAXIMUM_OPTICAL_DEPTH = 88.0;
    private static final double PI = 3.14159265358979323846;

    private VolumetricCloudReference() {
    }

    /** World-space view ray with an opaque-surface distance bound. */
    record Ray(double originX, double originY, double originZ,
               double directionX, double directionY, double directionZ,
               double maxDistance) {
    }

    /** A pixel's premultiplied single scatter and transmittance. */
    record Contribution(double red, double green, double blue, double transmittance) {
        double[] composed(double[] background) {
            return new double[]{red + transmittance * background[0],
                    green + transmittance * background[1],
                    blue + transmittance * background[2]};
        }
    }

    /** The four low-resolution taps of one output pixel, in tap order (0,0),(1,0),(0,1),(1,1). */
    record Reconstructed(double[] weights, double[] similarity) {
    }

    /**
     * Rebuilds the shader's view ray for one pixel in double precision: the
     * near-plane point is the origin and {@code far - near} the direction, for
     * perspective and orthographic projections alike.
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
        double length = Math.sqrt(directionX * directionX + directionY * directionY
                + directionZ * directionZ);
        directionX /= length;
        directionY /= length;
        directionZ /= length;
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

    /** {@code fract(vec3(p.xyx) * 0.1031)} based shared cloud-shadow hash. */
    static double maskHash(double x, double z) {
        double ax = x * 0.1031;
        double az = z * 0.1031;
        double[] p3 = {fract(ax), fract(az), fract(ax)};
        double dot = p3[0] * (p3[1] + 33.33) + p3[1] * (p3[2] + 33.33) + p3[2] * (p3[0] + 33.33);
        p3[0] += dot;
        p3[1] += dot;
        p3[2] += dot;
        return fract((p3[0] + p3[1]) * p3[2]);
    }

    /** Bilinear value-noise cell interpolation of the shared cloud-shadow hash. */
    static double maskNoise(double x, double z) {
        double cellX = Math.floor(x);
        double cellZ = Math.floor(z);
        double localX = x - cellX;
        double localZ = z - cellZ;
        double weightX = localX * localX * (3.0 - 2.0 * localX);
        double weightZ = localZ * localZ * (3.0 - 2.0 * localZ);
        double a = maskHash(cellX, cellZ);
        double b = maskHash(cellX + 1.0, cellZ);
        double c = maskHash(cellX, cellZ + 1.0);
        double d = maskHash(cellX + 1.0, cellZ + 1.0);
        return mix(mix(a, b, weightX), mix(c, d, weightX), weightZ);
    }

    /** Three-octave shared cloud-shadow density normalized to {@code [0, 1]}. */
    static double maskDensity(double x, double z) {
        double density = 0.0;
        double amplitude = 0.5;
        double px = x;
        double pz = z;
        for (int octave = 0; octave < (int) CLOUD_SHADOW_OCTAVES; octave++) {
            density += amplitude * maskNoise(px, pz);
            px = px * 2.03 + 17.1;
            pz = pz * 2.03 + 9.2;
            amplitude *= 0.5;
        }
        return density / 0.875;
    }

    /**
     * World-xz coverage of the shared cloud field. The wind offset, threshold
     * and softness mapping mirror {@code cloudLayerMask} in
     * {@code cloud-march.frag} and {@code cloudShadowVisibility} in
     * {@code pbr-forward.frag}.
     */
    static double mask(VolumetricCloudSettings settings, double worldX, double worldZ,
                       double time) {
        double wind = time * Math.max(settings.coverageField().speed(), 0.0);
        double x = worldX + 0.8 * wind;
        double z = worldZ + 0.6 * wind;
        double scale = Math.max(settings.coverageField().scale(), 1.0e-4);
        double noise = maskDensity(x * scale, z * scale);
        double coverage = clamp(settings.coverageField().coverage(), 0.0, 1.0);
        double threshold = mix(0.78, 0.22, coverage);
        double edge = mix(0.004, 0.28, clamp(settings.coverageField().softness(), 0.0, 1.0));
        return smoothstep(threshold - edge, threshold + edge, noise);
    }

    /** Vertical layer profile in {@code [0, 1]}, exactly zero outside the slab. */
    static double profile(VolumetricCloudSettings settings, double y) {
        double span = Math.max(settings.topAltitudeMetres() - settings.baseAltitudeMetres(),
                1.0e-3);
        double t = (y - settings.baseAltitudeMetres()) / span;
        return smoothstep(0.0, PROFILE_EDGE, t)
                * (1.0 - smoothstep(1.0 - PROFILE_EDGE, 1.0, t));
    }

    /** Extinction times coverage times vertical profile, in 1/metre. */
    static double density(VolumetricCloudSettings settings, double x, double y, double z,
                          double time) {
        double mask = mask(settings, x, z, time);
        if (!(mask > 0.0)) {
            return 0.0;
        }
        return settings.extinction() * mask * profile(settings, y);
    }

    /**
     * Exact integral of the vertical profile over a positive-length altitude
     * interval, in metres. For {@code PROFILE_EDGE = 0.25} the full-slab
     * integral is exactly {@code 0.75 * (top - base)}.
     */
    static double profileIntegral(VolumetricCloudSettings settings,
                                  double yStart, double yEnd) {
        if (!(yEnd > yStart)) {
            return 0.0;
        }
        double base = settings.baseAltitudeMetres();
        double top = settings.topAltitudeMetres();
        double span = top - base;
        double start = clamp((Math.max(yStart, base) - base) / span, 0.0, 1.0);
        double end = clamp((Math.min(yEnd, top) - base) / span, 0.0, 1.0);
        if (!(end > start)) {
            return 0.0;
        }
        return (profileAntiderivative(end) - profileAntiderivative(start)) * span;
    }

    /** Distance interval along the ray that lies inside the cloud slab and in front of the surface. */
    static double[] visibleInterval(VolumetricCloudSettings settings, Ray ray) {
        double base = settings.baseAltitudeMetres();
        double top = settings.topAltitudeMetres();
        double start;
        double end;
        if (Math.abs(ray.directionY()) < 1.0e-12) {
            if (ray.originY() < base || ray.originY() > top) {
                return null;
            }
            start = 0.0;
            end = NO_SURFACE;
        } else {
            double first = (base - ray.originY()) / ray.directionY();
            double second = (top - ray.originY()) / ray.directionY();
            start = Math.max(Math.min(first, second), 0.0);
            end = Math.max(first, second);
        }
        end = Math.min(end, ray.maxDistance());
        return end > start ? new double[]{start, end} : null;
    }

    /**
     * Closed-form optical depth for a ray whose sampled mask is constant (a
     * vertical ray through a horizontal layer):
     * {@code extinction * maskValue * profileIntegral(entry, exit)}.
     */
    static double verticalOpticalDepth(VolumetricCloudSettings settings, Ray ray,
                                       double maskValue) {
        double[] interval = visibleInterval(settings, ray);
        if (interval == null || !(maskValue > 0.0)) {
            return 0.0;
        }
        double entryY = ray.originY() + ray.directionY() * interval[0];
        double exitY = ray.originY() + ray.directionY() * interval[1];
        double low = Math.min(entryY, exitY);
        double high = Math.max(entryY, exitY);
        return settings.extinction() * maskValue * profileIntegral(settings, low, high);
    }

    /** The shader's midpoint view march for one ray, in double precision. */
    static double quadratureOpticalDepth(VolumetricCloudSettings settings, Ray ray,
                                         double time) {
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
        double opticalDepth = 0.0;
        for (int step = 0; step < steps; step++) {
            double distance = start + (step + 0.5) * delta;
            double x = ray.originX() + ray.directionX() * distance;
            double y = ray.originY() + ray.directionY() * distance;
            double z = ray.originZ() + ray.directionZ() * distance;
            opticalDepth += density(settings, x, y, z, time) * delta;
        }
        return opticalDepth;
    }

    /** Exact integral of the vertical profile over the ray's visible layer interval. */
    static double visibleProfileIntegral(VolumetricCloudSettings settings, Ray ray) {
        double[] interval = visibleInterval(settings, ray);
        if (interval == null) {
            return 0.0;
        }
        double entryY = ray.originY() + ray.directionY() * interval[0];
        double exitY = ray.originY() + ray.directionY() * interval[1];
        return profileIntegral(settings, Math.min(entryY, exitY), Math.max(entryY, exitY));
    }

    /**
     * The profile-weighted mean mask along the ray: the single mask value that
     * produces the same optical depth as the sampled field,
     * {@code (integral mask*profile dt) / (integral profile dt)}. A per-pixel
     * effective mask fitted from a measured transmittance can be compared with
     * this value without depending on the shader's binary32 hash bits.
     */
    static double effectiveMask(VolumetricCloudSettings settings, Ray ray, double time) {
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
        double weighted = 0.0;
        double weightSum = 0.0;
        for (int step = 0; step < steps; step++) {
            double distance = start + (step + 0.5) * delta;
            double x = ray.originX() + ray.directionX() * distance;
            double y = ray.originY() + ray.directionY() * distance;
            double z = ray.originZ() + ray.directionZ() * distance;
            double weight = profile(settings, y) * delta;
            weighted += mask(settings, x, z, time) * weight;
            weightSum += weight;
        }
        return weightSum > 0.0 ? weighted / weightSum : 0.0;
    }

    /**
     * The cloud lighting model with one uniform mask value (no hash
     * evaluation). Used to predict the measured in-scatter from an effective
     * mask fitted out of the measured transmittance.
     */
    static Contribution marchWithUniformMask(VolumetricCloudSettings settings, Ray ray,
                                             double time, double maskValue) {
        double[] interval = visibleInterval(settings, ray);
        if (interval == null || !(maskValue > 0.0)) {
            return new Contribution(0.0, 0.0, 0.0, 1.0);
        }
        double start = interval[0];
        double end = interval[1];
        double span = end - start;
        double stepLength = Math.max(span / settings.maximumSteps(), settings.stepSizeMetres());
        int steps = (int) Math.max(1.0, Math.min(settings.maximumSteps(),
                Math.ceil(span / stepLength)));
        double delta = span / steps;
        double extinction = settings.extinction();
        double albedo = settings.scatteringAlbedo();
        double toLightY = -settings.lightDirectionInternal().y;
        double cosine = clamp(settings.lightDirectionInternal().x * -ray.directionX()
                + settings.lightDirectionInternal().y * -ray.directionY()
                + settings.lightDirectionInternal().z * -ray.directionZ(), -1.0, 1.0);
        double phase = phase(settings.anisotropy(), cosine);
        double scatter = 0.0;
        double transmittance = 1.0;
        for (int step = 0; step < steps; step++) {
            double distance = start + (step + 0.5) * delta;
            double y = ray.originY() + ray.directionY() * distance;
            double sigma = extinction * maskValue * profile(settings, y);
            if (!(sigma > 0.0)) {
                continue;
            }
            double lightTau = lightTauUniform(settings, y, maskValue, toLightY);
            double slabTransmittance = Math.exp(-Math.min(sigma * delta, MAXIMUM_OPTICAL_DEPTH));
            double direct = Math.exp(-Math.min(lightTau, MAXIMUM_OPTICAL_DEPTH));
            double radiance = phase * direct;
            if (toLightY > 1.0e-4 && settings.multipleScatteringStrength() > 0.0f) {
                double interaction = -Math.expm1(-Math.min(lightTau, MAXIMUM_OPTICAL_DEPTH));
                double nextOrder = settings.multipleScatteringStrength() * albedo * interaction;
                for (int order = 1; order <= 2; order++) {
                    double phaseScale = order == 1 ? 0.45 : 0.20;
                    double extinctionScale = Math.scalb(1.0, -order);
                    radiance += Math.pow(0.5 * nextOrder, order)
                            * phase(settings.anisotropy() * phaseScale, cosine)
                            * Math.exp(-extinctionScale * lightTau);
                }
            }
            double weight = radiance * albedo * (1.0 - slabTransmittance);
            scatter += transmittance * weight;
            transmittance *= slabTransmittance;
        }
        return new Contribution(scatter * settings.lightColorInternal().x,
                scatter * settings.lightColorInternal().y,
                scatter * settings.lightColorInternal().z,
                clamp(transmittance, 0.0, 1.0));
    }

    /** Bounded uniform-mask light march (the light direction is vertical in the tests). */
    private static double lightTauUniform(VolumetricCloudSettings settings, double y,
                                          double maskValue, double lightY) {
        if (!(lightY > 1.0e-4)) {
            return MAXIMUM_OPTICAL_DEPTH;
        }
        double span = (settings.topAltitudeMetres() - y) / lightY;
        span = clamp(span, 0.0, settings.maximumLightDistanceMetres());
        if (!(span > 0.0)) {
            return 0.0;
        }
        double delta = span / settings.lightSteps();
        double opticalDepth = 0.0;
        for (int step = 0; step < settings.lightSteps(); step++) {
            double along = (step + 0.5) * delta;
            opticalDepth += settings.extinction() * maskValue
                    * profile(settings, y + lightY * along) * delta;
        }
        return opticalDepth;
    }

    /** Effective-mask fit from a measured transmittance: {@code -ln(T) / (extinction * I)}. */
    static double fittedMask(VolumetricCloudSettings settings, Ray ray,
                             double measuredTransmittance) {
        double integral = visibleProfileIntegral(settings, ray);
        double transmittance = clamp(measuredTransmittance, 1.0e-6, 1.0);
        if (!(integral > 0.0) || !(settings.extinction() > 0.0)) {
            return 0.0;
        }
        return clamp(-Math.log(transmittance) / (settings.extinction() * integral),
                0.0, 1.0);
    }

    /** Normalized Henyey-Greenstein phase with the shader's {@code 0.01} denominator floor. */
    static double phase(double anisotropy, double cosine) {
        double denominator = Math.max(0.01, 1.0 + anisotropy * anisotropy
                - 2.0 * anisotropy * cosine);
        return (1.0 - anisotropy * anisotropy)
                / (4.0 * PI * denominator * Math.sqrt(denominator));
    }

    /**
     * Full shader model: bounded light march per non-empty sample, front-to-back
     * accumulation of {@code light * phase * albedo * lightTransmittance *
     * (1 - exp(-sigma * delta))}, transmittance product.
     */
    static Contribution march(VolumetricCloudSettings settings, Ray ray, double time) {
        double[] interval = visibleInterval(settings, ray);
        if (interval == null) {
            return new Contribution(0.0, 0.0, 0.0, 1.0);
        }
        double start = interval[0];
        double end = interval[1];
        double span = end - start;
        double stepLength = Math.max(span / settings.maximumSteps(), settings.stepSizeMetres());
        int steps = (int) Math.max(1.0, Math.min(settings.maximumSteps(),
                Math.ceil(span / stepLength)));
        double delta = span / steps;
        double extinction = settings.extinction();
        double albedo = settings.scatteringAlbedo();
        double lightX = -settings.lightDirectionInternal().x;
        double lightY = -settings.lightDirectionInternal().y;
        double lightZ = -settings.lightDirectionInternal().z;
        double cosine = clamp(settings.lightDirectionInternal().x * -ray.directionX()
                + settings.lightDirectionInternal().y * -ray.directionY()
                + settings.lightDirectionInternal().z * -ray.directionZ(), -1.0, 1.0);
        double phase = phase(settings.anisotropy(), cosine);
        double scatterRed = 0.0;
        double scatterGreen = 0.0;
        double scatterBlue = 0.0;
        double transmittance = 1.0;
        for (int step = 0; step < steps; step++) {
            double distance = start + (step + 0.5) * delta;
            double x = ray.originX() + ray.directionX() * distance;
            double y = ray.originY() + ray.directionY() * distance;
            double z = ray.originZ() + ray.directionZ() * distance;
            double sigma = density(settings, x, y, z, time);
            if (!(sigma > 0.0)) {
                continue;
            }
            double lightTau = lightTau(settings, x, y, z, time, lightX, lightY, lightZ);
            double slabTransmittance = Math.exp(-Math.min(sigma * delta, MAXIMUM_OPTICAL_DEPTH));
            double weight = phase * albedo
                    * Math.exp(-Math.min(lightTau, MAXIMUM_OPTICAL_DEPTH))
                    * (1.0 - slabTransmittance);
            scatterRed += transmittance * settings.lightColorInternal().x * weight;
            scatterGreen += transmittance * settings.lightColorInternal().y * weight;
            scatterBlue += transmittance * settings.lightColorInternal().z * weight;
            transmittance *= slabTransmittance;
        }
        return new Contribution(scatterRed, scatterGreen, scatterBlue,
                clamp(transmittance, 0.0, 1.0));
    }

    /**
     * Bounded light-march optical depth from a sample toward the light, exactly
     * as {@code cloudLightTau} in the shader.
     */
    static double lightTau(VolumetricCloudSettings settings, double x, double y, double z,
                           double time, double lightX, double lightY, double lightZ) {
        if (!(lightY > 1.0e-4)) {
            return MAXIMUM_OPTICAL_DEPTH;
        }
        double span = (settings.topAltitudeMetres() - y) / lightY;
        span = clamp(span, 0.0, settings.maximumLightDistanceMetres());
        if (!(span > 0.0)) {
            return 0.0;
        }
        double delta = span / settings.lightSteps();
        double opticalDepth = 0.0;
        for (int step = 0; step < settings.lightSteps(); step++) {
            double along = (step + 0.5) * delta;
            opticalDepth += density(settings, x + lightX * along, y + lightY * along,
                    z + lightZ * along, time) * delta;
        }
        return opticalDepth;
    }

    /**
     * Depth-aware spatial reconstruction weights for the four low-resolution
     * taps of one output pixel, in tap order (0,0),(1,0),(0,1),(1,1).
     */
    static Reconstructed spatialWeights(double lowX, double lowY, double centerDepth,
                                        double[] tapDepths, double tolerance) {
        double baseX = Math.floor(lowX);
        double baseY = Math.floor(lowY);
        double fractionX = lowX - baseX;
        double fractionY = lowY - baseY;
        double[] weights = new double[4];
        double[] similarity = new double[4];
        double sum = 0.0;
        for (int tap = 0; tap < 4; tap++) {
            int x = tap & 1;
            int y = tap >> 1;
            double bilinear = (x == 0 ? 1.0 - fractionX : fractionX)
                    * (y == 0 ? 1.0 - fractionY : fractionY);
            similarity[tap] = depthSimilarity(centerDepth, tapDepths[tap], tolerance);
            double weight = bilinear * similarity[tap];
            weights[tap] = weight;
            sum += weight;
        }
        if (sum > 1.0e-8) {
            for (int tap = 0; tap < 4; tap++) {
                weights[tap] /= sum;
            }
        } else {
            double nearestX = Math.floor(lowX + 0.5);
            double nearestY = Math.floor(lowY + 0.5);
            int nearest = (nearestX == baseX ? 0 : 1) | (nearestY == baseY ? 0 : 2);
            weights = new double[4];
            weights[nearest] = 1.0;
        }
        return new Reconstructed(weights, similarity);
    }

    /** Background/background similarity is 1; background/surface is a rejection. */
    static double depthSimilarity(double centerDepth, double tapDepth, double tolerance) {
        if (centerDepth < 0.0 && tapDepth < 0.0) {
            return 1.0;
        }
        if (centerDepth < 0.0 || tapDepth < 0.0) {
            return 0.0;
        }
        return Math.exp(-Math.abs(centerDepth - tapDepth) / tolerance);
    }

    /** History acceptance for one pixel, mirroring the reconstruction shader. */
    static boolean historyAccepted(boolean globalHistoryValid, boolean anchorDefined,
                                   double previousU, double previousV, double expectedDepth,
                                   double storedDepth, boolean storedValid) {
        if (!globalHistoryValid || !anchorDefined) {
            return false;
        }
        if (previousU < 0.0 || previousU > 1.0 || previousV < 0.0 || previousV > 1.0) {
            return false;
        }
        double tolerance = Math.max(1.0e-4, 0.02 * Math.max(expectedDepth, 1.0));
        return storedValid && Math.abs(storedDepth - expectedDepth) <= tolerance;
    }

    /** History clamp to the current low-resolution range, then blend. */
    static double historyBlend(double spatial, double history, double minimum, double maximum,
                               double historyWeight) {
        double clamped = clamp(history, minimum, maximum);
        return mix(spatial, clamped, clamp(historyWeight, 0.0, 1.0));
    }

    /** Exact camera-cut rule shared with the pass: translation, rotation or projection change. */
    static boolean cameraCut(double previousX, double previousY, double previousZ,
                             double currentX, double currentY, double currentZ,
                             double previousForwardX, double previousForwardY,
                             double previousForwardZ,
                             double currentForwardX, double currentForwardY,
                             double currentForwardZ, double translationLimit,
                             double angleLimitDegrees) {
        double translation = Math.sqrt((previousX - currentX) * (previousX - currentX)
                + (previousY - currentY) * (previousY - currentY)
                + (previousZ - currentZ) * (previousZ - currentZ));
        if (!Double.isFinite(translation) || translation > translationLimit) {
            return true;
        }
        double dot = previousForwardX * currentForwardX + previousForwardY * currentForwardY
                + previousForwardZ * currentForwardZ;
        double angle = Math.toDegrees(Math.acos(clamp(dot, -1.0, 1.0)));
        return !Double.isFinite(angle) || angle > angleLimitDegrees;
    }

    /** Relative exposure-change rule shared with the pass. */
    static boolean exposureAccepted(float previousExposure, float currentExposure,
                                    float threshold) {
        if (!Float.isFinite(previousExposure) || !Float.isFinite(currentExposure)
                || previousExposure <= 0.0f || currentExposure <= 0.0f) {
            return false;
        }
        return Math.abs(currentExposure - previousExposure)
                / Math.max(previousExposure, 1.0e-6f) <= threshold;
    }

    /** Integral of the cubic smoothstep helper on {@code [0, 1]}. */
    private static double smoothstepIntegral(double u) {
        return u * u * u - 0.5 * u * u * u * u;
    }

    private static double profileAntiderivative(double u) {
        if (u <= 0.0) {
            return 0.0;
        }
        if (u >= 1.0) {
            return 0.75;
        }
        double total = 0.0;
        if (u <= PROFILE_EDGE) {
            double scaled = u / PROFILE_EDGE;
            return PROFILE_EDGE * smoothstepIntegral(scaled);
        }
        total += PROFILE_EDGE * smoothstepIntegral(1.0);
        if (u <= 1.0 - PROFILE_EDGE) {
            return total + (u - PROFILE_EDGE);
        }
        total += (1.0 - PROFILE_EDGE) - PROFILE_EDGE;
        double scaled = (u - (1.0 - PROFILE_EDGE)) / PROFILE_EDGE;
        total += (u - (1.0 - PROFILE_EDGE))
                - PROFILE_EDGE * smoothstepIntegral(scaled);
        return total;
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    private static double mix(double left, double right, double weight) {
        return left + (right - left) * weight;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
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
