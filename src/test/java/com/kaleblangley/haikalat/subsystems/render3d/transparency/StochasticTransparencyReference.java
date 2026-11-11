package com.kaleblangley.haikalat.subsystems.render3d.transparency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Independent double-precision reference for the stochastic-transparency
 * estimator and its temporal accumulation.
 *
 * <p>This class deliberately does not call the production shader, pass or
 * {@link StochasticTransparencySettings#acceptanceProbability(double)}
 * helper.  It re-derives the documented formulas from the settings fields so a
 * bug in the production expression cannot silently define its own expected
 * value.  Fixed-input JVM cases and the statistical GL cases use these methods
 * as the independent oracle.</p>
 */
final class StochasticTransparencyReference {
    static final double LUMINANCE_RED = 0.2126;
    static final double LUMINANCE_GREEN = 0.7152;
    static final double LUMINANCE_BLUE = 0.0722;
    static final double MAXIMUM_COLOR = 65_504.0;
    static final double SIGMA_FACTOR = 2.0;

    private StochasticTransparencyReference() {
    }

    /**
     * One stochastic layer: acceptance probability in {@code [0,1]}, positive
     * view-space depth (larger is farther) and linear HDR colour.
     */
    record Layer(double probability, double viewDepth, double[] rgb) {
        Layer {
            if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("probability must be in [0, 1]");
            }
            if (!Double.isFinite(viewDepth) || viewDepth <= 0.0) {
                throw new IllegalArgumentException("viewDepth must be positive and finite");
            }
            if (rgb == null || rgb.length != 3) {
                throw new IllegalArgumentException("rgb must have three components");
            }
            rgb = rgb.clone();
        }

        @Override
        public double[] rgb() {
            return rgb.clone();
        }
    }

    /** Builds layers from the selected quads using the independent probability formula. */
    static List<Layer> layers(StochasticTransparencySettings settings,
                              List<OitSurface> surfaces) {
        List<Layer> layers = new ArrayList<>(surfaces.size());
        for (OitSurface surface : surfaces) {
            double probability = acceptanceProbability(settings, surface.alpha());
            layers.add(new Layer(probability, -surface.centerZ(),
                    new double[]{surface.red(), surface.green(), surface.blue()}));
        }
        return layers;
    }

    /** Documented acceptance formula, evaluated independently. */
    static double acceptanceProbability(StochasticTransparencySettings settings, double alpha) {
        if (!Double.isFinite(alpha) || alpha < 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("alpha must be in [0, 1]");
        }
        if (alpha <= settings.alphaThreshold()) {
            return 0.0;
        }
        if (alpha >= 1.0) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, Math.pow(alpha, settings.coverageGamma())));
    }

    /** Exact expected coverage {@code 1 - prod(1 - p_i)} of the layer set. */
    static double expectedCoverage(List<Layer> layers) {
        double transmittance = 1.0;
        for (Layer layer : layers) {
            transmittance *= 1.0 - layer.probability();
        }
        return 1.0 - transmittance;
    }

    /**
     * Exact expected premultiplied colour of "nearest accepted layer wins":
     * {@code sum_i p_i c_i prod_{nearer j} (1 - p_j)}.
     */
    static double[] expectedPremultiplied(List<Layer> layers) {
        List<Layer> sorted = new ArrayList<>(layers);
        sorted.sort(Comparator.comparingDouble(Layer::viewDepth));
        double[] premultiplied = {0.0, 0.0, 0.0};
        double transmittance = 1.0;
        for (Layer layer : sorted) {
            double[] rgb = layer.rgb();
            for (int channel = 0; channel < 3; channel++) {
                premultiplied[channel] += transmittance * layer.probability() * rgb[channel];
            }
            transmittance *= 1.0 - layer.probability();
        }
        return premultiplied;
    }

    /** Expected sorted-over composite over an opaque background colour. */
    static double[] expectedComposite(List<Layer> layers, double[] background) {
        double[] premultiplied = expectedPremultiplied(layers);
        double transmittance = 1.0 - expectedCoverage(layers);
        return new double[]{
                premultiplied[0] + transmittance * background[0],
                premultiplied[1] + transmittance * background[1],
                premultiplied[2] + transmittance * background[2]};
    }

    /** Per-frame coverage estimate for a fixed hit count. */
    static double coverageFromHits(int hits, int samples) {
        if (samples <= 0 || hits < 0 || hits > samples) {
            throw new IllegalArgumentException("invalid hit/sample counts");
        }
        return (double) hits / (double) samples;
    }

    /** Documented bounded running-average weight. */
    static double temporalWeight(StochasticTransparencySettings settings, int historyLength) {
        if (historyLength < 0) {
            throw new IllegalArgumentException("historyLength must be non-negative");
        }
        if (!settings.temporalAccumulation() || historyLength == 0) {
            return 0.0;
        }
        int clamped = Math.min(historyLength, settings.maximumHistoryLength());
        return (double) settings.historyWeight() * clamped / (clamped + 1.0);
    }

    /** One running-average step: {@code current*(1-w) + history*w}. */
    static double accumulate(double history, double current, double weight) {
        if (!Double.isFinite(history) || !Double.isFinite(current)
                || !Double.isFinite(weight) || weight < 0.0 || weight > 1.0) {
            throw new IllegalArgumentException("invalid accumulation input");
        }
        return current * (1.0 - weight) + history * weight;
    }

    /** Luminance with the shader's Rec.709 weights. */
    static double luminance(double[] rgb) {
        return LUMINANCE_RED * rgb[0] + LUMINANCE_GREEN * rgb[1] + LUMINANCE_BLUE * rgb[2];
    }

    /** Depth acceptance window shared with the shader. */
    static double depthTolerance(StochasticTransparencySettings settings, double depth) {
        return Math.max(1.0e-4,
                (double) settings.depthRejectionThreshold() * Math.max(Math.abs(depth), 1.0));
    }

    /** Coverage mean / second moment / variance for a set of coverage samples. */
    static double[] coverageMoments(double[] coverages) {
        if (coverages.length == 0) {
            throw new IllegalArgumentException("coverage array must not be empty");
        }
        double sum = 0.0;
        double squares = 0.0;
        for (double coverage : coverages) {
            if (!Double.isFinite(coverage) || coverage < 0.0 || coverage > 1.0) {
                throw new IllegalArgumentException("coverage must be in [0, 1]");
            }
            sum += coverage;
            squares += coverage * coverage;
        }
        double mean = sum / coverages.length;
        double secondMoment = squares / coverages.length;
        return new double[]{mean, secondMoment,
                Math.max(secondMoment - mean * mean, 0.0)};
    }

    // ------------------------------------------------------------------
    // JVM twin of the shader's white-noise threshold (used only for
    // distribution tests; the GL test validates the real shader).
    // ------------------------------------------------------------------

    static int hash32(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return mixed;
    }

    static double unitHash(int pixelX, int pixelY, int instanceId, int sampleIndex,
                           int frameIndex, int seed) {
        int key = pixelX ^ (pixelY << 16);
        key ^= instanceId * 0x9E3779B9;
        key ^= sampleIndex * 0x85EBCA6B;
        key ^= frameIndex * 0xC2B2AE35;
        key ^= seed;
        return (hash32(key) >>> 8) * (1.0 / 16_777_216.0);
    }

    /** Strict acceptance test used by the shader: {@code u < p}. */
    static boolean accepts(double unit, double probability) {
        if (!Double.isFinite(unit) || unit < 0.0 || unit >= 1.0) {
            throw new IllegalArgumentException("unit threshold must be in [0, 1)");
        }
        return unit < probability;
    }
}
