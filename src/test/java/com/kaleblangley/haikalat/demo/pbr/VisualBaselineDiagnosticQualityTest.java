package com.kaleblangley.haikalat.demo.pbr;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Offline rendered diagnostics and a distinct high-sample, history-off volume oracle. */
class VisualBaselineDiagnosticQualityTest {
    private static final Path VISUAL = Path.of("build/reports/render3d-v0243/visual");

    @Test
    void forestLinearHdrStaysCloseToHighSampleHistoryOffVolume() {
        requireOrSkip(VISUAL.resolve("highsample/forest_morning-0060.h4f.gz"));
        for (String id : new String[] {"forest_morning", "forest_dusk"}) {
            for (int frame : new int[] {60, 240, 420}) {
                String filename = id + "-" + String.format("%04d", frame) + ".h4f.gz";
                VisualBaselineHdr.Image reference = VisualBaselineHdr.load(
                        VISUAL.resolve("highsample").resolve(filename));
                VisualBaselineHdr.Image baseline = VisualBaselineHdr.load(
                        Path.of("config/visual-baselines/reference", filename));
                // Existing linear-HDR gate: this independent volume quality mode must
                // remain close without demanding bitwise identity or an RGBA8 proxy.
                VisualBaselineHdr.Result comparison = VisualBaselineHdr.compare(reference, baseline,
                        VISUAL.resolve("hdr-error/highsample-" + filename + ".png"));
                assertTrue(comparison.rmse() > 0.001,
                        id + " high-sample override must change the linear volume output");
            }
        }
    }

    @Test
    void diagnosticAttachmentsHaveTheExpectedShapeAndDistinctContent() throws IOException {
        Path root = VISUAL.resolve("diagnostics");
        requireOrSkip(root.resolve("forest_morning-0420-scattering.png"));
        for (String id : new String[] {"forest_morning", "forest_dusk"}) {
            for (int frame : new int[] {60, 240, 420}) {
                String prefix = id + "-" + String.format("%04d", frame);
                BufferedImage scattering = read(root.resolve(prefix + "-scattering.png"));
                BufferedImage transmittance = read(root.resolve(prefix + "-transmittance.png"));
                BufferedImage rejection = read(root.resolve(prefix + "-history-rejection.png"));
                assertEquals(640, scattering.getWidth());
                assertEquals(360, scattering.getHeight());
                assertEquals(scattering.getWidth(), transmittance.getWidth());
                assertEquals(scattering.getHeight(), rejection.getHeight());
                assertTrue(channelRange(scattering, 0) > 20,
                        prefix + " scattering should contain structure");
                assertTrue(channelRange(transmittance, 0) > 20,
                        prefix + " transmittance should contain structure");
                assertTrue(isGray(rejection), prefix + " rejection should be a scalar mask");
                if (id.equals("forest_morning") && frame == 420) {
                    assertTrue(channelRange(rejection, 0) > 0,
                            "moving morning path should contain rejected history pixels");
                }
            }
        }
        BufferedImage lightCount = read(root.resolve("town_night-0420-light-count.png"));
        BufferedImage overflow = read(root.resolve("town_night-0420-overflow.png"));
        BufferedImage shadowSlots = read(root.resolve("town_night-0420-shadow-slot.png"));
        assertEquals(1280, lightCount.getWidth());
        assertEquals(720, lightCount.getHeight());
        assertTrue(channelRange(lightCount, 0) > 40);
        assertTrue(pixelDifference(lightCount, overflow) > 10_000);
        assertTrue(pixelDifference(lightCount, shadowSlots) > 10_000);
    }

    private static BufferedImage read(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image, "missing diagnostic PNG: " + path);
        return image;
    }

    private static void requireOrSkip(Path path) {
        if (Boolean.getBoolean("haikalat.visualDiagnosticQuality.required")) {
            assertTrue(Files.exists(path), "missing required diagnostic artifact: " + path);
        } else {
            assumeTrue(Files.exists(path));
        }
    }

    private static int channelRange(BufferedImage image, int channel) {
        int minimum = 255;
        int maximum = 0;
        int shift = (2 - channel) * 8;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int value = (image.getRGB(x, y) >> shift) & 255;
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        return maximum - minimum;
    }

    private static int pixelDifference(BufferedImage first, BufferedImage second) {
        assertEquals(first.getWidth(), second.getWidth());
        assertEquals(first.getHeight(), second.getHeight());
        int changed = 0;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if ((first.getRGB(x, y) & 0xffffff) != (second.getRGB(x, y) & 0xffffff)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static boolean isGray(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 255) != ((rgb >> 8) & 255)
                        || ((rgb >> 8) & 255) != (rgb & 255)) return false;
            }
        }
        return true;
    }
}
