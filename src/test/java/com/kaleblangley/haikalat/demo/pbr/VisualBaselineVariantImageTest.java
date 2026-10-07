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

/** Real rendered comparisons: all three frozen scenes must respond to fog and Bloom controls. */
class VisualBaselineVariantImageTest {
    @Test
    void frozenFogAndBloomVariantsHaveVisiblePixelEffect() throws IOException {
        Path root = Path.of("build/reports/render3d-v0243/visual");
        Path first = root.resolve("forest_morning-0420.png");
        if (Boolean.getBoolean("haikalat.visualVariantQuality.required")) {
            assertTrue(Files.exists(first), "missing required visual baseline: " + first);
        } else {
            assumeTrue(Files.exists(first));
        }
        for (String id : new String[] {"forest_morning", "forest_dusk", "town_night"}) {
            BufferedImage finalImage = read(root.resolve(id + "-0420.png"));
            for (String variant : new String[] {"fog-off", "bloom-off", "gray-model"}) {
                BufferedImage comparison = read(root.resolve("variants")
                        .resolve(id + "-" + variant + "-0420.png"));
                assertEquals(finalImage.getWidth(), comparison.getWidth());
                assertEquals(finalImage.getHeight(), comparison.getHeight());
                int changed = 0;
                for (int y = 0; y < finalImage.getHeight(); y++) {
                    for (int x = 0; x < finalImage.getWidth(); x++) {
                        if ((finalImage.getRGB(x, y) & 0x00ffffff)
                                != (comparison.getRGB(x, y) & 0x00ffffff)) changed++;
                    }
                }
                assertTrue(changed > (variant.equals("bloom-off") ? 1_000 : 10_000),
                        id + " " + variant + " should alter the rendered scene; changed=" + changed);
            }
        }
    }

    private static BufferedImage read(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        assertNotNull(image, "missing PNG: " + path);
        return image;
    }
}
