package com.kaleblangley.haikalat.subsystems.render3d.caustics;

import org.joml.Vector3fc;

/**
 * Independent double-precision reference for the documented caustics model.
 *
 * <p>This class is a test oracle, not production code. It re-derives the
 * pattern, projection, distance attenuation and rejection gates from the
 * design formula instead of calling the shader or any production helper, so a
 * drift between the shader and this reference is observable. The light
 * direction is passed already normalized and pointing from the light toward the
 * scene.</p>
 */
final class CausticsReference {
    static final double TWO_PI = 6.283185307179586;
    static final double RANGE_FADE_START = 0.8;
    static final double RIDGE_SHARPNESS = 1.8;
    static final double RIDGE_POWER = 3.0;

    private CausticsReference() {
    }

    /** Pattern value in [0, 1] for a water-entry point and a fixed time. */
    static double pattern(double waterX, double waterZ, double timeSeconds,
                          CausticsSettings settings) {
        if (!settings.active()) return 0.0;
        double scale = Math.max(settings.worldScaleMetres(), 1.0e-4);
        double qx = waterX / scale;
        double qz = waterZ / scale;
        double phase = TWO_PI * settings.animationSpeed() * timeSeconds;
        double wave1 = Math.sin(TWO_PI * (0.83 * qx + 0.37 * qz) + phase);
        double wave2 = Math.sin(TWO_PI * (-0.44 * qx + 0.79 * qz) * 1.21
                - phase * 0.73 + 1.7);
        double wave3 = Math.sin(TWO_PI * (0.17 * qx - 0.91 * qz) * 0.87
                + phase * 1.31 + 3.1);
        double sum = (wave1 + wave2 + wave3) / 3.0;
        double ridge = clamp01(1.0 - Math.abs(sum) * RIDGE_SHARPNESS);
        return Math.pow(ridge, RIDGE_POWER);
    }

    /**
     * Caustic amount for a receiver point with the given absolute normal up
     * component ({@code |n.y|} of a unit normal), or 0 when any rejection gate
     * fails.
     */
    static double amount(double worldX, double worldY, double worldZ, double upDot,
                         CausticsSettings settings, Vector3fc lightDirection,
                         double timeSeconds) {
        if (!settings.active()) return 0.0;
        double lightY = lightDirection.y();
        if (!(upDot >= settings.minimumReceiverUpDot())) return 0.0;
        double thickness = settings.waterLevel() - worldY;
        if (!(thickness > settings.depthBias())) return 0.0;
        double travel = thickness / Math.max(-lightY, 1.0e-4);
        if (!(travel <= settings.maximumProjectionDistance())) return 0.0;

        double waterX = worldX - lightDirection.x() * travel;
        double waterZ = worldZ - lightDirection.z() * travel;
        double edge = Math.min(
                Math.min(waterX - settings.waterMinX(), settings.waterMaxX() - waterX),
                Math.min(waterZ - settings.waterMinZ(), settings.waterMaxZ() - waterZ));
        double regionFade = rectangleFade(edge, settings.edgeFadeDistance(),
                Math.min(settings.waterWidth(), settings.waterDepth()));
        double heightFade = bandFade(worldY, settings.receiverMinimumHeight(),
                settings.receiverMaximumHeight(), settings.edgeFadeDistance());
        double rangeFade = 1.0 - smoothstep(
                RANGE_FADE_START * settings.maximumProjectionDistance(),
                settings.maximumProjectionDistance(), travel);
        double attenuation = Math.exp(-settings.distanceFalloff() * travel)
                * rangeFade * regionFade * heightFade;
        double pattern = pattern(waterX, waterZ, timeSeconds, settings);
        return settings.intensity() * pattern * attenuation;
    }

    /** Distance along the light path from the receiver to the water plane. */
    static double travelToWater(double worldY, CausticsSettings settings,
                                Vector3fc lightDirection) {
        return (settings.waterLevel() - worldY) / Math.max(-lightDirection.y(), 1.0e-4);
    }

    static double bandFade(double value, double minimum, double maximum,
                           double requestedFade) {
        double width = Math.min(Math.max(requestedFade, 0.0),
                0.5 * (maximum - minimum));
        if (width <= 0.0) {
            return value >= minimum && value <= maximum ? 1.0 : 0.0;
        }
        return smoothstep(minimum, minimum + width, value)
                * (1.0 - smoothstep(maximum - width, maximum, value));
    }

    static double rectangleFade(double distanceToEdge, double requestedFade,
                                double minimumExtent) {
        if (!(distanceToEdge > 0.0)) return 0.0;
        double width = Math.min(Math.max(requestedFade, 0.0), 0.5 * minimumExtent);
        return width <= 0.0 ? 1.0 : smoothstep(0.0, width, distanceToEdge);
    }

    static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp01((value - edge0) / (edge1 - edge0));
        return t * t * (3.0 - 2.0 * t);
    }

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
