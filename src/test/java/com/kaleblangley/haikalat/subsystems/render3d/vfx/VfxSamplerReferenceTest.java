package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CPU reference for the frozen VFX sprite sampler contract.
 *
 * <p>This test does not touch OpenGL. It reads the same fixture PNGs as
 * {@code VfxSamplerContractGlTest}, simulates the sRGB decode, box-filtered mip
 * chain and bilinear reconstruction independently, and pins the numeric values
 * the real-GL cases assert. A fixture edit therefore fails here before it can
 * silently change the GL expectation.</p>
 */
class VfxSamplerReferenceTest {
    private static final String FIXTURES = "/vfx/test/";

    @Test
    void srgbDecodeAndBilinearMidpointReference() throws IOException {
        double[][] color2x1 = load("sampler-color-2x1.png", Channel.RED, true);
        double[][] mask2x1 = load("sampler-mask-2x1.png", Channel.ALPHA, false);

        assertEquals(0.5, sampleLevel(color2x1, 0.5, 0.5, true), 1.0e-9,
                "black/white sRGB pair must filter to the linear midpoint");
        assertEquals(0.5, sampleLevel(mask2x1, 0.5, 0.5, false), 1.0e-9,
                "linear R8 pair must filter to the linear midpoint");
        assertEquals(1.0, sampleLevel(color2x1, 2.0, 0.5, true), 1.0e-9,
                "clamp-to-edge repeats the rightmost texel");
        assertEquals(0.0, sampleLevel(color2x1, 2.0, 0.5, false, true), 1.0e-9,
                "repeat would wrap U=2 to the left texel; the contract is clamp-to-edge");

        double encodedMidGray = 128.0 / 255.0;
        assertEquals(0.21586050011389926, srgbDecode(encodedMidGray), 1.0e-12,
                "sRGB decode reference used by the GL decode assertion");
    }

    @Test
    void mipChainReferencePinsTheMinifiedAverage() throws IOException {
        double[][] mask8x8 = load("sampler-mask-8x8.png", Channel.ALPHA, false);
        double[][] color8x8 = load("sampler-color-8x8.png", Channel.RED, true);

        List<double[][]> maskChain = mipChain(mask8x8, false);
        List<double[][]> colorChain = mipChain(color8x8, true);
        assertEquals(4, maskChain.size());
        assertEquals(4, colorChain.size());

        // One white row out of eight: the 8x8 -> 4x4 -> 2x2 -> 1x1 box filter is
        // exact, so the 1x1 mip is 8/64 = 0.125 linear. R8 quantization stores
        // 32/255; sRGB storage encodes 0.125 to 99/255, which decodes to
        // 0.1247718.
        assertEquals(32.0 / 255.0, sampledTexel(maskChain.get(3), 0, 0, false), 1.0e-12);
        assertEquals(0.12477181756095049, sampledTexel(colorChain.get(3), 0, 0, true), 1.0e-12);

        // A nearest sampler (or a forgotten mip chain sampled at LOD 0) reads a
        // single black center texel instead of the 0.125 average.
        assertEquals(0.0, nearestTexel(maskChain.get(0), 0.5, 0.5), 1.0e-12);
        assertEquals(0.0, sampleLevel(maskChain.get(0), 0.5, 0.5, false), 1.0e-12);

        // The probe forces log2(8) = 3.0, so the explicit level-3 value is the
        // reference, not an interpolation between levels.
        assertEquals(3.0, log2(8.0), 1.0e-12);
    }

    @Test
    void unpaddedAtlasMipCrossesTheCellBoundaryWhichIsWhyFlipbooksUseLodZero() throws IOException {
        double[][] atlas = load("sampler-atlas-12x4.png", Channel.GREEN, true);
        List<double[][]> chain = mipChain(atlas, true);
        assertEquals(4, chain.size());

        // 12x4 -> 6x2 -> 3x1. The 3x1 level's middle texel averages the red
        // cell and the green cell, so any minified sample that reaches level 2
        // at the right edge of cell 0 picks up green.
        double cellZeroEdgeUv = 0.5 - 0.5 / 12.0;
        double bleedy = sampleLevel(chain.get(2), cellZeroEdgeUv, 0.5, true);
        assertTrue(bleedy > 0.4,
                "the 3x1 mip must straddle the cell boundary; green=" + bleedy);

        // At LOD 0 the half-texel inset maps exactly to the last red texel.
        double lodZero = sampleLevel(chain.get(0), cellZeroEdgeUv, 0.5, true);
        assertEquals(0.0, lodZero, 1.0e-12,
                "base-level inset sampling must stay inside the red cell");

        // The tiny production sprite is ~3 pixels wide, i.e. four texels per
        // pixel and LOD 2.0.
        assertEquals(2.0, log2(12.0 / 3.0), 1.0e-12);
    }

    @Test
    void linearAtlasMipZeroLevelIsPureRedAtTheInsetEdge() throws IOException {
        double[][] source = load("sampler-atlas-12x4.png", Channel.GREEN, true);
        assertEquals(0.0, sampleLevel(source, 0.5 - 0.5 / 12.0, 0.5, true), 1.0e-12);
    }

    private enum Channel {
        RED,
        GREEN,
        ALPHA
    }

    private static double[][] load(String name, Channel channel, boolean srgbStored)
            throws IOException {
        try (InputStream stream = VfxSamplerReferenceTest.class.getResourceAsStream(
                FIXTURES + name)) {
            assertTrue(stream != null, "missing fixture " + name);
            BufferedImage image = ImageIO.read(stream);
            double[][] texels = new double[image.getHeight()][image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int value = switch (channel) {
                        case RED -> (argb >>> 16) & 0xFF;
                        case GREEN -> (argb >>> 8) & 0xFF;
                        case ALPHA -> (argb >>> 24) & 0xFF;
                    };
                    texels[y][x] = value / 255.0;
                }
            }
            return texels;
        }
    }

    private static List<double[][]> mipChain(double[][] base, boolean srgbStored) {
        List<double[][]> chain = new ArrayList<>();
        chain.add(base);
        double[][] current = base;
        while (current[0].length > 1 || current.length > 1) {
            current = downsample(current, srgbStored);
            chain.add(current);
        }
        return chain;
    }

    private static double[][] downsample(double[][] input, boolean srgbStored) {
        int width = input[0].length;
        int height = input.length;
        int nextWidth = Math.max(1, width / 2);
        int nextHeight = Math.max(1, height / 2);
        double[][] output = new double[nextHeight][nextWidth];
        for (int y = 0; y < nextHeight; y++) {
            for (int x = 0; x < nextWidth; x++) {
                double sum = 0.0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sourceY = Math.min(height - 1, y * 2 + dy);
                        int sourceX = Math.min(width - 1, x * 2 + dx);
                        double value = input[sourceY][sourceX];
                        sum += srgbStored ? srgbDecode(value) : value;
                    }
                }
                double average = sum / 4.0;
                output[y][x] = srgbStored ? quantizeSrgb(average) : quantizeLinear(average);
            }
        }
        return output;
    }

    private static double quantizeSrgb(double linear) {
        double encoded = linear <= 0.0031308
                ? linear * 12.92 : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        return Math.round(encoded * 255.0) / 255.0;
    }

    private static double quantizeLinear(double value) {
        return Math.round(value * 255.0) / 255.0;
    }

    private static double srgbDecode(double encoded) {
        return encoded <= 0.04045
                ? encoded / 12.92 : Math.pow((encoded + 0.055) / 1.055, 2.4);
    }

    private static double sampledTexel(double[][] stored, int x, int y, boolean srgbStored) {
        double value = stored[y][x];
        return srgbStored ? srgbDecode(value) : value;
    }

    private static double nearestTexel(double[][] stored, double u, double v) {
        int width = stored[0].length;
        int height = stored.length;
        int x = Math.min(width - 1, Math.max(0, (int) Math.floor(u * width)));
        int y = Math.min(height - 1, Math.max(0, (int) Math.floor(v * height)));
        return stored[y][x];
    }

    private static double sampleLevel(double[][] stored, double u, double v,
                                      boolean srgbStored) {
        return sampleLevel(stored, u, v, srgbStored, false);
    }

    private static double sampleLevel(double[][] stored, double u, double v,
                                      boolean srgbStored, boolean repeat) {
        int width = stored[0].length;
        int height = stored.length;
        double wrappedU;
        double wrappedV;
        if (repeat) {
            wrappedU = u - Math.floor(u);
            wrappedV = v - Math.floor(v);
        } else {
            wrappedU = Math.min(1.0, Math.max(0.0, u));
            wrappedV = Math.min(1.0, Math.max(0.0, v));
        }
        double texelU = wrappedU * width - 0.5;
        double texelV = wrappedV * height - 0.5;
        int x0 = (int) Math.floor(texelU);
        int y0 = (int) Math.floor(texelV);
        double fractionU = texelU - x0;
        double fractionV = texelV - y0;
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        x0 = Math.min(width - 1, Math.max(0, x0));
        x1 = Math.min(width - 1, Math.max(0, x1));
        y0 = Math.min(height - 1, Math.max(0, y0));
        y1 = Math.min(height - 1, Math.max(0, y1));
        double top = lerp(sampledTexel(stored, x0, y0, srgbStored),
                sampledTexel(stored, x1, y0, srgbStored), fractionU);
        double bottom = lerp(sampledTexel(stored, x0, y1, srgbStored),
                sampledTexel(stored, x1, y1, srgbStored), fractionU);
        return lerp(top, bottom, fractionV);
    }

    private static double lerp(double from, double to, double weight) {
        return from + (to - from) * weight;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }
}
