package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.core.material.SubsurfaceScatteringSettings;

/**
 * Independent double-precision reference for the bounded subsurface model used
 * by this test source set.
 *
 * <p>This class intentionally does not call production helpers. It evaluates
 * the same closed-form equations as {@code pbr-forward.frag},
 * {@code scene-surface.frag} and {@code subsurface-scattering.frag} from fixed
 * inputs so a drift in the shaders, the mask producer or the kernel can be
 * detected without a GPU. The 16 taps are the same fixed Poisson disk; the
 * shader-source contract test compares the numeric table against this class.</p>
 */
public final class SubsurfaceScatteringReference {
    /** Fixed 16-tap Poisson disk bounded to {@code |r| <= 1.24}, in shader order. */
    public static final double[][] POISSON_TAPS = {
            {-0.94201624, -0.39906216},
            {0.94558609, -0.76890725},
            {-0.09418410, -0.92938870},
            {0.34495938, 0.29387760},
            {-0.91588581, 0.45771432},
            {-0.81544232, -0.87912464},
            {-0.38277543, 0.27676845},
            {0.97484398, 0.75648379},
            {0.44323325, -0.97511554},
            {0.53742981, -0.47373420},
            {-0.26496911, -0.41893023},
            {0.79197514, 0.19090188},
            {-0.24188840, 0.99706507},
            {-0.81409955, 0.91437590},
            {0.19984126, 0.78641367},
            {0.14383161, -0.14100790}
    };

    private static final double KERNEL_FALLOFF = 2.5;
    private static final double MAXIMUM_THICKNESS = 2.0;
    private static final double MINIMUM_PROFILE_SCALE = 0.001;
    private static final double MAXIMUM_PROFILE_SCALE = 10.0;
    private static final double MAXIMUM_RADIUS = 2.0;

    private SubsurfaceScatteringReference() {
    }

    /** Per-channel penetration of a profile; index 0/1/2 is red/green/blue. */
    public static double[] penetration(SubsurfaceScatteringSettings.Profile profile) {
        if (profile == SubsurfaceScatteringSettings.Profile.SKIN) {
            return new double[]{1.0, 0.55, 0.30};
        }
        return new double[]{1.0, 1.0, 1.0};
    }

    /** Per-channel exponential attenuation {@code exp(-thickness / (scale * penetration))}. */
    public static double[] attenuation(double thicknessMetres, double profileScaleMetres,
                                       SubsurfaceScatteringSettings.Profile profile) {
        if (!(thicknessMetres >= 0.0) || !(thicknessMetres <= MAXIMUM_THICKNESS)) {
            throw new IllegalArgumentException("thickness out of [0, 2]");
        }
        double scale = Math.max(MINIMUM_PROFILE_SCALE,
                Math.min(MAXIMUM_PROFILE_SCALE, profileScaleMetres));
        double[] penetration = penetration(profile);
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            result[channel] = Math.exp(-thicknessMetres
                    / Math.max(scale * penetration[channel], MINIMUM_PROFILE_SCALE));
        }
        return result;
    }

    /** Back-light transmission weight for one channel, matching the forward term. */
    public static double transmissionWeight(double nDotL, double thicknessMetres,
                                            double profileScaleMetres,
                                            SubsurfaceScatteringSettings.Profile profile,
                                            int channel) {
        double backFacing = Math.max(0.0, Math.min(1.0, -nDotL));
        return backFacing * attenuation(thicknessMetres, profileScaleMetres, profile)[channel];
    }

    /** Effective screen-space radius in metres written to the surface mask. */
    public static double effectiveRadiusMetres(double thicknessMetres,
                                               double profileScaleMetres,
                                               SubsurfaceScatteringSettings.Profile profile,
                                               double authoredRadiusMetres) {
        double[] attenuation = attenuation(thicknessMetres, profileScaleMetres, profile);
        double mean = (attenuation[0] + attenuation[1] + attenuation[2]) / 3.0;
        return Math.max(0.0, Math.min(MAXIMUM_RADIUS, authoredRadiusMetres * mean));
    }

    /** Radially decreasing kernel weight for one tap distance and pixel radius. */
    public static double diffusionWeight(double distancePixels, double radiusPixels) {
        if (!(radiusPixels > 0.0)) {
            throw new IllegalArgumentException("radiusPixels must be positive");
        }
        double normalized = distancePixels / radiusPixels;
        return Math.exp(-KERNEL_FALLOFF * normalized * normalized);
    }

    /** Sum of the fixed tap weights for a pixel radius, used for normalisation checks. */
    public static double tapWeightSum(double radiusPixels) {
        double sum = 0.0;
        for (double[] tap : POISSON_TAPS) {
            sum += diffusionWeight(Math.hypot(tap[0], tap[1]) * radiusPixels, radiusPixels);
        }
        return sum;
    }

    /** Resolved thickness for one of the three sources, in metres. */
    public static double thicknessMetres(double authoredThicknessMetres,
                                         SubsurfaceScatteringSettings.ThicknessSource source,
                                         double occlusion, double baseAlpha) {
        double base = Math.max(0.0, Math.min(MAXIMUM_THICKNESS, authoredThicknessMetres));
        return switch (source) {
            case CONSTANT -> base;
            case OCCLUSION -> Math.max(0.0, Math.min(MAXIMUM_THICKNESS,
                    (1.0 - clampUnit(occlusion)) * base));
            case BASE_ALPHA -> Math.max(0.0, Math.min(MAXIMUM_THICKNESS,
                    (1.0 - clampUnit(baseAlpha)) * base));
        };
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
