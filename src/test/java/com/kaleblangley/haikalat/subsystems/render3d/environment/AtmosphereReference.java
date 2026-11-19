package com.kaleblangley.haikalat.subsystems.render3d.environment;

import org.joml.Vector3fc;

/**
 * Independent double-precision reference for the atmospheric single-scattering
 * model documented in {@code docs/planning/v0.28.1-s2-atmosphere.md}.
 *
 * <p>This class deliberately does not call any production helper.  It mirrors
 * the written equations with plain uniform midpoint quadrature so that the
 * shader's quadratic sampling and LUT interpolation remain an independent
 * implementation.  Optical depth uses the same exponential density profiles and
 * the same upper-hemisphere convention as the documented contract.</p>
 */
final class AtmosphereReference {
    private AtmosphereReference() {
    }

    static double rayleighPhase(double cosTheta) {
        return 3.0 / (16.0 * Math.PI) * (1.0 + cosTheta * cosTheta);
    }

    static double miePhaseHenyeyGreenstein(double cosTheta, double g) {
        double g2 = g * g;
        double denominator = 1.0 + g2 - 2.0 * g * cosTheta;
        return (1.0 - g2) / (4.0 * Math.PI * Math.pow(denominator, 1.5));
    }

    /** Geometric horizon dip below the local horizontal for an observer altitude. */
    static double horizonDipRadians(double planetRadiusMeters, double altitudeMeters) {
        if (!(planetRadiusMeters > 0.0) || altitudeMeters < 0.0) {
            throw new IllegalArgumentException("horizon geometry requires a positive radius");
        }
        return Math.acos(planetRadiusMeters / (planetRadiusMeters + altitudeMeters));
    }

    /** Sun elevation for the engine's light-travel-direction convention. */
    static double sunElevationRadians(Vector3fc sunDirection) {
        double y = Math.max(-1.0, Math.min(1.0, -sunDirection.y()));
        return Math.asin(y);
    }

    /** Sun azimuth measured from +Z toward +X. */
    static double sunAzimuthRadians(Vector3fc sunDirection) {
        return Math.atan2(-sunDirection.x(), -sunDirection.z());
    }

    static double distanceToTop(double radiusMeters, double mu, double topRadiusMeters) {
        double discriminant = radiusMeters * radiusMeters * (mu * mu - 1.0)
                + topRadiusMeters * topRadiusMeters;
        if (discriminant <= 0.0) return 0.0;
        return -radiusMeters * mu + Math.sqrt(discriminant);
    }

    /** @return {@code {rayleighDepth, mieDepth}} along a non-intersecting upward ray */
    static double[] opticalDepth(AtmosphereSettings settings, double radiusMeters, double mu,
                                 int samples) {
        if (mu <= 0.0) {
            throw new IllegalArgumentException("the transmittance reference covers mu > 0 only");
        }
        if (samples < 1) throw new IllegalArgumentException("samples must be positive");
        double topRadius = settings.atmosphereTopRadiusMeters();
        double pathLength = distanceToTop(radiusMeters, mu, topRadius);
        double rayleighDepth = 0.0;
        double mieDepth = 0.0;
        if (pathLength > 0.0) {
            double step = pathLength / samples;
            double horizontal = Math.sqrt(Math.max(0.0, 1.0 - mu * mu));
            for (int index = 0; index < samples; index++) {
                double distance = (index + 0.5) * step;
                double x = horizontal * distance;
                double y = radiusMeters + mu * distance;
                double sampleRadius = Math.hypot(x, y);
                double altitude = Math.max(0.0, sampleRadius - settings.planetRadiusMeters());
                rayleighDepth += Math.exp(-altitude / settings.rayleighScaleHeightMeters()) * step;
                mieDepth += Math.exp(-altitude / settings.mieScaleHeightMeters()) * step;
            }
        }
        return new double[]{rayleighDepth, mieDepth};
    }

    static double[] transmittance(AtmosphereSettings settings, double radiusMeters, double mu,
                                  int samples) {
        return transmittance(settings, radiusMeters, mu, samples, rayleighCoefficients(settings));
    }

    private static double[] transmittance(AtmosphereSettings settings, double radiusMeters,
                                          double mu, int samples, double[] betaRayleigh) {
        if (mu <= 0.0) return new double[]{0.0, 0.0, 0.0};
        double[] depth = opticalDepth(settings, radiusMeters, mu, samples);
        double extinction = settings.mieScatteringPerMeter() + settings.mieAbsorptionPerMeter();
        return new double[]{
                Math.exp(-(betaRayleigh[0] * depth[0] + extinction * depth[1])),
                Math.exp(-(betaRayleigh[1] * depth[0] + extinction * depth[1])),
                Math.exp(-(betaRayleigh[2] * depth[0] + extinction * depth[1]))
        };
    }

    private static double[] rayleighCoefficients(AtmosphereSettings settings) {
        var coefficients = settings.rayleighScatteringPerMeter();
        return new double[]{coefficients.x(), coefficients.y(), coefficients.z()};
    }

    /**
     * Single-scattering radiance in {@code W / (m^2 sr)} for an upper-hemisphere
     * view direction in the sun-relative frame (sun azimuth is +Z).
     */
    static double[] singleScattering(AtmosphereSettings settings, double altitudeMeters,
                                     double viewZenithRadians, double viewAzimuthRadians,
                                     int viewSamples, int transmittanceSamples) {
        if (viewSamples < 1 || transmittanceSamples < 1) {
            throw new IllegalArgumentException("sample counts must be positive");
        }
        double planetRadius = settings.planetRadiusMeters();
        double observerRadius = planetRadius + altitudeMeters;
        double sunElevation = sunElevationRadians(settings.sunDirection());
        double[] toSun = {0.0, Math.sin(sunElevation), Math.cos(sunElevation)};
        double[] betaRayleigh = rayleighCoefficients(settings);
        double mieScattering = settings.mieScatteringPerMeter();
        double extinction = mieScattering + settings.mieAbsorptionPerMeter();
        double[] viewDirection = {
                Math.sin(viewZenithRadians) * Math.sin(viewAzimuthRadians),
                Math.cos(viewZenithRadians),
                Math.sin(viewZenithRadians) * Math.cos(viewAzimuthRadians)
        };
        double pathLength = distanceToTop(observerRadius, viewDirection[1],
                settings.atmosphereTopRadiusMeters());
        double cosTheta = dot(viewDirection, toSun);
        double rayleighPhase = rayleighPhase(cosTheta);
        double miePhase = miePhaseHenyeyGreenstein(cosTheta, settings.mieAnisotropy());
        double[] radiance = new double[3];
        double[] viewOpticalDepth = new double[3];
        if (!(pathLength > 0.0)) return radiance;
        double step = pathLength / viewSamples;
        for (int index = 0; index < viewSamples; index++) {
            double distance = (index + 0.5) * step;
            double x = viewDirection[0] * distance;
            double y = observerRadius + viewDirection[1] * distance;
            double z = viewDirection[2] * distance;
            double sampleRadius = Math.sqrt(x * x + y * y + z * z);
            double altitude = Math.max(0.0, sampleRadius - planetRadius);
            double rayleighDensity = Math.exp(-altitude / settings.rayleighScaleHeightMeters());
            double mieDensity = Math.exp(-altitude / settings.mieScaleHeightMeters());
            double sunMu = (x * toSun[0] + y * toSun[1] + z * toSun[2]) / sampleRadius;
            double[] sunTransmittance = sunMu > 0.0
                    ? transmittance(settings, sampleRadius, sunMu, transmittanceSamples,
                    betaRayleigh)
                    : new double[]{0.0, 0.0, 0.0};
            for (int channel = 0; channel < 3; channel++) {
                double viewTransmittance = Math.exp(-viewOpticalDepth[channel]);
                double inScatter = (betaRayleigh[channel] * rayleighDensity * rayleighPhase
                        + mieScattering * mieDensity * miePhase)
                        * viewTransmittance * sunTransmittance[channel] * step;
                radiance[channel] += inScatter;
            }
            for (int channel = 0; channel < 3; channel++) {
                viewOpticalDepth[channel] += (betaRayleigh[channel] * rayleighDensity
                        + extinction * mieDensity) * step;
            }
        }
        double sunIntensity = settings.sunIntensityWattsPerSquareMetre();
        for (int channel = 0; channel < 3; channel++) radiance[channel] *= sunIntensity;
        return radiance;
    }

    static double luminance(double[] color) {
        return 0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2];
    }

    private static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }
}
