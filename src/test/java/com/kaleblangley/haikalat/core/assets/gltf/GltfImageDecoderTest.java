package com.kaleblangley.haikalat.core.assets.gltf;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfImageDecoderTest {
    @Test
    void decodesRgbaOnJvmWithoutOpenGlContext() throws IOException {
        byte[] png = Files.readAllBytes(Path.of(
                "src/main/resources/vfx/masks/kenney/development_essentials/gradient_radial.png"));

        GltfImageData image = GltfImageDecoder.decodeRgba8(png, false);

        assertTrue(image.width() > 0);
        assertTrue(image.height() > 0);
        assertEquals(image.width() * image.height() * 4, image.rgba8().length);
    }
}
