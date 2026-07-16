package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;

import static com.kaleblangley.haikalat.integration.GlTestSupport.hiddenWindow;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL45.glGetTextureImage;

/** 验证正式 R8 region upload 的像素范围、payload 所有权和 pixel-store 恢复。 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class TextureRegionUploadGlTest {
    @Test
    void r8RegionUploadChangesOnlyTargetAndRestoresUnpackAlignment() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (Texture2D texture = Texture2D.createR8(5, 4)) {
                ByteBuffer empty = ByteBuffer.allocate(20);
                ByteBuffer region = ByteBuffer.wrap(new byte[]{10, 20, 30, 40, 50, 60});
                var commands = device.createCommandBuffer()
                        .enableBlend(true)
                        .uploadTextureRegion(texture, 0, 0, 5, 4, empty)
                        .uploadTextureRegion(texture, 1, 1, 3, 2, region);
                for (int index = 0; index < region.capacity(); index++) {
                    region.put(index, (byte) 99);
                }

                glPixelStorei(GL_UNPACK_ALIGNMENT, 8);
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 9);
                glPixelStorei(GL_UNPACK_SKIP_ROWS, 2);
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, 1);
                device.execute(commands);

                assertEquals(8, glGetInteger(GL_UNPACK_ALIGNMENT));
                assertEquals(9, glGetInteger(GL_UNPACK_ROW_LENGTH));
                assertEquals(2, glGetInteger(GL_UNPACK_SKIP_ROWS));
                assertEquals(1, glGetInteger(GL_UNPACK_SKIP_PIXELS));
                ByteBuffer pixels = readR8(texture);
                assertAll(
                        () -> assertEquals(0, pixel(pixels, 5, 0, 0)),
                        () -> assertEquals(0, pixel(pixels, 5, 4, 3)),
                        () -> assertEquals(10, pixel(pixels, 5, 1, 1)),
                        () -> assertEquals(20, pixel(pixels, 5, 2, 1)),
                        () -> assertEquals(30, pixel(pixels, 5, 3, 1)),
                        () -> assertEquals(40, pixel(pixels, 5, 1, 2)),
                        () -> assertEquals(50, pixel(pixels, 5, 2, 2)),
                        () -> assertEquals(60, pixel(pixels, 5, 3, 2)));
                GlDebug.assertNoError("typed R8 texture region upload");
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
                glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            }
        }
    }

    private static ByteBuffer readR8(Texture2D texture) {
        int previousPack = glGetInteger(GL_PACK_ALIGNMENT);
        ByteBuffer pixels = BufferUtils.createByteBuffer(texture.width() * texture.height());
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glGetTextureImage(texture.id(), 0, GL_RED, GL_UNSIGNED_BYTE, pixels);
            return pixels;
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, previousPack);
        }
    }

    private static int pixel(ByteBuffer pixels, int width, int x, int y) {
        return Byte.toUnsignedInt(pixels.get(y * width + x));
    }
}
