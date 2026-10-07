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
    void rgba32fTypedUploadAndFramebufferRetainPrecisionAndAccountFullStorage() {
        try (GlfwWindow window = hiddenWindow()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var texture = Texture2D.createEmpty(5, 3, org.lwjgl.opengl.GL30.GL_RGBA32F);
                     var framebuffer = com.kaleblangley.haikalat.backend.framebuffer.Framebuffer.fromDescriptor(
                             com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor.builder(5, 3)
                                     .colorTexture(com.kaleblangley.haikalat.backend.RenderFormat.RGBA32F).build())) {
                    float[] expected = {0.12345679f, 2.3456788f, 70000.125f, 0.9876543f};
                    assertEquals(240, texture.requiredRegionBytes(0, 0, 5, 3));
                    var payload = ByteBuffer.allocate(240).order(java.nio.ByteOrder.nativeOrder());
                    for (int pixel = 0; pixel < 15; pixel++) for (float value : expected) payload.putFloat(value);
                    payload.flip();
                    var device = new GlRenderDevice();
                    var commands = device.createCommandBuffer().uploadTextureRegion(texture, 0, 0, 5, 3, payload);
                    for (int byteIndex = 0; byteIndex < payload.capacity(); byteIndex++) payload.put(byteIndex, (byte) 0);
                    device.execute(commands);
                    org.lwjgl.opengl.GL45.glClearNamedFramebufferfv(framebuffer.id(), org.lwjgl.opengl.GL11.GL_COLOR, 0, expected);
                    for (int id : new int[]{texture.id(), framebuffer.colorAttachment()}) {
                        assertEquals(org.lwjgl.opengl.GL30.GL_RGBA32F,
                                org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(id, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_INTERNAL_FORMAT));
                        var pixels = BufferUtils.createFloatBuffer(60);
                        glGetTextureImage(id, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_FLOAT, pixels);
                        for (int index = 0; index < 60; index++) assertEquals(expected[index % 4], pixels.get(index), 0);
                    }
                    assertEquals(480L, GlDebug.resources().liveResources().stream()
                            .filter(resource -> resource.kind().equals("TEXTURE"))
                            .mapToLong(GlDebug.ResourceInfo::estimatedBytes).sum());
                    GlDebug.assertNoError("RGBA32F typed upload, framebuffer and full storage accounting");
                }
                assertEquals(0, GlDebug.resources().liveResources().size());
            }
        }
    }

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
