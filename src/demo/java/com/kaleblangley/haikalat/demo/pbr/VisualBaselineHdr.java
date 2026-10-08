package com.kaleblangley.haikalat.demo.pbr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Frozen, linear pre-Bloom HDR snapshots used only by the visual baseline demos. */
final class VisualBaselineHdr {
    private static final int MAGIC = 0x48344631; // H4F1
    private static final int DOWNSAMPLE = 4;
    private static final double MAX_RMSE = 0.015;
    private static final double MAX_P95 = 0.035;

    private VisualBaselineHdr() { }

    static Image downsample(float[] rgba, int width, int height) {
        if (width <= 0 || height <= 0 || width % DOWNSAMPLE != 0
                || height % DOWNSAMPLE != 0 || rgba.length != width * height * 4) {
            throw new IllegalArgumentException("HDR readback extent must be a multiple of four");
        }
        int sampleWidth = width / DOWNSAMPLE;
        int sampleHeight = height / DOWNSAMPLE;
        float[] rgb = new float[sampleWidth * sampleHeight * 3];
        for (int y = 0; y < sampleHeight; y++) {
            for (int x = 0; x < sampleWidth; x++) {
                int target = (y * sampleWidth + x) * 3;
                for (int sy = 0; sy < DOWNSAMPLE; sy++) {
                    for (int sx = 0; sx < DOWNSAMPLE; sx++) {
                        int source = ((y * DOWNSAMPLE + sy) * width
                                + x * DOWNSAMPLE + sx) * 4;
                        for (int channel = 0; channel < 3; channel++) {
                            float value = rgba[source + channel];
                            if (!Float.isFinite(value)) {
                                throw new IllegalStateException("nonfinite linear HDR sample at "
                                        + (x * DOWNSAMPLE + sx) + "," + (y * DOWNSAMPLE + sy));
                            }
                            rgb[target + channel] += value / (DOWNSAMPLE * DOWNSAMPLE);
                        }
                    }
                }
            }
        }
        return new Image(sampleWidth, sampleHeight, rgb);
    }

    static void save(Path path, Image image) {
        try {
            Path target = path.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(
                    Files.newOutputStream(target)))) {
                output.writeInt(MAGIC);
                output.writeInt(image.width);
                output.writeInt(image.height);
                for (float component : image.rgb) output.writeFloat(component);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to save linear HDR baseline " + path, failure);
        }
    }

    static Image load(Path path) {
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(
                Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid HDR baseline magic");
            int width = input.readInt();
            int height = input.readInt();
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                throw new IOException("invalid HDR baseline extent");
            }
            float[] rgb = new float[Math.multiplyExact(Math.multiplyExact(width, height), 3)];
            for (int index = 0; index < rgb.length; index++) {
                rgb[index] = input.readFloat();
                if (!Float.isFinite(rgb[index])) throw new IOException("nonfinite HDR baseline component");
            }
            if (input.read() != -1) throw new IOException("trailing HDR baseline data");
            return new Image(width, height, rgb);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to load linear HDR baseline " + path, failure);
        }
    }

    static Result compare(Image expected, Image actual, Path errorMap) {
        Result result = measure(expected, actual, errorMap);
        if (result.rmse > MAX_RMSE || result.p95 > MAX_P95) {
            throw new IllegalStateException("linear HDR baseline exceeded thresholds: " + result
                    + " (rmse <= " + MAX_RMSE + ", p95 <= " + MAX_P95 + ")");
        }
        return result;
    }

    /** Measures a distinct quality-mode reference without treating it as a bitwise replay. */
    static Result measure(Image expected, Image actual, Path errorMap) {
        if (expected.width != actual.width || expected.height != actual.height) {
            throw new IllegalArgumentException("HDR baseline extent mismatch");
        }
        double[] errors = new double[expected.width * expected.height];
        double sumSquares = 0.0;
        for (int pixel = 0; pixel < errors.length; pixel++) {
            int base = pixel * 3;
            double squared = 0.0;
            for (int channel = 0; channel < 3; channel++) {
                double difference = actual.rgb[base + channel] - expected.rgb[base + channel];
                squared += difference * difference;
                sumSquares += difference * difference;
            }
            errors[pixel] = Math.sqrt(squared / 3.0);
        }
        double rmse = Math.sqrt(sumSquares / expected.rgb.length);
        double[] sorted = errors.clone();
        Arrays.sort(sorted);
        double p95 = sorted[(int) Math.ceil(sorted.length * 0.95) - 1];
        if (errorMap != null) writeErrorMap(errorMap, expected.width, expected.height, errors);
        return new Result(rmse, p95);
    }

    private static void writeErrorMap(Path path, int width, int height, double[] errors) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double normalized = Math.min(1.0, errors[y * width + x] / MAX_P95);
                int red = (int) Math.round(normalized * 255.0);
                int green = (int) Math.round(Math.max(0.0, normalized - 0.5) * 256.0);
                image.setRGB(x, height - 1 - y, 0xff000000 | red << 16 | green << 8);
            }
        }
        try {
            Path target = path.toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            if (!ImageIO.write(image, "png", target.toFile())) {
                throw new IOException("PNG writer unavailable");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to write HDR error map " + path, failure);
        }
    }

    record Image(int width, int height, float[] rgb) {
        Image {
            if (width <= 0 || height <= 0 || rgb.length != width * height * 3) {
                throw new IllegalArgumentException("invalid HDR image");
            }
        }
    }

    record Result(double rmse, double p95) { }
}
