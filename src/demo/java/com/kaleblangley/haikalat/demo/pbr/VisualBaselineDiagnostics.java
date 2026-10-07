package com.kaleblangley.haikalat.demo.pbr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit, non-measured volume attachment exports for visual inspection. */
final class VisualBaselineDiagnostics {
    private VisualBaselineDiagnostics() { }

    static void saveOutdoor(Path prefix, float[] volume, float[] rejection,
                            int width, int height) {
        int pixels = Math.multiplyExact(width, height);
        if (width <= 0 || height <= 0 || volume.length != pixels * 4
                || rejection.length != pixels * 4) {
            throw new IllegalArgumentException("outdoor diagnostic attachment extent mismatch");
        }
        BufferedImage scattering = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        BufferedImage transmittance = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        BufferedImage historyRejection = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * width + x) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    if (!Float.isFinite(volume[index + channel])) {
                        throw new IllegalStateException("nonfinite outdoor volume at " + x + "," + y);
                    }
                }
                if (!Float.isFinite(rejection[index])) {
                    throw new IllegalStateException("nonfinite outdoor rejection at " + x + "," + y);
                }
                int red = displayScattering(volume[index]);
                int green = displayScattering(volume[index + 1]);
                int blue = displayScattering(volume[index + 2]);
                int transmission = toByte(volume[index + 3]);
                int rejected = toByte(rejection[index]);
                int targetY = height - 1 - y;
                scattering.setRGB(x, targetY, 0xff000000 | red << 16 | green << 8 | blue);
                transmittance.setRGB(x, targetY, gray(transmission));
                historyRejection.setRGB(x, targetY, gray(rejected));
            }
        }
        write(Path.of(prefix + "-scattering.png"), scattering);
        write(Path.of(prefix + "-transmittance.png"), transmittance);
        write(Path.of(prefix + "-history-rejection.png"), historyRejection);
    }

    private static int displayScattering(float linear) {
        if (linear < -0.0001f) throw new IllegalStateException("negative outdoor scattering");
        double mapped = Math.max(linear, 0.0f) / (1.0 + Math.max(linear, 0.0f));
        return toByte((float) Math.pow(mapped, 1.0 / 2.2));
    }

    private static int toByte(float value) {
        return Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f);
    }

    private static int gray(int value) {
        return 0xff000000 | value << 16 | value << 8 | value;
    }

    private static void write(Path path, BufferedImage image) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            if (!ImageIO.write(image, "png", absolute.toFile())) {
                throw new IOException("PNG writer unavailable");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("failed to write outdoor diagnostic " + path, failure);
        }
    }
}
